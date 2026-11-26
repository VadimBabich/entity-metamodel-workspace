package io.github.vadimbabich.workspace.identity;

import java.time.Duration;

public class SigningKeysUnavailableException extends RuntimeException {

  private final Duration retryAfter;

  public SigningKeysUnavailableException(String reason, Duration retryAfter, Throwable cause) {
    super(reason, cause);
    this.retryAfter = retryAfter;
  }

  public Duration retryAfter() {
    return retryAfter;
  }
}
