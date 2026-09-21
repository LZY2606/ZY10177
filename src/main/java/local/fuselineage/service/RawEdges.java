package local.fuselineage.service;

import java.util.ArrayList;
import java.util.List;
import local.fuselineage.domain.RawRecords;

final class RawEdges {
  private RawEdges() {
  }

  record Edge(
      long startClock,
      long endClock,
      double startX,
      double startY,
      double startZ,
      double endX,
      double endY,
      double endZ,
      String startLayer,
      String startVector,
      String endLayer,
      String endVector) {
  }

  static List<Edge> from(List<RawRecords.ScannerPoint> points) {
    List<Edge> edges = new ArrayList<>();
    for (int index = 1; index < points.size(); index++) {
      RawRecords.ScannerPoint start = points.get(index - 1);
      RawRecords.ScannerPoint end = points.get(index);
      if (end.scannerClock() > start.scannerClock()) {
        edges.add(new Edge(start.scannerClock(), end.scannerClock(),
            start.x(), start.y(), start.z(), end.x(), end.y(), end.z(),
            start.layerId(), start.vectorId(), end.layerId(), end.vectorId()));
      }
    }
    return edges;
  }
}
