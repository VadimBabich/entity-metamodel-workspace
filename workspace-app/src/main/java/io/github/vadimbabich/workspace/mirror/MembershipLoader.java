package io.github.vadimbabich.workspace.mirror;

import io.github.vadimbabich.workspace.domain.AppUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class MembershipLoader {

  private static final Logger LOG = LoggerFactory.getLogger(MembershipLoader.class);

  private final KeycloakAdminClient keycloak;
  private final GroupMirror groupMirror;
  private final MembershipService membershipService;
  private final Clock clock;
  private final Duration loadTimeout;
  private final Duration membershipMaxAge;
  private final Duration reloadBackoff;
  private final Map<Long, Mono<Void>> loadsInFlight = new ConcurrentHashMap<>();
  private final Map<Long, Instant> failedReloads = new ConcurrentHashMap<>();

  MembershipLoader(
      KeycloakAdminClient keycloak,
      GroupMirror groupMirror,
      MembershipService membershipService,
      MirrorProperties properties,
      Clock clock) {
    this.keycloak = keycloak;
    this.groupMirror = groupMirror;
    this.membershipService = membershipService;
    this.clock = clock;
    this.loadTimeout = properties.loadTimeout();
    this.membershipMaxAge = properties.membershipMaxAge();
    this.reloadBackoff = properties.reloadBackoff();
  }

  public Mono<Void> ensureCurrent(AppUser user, String companyAlias) {
    Instant loadedAt = user.membershipsLoadedAt();
    Instant now = clock.instant();

    if (loadedAt == null) {
      return loadFor(user, companyAlias);
    }

    boolean stillCurrent = loadedAt.plus(membershipMaxAge).isAfter(now);

    if (stillCurrent) {
      return Mono.empty();
    }

    long appUserId = Objects.requireNonNull(user.id());

    if (isBackingOff(appUserId, now)) {
      return Mono.empty();
    }

    return loadFor(user, companyAlias);
  }

  private boolean isBackingOff(long appUserId, Instant now) {
    Instant failedAt = failedReloads.get(appUserId);

    if (failedAt == null) {
      return false;
    }

    boolean backoffRunning = failedAt.plus(reloadBackoff).isAfter(now);

    if (backoffRunning) {
      return true;
    }

    failedReloads.remove(appUserId, failedAt);

    return false;
  }

  private Mono<Void> loadFor(AppUser user, String companyAlias) {
    long appUserId = Objects.requireNonNull(user.id());
    Mono<Void> sharedLoad = loadsInFlight.computeIfAbsent(
        appUserId, userId -> startLoad(user.companyId(), companyAlias, userId, user.sub()));

    return sharedLoad
        .then(forgetFailedReload(appUserId))
        .onErrorResume(failure -> proceedWithoutMemberships(appUserId, user.sub(), failure));
  }

  private Mono<Void> forgetFailedReload(long appUserId) {
    return Mono.fromRunnable(() -> failedReloads.remove(appUserId));
  }

  private Mono<Void> proceedWithoutMemberships(long appUserId, String sub, Throwable failure) {
    failedReloads.put(appUserId, clock.instant());

    LOG.warn("Loading the memberships of {} failed; the request proceeds with those it had: {}",
        sub, failure.toString());

    return Mono.empty();
  }

  private Mono<Void> startLoad(long companyId, String companyAlias, long appUserId, String sub) {
    return load(companyId, companyAlias, appUserId, sub)
        .timeout(loadTimeout)
        .doFinally(_ -> loadsInFlight.remove(appUserId))
        .cache();
  }

  private Mono<Void> load(long companyId, String companyAlias, long appUserId, String sub) {
    Mono<List<KeycloakGroup>> memberGroups = organizationWithAlias(sub, companyAlias)
        .flatMap(organization -> groupsOfMemberIn(organization, sub))
        .defaultIfEmpty(List.of());

    return memberGroups
        .flatMap(keycloakGroups -> groupMirror.mirrored(companyId, keycloakGroups))
        .flatMap(userGroups -> membershipService.replaceMemberships(companyId, appUserId, userGroups, clock.instant()));
  }

  private Mono<KeycloakOrganization> organizationWithAlias(String sub, String companyAlias) {
    return keycloak.organizationsOf(sub)
        .filter(organization -> companyAlias.equals(organization.alias()))
        .next();
  }

  private Mono<List<KeycloakGroup>> groupsOfMemberIn(KeycloakOrganization organization, String sub) {
    return keycloak.groupsOfMember(organization.id(), sub).collectList();
  }
}
