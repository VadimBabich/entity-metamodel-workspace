package io.github.vadimbabich.workspace.web;

import java.net.URI;
import java.util.Optional;
import org.springframework.http.HttpStatus;

public enum ProblemCondition {

  INVALID_REQUEST("invalid-request", HttpStatus.BAD_REQUEST),
  UNAUTHENTICATED("unauthenticated", HttpStatus.UNAUTHORIZED),
  ITEM_NOT_FOUND("item-not-found", HttpStatus.NOT_FOUND),
  IDENTITY_PROVIDER_UNAVAILABLE("identity-provider-unavailable", HttpStatus.SERVICE_UNAVAILABLE);

  private static final String TYPE_BASE = "https://vadimbabich.github.io/entity-metamodel-workspace/problems/";

  private final String code;
  private final HttpStatus status;

  ProblemCondition(String code, HttpStatus status) {
    this.code = code;
    this.status = status;
  }

  static Optional<ProblemCondition> identifiedBy(URI problemType) {
    for (ProblemCondition condition : values()) {
      if (condition.type().equals(problemType)) {
        return Optional.of(condition);
      }
    }

    return Optional.empty();
  }

  String code() {
    return code;
  }

  HttpStatus status() {
    return status;
  }

  URI type() {
    return URI.create(TYPE_BASE + code);
  }
}
