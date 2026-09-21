package local.fuselineage.domain;

import java.util.List;

public final class RawRecords {
  private RawRecords() {
  }

  public record ScannerPoint(
      String id, String sessionId, String layerId, long sequenceNo, long scannerClock,
      double x, double y, double z, String vectorId) {
  }

  public record LaserCommand(
      String id, String sessionId, String layerId, long sequenceNo, long scannerClock,
      String state, String vectorId, double power) {
  }

  public record MeltSample(
      String id, String sessionId, long sequenceNo, long sensorClock, double intensity) {
  }

  public record MachineEvent(
      String id, String sessionId, long sequenceNo, long scannerClock,
      String eventType, Long eventCounter, String payload) {
  }

  public record CalibrationMarker(
      String id, String sessionId, String layerId, int pairIndex,
      long scannerClock, long sensorClock) {
  }

  public record Decision(
      String id, String decisionType, String targetId, String sessionId, String layerId,
      Long scannerClock, Long sensorClockStart, Long sensorClockEnd,
      String reason, String payload, String createdAt, long sequenceNo) {
  }

  public record RawBundle(
      List<ScannerPoint> scannerPoints,
      List<LaserCommand> laserCommands,
      List<MeltSample> meltSamples,
      List<MachineEvent> machineEvents,
      List<CalibrationMarker> markers,
      List<Decision> decisions) {
  }
}
