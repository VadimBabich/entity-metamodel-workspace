package io.github.vadimbabich.workspace;

import static io.github.vadimbabich.workspace.support.SqlStates.CHECK_VIOLATION;
import static io.github.vadimbabich.workspace.support.SqlStates.FOREIGN_KEY_VIOLATION;
import static io.github.vadimbabich.workspace.support.SqlStates.UNIQUE_VIOLATION;
import static io.github.vadimbabich.workspace.support.SqlStates.refusedWith;
import static io.github.vadimbabich.workspace.support.TestDatabase.MIGRATION_ROLE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.vadimbabich.workspace.support.FixtureDatabase;
import io.github.vadimbabich.workspace.support.WorkspaceIntegrationTest;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@WorkspaceIntegrationTest
class SchemaIT {

  private static final long LARGEST_EXACT_JSON_INTEGER = 9007199254740991L;

  @Autowired
  FixtureDatabase fixtures;

  @BeforeEach
  void seedTwoCompanies() {
    fixtures.deleteAllRows().block();
    fixtures.run("insert into company (alias, name) values ('acme', 'Acme'), ('globex', 'Globex')");
    fixtures.run("""
        insert into node (company_id, parent_id, kind_rank, name, created_by) values
          (1, null, 0, 'acme-root', 'seed'),
          (1, 1, 0, 'acme-child', 'seed'),
          (2, null, 0, 'globex-root', 'seed')
        """);
  }

  @Test
  void aChildMustBeInItsParentsCompany() {
    Mono<Void> crossCompanyChild = statement("""
        insert into node (company_id, parent_id, kind_rank, name, created_by) values (2, 1, 0, 'intruder', 'seed')
        """);

    StepVerifier.create(crossCompanyChild)
        .expectErrorSatisfies(refusedWith(FOREIGN_KEY_VIOLATION))
        .verify();
  }

  @Test
  void aChildCannotMoveToAnotherCompany() {
    StepVerifier.create(statement("update node set company_id = 2 where name = 'acme-child'"))
        .expectErrorSatisfies(refusedWith(FOREIGN_KEY_VIOLATION))
        .verify();
  }

  @Test
  void aParentWithChildrenCannotMoveToAnotherCompany() {
    StepVerifier.create(statement("update node set company_id = 2 where name = 'acme-root'"))
        .expectErrorSatisfies(refusedWith(FOREIGN_KEY_VIOLATION))
        .verify();
  }

  @Test
  void aGrantMustBeInItsItemsCompany() {
    Mono<Void> crossCompanyGrant = statement("""
        insert into acl.access_grant (company_id, node_id, subject_kind, subject_id, role) values (2, 1, 3, 2, 1)
        """);

    StepVerifier.create(crossCompanyGrant)
        .expectErrorSatisfies(refusedWith(FOREIGN_KEY_VIOLATION))
        .verify();
  }

  @Test
  void aMembershipMustBeInItsGroupsCompany() {
    fixtures.run("insert into app_user (company_id, sub, username) values (1, 'sub-alice', 'alice')");
    fixtures.run("""
        insert into user_group (company_id, keycloak_id, name)
        values (2, '00000000-0000-0000-0000-000000000001', 'editors')
        """);

    Mono<Void> crossCompanyMembership = statement("""
        insert into group_membership (company_id, user_group_id, app_user_id) values (1, 1, 1)
        """);

    StepVerifier.create(crossCompanyMembership)
        .expectErrorSatisfies(refusedWith(FOREIGN_KEY_VIOLATION))
        .verify();
  }

  @Test
  void aFileMustHaveAParent() {
    Mono<Void> topLevelFile = statement("""
        insert into node (company_id, parent_id, kind_rank, name, created_by) values (1, null, 1, 'loose.txt', 'seed')
        """);

    StepVerifier.create(topLevelFile)
        .expectErrorSatisfies(refusedWith(CHECK_VIOLATION))
        .verify();
  }

  @Test
  void twoLiveSiblingsCannotShareAName() {
    Mono<Void> duplicateChild = statement("""
        insert into node (company_id, parent_id, kind_rank, name, created_by) values (1, 1, 0, 'acme-child', 'seed')
        """);

    StepVerifier.create(duplicateChild)
        .expectErrorSatisfies(refusedWith(UNIQUE_VIOLATION))
        .verify();
  }

  @Test
  void twoLiveTopLevelFoldersOfOneCompanyCannotShareAName() {
    Mono<Void> duplicateTopLevel = statement("""
        insert into node (company_id, parent_id, kind_rank, name, created_by) values (1, null, 0, 'acme-root', 'seed')
        """);

    StepVerifier.create(duplicateTopLevel)
        .expectErrorSatisfies(refusedWith(UNIQUE_VIOLATION))
        .verify();
  }

  @Test
  void aTrashedSiblingDoesNotHoldItsName() {
    fixtures.run("update node set deleted_at = now() where name = 'acme-child'");

    Mono<Void> reusedName = statement("""
        insert into node (company_id, parent_id, kind_rank, name, created_by) values (1, 1, 0, 'acme-child', 'seed')
        """);

    StepVerifier.create(reusedName)
        .verifyComplete();
  }

  @Test
  void aSubjectHoldsOneGrantPerItem() {
    fixtures.run("""
        insert into acl.access_grant (company_id, node_id, subject_kind, subject_id, role) values (1, 1, 3, 1, 1)
        """);

    Mono<Void> secondGrant = statement("""
        insert into acl.access_grant (company_id, node_id, subject_kind, subject_id, role) values (1, 1, 3, 1, 5)
        """);

    StepVerifier.create(secondGrant)
        .expectErrorSatisfies(refusedWith(UNIQUE_VIOLATION))
        .verify();
  }

  @Test
  void aCallersGrantsAreFoundByTheirSubjectsWithinTheCompany() {
    Mono<List<String>> grantIndexes = namesOf("""
        select pg_get_indexdef(indexrelid) from pg_index where indrelid = 'acl.access_grant'::regclass
        """);

    StepVerifier.create(grantIndexes)
        .assertNext(indexes -> assertThat(indexes)
            .anyMatch(index -> index.contains("(company_id, subject_kind, subject_id")))
        .verifyComplete();
  }

  @Test
  void noTableOrColumnIsAReservedWord() {
    Mono<List<String>> reservedNames = namesOf("""
        select c.table_name || '.' || c.column_name
        from information_schema.columns c
        where c.table_schema in ('public', 'acl')
          and (c.table_name in (select word from pg_get_keywords() where catcode = 'R')
            or c.column_name in (select word from pg_get_keywords() where catcode = 'R'))
        """);

    StepVerifier.create(reservedNames)
        .assertNext(names -> assertThat(names).isEmpty())
        .verifyComplete();
  }

  @Test
  void everyForeignKeyRestrictsDeletes() {
    Mono<List<String>> foreignKeyActions = namesOf("""
        select c.conname::text || ' ' || c.confdeltype::text
        from pg_constraint c join pg_namespace n on n.oid = c.connamespace
        where c.contype = 'f' and n.nspname in ('public', 'acl')
        """);

    StepVerifier.create(foreignKeyActions)
        .assertNext(actions -> assertThat(actions)
            .isNotEmpty()
            .allMatch(action -> action.endsWith(" r")))
        .verifyComplete();
  }

  @Test
  void noTableOrColumnIsNamedTenant() {
    Mono<List<String>> tenantNames = namesOf("""
        select table_schema || '.' || table_name || '.' || column_name
        from information_schema.columns
        where table_schema in ('public', 'acl') and (table_name like '%tenant%' or column_name like '%tenant%')
        """);

    StepVerifier.create(tenantNames)
        .assertNext(names -> assertThat(names).isEmpty())
        .verifyComplete();
  }

  @Test
  void theAclSchemaBelongsToTheMigrationRole() {
    Mono<List<String>> aclOwner = namesOf("select pg_get_userbyid(nspowner) from pg_namespace where nspname = 'acl'");

    StepVerifier.create(aclOwner)
        .assertNext(owners -> assertThat(owners).containsExactly(MIGRATION_ROLE))
        .verifyComplete();
  }

  @Test
  void namesCompareByteByByte() {
    Mono<List<String>> nameCollation = namesOf("""
        select collation_name from information_schema.columns where table_name = 'node' and column_name = 'name'
        """);

    StepVerifier.create(nameCollation)
        .assertNext(collations -> assertThat(collations).containsExactly("C"))
        .verifyComplete();
  }

  @Test
  void everyIdentityFitsAnExactJsonInteger() {
    Mono<List<String>> identityLimits = namesOf("""
        select sequencename || ' ' || max_value from pg_sequences where schemaname in ('public', 'acl')
        """);

    StepVerifier.create(identityLimits)
        .assertNext(limits -> assertThat(limits)
            .hasSize(6)
            .allMatch(limit -> limit.endsWith(" " + LARGEST_EXACT_JSON_INTEGER)))
        .verifyComplete();
  }

  private Mono<Void> statement(String sql) {
    return fixtures.sql(sql).then();
  }

  private Mono<List<String>> namesOf(String query) {
    return fixtures.sql(query)
        .map(row -> Objects.requireNonNull(row.get(0, String.class)))
        .all()
        .collectList();
  }
}
