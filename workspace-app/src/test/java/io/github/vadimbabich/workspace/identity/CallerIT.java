package io.github.vadimbabich.workspace.identity;

import static io.github.vadimbabich.workspace.support.FailingEndpoints.CURRENT_AUTHENTICATION;
import static io.github.vadimbabich.workspace.support.FailingEndpoints.CURRENT_CALLER;
import static io.github.vadimbabich.workspace.support.TestCallers.ACME;
import static io.github.vadimbabich.workspace.support.TestCallers.GLOBEX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

import io.github.vadimbabich.workspace.support.FixtureDatabase;
import io.github.vadimbabich.workspace.support.KeycloakDirectory;
import io.github.vadimbabich.workspace.support.ProblemTypes;
import io.github.vadimbabich.workspace.support.TestCallers;
import io.github.vadimbabich.workspace.support.WorkspaceIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.JwtMutator;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@WorkspaceIntegrationTest
class CallerIT {

  private static final String ALICE = "sub-alice";
  private static final ParameterizedTypeReference<Map<String, Object>> PROBLEM = new ParameterizedTypeReference<>() {
  };

  @Autowired
  WebTestClient client;

  @Autowired
  FixtureDatabase fixtures;

  @Autowired
  CallerResolver callers;

  @Autowired
  KeycloakDirectory keycloak;

  @BeforeEach
  void seedTwoCompanies() {
    keycloak.reset();
    fixtures.deleteAllRows().block();
    fixtures.run("insert into company (alias, name) values ('acme', 'Acme'), ('globex', 'Globex')");
  }

  @Test
  void aFirstRequestCreatesTheUserRowFromTheToken() {
    currentCallerAs(TestCallers.memberOf(ACME, ALICE)).expectStatus().isOk();

    StepVerifier.create(userRowsOfAlice())
        .expectNext(List.of("acme"))
        .verifyComplete();
  }

  @Test
  void theHandlerSeesTheCallerOfTheRequest() {
    Caller caller = currentCallerAs(TestCallers.memberOf(GLOBEX, ALICE))
        .expectStatus().isOk()
        .expectBody(Caller.class)
        .returnResult().getResponseBody();

    assertThat(caller).isNotNull();
    assertThat(caller.sub()).isEqualTo(ALICE);
    assertThat(caller.companyId()).isEqualTo(2L);
    assertThat(caller.administrator()).isFalse();
  }

  @Test
  void theRequestsAuthenticationIsTheResolvedCaller() {
    Map<String, Object> authentication = client.mutateWith(TestCallers.memberOf(GLOBEX, ALICE))
        .get().uri(CURRENT_AUTHENTICATION)
        .exchange()
        .expectStatus().isOk()
        .expectBody(PROBLEM)
        .returnResult().getResponseBody();

    assertThat(authentication).isNotNull();
    assertThat(authentication).containsEntry("exchangePrincipalName", ALICE);
    assertThat(authentication.get("principal"))
        .isEqualTo(Map.of("companyId", 2, "appUserId", 1, "sub", ALICE, "administrator", false));
  }

  @Test
  void aSecondRequestReusesTheRow() {
    currentCallerAs(TestCallers.memberOf(ACME, ALICE)).expectStatus().isOk();
    currentCallerAs(TestCallers.memberOf(ACME, ALICE)).expectStatus().isOk();

    StepVerifier.create(userRowsOfAlice())
        .expectNext(List.of("acme"))
        .verifyComplete();
  }

  @Test
  void concurrentFirstRequestsOfOneUserCreateOneRow() {
    Jwt token = Jwt.withTokenValue("token")
        .header("alg", "RS256")
        .subject(ALICE)
        .claim("organization", List.of(ACME))
        .build();

    Mono<List<Long>> callerIds = Flux.range(1, 20)
        .flatMap(_ -> callers.resolve(token))
        .map(Caller::appUserId)
        .distinct()
        .collectList();

    keycloak.addMember(ALICE, KeycloakDirectory.ACME_ORGANIZATION, KeycloakDirectory.ACME_EDITORS);

    StepVerifier.create(callerIds)
        .assertNext(distinctIds -> assertThat(distinctIds).hasSize(1))
        .verifyComplete();
    assertThat(keycloak.memberGroupReads()).isEqualTo(1);
    StepVerifier.create(userRowsOfAlice())
        .expectNext(List.of("acme"))
        .verifyComplete();
  }

  @Test
  void aUserMovedToAnotherOrganizationFollowsTheToken() {
    currentCallerAs(TestCallers.memberOf(ACME, ALICE)).expectStatus().isOk();

    currentCallerAs(TestCallers.memberOf(GLOBEX, ALICE)).expectStatus().isOk();

    StepVerifier.create(userRowsOfAlice())
        .expectNext(List.of("globex"))
        .verifyComplete();
  }

  @Test
  void changedDisplayFieldsAreRefreshed() {
    currentCallerAs(TestCallers.memberOf(ACME, ALICE)).expectStatus().isOk();

    JwtMutator renamed = mockJwt().jwt(token -> token
        .subject(ALICE)
        .claim("organization", List.of(ACME))
        .claim("preferred_username", "alice")
        .claim("email", "alice@example.org"));
    currentCallerAs(renamed).expectStatus().isOk();

    StepVerifier.create(fixtures.sql("select username || ' ' || email from app_user where sub = 'sub-alice'")
            .map(row -> Objects.requireNonNull(row.get(0, String.class)))
            .one())
        .expectNext("alice alice@example.org")
        .verifyComplete();
  }

  @Test
  void aTokenWithoutAnOrganizationIsRefusedAndWritesNothing() {
    expectRefusedWithoutARow(TestCallers.withoutOrganization(ALICE));
  }

  @Test
  void aTokenWithTwoOrganizationsIsRefusedAndWritesNothing() {
    expectRefusedWithoutARow(TestCallers.withOrganizationClaim(ALICE, List.of(ACME, GLOBEX)));
  }

  @Test
  void aTokenNamingAnUnknownOrganizationIsRefusedAndWritesNothing() {
    expectRefusedWithoutARow(TestCallers.memberOf("initech", ALICE));
  }

  @Test
  void aTokenWithoutASubjectIsRefusedAndWritesNothing() {
    JwtMutator withoutSubject = mockJwt().jwt(token -> token
        .claim("organization", List.of(ACME))
        .claims(claims -> claims.remove("sub")));

    currentCallerAs(withoutSubject)
        .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED)
        .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON);

    Mono<Long> userRows = fixtures.sql("select count(*) from app_user")
        .map(row -> Objects.requireNonNull(row.get(0, Long.class)))
        .one();

    StepVerifier.create(userRows)
        .expectNext(0L)
        .verifyComplete();
  }

  @Test
  void aUserDisabledInTheDatabaseIsRefusedWithoutLoadingMemberships() {
    currentCallerAs(TestCallers.memberOf(ACME, ALICE)).expectStatus().isOk();
    fixtures.run("update app_user set enabled = false, memberships_loaded_at = null where sub = '%s'".formatted(ALICE));
    keycloak.addMember(ALICE, KeycloakDirectory.ACME_ORGANIZATION, KeycloakDirectory.ACME_EDITORS);
    int memberGroupReadsBefore = keycloak.memberGroupReads();

    currentCallerAs(TestCallers.memberOf(ACME, ALICE))
        .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED)
        .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");

    assertThat(keycloak.memberGroupReads()).isEqualTo(memberGroupReadsBefore);
  }

  private void expectRefusedWithoutARow(JwtMutator caller) {
    Map<String, Object> problem = currentCallerAs(caller)
        .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED)
        .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"")
        .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody(PROBLEM)
        .returnResult().getResponseBody();

    assertThat(problem).containsEntry("type", ProblemTypes.BASE + "unauthenticated");
    StepVerifier.create(userRowsOfAlice())
        .expectNext(List.of())
        .verifyComplete();
  }

  private WebTestClient.ResponseSpec currentCallerAs(JwtMutator caller) {
    return client.mutateWith(caller).get().uri(CURRENT_CALLER).exchange();
  }

  private Mono<List<String>> userRowsOfAlice() {
    return fixtures.sql("""
            select c.alias from app_user u join company c on c.company_id = u.company_id where u.sub = '%s'
            """.formatted(ALICE))
        .map(row -> Objects.requireNonNull(row.get(0, String.class)))
        .all()
        .collectList();
  }
}
