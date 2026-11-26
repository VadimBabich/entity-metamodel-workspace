package io.github.vadimbabich.workspace.mirror;

import static io.github.vadimbabich.workspace.support.KeycloakDirectory.ACME_EDITORS;
import static io.github.vadimbabich.workspace.support.KeycloakDirectory.ACME_MANAGERS;
import static io.github.vadimbabich.workspace.support.KeycloakDirectory.ACME_ORGANIZATION;
import static io.github.vadimbabich.workspace.support.KeycloakDirectory.GLOBEX_EDITORS;
import static io.github.vadimbabich.workspace.support.KeycloakDirectory.GLOBEX_ORGANIZATION;
import static io.github.vadimbabich.workspace.support.TestCallers.ACME;
import static io.github.vadimbabich.workspace.support.TestCallers.GLOBEX;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.vadimbabich.workspace.support.FailingEndpoints;
import io.github.vadimbabich.workspace.support.FixtureDatabase;
import io.github.vadimbabich.workspace.support.KeycloakDirectory;
import io.github.vadimbabich.workspace.support.StatementLog;
import io.github.vadimbabich.workspace.support.TestCallers;
import io.github.vadimbabich.workspace.support.WorkspaceIntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@WorkspaceIntegrationTest
class MirrorIT {

  private static final String ALICE = "sub-alice";
  private static final String ERIN = "sub-erin";
  private static final String FRANK = "sub-frank";

  @Autowired
  WebTestClient client;

  @Autowired
  FixtureDatabase fixtures;

  @Autowired
  KeycloakDirectory keycloak;

  @Autowired
  GroupPass groupPass;

  @Autowired
  ReactiveOAuth2AuthorizedClientService authorizedClients;

  @BeforeEach
  void seedTwoCompanies() {
    keycloak.reset();
    fixtures.deleteAllRows().block();
    fixtures.run("insert into company (alias, name) values ('acme', 'Acme'), ('globex', 'Globex')");
  }

  @Test
  void aGroupedUsersFirstRequestLoadsTheirGroupsAndMemberships() {
    keycloak.addMember(ALICE, ACME_ORGANIZATION, ACME_EDITORS, ACME_MANAGERS);

    callAs(ACME);

    StepVerifier.create(aliceMemberships())
        .expectNext(List.of("acme editors", "acme managers"))
        .verifyComplete();
    StepVerifier.create(aliceMembershipsLoaded())
        .expectNext(true)
        .verifyComplete();
  }

  @Test
  void aFailedLoadLetsTheRequestProceedAndTheNextRequestLoads() {
    keycloak.addMember(ALICE, ACME_ORGANIZATION, ACME_EDITORS);
    keycloak.makeUnreachable(ALICE);

    callAs(ACME);

    StepVerifier.create(aliceMembershipsLoaded())
        .expectNext(false)
        .verifyComplete();

    keycloak.makeReachable(ALICE);
    callAs(ACME);

    StepVerifier.create(aliceMemberships())
        .expectNext(List.of("acme editors"))
        .verifyComplete();
  }

  @Test
  void aRemovalInKeycloakTakesEffectOnceTheMembershipsAreOlderThanTheMaxAge() {
    keycloak.addMember(ALICE, ACME_ORGANIZATION, ACME_EDITORS, ACME_MANAGERS);
    callAs(ACME);
    keycloak.addMember(ALICE, ACME_ORGANIZATION, ACME_EDITORS);

    callAs(ACME);

    StepVerifier.create(aliceMemberships())
        .expectNext(List.of("acme editors", "acme managers"))
        .verifyComplete();

    ageAliceMembershipsPastTheMaxAge();
    callAs(ACME);

    StepVerifier.create(aliceMemberships())
        .expectNext(List.of("acme editors"))
        .verifyComplete();
  }

  @Test
  void aFailedReloadKeepsTheOldMembershipsAndIsNotRetriedOnTheNextRequest() {
    keycloak.addMember(ALICE, ACME_ORGANIZATION, ACME_EDITORS);
    callAs(ACME);
    ageAliceMembershipsPastTheMaxAge();
    keycloak.makeUnreachable(ALICE);
    int readsBefore = keycloak.organizationReads();

    callAs(ACME);
    int readsAfterTheFailedReload = keycloak.organizationReads();
    callAs(ACME);

    assertThat(readsAfterTheFailedReload).isGreaterThan(readsBefore);
    assertThat(keycloak.organizationReads()).isEqualTo(readsAfterTheFailedReload);
    StepVerifier.create(aliceMemberships())
        .expectNext(List.of("acme editors"))
        .verifyComplete();
  }

  @Test
  void aUserInNoGroupIsMarkedLoadedWithNoMembership() {
    callAs(ACME);

    StepVerifier.create(aliceMembershipsLoaded())
        .expectNext(true)
        .verifyComplete();
    StepVerifier.create(aliceMemberships())
        .expectNext(List.of())
        .verifyComplete();
  }

  @Test
  void aUserMovedToAnotherOrganizationDropsTheOldMembershipsAndLoadsTheNew() {
    keycloak.addMember(ALICE, ACME_ORGANIZATION, ACME_EDITORS);
    callAs(ACME);

    keycloak.addMember(ALICE, GLOBEX_ORGANIZATION, GLOBEX_EDITORS);

    try (StatementLog statementLog = StatementLog.open()) {
      callAs(GLOBEX);

      assertThat(statementLog.firstTransactionBegunBy("BEGIN READ WRITE"))
          .anyMatch(statement -> statement.startsWith("Executing query: DELETE FROM \"group_membership\""))
          .anyMatch(statement -> statement.startsWith("Executing query: UPDATE \"app_user\" SET \"username\""));
    }

    StepVerifier.create(aliceMemberships())
        .expectNext(List.of("globex editors"))
        .verifyComplete();
  }

  @Test
  void theGroupPassMirrorsTheGroupsOfOrganizationsThatAreCompanies() {
    StepVerifier.create(groupPass.run()).verifyComplete();

    StepVerifier.create(groupNames())
        .expectNext(List.of("acme editors", "acme managers", "globex editors"))
        .verifyComplete();
  }

  @Test
  void theGroupPassReadsOrganizationsBeyondKeycloaksDefaultPage() {
    for (int filler = 1; filler <= 10; filler++) {
      keycloak.addOrganization("org-filler-" + filler, "filler%02d".formatted(filler));
    }

    StepVerifier.create(groupPass.run()).verifyComplete();

    StepVerifier.create(groupNames())
        .expectNext(List.of("acme editors", "acme managers", "globex editors"))
        .verifyComplete();
  }

  @Test
  void groupsBeyondOnePageAreAllMirroredAndLoaded() {
    keycloak.addMember(ALICE, ACME_ORGANIZATION, addSevenTeams().toArray(String[]::new));

    StepVerifier.create(groupPass.run()).verifyComplete();
    callAs(ACME);

    StepVerifier.create(groupNames())
        .assertNext(names -> assertThat(names).hasSize(10))
        .verifyComplete();
    StepVerifier.create(aliceMemberships())
        .assertNext(memberships -> assertThat(memberships).hasSize(7))
        .verifyComplete();
  }

  @Test
  void aListWhosePagesDoNotAdvanceEndsThePassAtOnce() {
    addSevenTeams();
    keycloak.ignoreFirstOnGroupLists();

    StepVerifier.create(groupPass.run())
        .expectComplete()
        .verify(Duration.ofSeconds(3));
  }

  @Test
  void anUnchangedReloadWritesOnlyTheLoadedMark() {
    keycloak.addMember(ALICE, ACME_ORGANIZATION, ACME_EDITORS, ACME_MANAGERS);
    callAs(ACME);
    ageAliceMembershipsPastTheMaxAge();

    try (StatementLog statementLog = StatementLog.open()) {
      callAs(ACME);

      assertThat(statementLog.statementsOn("group_membership"))
          .noneMatch(statement -> statement.contains("DELETE") || statement.contains("INSERT"));
      assertThat(statementLog.statementsOn("app_user"))
          .anyMatch(statement -> statement.contains("\"memberships_loaded_at\" = "));
    }
  }

  @Test
  void aChangedReloadKeepsTheUnchangedMembershipRows() {
    keycloak.addMember(ALICE, ACME_ORGANIZATION, ACME_EDITORS, ACME_MANAGERS);
    callAs(ACME);
    Long editorsRow = Objects.requireNonNull(aliceEditorsMembershipRow().block());
    keycloak.addMember(ALICE, ACME_ORGANIZATION, ACME_EDITORS);
    ageAliceMembershipsPastTheMaxAge();

    callAs(ACME);

    StepVerifier.create(aliceMemberships())
        .expectNext(List.of("acme editors"))
        .verifyComplete();
    StepVerifier.create(aliceEditorsMembershipRow())
        .expectNext(editorsRow)
        .verifyComplete();
  }

  @Test
  void mirroringAUsersGroupsReadsTheGroupTableOnce() {
    keycloak.addMember(ALICE, ACME_ORGANIZATION, addSevenTeams().toArray(String[]::new));

    try (StatementLog statementLog = StatementLog.open()) {
      callAs(ACME);

      assertThat(statementLog.statementsOn("user_group"))
          .filteredOn(statement -> statement.startsWith("Executing query: SELECT"))
          .filteredOn(statement -> !statement.contains("group_membership"))
          .hasSize(1);
    }
  }

  @Test
  void theGroupPassRenamesAGroupRenamedInKeycloak() {
    StepVerifier.create(groupPass.run()).verifyComplete();
    keycloak.renameGroup(ACME_ORGANIZATION, ACME_EDITORS, "authors");

    StepVerifier.create(groupPass.run()).verifyComplete();

    StepVerifier.create(groupNames())
        .expectNext(List.of("acme authors", "acme managers", "globex editors"))
        .verifyComplete();
  }

  @Test
  void aGroupInsertedConcurrentlyElsewhereIsTakenAndThePassContinues() {
    Sinks.Empty<Void> concurrentInsertExecuted = Sinks.empty();
    Mono<Void> concurrentInsert = fixtures.runCommittingOnceAnotherStatementWaits(
        "insert into user_group (company_id, keycloak_id, name) values (1, '%s', 'editors')".formatted(ACME_EDITORS),
        concurrentInsertExecuted);
    Mono<Void> passAfterTheInsert = concurrentInsertExecuted.asMono().then(Mono.defer(groupPass::run));

    StepVerifier.create(Mono.when(concurrentInsert, passAfterTheInsert)).verifyComplete();

    StepVerifier.create(groupNames())
        .expectNext(List.of("acme editors", "acme managers", "globex editors"))
        .verifyComplete();
  }

  @Test
  void usersSigningInShareOneServiceToken() {
    keycloak.addMember(ERIN, ACME_ORGANIZATION, ACME_EDITORS);
    keycloak.addMember(FRANK, ACME_ORGANIZATION, ACME_MANAGERS);

    callAs(ACME, ERIN);
    callAs(ACME, FRANK);

    assertThat(keycloak.tokenRequests()).isLessThanOrEqualTo(1);
  }

  @Test
  void aHungTokenEndpointEndsTheGroupPassAfterItsRetriesNotAtThePassTimeout() {
    authorizedClients.removeAuthorizedClient(KeycloakAdminClient.REGISTRATION, KeycloakAdminClient.SERVICE_PRINCIPAL)
        .block();
    keycloak.hangTokenEndpoint();

    StepVerifier.create(groupPass.run())
        .expectComplete()
        .verify(Duration.ofSeconds(8));
  }

  @Test
  void aHungAdminApiDelaysAFirstRequestOnlyUntilTheLoadTimeout() {
    keycloak.addMember(ALICE, ACME_ORGANIZATION, ACME_EDITORS);
    keycloak.makeHang(ALICE);

    callAs(ACME);

    StepVerifier.create(aliceMembershipsLoaded())
        .expectNext(false)
        .verifyComplete();
  }

  private void callAs(String companyAlias) {
    callAs(companyAlias, ALICE);
  }

  private void callAs(String companyAlias, String sub) {
    client.mutateWith(TestCallers.memberOf(companyAlias, sub))
        .get().uri(FailingEndpoints.CURRENT_CALLER)
        .exchange()
        .expectStatus().isOk();
  }

  private Mono<List<String>> aliceMemberships() {
    return namesOf("""
        select c.alias || ' ' || g.name
        from group_membership m
          join user_group g on g.user_group_id = m.user_group_id
          join company c on c.company_id = m.company_id
          join app_user u on u.app_user_id = m.app_user_id
        where u.sub = '%s'
        order by 1
        """.formatted(ALICE));
  }

  private List<String> addSevenTeams() {
    List<String> teams = new ArrayList<>();

    for (int team = 1; team <= 7; team++) {
      String teamId = "00000000-0000-4000-8000-0000000a01%02d".formatted(team);
      keycloak.addGroup(ACME_ORGANIZATION, teamId, "team%02d".formatted(team));
      teams.add(teamId);
    }

    return teams;
  }

  private Mono<Long> aliceEditorsMembershipRow() {
    return fixtures.sql("""
            select m.group_membership_id from group_membership m
              join user_group g on g.user_group_id = m.user_group_id
              join app_user u on u.app_user_id = m.app_user_id
            where u.sub = '%s' and g.name = 'editors'
            """.formatted(ALICE))
        .map(row -> Objects.requireNonNull(row.get(0, Long.class)))
        .one();
  }

  private void ageAliceMembershipsPastTheMaxAge() {
    fixtures.run("update app_user set memberships_loaded_at = now() - interval '6 minutes' where sub = '%s'"
        .formatted(ALICE));
  }

  private Mono<List<String>> groupNames() {
    return namesOf("""
        select c.alias || ' ' || g.name from user_group g join company c on c.company_id = g.company_id order by 1
        """);
  }

  private Mono<Boolean> aliceMembershipsLoaded() {
    return fixtures.sql("select memberships_loaded_at is not null from app_user where sub = '%s'".formatted(ALICE))
        .map(row -> Objects.requireNonNull(row.get(0, Boolean.class)))
        .one();
  }

  private Mono<List<String>> namesOf(String query) {
    return fixtures.sql(query)
        .map(row -> Objects.requireNonNull(row.get(0, String.class)))
        .all()
        .collectList();
  }
}
