package com.example.meltlineage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Alignment engine (algo "ridge-ls-v1").
 *
 * For every non-reference channel and clock epoch, active anchors are fit with a
 * ridge-regularized least-squares line (regularized toward the nominal slope 1.0).
 * Extrapolation farther than one anchor interval beyond the anchor range is flagged
 * as risk and never reported as precise. Scanner fits are per epoch, so machine
 * restarts never connect counts across the reset. Raw samples are read-only here.
 */
@Service
public class AlignmentService {
    public static final String ALGO_V1 = "ridge-ls-v1";
    private static final double RIDGE_LAMBDA = 0.5;

    private final JdbcTemplate jdbc;
    private final Db db;
    private final FixtureImporter importer;
    private final DecisionService decisions;
    private final ObjectMapper mapper = new ObjectMapper();

    public AlignmentService(JdbcTemplate jdbc, Db db, FixtureImporter importer, DecisionService decisions) {
        this.jdbc = jdbc;
        this.db = db;
        this.importer = importer;
        this.decisions = decisions;
    }

    private record AnchorRow(long id, double tChannel, double tRef, String basis) {}
    private record Fit(double slope, double offset, double residualRms, double minT, double maxT,
                       double maxGap, String basis, int anchorCount) {}

    /** Full recompute: restore fixture state, replay every decision, run the fit. */
    public synchronized long recompute(String algo, String note) throws Exception {
        importer.restoreSnapshot();
        for (Map<String, Object> d : decisions.list()) {
            decisions.apply((String) d.get("type"), mapper.readTree((String) d.get("payload")));
        }
        long versionId = nextVersionId();
        jdbc.update("INSERT INTO alignment_version(id,algo,created_at,note) VALUES(?,?,?,?)",
                versionId, algo, Instant.now().toString(), note);

        List<Map<String, Object>> unalignedWindows = unalignedWindows();
        Map<String, Fit> fits = new HashMap<>(); // key channel|epoch

        // reference channels: identity mapping
        fits.put("laser|0", new Fit(1.0, 0.0, 0.0, Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, "reference-clock", 0));

        for (String channel : new String[]{"meltpool", "scanner"}) {
            List<Integer> epochs = jdbc.queryForList(
                    "SELECT DISTINCT epoch FROM sample WHERE channel=? ORDER BY epoch",
                    Integer.class, channel);
            for (int epoch : epochs) {
                List<AnchorRow> anchors = activeAnchors(channel, epoch);
                String key = channel + "|" + epoch;
                if (anchors.isEmpty()) {
                    fits.put(key, null);
                    diagnostic(versionId, "channel-unanchored",
                            "通道 " + channel + " epoch " + epoch + " 没有可用锦标,样本保持未对齐");
                    continue;
                }
                Fit fit = fit(anchors);
                fits.put(key, fit);
                jdbc.update("""
                    INSERT INTO segment(version_id,channel,epoch,slope,offset,residual_rms,anchor_count,basis)
                    VALUES(?,?,?,?,?,?,?,?)""",
                        versionId, channel, epoch, fit.slope(), fit.offset(), fit.residualRms(),
                        fit.anchorCount(), fit.basis());
                if (channel.equals("scanner") && fit.slope() <= 0) {
                    diagnostic(versionId, "scanner-clock-non-monotonic",
                            "扫描器 epoch " + epoch + " 拟合斜率非正,时钟非单调");
                }
            }
        }

        // align every raw sample (raw rows are never modified)
        List<Map<String, Object>> samples = jdbc.queryForList(
                "SELECT id,channel,epoch,t FROM sample ORDER BY id");
        for (Map<String, Object> s : samples) {
            long id = ((Number) s.get("id")).longValue();
            String channel = (String) s.get("channel");
            int epoch = ((Number) s.get("epoch")).intValue();
            double t = ((Number) s.get("t")).doubleValue();
            if (inUnalignedWindow(unalignedWindows, channel, t)) {
                jdbc.update("INSERT INTO aligned_sample(version_id,sample_id,t_ref,risk,unaligned,basis) VALUES(?,?,NULL,0,1,'user-unaligned')",
                        versionId, id);
                continue;
            }
            Fit fit = fits.get(channel + "|" + epoch);
            if (fit == null) {
                jdbc.update("INSERT INTO aligned_sample(version_id,sample_id,t_ref,risk,unaligned,basis) VALUES(?,?,NULL,0,1,'no-anchor')",
                        versionId, id);
                continue;
            }
            double tRef = fit.slope() * t + fit.offset();
            boolean risk = isRisk(fit, t);
            jdbc.update("INSERT INTO aligned_sample(version_id,sample_id,t_ref,risk,unaligned,basis) VALUES(?,?,?,?,0,?)",
                    versionId, id, tRef, risk ? 1 : 0, fit.basis());
        }

        checkScannerMonotonic(versionId);
        computeTrackStats(versionId);
        diagnoseMissingChannels(versionId);
        diagnoseRiskRegions(versionId);
        return versionId;
    }

    private List<AnchorRow> activeAnchors(String channel, int epoch) {
        return jdbc.query("""
                SELECT id,t_channel,t_ref,basis FROM anchor
                WHERE channel=? AND epoch=? AND status!='revoked' ORDER BY t_channel""",
                (rs, i) -> new AnchorRow(rs.getLong("id"), rs.getDouble("t_channel"),
                        rs.getDouble("t_ref"), rs.getString("basis")),
                channel, epoch);
    }

    /** Ridge-regularized least squares toward nominal slope 1.0. */
    private Fit fit(List<AnchorRow> anchors) {
        int n = anchors.size();
        double meanT = 0, meanR = 0;
        for (AnchorRow a : anchors) { meanT += a.tChannel(); meanR += a.tRef(); }
        meanT /= n; meanR /= n;
        double sTT = 0, sTR = 0;
        for (AnchorRow a : anchors) {
            sTT += (a.tChannel() - meanT) * (a.tChannel() - meanT);
            sTR += (a.tChannel() - meanT) * (a.tRef() - meanR);
        }
        double slope = (sTR + RIDGE_LAMBDA) / (sTT + RIDGE_LAMBDA);
        double offset = meanR - slope * meanT;
        double sse = 0;
        for (AnchorRow a : anchors) {
            double r = slope * a.tChannel() + offset - a.tRef();
            sse += r * r;
        }
        double rms = Math.sqrt(sse / n);
        double minT = anchors.get(0).tChannel();
        double maxT = anchors.get(n - 1).tChannel();
        double maxGap = 0;
        for (int i = 1; i < n; i++) {
            maxGap = Math.max(maxGap, anchors.get(i).tChannel() - anchors.get(i - 1).tChannel());
        }
        String basis;
        if (n == 1) {
            basis = anchors.get(0).basis();
        } else {
            StringBuilder sb = new StringBuilder("anchors:");
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(',');
                sb.append(anchors.get(i).id());
            }
            basis = sb.toString();
        }
        return new Fit(slope, offset, rms, minT, maxT, maxGap, basis, n);
    }

    /** Extrapolation farther than one anchor interval beyond the anchor range is risk. */
    private boolean isRisk(Fit fit, double t) {
        if (t < fit.minT()) return fit.minT() - t > fit.maxGap();
        if (t > fit.maxT()) return t - fit.maxT() > fit.maxGap();
        return false;
    }

    private List<Map<String, Object>> unalignedWindows() throws Exception {
        List<Map<String, Object>> windows = new ArrayList<>();
        for (Map<String, Object> d : decisions.list()) {
            if (!"MARK_UNALIGNED".equals(d.get("type"))) continue;
            JsonNode p = mapper.readTree((String) d.get("payload"));
            windows.add(Map.of("channel", p.get("channel").asText(),
                    "tStart", p.get("tStart").asDouble(), "tEnd", p.get("tEnd").asDouble()));
        }
        return windows;
    }

    private boolean inUnalignedWindow(List<Map<String, Object>> windows, String channel, double t) {
        for (Map<String, Object> w : windows) {
            if (w.get("channel").equals(channel)
                    && t >= (Double) w.get("tStart") && t < (Double) w.get("tEnd")) return true;
        }
        return false;
    }

    /** Scanner aligned clock must be monotonic within a layer/epoch. */
    private void checkScannerMonotonic(long versionId) {
        List<Integer> epochs = jdbc.queryForList(
                "SELECT DISTINCT epoch FROM sample WHERE channel='scanner'", Integer.class);
        for (int epoch : epochs) {
            List<Double> refs = jdbc.queryForList("""
                SELECT a.t_ref FROM aligned_sample a JOIN sample s ON s.id=a.sample_id
                WHERE a.version_id=? AND s.channel='scanner' AND s.epoch=? AND a.unaligned=0
                ORDER BY s.t""", Double.class, versionId, epoch);
            for (int i = 1; i < refs.size(); i++) {
                if (refs.get(i) < refs.get(i - 1)) {
                    diagnostic(versionId, "scanner-clock-non-monotonic",
                            "扫描器 epoch " + epoch + " 对齐后时钟回退 (样本序号 " + i + ")");
                    break;
                }
            }
        }
    }

    /**
     * Melt-pool statistics per laser-on track. Assignment is left-closed right-open
     * [t_start, t_end), so a sample exactly on a boundary belongs to the next track
     * only. Non-laser moves are excluded from statistics.
     */
    private void computeTrackStats(long versionId) {
        List<Map<String, Object>> tracks = jdbc.queryForList(
                "SELECT id,seq,t_start,t_end FROM track WHERE active=1 AND laser_on=1 ORDER BY t_start");
        for (Map<String, Object> tr : tracks) {
            long trackId = ((Number) tr.get("id")).longValue();
            double tStart = ((Number) tr.get("t_start")).doubleValue();
            double tEnd = ((Number) tr.get("t_end")).doubleValue();
            Map<String, Object> stats = jdbc.queryForMap("""
                SELECT COUNT(*) AS n, AVG(s.v) AS mean
                FROM aligned_sample a JOIN sample s ON s.id=a.sample_id
                WHERE a.version_id=? AND s.channel='meltpool' AND a.unaligned=0
                  AND a.t_ref >= ? AND a.t_ref < ?""",
                    versionId, tStart, tEnd);
            long n = ((Number) stats.get("n")).longValue();
            Object mean = stats.get("mean");
            jdbc.update("INSERT INTO track_stat(version_id,track_id,n,mean) VALUES(?,?,?,?)",
                    versionId, trackId, n, mean == null ? null : ((Number) mean).doubleValue());
        }
    }

    private void diagnoseMissingChannels(long versionId) {
        List<Map<String, Object>> tracks = jdbc.queryForList(
                "SELECT id,seq,t_start,t_end FROM track WHERE active=1 AND laser_on=1 ORDER BY t_start");
        for (Map<String, Object> tr : tracks) {
            double tStart = ((Number) tr.get("t_start")).doubleValue();
            double tEnd = ((Number) tr.get("t_end")).doubleValue();
            Object seq = tr.get("seq");
            Integer mp = jdbc.queryForObject("""
                SELECT COUNT(*) FROM aligned_sample a JOIN sample s ON s.id=a.sample_id
                WHERE a.version_id=? AND s.channel='meltpool' AND a.unaligned=0
                  AND a.t_ref >= ? AND a.t_ref < ?""", Integer.class, versionId, tStart, tEnd);
            if (mp == null || mp == 0) {
                diagnostic(versionId, "missing-channel", "道次 " + seq + " 缺少熔池通道数据");
            }
            Integer sc = jdbc.queryForObject("""
                SELECT COUNT(*) FROM aligned_sample a JOIN sample s ON s.id=a.sample_id
                WHERE a.version_id=? AND s.channel='scanner' AND a.unaligned=0
                  AND a.t_ref >= ? AND a.t_ref < ?""", Integer.class, versionId, tStart, tEnd);
            if (sc == null || sc == 0) {
                diagnostic(versionId, "missing-channel", "道次 " + seq + " 缺少扫描器位置数据");
            }
        }
    }

    private void diagnoseRiskRegions(long versionId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT s.channel AS channel, COUNT(*) AS n, MIN(a.t_ref) AS lo, MAX(a.t_ref) AS hi
            FROM aligned_sample a JOIN sample s ON s.id=a.sample_id
            WHERE a.version_id=? AND a.risk=1 AND a.unaligned=0
            GROUP BY s.channel""", versionId);
        for (Map<String, Object> r : rows) {
            diagnostic(versionId, "extrapolation-risk",
                    "通道 " + r.get("channel") + " 有 " + r.get("n")
                            + " 个样本外推超过一个锦标间隔,仅标记风险 (t_ref "
                            + r.get("lo") + " .. " + r.get("hi") + ")");
        }
    }

    private void diagnostic(long versionId, String kind, String message) {
        jdbc.update("INSERT INTO diagnostic(version_id,kind,message) VALUES(?,?,?)",
                versionId, kind, message);
    }

    private long nextVersionId() {
        Integer n = jdbc.queryForObject("SELECT COALESCE(MAX(id),0)+1 FROM alignment_version", Integer.class);
        return n == null ? 1 : n;
    }

    public long currentVersionId() {
        Integer n = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM alignment_version", Integer.class);
        return n == null ? 0 : n;
    }
}
