package io.github.vadimbabich.workspace.mirror;

import io.netty.channel.ChannelOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ClientCredentialsReactiveOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServerOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

@Component
class KeycloakAdminClient {

  static final String REGISTRATION = "keycloak-admin";
  static final String SERVICE_PRINCIPAL = "workspace-mirror";

  private static final String CONNECTION_POOL = "keycloak-admin";
  private static final double RETRY_JITTER = 0.5;
  private static final Authentication SERVICE_ACCOUNT = new AnonymousAuthenticationToken(
      SERVICE_PRINCIPAL, SERVICE_PRINCIPAL, AuthorityUtils.createAuthorityList("ROLE_" + SERVICE_PRINCIPAL));

  private final WebClient adminApi;
  private final RetryBackoffSpec transientFailureRetry;
  private final int pageSize;

  KeycloakAdminClient(
      WebClient.Builder webClientBuilder,
      ReactiveClientRegistrationRepository registrations,
      ReactiveOAuth2AuthorizedClientService authorizedClients,
      MirrorProperties properties) {
    this.adminApi = adminApi(webClientBuilder, registrations, authorizedClients, properties);
    this.transientFailureRetry = transientFailureRetry(properties);
    this.pageSize = properties.pageSize();
  }

  Flux<KeycloakOrganization> organizations() {
    PagedList<KeycloakOrganization> allOrganizations =
        new PagedList<>(KeycloakOrganization.class, "/organizations", List.of());

    return everyPage(allOrganizations);
  }

  Flux<KeycloakOrganization> organizationsOf(String sub) {
    return get(KeycloakOrganization.class, "/organizations/members/{sub}/organizations", sub);
  }

  Flux<KeycloakGroup> groupsOf(String organizationId) {
    PagedList<KeycloakGroup> organizationGroups =
        new PagedList<>(KeycloakGroup.class, "/organizations/{organization}/groups", List.of(organizationId));

    return everyPage(organizationGroups);
  }

  Flux<KeycloakGroup> groupsOfMember(String organizationId, String sub) {
    PagedList<KeycloakGroup> memberGroups = new PagedList<>(
        KeycloakGroup.class, "/organizations/{organization}/members/{sub}/groups", List.of(organizationId, sub));

    return everyPage(memberGroups);
  }

  private <T> Flux<T> everyPage(PagedList<T> pagedList) {
    return pageFrom(pagedList, 0, List.of());
  }

  private <T> Flux<T> pageFrom(PagedList<T> pagedList, int first, List<T> previousPage) {
    List<Object> pageVariables = new ArrayList<>(pagedList.pathVariables());

    pageVariables.add(first);
    pageVariables.add(pageSize);

    return get(pagedList.itemType(), pagedList.path() + "?first={first}&max={max}", pageVariables.toArray())
        .collectList()
        .flatMapMany(page -> pageThenRest(pagedList, page, first, previousPage));
  }

  private <T> Flux<T> pageThenRest(PagedList<T> pagedList, List<T> page, int first, List<T> previousPage) {
    boolean repeatedPage = !page.isEmpty() && page.equals(previousPage);

    if (repeatedPage) {
      return Flux.error(new IllegalStateException("Keycloak returned the same page twice for " + pagedList.path()));
    }

    Flux<T> thisPage = Flux.fromIterable(page);
    boolean lastPage = page.size() < pageSize;

    if (lastPage) {
      return thisPage;
    }

    return thisPage.concatWith(Flux.defer(() -> pageFrom(pagedList, first + pageSize, page)));
  }

  private <T> Flux<T> get(Class<T> itemType, String path, Object... pathVariables) {
    return adminApi.get()
        .uri(path, pathVariables)
        .retrieve()
        .bodyToFlux(itemType)
        .retryWhen(transientFailureRetry);
  }

  private static WebClient adminApi(
      WebClient.Builder webClientBuilder,
      ReactiveClientRegistrationRepository registrations,
      ReactiveOAuth2AuthorizedClientService authorizedClients,
      MirrorProperties properties) {
    ReactorClientHttpConnector boundedConnector = new ReactorClientHttpConnector(boundedHttpClient(properties));
    WebClient tokenEndpoint = webClientBuilder.clone()
        .clientConnector(boundedConnector)
        .build();

    return webClientBuilder.clone()
        .baseUrl(properties.adminUri().toString())
        .clientConnector(boundedConnector)
        .filter(clientCredentials(registrations, authorizedClients, tokenEndpoint))
        .build();
  }

  private static RetryBackoffSpec transientFailureRetry(MirrorProperties properties) {
    return Retry.backoff(properties.retries(), properties.firstRetryBackoff())
        .maxBackoff(properties.maxRetryBackoff())
        .jitter(RETRY_JITTER)
        .filter(KeycloakAdminClient::isTransient)
        .onRetryExhaustedThrow((_, exhausted) -> exhausted.failure());
  }

  private static HttpClient boundedHttpClient(MirrorProperties properties) {
    ConnectionProvider connections = ConnectionProvider.builder(CONNECTION_POOL)
        .maxConnections(properties.maxConnections())
        .pendingAcquireMaxCount(properties.maxPendingConnections())
        .pendingAcquireTimeout(properties.connectionAcquireTimeout())
        .build();

    int connectTimeoutMillis = Math.toIntExact(properties.connectTimeout().toMillis());

    return HttpClient.create(connections)
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis)
        .responseTimeout(properties.responseTimeout());
  }

  private static ServerOAuth2AuthorizedClientExchangeFilterFunction clientCredentials(
      ReactiveClientRegistrationRepository registrations,
      ReactiveOAuth2AuthorizedClientService authorizedClients,
      WebClient tokenEndpoint) {
    WebClientReactiveClientCredentialsTokenResponseClient tokenResponseClient =
        new WebClientReactiveClientCredentialsTokenResponseClient();
    tokenResponseClient.setWebClient(tokenEndpoint);

    ClientCredentialsReactiveOAuth2AuthorizedClientProvider clientCredentialsProvider =
        new ClientCredentialsReactiveOAuth2AuthorizedClientProvider();
    clientCredentialsProvider.setAccessTokenResponseClient(tokenResponseClient);

    AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager authorizedClientManager =
        new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(registrations, authorizedClients);
    authorizedClientManager.setAuthorizedClientProvider(clientCredentialsProvider);

    ServerOAuth2AuthorizedClientExchangeFilterFunction clientCredentials =
        new ServerOAuth2AuthorizedClientExchangeFilterFunction(authorizedClientManager);
    clientCredentials.setDefaultClientRegistrationId(REGISTRATION);
    clientCredentials.setPrincipalResolver(_ -> Mono.just(SERVICE_ACCOUNT));

    return clientCredentials;
  }

  private static boolean isTransient(Throwable failure) {
    if (failure instanceof WebClientResponseException response) {
      HttpStatusCode status = response.getStatusCode();

      return status.is5xxServerError() || status.isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS);
    }

    return failure instanceof WebClientRequestException || failure instanceof TimeoutException;
  }

  private record PagedList<T>(Class<T> itemType, String path, List<Object> pathVariables) {
  }
}
