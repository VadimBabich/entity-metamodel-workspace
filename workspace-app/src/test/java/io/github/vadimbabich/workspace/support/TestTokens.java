package io.github.vadimbabich.workspace.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/**
 * Signed bearer tokens for tests of the token itself, and a decoder that replaces Boot's: it trusts their key, checks
 * the audience configured in application.yaml and the issuer pinned to {@link #ISSUER} by the integration tests. It
 * proves the audience value and that both checks refuse, not how production's decoder is wired. Every other test
 * authenticates with Spring Security's {@code mockJwt()}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestTokens {

  public static final String ISSUER = "http://localhost:8180/realms/workspace";
  public static final String AUDIENCE = "workspace-api";

  private static final KeyPair SIGNING_KEYS = newRsaKeyPair();

  @Bean
  ReactiveJwtDecoder trustingTheTestKey(
      @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String configuredIssuer,
      @Value("${spring.security.oauth2.resourceserver.jwt.audiences}") String configuredAudience) {
    NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
        .withPublicKey((RSAPublicKey) SIGNING_KEYS.getPublic())
        .build();

    OAuth2TokenValidator<Jwt> applicationValidators = JwtValidators.createDefaultWithValidators(
        new JwtIssuerValidator(configuredIssuer),
        new JwtAudienceValidator(configuredAudience));
    decoder.setJwtValidator(applicationValidators);

    return decoder;
  }

  public static String signedToken(String subject, Instant expiresAt) {
    return signedToken(trustedSigningKey(), ISSUER, AUDIENCE, subject, expiresAt);
  }

  public static String signedToken(
      PrivateKey signingKey, String issuer, String audience, String subject, Instant expiresAt) {
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .issuer(issuer)
        .audience(audience)
        .subject(subject)
        .issueTime(Date.from(expiresAt.minusSeconds(300)))
        .expirationTime(Date.from(expiresAt))
        .build();

    SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);

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
