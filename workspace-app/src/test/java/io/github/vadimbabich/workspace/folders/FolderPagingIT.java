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

import io.github.vadimbabich.workspace.contract.model.Node;
import io.github.vadimbabich.workspace.contract.model.NodePage;
import io.github.vadimbabich.workspace.support.FixtureDatabase;
import io.github.vadimbabich.workspace.support.KeycloakDirectory;
import io.github.vadimbabich.workspace.support.ProblemTypes;
import io.github.vadimbabich.workspace.support.TestCallers;
import io.github.vadimbabich.workspace.support.WorkspaceIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.JwtMutator;
import org.springframework.test.web.reactive.server.WebTestClient;

@WorkspaceIntegrationTest
@DisplayName("Keyset paging with a signed cursor")
class FolderPagingIT {

  private static final String FRANK = "sub-frank";
  private static final String PROJECTS_CHILDREN = childrenPath(1);
  private static final String DESIGNS_CHILDREN = childrenPath(5);
  private static final String INVALID_CURSOR = ProblemTypes.BASE + "invalid-cursor";
  private static final String CURSOR_OF_ANOTHER_QUERY = ProblemTypes.BASE + "cursor-of-another-query";
  private static final String INVALID_REQUEST = ProblemTypes.BASE + "invalid-request";
  private static final int WALKING_PAGE_SIZE = 2;
  private static final List<String> PROJECTS_IN_ORDER =
      List.of("alpha", "beta", "gamma", "a.txt", "b.txt", "c.txt", "d.txt");
  private static final List<String> TOP_LEVEL_IN_ORDER = List.of("Archive", "Budget", "Clients", "Designs", "Projects");

  @Autowired
  WebTestClient client;

  @Autowired
  FixtureDatabase fixtures;

  @Autowired
  KeycloakDirectory keycloak;

  @BeforeEach
  void seedFoldersGrantedToTheEditors() {
    keycloak.reset();
    keycloak.addMember(ALICE, ACME_ORGANIZATION, ACME_EDITORS);
    keycloak.addMember(FRANK, ACME_ORGANIZATION, ACME_EDITORS);
    fixtures.deleteAllRows().block();
    fixtures.run("insert into company (alias, name) values ('acme', 'Acme'), ('globex', 'Globex')");
    fixtures.run("insert into user_group (company_id, keycloak_id, name) values (1, '%s', 'editors')"
        .formatted(ACME_EDITORS));
    fixtures.run("""
        insert into node (company_id, parent_id, kind_rank, name, created_by) values
          (1, null, 0, 'Projects', 'seed'),
          (1, null, 0, 'Archive', 'seed'),
          (1, null, 0, 'Budget', 'seed'),
          (1, null, 0, 'Clients', 'seed'),
          (1, null, 0, 'Designs', 'seed'),
          (1, 1, 1, 'd.txt', 'seed'),
          (1, 1, 0, 'beta', 'seed'),
          (1, 1, 1, 'a.txt', 'seed'),
          (1, 1, 0, 'gamma', 'seed'),
          (1, 1, 1, 'c.txt', 'seed'),
          (1, 1, 0, 'alpha', 'seed'),
          (1, 1, 1, 'b.txt', 'seed'),
          (1, 5, 1, 'sketch.png', 'seed')
        """);
    fixtures.run("""
        insert into acl.access_grant (company_id, node_id, subject_kind, subject_id, role)
        select 1, node_id, 2, 1, 4 from node where parent_id is null and company_id = 1
        """);
  }

  @Test
  @DisplayName("keyset: walking a folder page by page returns every child once, in the listing order")
  void walkingAFolderPageByPageReturnsEveryChildOnceInOrder() {
    assertThat(namesWalking(PROJECTS_CHILDREN, alice())).containsExactlyElementsOf(PROJECTS_IN_ORDER);
  }

  @Test
  @DisplayName("keyset: walking the top level page by page returns every folder once, by name")
  void walkingTheTopLevelPageByPageReturnsEveryFolderOnceInOrder() {
    assertThat(namesWalking(TOP_LEVEL, alice())).containsExactlyElementsOf(TOP_LEVEL_IN_ORDER);
  }

  @Test
  @DisplayName("slice: the last page carries no next cursor, every other page does")
  void onlyAPageWithMoreToFollowCarriesANextCursor() {
    NodePage firstPage = pageAt(client, PROJECTS_CHILDREN + "?limit=4", alice());
    NodePage lastPage = pageAt(client, PROJECTS_CHILDREN + "?limit=4&cursor=" + firstPage.getNextCursor(), alice());
    NodePage wholeFolder = pageAt(client, PROJECTS_CHILDREN + "?limit=7", alice());

    assertThat(firstPage.getNextCursor()).isNotBlank();
    assertThat(namesOf(lastPage)).containsExactly("b.txt", "c.txt", "d.txt");
    assertThat(lastPage.getNextCursor()).isNull();
    assertThat(wholeFolder.getNextCursor()).isNull();
  }

  @Test
  @DisplayName("keyset: the limit may change between pages")
  void theLimitMayChangeBetweenPages() {
    NodePage firstPage = pageAt(client, PROJECTS_CHILDREN + "?limit=2", alice());
    NodePage secondPage = pageAt(client, PROJECTS_CHILDREN + "?limit=3&cursor=" + firstPage.getNextCursor(), alice());

    assertThat(namesOf(secondPage)).containsExactly("gamma", "a.txt", "b.txt");
  }

  @Test
  @DisplayName("keyset: a child added between pages is never listed twice")
  void aChildAddedBetweenPagesIsNeverListedTwice() {
    NodePage firstPage = pageAt(client, PROJECTS_CHILDREN + "?limit=3", alice());
    fixtures.run("""
        insert into node (company_id, parent_id, kind_rank, name, created_by) values
          (1, 1, 0, 'aardvark', 'seed'),
          (1, 1, 1, 'e.txt', 'seed')
        """);

    List<String> names = new ArrayList<>(namesOf(firstPage));
    names.addAll(namesWalkingFrom(PROJECTS_CHILDREN, 3, firstPage.getNextCursor(), alice()));

    assertThat(names).doesNotHaveDuplicates().contains("e.txt").doesNotContain("aardvark");
  }

  @Test
  @DisplayName("a cursor reused by another caller belongs to another query")
  void aCursorReusedByAnotherCallerBelongsToAnotherQuery() {
    String alicesCursor = pageAt(client, PROJECTS_CHILDREN + "?limit=2", alice()).getNextCursor();

    expectRefusal(PROJECTS_CHILDREN + "?limit=2&cursor=" + alicesCursor, frank(), CURSOR_OF_ANOTHER_QUERY);
  }

  @Test
  @DisplayName("a cursor reused on another folder, or between a folder and the top level, belongs to another query")
  void aCursorReusedOnAnotherListingBelongsToAnotherQuery() {
    String projectsCursor = pageAt(client, PROJECTS_CHILDREN + "?limit=2", alice()).getNextCursor();
    String topLevelCursor = pageAt(client, TOP_LEVEL + "?limit=2", alice()).getNextCursor();

    expectRefusal(DESIGNS_CHILDREN + "?cursor=" + projectsCursor, alice(), CURSOR_OF_ANOTHER_QUERY);
    expectRefusal(TOP_LEVEL + "?cursor=" + projectsCursor, alice(), CURSOR_OF_ANOTHER_QUERY);
    expectRefusal(PROJECTS_CHILDREN + "?cursor=" + topLevelCursor, alice(), CURSOR_OF_ANOTHER_QUERY);
  }

  @Test
  @DisplayName("exists: a cursor whose item was trashed between pages is invalid, never an empty page")
  void aCursorWhoseItemWasTrashedIsInvalid() {
    NodePage firstPage = pageAt(client, PROJECTS_CHILDREN + "?limit=2", alice());
    fixtures.run("update node set deleted_at = now() where name = 'beta'");

    expectRefusal(PROJECTS_CHILDREN + "?limit=2&cursor=" + firstPage.getNextCursor(), alice(), INVALID_CURSOR);
  }

  @Test
  @DisplayName("a cursor whose item moved to another folder between pages is invalid")
  void aCursorWhoseItemMovedToAnotherFolderIsInvalid() {
    NodePage firstPage = pageAt(client, PROJECTS_CHILDREN + "?limit=2", alice());
    fixtures.run("update node set parent_id = 5 where name = 'beta'");

    expectRefusal(PROJECTS_CHILDREN + "?limit=2&cursor=" + firstPage.getNextCursor(), alice(), INVALID_CURSOR);
  }

  @Test
  @DisplayName("a cursor for a folder the caller may no longer list answers 404 before the cursor is read")
  void aCursorForAFolderNoLongerListableIsNotFound() {
    NodePage firstPage = pageAt(client, PROJECTS_CHILDREN + "?limit=2", alice());
    fixtures.run("delete from acl.access_grant where node_id = 1");

    client.mutateWith(alice()).get().uri(PROJECTS_CHILDREN + "?cursor=" + firstPage.getNextCursor()).exchange()
        .expectStatus().isNotFound()
        .expectBody().jsonPath("$.type").isEqualTo(ProblemTypes.ITEM_NOT_FOUND);
  }

  @Test
  @DisplayName("a top-level cursor whose folder lost its grant is invalid")
  void aTopLevelCursorWhoseFolderIsNoLongerVisibleIsInvalid() {
    NodePage firstPage = pageAt(client, TOP_LEVEL + "?limit=2", alice());
    fixtures.run("delete from acl.access_grant where node_id = (select node_id from node where name = 'Budget')");

    expectRefusal(TOP_LEVEL + "?limit=2&cursor=" + firstPage.getNextCursor(), alice(), INVALID_CURSOR);
  }

  @Test
  @DisplayName("a tampered or empty cursor is invalid; one longer than any cursor is outside the parameter's range")
  void textThatIsNotACursorIsRefused() {
    String cursor = pageAt(client, PROJECTS_CHILDREN + "?limit=2", alice()).getNextCursor();
    assertThat(cursor).isNotNull();

    String tampered = cursor.substring(0, 10) + flipped(cursor.charAt(10)) + cursor.substring(11);

    expectRefusal(PROJECTS_CHILDREN + "?cursor=" + tampered, alice(), INVALID_CURSOR);
    expectRefusal(PROJECTS_CHILDREN + "?cursor=", alice(), INVALID_CURSOR);
    expectRefusal(PROJECTS_CHILDREN + "?cursor=" + cursor + "A", alice(), INVALID_REQUEST);
  }

  private List<String> namesWalking(String path, JwtMutator caller) {
    NodePage firstPage = pageAt(client, path + "?limit=" + WALKING_PAGE_SIZE, caller);
    List<String> names = new ArrayList<>(namesOf(firstPage));

    names.addAll(namesWalkingFrom(path, WALKING_PAGE_SIZE, firstPage.getNextCursor(), caller));
    return names;
  }

  private List<String> namesWalkingFrom(String path, int limit, @Nullable String firstCursor, JwtMutator caller) {
    List<String> names = new ArrayList<>();
    String cursor = firstCursor;

    while (cursor != null) {
      NodePage page = pageAt(client, path + "?limit=" + limit + "&cursor=" + cursor, caller);
      names.addAll(namesOf(page));
      cursor = page.getNextCursor();
    }

    return names;
  }

  private void expectRefusal(String pathAndQuery, JwtMutator caller, String problemType) {
    client.mutateWith(caller).get().uri(pathAndQuery).exchange()
        .expectStatus().isEqualTo(HttpStatus.BAD_REQUEST)
        .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody().jsonPath("$.type").isEqualTo(problemType);
  }

  private static List<String> namesOf(NodePage page) {
    List<String> names = new ArrayList<>();

    for (Node item : page.getItems()) {
      names.add(item.getName());
    }

    return names;
  }

  private static char flipped(char cursorCharacter) {
    if (cursorCharacter == 'A') {
      return 'B';
    }

    return 'A';
  }

  private static JwtMutator frank() {
    return TestCallers.memberOf(ACME, FRANK);
  }
}
