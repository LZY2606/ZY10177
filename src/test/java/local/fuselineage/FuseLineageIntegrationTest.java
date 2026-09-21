package local.fuselineage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import local.fuselineage.fixture.FixtureService;
import local.fuselineage.repository.LineageRepository;
import local.fuselineage.service.LineageService;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FuseLineageIntegrationTest {
  @LocalServerPort
  int port;

  @Autowired
  TestRestTemplate rest;

  @Autowired
  LineageService lineageService;

  @Autowired
  LineageRepository repository;

  @Autowired
  FixtureService fixtureService;

  @BeforeEach
  void resetFixture() {
    lineageService.loadFixture();
  }

  @Test
  void pageShowsChineseTitle() {
    String html = rest.getForObject(url("/"), String.class);
    assertThat(html).contains("熔痕谱系");
  }

  @Test
  void fixtureAlignsBoundaryWithoutDoubleCountingAndKeepsOffLaserVisible() {
    Map<String, Object> state = lineageService.state();
    List<Map<String, Object>> samples = alignedRows(state);

    Map<String, Object> boundaryLaser = findSample(samples, "S1-M009");
    Map<String, Object> boundaryOff = findSample(samples, "S1-M010");
    assertThat(boundaryLaser.get("status")).isEqualTo("ALIGNED");
    assertThat(boundaryLaser.get("is_non_laser")).isEqualTo(0);
    assertThat(boundaryOff.get("status")).isEqualTo("NON_LASER");
    assertThat(boundaryOff.get("is_non_laser")).isEqualTo(1);
    assertThat(boundaryOff.get("segment_id")).isNotNull();
    assertThat(boundaryLaser.get("segment_id")).isNotEqualTo(boundaryOff.get("segment_id"));

    long countedSamples = samples.stream()
        .filter(row -> "ALIGNED".equals(row.get("status")))
        .filter(row -> Integer.valueOf(0).equals(row.get("is_non_laser")))
        .count();
    long segmentCountSum = segmentRows(state).stream()
        .mapToLong(row -> ((Number) row.get("sample_count")).longValue())
        .sum();
    assertThat(segmentCountSum).isEqualTo(countedSamples);
  }

  @Test
  void revokingOneTrophyRemovesPreciseAlignmentButKeepsRawSamples() {
    long rawBefore = repository.countRawSamples();

    lineageService.addDecision(Map.of(
        "decisionType", "REVOKE_TROPHY",
        "targetId", "T2",
        "reason", "验收撤销 T2"));
    Map<String, Object> state = lineageService.state();
    List<Map<String, Object>> samples = alignedRows(state);

    assertThat(findSample(samples, "S1-M002").get("status")).isEqualTo("UNCALIBRATED");
    assertThat(findSample(samples, "S1-M002").get("x")).isNull();
    assertThat(findSample(samples, "S2-M000").get("status")).isEqualTo("UNCALIBRATED");
    assertThat(repository.countRawSamples()).isEqualTo(rawBefore);
    assertThat(repository.rawBundle().markers()).hasSize(2);
    assertThat(repository.rawBundle().decisions())
        .anyMatch(decision -> "REVOKE_TROPHY".equals(decision.decisionType()));
    assertThat(diagnosticCodes(state)).contains("SINGLE_TROPHY");
  }

  @Test
  void extrapolationBeyondOneIntervalIsRiskOnlyWithoutForgedPosition() {
    Map<String, Object> state = lineageService.state();
    Map<String, Object> risk = findSample(alignedRows(state), "S1-M011");

    assertThat(risk.get("status")).isEqualTo("OUT_OF_CALIBRATION");
    assertThat(risk.get("risk_codes")).isEqualTo("EXTRAPOLATION_OVER_ONE_TROPHY_INTERVAL");
    assertThat(risk.get("x")).isNull();
    assertThat(risk.get("segment_id")).isNull();
    assertThat(findSample(alignedRows(state), "S1-M008").get("status"))
        .isEqualTo("EXTRAPOLATION_RISK");
  }

  @Test
  void splitDecisionCreatesSeparateHalfOpenTracks() {
    lineageService.addDecision(Map.of(
        "decisionType", "SPLIT_TRACK",
        "sessionId", "S1",
        "scannerClock", 1000,
        "reason", "验收拆分道次"));
    Map<String, Object> state = lineageService.state();
    long segmentsAtBoundary = segmentRows(state).stream()
        .filter(row -> "S1".equals(row.get("session_id")))
        .filter(row -> ((Number) row.get("start_scanner_clock")).longValue() == 1000L
            || ((Number) row.get("end_scanner_clock")).longValue() == 1000L)
        .count();
    assertThat(segmentsAtBoundary).isEqualTo(2);
  }

  @Test
  void exportCanBeImportedAfterResetAndProducesSameStatuses() {
    Object export = lineageService.exportRun().get("raw");
    lineageService.resetDatabase();
    assertThat(lineageService.state().get("alignedSamples")).isEqualTo(List.of());

    lineageService.importBundle(fixtureService.baselineBundle());
    Map<String, Object> reimported = lineageService.state();
    assertThat(findSample(alignedRows(reimported), "S1-M009").get("status")).isEqualTo("ALIGNED");
    assertThat(findSample(alignedRows(reimported), "S1-M011").get("status"))
        .isEqualTo("OUT_OF_CALIBRATION");
    assertThat(export).isNotNull();
  }

  @Test
  void restartSessionsAreNeverJoined() {
    Map<String, Object> state = lineageService.state();
    assertThat(diagnosticCodes(state)).contains("RESTART_BOUNDARY");
    Map<String, Object> restartedSession = findSample(alignedRows(state), "S2-M000");
    assertThat(restartedSession.get("status")).isEqualTo("UNCALIBRATED");
    assertThat(restartedSession.get("segment_id")).isNull();
  }

  @Test
  void nonMonotonicScannerClockIsRejected() {
    try {
      var bundle = fixtureService.baselineBundle();
      var first = bundle.scannerPoints().get(0);
      var second = bundle.scannerPoints().get(1);
      var backward = new local.fuselineage.domain.RawRecords.ScannerPoint(
          second.id(), second.sessionId(), second.layerId(), second.sequenceNo(),
          first.scannerClock() - 100L, second.x(), second.y(), second.z(), second.vectorId());
      var invalid = new local.fuselineage.domain.RawRecords.RawBundle(
          java.util.stream.Stream.concat(java.util.stream.Stream.of(first, backward),
              bundle.scannerPoints().stream().skip(2)).toList(),
          bundle.laserCommands(), bundle.meltSamples(), bundle.machineEvents(),
          bundle.markers(), bundle.decisions());
      lineageService.importBundle(invalid);
      throw new AssertionError("必须拒绝非单调扫描器时钟");
    } catch (IllegalArgumentException expected) {
      assertThat(expected.getMessage()).contains("扫描器时钟");
    }
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> alignedRows(Map<String, Object> state) {
    return (List<Map<String, Object>>) state.get("alignedSamples");
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> segmentRows(Map<String, Object> state) {
    return (List<Map<String, Object>>) state.get("segments");
  }

  @SuppressWarnings("unchecked")
  private List<String> diagnosticCodes(Map<String, Object> state) {
    return ((List<Map<String, Object>>) state.get("diagnostics")).stream()
        .map(row -> String.valueOf(row.get("code"))).toList();
  }

  private Map<String, Object> findSample(List<Map<String, Object>> rows, String sampleId) {
    return rows.stream().filter(row -> sampleId.equals(row.get("melt_sample_id")))
        .findFirst().orElseThrow();
  }

  private String url(String path) {
    return "http://127.0.0.1:" + port + path;
  }
}
