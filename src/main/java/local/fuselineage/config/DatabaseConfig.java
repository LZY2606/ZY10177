package local.fuselineage.config;

import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration
public class DatabaseConfig {
  @Bean
  public DataSource dataSource(@Value("${app.database-path}") String databasePath) {
    try {
      Path path = Path.of(databasePath).toAbsolutePath().normalize();
      Path parent = path.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      DriverManagerDataSource dataSource = new DriverManagerDataSource();
      dataSource.setDriverClassName("org.sqlite.JDBC");
      dataSource.setUrl("jdbc:sqlite:" + path);
      return dataSource;
    } catch (Exception exception) {
      throw new IllegalStateException("无法创建 SQLite 数据库: " + databasePath, exception);
    }
  }
}
