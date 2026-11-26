package io.github.vadimbabich.workspace.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class NimbusSigningKeysTest {

  private static final SigningKeyProperties POLICY = new SigningKeyProperties(
      Duration.ofSeconds(300),
      Duration.ofSeconds(30),
      Duration.ofMinutes(10),
      Duration.ofMillis(500));

  private final RSAKey currentKey = currentKey();
  private final AtomicInteger fetches = new AtomicInteger();

  private volatile int keyEndpointStatus = 200;
  private final CountDownLatch slowProviderReleased = new CountDownLatch(1);
  private final ExecutorService keyEndpointThreads = Executors.newCachedThreadPool();
  private volatile boolean keyEndpointSlow;
  private HttpServer keyEndpoint;
  private NimbusSigningKeys signingKeys;

  @BeforeEach
  void startTheKeyEndpoint() throws IOException {
    keyEndpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    keyEndpoint.createContext("/certs", this::serveKeySet);
    keyEndpoint.setExecutor(keyEndpointThreads);
    keyEndpoint.start();

    URI keySetUri = URI.create("http://127.0.0.1:" + keyEndpoint.getAddress().getPort() + "/certs");
    signingKeys = new NimbusSigningKeys(keySetUri, POLICY);
  }

  @AfterEach
  void stopTheKeyEndpoint() {
    slowProviderReleased.countDown();
    keyEndpoint.stop(0);
    keyEndpointThreads.shutdownNow();
  }

  @Test
  void theKeyNamedByTheTokenIsServed() {
    StepVerifier.create(signingKeys.keysFor(tokenSignedBy("current")))
        .expectNextMatches(key -> key.getKeyID().equals("current"))
        .verifyComplete();
  }

  @Test
  void concurrentFirstCallersShareOneFetch() {
    List<Flux<JWK>> concurrentCallers = new ArrayList<>();

    for (int caller = 0; caller < 20; caller++) {
      concurrentCallers.add(signingKeys.keysFor(tokenSignedBy("current")));
    }

    StepVerifier.create(Flux.merge(concurrentCallers))
        .expectNextCount(20)
        .verifyComplete();
    assertThat(fetches.get()).isEqualTo(1);
  }

  @Test
  void unknownKeysAreRefusedWithoutAFetchEach() {
    for (int token = 0; token < 10; token++) {
      StepVerifier.create(signingKeys.keysFor(tokenSignedBy("unknown-" + token)))
          .verifyComplete();
    }

    assertThat(fetches.get()).isLessThanOrEqualTo(3);
  }

  @Test
  void anUnreachableProviderMakesTheKeysUnavailableAndNamesTheRateLimitAsRetryAfter() {
    keyEndpointStatus = 503;

    StepVerifier.create(signingKeys.keysFor(tokenSignedBy("current")))
        .expectErrorSatisfies(failure -> assertThat(failure)
            .isInstanceOfSatisfying(SigningKeysUnavailableException.class,
                unavailable -> assertThat(unavailable.retryAfter()).isEqualTo(Duration.ofSeconds(30))))
        .verify();
  }

  @Test
  void everyRequestOfAColdOutageIsAnsweredUnavailableNotInvalid() {
    keyEndpointStatus = 503;

    for (int request = 1; request <= 3; request++) {
      StepVerifier.create(signingKeys.keysFor(tokenSignedBy("current")))
          .expectError(SigningKeysUnavailableException.class)
          .verify();
    }
  }

  @Test
  void aHungProviderIsAbandonedAfterTwoTimedOutAttempts() {
    keyEndpointSlow = true;

    StepVerifier.create(signingKeys.keysFor(tokenSignedBy("current")))
        .expectError(SigningKeysUnavailableException.class)
        .verify(Duration.ofMillis(1500));
    assertThat(fetches.get()).isEqualTo(2);
  }

  private void serveKeySet(HttpExchange exchange) throws IOException {
    fetches.incrementAndGet();

    if (keyEndpointSlow) {
      awaitRelease();
    }

    byte[] keySetDocument = new JWKSet(currentKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);

    if (keyEndpointStatus != 200) {
      exchange.sendResponseHeaders(keyEndpointStatus, -1);
      exchange.close();
      return;
    }

    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, keySetDocument.length);

    try (OutputStream body = exchange.getResponseBody()) {
      body.write(keySetDocument);
    }
  }

  private void awaitRelease() {
    try {
      slowProviderReleased.await(5, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private static SignedJWT tokenSignedBy(String keyId) {
    JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build();

    return new SignedJWT(header, new JWTClaimsSet.Builder().subject("sub").build());
  }

  private static RSAKey currentKey() {
    try {
      return new RSAKeyGenerator(2048).keyID("current").generate();
    } catch (JOSEException generationFailure) {
      throw new IllegalStateException("could not generate a test key", generationFailure);
    }
  }
}
