package io.github.vadimbabich.workspace.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

class EventLoopTest {

  @Test
  void aHandlerThatBlocksOnTheEventLoopIsRefusedByReactor() {
    AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
    Mono<String> blockingReply = Mono.fromCallable(EventLoopTest::delayedGreeting)
        .doOnError(handlerFailure::set);
    DisposableServer server = serverAnswering(blockingReply);

    try {
      StepVerifier.create(statusFrom(server))
          .expectNext(500)
          .verifyComplete();
    } finally {
      server.disposeNow();
    }

    assertThat(handlerFailure.get())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("block()")
        .hasMessageContaining("reactor-http");
  }

  @Test
  void aHandlerThatBlocksOffTheEventLoopIsAnswered() {
    Mono<String> offloadedReply = Mono.fromCallable(EventLoopTest::delayedGreeting)
        .subscribeOn(Schedulers.boundedElastic());
    DisposableServer server = serverAnswering(offloadedReply);

    try {
      StepVerifier.create(statusFrom(server))
          .expectNext(200)
          .verifyComplete();
    } finally {
      server.disposeNow();
    }
  }

  private static DisposableServer serverAnswering(Mono<String> reply) {
    return HttpServer.create()
        .host("127.0.0.1")
        .port(0)
        .handle((request, response) -> response.sendString(reply))
        .bindNow();
  }

  private static Mono<Integer> statusFrom(DisposableServer server) {
    return HttpClient.create()
        .get()
        .uri("http://127.0.0.1:" + server.port() + "/")
        .responseSingle((response, body) -> Mono.just(response.status().code()));
  }

  private static String delayedGreeting() {
    return Mono.just("greeting").delayElement(Duration.ofMillis(10)).block();
  }
}
