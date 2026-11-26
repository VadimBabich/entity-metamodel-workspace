package io.github.vadimbabich.workspace.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
class ProblemAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

  private static final String BEARER = "Bearer";

  private final ProblemDetails problems;
  private final ProblemResponseWriter problemWriter;

  ProblemAuthenticationEntryPoint(ProblemDetails problems, ProblemResponseWriter problemWriter) {
    this.problems = problems;
    this.problemWriter = problemWriter;
  }

  @Override
  public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException refusal) {
    ProblemDetail problem = problems.unauthenticated(exchange);

    exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, challengeFor(refusal));

    return problemWriter.write(problem, exchange);
  }

  private static String challengeFor(AuthenticationException refusal) {
    if (refusal instanceof OAuth2AuthenticationException tokenRefusal) {
      return BEARER + " error=\"" + tokenRefusal.getError().getErrorCode() + "\"";
    }

    return BEARER;
  }
}
