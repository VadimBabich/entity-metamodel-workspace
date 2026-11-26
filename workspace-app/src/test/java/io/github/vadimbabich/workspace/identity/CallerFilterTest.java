package io.github.vadimbabich.workspace.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class CallerFilterTest {

  private final CallerResolver callers = mock(CallerResolver.class);
  private final ServerAuthenticationEntryPoint entryPoint = mock(ServerAuthenticationEntryPoint.class);
  private final CallerFilter filter = new CallerFilter(callers, entryPoint, new WebEndpointProperties());
  private final AtomicBoolean chainContinued = new AtomicBoolean();
  private final WebFilterChain chain = _ -> continueChain();

  private Mono<Void> continueChain() {
    return Mono.fromRunnable(() -> chainContinued.set(true));
  }

  @Test
  void aManagementRequestPassesWithoutResolvingACaller() {
    StepVerifier.create(filter.filter(authenticatedExchangeFor("/actuator/health/readiness"), chain))
        .verifyComplete();

    assertThat(chainContinued).isTrue();
    verifyNoInteractions(callers, entryPoint);
  }

  @Test
  void anApiRequestIsResolvedToItsCaller() {
    when(callers.resolve(any())).thenReturn(Mono.just(new Caller(1L, 1L, "sub-alice", false)));

    StepVerifier.create(filter.filter(authenticatedExchangeFor("/api/v1/folders/1/children"), chain))
        .verifyComplete();

    assertThat(chainContinued).isTrue();
    verify(callers).resolve(any());
  }

  private static ServerWebExchange authenticatedExchangeFor(String path) {
    Jwt token = Jwt.withTokenValue("token")
        .header("alg", "RS256")
        .subject("sub-alice")
        .claim("organization", List.of("initech"))
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .build();

    return MockServerWebExchange.from(MockServerHttpRequest.get(path))
        .mutate()
        .principal(Mono.just(new JwtAuthenticationToken(token)))
        .build();
  }
}
