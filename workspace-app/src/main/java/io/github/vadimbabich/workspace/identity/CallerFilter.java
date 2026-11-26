package io.github.vadimbabich.workspace.identity;

import java.util.Optional;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.security.oauth2.server.resource.BearerTokenErrorCodes;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
class CallerFilter implements WebFilter {

  private final CallerResolver callers;
  private final ServerAuthenticationEntryPoint authenticationEntryPoint;
  private final String managementPathPrefix;

  CallerFilter(
      CallerResolver callers,
      ServerAuthenticationEntryPoint authenticationEntryPoint,
      WebEndpointProperties webEndpoints) {
    this.callers = callers;
    this.authenticationEntryPoint = authenticationEntryPoint;
    this.managementPathPrefix = webEndpoints.getBasePath() + "/";
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    if (isManagementRequest(exchange)) {
      return chain.filter(exchange);
    }

    return exchange.getPrincipal()
        .ofType(JwtAuthenticationToken.class)
        .singleOptional()
        .flatMap(tokenAuthentication -> filterFor(tokenAuthentication, exchange, chain));
  }

  private boolean isManagementRequest(ServerWebExchange exchange) {
    String requestPath = exchange.getRequest().getPath().value();

    return requestPath.startsWith(managementPathPrefix);
  }

  private Mono<Void> filterFor(
      Optional<JwtAuthenticationToken> tokenAuthentication, ServerWebExchange exchange, WebFilterChain chain) {
    if (tokenAuthentication.isEmpty()) {
      return chain.filter(exchange);
    }

    return filterAs(tokenAuthentication.get(), exchange, chain);
  }

  private Mono<Void> filterAs(
      JwtAuthenticationToken tokenAuthentication, ServerWebExchange exchange, WebFilterChain chain) {
    return callers.resolve(tokenAuthentication.getToken())
        .flatMap(caller -> proceedAs(new CallerAuthentication(caller, tokenAuthentication), exchange, chain))
        .onErrorResume(CallerRefusedException.class, refusal -> refuse(exchange, refusal));
  }

  private static Mono<Void> proceedAs(
      CallerAuthentication callerAuthentication, ServerWebExchange exchange, WebFilterChain chain) {
    return chain.filter(exchange)
        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(callerAuthentication));
  }

  private Mono<Void> refuse(ServerWebExchange exchange, CallerRefusedException refusal) {
    BearerTokenError invalidToken = new BearerTokenError(
        BearerTokenErrorCodes.INVALID_TOKEN, HttpStatus.UNAUTHORIZED, refusal.getMessage(), null);

    return authenticationEntryPoint.commence(exchange, new OAuth2AuthenticationException(invalidToken));
  }
}
