package io.github.vadimbabich.workspace.mirror;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
class GroupPass {

  private static final Logger LOG = LoggerFactory.getLogger(GroupPass.class);

  private final KeycloakAdminClient keycloak;
  private final GroupMirror groupMirror;
  private final Duration timeout;

  GroupPass(
      KeycloakAdminClient keycloak,
      GroupMirror groupMirror,
      MirrorProperties properties) {
    this.keycloak = keycloak;
    this.groupMirror = groupMirror;
    this.timeout = properties.groupPassTimeout();
  }

  @Scheduled(fixedDelayString = "${workspace.mirror.group-pass-interval:60s}")
  Mono<Void> run() {
    return Mono.defer(this::passOnce)
        .timeout(timeout)
        .onErrorResume(this::logFailedPass);
  }

  private Mono<Void> passOnce() {
    return keycloak.organizations()
        .concatMap(this::mirrorGroupsOf)
        .then();
  }

  private Mono<Void> mirrorGroupsOf(KeycloakOrganization organization) {
    return keycloak.groupsOf(organization.id())
        .collectList()
        .flatMap(keycloakGroups -> groupMirror.mirrorOrganizationGroups(organization.alias(), keycloakGroups));
  }

  private Mono<Void> logFailedPass(Throwable failure) {
    LOG.warn("The group pass failed; the next pass retries: {}", failure.toString());

    return Mono.empty();
  }
}
