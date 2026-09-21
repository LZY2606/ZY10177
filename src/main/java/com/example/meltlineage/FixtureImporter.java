package com.example.meltlineage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Imports the fixed synthetic build fixture into raw tables.
 * Raw samples are insert-only; re-import requires a reset first.
 */
@Service
public class FixtureImporter {
    private final JdbcTemplate jdbc;
    private final Db db;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${melt.fixture}")
    private Resource fixtureResource;

    public FixtureImporter(JdbcTemplate jdbc, Db db) {
        this.jdbc = jdbc;
        this.db = db;
    }

    public synchronized int importFixture() throws Exception {
        db.resetAll();
        JsonNode root = mapper.readTree(fixtureResource.getInputStream());

        int sampleId = 1;
        JsonNode samples = root.get("samples");
        // laser channel: v = power (0/1)
        for (JsonNode s : samples.get("laser")) {
            jdbc.update("INSERT INTO sample(id,channel,epoch,t,v,v2) VALUES(?,?,?,?,?,NULL)",
                    sampleId++, "laser", 0, s.get("t").asDouble(), s.get("v").asDouble());
        }
        // meltpool channel: v = intensity
        for (JsonNode s : samples.get("meltpool")) {
            jdbc.update("INSERT INTO sample(id,channel,epoch,t,v,v2) VALUES(?,?,?,?,?,NULL)",
                    sampleId++, "meltpool", 0, s.get("t").asDouble(), s.get("v").asDouble());
        }
        // scanner channel: v = x, v2 = y, epoch from fixture (restart resets clock)
        for (JsonNode s : samples.get("scanner")) {
            jdbc.update("INSERT INTO sample(id,channel,epoch,t,v,v2) VALUES(?,?,?,?,?,?)",
                    sampleId++, "scanner", s.get("epoch").asInt(), s.get("t").asDouble(),
                    s.get("x").asDouble(), s.get("y").asDouble());
        }

        int eventId = 1;
        for (JsonNode e : root.get("events")) {
            jdbc.update("INSERT INTO event(id,t,code) VALUES(?,?,?)",
                    eventId++, e.get("t").asDouble(), e.get("code").asText());
        }

        int trackId = 1;
        for (JsonNode tr : root.get("tracks")) {
            jdbc.update("""
                INSERT INTO track(id,layer,seq,laser_on,t_start,t_end,x1,y1,x2,y2,active,parent_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,1,NULL)""",
                    trackId++, tr.get("layer").asInt(), tr.get("seq").asInt(),
                    tr.get("laserOn").asBoolean() ? 1 : 0,
                    tr.get("tStart").asDouble(), tr.get("tEnd").asDouble(),
                    tr.get("x1").asDouble(), tr.get("y1").asDouble(),
                    tr.get("x2").asDouble(), tr.get("y2").asDouble());
        }

        int anchorId = 1;
        for (JsonNode a : root.get("anchors")) {
            jdbc.update("""
                INSERT INTO anchor(id,pair,channel,epoch,t_channel,t_ref,status,basis,note)
                VALUES(?,?,?,?,?,?,'proposed','fixture',?)""",
                    anchorId++, a.get("pair").asInt(), a.get("channel").asText(),
                    a.get("epoch").asInt(), a.get("tChannel").asDouble(), a.get("tRef").asDouble(),
                    a.has("note") ? a.get("note").asText() : null);
        }

        // Derive implicit restart anchors: scanner clock resets to 0 at each RESTART event.
        // Counts before/after a restart are never connected by one fit; each new epoch
        // gets its own zero-point anchor whose basis is the restart event itself.
        List<Double> restarts = jdbc.queryForList(
                "SELECT t FROM event WHERE code='RESTART' ORDER BY t", Double.class);
        List<Integer> epochs = jdbc.queryForList(
                "SELECT DISTINCT epoch FROM sample WHERE channel='scanner' AND epoch>0 ORDER BY epoch",
                Integer.class);
        for (int i = 0; i < Math.min(restarts.size(), epochs.size()); i++) {
            jdbc.update("""
                INSERT INTO anchor(id,pair,channel,epoch,t_channel,t_ref,status,basis,note)
                VALUES(?,0,'scanner',?,0.0,?,'proposed','restart-event',?)""",
                    anchorId++, epochs.get(i), restarts.get(i),
                    "由 RESTART 事件推导:扫描器时钟在 t=" + restarts.get(i) + " 归零");
        }

        // Snapshot pristine anchors/tracks so alignment can replay decisions from scratch.
        String anchorsJson = mapper.writeValueAsString(jdbc.queryForList(
                "SELECT * FROM anchor ORDER BY id"));
        String tracksJson = mapper.writeValueAsString(jdbc.queryForList(
                "SELECT * FROM track ORDER BY id"));
        jdbc.update("INSERT INTO fixture_snapshot(id,anchors_json,tracks_json) VALUES(1,?,?)",
                anchorsJson, tracksJson);
        return sampleId - 1;
    }

    public boolean isImported() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM sample", Integer.class);
        return n != null && n > 0;
    }

    /** Restores anchors/tracks to the fixture snapshot (used before decision replay). */
    public void restoreSnapshot() throws Exception {
        List<java.util.Map<String, Object>> snap = jdbc.queryForList(
                "SELECT anchors_json, tracks_json FROM fixture_snapshot WHERE id=1");
        if (snap.isEmpty()) throw new IllegalStateException("fixture not imported");
        jdbc.update("DELETE FROM anchor");
        jdbc.update("DELETE FROM track");
        JsonNode anchors = mapper.readTree((String) snap.get(0).get("anchors_json"));
        for (JsonNode a : anchors) {
            jdbc.update("""
                INSERT INTO anchor(id,pair,channel,epoch,t_channel,t_ref,status,basis,note)
                VALUES(?,?,?,?,?,?,?,?,?)""",
                    a.get("id").asInt(), a.get("pair").asInt(), a.get("channel").asText(),
                    a.get("epoch").asInt(), a.get("t_channel").asDouble(), a.get("t_ref").asDouble(),
                    a.get("status").asText(), a.get("basis").asText(),
                    a.get("note").isNull() ? null : a.get("note").asText());
        }
        JsonNode tracks = mapper.readTree((String) snap.get(0).get("tracks_json"));
        for (JsonNode tr : tracks) {
            jdbc.update("""
                INSERT INTO track(id,layer,seq,laser_on,t_start,t_end,x1,y1,x2,y2,active,parent_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
                    tr.get("id").asInt(), tr.get("layer").asInt(), tr.get("seq").asInt(),
                    tr.get("laser_on").asInt(), tr.get("t_start").asDouble(), tr.get("t_end").asDouble(),
                    tr.get("x1").asDouble(), tr.get("y1").asDouble(),
                    tr.get("x2").asDouble(), tr.get("y2").asDouble(),
                    tr.get("active").asInt(),
                    tr.get("parent_id").isNull() ? null : tr.get("parent_id").asInt());
        }
    }
}
