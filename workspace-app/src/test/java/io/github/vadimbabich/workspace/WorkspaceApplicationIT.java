package io.github.vadimbabich.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

import io.github.vadimbabich.workspace.contract.model.Node;
import io.github.vadimbabich.workspace.contract.model.NodeKind;
import io.github.vadimbabich.workspace.contract.model.NodePage;
import io.github.vadimbabich.workspace.support.FixtureDatabase;
import io.github.vadimbabich.workspace.support.StatementLog;
import io.github.vadimbabich.workspace.support.WorkspaceIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

@WorkspaceIntegrationTest
class WorkspaceApplicationIT {

  @Autowired
  WebTestClient client;

  @Autowired
  FixtureDatabase fixtures;

  @BeforeEach
  void seed() {
    fixtures.deleteAllRows().block();
    fixtures.run("""
        insert into node (tenant_id, parent_id, kind_rank, name, created_by) values (1, null, 0, 'root', 'seed')
        """);
    fixtures.run("""
        insert into node (tenant_id, parent_id, kind_rank, name, size_bytes, created_by) values
          (1, 1, 1, 'b-notes.txt', 200, 'seed'),
          (1, 1, 0, 'Invoices', 0, 'seed'),
          (1, 1, 1, 'a-report.pdf', 100, 'seed')
        """);
  }

  @Test
  void aFolderListsFoldersFirstThenFilesByName() {
    NodePage page = client.mutateWith(mockJwt())
        .get().uri("/api/v1/folders/1/children?limit=10")
        .exchange()
        .expectStatus().isOk()
        .expectBody(NodePage.class)
        .returnResult().getResponseBody();

    assertThat(page).isNotNull();
    assertThat(page.getItems()).extracting(Node::getName)
        .containsExactly("Invoices", "a-report.pdf", "b-notes.txt");
    assertThat(page.getItems().getFirst().getKind()).isEqualTo(NodeKind.FOLDER);
  }

  @Test
  void aFolderListingRunsOneStatement() {
    try (StatementLog statementLog = StatementLog.open()) {
      client.mutateWith(mockJwt())
          .get().uri("/api/v1/folders/1/children?limit=10")
          .exchange()
          .expectStatus().isOk();

      assertThat(statementLog.statementsOn("node")).hasSize(1);
    }
  }

  // Only the status for now: the response body becomes application/problem+json with the error handler.
  @ParameterizedTest
  @ValueSource(ints = {-5, 1000})
  void aLimitOutsideTheContractIsABadRequest(int limit) {
    client.mutateWith(mockJwt())
        .get().uri("/api/v1/folders/1/children?limit=" + limit)
        .exchange()
        .expectStatus().isBadRequest();
  }

  @Test
  void theApiRefusesACallerWithoutAToken() {
    client.get().uri("/api/v1/folders/1/children").exchange().expectStatus().isUnauthorized();
  }

  @Test
  void healthAndMetricsAreReachableForTheComposeStack() {
    client.get().uri("/actuator/health/readiness").exchange().expectStatus().isOk();
    client.get().uri("/actuator/prometheus").exchange().expectStatus().isOk();
  }
}
