package local.fuselineage.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import local.fuselineage.domain.AlignmentResults;
import local.fuselineage.domain.RawRecords;
import org.springframework.stereotype.Service;

@Service
public class AlignmentService {
  private static final String JSON_SAFE_NULL = "null";

  public Result calculate(RawRecords.RawBundle raw) {
    validateRaw(raw);
    ActiveDecisions active = new ActiveDecisions(raw.decisions());
    Map<String, List<RawRecords.ScannerPoint>> scannerBySession = group(raw.scannerPoints());
    Map<String, List<RawRecords.LaserCommand>> laserBySession = groupLaser(raw.laserCommands());
    List<AlignmentResults.PathSegment> segments = buildSegments(raw.scannerPoints(),
        raw.laserCommands(), active);
    Map<String, CalibrationModel> calibrations = fit(raw.markers(), active);
    List<AlignmentResults.Diagnostic> diagnostics = new ArrayList<>();
    diagnoseChannels(raw, calibrations, diagnostics);

    List<AlignmentResults.Aligned> aligned = new ArrayList<>();
    for (RawRecords.MeltSample sample : raw.meltSamples()) {
      aligned.add(alignSample(sample, calibrations.get(sample.sessionId()), segments,
          laserBySession.getOrDefault(sample.sessionId(), List.of()), active));
    }

    Map<String, List<AlignmentResults.Aligned>> bySegment = aligned.stream()
        .filter(item -> item.segmentId != null)
        .collect(Collectors.groupingBy(item -> item.segmentId, LinkedHashMap::new, Collectors.toList()));
    for (AlignmentResults.PathSegment segment : segments) {
      List<AlignmentResults.Aligned> values = bySegment.getOrDefault(segment.segmentId, List.of());
      double count = values.stream().filter(item -> !item.nonLaser && "ALIGNED".equals(item.status)).count();
      double mean = values.stream()
          .filter(item -> !item.nonLaser && "ALIGNED".equals(item.status))
          .mapToDouble(item -> item.intensity)
          .average().orElse(Double.NaN);
      segment.laser = segment.laser;
      segment.segmentId = segment.segmentId;
    }

    List<AlignmentResults.SegmentResidual> segmentResiduals = buildSegmentResiduals(
        segments, aligned, raw.markers(), active);
    List<AlignmentResults.MarkerResidual> markerResiduals =
        buildMarkerResiduals(raw.markers(), active, calibrations);
    return new Result(segments, aligned, segmentResiduals, markerResiduals, diagnostics,
        new ArrayList<>(calibrations.values()));
  }

  private void validateRaw(RawRecords.RawBundle raw) {
    validateScanner(raw.scannerPoints());
    validateCommands(raw.laserCommands());
    Set<String> markerIds = raw.markers().stream().map(RawRecords.CalibrationMarker::id)
        .collect(Collectors.toSet());
    for (RawRecords.Decision decision : raw.decisions()) {
      if (decision.sequenceNo() < 0) {
        throw new IllegalArgumentException("决策序号不能为负: " + decision.id());
      }
      if ((decision.decisionType().equals("CONFIRM_TROPHY")
          || decision.decisionType().equals("REVOKE_TROPHY"))
          && !markerIds.contains(decision.targetId())) {
        throw new IllegalArgumentException("决策引用了不存在的锦标: " + decision.targetId());
      }
    }
    long last = -1;
    for (RawRecords.Decision decision : raw.decisions().stream()
        .sorted(Comparator.comparingLong(RawRecords.Decision::sequenceNo)).toList()) {
      if (decision.sequenceNo() <= last) {
        throw new IllegalArgumentException("决策序号必须严格递增");
      }
      last = decision.sequenceNo();
    }
  }

  private void validateScanner(List<RawRecords.ScannerPoint> points) {
    Map<String, List<RawRecords.ScannerPoint>> bySession = group(points);
    for (Map.Entry<String, List<RawRecords.ScannerPoint>> entry : bySession.entrySet()) {
      List<RawRecords.ScannerPoint> bySequence = entry.getValue().stream()
          .sorted(Comparator.comparingLong(RawRecords.ScannerPoint::sequenceNo)).toList();
      long previousSequence = Long.MIN_VALUE;
      long previousClockBySequence = Long.MIN_VALUE;
      for (RawRecords.ScannerPoint point : bySequence) {
        if (point.sequenceNo() < previousSequence) {
          throw new IllegalArgumentException("同一会话扫描器序号必须单调: " + entry.getKey());
        }
        previousSequence = point.sequenceNo();
        if (point.scannerClock() < previousClockBySequence) {
          throw new IllegalArgumentException("同一会话扫描器时钟必须单调: " + entry.getKey());
        }
        previousClockBySequence = point.scannerClock();
      }
      long previousClock = Long.MIN_VALUE;
      for (RawRecords.ScannerPoint point : entry.getValue()) {
        if (point.scannerClock() < previousClock) {
          throw new IllegalArgumentException("同一会话扫描器时钟必须单调: " + entry.getKey());
        }
        previousClock = point.scannerClock();
      }
    }
  }

  private void validateCommands(List<RawRecords.LaserCommand> commands) {
    for (Map.Entry<String, List<RawRecords.LaserCommand>> entry : groupLaser(commands).entrySet()) {
      long previous = Long.MIN_VALUE;
      for (RawRecords.LaserCommand command : entry.getValue()) {
        if (command.scannerClock() < previous) {
          throw new IllegalArgumentException("激光指令时钟必须单调: " + entry.getKey());
        }
        previous = command.scannerClock();
      }
    }
  }

  private Map<String, List<RawRecords.ScannerPoint>> group(List<RawRecords.ScannerPoint> points) {
    return points.stream().collect(Collectors.groupingBy(RawRecords.ScannerPoint::sessionId,
        LinkedHashMap::new, Collectors.toList()));
  }

  private Map<String, List<RawRecords.LaserCommand>> groupLaser(List<RawRecords.LaserCommand> commands) {
    return commands.stream().collect(Collectors.groupingBy(RawRecords.LaserCommand::sessionId,
        LinkedHashMap::new, Collectors.toList()));
  }

  private Map<String, CalibrationModel> fit(List<RawRecords.CalibrationMarker> markers,
      ActiveDecisions active) {
    Map<String, List<RawRecords.CalibrationMarker>> bySession = markers.stream()
        .filter(active::confirmed)
        .collect(Collectors.groupingBy(RawRecords.CalibrationMarker::sessionId,
            LinkedHashMap::new, Collectors.toList()));
    Map<String, CalibrationModel> result = new LinkedHashMap<>();
    for (Map.Entry<String, List<RawRecords.CalibrationMarker>> entry : bySession.entrySet()) {
      List<RawRecords.CalibrationMarker> values = entry.getValue().stream()
          .sorted(Comparator.comparingLong(RawRecords.CalibrationMarker::scannerClock)).toList();
      if (values.size() == 1) {
        RawRecords.CalibrationMarker marker = values.get(0);
        result.put(entry.getKey(), new CalibrationModel(entry.getKey(), 1.0,
            marker.sensorClock() - marker.scannerClock(), marker.scannerClock(),
            marker.scannerClock(), 0L, values, true));
      } else if (values.size() >= 2) {
        RawRecords.CalibrationMarker first = values.get(0);
        RawRecords.CalibrationMarker last = values.get(values.size() - 1);
        double slope = (double) (last.sensorClock() - first.sensorClock())
            / (last.scannerClock() - first.scannerClock());
        double intercept = first.sensorClock() - slope * first.scannerClock();
        result.put(entry.getKey(), new CalibrationModel(entry.getKey(), slope, intercept,
            first.scannerClock(), last.scannerClock(), last.scannerClock() - first.scannerClock(),
            values, false));
      }
    }
    return result;
  }

  private List<AlignmentResults.PathSegment> buildSegments(List<RawRecords.ScannerPoint> points,
      List<RawRecords.LaserCommand> commands, ActiveDecisions active) {
    List<AlignmentResults.PathSegment> result = new ArrayList<>();
    for (String sessionId : points.stream().map(RawRecords.ScannerPoint::sessionId)
        .distinct().toList()) {
      List<RawRecords.ScannerPoint> sessionPoints = points.stream()
          .filter(point -> point.sessionId().equals(sessionId))
          .sorted(Comparator.comparingLong(RawRecords.ScannerPoint::scannerClock)).toList();
      List<RawEdges.Edge> edges = RawEdges.from(sessionPoints);
      for (RawEdges.Edge edge : edges) {
        List<Long> cuts = new ArrayList<>();
        cuts.add(edge.startClock());
        cuts.add(edge.endClock());
        commands.stream()
            .filter(command -> command.sessionId().equals(sessionId))
            .map(RawRecords.LaserCommand::scannerClock)
            .filter(clock -> clock > edge.startClock() && clock < edge.endClock())
            .forEach(cuts::add);
        active.splitClocks(sessionId).stream()
            .filter(clock -> clock > edge.startClock() && clock < edge.endClock())
            .forEach(cuts::add);
        cuts.sort(Long::compare);
        for (int index = 1; index < cuts.size(); index++) {
          long start = cuts.get(index - 1);
          long end = cuts.get(index);
          AlignmentResults.PathSegment segment = new AlignmentResults.PathSegment();
          segment.sessionId = sessionId;
          segment.layerId = interpolateLayer(sessionPoints, edge, start);
          segment.vectorId = interpolateVector(sessionPoints, edge, start);
          segment.partIndex = index - 1;
          segment.startClock = start;
          segment.endClock = end;
          segment.x1 = interpolateCoordinate(sessionPoints, edge, start, true);
          segment.y1 = interpolateCoordinate(sessionPoints, edge, start, false);
          segment.x2 = interpolateCoordinate(sessionPoints, edge, end, true);
          segment.y2 = interpolateCoordinate(sessionPoints, edge, end, false);
          segment.z = interpolateZ(sessionPoints, edge, start);
          segment.laser = isLaserOn(commands, sessionId, start);
          segment.segmentId = sessionId + "-" + segment.layerId + "-"
              + segment.vectorId + "-P" + result.size() + "-" + segment.startClock;
          result.add(segment);
        }
      }
    }
    return result;
  }

  private String interpolateLayer(List<RawRecords.ScannerPoint> points, RawEdges.Edge edge, long clock) {
    return clock < edge.endClock() ? edge.startLayer() : edge.endLayer();
  }

  private String interpolateVector(List<RawRecords.ScannerPoint> points, RawEdges.Edge edge, long clock) {
    return clock < edge.endClock() ? edge.startVector() : edge.endVector();
  }

  private double interpolateCoordinate(List<RawRecords.ScannerPoint> points, RawEdges.Edge edge,
      long clock, boolean x) {
    if (clock == edge.endClock()) {
      return x ? edge.endX() : edge.endY();
    }
    double ratio = (double) (clock - edge.startClock()) / (edge.endClock() - edge.startClock());
    double start = x ? edge.startX() : edge.startY();
    double end = x ? edge.endX() : edge.endY();
    return start + (end - start) * ratio;
  }

  private double interpolateZ(List<RawRecords.ScannerPoint> points, RawEdges.Edge edge, long clock) {
    return clock < edge.endClock() ? edge.startZ() : edge.endZ();
  }

  private boolean isLaserOn(List<RawRecords.LaserCommand> allCommands, String sessionId, long clock) {
    return allCommands.stream()
        .filter(command -> command.sessionId().equals(sessionId))
        .filter(command -> command.scannerClock() <= clock)
        .max(Comparator.comparingLong(RawRecords.LaserCommand::scannerClock))
        .map(command -> "ON".equals(command.state()))
        .orElse(false);
  }

  private AlignmentResults.Aligned alignSample(RawRecords.MeltSample sample,
      CalibrationModel model, List<AlignmentResults.PathSegment> segments,
      List<RawRecords.LaserCommand> sessionCommands, ActiveDecisions active) {
    AlignmentResults.Aligned result = new AlignmentResults.Aligned();
    result.meltSampleId = sample.id();
    result.sessionId = sample.sessionId();
    result.sensorClock = sample.sensorClock();
    result.intensity = sample.intensity();
    result.nonLaser = false;
    result.riskCodes = "";
    result.evidence = "{}";
    if (active.manuallyUnaligned(sample.sessionId(), sample.sensorClock())) {
      result.status = "MANUAL_UNALIGNED";
      result.riskCodes = "MANUALLY_KEPT_UNALIGNED";
      result.evidence = "{\"decision\":\"KEEP_UNALIGNED\"}";
      return result;
    }
    if (model == null) {
      result.status = "UNCALIBRATED";
      result.riskCodes = "NO_ACTIVE_TROPHY";
      result.evidence = "{\"reason\":\"会话内没有已确认同步锦标\"}";
      return result;
    }
    if (model.singleMarker()) {
      result.status = "UNCALIBRATED";
      result.riskCodes = "SINGLE_TROPHY;CLOCK_FIT_NOT_REPLAYABLE";
      result.evidence = "{\"reason\":\"仅有一个有效锦标，无法确定漂移斜率\"}";
      return result;
    }
    double mappedClock = model.scannerAt(sample.sensorClock());
    double lowerSensor = model.sensorAt(model.minClock());
    double upperSensor = model.sensorAt(model.maxClock());
    double distanceSensor = Math.max(0,
        Math.max(lowerSensor - sample.sensorClock(), sample.sensorClock() - upperSensor));
    double distanceClock = distanceSensor / Math.abs(model.slope());
    boolean withinBracket = sample.sensorClock() >= Math.round(Math.ceil(lowerSensor))
        && sample.sensorClock() <= Math.round(Math.floor(upperSensor));
    boolean withinOneInterval = distanceClock <= model.interval();
    if (!withinOneInterval) {
      result.status = "OUT_OF_CALIBRATION";
      result.riskCodes = "EXTRAPOLATION_OVER_ONE_TROPHY_INTERVAL";
      result.mappedScannerClock = null;
      result.evidence = String.format(
          "{\"distance\":%.6f,\"interval\":%d,\"policy\":\"risk-only-no-position\"}",
          distanceClock, model.interval());
      return result;
    }

    AlignmentResults.PathSegment segment = findSegment(segments, sample.sessionId(), mappedClock);
    result.mappedScannerClock = mappedClock;
    if (segment == null) {
      result.status = withinBracket ? "PATH_GAP" : "EXTRAPOLATION_RISK";
      result.riskCodes = withinBracket ? "NO_PATH_SEGMENT"
          : "EXTRAPOLATION_WITHIN_ONE_TROPHY_INTERVAL";
      return result;
    }
    result.layerId = segment.layerId;
    result.segmentId = segment.segmentId;
    result.vectorId = segment.vectorId;
    result.x = interpolateSegment(segment, mappedClock, true);
    result.y = interpolateSegment(segment, mappedClock, false);
    result.z = segment.z;
    result.positionResidual = 0.0;
    result.clockResidual = 0.0;
    result.nonLaser = !segment.laser;
    if (!withinBracket) {
      result.status = "EXTRAPOLATION_RISK";
      result.riskCodes = "EXTRAPOLATION_WITHIN_ONE_TROPHY_INTERVAL";
    } else if (result.nonLaser) {
      result.status = "NON_LASER";
      result.riskCodes = "NON_LASER_MOVEMENT";
    } else {
      result.status = "ALIGNED";
    }
    result.evidence = String.format(
        "{\"slope\":%.12f,\"intercept\":%.12f,\"interval\":%d,\"bracket\":[%d,%d]}",
        model.slope(), model.intercept(), model.interval(), model.minClock(), model.maxClock());
    return result;
  }

  private AlignmentResults.PathSegment findSegment(List<AlignmentResults.PathSegment> segments,
      String sessionId, double clock) {
    return segments.stream()
        .filter(segment -> segment.sessionId.equals(sessionId))
        .filter(segment -> clock >= segment.startClock && clock < segment.endClock)
        .findFirst()
        .orElse(null);
  }

  private double interpolateSegment(AlignmentResults.PathSegment segment, double clock, boolean x) {
    if (segment.endClock == segment.startClock) {
      return x ? segment.x1 : segment.y1;
    }
    double ratio = (clock - segment.startClock) / (segment.endClock - segment.startClock);
    double start = x ? segment.x1 : segment.y1;
    double end = x ? segment.x2 : segment.y2;
    return start + (end - start) * ratio;
  }

  private List<AlignmentResults.SegmentResidual> buildSegmentResiduals(
      List<AlignmentResults.PathSegment> segments, List<AlignmentResults.Aligned> aligned,
      List<RawRecords.CalibrationMarker> markers, ActiveDecisions active) {
    List<AlignmentResults.SegmentResidual> result = new ArrayList<>();
    for (AlignmentResults.PathSegment segment : segments) {
      List<AlignmentResults.Aligned> values = aligned.stream()
          .filter(item -> segment.segmentId.equals(item.segmentId))
          .filter(item -> "ALIGNED".equals(item.status))
          .filter(item -> !item.nonLaser)
          .toList();
      double clockRms = Math.sqrt(values.stream()
          .map(item -> item.clockResidual == null ? 0.0 : item.clockResidual)
          .mapToDouble(value -> value * value).average().orElse(0.0));
      double positionRms = Math.sqrt(values.stream()
          .map(item -> item.positionResidual == null ? 0.0 : item.positionResidual)
          .mapToDouble(value -> value * value).average().orElse(0.0));
      int markerCount = (int) markers.stream()
          .filter(active::confirmed)
          .filter(marker -> marker.sessionId().equals(segment.sessionId))
          .filter(marker -> marker.layerId().equals(segment.layerId))
          .filter(marker -> marker.scannerClock() >= segment.startClock
              && marker.scannerClock() < segment.endClock)
          .count();
      result.add(new AlignmentResults.SegmentResidual(segment.segmentId, markerCount,
          values.size(), clockRms, positionRms,
          "{\"policy\":\"deterministic-fixture-linear-model;non-laser-excluded\"}"));
    }
    return result;
  }

  private List<AlignmentResults.MarkerResidual> buildMarkerResiduals(
      List<RawRecords.CalibrationMarker> markers, ActiveDecisions active,
      Map<String, CalibrationModel> calibrations) {
    List<AlignmentResults.MarkerResidual> result = new ArrayList<>();
    for (RawRecords.CalibrationMarker marker : markers) {
      boolean isActive = active.confirmed(marker);
      CalibrationModel model = calibrations.get(marker.sessionId());
      Double predicted = isActive && model != null && !model.singleMarker()
          ? model.sensorAt(marker.scannerClock()) : null;
      Double residual = predicted == null ? null : predicted - marker.sensorClock();
      String evidence = active.evidenceFor(marker.id());
      result.add(new AlignmentResults.MarkerResidual(marker.id(), marker.sessionId(),
          marker.layerId(), isActive, marker.sensorClock(), predicted, residual,
          evidence));
    }
    return result;
  }

  private void diagnoseChannels(RawRecords.RawBundle raw, Map<String, CalibrationModel> calibrations,
      List<AlignmentResults.Diagnostic> diagnostics) {
    Set<String> scannerSessions = raw.scannerPoints().stream()
        .map(RawRecords.ScannerPoint::sessionId).collect(Collectors.toSet());
    Set<String> meltSessions = raw.meltSamples().stream()
        .map(RawRecords.MeltSample::sessionId).collect(Collectors.toSet());
    Set<String> laserSessions = raw.laserCommands().stream()
        .map(RawRecords.LaserCommand::sessionId).collect(Collectors.toSet());
    Set<String> eventSessions = raw.machineEvents().stream()
        .map(RawRecords.MachineEvent::sessionId).collect(Collectors.toSet());
    Set<String> all = new LinkedHashSet<>();
    all.addAll(scannerSessions);
    all.addAll(meltSessions);
    all.addAll(laserSessions);
    all.addAll(eventSessions);
    for (String sessionId : all) {
      addMissing(diagnostics, scannerSessions, sessionId, "scanner");
      addMissing(diagnostics, meltSessions, sessionId, "melt");
      addMissing(diagnostics, laserSessions, sessionId, "laser");
      addMissing(diagnostics, eventSessions, sessionId, "event");
      CalibrationModel model = calibrations.get(sessionId);
      if (!meltSessions.contains(sessionId)) {
        continue;
      }
      if (model == null) {
        diagnostics.add(new AlignmentResults.Diagnostic("ERROR", "NO_CALIBRATION", "marker",
            sessionId, null, "会话内没有有效同步锦标，无法建立时钟映射",
            "{\"policy\":\"session-boundary\"}"));
      } else if (model.singleMarker()) {
        diagnostics.add(new AlignmentResults.Diagnostic("WARN", "SINGLE_TROPHY", "marker",
            sessionId, null, "只剩一个有效锦标；撤销后不产生精确对齐",
            "{\"markerCount\":1}"));
      }
    }
    for (RawRecords.MachineEvent event : raw.machineEvents()) {
      if ("RESTARTED".equals(event.eventType())) {
        diagnostics.add(new AlignmentResults.Diagnostic("INFO", "RESTART_BOUNDARY", "event",
            event.sessionId(), null, "设备重启：计数从零开始，禁止与旧会话直接连接",
            event.payload()));
      }
    }
  }

  private void addMissing(List<AlignmentResults.Diagnostic> diagnostics, Set<String> present,
      String sessionId, String channel) {
    if (!present.contains(sessionId)) {
      diagnostics.add(new AlignmentResults.Diagnostic("WARN", "MISSING_" + channel.toUpperCase()
          + "_CHANNEL", channel, sessionId, null,
          "会话缺少 " + channel + " 通道", "{\"sessionId\":\"" + sessionId + "\"}"));
    }
  }

  public record Result(
      List<AlignmentResults.PathSegment> segments,
      List<AlignmentResults.Aligned> aligned,
      List<AlignmentResults.SegmentResidual> segmentResiduals,
      List<AlignmentResults.MarkerResidual> markerResiduals,
      List<AlignmentResults.Diagnostic> diagnostics,
      List<CalibrationModel> calibrations) {
  }

  public record CalibrationModel(
      String sessionId, double slope, double intercept, long minClock, long maxClock,
      long interval, List<RawRecords.CalibrationMarker> markers, boolean singleMarker) {
    double sensorAt(long scannerClock) {
      return slope * scannerClock + intercept;
    }

    double scannerAt(long sensorClock) {
      return (sensorClock - intercept) / slope;
    }
  }

  private static final class ActiveDecisions {
    private final Set<String> revoked = new LinkedHashSet<>();
    private final Map<String, List<Long>> splits = new LinkedHashMap<>();
    private final List<long[]> unalignedRanges = new ArrayList<>();
    private final Map<String, List<Long>> histories = new LinkedHashMap<>();

    private ActiveDecisions(List<RawRecords.Decision> decisions) {
      for (RawRecords.Decision decision : decisions.stream()
          .sorted(Comparator.comparingLong(RawRecords.Decision::sequenceNo)).toList()) {
        switch (decision.decisionType()) {
          case "REVOKE_TROPHY" -> {
            revoked.add(decision.targetId());
            histories.computeIfAbsent(decision.targetId(), key -> new ArrayList<>()).add(0L);
          }
          case "CONFIRM_TROPHY" -> {
            revoked.remove(decision.targetId());
            histories.computeIfAbsent(decision.targetId(), key -> new ArrayList<>()).add(1L);
          }
          case "SPLIT_TRACK" -> splits.computeIfAbsent(decision.sessionId(), key -> new ArrayList<>())
              .add(decision.scannerClock());
          case "KEEP_UNALIGNED" -> unalignedRanges.add(new long[] {
              decision.sensorClockStart(), decision.sensorClockEnd()});
          default -> throw new IllegalArgumentException("未知决策类型: " + decision.decisionType());
        }
      }
    }

    private boolean confirmed(RawRecords.CalibrationMarker marker) {
      return !revoked.contains(marker.id());
    }

    private String evidenceFor(String markerId) {
      long confirms = histories.getOrDefault(markerId, List.of()).stream()
          .filter(value -> value == 1L).count();
      long revokes = histories.getOrDefault(markerId, List.of()).stream()
          .filter(value -> value == 0L).count();
      Long last = histories.getOrDefault(markerId, List.of()).stream()
          .reduce((first, second) -> second).orElse(null);
      return String.format(
          "{\"markerId\":\"%s\",\"confirmations\":%d,\"revocations\":%d,\"latestAction\":\"%s\"}",
          markerId, confirms, revokes, last != null && last == 1L ? "CONFIRM_TROPHY"
              : revokes > 0 ? "REVOKE_TROPHY" : "NONE");
    }

    private List<Long> splitClocks(String sessionId) {
      return splits.getOrDefault(sessionId, List.of());
    }

    private boolean manuallyUnaligned(String sessionId, long sensorClock) {
      return unalignedRanges.stream().anyMatch(range -> sensorClock >= range[0]
          && sensorClock < range[1]);
    }
  }
}
