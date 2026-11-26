package io.github.vadimbabich.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vadimbabich.workspace.contract.model.Node;
import io.github.vadimbabich.workspace.contract.model.NodeKind;
import io.github.vadimbabich.workspace.contract.model.NodePage;
import io.github.vadimbabich.workspace.support.FixtureDatabase;
import io.github.vadimbabich.workspace.support.TestCallers;
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
    fixtures.run("insert into company (alias, name) values ('acme', 'Acme')");
    fixtures.run("""
        insert into node (company_id, parent_id, kind_rank, name, created_by) values (1, null, 0, 'root', 'seed')
        """);
    fixtures.run("""
        insert into node (company_id, parent_id, kind_rank, name, size_bytes, created_by) values
          (1, 1, 1, 'b-notes.txt', 200, 'seed'),
          (1, 1, 0, 'Invoices', 0, 'seed'),
          (1, 1, 1, 'a-report.pdf', 100, 'seed')
        """);
    fixtures.run("""
        insert into acl.access_grant (company_id, node_id, subject_kind, subject_id, role) values (1, 1, 3, 1, 2)
        """);
  }

  @Test
  void aFolderListsFoldersFirstThenFilesByName() {
    NodePage page = client.mutateWith(TestCallers.acmeMember())
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
  void namesSortByteByByteSoCapitalsPrecedeLowercaseAndAccentsComeLast() {
    fixtures.run("""
        insert into node (company_id, parent_id, kind_rank, name, created_by) values (1, 1, 0, 'Sorting', 'seed')
        """);
    fixtures.run("""
        insert into node (company_id, parent_id, kind_rank, name, created_by) values
          (1, 5, 1, 'a', 'seed'),
          (1, 5, 1, 'Ä', 'seed'),
          (1, 5, 1, 'B', 'seed')
        """);

    NodePage page = client.mutateWith(TestCallers.acmeMember())
        .get().uri("/api/v1/folders/5/children?limit=10")
        .exchange()
        .expectStatus().isOk()
        .expectBody(NodePage.class)
        .returnResult().getResponseBody();

    assertThat(page).isNotNull();
    assertThat(page.getItems()).extracting(Node::getName).containsExactly("B", "a", "Ä");
  }

  @ParameterizedTest
  @ValueSource(ints = {-5, 1000})
  void aLimitOutsideTheContractIsABadRequest(int limit) {
    client.mutateWith(TestCallers.acmeMember())
        .get().uri("/api/v1/folders/1/children?limit=" + limit)
        .exchange()
        .expectStatus().isBadRequest();
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "9007199254740992", "abc"})
  void aFolderIdOutsideTheContractIsABadRequest(String folderId) {
    client.mutateWith(TestCallers.acmeMember())
        .get().uri("/api/v1/folders/" + folderId + "/children")
        .exchange()
        .expectStatus().isBadRequest();
  }

  @Test
  void theApiRefusesACallerWithoutAToken() {
    client.get().uri("/api/v1/folders/1/children").exchange().expectStatus().isUnauthorized();
  }

}
