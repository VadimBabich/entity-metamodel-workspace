package io.github.vadimbabich.workspace.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@TestConfiguration(proxyBeanMethods = false)
public class FailingEndpoints {

  public static final String UNEXPECTED_FAILURE = "/test/failures/unexpected";
  public static final String FAILURE_THREE_OPERATORS_DEEP = "/test/failures/three-operators-deep";
  public static final String LIBRARY_ARGUMENT_FAILURE = "/test/failures/library-argument";
  public static final String INTERNAL_DETAIL = "internal-detail-that-must-not-leave-the-server";
  public static final String FILTER_FAILURE = "/test/failures/in-filter";
  public static final String FILTER_UNAVAILABLE = "/test/failures/in-filter-unavailable";
  public static final String RETRY_AFTER_SECONDS = "10";

  @Bean
  WebFilter failingFilter() {
    return FailingEndpoints::failOnFilterPaths;
  }

  private static Mono<Void> failOnFilterPaths(ServerWebExchange exchange, WebFilterChain chain) {
    String path = exchange.getRequest().getPath().value();

    if (path.equals(FILTER_FAILURE)) {
      return Mono.error(new IllegalStateException(INTERNAL_DETAIL));
    }

    if (path.equals(FILTER_UNAVAILABLE)) {
      ErrorResponseException unavailable = new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE);
      unavailable.getHeaders().set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);

      return Mono.error(unavailable);
    }

    return chain.filter(exchange);
  }

  @RestController
  static class Failures {

    @GetMapping(UNEXPECTED_FAILURE)
    Mono<String> unexpected() {
      return Mono.error(new IllegalStateException(INTERNAL_DETAIL));
    }

    @GetMapping(FAILURE_THREE_OPERATORS_DEEP)
    Mono<String> threeOperatorsDeep() {
      return Mono.just(1)
          .map(Failures::increment)
          .flatMap(Failures::failOn)
          .map(String::valueOf);
    }

    @GetMapping(LIBRARY_ARGUMENT_FAILURE)
    Mono<String> libraryArgument() {
      return Mono.error(new IllegalArgumentException(INTERNAL_DETAIL));
    }

    private static int increment(int count) {
      return count + 1;
    }

    private static Mono<Integer> failOn(int count) {
      return Mono.error(new IllegalStateException(INTERNAL_DETAIL + " " + count));
    }
  }
}
