package io.github.vadimbabich.workspace;

import static io.github.vadimbabich.workspace.support.FailingEndpoints.FAILURE_THREE_OPERATORS_DEEP;
import static io.github.vadimbabich.workspace.support.FailingEndpoints.FILTER_FAILURE;
import static io.github.vadimbabich.workspace.support.FailingEndpoints.FILTER_UNAVAILABLE;
import static io.github.vadimbabich.workspace.support.FailingEndpoints.RETRY_AFTER_SECONDS;
import static io.github.vadimbabich.workspace.support.FailingEndpoints.INTERNAL_DETAIL;
import static io.github.vadimbabich.workspace.support.FailingEndpoints.LIBRARY_ARGUMENT_FAILURE;
import static io.github.vadimbabich.workspace.support.FailingEndpoints.UNEXPECTED_FAILURE;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.github.vadimbabich.workspace.support.FixtureDatabase;
import io.github.vadimbabich.workspace.support.LogCapture;
import io.github.vadimbabich.workspace.support.ProblemTypes;
import io.github.vadimbabich.workspace.support.TestCallers;
import io.github.vadimbabich.workspace.support.TestTokens;
import io.github.vadimbabich.workspace.support.WorkspaceIntegrationTest;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

@WorkspaceIntegrationTest
class ErrorResponsesIT {

  private static final String ABOUT_BLANK = "about:blank";
  private static final String CHILDREN = "/api/v1/folders/1/children";
  private static final String APPLICATION_LOGGERS = "io.github.vadimbabich.workspace";
  private static final ParameterizedTypeReference<Map<String, Object>> PROBLEM = new ParameterizedTypeReference<>() {
  };

  @Autowired
  WebTestClient client;

  @Autowired
  FixtureDatabase fixtures;

  @BeforeEach
  void seedTheCallersCompany() {
    fixtures.deleteAllRows().block();
    fixtures.run("insert into company (alias, name) values ('acme', 'Acme')");
  }

  @ParameterizedTest
  @ValueSource(strings = {"?limit=-5", "?limit=1000", "?limit=ten"})
  void aViolatedQueryParameterIsAnInvalidRequestProblem(String query) {
    Map<String, Object> problem = problemFrom(authenticatedGet(CHILDREN + query), HttpStatus.BAD_REQUEST);

    assertThat(problem)
        .containsEntry("type", ProblemTypes.BASE + "invalid-request")
        .containsEntry("title", "Invalid request")
        .containsEntry("instance", CHILDREN);
    assertThat(String.valueOf(problem.get("detail"))).doesNotContain(query.substring(query.indexOf('=') + 1));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "abc"})
  void aViolatedPathParameterIsAnInvalidRequestProblem(String folderId) {
    String path = "/api/v1/folders/" + folderId + "/children";

    Map<String, Object> problem = problemFrom(authenticatedGet(path), HttpStatus.BAD_REQUEST);

    assertThat(problem)
        .containsEntry("type", ProblemTypes.BASE + "invalid-request")
        .containsEntry("instance", path);
  }

  @Test
  void aRequestWithoutATokenIsAnUnauthenticatedProblemWithAChallenge() {
    WebTestClient.ResponseSpec response = client.get().uri(CHILDREN).exchange();

    response.expectHeader().valueMatches(HttpHeaders.WWW_AUTHENTICATE, "Bearer.*");

    Map<String, Object> problem = problemFrom(response, HttpStatus.UNAUTHORIZED);

    assertThat(problem)
        .containsEntry("type", ProblemTypes.BASE + "unauthenticated")
        .containsEntry("title", "Unauthenticated")
        .containsEntry("instance", CHILDREN);
  }

  @ParameterizedTest
  @MethodSource("refusedTokens")
  void aRefusedTokenIsAnUnauthenticatedProblemWhoseChallengeNamesNoReason(String refusedToken) {
    WebTestClient.ResponseSpec response = client.get().uri(CHILDREN)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + refusedToken)
        .exchange();

    response.expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"");

    Map<String, Object> problem = problemFrom(response, HttpStatus.UNAUTHORIZED);

    assertThat(problem).containsEntry("type", ProblemTypes.BASE + "unauthenticated");
  }

  static Stream<String> refusedTokens() {
    Instant inAMinute = Instant.now().plusSeconds(60);
    Instant twoMinutesAgo = Instant.now().minusSeconds(120);
    PrivateKey untrustedKey = TestTokens.newRsaKeyPair().getPrivate();
    PrivateKey trustedKey = TestTokens.trustedSigningKey();

    String expired = TestTokens.signedToken("sub-expired", twoMinutesAgo);
    String forged =
        TestTokens.signedToken(untrustedKey, TestTokens.ISSUER, TestTokens.AUDIENCE, "sub-forged", inAMinute);
    String forAnotherAudience =
        TestTokens.signedToken(trustedKey, TestTokens.ISSUER, "account", "sub-elsewhere", inAMinute);

    return Stream.of("not-a-token", expired, forged, forAnotherAudience);
  }

  @Test
  void anUnknownPathIsANotFoundProblemThatNamesNoInternals() {
    Map<String, Object> problem = problemFrom(authenticatedGet("/api/v1/nothing-here"), HttpStatus.NOT_FOUND);

    assertThat(problem).containsEntry("type", ABOUT_BLANK).containsEntry("title", "Not Found");
    assertThat(String.valueOf(problem.get("detail"))).doesNotContainIgnoringCase("static resource");
  }

  @Test
  void anUnsupportedMethodIsAMethodNotAllowedProblemWithAllow() {
    WebTestClient.ResponseSpec response = client.mutateWith(TestCallers.acmeMember())
        .method(HttpMethod.DELETE).uri(CHILDREN)
        .exchange();

    response.expectHeader().value(HttpHeaders.ALLOW, allowed -> assertThat(allowed).contains("GET"));

    Map<String, Object> problem = problemFrom(response, HttpStatus.METHOD_NOT_ALLOWED);

    assertThat(problem).containsEntry("type", ABOUT_BLANK);
  }

  @Test
  void aSuccessIsNeverLabelledAsAProblem() {
    WebTestClient.ResponseSpec response = client.mutateWith(TestCallers.acmeMember())
        .get().uri(CHILDREN)
        .accept(MediaType.APPLICATION_PROBLEM_JSON)
        .exchange();

    Map<String, Object> problem = problemFrom(response, HttpStatus.NOT_ACCEPTABLE);

    assertThat(problem).containsEntry("type", ABOUT_BLANK);
  }

  @Test
  void anUnexpectedFailureIsAGenericProblemAndOneErrorLogLineWithItsRequestId() {
    try (LogCapture applicationLog = LogCapture.open(APPLICATION_LOGGERS, Level.ERROR)) {
      Map<String, Object> problem = problemFrom(authenticatedGet(UNEXPECTED_FAILURE), HttpStatus.INTERNAL_SERVER_ERROR);

      assertThat(problem)
          .containsEntry("type", ABOUT_BLANK)
          .containsEntry("title", "Internal Server Error")
          .doesNotContainKeys("trace", "message", "exception", "errors");
      assertThat(problem.toString()).doesNotContain(INTERNAL_DETAIL);

      List<ILoggingEvent> errorLines = applicationLog.events();

      assertThat(errorLines).hasSize(1);
      assertThat(errorLines.getFirst().getFormattedMessage()).contains(String.valueOf(problem.get("requestId")));
      assertThat(errorLines.getFirst().getThrowableProxy()).isNotNull();
    }
  }

  @Test
  void aFailureInTheFilterChainIsAGenericProblemAndOneErrorLogLine() {
    try (LogCapture applicationLog = LogCapture.open(APPLICATION_LOGGERS, Level.ERROR)) {
      Map<String, Object> problem = problemFrom(authenticatedGet(FILTER_FAILURE), HttpStatus.INTERNAL_SERVER_ERROR);

      assertThat(problem).containsEntry("type", ABOUT_BLANK).containsEntry("instance", FILTER_FAILURE);
      assertThat(problem.toString()).doesNotContain(INTERNAL_DETAIL);

      List<ILoggingEvent> errorLines = applicationLog.events();

      assertThat(errorLines).hasSize(1);
      assertThat(errorLines.getFirst().getFormattedMessage()).contains(String.valueOf(problem.get("requestId")));
    }
  }

  @Test
  void anErrorResponseFromTheFilterChainKeepsItsStatusAndHeaders() {
    WebTestClient.ResponseSpec response = authenticatedGet(FILTER_UNAVAILABLE);

    response.expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);

    Map<String, Object> problem = problemFrom(response, HttpStatus.SERVICE_UNAVAILABLE);

    assertThat(problem).containsEntry("type", ABOUT_BLANK).containsEntry("title", "Service Unavailable");
  }

  @Test
  void aFailureRaisedDeepInsideAPipelineIsAProblem() {
    WebTestClient.ResponseSpec response = authenticatedGet(FAILURE_THREE_OPERATORS_DEEP);

    Map<String, Object> problem = problemFrom(response, HttpStatus.INTERNAL_SERVER_ERROR);

    assertThat(problem).containsEntry("type", ABOUT_BLANK);
  }

  @Test
  void anIllegalArgumentFromBelowTheWebLayerIsAServerErrorNotABadRequest() {
    WebTestClient.ResponseSpec response = authenticatedGet(LIBRARY_ARGUMENT_FAILURE);

    Map<String, Object> problem = problemFrom(response, HttpStatus.INTERNAL_SERVER_ERROR);

    assertThat(problem).containsEntry("type", ABOUT_BLANK);
    assertThat(problem.toString()).doesNotContain(INTERNAL_DETAIL);
  }

  @Test
  void everyResponseCarriesTheSecurityHeadersAndForbidsFraming() {
    authenticatedGet(CHILDREN)
        .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
        .expectHeader().valueEquals("X-Frame-Options", "DENY")
        .expectHeader().valueEquals("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate")
        .expectHeader().valueEquals("Pragma", "no-cache")
        .expectHeader().valueEquals("Expires", "0")
        .expectHeader().valueEquals("X-XSS-Protection", "0")
        .expectHeader().valueEquals("Referrer-Policy", "no-referrer")
        .expectHeader().valueEquals("Content-Security-Policy", "frame-ancestors 'none'");
  }

  private WebTestClient.ResponseSpec authenticatedGet(String path) {
    return client.mutateWith(TestCallers.acmeMember()).get().uri(path).exchange();
  }

  private static Map<String, Object> problemFrom(WebTestClient.ResponseSpec response, HttpStatus expectedStatus) {
    EntityExchangeResult<Map<String, Object>> exchange = response
        .expectStatus().isEqualTo(expectedStatus)
        .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody(PROBLEM)
        .returnResult();

    Map<String, Object> problem = exchange.getResponseBody();

    assertThat(problem).isNotNull();
    assertThat(problem)
        .containsEntry("status", expectedStatus.value())
        .containsKey("detail");
    assertThat(String.valueOf(problem.get("requestId"))).isNotBlank().isNotEqualTo("null");
    return problem;
  }
}
