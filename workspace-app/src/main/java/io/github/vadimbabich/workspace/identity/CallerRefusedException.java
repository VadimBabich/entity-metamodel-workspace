package io.github.vadimbabich.workspace.identity;

class CallerRefusedException extends RuntimeException {

  CallerRefusedException(String reason) {
    super(reason);
  }
}
