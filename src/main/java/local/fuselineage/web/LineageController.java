package local.fuselineage.web;

import java.util.Map;
import local.fuselineage.domain.RawRecords;
import local.fuselineage.repository.LineageRepository;
import local.fuselineage.service.LineageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class LineageController {
  private final LineageService lineageService;
  private final LineageRepository repository;

  public LineageController(LineageService lineageService, LineageRepository repository) {
    this.lineageService = lineageService;
    this.repository = repository;
  }

  @GetMapping("/state")
  public Map<String, Object> state() {
    return lineageService.state();
  }

  @PostMapping("/fixture/load")
  public Map<String, Object> loadFixture() {
    return lineageService.loadFixture();
  }

  @PostMapping("/reset")
  public Map<String, Object> reset() {
    return lineageService.resetDatabase();
  }

  @PostMapping("/decisions")
  public Map<String, Object> addDecision(@RequestBody Map<String, Object> request) {
    return lineageService.addDecision(request);
  }

  @PostMapping("/recompute")
  public Map<String, Object> recompute(@RequestBody(required = false) Map<String, Object> request) {
    String reason = request == null ? "手动重算" : String.valueOf(request.getOrDefault("reason", "手动重算"));
    return lineageService.recompute(reason);
  }

  @GetMapping("/export")
  public Map<String, Object> exportRun() {
    return lineageService.exportRun();
  }

  @PostMapping("/import")
  public Map<String, Object> importRun(@RequestBody ImportRequest request) {
    if (request == null || request.raw() == null) {
      throw new IllegalArgumentException("导入内容必须包含 raw");
    }
    return lineageService.importBundle(request.raw());
  }

  @GetMapping("/raw-count")
  public Map<String, Integer> rawCount() {
    return Map.of("rawRows", repository.countRawSamples());
  }

  public record ImportRequest(RawRecords.RawBundle raw) {
  }
}
