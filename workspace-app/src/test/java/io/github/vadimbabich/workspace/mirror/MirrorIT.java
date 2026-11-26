package io.github.vadimbabich.workspace.mirror;

import static io.github.vadimbabich.workspace.support.KeycloakDirectory.ACME_EDITORS;
import static io.github.vadimbabich.workspace.support.KeycloakDirectory.ACME_ORGANIZATION;

import io.github.vadimbabich.workspace.support.FixtureDatabase;
import io.github.vadimbabich.workspace.support.KeycloakDirectory;
import io.github.vadimbabich.workspace.support.WorkspaceIntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@WorkspaceIntegrationTest
class MirrorIT {

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
  void aListWhosePagesDoNotAdvanceEndsThePassAtOnce() {
    addSevenTeams();
    keycloak.ignoreFirstOnGroupLists();

    StepVerifier.create(groupPass.run())
        .expectComplete()
        .verify(Duration.ofSeconds(3));
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
  void aHungTokenEndpointEndsTheGroupPassAfterItsRetriesNotAtThePassTimeout() {
    authorizedClients.removeAuthorizedClient(KeycloakAdminClient.REGISTRATION, KeycloakAdminClient.SERVICE_PRINCIPAL)
        .block();
    keycloak.hangTokenEndpoint();

    StepVerifier.create(groupPass.run())
        .expectComplete()
        .verify(Duration.ofSeconds(8));
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

  private Mono<List<String>> groupNames() {
    return namesOf("""
        select c.alias || ' ' || g.name from user_group g join company c on c.company_id = g.company_id order by 1
        """);
  }

  private Mono<List<String>> namesOf(String query) {
    return fixtures.sql(query)
        .map(row -> Objects.requireNonNull(row.get(0, String.class)))
        .all()
        .collectList();
  }
}
