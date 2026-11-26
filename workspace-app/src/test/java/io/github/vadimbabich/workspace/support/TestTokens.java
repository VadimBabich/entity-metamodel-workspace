package io.github.vadimbabich.workspace.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.vadimbabich.workspace.identity.SigningKeys;
import io.github.vadimbabich.workspace.identity.SigningKeysUnavailableException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Flux;

/**
 * Signed bearer tokens for tests of the token itself, and the signing keys the application's own decoder trusts in
 * tests: the test key, and a reserved key id whose keys cannot be obtained. Every other test authenticates with Spring
 * Security's {@code mockJwt()}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestTokens {

  public static final String ISSUER = "http://localhost:8180/realms/workspace";
  public static final String AUDIENCE = "workspace-api";

  public static final String UNAVAILABLE_KEY_ID = "keys-unavailable";

  private static final String TRUSTED_KEY_ID = "test-key";
  private static final KeyPair SIGNING_KEYS = newRsaKeyPair();
  private static final JWK TRUSTED_PUBLIC_KEY = new RSAKey.Builder((RSAPublicKey) SIGNING_KEYS.getPublic())
      .keyID(TRUSTED_KEY_ID)
      .build();

  @Bean
  @Primary
  SigningKeys trustingTheTestKey() {
    return TestTokens::keysFor;
  }

  private static Flux<JWK> keysFor(SignedJWT token) {
    String keyId = token.getHeader().getKeyID();

    if (UNAVAILABLE_KEY_ID.equals(keyId)) {
      IllegalStateException unreachable = new IllegalStateException("the test identity provider is unreachable");

      Duration retryAfter = Duration.ofSeconds(10);

      return Flux.error(new SigningKeysUnavailableException("the test keys are unavailable", retryAfter, unreachable));
    }

    if (TRUSTED_KEY_ID.equals(keyId)) {
      return Flux.just(TRUSTED_PUBLIC_KEY);
    }

    return Flux.empty();
  }

  public static String signedToken(String subject, Instant expiresAt) {
    return signedToken(trustedSigningKey(), ISSUER, AUDIENCE, subject, expiresAt);
  }

  public static String signedToken(
      PrivateKey signingKey, String issuer, String audience, String subject, Instant expiresAt) {
    return signedToken(TRUSTED_KEY_ID, signingKey, issuer, audience, subject, expiresAt);
  }

  public static String signedTokenNamingAnUnavailableKey(String subject) {
    Instant inAMinute = Instant.now().plusSeconds(60);

    return signedToken(UNAVAILABLE_KEY_ID, trustedSigningKey(), ISSUER, AUDIENCE, subject, inAMinute);
  }

  private static String signedToken(
      String keyId, PrivateKey signingKey, String issuer, String audience, String subject, Instant expiresAt) {
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .issuer(issuer)
        .audience(audience)
        .subject(subject)
        .claim("organization", List.of(TestCallers.ACME))
        .issueTime(Date.from(expiresAt.minusSeconds(300)))
        .expirationTime(Date.from(expiresAt))
        .build();

    SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(), claims);

    try {
      token.sign(new RSASSASigner(signingKey));
    } catch (JOSEException signingFailure) {
      throw new IllegalStateException("could not sign a test token", signingFailure);
    }

    return token.serialize();
  }

  public static PrivateKey trustedSigningKey() {
    return SIGNING_KEYS.getPrivate();
  }

  public static KeyPair newRsaKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (NoSuchAlgorithmException missingAlgorithm) {
      throw new IllegalStateException("the JDK offers no RSA key generator", missingAlgorithm);
    }
  }
}
