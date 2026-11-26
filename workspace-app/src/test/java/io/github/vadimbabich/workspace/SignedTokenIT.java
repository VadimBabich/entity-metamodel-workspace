package io.github.vadimbabich.workspace;

import static io.github.vadimbabich.workspace.support.TestTokens.AUDIENCE;
import static io.github.vadimbabich.workspace.support.TestTokens.ISSUER;
import static io.github.vadimbabich.workspace.support.TestTokens.newRsaKeyPair;
import static io.github.vadimbabich.workspace.support.TestTokens.signedToken;
import static io.github.vadimbabich.workspace.support.TestTokens.trustedSigningKey;

import io.github.vadimbabich.workspace.support.FixtureDatabase;
import io.github.vadimbabich.workspace.support.ProblemTypes;
import io.github.vadimbabich.workspace.support.TestTokens;
import io.github.vadimbabich.workspace.support.WorkspaceIntegrationTest;
import java.security.PrivateKey;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@WorkspaceIntegrationTest
class SignedTokenIT {

  private static final String FOLDER_CHILDREN = "/api/v1/folders/1/children";
  private static final String SUBJECT = "6f1c2b4e-0000-4000-8000-00000000a11c";

  @Autowired
  WebTestClient client;

  @Autowired
  FixtureDatabase fixtures;

  @BeforeEach
  void seedTheTokensCompany() {
    fixtures.deleteAllRows().block();
    fixtures.run("insert into company (alias, name) values ('acme', 'Acme')");
  }

  @Test
  void aTokenSignedByTheTrustedKeyIsAccepted() {
    String token = signedToken(SUBJECT, inAMinute());

    callWith(token).expectStatus().isOk();
  }

  @Test
  void aTokenSignedByAnotherKeyIsRefused() {
    PrivateKey untrustedKey = newRsaKeyPair().getPrivate();
    String token = signedToken(untrustedKey, ISSUER, AUDIENCE, SUBJECT, inAMinute());

    callWith(token).expectStatus().isUnauthorized();
  }

  @Test
  void aTokenForAnotherAudienceIsRefused() {
    String token = signedToken(trustedSigningKey(), ISSUER, "account", SUBJECT, inAMinute());

    callWith(token).expectStatus().isUnauthorized();
  }

  @Test
  void aTokenFromAnotherIssuerIsRefused() {
    String otherIssuer = "http://localhost:8180/realms/other";
    String token = signedToken(trustedSigningKey(), otherIssuer, AUDIENCE, SUBJECT, inAMinute());

    callWith(token).expectStatus().isUnauthorized();
  }

  // Beyond Spring's default clock skew of 60 seconds, so the expiry is not forgiven.
  @Test
  void aTokenExpiredMoreThanTheClockSkewAgoIsRefused() {
    String token = signedToken(SUBJECT, Instant.now().minusSeconds(120));

    callWith(token).expectStatus().isUnauthorized();
  }

  private static Instant inAMinute() {
    return Instant.now().plusSeconds(60);
  }

  private WebTestClient.ResponseSpec callWith(String token) {
    return client.get()
        .uri(FOLDER_CHILDREN)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .exchange();
  }

  @Test
  void aTokenWhoseSigningKeysCannotBeObtainedIsAnsweredLaterNotRefused() {
    String token = TestTokens.signedTokenNamingAnUnavailableKey(SUBJECT);

    callWith(token)
        .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "10")
        .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .jsonPath("$.type").isEqualTo(ProblemTypes.BASE + "identity-provider-unavailable")
        .jsonPath("$.title").isEqualTo("Identity provider unavailable");
  }
}
