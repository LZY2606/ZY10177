package local.fuselineage.repository;

import java.util.List;
import local.fuselineage.domain.AlignmentResults;
import local.fuselineage.domain.RawRecords;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LineageRepository {
  private final JdbcTemplate jdbc;

  public LineageRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public int countRawSamples() {
    Integer scanner = jdbc.queryForObject("SELECT COUNT(*) FROM scanner_point", Integer.class);
    Integer melt = jdbc.queryForObject("SELECT COUNT(*) FROM melt_sample", Integer.class);
    return (scanner == null ? 0 : scanner) + (melt == null ? 0 : melt);
  }

  @Transactional
  public void replaceRawBundle(RawRecords.RawBundle bundle) {
    clearAll();
    insertRawBundle(bundle);
  }

  @Transactional
  public void clearAll() {
    jdbc.update("DELETE FROM diagnostic");
    jdbc.update("DELETE FROM marker_residual");
    jdbc.update("DELETE FROM segment_residual");
    jdbc.update("DELETE FROM aligned_sample");
    jdbc.update("DELETE FROM track_segment");
    jdbc.update("DELETE FROM alignment_version");
    jdbc.update("DELETE FROM decision");
    jdbc.update("DELETE FROM calibration_marker");
    jdbc.update("DELETE FROM machine_event");
    jdbc.update("DELETE FROM melt_sample");
    jdbc.update("DELETE FROM laser_command");
    jdbc.update("DELETE FROM scanner_point");
  }

  @Transactional
  public void insertRawBundle(RawRecords.RawBundle bundle) {
    for (RawRecords.ScannerPoint point : bundle.scannerPoints()) {
      jdbc.update("""
          INSERT INTO scanner_point
          (id,session_id,layer_id,sequence_no,scanner_clock,x,y,z,vector_id)
          VALUES (?,?,?,?,?,?,?,?,?)
          """, point.id(), point.sessionId(), point.layerId(), point.sequenceNo(),
          point.scannerClock(), point.x(), point.y(), point.z(), point.vectorId());
    }
    for (RawRecords.LaserCommand command : bundle.laserCommands()) {
      jdbc.update("""
          INSERT INTO laser_command
          (id,session_id,layer_id,sequence_no,scanner_clock,state,vector_id,power)
          VALUES (?,?,?,?,?,?,?,?)
          """, command.id(), command.sessionId(), command.layerId(), command.sequenceNo(),
          command.scannerClock(), command.state(), command.vectorId(), command.power());
    }
    for (RawRecords.MeltSample sample : bundle.meltSamples()) {
      jdbc.update("""
          INSERT INTO melt_sample
          (id,session_id,sequence_no,sensor_clock,intensity)
          VALUES (?,?,?,?,?)
          """, sample.id(), sample.sessionId(), sample.sequenceNo(),
          sample.sensorClock(), sample.intensity());
    }
    for (RawRecords.MachineEvent event : bundle.machineEvents()) {
      jdbc.update("""
          INSERT INTO machine_event
          (id,session_id,sequence_no,scanner_clock,event_type,event_counter,payload)
          VALUES (?,?,?,?,?,?,?)
          """, event.id(), event.sessionId(), event.sequenceNo(), event.scannerClock(),
          event.eventType(), event.eventCounter(), event.payload());
    }
    for (RawRecords.CalibrationMarker marker : bundle.markers()) {
      jdbc.update("""
          INSERT INTO calibration_marker
          (id,session_id,layer_id,pair_index,scanner_clock,sensor_clock)
          VALUES (?,?,?,?,?,?)
          """, marker.id(), marker.sessionId(), marker.layerId(), marker.pairIndex(),
          marker.scannerClock(), marker.sensorClock());
    }
    for (RawRecords.Decision decision : bundle.decisions()) {
      insertDecision(decision);
    }
  }

  @Transactional
  public void insertDecision(RawRecords.Decision decision) {
    jdbc.update("""
        INSERT INTO decision
        (id,decision_type,target_id,session_id,layer_id,scanner_clock,sensor_clock_start,
         sensor_clock_end,reason,payload,created_at,sequence_no)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
        """, decision.id(), decision.decisionType(), decision.targetId(), decision.sessionId(),
        decision.layerId(), decision.scannerClock(), decision.sensorClockStart(),
        decision.sensorClockEnd(), decision.reason(), decision.payload(),
        decision.createdAt(), decision.sequenceNo());
  }

  public List<RawRecords.ScannerPoint> scannerPoints() {
    return jdbc.query("SELECT * FROM scanner_point ORDER BY session_id, scanner_clock, sequence_no",
        (rs, row) -> new RawRecords.ScannerPoint(
            rs.getString("id"), rs.getString("session_id"), rs.getString("layer_id"),
            rs.getLong("sequence_no"), rs.getLong("scanner_clock"),
            rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
            rs.getString("vector_id")));
  }

  public List<RawRecords.LaserCommand> laserCommands() {
    return jdbc.query("SELECT * FROM laser_command ORDER BY session_id, scanner_clock, sequence_no",
        (rs, row) -> new RawRecords.LaserCommand(
            rs.getString("id"), rs.getString("session_id"), rs.getString("layer_id"),
            rs.getLong("sequence_no"), rs.getLong("scanner_clock"),
            rs.getString("state"), rs.getString("vector_id"), rs.getDouble("power")));
  }

  public List<RawRecords.MeltSample> meltSamples() {
    return jdbc.query("SELECT * FROM melt_sample ORDER BY session_id, sensor_clock, sequence_no",
        (rs, row) -> new RawRecords.MeltSample(
            rs.getString("id"), rs.getString("session_id"), rs.getLong("sequence_no"),
            rs.getLong("sensor_clock"), rs.getDouble("intensity")));
  }

  public List<RawRecords.MachineEvent> machineEvents() {
    return jdbc.query("SELECT * FROM machine_event ORDER BY session_id, scanner_clock, sequence_no",
        (rs, row) -> {
          long counterValue = rs.getLong("event_counter");
          Long eventCounter = rs.wasNull() ? null : counterValue;
          return new RawRecords.MachineEvent(
            rs.getString("id"), rs.getString("session_id"), rs.getLong("sequence_no"),
            rs.getLong("scanner_clock"), rs.getString("event_type"),
            eventCounter, rs.getString("payload"));
        });
  }

  public List<RawRecords.CalibrationMarker> markers() {
    return jdbc.query("SELECT * FROM calibration_marker ORDER BY session_id, scanner_clock",
        (rs, row) -> new RawRecords.CalibrationMarker(
            rs.getString("id"), rs.getString("session_id"), rs.getString("layer_id"),
            rs.getInt("pair_index"), rs.getLong("scanner_clock"),
            rs.getLong("sensor_clock")));
  }

  public List<RawRecords.Decision> decisions() {
    return jdbc.query("SELECT * FROM decision ORDER BY sequence_no, id",
        (rs, row) -> new RawRecords.Decision(
            rs.getString("id"), rs.getString("decision_type"), rs.getString("target_id"),
            rs.getString("session_id"), rs.getString("layer_id"),
            nullableLong(rs, "scanner_clock"), nullableLong(rs, "sensor_clock_start"),
            nullableLong(rs, "sensor_clock_end"), rs.getString("reason"),
            rs.getString("payload"), rs.getString("created_at"), rs.getLong("sequence_no")));
  }

  private Long nullableLong(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
    long value = resultSet.getLong(column);
    return resultSet.wasNull() ? null : value;
  }

  public RawRecords.RawBundle rawBundle() {
    return new RawRecords.RawBundle(scannerPoints(), laserCommands(), meltSamples(),
        machineEvents(), markers(), decisions());
  }

  @Transactional
  public long saveVersion(String reason, String headId, int decisionCount, String fixtureRevision) {
    jdbc.update("""
        INSERT INTO alignment_version(created_at,trigger_reason,decision_head_id,decision_count,fixture_revision)
        VALUES (?,?,?,?,?)
        """, java.time.Instant.now().toString(), reason, headId, decisionCount, fixtureRevision);
    return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
  }

  @Transactional
  public void saveResult(long versionId, AlignmentResults.Calibration ignored,
      List<AlignmentResults.PathSegment> segments, List<AlignmentResults.Aligned> aligned,
      List<AlignmentResults.SegmentResidual> segmentResiduals,
      List<AlignmentResults.MarkerResidual> markerResiduals,
      List<AlignmentResults.Diagnostic> diagnostics) {
    for (AlignmentResults.PathSegment segment : segments) {
      List<AlignmentResults.Aligned> counted = aligned.stream()
          .filter(item -> segment.segmentId.equals(item.segmentId))
          .filter(item -> !item.nonLaser && "ALIGNED".equals(item.status))
          .toList();
      double meanIntensity = counted.stream()
          .mapToDouble(item -> item.intensity)
          .average().orElse(Double.NaN);
      jdbc.update("""
          INSERT INTO track_segment
          (version_id,segment_id,session_id,layer_id,vector_id,part_index,start_scanner_clock,
           end_scanner_clock,x1,y1,x2,y2,z,is_laser,sample_count,mean_intensity)
          VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
          """, versionId, segment.segmentId, segment.sessionId, segment.layerId,
          segment.vectorId, segment.partIndex, segment.startClock, segment.endClock,
          segment.x1, segment.y1, segment.x2, segment.y2, segment.z, segment.laser ? 1 : 0,
          counted.size(), Double.isNaN(meanIntensity) ? null : meanIntensity);
    }
    for (AlignmentResults.Aligned item : aligned) {
      jdbc.update("""
          INSERT INTO aligned_sample
          (version_id,melt_sample_id,session_id,layer_id,segment_id,vector_id,sensor_clock,
           mapped_scanner_clock,x,y,z,intensity,status,risk_codes,clock_residual,
           position_residual,is_non_laser,evidence)
          VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
          """, versionId, item.meltSampleId, item.sessionId, item.layerId, item.segmentId,
          item.vectorId, item.sensorClock, item.mappedScannerClock, item.x, item.y, item.z,
          item.intensity, item.status, item.riskCodes, item.clockResidual,
          item.positionResidual, item.nonLaser ? 1 : 0, item.evidence);
    }
    for (AlignmentResults.SegmentResidual residual : segmentResiduals) {
      jdbc.update("""
          INSERT INTO segment_residual
          (version_id,segment_id,marker_count,sample_count,clock_residual_rms,
           position_residual_rms,evidence)
          VALUES (?,?,?,?,?,?,?)
          """, versionId, residual.segmentId(), residual.markerCount(), residual.sampleCount(),
          residual.clockResidualRms(), residual.positionResidualRms(), residual.evidence());
    }
    for (AlignmentResults.MarkerResidual residual : markerResiduals) {
      jdbc.update("""
          INSERT INTO marker_residual
          (version_id,marker_id,session_id,layer_id,active,observed_sensor_clock,
           predicted_sensor_clock,residual,evidence)
          VALUES (?,?,?,?,?,?,?,?,?)
          """, versionId, residual.markerId(), residual.sessionId(), residual.layerId(),
          residual.active() ? 1 : 0, residual.observedSensorClock(),
          residual.predictedSensorClock(), residual.residual(), residual.evidence());
    }
    for (AlignmentResults.Diagnostic diagnostic : diagnostics) {
      jdbc.update("""
          INSERT INTO diagnostic
          (version_id,severity,code,channel,session_id,layer_id,message,evidence)
          VALUES (?,?,?,?,?,?,?,?)
          """, versionId, diagnostic.severity(), diagnostic.code(), diagnostic.channel(),
          diagnostic.sessionId(), diagnostic.layerId(), diagnostic.message(),
          diagnostic.evidence());
    }
  }
}
