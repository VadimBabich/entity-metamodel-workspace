package io.github.vadimbabich.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.vadimbabich.workspace.support.FixtureDatabase;
import io.github.vadimbabich.workspace.support.WorkspaceIntegrationTest;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@WorkspaceIntegrationTest
class EntityMappingIT {

  private static final String DOMAIN_PACKAGE = "io.github.vadimbabich.workspace.domain";
  private static final String ANY_COLUMN = "true";
  private static final String NULLABLE_COLUMN = "is_nullable = 'YES'";

  @Autowired
  FixtureDatabase fixtures;

  @Test
  void theDomainPackageIsNullMarked() {
    for (Class<?> entityType : entityTypes()) {
      assertThat(entityType.getPackage().isAnnotationPresent(NullMarked.class))
          .as("package of %s is @NullMarked", entityType.getSimpleName())
          .isTrue();
    }
  }

  @Test
  void everyMappedColumnExistsInItsTable() {
    for (Class<?> entityType : entityTypes()) {
      String qualifiedTable = qualifiedTableOf(entityType);
      Set<String> mappedColumns = mappedColumnsOf(entityType);

      StepVerifier.create(columnsOf(qualifiedTable, ANY_COLUMN))
          .assertNext(tableColumns -> assertThat(tableColumns)
              .as("columns of %s mapped by %s", qualifiedTable, entityType.getSimpleName())
              .containsAll(mappedColumns))
          .verifyComplete();
    }
  }

  @Test
  void everyNullableColumnIsANullableProperty() {
    for (Class<?> entityType : entityTypes()) {
      Set<String> columnsOfNullableProperties = columnsOfNullablePropertiesOf(entityType);

      StepVerifier.create(columnsOf(qualifiedTableOf(entityType), NULLABLE_COLUMN))
          .assertNext(nullableColumns -> assertThat(columnsOfNullableProperties)
              .as("nullable columns of %s mapped by @Nullable properties", entityType.getSimpleName())
              .containsAll(nullableColumns))
          .verifyComplete();
    }
  }

  private static List<Class<?>> entityTypes() {
    List<Class<?>> entityTypes = new ArrayList<>();

    for (JavaClass importedClass : new ClassFileImporter().importPackages(DOMAIN_PACKAGE)) {
      if (importedClass.isAnnotatedWith(Table.class)) {
        entityTypes.add(importedClass.reflect());
      }
    }

    assertThat(entityTypes).as("@Table types in %s", DOMAIN_PACKAGE).isNotEmpty();
    return entityTypes;
  }

  private static String qualifiedTableOf(Class<?> entityType) {
    Table table = entityType.getAnnotation(Table.class);

    if (table.schema().isEmpty()) {
      return "public." + table.value();
    }

    return table.schema() + "." + table.value();
  }

  private static List<Field> persistedFieldsOf(Class<?> entityType) {
    List<Field> persistedFields = new ArrayList<>();

    for (Field declaredField : entityType.getDeclaredFields()) {
      if (declaredField.isAnnotationPresent(Column.class)) {
        persistedFields.add(declaredField);
      }
    }

    return persistedFields;
  }

  private static Set<String> mappedColumnsOf(Class<?> entityType) {
    return persistedFieldsOf(entityType).stream()
        .map(persistedField -> persistedField.getAnnotation(Column.class).value())
        .collect(Collectors.toSet());
  }

  private static Set<String> columnsOfNullablePropertiesOf(Class<?> entityType) {
    Set<String> columnNames = new HashSet<>();

    for (Field persistedField : persistedFieldsOf(entityType)) {
      if (persistedField.getAnnotatedType().isAnnotationPresent(Nullable.class)) {
        columnNames.add(persistedField.getAnnotation(Column.class).value());
      }
    }

    return columnNames;
  }

  private Mono<Set<String>> columnsOf(String qualifiedTable, String nullabilityCondition) {
    String[] schemaAndTable = qualifiedTable.split("\\.");

    return fixtures.sql("""
            select column_name from information_schema.columns
            where table_schema = '%s' and table_name = '%s' and %s
            """.formatted(schemaAndTable[0], schemaAndTable[1], nullabilityCondition))
        .map(row -> Objects.requireNonNull(row.get(0, String.class)))
        .all()
        .collect(Collectors.toSet());
  }
}
