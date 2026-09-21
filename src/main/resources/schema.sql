CREATE TABLE IF NOT EXISTS scanner_point (
  id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL,
  layer_id TEXT NOT NULL,
  sequence_no INTEGER NOT NULL,
  scanner_clock INTEGER NOT NULL,
  x REAL NOT NULL,
  y REAL NOT NULL,
  z REAL NOT NULL,
  vector_id TEXT NOT NULL,
  UNIQUE(session_id, sequence_no),
  UNIQUE(session_id, scanner_clock)
);

CREATE TABLE IF NOT EXISTS laser_command (
  id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL,
  layer_id TEXT NOT NULL,
  sequence_no INTEGER NOT NULL,
  scanner_clock INTEGER NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('ON','OFF')),
  vector_id TEXT,
  power REAL NOT NULL,
  UNIQUE(session_id, scanner_clock)
);

CREATE TABLE IF NOT EXISTS melt_sample (
  id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL,
  sequence_no INTEGER NOT NULL,
  sensor_clock INTEGER NOT NULL,
  intensity REAL NOT NULL,
  UNIQUE(session_id, sequence_no)
);

CREATE TABLE IF NOT EXISTS machine_event (
  id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL,
  sequence_no INTEGER NOT NULL,
  scanner_clock INTEGER NOT NULL,
  event_type TEXT NOT NULL,
  event_counter INTEGER,
  payload TEXT NOT NULL,
  UNIQUE(session_id, sequence_no)
);

CREATE TABLE IF NOT EXISTS calibration_marker (
  id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL,
  layer_id TEXT NOT NULL,
  pair_index INTEGER NOT NULL,
  scanner_clock INTEGER NOT NULL,
  sensor_clock INTEGER NOT NULL,
  UNIQUE(session_id, layer_id, pair_index)
);

CREATE TABLE IF NOT EXISTS decision (
  id TEXT PRIMARY KEY,
  decision_type TEXT NOT NULL CHECK(decision_type IN (
    'CONFIRM_TROPHY','REVOKE_TROPHY','SPLIT_TRACK','KEEP_UNALIGNED'
  )),
  target_id TEXT,
  session_id TEXT,
  layer_id TEXT,
  scanner_clock INTEGER,
  sensor_clock_start INTEGER,
  sensor_clock_end INTEGER,
  reason TEXT NOT NULL,
  payload TEXT NOT NULL,
  created_at TEXT NOT NULL,
  sequence_no INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS alignment_version (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  created_at TEXT NOT NULL,
  trigger_reason TEXT NOT NULL,
  decision_head_id TEXT,
  decision_count INTEGER NOT NULL,
  fixture_revision TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS track_segment (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  version_id INTEGER NOT NULL,
  segment_id TEXT NOT NULL,
  session_id TEXT NOT NULL,
  layer_id TEXT NOT NULL,
  vector_id TEXT NOT NULL,
  part_index INTEGER NOT NULL,
  start_scanner_clock INTEGER NOT NULL,
  end_scanner_clock INTEGER NOT NULL,
  x1 REAL NOT NULL,
  y1 REAL NOT NULL,
  x2 REAL NOT NULL,
  y2 REAL NOT NULL,
  z REAL NOT NULL,
  is_laser INTEGER NOT NULL,
  sample_count INTEGER NOT NULL,
  mean_intensity REAL,
  FOREIGN KEY(version_id) REFERENCES alignment_version(id)
);

CREATE TABLE IF NOT EXISTS aligned_sample (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  version_id INTEGER NOT NULL,
  melt_sample_id TEXT NOT NULL,
  session_id TEXT NOT NULL,
  layer_id TEXT,
  segment_id TEXT,
  vector_id TEXT,
  sensor_clock INTEGER NOT NULL,
  mapped_scanner_clock REAL,
  x REAL,
  y REAL,
  z REAL,
  intensity REAL NOT NULL,
  status TEXT NOT NULL,
  risk_codes TEXT NOT NULL,
  clock_residual REAL,
  position_residual REAL,
  is_non_laser INTEGER NOT NULL,
  evidence TEXT NOT NULL,
  FOREIGN KEY(version_id) REFERENCES alignment_version(id),
  UNIQUE(version_id, melt_sample_id)
);

CREATE TABLE IF NOT EXISTS segment_residual (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  version_id INTEGER NOT NULL,
  segment_id TEXT NOT NULL,
  marker_count INTEGER NOT NULL,
  sample_count INTEGER NOT NULL,
  clock_residual_rms REAL NOT NULL,
  position_residual_rms REAL NOT NULL,
  evidence TEXT NOT NULL,
  UNIQUE(version_id, segment_id)
);

CREATE TABLE IF NOT EXISTS marker_residual (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  version_id INTEGER NOT NULL,
  marker_id TEXT NOT NULL,
  session_id TEXT NOT NULL,
  layer_id TEXT NOT NULL,
  active INTEGER NOT NULL,
  observed_sensor_clock INTEGER NOT NULL,
  predicted_sensor_clock REAL,
  residual REAL,
  evidence TEXT NOT NULL,
  UNIQUE(version_id, marker_id)
);

CREATE TABLE IF NOT EXISTS diagnostic (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  version_id INTEGER NOT NULL,
  severity TEXT NOT NULL,
  code TEXT NOT NULL,
  channel TEXT,
  session_id TEXT,
  layer_id TEXT,
  message TEXT NOT NULL,
  evidence TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_scanner_layer ON scanner_point(session_id, layer_id, scanner_clock);
CREATE INDEX IF NOT EXISTS idx_laser_time ON laser_command(session_id, scanner_clock);
CREATE INDEX IF NOT EXISTS idx_melt_clock ON melt_sample(session_id, sensor_clock);
CREATE INDEX IF NOT EXISTS idx_aligned_version ON aligned_sample(version_id);
CREATE INDEX IF NOT EXISTS idx_track_version ON track_segment(version_id);
