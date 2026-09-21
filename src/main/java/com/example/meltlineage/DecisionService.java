package com.example.meltlineage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * User decisions (confirm/revoke anchors, split tracks, mark unaligned).
 * Decisions are recorded as an ordered log and can be replayed from the
 * fixture snapshot by any alignment algorithm version.
 */
@Service
public class DecisionService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public DecisionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public synchronized long record(String type, JsonNode payload) throws Exception {
        apply(type, payload);
        long id = nextId("decision");
        jdbc.update("INSERT INTO decision(id,type,payload,created_at) VALUES(?,?,?,?)",
                id, type, mapper.writeValueAsString(payload), Instant.now().toString());
        return id;
    }

    /** Applies one decision to the live anchor/track tables. Also used during replay. */
    public void apply(String type, JsonNode p) {
        switch (type) {
            case "CONFIRM_ANCHOR" -> jdbc.update(
                    "UPDATE anchor SET status='confirmed' WHERE id=?", p.get("anchorId").asLong());
            case "REVOKE_ANCHOR" -> jdbc.update(
                    "UPDATE anchor SET status='revoked' WHERE id=?", p.get("anchorId").asLong());
            case "ADD_ANCHOR" -> jdbc.update("""
                    INSERT INTO anchor(id,pair,channel,epoch,t_channel,t_ref,status,basis,note)
                    VALUES(?,0,?,?,?,?,'proposed','user',?)""",
                    nextId("anchor"), p.get("channel").asText(),
                    p.has("epoch") ? p.get("epoch").asInt() : 0,
                    p.get("tChannel").asDouble(), p.get("tRef").asDouble(),
                    p.has("note") ? p.get("note").asText() : null);
            case "SPLIT_TRACK" -> splitTrack(p.get("trackId").asLong(), p.get("t").asDouble());
            case "MARK_UNALIGNED" -> { /* consumed by the alignment pass, no table mutation */ }
            default -> throw new IllegalArgumentException("unknown decision type: " + type);
        }
    }

    /** Splits an active track at aligned time t (left-closed right-open on both children). */
    public void splitTrack(long trackId, double t) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM track WHERE id=? AND active=1", trackId);
        if (rows.isEmpty()) throw new IllegalArgumentException("track not active: " + trackId);
        Map<String, Object> tr = rows.get(0);
        double tStart = ((Number) tr.get("t_start")).doubleValue();
        double tEnd = ((Number) tr.get("t_end")).doubleValue();
        if (t <= tStart || t >= tEnd)
            throw new IllegalArgumentException("split point outside track (" + tStart + "," + tEnd + ")");
        double f = (t - tStart) / (tEnd - tStart);
        double mx = ((Number) tr.get("x1")).doubleValue()
                + f * (((Number) tr.get("x2")).doubleValue() - ((Number) tr.get("x1")).doubleValue());
        double my = ((Number) tr.get("y1")).doubleValue()
                + f * (((Number) tr.get("y2")).doubleValue() - ((Number) tr.get("y1")).doubleValue());
        jdbc.update("UPDATE track SET active=0 WHERE id=?", trackId);
        long id1 = nextId("track");
        long id2 = id1 + 1;
        int layer = ((Number) tr.get("layer")).intValue();
        int seq = ((Number) tr.get("seq")).intValue();
        int laserOn = ((Number) tr.get("laser_on")).intValue();
        jdbc.update("""
            INSERT INTO track(id,layer,seq,laser_on,t_start,t_end,x1,y1,x2,y2,active,parent_id)
            VALUES(?,?,?,?,?,?,?,?,?,?,1,?)""",
                id1, layer, seq, laserOn, tStart, t,
                tr.get("x1"), tr.get("y1"), mx, my, trackId);
        jdbc.update("""
            INSERT INTO track(id,layer,seq,laser_on,t_start,t_end,x1,y1,x2,y2,active,parent_id)
            VALUES(?,?,?,?,?,?,?,?,?,?,1,?)""",
                id2, layer, seq + 1, laserOn, t, tEnd,
                mx, my, tr.get("x2"), tr.get("y2"), trackId);
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT * FROM decision ORDER BY id");
    }

    long nextId(String table) {
        Integer n = jdbc.queryForObject("SELECT COALESCE(MAX(id),0)+1 FROM " + table, Integer.class);
        return n == null ? 1 : n;
    }
}
