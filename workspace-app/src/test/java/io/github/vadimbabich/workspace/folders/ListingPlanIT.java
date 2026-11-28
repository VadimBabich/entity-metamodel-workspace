package io.github.vadimbabich.workspace.folders;

import static io.github.vadimbabich.workspace.support.KeycloakDirectory.ACME_EDITORS;
import static io.github.vadimbabich.workspace.support.KeycloakDirectory.ACME_ORGANIZATION;
import static io.github.vadimbabich.workspace.support.ListingRequests.TOP_LEVEL;
import static io.github.vadimbabich.workspace.support.ListingRequests.childrenPath;
import static io.github.vadimbabich.workspace.support.ListingRequests.pageAt;
import static io.github.vadimbabich.workspace.support.TestCallers.ALICE;
import static io.github.vadimbabich.workspace.support.TestCallers.alice;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.vadimbabich.workspace.contract.model.NodePage;
import io.github.vadimbabich.workspace.support.FixtureDatabase;
import io.github.vadimbabich.workspace.support.KeycloakDirectory;
import io.github.vadimbabich.workspace.support.StatementLog;
import io.github.vadimbabich.workspace.support.WorkspaceIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plans the listing statements the application really sends, captured from the driver. The driver logs no bound
 * values here, so each statement is planned as a generic plan, which keeps its parameter markers; it reaches the
 * server as a string literal, because the driver refuses to send markers it has no values for.
 */
@WorkspaceIntegrationTest
@DisplayName("Keyset listing plans at scale")
class ListingPlanIT {

  private static final int ROOTS = 5_000;
  private static final int CHILDREN = 20_000;
  private static final String BIG_FOLDER_CHILDREN = childrenPath(1);
  private static final String LISTING_INDEX = "node_listing";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String GENERIC_PLAN_FUNCTION = """
      create or replace function generic_plan_of(statement text) returns json language plpgsql as $$
      declare
        plan json;
      begin
        execute 'explain (generic_plan, format json) ' || statement into plan;
        return plan;
      end
      $$
      """;

  @Autowired
  WebTestClient client;

  @Autowired
  FixtureDatabase fixtures;

  @Autowired
  KeycloakDirectory keycloak;

  @BeforeEach
  void seedThousandsOfRootsAndChildren() {
    keycloak.reset();
    keycloak.addMember(ALICE, ACME_ORGANIZATION, ACME_EDITORS);
    fixtures.deleteAllRows().block();
    fixtures.run("insert into company (alias, name) values ('acme', 'Acme')");
    fixtures.run("insert into user_group (company_id, keycloak_id, name) values (1, '%s', 'editors')"
        .formatted(ACME_EDITORS));
    fixtures.run("""
        insert into node (company_id, parent_id, kind_rank, name, created_by)
        select 1, null, 0, 'root-' || lpad(root::text, 5, '0'), 'seed'
        from generate_series(1, %d) as root
        """.formatted(ROOTS));
    fixtures.run("""
        insert into node (company_id, parent_id, kind_rank, name, created_by)
        select 1, 1, case when child <= %d then 0 else 1 end, 'child-' || lpad(child::text, 5, '0'), 'seed'
        from generate_series(1, %d) as child
        """.formatted(CHILDREN / 10, CHILDREN));
    fixtures.run("""
        insert into acl.access_grant (company_id, node_id, subject_kind, subject_id, role)
        select 1, node_id, 2, 1, 4 from node where parent_id is null
        """);
    fixtures.run("analyze node");
    fixtures.run("analyze acl.access_grant");
    fixtures.run(GENERIC_PLAN_FUNCTION);
  }

  @Test
  @DisplayName("keyset: with every root granted, the top level's first and second pages read an ordered index unsorted")
  void theTopLevelPagesReadAnOrderedIndexWithoutSorting() {
    NodePage firstPage;
    String firstPageStatement;

    try (StatementLog statementLog = StatementLog.open()) {
      firstPage = pageAt(client, TOP_LEVEL, alice());
      firstPageStatement = listingStatementOf(statementLog);
    }

    String secondPageStatement = secondPageStatementAfter(TOP_LEVEL, firstPage);

    for (String pageStatement : List.of(firstPageStatement, secondPageStatement)) {
      StepVerifier.create(planOf(pageStatement))
          .assertNext(plan -> {
            assertThat(nodeTypesOf(plan)).as("plan %s", plan).doesNotContain("Sort");
            assertThat(indexNamesOf(plan)).as("plan %s", plan).contains("node_top_level");
          })
          .verifyComplete();
    }
  }

  @Test
  @DisplayName("keyset: a children second page seeks with the row comparison in the index condition, not a filter")
  void aChildrenSecondPageSeeksWithTheRowComparisonInTheIndexCondition() {
    NodePage firstPage = pageAt(client, BIG_FOLDER_CHILDREN, alice());
    String secondPageStatement = secondPageStatementAfter(BIG_FOLDER_CHILDREN, firstPage);

    StepVerifier.create(planOf(secondPageStatement))
        .assertNext(plan -> {
          JsonNode listingScan = listingIndexScanOf(plan);

          assertThat(nodeTypesOf(plan)).as("plan %s", plan).doesNotContain("Sort");
          assertThat(listingScan.path("Index Cond").asString())
              .as("plan %s", plan)
              .contains("ROW(kind_rank, name, node_id) >");
          assertThat(listingScan.path("Filter").asString("")).as("plan %s", plan).doesNotContain("kind_rank");
        })
        .verifyComplete();
  }

  private String secondPageStatementAfter(String path, NodePage firstPage) {
    assertThat(firstPage.getNextCursor()).as("the first page of %s has a next cursor", path).isNotBlank();

    try (StatementLog statementLog = StatementLog.open()) {
      pageAt(client, path + "?cursor=" + firstPage.getNextCursor(), alice());
      return listingStatementOf(statementLog);
    }
  }

  private Mono<JsonNode> planOf(String statement) {
    String statementLiteral = "'" + statement.replace("'", "''") + "'";

    return fixtures.sql("select generic_plan_of(" + statementLiteral + ")::text")
        .map(row -> Objects.requireNonNull(row.get(0, String.class)))
        .one()
        .map(planJson -> JSON.readTree(planJson).get(0).get("Plan"));
  }

  private static String listingStatementOf(StatementLog statementLog) {
    List<String> listingStatements = new ArrayList<>();

    for (String statement : statementLog.selects()) {
      if (statement.startsWith("SELECT DISTINCT") && statement.contains("ORDER BY")) {
        listingStatements.add(statement);
      }
    }

    assertThat(listingStatements).as("the page's listing statement").hasSize(1);
    return listingStatements.getFirst();
  }

  private static JsonNode listingIndexScanOf(JsonNode plan) {
    for (JsonNode planNode : planNodesOf(plan)) {
      if (planNode.path("Index Name").asString("").equals(LISTING_INDEX)) {
        return planNode;
      }
    }

    throw new AssertionError("no scan of " + LISTING_INDEX + " in " + plan);
  }

  private static List<String> nodeTypesOf(JsonNode plan) {
    List<String> nodeTypes = new ArrayList<>();

    for (JsonNode planNode : planNodesOf(plan)) {
      nodeTypes.add(planNode.path("Node Type").asString());
    }

    return nodeTypes;
  }

  private static List<String> indexNamesOf(JsonNode plan) {
    List<String> indexNames = new ArrayList<>();

    for (JsonNode planNode : planNodesOf(plan)) {
      if (planNode.has("Index Name")) {
        indexNames.add(planNode.path("Index Name").asString());
      }
    }

    return indexNames;
  }

  private static List<JsonNode> planNodesOf(JsonNode plan) {
    List<JsonNode> planNodes = new ArrayList<>();
    planNodes.add(plan);

    for (JsonNode childPlan : plan.path("Plans")) {
      planNodes.addAll(planNodesOf(childPlan));
    }

    return planNodes;
  }
}
