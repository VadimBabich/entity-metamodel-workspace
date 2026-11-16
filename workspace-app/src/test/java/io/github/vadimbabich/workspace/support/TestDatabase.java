package io.github.vadimbabich.workspace.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PostgreSQL for integration tests, as a bean of the shared test context, from the image the local stack runs. The
 * container starts once per context and stops after the context's other beans.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDatabase {

  @Bean
  @ServiceConnection
  PostgreSQLContainer postgres() {
    return new PostgreSQLContainer(ComposeImages.imageOf("postgres"));
  }
}
