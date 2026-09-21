package com.example.meltlineage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/test-acceptance.db",
        "spring.datasource.hikari.maximum-pool-size=1"
})
class AlignmentAcceptanceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired Db db;
    @Autowired FixtureImporter importer;
    @Autowired AlignmentService alignment;
    @Autowired DecisionService decisions;
    @Autowired ApiController api;
    ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void freshImport() throws Exception {
        importer.importFixture();
        alignment.recompute(AlignmentService.ALGO_V1, "test");
    }

    long version() { return alignment.currentVersionId(); }

    @Test
    void revokeAnchorYieldsRiskNotPreciseAlignment() throws Exception {
        // baseline: meltpool has two anchors; far extrapolation beyond one interval is risk
        Integer baselineRisk = jdbc.queryForObject("""
            SELECT COUNT(*) FROM aligned_sample a JOIN sample s ON s.id=a.sample_id
            WHERE a.version_id=? AND s.channel='meltpool' AND a.risk=1""", Integer.class, version());
        assertNotNull(baselineRisk);
        assertTrue(baselineRisk > 0, "fixture should contain a risk region beyond one anchor interval");
        // samples between the two anchors are precise
        Integer preciseInside = jdbc.queryForObject("""
            SELECT COUNT(*) FROM aligned_sample a JOIN sample s ON s.id=a.sample_id
            WHERE a.version_id=? AND s.channel='meltpool' AND a.risk=0 AND a.unaligned=0
              AND s.t BETWEEN 11.02 AND 33.058""", Integer.class, version());
        assertTrue(preciseInside != null && preciseInside > 0);

        // revoke the second meltpool anchor (pair 2) and recompute
        decisions.record("REVOKE_ANCHOR", mapper.createObjectNode().put("anchorId", 3));
        alignment.recompute(AlignmentService.ALGO_V1, "revoked anchor 3");

        // with a single anchor left, every sample away from it is extrapolation beyond
        // one (undefined) interval: risk only, never precise
        Integer badPrecise = jdbc.queryForObject("""
            SELECT COUNT(*) FROM aligned_sample a JOIN sample s ON s.id=a.sample_id
            WHERE a.version_id=? AND s.channel='meltpool' AND a.risk=0 AND a.unaligned=0
              AND ABS(s.t - 11.02) > 1e-9""", Integer.class, version());
        assertEquals(0, badPrecise, "no precise alignment may be fabricated beyond one interval");
        // risk samples still carry an estimated t_ref (risk != unaligned)
        Integer riskWithEstimate = jdbc.queryForObject("""
            SELECT COUNT(*) FROM aligned_sample a JOIN sample s ON s.id=a.sample_id
            WHERE a.version_id=? AND s.channel='meltpool' AND a.risk=1 AND a.t_ref IS NOT NULL""",
                Integer.class, version());
        assertTrue(riskWithEstimate != null && riskWithEstimate > 0);
        // a diagnostic documents the risk region
        Integer diag = jdbc.queryForObject("""
            SELECT COUNT(*) FROM diagnostic WHERE version_id=? AND kind='extrapolation-risk'""",
                Integer.class, version());
        assertTrue(diag != null && diag > 0);
    }

    @Test
    void boundarySampleAssignedLeftClosedRightOpen() throws Exception {
        // craft a meltpool sample landing exactly on the track boundary t_ref = 12.0
        Map<String, Object> seg = jdbc.queryForMap(
                "SELECT slope, offset FROM segment WHERE version_id=? AND channel='meltpool'", version());
        double slope = ((Number) seg.get("slope")).doubleValue();
        double offset = ((Number) seg.get("offset")).doubleValue();
        double rawT = (12.0 - offset) / slope;
        long newId = jdbc.queryForObject("SELECT COALESCE(MAX(id),0)+1 FROM sample", Long.class);
        jdbc.update("INSERT INTO sample(id,channel,epoch,t,v) VALUES(?,?,0,?,150.0)", newId, "meltpool", rawT);
        alignment.recompute(AlignmentService.ALGO_V1, "boundary sample");

        Double tRef = jdbc.queryForObject(
                "SELECT t_ref FROM aligned_sample WHERE version_id=? AND sample_id=?", Double.class, version(), newId);
        assertEquals(12.0, tRef, 1e-9);

        // the boundary sample must be counted by the right-hand track only
        List<Map<String, Object>> stats = jdbc.queryForList("""
            SELECT t.t_start, t.t_end, ts.n FROM track_stat ts JOIN track t ON t.id=ts.track_id
            WHERE ts.version_id=? AND t.t_start <= 12.0 + 1e-9 AND t.t_end >= 12.0 - 1e-9
            ORDER BY t.t_start""", version());
        assertEquals(2, stats.size());
        double leftStart = ((Number) stats.get(0).get("t_start")).doubleValue();
        double leftEnd = ((Number) stats.get(0).get("t_end")).doubleValue();
        double rightStart = ((Number) stats.get(1).get("t_start")).doubleValue();
        assertEquals(12.0, leftEnd, 1e-9);
        assertEquals(12.0, rightStart, 1e-9);
        Long expectedLeft = jdbc.queryForObject("""
            SELECT COUNT(*) FROM aligned_sample a JOIN sample s ON s.id=a.sample_id
            WHERE a.version_id=? AND s.channel='meltpool' AND a.unaligned=0
              AND a.t_ref >= ? AND a.t_ref < ?""", Long.class, version(), leftStart, leftEnd);
        Long expectedRight = jdbc.queryForObject("""
            SELECT COUNT(*) FROM aligned_sample a JOIN sample s ON s.id=a.sample_id
            WHERE a.version_id=? AND s.channel='meltpool' AND a.unaligned=0
              AND a.t_ref >= ? AND a.t_ref < ?""", Long.class, version(),
                rightStart, ((Number) stats.get(1).get("t_end")).doubleValue());
        assertEquals(expectedLeft.longValue(), ((Number) stats.get(0).get("n")).longValue());
        assertEquals(expectedRight.longValue(), ((Number) stats.get(1).get("n")).longValue());

        // global invariant: no aligned meltpool sample is counted by two tracks
        Integer doubleCounted = jdbc.queryForObject("""
            SELECT COUNT(*) FROM (
              SELECT a.sample_id, COUNT(*) AS c
              FROM aligned_sample a JOIN sample s ON s.id=a.sample_id
              JOIN track t ON t.active=1 AND t.laser_on=1
                AND a.t_ref >= t.t_start AND a.t_ref < t.t_end
              WHERE a.version_id=? AND s.channel='meltpool' AND a.unaligned=0
              GROUP BY a.sample_id HAVING c > 1)""", Integer.class, version());
        assertEquals(0, doubleCounted);
    }

    @Test
    void restartEpochsAreNotConnected() {
        // scanner clock resets at restart: raw counts after restart are lower than before
        Double maxBefore = jdbc.queryForObject(
                "SELECT MAX(t) FROM sample WHERE channel='scanner' AND epoch=0", Double.class);
        Double maxAfter = jdbc.queryForObject(
                "SELECT MAX(t) FROM sample WHERE channel='scanner' AND epoch=1", Double.class);
        assertTrue(maxAfter < maxBefore, "scanner clock must reset after restart");

        // one fit segment per epoch; counts are never connected across the restart
        List<Map<String, Object>> segs = jdbc.queryForList(
                "SELECT * FROM segment WHERE version_id=? AND channel='scanner' ORDER BY epoch", version());
        assertEquals(2, segs.size());
        assertEquals(0, ((Number) segs.get(0).get("epoch")).intValue());
        assertEquals(1, ((Number) segs.get(1).get("epoch")).intValue());
        assertEquals("restart-event", segs.get(1).get("basis"));

        // aligned scanner clock is monotonic within each epoch
        Integer violations = jdbc.queryForObject("""
            SELECT COUNT(*) FROM diagnostic
            WHERE version_id=? AND kind='scanner-clock-non-monotonic'""", Integer.class, version());
        assertEquals(0, violations);
    }

    @Test
    void nonLaserMoveExcludedFromStatsButVisible() {
        // the laser-off move (seq 4) stays in the track list for the path view
        Integer move = jdbc.queryForObject(
                "SELECT COUNT(*) FROM track WHERE active=1 AND laser_on=0", Integer.class);
        assertEquals(1, move);
        // but contributes no melt-pool statistics
        Integer stats = jdbc.queryForObject("""
            SELECT COUNT(*) FROM track_stat ts JOIN track t ON t.id=ts.track_id
            WHERE ts.version_id=? AND t.laser_on=0""", Integer.class, version());
        assertEquals(0, stats);
    }

    @Test
   void nonMonotonicScannerClockIsDiagnosed() throws Exception {
        // a bogus anchor that inverts the scanner clock slope
        decisions.record("ADD_ANCHOR", mapper.createObjectNode()
                .put("channel", "scanner").put("epoch", 0)
                .put("tChannel", 39.0).put("tRef", -100.0).put("note", "bad"));
        alignment.recompute(AlignmentService.ALGO_V1, "bad anchor");
        Integer diag = jdbc.queryForObject("""
            SELECT COUNT(*) FROM diagnostic
            WHERE version_id=? AND kind='scanner-clock-non-monotonic'""", Integer.class, version());
        assertTrue(diag != null && diag > 0, "non-monotonic scanner clock must be diagnosed");
    }

    @Test
    void splitTrackProducesTwoLeftClosedRightOpenChildren() throws Exception {
        decisions.record("SPLIT_TRACK", mapper.createObjectNode().put("trackId", 1).put("t", 7.0));
        alignment.recompute(AlignmentService.ALGO_V1, "split");
        List<Map<String, Object>> children = jdbc.queryForList(
                "SELECT * FROM track WHERE parent_id=1 AND active=1 ORDER BY t_start");
        assertEquals(2, children.size());
        assertEquals(2.0, ((Number) children.get(0).get("t_start")).doubleValue(), 1e-9);
        assertEquals(7.0, ((Number) children.get(0).get("t_end")).doubleValue(), 1e-9);
        assertEquals(7.0, ((Number) children.get(1).get("t_start")).doubleValue(), 1e-9);
        assertEquals(12.0, ((Number) children.get(1).get("t_end")).doubleValue(), 1e-9);
        // both children have statistics; original is inactive
        Integer stats = jdbc.queryForObject("""
            SELECT COUNT(*) FROM track_stat ts JOIN track t ON t.id=ts.track_id
            WHERE ts.version_id=? AND t.parent_id=1""", Integer.class, version());
        assertEquals(2, stats);
        Integer orig = jdbc.queryForObject(
                "SELECT active FROM track WHERE id=1", Integer.class);
        assertEquals(0, orig);
    }

    @Test
    void markUnalignedKeepsSamplesOutOfAlignment() throws Exception {
        decisions.record("MARK_UNALIGNED", mapper.createObjectNode()
                .put("channel", "meltpool").put("tStart", 12.0).put("tEnd", 20.0));
        alignment.recompute(AlignmentService.ALGO_V1, "unaligned window");
        Integer unaligned = jdbc.queryForObject("""
            SELECT COUNT(*) FROM aligned_sample a JOIN sample s ON s.id=a.sample_id
            WHERE a.version_id=? AND s.channel='meltpool' AND a.unaligned=1
              AND a.t_ref IS NULL""", Integer.class, version());
        assertTrue(unaligned != null && unaligned > 0);
        Integer leaked = jdbc.queryForObject("""
            SELECT COUNT(*) FROM aligned_sample a JOIN sample s ON s.id=a.sample_id
            WHERE a.version_id=? AND s.channel='meltpool' AND a.unaligned=0
              AND s.t >= 12.0 AND s.t < 20.0""", Integer.class, version());
        assertEquals(0, leaked);
    }

    @Test
    void replayNeverRewritesRawSamples() throws Exception {
        List<Map<String, Object>> before = jdbc.queryForList("SELECT * FROM sample ORDER BY id");
        decisions.record("REVOKE_ANCHOR", mapper.createObjectNode().put("anchorId", 4));
        decisions.record("SPLIT_TRACK", mapper.createObjectNode().put("trackId", 2).put("t", 17.0));
        decisions.record("MARK_UNALIGNED", mapper.createObjectNode()
                .put("channel", "scanner").put("tStart", 5.0).put("tEnd", 9.0));
        long v2 = alignment.recompute("ridge-ls-v2-experimental", "new algo replays old decisions");
        assertTrue(v2 > 1, "recompute must produce a new alignment version");
        List<Map<String, Object>> after = jdbc.queryForList("SELECT * FROM sample ORDER BY id");
        assertEquals(before, after, "raw samples must never be rewritten");
        // decisions were replayed from the fixture snapshot
        assertEquals("revoked", jdbc.queryForObject(
                "SELECT status FROM anchor WHERE id=4", String.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM track WHERE parent_id=2 AND active=1", Integer.class).intValue());
        // both versions keep their derived data (lineage)
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(DISTINCT version_id) FROM aligned_sample", Integer.class).intValue());
    }

    @Test
    void exportThenReimportRecordReproducesDecisions() throws Exception {
        decisions.record("REVOKE_ANCHOR", mapper.createObjectNode().put("anchorId", 2));
        alignment.recompute(AlignmentService.ALGO_V1, "before export");
        Map<String, Object> record = api.export();

        Map<String, Object> result = api.importRecord(mapper.valueToTree(record));
        assertEquals(1, result.get("decisions"));
        assertEquals("revoked", jdbc.queryForObject(
                "SELECT status FROM anchor WHERE id=2", String.class));
        assertTrue(importer.isImported());
        assertTrue(alignment.currentVersionId() > 0);
    }
}
