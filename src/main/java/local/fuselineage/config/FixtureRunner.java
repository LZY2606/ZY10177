package local.fuselineage.config;

import local.fuselineage.repository.LineageRepository;
import local.fuselineage.service.LineageService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class FixtureRunner implements ApplicationRunner {
  private final LineageRepository repository;
  private final LineageService lineageService;

  public FixtureRunner(LineageRepository repository, LineageService lineageService) {
    this.repository = repository;
    this.lineageService = lineageService;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (repository.countRawSamples() == 0) {
      lineageService.loadFixture();
    }
  }
}
