package io.github.vadimbabich.workspace.identity;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class TokenDecoderTest {

  private static final String ISSUER = "https://issuer.example";

  private final RSAKey signingKey = newKey();
  private final TokenDecoder decoder =
      new TokenDecoder(_ -> Flux.just(signingKey.toPublicJWK()), ISSUER, List.of("workspace-api", "reporting-api"));

  @Test
  void aTokenForAnyConfiguredAudienceIsAccepted() {
    StepVerifier.create(decoder.decode(tokenFor("reporting-api")))
        .expectNextMatches(jwt -> Objects.requireNonNull(jwt.getAudience()).contains("reporting-api"))
        .verifyComplete();
  }

  @Test
  void aTokenForAnotherAudienceIsRefused() {
    StepVerifier.create(decoder.decode(tokenFor("account")))
        .expectError()
        .verify();
  }

  @Test
  void aTokenWithoutAnAudienceIsRefusedAsInvalid() {
    StepVerifier.create(decoder.decode(tokenFor(null)))
        .expectError(JwtValidationException.class)
        .verify();
  }

  private String tokenFor(@Nullable String audience) {
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .issuer(ISSUER)
        .audience(audience)
        .subject("sub-alice")
        .issueTime(new Date())
        .expirationTime(Date.from(Instant.now().plusSeconds(60)))
        .build();
    SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims);

    try {
      token.sign(new RSASSASigner(signingKey));
    } catch (JOSEException signingFailure) {
      throw new IllegalStateException("could not sign a test token", signingFailure);
    }

    return token.serialize();
  }

  private static RSAKey newKey() {
    try {
      return new RSAKeyGenerator(2048).keyID("test").generate();
    } catch (JOSEException generationFailure) {
      throw new IllegalStateException("could not generate a test key", generationFailure);
    }
  }
}
