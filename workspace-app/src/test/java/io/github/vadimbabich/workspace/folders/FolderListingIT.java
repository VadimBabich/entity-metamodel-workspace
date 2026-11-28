package io.github.vadimbabich.workspace.folders;

import static io.github.vadimbabich.workspace.support.KeycloakDirectory.ACME_EDITORS;
import static io.github.vadimbabich.workspace.support.KeycloakDirectory.ACME_ORGANIZATION;
import static io.github.vadimbabich.workspace.support.ListingRequests.TOP_LEVEL;
import static io.github.vadimbabich.workspace.support.ListingRequests.childrenPath;
import static io.github.vadimbabich.workspace.support.ListingRequests.pageAt;
import static io.github.vadimbabich.workspace.support.TestCallers.ACME;
import static io.github.vadimbabich.workspace.support.TestCallers.ALICE;
import static io.github.vadimbabich.workspace.support.TestCallers.alice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

import io.github.vadimbabich.workspace.contract.model.Node;
import io.github.vadimbabich.workspace.support.FixtureDatabase;
import io.github.vadimbabich.workspace.support.KeycloakDirectory;
import io.github.vadimbabich.workspace.support.ProblemTypes;
import io.github.vadimbabich.workspace.support.QueryPreviewLog;
import io.github.vadimbabich.workspace.support.QueryPreviewLog.QueryPreview;
import io.github.vadimbabich.workspace.support.StatementLog;
import io.github.vadimbabich.workspace.support.TestCallers;
import io.github.vadimbabich.workspace.support.WorkspaceIntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.JwtMutator;
import org.springframework.test.web.reactive.server.WebTestClient;

@WorkspaceIntegrationTest
@DisplayName("Folder listings through typed queries")
class FolderListingIT {

  private static final String HEIDI = "sub-heidi";
  private static final long PROJECTS = 1;
  private static final long NOTES = 3;
  private static final long HIDDEN = 4;
  private static final long TRASHED = 5;
  private static final long GLOBEX_FOLDER = 6;
  private static final long LISTER = 1;
  private static final long VIEWER = 2;

  @Autowired
  WebTestClient client;

  @Autowired
  FixtureDatabase fixtures;

  @Autowired
  KeycloakDirectory keycloak;

  @BeforeEach
  void seedTwoCompaniesWithFolders() {
    keycloak.reset();
    keycloak.addMember(ALICE, ACME_ORGANIZATION, ACME_EDITORS);
    keycloak.addMember(HEIDI, ACME_ORGANIZATION);
    fixtures.deleteAllRows().block();
    fixtures.run("insert into company (alias, name) values ('acme', 'Acme'), ('globex', 'Globex')");
    fixtures.run("insert into user_group (company_id, keycloak_id, name) values (1, '%s', 'editors')"
        .formatted(ACME_EDITORS));
    fixtures.run("""
        insert into node (company_id, parent_id, kind_rank, name, created_by, deleted_at) values
          (1, null, 0, 'Projects', 'seed', null),
          (1, 1, 0, 'Plans', 'seed', null),
          (1, 1, 1, 'notes.txt', 'seed', null),
          (1, null, 0, 'Hidden', 'seed', null),
          (1, null, 0, 'Trashed', 'seed', now()),
          (2, null, 0, 'Globex', 'seed', null)
        """);
    fixtures.run("""
        insert into acl.access_grant (company_id, node_id, subject_kind, subject_id, role) values
          (2, 6, 3, 2, 5),
          (1, 5, 3, 1, 5)
        """);
  }

  @ParameterizedTest
  @ValueSource(longs = {1, 2, 3, 4, 5})
  @DisplayName("schema-qualified table: a grant in the acl schema lists the folder with its role's capabilities")
  void aGroupGrantOfEveryRoleListsTheFolderWithTheRolesCapabilities(long role) {
    grantEditors(PROJECTS, role);

    List<Node> children = childrenOf(PROJECTS, alice());

    assertThat(children).extracting(Node::getName).containsExactly("Plans", "notes.txt");
    assertThat(children.get(0).getCapabilities().getList()).isTrue();
    assertThat(children.get(0).getCapabilities().getRead()).isEqualTo(role >= VIEWER);
    assertThat(children.get(1).getCapabilities().getList()).isFalse();
    assertThat(children.get(1).getCapabilities().getRead()).isEqualTo(role >= VIEWER);
    assertThat(children.get(0).getParentId()).isEqualTo(PROJECTS);
    assertThat(children.get(0).getCreatedAt()).isNotNull();
  }

  @Test
  void aTopLevelFolderIsSentWithoutAParentIdMember() {
    grantEditors(PROJECTS, LISTER);

    String topLevelBody = client.mutateWith(alice()).get().uri(TOP_LEVEL).exchange()
        .expectStatus().isOk()
        .expectBody(String.class)
        .returnResult().getResponseBody();

    assertThat(topLevelBody).contains("\"name\":\"Projects\"").doesNotContain("parentId");
    client.mutateWith(alice()).get().uri(childrenPath(PROJECTS)).exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.items[0].parentId").isEqualTo(PROJECTS);
  }

  @Test
  void theTopLevelListsOnlyFoldersTheCallerHoldsAGrantOn() {
    grantEditors(PROJECTS, LISTER);

    List<Node> topLevel = topLevelFolders(alice());

    assertThat(topLevel).extracting(Node::getName).containsExactly("Projects");
    assertThat(topLevel.getFirst().getCapabilities().getList()).isTrue();
    assertThat(topLevel.getFirst().getParentId()).isNull();
  }

  @Test
  void aFolderWithoutAGrantIsNotFound() {
    grantEditors(PROJECTS, LISTER);

    expectNotFound(HIDDEN, alice());
  }

  @Test
  void anotherCompanysFolderIsNotFoundAndNotListed() {
    grantEditors(PROJECTS, LISTER);

    expectNotFound(GLOBEX_FOLDER, alice());
    assertThat(topLevelFolders(alice())).extracting(Node::getName).doesNotContain("Globex");
  }

  @ParameterizedTest
  @ValueSource(longs = {NOTES, TRASHED, 999})
  void aFileATrashedFolderAndAnAbsentIdAreNotFoundAlike(long itemId) {
    grantEditors(PROJECTS, LISTER);

    expectNotFound(itemId, alice());
  }

  @Test
  void anAdministratorWithoutAGrantSeesWhatAnyoneWithoutOneSees() {
    grantEditors(PROJECTS, LISTER);
    JwtMutator administrator = mockJwt().jwt(token -> token
        .subject(ALICE)
        .claim("organization", List.of(ACME))
        .claim("realm_access", Map.of("roles", List.of("company-admin"))));

    expectNotFound(HIDDEN, administrator);
    assertThat(topLevelFolders(administrator)).extracting(Node::getName).containsExactly("Projects");
  }

  @Test
  void aDirectGrantAboveTheInheritedRoleRaisesTheItemsCapabilities() {
    grantEditors(PROJECTS, LISTER);
    grantEditors(NOTES, VIEWER);

    List<Node> children = childrenOf(PROJECTS, alice());

    assertThat(children.get(0).getCapabilities().getRead()).isFalse();
    assertThat(children.get(1).getCapabilities().getRead()).isTrue();
  }

  @Test
  @DisplayName("distinct: a folder reached by two of the caller's grants lists each child once")
  void aFolderReachedByTwoGrantsListsEachChildOnce() {
    grantEditors(PROJECTS, LISTER);
    fixtures.run("""
        insert into acl.access_grant (company_id, node_id, subject_kind, subject_id, role) values (1, 1, 3, 1, 2)
        """);

    assertThat(childrenOf(PROJECTS, alice())).extracting(Node::getName).containsExactly("Plans", "notes.txt");
    assertThat(topLevelFolders(alice())).extracting(Node::getName).containsExactly("Projects");
  }

  @Test
  void aCallerInNoGroupIsReachedByAGrantToTheirCompany() {
    fixtures.run("""
        insert into acl.access_grant (company_id, node_id, subject_kind, subject_id, role) values (1, 1, 3, 1, 1)
        """);

    assertThat(topLevelFolders(heidi())).extracting(Node::getName).containsExactly("Projects");
    assertThat(childrenOf(PROJECTS, heidi())).extracting(Node::getName).containsExactly("Plans", "notes.txt");
  }

  @Test
  void aCallerInNoGroupIsReachedByAGrantToThemselves() {
    topLevelFolders(heidi());
    fixtures.run("""
        insert into acl.access_grant (company_id, node_id, subject_kind, subject_id, role)
        select 1, 4, 1, app_user_id, 2 from app_user where sub = '%s'
        """.formatted(HEIDI));

    assertThat(topLevelFolders(heidi())).extracting(Node::getName).containsExactly("Hidden");
    assertThat(childrenOf(HIDDEN, heidi())).isEmpty();
    expectNotFound(PROJECTS, heidi());
  }

  @Test
  void aListingRunsItsStatementsInOneReadOnlyTransaction() {
    grantEditors(PROJECTS, LISTER);
    topLevelFolders(alice());

    try (StatementLog statementLog = StatementLog.open()) {
      childrenOf(PROJECTS, alice());

      assertThat(statementLog.onlyTransactionBegunBy("BEGIN READ ONLY"))
          .anyMatch(statement -> statement.contains("group_membership"))
          .anyMatch(statement -> statement.contains("access_grant"))
          .anyMatch(statement -> statement.contains("FROM \"node\""));
    }
  }

  @Test
  @DisplayName("slice: a folder page costs three statements on grants and the top level two, none of them a count")
  void aFolderListingRunsThreeAccessStatementsAndTheTopLevelTwo() {
    grantEditors(PROJECTS, LISTER);
    topLevelFolders(alice());

    try (StatementLog statementLog = StatementLog.open()) {
      childrenOf(PROJECTS, alice());

      assertThat(statementLog.statementsOn("access_grant")).hasSize(3);
    }

    try (StatementLog statementLog = StatementLog.open()) {
      topLevelFolders(alice());

      assertThat(statementLog.statementsOn("access_grant")).hasSize(2);
    }
  }

  @Test
  @DisplayName("preview log: a listing logs a redacted preview of each typed query, in the order it runs")
  void aListingLogsARedactedPreviewOfEachTypedQueryInTheOrderItRuns() {
    grantEditors(PROJECTS, LISTER);
    topLevelFolders(alice());

    try (StatementLog statementLog = StatementLog.open();
        QueryPreviewLog previewLog = QueryPreviewLog.open()) {
      childrenOf(PROJECTS, alice());

      List<String> executedSelects = statementLog.selects();
      List<QueryPreview> previews = previewLog.previews();

      assertThat(previews).hasSameSizeAs(executedSelects);

      for (int index = 0; index < previews.size(); index++) {
        QueryPreview preview = previews.get(index);

        assertThat(executedSelects.get(index)).startsWith(preview.statement());
        assertThat(preview.bindingLines()).hasSize(preview.markerCount());
        assertThat(preview.bindingLinesShowingAValue()).isEmpty();
      }
    }
  }

  private void grantEditors(long nodeId, long role) {
    fixtures.run("""
        insert into acl.access_grant (company_id, node_id, subject_kind, subject_id, role) values (1, %d, 2, 1, %d)
        """.formatted(nodeId, role));
  }

  private static JwtMutator heidi() {
    return TestCallers.memberOf(ACME, HEIDI);
  }

  private List<Node> childrenOf(long folderId, JwtMutator caller) {
    return pageAt(client, childrenPath(folderId), caller).getItems();
  }

  private List<Node> topLevelFolders(JwtMutator caller) {
    return pageAt(client, TOP_LEVEL, caller).getItems();
  }

  private void expectNotFound(long folderId, JwtMutator caller) {
    client.mutateWith(caller).get().uri(childrenPath(folderId)).exchange()
        .expectStatus().isEqualTo(HttpStatus.NOT_FOUND)
        .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody().jsonPath("$.type").isEqualTo(ProblemTypes.ITEM_NOT_FOUND);
  }
}
