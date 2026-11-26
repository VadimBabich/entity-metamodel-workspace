package io.github.vadimbabich.workspace;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.vadimbabich.workspace.support.WorkspaceIntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@WorkspaceIntegrationTest
class MigrationNamingIT {

  private static final String APPLICATION_MIGRATIONS = "classpath:db/migration";
  private static final String MISNAMED_MIGRATION = "classpath:db/misnamed-migration";

  @Autowired
  Flyway applicationFlyway;

  @Test
  void theApplicationsMigrationsValidate() {
    Flyway applicationMigrations = flywayOver(APPLICATION_MIGRATIONS);

    assertThatCode(applicationMigrations::validate).doesNotThrowAnyException();
  }

  @Test
  void aMisnamedMigrationFailsValidation() {
    Flyway withMisnamedMigration = flywayOver(APPLICATION_MIGRATIONS, MISNAMED_MIGRATION);

    assertThatThrownBy(withMisnamedMigration::validate)
        .hasMessageContaining("Invalid versioned migration name format: V99_misnamed.sql");
  }

  private Flyway flywayOver(String... locations) {
    Configuration applicationConfiguration = applicationFlyway.getConfiguration();

    return Flyway.configure()
        .configuration(applicationConfiguration)
        .locations(locations)
        .load();
  }
}
