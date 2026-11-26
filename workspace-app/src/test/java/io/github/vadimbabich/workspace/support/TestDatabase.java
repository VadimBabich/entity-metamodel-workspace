package io.github.vadimbabich.workspace.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * PostgreSQL for integration tests, as a bean of the shared test context, from the image and with the role script the
 * local stack runs. The application connects as the application role and Flyway as the migration role; the
 * container's own superuser only runs the role script.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDatabase {

  public static final String APPLICATION_ROLE = "workspace_app";
  public static final String MIGRATION_ROLE = "workspace_migrator";

  static final String APPLICATION_PASSWORD = "workspace-app-local-development-password";
  static final String MIGRATION_PASSWORD = "workspace-migrator-local-development-password";

  private static final String ROLE_SCRIPT = "../deploy/compose/postgres/initdb/01-roles.sql";
  private static final String CONTAINER_INIT_DIRECTORY = "/docker-entrypoint-initdb.d/";

  @Bean
  PostgreSQLContainer postgres() {
    MountableFile roleScript = MountableFile.forHostPath(ROLE_SCRIPT);

    return new PostgreSQLContainer(ComposeImages.imageOf("postgres"))
        .withCopyFileToContainer(roleScript, CONTAINER_INIT_DIRECTORY + "01-roles.sql");
  }

  @Bean
  DynamicPropertyRegistrar databaseIdentities(PostgreSQLContainer postgres) {
    return registry -> {
      registry.add("spring.r2dbc.url", () -> r2dbcUrlOf(postgres));
      registry.add("spring.r2dbc.username", () -> APPLICATION_ROLE);
      registry.add("spring.r2dbc.password", () -> APPLICATION_PASSWORD);
      registry.add("spring.flyway.url", postgres::getJdbcUrl);
      registry.add("spring.flyway.user", () -> MIGRATION_ROLE);
      registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
    };
  }

  @Bean
  FixtureDatabase fixtureDatabase(PostgreSQLContainer postgres) {
    return new FixtureDatabase(r2dbcUrlOf(postgres));
  }

  private static String r2dbcUrlOf(PostgreSQLContainer postgres) {
    String hostAndPort = postgres.getHost() + ":" + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT);

    return "r2dbc:postgresql://" + hostAndPort + "/" + postgres.getDatabaseName();
  }
}
