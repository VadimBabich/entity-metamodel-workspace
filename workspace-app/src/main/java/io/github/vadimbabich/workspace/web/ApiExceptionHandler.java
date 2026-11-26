package io.github.vadimbabich.workspace.web;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  private final ProblemDetails problems;

  ApiExceptionHandler(ProblemDetails problems) {
    this.problems = problems;
  }

  @Override
  protected Mono<ResponseEntity<Object>> createResponseEntity(
      @Nullable Object body,
      @Nullable HttpHeaders headers,
      HttpStatusCode status,
      ServerWebExchange exchange) {

    if (body instanceof ProblemDetail problem) {
      ProblemDetail completedProblem = problems.completed(problem, exchange);

      return super.createResponseEntity(completedProblem, headers, status, exchange);
    }

    return super.createResponseEntity(body, headers, status, exchange);
  }
}
