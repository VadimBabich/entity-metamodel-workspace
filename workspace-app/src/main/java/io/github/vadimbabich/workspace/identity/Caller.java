package io.github.vadimbabich.workspace.identity;

import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import reactor.core.publisher.Mono;

public record Caller(long companyId, long appUserId, String sub, boolean administrator) {

  public static Mono<Caller> current() {
    return ReactiveSecurityContextHolder.getContext()
        .flatMap(Caller::callerOf)
        .switchIfEmpty(Mono.error(() -> new IllegalStateException("the request's security context carries no caller")));
  }

  private static Mono<Caller> callerOf(SecurityContext securityContext) {
    if (securityContext.getAuthentication() instanceof CallerAuthentication callerAuthentication) {
      return Mono.just(callerAuthentication.getPrincipal());
    }

    return Mono.empty();
  }
}
