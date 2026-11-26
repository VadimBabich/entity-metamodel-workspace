package io.github.vadimbabich.workspace.identity;

import io.github.vadimbabich.workspace.web.ProblemCondition;
import io.github.vadimbabich.workspace.web.ProblemConditionException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
class TokenDecoder implements ReactiveJwtDecoder {

  private final NimbusReactiveJwtDecoder signatureCheckingDecoder;

  TokenDecoder(
      SigningKeys signingKeys,
      @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
      @Value("${spring.security.oauth2.resourceserver.jwt.audiences}") List<String> audiences) {
    OAuth2TokenValidator<Jwt> issuerAndAudience = JwtValidators.createDefaultWithValidators(
        List.of(new JwtIssuerValidator(issuer), audienceValidator(audiences)));

    this.signatureCheckingDecoder = NimbusReactiveJwtDecoder.withJwkSource(signingKeys::keysFor)
        .jwsAlgorithm(SignatureAlgorithm.RS256)
        .build();
    this.signatureCheckingDecoder.setJwtValidator(issuerAndAudience);
  }

  @Override
  public Mono<Jwt> decode(String token) {
    return signatureCheckingDecoder.decode(token)
        .onErrorMap(TokenDecoder::causedByUnavailableKeys, TokenDecoder::identityProviderUnavailable);
  }

  private static OAuth2TokenValidator<Jwt> audienceValidator(List<String> audiences) {
    return new JwtClaimValidator<Collection<String>>(
        JwtClaimNames.AUD, tokenAudiences -> namesAny(tokenAudiences, audiences));
  }

  private static boolean namesAny(Collection<String> tokenAudiences, List<String> audiences) {
    return !Collections.disjoint(tokenAudiences, audiences);
  }

  private static boolean causedByUnavailableKeys(Throwable decodingFailure) {
    return unavailableKeysIn(decodingFailure).isPresent();
  }

  private static Optional<SigningKeysUnavailableException> unavailableKeysIn(Throwable decodingFailure) {
    Throwable cause = decodingFailure;

    while (cause != null) {
      if (cause instanceof SigningKeysUnavailableException unavailableKeys) {
        return Optional.of(unavailableKeys);
      }

      cause = cause.getCause();
    }

    return Optional.empty();
  }

  private static ProblemConditionException identityProviderUnavailable(Throwable decodingFailure) {
    SigningKeysUnavailableException unavailableKeys = unavailableKeysIn(decodingFailure).orElseThrow();
    String retryAfterSeconds = String.valueOf(unavailableKeys.retryAfter().toSeconds());
    ProblemConditionException unavailable =
        new ProblemConditionException(ProblemCondition.IDENTITY_PROVIDER_UNAVAILABLE);

    unavailable.getHeaders().set(HttpHeaders.RETRY_AFTER, retryAfterSeconds);

    return unavailable;
  }
}
