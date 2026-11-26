package io.github.vadimbabich.workspace.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

@Component
@Order(ProblemWebExceptionHandler.BEFORE_BOOTS_ERROR_HANDLER)
class ProblemWebExceptionHandler implements WebExceptionHandler {

  static final int BEFORE_BOOTS_ERROR_HANDLER = -2;

  private static final Logger LOG = LoggerFactory.getLogger(ProblemWebExceptionHandler.class);

  private final ProblemDetails problems;
  private final ProblemResponseWriter problemWriter;

  ProblemWebExceptionHandler(ProblemDetails problems, ProblemResponseWriter problemWriter) {
    this.problems = problems;
    this.problemWriter = problemWriter;
  }

  @Override
  public Mono<Void> handle(ServerWebExchange exchange, Throwable failure) {
    if (exchange.getResponse().isCommitted()) {
      return Mono.error(failure);
    }

    if (failure instanceof ErrorResponse errorResponse) {
      return writeErrorResponse(exchange, errorResponse);
    }

    return writeUnexpectedFailure(exchange, failure);
  }

  private Mono<Void> writeErrorResponse(ServerWebExchange exchange, ErrorResponse errorResponse) {
    ProblemDetail problem = problems.completed(errorResponse.getBody(), exchange);

    exchange.getResponse().getHeaders().addAll(errorResponse.getHeaders());

    return problemWriter.write(problem, exchange);
  }

  private Mono<Void> writeUnexpectedFailure(ServerWebExchange exchange, Throwable failure) {
    LOG.error("Request {} failed unexpectedly", exchange.getRequest().getId(), failure);

    ProblemDetail internalError = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    ProblemDetail problem = problems.completed(internalError, exchange);

    return problemWriter.write(problem, exchange);
  }
}
