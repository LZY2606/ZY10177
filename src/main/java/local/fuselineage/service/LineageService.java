package local.fuselineage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import local.fuselineage.domain.AlignmentResults;
import local.fuselineage.domain.RawRecords;
import local.fuselineage.fixture.FixtureService;
import local.fuselineage.repository.LineageRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LineageService {
  private final LineageRepository repository;
  private final FixtureService fixtureService;
  private final AlignmentService alignmentService;
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public LineageService(LineageRepository repository, FixtureService fixtureService,
      AlignmentService alignmentService, JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.repository = repository;
    this.fixtureService = fixtureService;
    this.alignmentService = alignmentService;
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public Map<String, Object> loadFixture() {
    RawRecords.RawBundle bundle = fixtureService.baselineBundle();
    repository.replaceRawBundle(bundle);
    return recompute("加载固定 fixture");
  }

  @Transactional
  public Map<String, Object> resetDatabase() {
    repository.clearAll();
    return state();
  }

  @Transactional
  public Map<String, Object> importBundle(RawRecords.RawBundle bundle) {
    repository.replaceRawBundle(bundle);
    return recompute("导入运行记录");
  }

  @Transactional
  public Map<String, Object> addDecision(Map<String, Object> request) {
    RawRecords.RawBundle current = repository.rawBundle();
    String type = String.valueOf(request.get("decisionType"));
    String targetId = stringValue(request.get("targetId"));
    String sessionId = stringValue(request.get("sessionId"));
    String layerId = stringValue(request.get("layerId"));
    Long scannerClock = longValue(request.get("scannerClock"));
    Long sensorStart = longValue(request.get("sensorClockStart"));
    Long sensorEnd = longValue(request.get("sensorClockEnd"));
    String reason = stringValue(request.get("reason"));
    if (reason == null || reason.isBlank()) {
      reason = "页面复核操作";
    }
    if (current.scannerPoints().isEmpty()) {
      throw new IllegalArgumentException("数据库为空，请先导入运行记录或装载 fixture");
    }
    if ("SPLIT_TRACK".equals(type)
        && (sessionId == null || scannerClock == null)) {
      throw new IllegalArgumentException("拆分道次必须提供 sessionId 与 scannerClock");
    }
    if ("KEEP_UNALIGNED".equals(type)
        && (sessionId == null || sensorStart == null || sensorEnd == null
        || sensorEnd <= sensorStart)) {
      throw new IllegalArgumentException("保留未对齐必须提供左闭右开且起止有效的传感器区间");
    }
    if (!List.of("CONFIRM_TROPHY", "REVOKE_TROPHY", "SPLIT_TRACK", "KEEP_UNALIGNED").contains(type)) {
      throw new IllegalArgumentException("未知决策类型: " + type);
    }
    long nextSequence = current.decisions().stream()
        .mapToLong(RawRecords.Decision::sequenceNo).max().orElse(-1) + 1;
    String id = "D-" + nextSequence + "-" + type.toLowerCase();
    String payload;
    try {
      payload = objectMapper.writeValueAsString(request.getOrDefault("payload", Map.of()));
    } catch (Exception exception) {
      throw new IllegalArgumentException("决策 payload 不是有效 JSON", exception);
    }
    RawRecords.Decision decision = new RawRecords.Decision(id, type, targetId, sessionId,
        layerId, scannerClock, sensorStart, sensorEnd, reason, payload,
        Instant.now().toString(), nextSequence);
    RawRecords.RawBundle candidate = new RawRecords.RawBundle(current.scannerPoints(),
        current.laserCommands(), current.meltSamples(), current.machineEvents(),
        current.markers(), plus(current.decisions(), decision));
    alignmentService.calculate(candidate);
    repository.insertDecision(decision);
    return recompute("追加决策 " + type);
  }

  @Transactional
  public Map<String, Object> recompute(String reason) {
    RawRecords.RawBundle raw = repository.rawBundle();
    AlignmentService.Result result = alignmentService.calculate(raw);
    String headId = raw.decisions().stream()
        .max(java.util.Comparator.comparingLong(RawRecords.Decision::sequenceNo))
        .map(RawRecords.Decision::id).orElse(null);
    long versionId = repository.saveVersion(reason, headId, raw.decisions().size(),
        FixtureService.FIXTURE_REVISION);
    repository.saveResult(versionId, null, result.segments(), result.aligned(),
        result.segmentResiduals(), result.markerResiduals(), result.diagnostics());
    return state();
  }

  public Map<String, Object> exportRun() {
    return Map.of(
        "exportedAt", Instant.now().toString(),
        "fixtureRevision", FixtureService.FIXTURE_REVISION,
        "raw", repository.rawBundle(),
        "state", state());
  }

  public Map<String, Object> state() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("rawCounts", counts());
    result.put("scannerPoints", repository.scannerPoints());
    result.put("laserCommands", repository.laserCommands());
    result.put("meltSamples", repository.meltSamples());
    result.put("machineEvents", repository.machineEvents());
    result.put("markers", repository.markers());
    result.put("decisions", repository.decisions());
    result.put("latestVersion", latestVersion());
    result.put("segments", latestRows("track_segment", "segment_id"));
    result.put("alignedSamples", latestRows("aligned_sample", "melt_sample_id"));
    result.put("segmentResiduals", latestRows("segment_residual", "segment_id"));
    result.put("markerResiduals", latestRows("marker_residual", "marker_id"));
    result.put("diagnostics", latestRows("diagnostic", "code"));
    return result;
  }

  private Map<String, Object> latestVersion() {
    try {
      return jdbc.queryForMap("SELECT * FROM alignment_version WHERE id=(SELECT MAX(id) FROM alignment_version)");
    } catch (Exception exception) {
      return Map.of();
    }
  }

  private List<Map<String, Object>> latestRows(String table, String orderColumn) {
    Long versionId;
    try {
      versionId = jdbc.queryForObject("SELECT MAX(id) FROM alignment_version", Long.class);
    } catch (Exception exception) {
      versionId = null;
    }
    if (versionId == null) {
      return List.of();
    }
    return jdbc.queryForList("SELECT * FROM " + table + " WHERE version_id=? ORDER BY id",
        versionId);
  }

  private Map<String, Integer> counts() {
    return Map.of(
        "scannerPoints", repository.scannerPoints().size(),
        "laserCommands", repository.laserCommands().size(),
        "meltSamples", repository.meltSamples().size(),
        "machineEvents", repository.machineEvents().size(),
        "markers", repository.markers().size(),
        "decisions", repository.decisions().size());
  }

  private List<RawRecords.Decision> plus(List<RawRecords.Decision> values,
      RawRecords.Decision value) {
    return java.util.stream.Stream.concat(values.stream(), java.util.stream.Stream.of(value)).toList();
  }

  private String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private Long longValue(Object value) {
    if (value == null || String.valueOf(value).isBlank()) {
      return null;
    }
    return ((Number) value).longValue();
  }
}
