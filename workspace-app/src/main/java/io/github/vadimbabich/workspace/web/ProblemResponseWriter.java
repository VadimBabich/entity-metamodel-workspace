package io.github.vadimbabich.workspace.web;

import java.util.List;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
class ProblemResponseWriter {

  private final ServerResponse.Context writingContext;

  ProblemResponseWriter(ServerCodecConfigurer codecs) {
    this.writingContext = new CodecContext(codecs.getWriters());
  }

  Mono<Void> write(ProblemDetail problem, ServerWebExchange exchange) {
    return ServerResponse.status(HttpStatusCode.valueOf(problem.getStatus()))
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .bodyValue(problem)
        .flatMap(response -> response.writeTo(exchange, writingContext));
  }

  private record CodecContext(List<HttpMessageWriter<?>> messageWriters) implements ServerResponse.Context {

    @Override
    public List<ViewResolver> viewResolvers() {
      return List.of();
    }
  }
}
