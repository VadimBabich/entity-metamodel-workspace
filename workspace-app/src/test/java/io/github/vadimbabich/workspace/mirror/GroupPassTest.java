package io.github.vadimbabich.workspace.mirror;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class GroupPassTest {

  @Test
  void aPassWhoseKeycloakCallNeverAnswersEndsAtItsTimeout() {
    KeycloakAdminClient keycloak = mock(KeycloakAdminClient.class);
    when(keycloak.organizations()).thenReturn(Flux.never());
    GroupPass groupPass = new GroupPass(keycloak, mock(GroupMirror.class), MirrorTestProperties.defaults());

    StepVerifier.withVirtualTime(groupPass::run)
        .expectSubscription()
        .thenAwait(Duration.ofSeconds(30))
        .verifyComplete();
  }
}
