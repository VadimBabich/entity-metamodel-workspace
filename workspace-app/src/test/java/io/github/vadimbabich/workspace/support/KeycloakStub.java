package io.github.vadimbabich.workspace.support;

import io.netty.handler.codec.http.QueryStringDecoder;
import java.util.List;
import org.reactivestreams.Publisher;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;
import reactor.netty.http.server.HttpServerRoutes;

@TestConfiguration(proxyBeanMethods = false)
public class KeycloakStub {

  private static final String ADMIN = "/admin/realms/workspace";
  private static final int KEYCLOAK_DEFAULT_MAX = 10;
  private static final String TOKEN =
      "{\"access_token\":\"stub-admin-token\",\"token_type\":\"Bearer\",\"expires_in\":300}";

  @Bean
  KeycloakDirectory keycloakDirectory() {
    return new KeycloakDirectory();
  }

  @Bean(destroyMethod = "disposeNow")
  DisposableServer keycloakStubServer(KeycloakDirectory directory) {
    return HttpServer.create()
        .host("127.0.0.1")
        .port(0)
        .route(routes -> declareRoutes(routes, directory))
        .bindNow();
  }

  @Bean
  DynamicPropertyRegistrar keycloakStubUris(DisposableServer keycloakStubServer) {
    String baseUri = "http://127.0.0.1:" + keycloakStubServer.port();

    return registry -> {
      registry.add("spring.security.oauth2.client.provider.keycloak.token-uri", () -> baseUri + "/token");
      registry.add("workspace.mirror.admin-uri", () -> baseUri + ADMIN);
    };
  }

  private static void declareRoutes(HttpServerRoutes routes, KeycloakDirectory directory) {
    routes.post("/token", (_, response) -> token(response, directory))
        .get(ADMIN + "/organizations", (request, response) -> organizations(request, response, directory))
        .get(ADMIN + "/organizations/members/{sub}/organizations",
            (request, response) -> organizationsOfMember(request, response, directory))
        .get(ADMIN + "/organizations/{organization}/members/{sub}/groups",
            (request, response) -> groupsOfMember(request, response, directory))
        .get(ADMIN + "/organizations/{organization}/groups",
            (request, response) -> groups(request, response, directory));
  }

  private static Publisher<Void> organizations(
      HttpServerRequest request, HttpServerResponse response, KeycloakDirectory directory) {
    QueryStringDecoder query = new QueryStringDecoder(request.uri());
    int first = intParameter(query, "first", 0);
    int max = intParameter(query, "max", KEYCLOAK_DEFAULT_MAX);

    return json(response, directory.organizationsJson(first, max));
  }

  private static Publisher<Void> groups(
      HttpServerRequest request, HttpServerResponse response, KeycloakDirectory directory) {
    QueryStringDecoder query = new QueryStringDecoder(request.uri());
    int first = directory.firstHonoured(intParameter(query, "first", 0));
    int max = intParameter(query, "max", Integer.MAX_VALUE);

    return json(response, directory.groupsJson(request.param("organization"), first, max));
  }

  private static int intParameter(QueryStringDecoder query, String name, int absent) {
    List<String> values = query.parameters().get(name);

    if (values == null || values.isEmpty()) {
      return absent;
    }

    return Integer.parseInt(values.getFirst());
  }

  private static Publisher<Void> token(HttpServerResponse response, KeycloakDirectory directory) {
    if (!directory.answersTokenRequest()) {
      return Mono.never();
    }

    return json(response, TOKEN);
  }

  private static Publisher<Void> organizationsOfMember(
      HttpServerRequest request, HttpServerResponse response, KeycloakDirectory directory) {
    String sub = request.param("sub");
    directory.recordOrganizationRead();

    if (directory.hangs(sub)) {
      return Mono.never();
    }

    if (directory.isUnreachable(sub)) {
      return response.status(503).send();
    }

    return json(response, directory.organizationsOfMemberJson(sub));
  }

  private static Publisher<Void> groupsOfMember(
      HttpServerRequest request, HttpServerResponse response, KeycloakDirectory directory) {
    String sub = request.param("sub");

    if (directory.isUnreachable(sub)) {
      return response.status(503).send();
    }

    QueryStringDecoder query = new QueryStringDecoder(request.uri());
    int first = directory.firstHonoured(intParameter(query, "first", 0));
    int max = intParameter(query, "max", Integer.MAX_VALUE);

    return json(response, directory.groupsOfMemberJson(request.param("organization"), sub, first, max));
  }

  private static Publisher<Void> json(HttpServerResponse response, String body) {
    return response.header("Content-Type", "application/json").sendString(Mono.just(body)).then();
  }
}
