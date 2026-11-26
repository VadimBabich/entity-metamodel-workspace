package io.github.vadimbabich.workspace.web;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

@Component
class ProblemDetails {

  private static final URI ABOUT_BLANK = URI.create("about:blank");
  private static final String REQUEST_ID = "requestId";
  private static final String GENERIC_STATUS_DETAIL = "problem.status.detail";

  private final MessageSource messages;

  ProblemDetails(MessageSource messages) {
    this.messages = messages;
  }

  ProblemDetail unauthenticated(ServerWebExchange exchange) {
    ProblemDetail problem = ProblemDetail.forStatus(ProblemCondition.UNAUTHENTICATED.status());

    describeCondition(problem, ProblemCondition.UNAUTHENTICATED, exchange);
    identifyOccurrence(problem, exchange);

    return problem;
  }

  ProblemDetail completed(ProblemDetail frameworkProblem, ServerWebExchange exchange) {
    describe(frameworkProblem, exchange);
    identifyOccurrence(frameworkProblem, exchange);

    return frameworkProblem;
  }

  private void describe(ProblemDetail frameworkProblem, ServerWebExchange exchange) {
    Optional<ProblemCondition> namedCondition = conditionNamedBy(frameworkProblem);

    if (namedCondition.isPresent()) {
      describeCondition(frameworkProblem, namedCondition.get(), exchange);
      return;
    }

    if (carriesItsOwnType(frameworkProblem)) {
      return;
    }

    if (frameworkProblem.getStatus() == HttpStatus.BAD_REQUEST.value()) {
      describeCondition(frameworkProblem, ProblemCondition.INVALID_REQUEST, exchange);
      return;
    }

    describeStatusOnly(frameworkProblem, exchange);
  }

  private static Optional<ProblemCondition> conditionNamedBy(ProblemDetail problem) {
    URI problemType = problem.getType();

    if (problemType == null) {
      return Optional.empty();
    }

    return ProblemCondition.identifiedBy(problemType);
  }

  private static boolean carriesItsOwnType(ProblemDetail problem) {
    URI problemType = problem.getType();

    if (problemType == null) {
      return false;
    }

    return !ABOUT_BLANK.equals(problemType);
  }

  private void describeCondition(ProblemDetail problem, ProblemCondition condition, ServerWebExchange exchange) {
    Locale locale = localeOf(exchange);
    String messagePrefix = "problem." + condition.code();

    problem.setType(condition.type());
    problem.setTitle(messages.getMessage(messagePrefix + ".title", null, locale));
    problem.setDetail(messages.getMessage(messagePrefix + ".detail", null, locale));
  }

  private void describeStatusOnly(ProblemDetail problem, ServerWebExchange exchange) {
    Locale locale = localeOf(exchange);
    String genericDetail = messages.getMessage(GENERIC_STATUS_DETAIL, null, locale);
    String statusDetailKey = "problem.status." + problem.getStatus() + ".detail";
    HttpStatus status = HttpStatus.valueOf(problem.getStatus());

    problem.setType(ABOUT_BLANK);
    problem.setTitle(status.getReasonPhrase());
    problem.setDetail(messages.getMessage(statusDetailKey, null, genericDetail, locale));
  }

  private static void identifyOccurrence(ProblemDetail problem, ServerWebExchange exchange) {
    String requestPath = exchange.getRequest().getPath().value();

    problem.setInstance(URI.create(requestPath));
    problem.setProperty(REQUEST_ID, exchange.getRequest().getId());
  }

  private static Locale localeOf(ServerWebExchange exchange) {
    Locale requestedLocale = exchange.getLocaleContext().getLocale();

    if (requestedLocale == null) {
      return Locale.ROOT;
    }

    return requestedLocale;
  }
}
