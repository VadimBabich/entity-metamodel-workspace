package io.github.vadimbabich.workspace;

import static io.github.vadimbabich.workspace.support.TestTokens.AUDIENCE;
import static io.github.vadimbabich.workspace.support.TestTokens.ISSUER;
import static io.github.vadimbabich.workspace.support.TestTokens.newRsaKeyPair;
import static io.github.vadimbabich.workspace.support.TestTokens.signedToken;
import static io.github.vadimbabich.workspace.support.TestTokens.trustedSigningKey;

import io.github.vadimbabich.workspace.support.WorkspaceIntegrationTest;
import java.security.PrivateKey;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

@WorkspaceIntegrationTest
class SignedTokenIT {

  private static final String CHILDREN_OF_FOLDER = "/api/v1/folders/1/children";
  private static final String SUBJECT = "6f1c2b4e-0000-4000-8000-00000000a11c";

  @Autowired
  WebTestClient client;

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
        .uri(CHILDREN_OF_FOLDER)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .exchange();
  }
}
