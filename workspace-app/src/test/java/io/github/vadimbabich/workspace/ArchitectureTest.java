package io.github.vadimbabich.workspace;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "io.github.vadimbabich.workspace", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  /** Queries are typed through the library; raw SQL strings stay out of request handlers. */
  @ArchTest
  static final ArchRule handlersDoNotWriteSql = noClasses()
      .that().resideInAPackage("..workspace.folders..")
      .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.r2dbc.core.DatabaseClient");

  /** The application is reactive end to end: no JDBC on a request path (Flyway is configuration, not code). */
  @ArchTest
  static final ArchRule noJdbcInApplicationCode = noClasses()
      .should().dependOnClassesThat().resideInAPackage("java.sql..");
}
