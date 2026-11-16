package io.github.vadimbabich.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.vadimbabich.workspace.support.ComposeImages;
import org.junit.jupiter.api.Test;

class ComposeImagesTest {

  @Test
  void theDatabaseServiceRunsAPostgresImage() {
    assertThat(ComposeImages.imageOf("postgres")).startsWith("postgres:");
  }

  @Test
  void eachServiceIsReadFromItsOwnBlock() {
    assertThat(ComposeImages.imageOf("keycloak")).startsWith("quay.io/keycloak/keycloak:");
  }

  @Test
  void aServiceTheStackDoesNotDefineIsAnError() {
    assertThatThrownBy(() -> ComposeImages.imageOf("no-such-service"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no-such-service");
  }
}
