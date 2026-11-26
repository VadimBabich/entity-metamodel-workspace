package io.github.vadimbabich.workspace.identity;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.jwk.source.RetryingJWKSetSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nimbusds.jose.util.events.Event;
import com.nimbusds.jwt.SignedJWT;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
class NimbusSigningKeys implements SigningKeys {

  private static final Logger LOG = LoggerFactory.getLogger(NimbusSigningKeys.class);
  private static final int KEY_SET_SIZE_LIMIT_BYTES = 51_200;
  private static final long CACHE_REFRESH_TIMEOUT_MILLIS = 15_000;
  private static final JWKSelector ANY_KEY = new JWKSelector(new JWKMatcher.Builder().build());

  private final JWKSource<@Nullable SecurityContext> keySource;
  private final Duration retryAfter;

  NimbusSigningKeys(
      @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") URI jwkSetUri,
      SigningKeyProperties policy) {
    this.keySource = cachedKeySource(jwkSetUri, policy);
    this.retryAfter = policy.fetchInterval();
  }

  @Override
  public Flux<JWK> keysFor(SignedJWT token) {
    return Mono.fromCallable(() -> keysMatching(token))
        .subscribeOn(Schedulers.boundedElastic())
        .flatMapIterable(List::copyOf);
  }

  private List<JWK> keysMatching(SignedJWT token) {
    JWKSelector selector = new JWKSelector(JWKMatcher.forJWSHeader(token.getHeader()));

    try {
      return keySource.get(selector, null);
    } catch (RateLimitReachedException fetchedTooRecently) {
      return noKeyIfAUsableSetExists(fetchedTooRecently);
    } catch (KeySourceException unavailableKeys) {
      throw unavailable(unavailableKeys);
    }
  }

  private List<JWK> noKeyIfAUsableSetExists(RateLimitReachedException fetchedTooRecently) {
    try {
      keySource.get(ANY_KEY, null);
    } catch (KeySourceException noUsableSet) {
      throw unavailable(fetchedTooRecently);
    }

    return List.of();
  }

  private SigningKeysUnavailableException unavailable(KeySourceException cause) {
    return new SigningKeysUnavailableException("the signing keys cannot be obtained", retryAfter, cause);
  }

  private static JWKSource<@Nullable SecurityContext> cachedKeySource(URI jwkSetUri, SigningKeyProperties policy) {
    int fetchTimeoutMillis = Math.toIntExact(policy.fetchTimeout().toMillis());
    DefaultResourceRetriever retriever =
        new DefaultResourceRetriever(fetchTimeoutMillis, fetchTimeoutMillis, KEY_SET_SIZE_LIMIT_BYTES);
    Duration staleKeysTrustedFor = policy.refreshAfter().plus(policy.staleTolerance());

    return JWKSourceBuilder.create(urlOf(jwkSetUri), retriever)
        .cache(policy.refreshAfter().toMillis(), CACHE_REFRESH_TIMEOUT_MILLIS)
        .refreshAheadCache(false)
        .rateLimited(policy.fetchInterval().toMillis())
        .outageTolerant(staleKeysTrustedFor.toMillis())
        .retrying(NimbusSigningKeys::logFailedFetch)
        .build();
  }

  private static void logFailedFetch(Event<RetryingJWKSetSource<SecurityContext>, SecurityContext> event) {
    if (event instanceof RetryingJWKSetSource.RetrialEvent<SecurityContext> retrial) {
      LOG.warn("Fetching the signing keys failed; retrying once: {}", retrial.getException().toString());
    }
  }

  private static URL urlOf(URI jwkSetUri) {
    try {
      return jwkSetUri.toURL();
    } catch (MalformedURLException unusableUri) {
      throw new IllegalArgumentException("the JWK set URI is not a URL: " + jwkSetUri, unusableUri);
    }
  }
}
