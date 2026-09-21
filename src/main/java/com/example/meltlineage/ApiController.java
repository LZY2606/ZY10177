package com.example.meltlineage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final JdbcTemplate jdbc;
    private final FixtureImporter importer;
    private final AlignmentService alignment;
    private final DecisionService decisions;
    private final Db db;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApiController(JdbcTemplate jdbc, FixtureImporter importer, AlignmentService alignment,
                         DecisionService decisions, Db db) {
        this.jdbc = jdbc;
        this.importer = importer;
        this.alignment = alignment;
        this.decisions = decisions;
        this.db = db;
    }

    @PostMapping("/import-fixture")
    public Map<String, Object> importFixture() throws Exception {
        int n = importer.importFixture();
        long version = alignment.recompute(AlignmentService.ALGO_V1, "fixture 导入");
        return Map.of("samples", n, "version", version);
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        db.resetAll();
        return Map.of("ok", true);
    }

    @PostMapping("/recompute")
    public Map<String, Object> recompute(@RequestBody(required = false) JsonNode body) throws Exception {
        String algo = body != null && body.has("algo") ? body.get("algo").asText()
                : AlignmentService.ALGO_V1;
        long version = alignment.recompute(algo, "手动重算");
        return Map.of("version", version);
    }

    @PostMapping("/anchors/{id}/confirm")
    public Map<String, Object> confirmAnchor(@PathVariable long id) throws Exception {
        long decisionId = decisions.record("CONFIRM_ANCHOR", mapper.createObjectNode().put("anchorId", id));
        long version = alignment.recompute(AlignmentService.ALGO_V1, "确认锦标 " + id);
        return Map.of("decision", decisionId, "version", version);
    }

    @PostMapping("/anchors/{id}/revoke")
    public Map<String, Object> revokeAnchor(@PathVariable long id) throws Exception {
        long decisionId = decisions.record("REVOKE_ANCHOR", mapper.createObjectNode().put("anchorId", id));
        long version = alignment.recompute(AlignmentService.ALGO_V1, "撤销锦标 " + id);
        return Map.of("decision", decisionId, "version", version);
    }

    @PostMapping("/tracks/{id}/split")
    public Map<String, Object> splitTrack(@PathVariable long id, @RequestBody JsonNode body) throws Exception {
        ObjectNode payload = mapper.createObjectNode()
                .put("trackId", id).put("t", body.get("t").asDouble());
        long decisionId = decisions.record("SPLIT_TRACK", payload);
        long version = alignment.recompute(AlignmentService.ALGO_V1, "拆分道次 " + id);
        return Map.of("decision", decisionId, "version", version);
    }

    @PostMapping("/unaligned")
    public Map<String, Object> markUnaligned(@RequestBody JsonNode body) throws Exception {
        ObjectNode payload = mapper.createObjectNode()
                .put("channel", body.get("channel").asText())
                .put("tStart", body.get("tStart").asDouble())
                .put("tEnd", body.get("tEnd").asDouble());
        long decisionId = decisions.record("MARK_UNALIGNED", payload);
        long version = alignment.recompute(AlignmentService.ALGO_V1, "标记未对齐段");
        return Map.of("decision", decisionId, "version", version);
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        long version = alignment.currentVersionId();
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("imported", importer.isImported());
        m.put("currentVersion", version);
        m.put("versions", jdbc.queryForList("SELECT * FROM alignment_version ORDER BY id"));
        m.put("anchors", jdbc.queryForList("SELECT * FROM anchor ORDER BY id"));
        m.put("tracks", jdbc.queryForList("SELECT * FROM track WHERE active=1 ORDER BY t_start"));
        m.put("decisions", decisions.list());
        m.put("segments", jdbc.queryForList("SELECT * FROM segment WHERE version_id=? ORDER BY channel, epoch", version));
        m.put("diagnostics", jdbc.queryForList("SELECT * FROM diagnostic WHERE version_id=? ORDER BY id", version));
        m.put("trackStats", jdbc.queryForList("""
                    SELECT ts.*, t.seq, t.t_start, t.t_end FROM track_stat ts
                    JOIN track t ON t.id=ts.track_id
                    WHERE ts.version_id=? ORDER BY t.t_start""", version));
        m.put("events", jdbc.queryForList("SELECT * FROM event ORDER BY t"));
        m.put("counts", jdbc.queryForList("SELECT channel, COUNT(*) AS n FROM sample GROUP BY channel"));
        return m;
    }

    /** Aligned samples for the timeline / path / heatmap views. */
    @GetMapping("/timeline")
    public Map<String, Object> timeline() {
        long version = alignment.currentVersionId();
        return Map.of(
                "version", version,
                "samples", jdbc.queryForList("""
                    SELECT s.id, s.channel, s.epoch, s.t, s.v, s.v2,
                           a.t_ref, a.risk, a.unaligned, a.basis
                    FROM sample s LEFT JOIN aligned_sample a
                      ON a.sample_id=s.id AND a.version_id=?
                    ORDER BY s.id""", version)
        );
    }

    /** Exports the run record: decisions + alignment outcomes, re-importable for review. */
    @GetMapping("/export")
    public Map<String, Object> export() {
        long version = alignment.currentVersionId();
        return Map.of(
                "format", "melt-lineage-run-record",
                "exportedAt", Instant.now().toString(),
                "decisions", decisions.list(),
                "versions", jdbc.queryForList("SELECT * FROM alignment_version ORDER BY id"),
                "currentVersion", version,
                "segments", jdbc.queryForList("SELECT * FROM segment WHERE version_id=?", version),
                "diagnostics", jdbc.queryForList("SELECT * FROM diagnostic WHERE version_id=?", version),
                "trackStats", jdbc.queryForList("SELECT * FROM track_stat WHERE version_id=?", version)
        );
    }

    /** Re-imports a run record onto a fresh fixture: replays decisions, never raw edits. */
    @PostMapping("/import-record")
    public Map<String, Object> importRecord(@RequestBody JsonNode record) throws Exception {
        if (!record.has("decisions")) throw new IllegalArgumentException("not a run record");
        importer.importFixture();
        for (JsonNode d : record.get("decisions")) {
            JsonNode payload = d.get("payload");
            if (payload != null && payload.isTextual()) payload = mapper.readTree(payload.asText());
            decisions.record(d.get("type").asText(), payload);
        }
        long version = alignment.recompute(AlignmentService.ALGO_V1, "运行记录重放复核");
        return Map.of("version", version, "decisions", record.get("decisions").size());
    }
}
