package io.github.vadimbabich.workspace.mirror;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.vadimbabich.workspace.domain.AppUser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class MembershipLoaderTest {

  private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
  private static final String SUB = "sub-alice";

  private final KeycloakAdminClient keycloak = mock(KeycloakAdminClient.class);
  private final GroupMirror groupMirror = mock(GroupMirror.class);
  private final MembershipService memberships = mock(MembershipService.class);
  private final MovableClock clock = new MovableClock(NOW);
  private final MembershipLoader loader =
      new MembershipLoader(keycloak, groupMirror, memberships, MirrorTestProperties.defaults(), clock);

  @Test
  void membershipsYoungerThanTheMaxAgeAreNotReloaded() {
    StepVerifier.create(loader.ensureCurrent(userLoadedAt(NOW.minus(Duration.ofMinutes(4))), "acme"))
        .verifyComplete();

    verifyNoInteractions(keycloak);
  }

  @Test
  void membershipsOlderThanTheMaxAgeAreReloaded() {
    when(keycloak.organizationsOf(SUB)).thenReturn(Flux.empty());
    when(groupMirror.mirrored(eq(1L), any())).thenReturn(Mono.just(List.of()));
    when(memberships.replaceMemberships(eq(1L), eq(7L), any(), any())).thenReturn(Mono.empty());

    StepVerifier.create(loader.ensureCurrent(userLoadedAt(NOW.minus(Duration.ofMinutes(6))), "acme"))
        .verifyComplete();

    verify(keycloak).organizationsOf(SUB);
  }

  @Test
  void aFailedReloadIsRetriedOnlyAfterTheBackoff() {
    when(keycloak.organizationsOf(SUB)).thenReturn(Flux.error(new IllegalStateException("Keycloak is down")));
    AppUser staleUser = userLoadedAt(NOW.minus(Duration.ofMinutes(10)));

    StepVerifier.create(loader.ensureCurrent(staleUser, "acme")).verifyComplete();
    clock.advance(Duration.ofSeconds(59));
    StepVerifier.create(loader.ensureCurrent(staleUser, "acme")).verifyComplete();

    verify(keycloak, times(1)).organizationsOf(SUB);

    clock.advance(Duration.ofSeconds(2));
    StepVerifier.create(loader.ensureCurrent(staleUser, "acme")).verifyComplete();

    verify(keycloak, times(2)).organizationsOf(SUB);
  }

  @Test
  void aFailedFirstLoadIsRetriedOnTheNextRequest() {
    when(keycloak.organizationsOf(SUB)).thenReturn(Flux.error(new IllegalStateException("Keycloak is down")));
    AppUser newUser = userLoadedAt(null);

    StepVerifier.create(loader.ensureCurrent(newUser, "acme")).verifyComplete();
    StepVerifier.create(loader.ensureCurrent(newUser, "acme")).verifyComplete();

    verify(keycloak, times(2)).organizationsOf(SUB);
  }

  private static AppUser userLoadedAt(@Nullable Instant membershipsLoadedAt) {
    return new AppUser(7L, 1L, SUB, "alice", null, false, null, true, NOW, membershipsLoadedAt);
  }



  private static final class MovableClock extends Clock {

    private Instant now;

    private MovableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
