package local.fuselineage.fixture;

import java.util.ArrayList;
import java.util.List;
import local.fuselineage.domain.RawRecords;
import org.springframework.stereotype.Service;

@Service
public class FixtureService {
  public static final String FIXTURE_REVISION = "fixed-fixture-2026-09-21";

  public RawRecords.RawBundle baselineBundle() {
    List<RawRecords.ScannerPoint> points = new ArrayList<>();
    List<RawRecords.LaserCommand> commands = new ArrayList<>();
    List<RawRecords.MeltSample> samples = new ArrayList<>();
    List<RawRecords.MachineEvent> events = new ArrayList<>();
    List<RawRecords.CalibrationMarker> markers = new ArrayList<>();
    List<RawRecords.Decision> decisions = new ArrayList<>();

    addScanner(points, "S1-L1-0", "S1", "L1", 0, 0, 0.0, 0.0, 1.0, "V1");
    addScanner(points, "S1-L1-1", "S1", "L1", 1, 500, 10.0, 0.0, 1.0, "V2");
    addScanner(points, "S1-L1-2", "S1", "L1", 2, 1500, 10.0, 10.0, 1.0, "V2");

    addScanner(points, "S1-L2-0", "S1", "L2", 10, 1601, 0.0, 0.0, 2.0, "V4");
    addScanner(points, "S1-L2-1", "S1", "L2", 11, 2600, 20.0, 0.0, 2.0, "V5");
    addScanner(points, "S1-L2-2", "S1", "L2", 12, 3600, 20.0, 10.0, 2.0, "V7");
    addScanner(points, "S1-L2-3", "S1", "L2", 13, 6600, 40.0, 10.0, 2.0, "V7");

    addScanner(points, "S2-L3-0", "S2", "L3", 0, 0, 0.0, 0.0, 3.0, "W1");
    addScanner(points, "S2-L3-1", "S2", "L3", 1, 1000, 15.0, 0.0, 3.0, "W2");

    addLaser(commands, "S1-L1-C0", "S1", "L1", 0, 0, "ON", "V1", 180.0);
    addLaser(commands, "S1-L1-C1", "S1", "L1", 1, 500, "ON", "V2", 180.0);
    addLaser(commands, "S1-L1-C2", "S1", "L1", 2, 1500, "OFF", null, 0.0);

    addLaser(commands, "S1-L2-C0", "S1", "L2", 10, 1601, "ON", "V4", 210.0);
    addLaser(commands, "S1-L2-C1", "S1", "L2", 11, 2600, "OFF", null, 0.0);
    addLaser(commands, "S1-L2-C2", "S1", "L2", 12, 3600, "ON", "V7", 210.0);
    addLaser(commands, "S1-L2-C3", "S1", "L2", 13, 6600, "ON", "V7", 210.0);
    addLaser(commands, "S1-L2-C4", "S1", "L2", 14, 6601, "OFF", null, 0.0);

    addLaser(commands, "S2-L3-C0", "S2", "L3", 0, 0, "ON", "W1", 190.0);
    addLaser(commands, "S2-L3-C1", "S2", "L3", 1, 1001, "OFF", null, 0.0);

    addMelt(samples, "S1-M000", "S1", 0, 1000, 84.0);
    addMelt(samples, "S1-M001", "S1", 1, 1050, 92.0);
    addMelt(samples, "S1-M002", "S1", 2, 2002, 96.0);
    addMelt(samples, "S1-M003", "S1", 3, 2003, 98.0);
    addMelt(samples, "S1-M004", "S1", 4, 2600, 101.0);
    addMelt(samples, "S1-M005", "S1", 5, 3000, 12.0);
    addMelt(samples, "S1-M006", "S1", 6, 4000, 108.0);
    addMelt(samples, "S1-M007", "S1", 7, 7000, 118.0);
    addMelt(samples, "S1-M008", "S1", 8, 8001, 121.0);
    addMelt(samples, "S1-M009", "S1", 9, 2500, 30.0);
    addMelt(samples, "S1-M010", "S1", 10, 3660, 18.0);
    addMelt(samples, "S1-M011", "S1", 11, 10000, 123.0);
    addMelt(samples, "S2-M000", "S2", 0, 100, 77.0);

    events.add(new RawRecords.MachineEvent("E-BUILD-START", "S1", 0, 0,
        "BUILD_START", 1L, "{\"note\":\"固定合成构建开始\"}"));
    events.add(new RawRecords.MachineEvent("E-RESTART", "S2", 0, 0,
        "RESTARTED", 0L, "{\"note\":\"设备重启，扫描器计数归零，禁止跨会话连接\"}"));

    markers.add(new RawRecords.CalibrationMarker("T1", "S1", "L1", 0, 500, 1050));
    markers.add(new RawRecords.CalibrationMarker("T2", "S1", "L2", 1, 4000, 5400));

    decisions.add(new RawRecords.Decision("D-CONFIRM-T1", "CONFIRM_TROPHY", "T1", "S1",
        "L1", 500L, 1050L, null, "固定 fixture：确认第一层同步锦标",
        "{\"source\":\"fixed-fixture\"}", "2026-09-21T00:00:00Z", 1));
    decisions.add(new RawRecords.Decision("D-CONFIRM-T2", "CONFIRM_TROPHY", "T2", "S1",
        "L2", 4000L, 5400L, null, "固定 fixture：确认第二层同步锦标",
        "{\"source\":\"fixed-fixture\"}", "2026-09-21T00:00:01Z", 2));

    return new RawRecords.RawBundle(List.copyOf(points), List.copyOf(commands),
        List.copyOf(samples), List.copyOf(events), List.copyOf(markers), List.copyOf(decisions));
  }

  private void addScanner(List<RawRecords.ScannerPoint> points, String id, String session,
      String layer, long sequence, long clock, double x, double y, double z, String vector) {
    points.add(new RawRecords.ScannerPoint(id, session, layer, sequence, clock, x, y, z, vector));
  }

  private void addLaser(List<RawRecords.LaserCommand> commands, String id, String session,
      String layer, long sequence, long clock, String state, String vector, double power) {
    commands.add(new RawRecords.LaserCommand(id, session, layer, sequence, clock,
        state, vector, power));
  }

  private void addMelt(List<RawRecords.MeltSample> samples, String id, String session,
      long sequence, long clock, double intensity) {
    samples.add(new RawRecords.MeltSample(id, session, sequence, clock, intensity));
  }
}
