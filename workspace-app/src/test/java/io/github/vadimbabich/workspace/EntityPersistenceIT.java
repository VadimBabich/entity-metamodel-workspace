package io.github.vadimbabich.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.vadimbabich.workspace.domain.AppUser;
import io.github.vadimbabich.workspace.support.FixtureDatabase;
import io.github.vadimbabich.workspace.support.WorkspaceIntegrationTest;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@WorkspaceIntegrationTest
class EntityPersistenceIT {

  private static final long ACME = 1L;

  @Autowired
  R2dbcEntityTemplate applicationTemplate;

  @Autowired
  FixtureDatabase fixtures;

  @BeforeEach
  void seedACompany() {
    fixtures.deleteAllRows().block();
    fixtures.run("insert into company (alias, name) values ('acme', 'Acme')");
  }

  @Test
  void anEntityWithoutAnIdIsInsertedAndReturnedWithTheGeneratedId() {
    AppUser newUser = new AppUser(null, ACME, "sub-alice", "alice", null, false, null, true, null, null);

    StepVerifier.create(applicationTemplate.insert(newUser))
        .assertNext(insertedUser -> {
          assertThat(insertedUser.id()).isPositive();
          assertThat(insertedUser.sub()).isEqualTo("sub-alice");
        })
        .verifyComplete();
  }

  @Test
  void theInsertedRowCarriesTheDatabaseDefaults() {
    AppUser newUser = new AppUser(null, ACME, "sub-bob", "bob", null, false, null, true, null, null);

    Mono<Boolean> syncedAtWasSet = fixtures.sql("select synced_at is not null from app_user where sub = 'sub-bob'")
        .map(row -> Objects.requireNonNull(row.get(0, Boolean.class)))
        .one();

    StepVerifier.create(applicationTemplate.insert(newUser).then(syncedAtWasSet))
        .expectNext(true)
        .verifyComplete();
  }
}
