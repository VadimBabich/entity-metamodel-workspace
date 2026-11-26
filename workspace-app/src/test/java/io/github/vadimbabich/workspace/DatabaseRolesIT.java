package io.github.vadimbabich.workspace;

import static io.github.vadimbabich.workspace.support.TestDatabase.APPLICATION_ROLE;
import static io.github.vadimbabich.workspace.support.SqlStates.INSUFFICIENT_PRIVILEGE;
import static io.github.vadimbabich.workspace.support.SqlStates.refusedWith;
import static io.github.vadimbabich.workspace.support.TestDatabase.MIGRATION_ROLE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.vadimbabich.workspace.support.FixtureDatabase;
import io.github.vadimbabich.workspace.support.WorkspaceIntegrationTest;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@WorkspaceIntegrationTest
class DatabaseRolesIT {

  @Autowired
  DatabaseClient applicationDatabase;

  @Autowired
  FixtureDatabase fixtures;

  @Test
  void theApplicationConnectsAsTheApplicationRole() {
    StepVerifier.create(currentUser())
        .expectNext(APPLICATION_ROLE)
        .verifyComplete();
  }

  @ParameterizedTest
  @ValueSource(strings = {APPLICATION_ROLE, MIGRATION_ROLE})
  void neitherRoleHoldsAnAdministrativeAttribute(String roleName) {
    Mono<Boolean> administrative = flagOf("""
        select rolsuper or rolcreaterole or rolcreatedb from pg_roles where rolname = '%s'
        """.formatted(roleName));

    StepVerifier.create(administrative)
        .expectNext(false)
        .verifyComplete();
  }

  @Test
  void theApplicationsConnectionDoesNotOwnTheDatabase() {
    Mono<Boolean> ownedByAnotherRole = flagOf("""
        select pg_get_userbyid(datdba) <> current_user from pg_database where datname = current_database()
        """);

    StepVerifier.create(ownedByAnotherRole)
        .expectNext(true)
        .verifyComplete();
  }

  @Test
  void theMigrationRoleOwnsTheMigratedTables() {
    Mono<List<String>> ownedByTheMigrationRole = relationNamesOf("""
        select c.relname
        from pg_class c join pg_namespace n on n.oid = c.relnamespace
        where n.nspname = 'public' and c.relkind = 'r' and pg_get_userbyid(c.relowner) = '%s'
        """.formatted(MIGRATION_ROLE));

    StepVerifier.create(ownedByTheMigrationRole)
        .assertNext(tableNames -> assertThat(tableNames).contains("flyway_schema_history", "node"))
        .verifyComplete();
  }

  @Test
  void noOtherRoleOwnsATableOrSequence() {
    Mono<List<String>> ownedByAnotherRole = relationNamesOf("""
        select c.relname
        from pg_class c join pg_namespace n on n.oid = c.relnamespace
        where n.nspname in ('public', 'acl') and c.relkind in ('r', 'p', 'S') and pg_get_userbyid(c.relowner) <> '%s'
        """.formatted(MIGRATION_ROLE));

    StepVerifier.create(ownedByAnotherRole)
        .assertNext(relationNames -> assertThat(relationNames).isEmpty())
        .verifyComplete();
  }

  @Test
  void theMigrationRoleMayCreateInTheDatabaseAndThePublicSchema() {
    Mono<Boolean> mayCreate = flagOf("""
        select has_database_privilege('%1$s', current_database(), 'CREATE')
          and has_schema_privilege('%1$s', 'public', 'CREATE, USAGE')
        """.formatted(MIGRATION_ROLE));

    StepVerifier.create(mayCreate)
        .expectNext(true)
        .verifyComplete();
  }

  @Test
  void theApplicationRoleMayUseButNotCreateInThePublicSchema() {
    Mono<Boolean> usesWithoutCreating = flagOf("""
        select has_schema_privilege('%1$s', 'public', 'USAGE')
          and not has_schema_privilege('%1$s', 'public', 'CREATE')
          and not has_database_privilege('%1$s', current_database(), 'CREATE')
        """.formatted(APPLICATION_ROLE));

    StepVerifier.create(usesWithoutCreating)
        .expectNext(true)
        .verifyComplete();
  }

  @Test
  void theApplicationRoleHoldsExactlyTheTablePrivilegesTheMigrationsGrant() {
    Mono<List<String>> tablePrivileges = relationNamesOf("""
        select table_schema || '.' || table_name || ' ' || privilege_type
        from information_schema.role_table_grants
        where grantee = '%s'
        order by 1
        """.formatted(APPLICATION_ROLE));

    StepVerifier.create(tablePrivileges)
        .assertNext(privileges -> assertThat(privileges).containsExactly("public.node SELECT"))
        .verifyComplete();
  }

  @Test
  void theApplicationRoleHoldsNoSequencePrivilege() {
    Mono<Boolean> holdsAny = flagOf("""
        select coalesce(bool_or(has_sequence_privilege('%s', c.oid, 'USAGE, SELECT, UPDATE')), false)
        from pg_class c join pg_namespace n on n.oid = c.relnamespace
        where n.nspname in ('public', 'acl') and c.relkind = 'S'
        """.formatted(APPLICATION_ROLE));

    StepVerifier.create(holdsAny)
        .expectNext(false)
        .verifyComplete();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "alter table node add column probe integer",
      "drop table node",
      "alter table node disable trigger all",
      "truncate node",
      "select count(*) from flyway_schema_history"
  })
  void theApplicationRoleIsRefused(String statement) {
    StepVerifier.create(applicationDatabase.sql(statement).then())
        .expectErrorSatisfies(refusedWith(INSUFFICIENT_PRIVILEGE))
        .verify();
  }

  @Test
  void fixturesResetTablesThroughTheMigrationRole() {
    StepVerifier.create(fixtures.deleteAllRows())
        .verifyComplete();
  }

  private Mono<String> currentUser() {
    return applicationDatabase.sql("select current_user")
        .map(row -> Objects.requireNonNull(row.get(0, String.class)))
        .one();
  }

  private Mono<List<String>> relationNamesOf(String query) {
    return applicationDatabase.sql(query)
        .map(row -> Objects.requireNonNull(row.get(0, String.class)))
        .all()
        .collectList();
  }

  private Mono<Boolean> flagOf(String query) {
    return applicationDatabase.sql(query)
        .map(row -> Objects.requireNonNull(row.get(0, Boolean.class)))
        .one();
  }
}
