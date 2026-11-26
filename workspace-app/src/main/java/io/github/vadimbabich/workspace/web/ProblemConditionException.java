package io.github.vadimbabich.workspace.web;

import org.springframework.web.ErrorResponseException;

public class ProblemConditionException extends ErrorResponseException {

  public ProblemConditionException(ProblemCondition condition) {
    super(condition.status());
    getBody().setType(condition.type());
  }
}
