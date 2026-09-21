package local.fuselineage.domain;

import java.util.List;

public final class AlignmentResults {
  private AlignmentResults() {
  }

  public record Calibration(
      String sessionId, double slope, double intercept, long minClock, long maxClock,
      long interval, List<String> activeMarkerIds, String evidence) {
  }

  public static final class PathSegment {
    public String segmentId;
    public String sessionId;
    public String layerId;
    public String vectorId;
    public int partIndex;
    public long startClock;
    public long endClock;
    public double x1;
    public double y1;
    public double x2;
    public double y2;
    public double z;
    public boolean laser;
  }

  public static final class Aligned {
    public String meltSampleId;
    public String sessionId;
    public String layerId;
    public String segmentId;
    public String vectorId;
    public long sensorClock;
    public Double mappedScannerClock;
    public Double x;
    public Double y;
    public Double z;
    public double intensity;
    public String status;
    public String riskCodes;
    public Double clockResidual;
    public Double positionResidual;
    public boolean nonLaser;
    public String evidence;
  }

  public record SegmentResidual(
      String segmentId, int markerCount, int sampleCount,
      double clockResidualRms, double positionResidualRms, String evidence) {
  }

  public record MarkerResidual(
      String markerId, String sessionId, String layerId, boolean active,
      long observedSensorClock, Double predictedSensorClock, Double residual, String evidence) {
  }

  public record Diagnostic(
      String severity, String code, String channel, String sessionId,
      String layerId, String message, String evidence) {
  }
}
