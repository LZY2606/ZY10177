package com.example.meltlineage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Schema management and full reset. Raw samples are never updated after import. */
@Component
public class Db {
    private final JdbcTemplate jdbc;

    public Db(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        init();
    }

    private void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS sample(
              id INTEGER PRIMARY KEY,
              channel TEXT NOT NULL,
              epoch INTEGER NOT NULL DEFAULT 0,
              t REAL NOT NULL,
              v REAL, v2 REAL
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS event(
              id INTEGER PRIMARY KEY,
              t REAL NOT NULL,
              code TEXT NOT NULL
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS anchor(
              id INTEGER PRIMARY KEY,
              pair INTEGER, channel TEXT NOT NULL, epoch INTEGER NOT NULL DEFAULT 0,
              t_channel REAL NOT NULL, t_ref REAL NOT NULL,
              status TEXT NOT NULL DEFAULT 'proposed',
              basis TEXT NOT NULL DEFAULT 'fixture', note TEXT
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS track(
              id INTEGER PRIMARY KEY,
              layer INTEGER NOT NULL, seq INTEGER NOT NULL, laser_on INTEGER NOT NULL,
              t_start REAL NOT NULL, t_end REAL NOT NULL,
              x1 REAL, y1 REAL, x2 REAL, y2 REAL,
              active INTEGER NOT NULL DEFAULT 1, parent_id INTEGER
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS decision(
              id INTEGER PRIMARY KEY,
              type TEXT NOT NULL, payload TEXT NOT NULL, created_at TEXT NOT NULL
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS alignment_version(
              id INTEGER PRIMARY KEY,
              algo TEXT NOT NULL, created_at TEXT NOT NULL, note TEXT
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS segment(
              id INTEGER PRIMARY KEY,
              version_id INTEGER NOT NULL, channel TEXT NOT NULL, epoch INTEGER NOT NULL,
              slope REAL, offset REAL, residual_rms REAL,
              anchor_count INTEGER NOT NULL, basis TEXT
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS aligned_sample(
              version_id INTEGER NOT NULL, sample_id INTEGER NOT NULL,
              t_ref REAL, risk INTEGER NOT NULL DEFAULT 0, unaligned INTEGER NOT NULL DEFAULT 0,
              basis TEXT,
              PRIMARY KEY(version_id, sample_id)
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS diagnostic(
              id INTEGER PRIMARY KEY,
              version_id INTEGER NOT NULL, kind TEXT NOT NULL, message TEXT NOT NULL
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS track_stat(
              version_id INTEGER NOT NULL, track_id INTEGER NOT NULL,
              n INTEGER NOT NULL, mean REAL,
              PRIMARY KEY(version_id, track_id)
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS fixture_snapshot(
              id INTEGER PRIMARY KEY CHECK(id=1),
              anchors_json TEXT NOT NULL, tracks_json TEXT NOT NULL
            )""");
    }

    /** Clears every table. Used by reset and before re-import. */
    public void resetAll() {
        for (String t : new String[]{"aligned_sample","track_stat","diagnostic","segment",
                "alignment_version","decision","track","anchor","event","sample","fixture_snapshot"}) {
            jdbc.update("DELETE FROM " + t);
        }
    }

    /** Clears only derived alignment data (never raw samples). */
    public void clearDerived() {
        for (String t : new String[]{"aligned_sample","track_stat","diagnostic","segment","alignment_version"}) {
            jdbc.update("DELETE FROM " + t);
        }
    }
}
