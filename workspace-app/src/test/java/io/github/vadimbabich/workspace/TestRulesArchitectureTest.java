package io.github.vadimbabich.workspace;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Duration;

/**
 * Rules over the test classes that keep integration tests on one shared Spring context and one database
 * container.
 */
@AnalyzeClasses(packages = "io.github.vadimbabich.workspace", importOptions = ImportOption.OnlyIncludeTests.class)
class TestRulesArchitectureTest {

  private static final String WORKSPACE_INTEGRATION_TEST =
      "io.github.vadimbabich.workspace.support.WorkspaceIntegrationTest";

  @ArchTest
  static final ArchRule integrationTestsUseTheSharedConfiguration = classes()
      .that().haveSimpleNameEndingWith("IT")
      .should().beMetaAnnotatedWith(WORKSPACE_INTEGRATION_TEST);

  @ArchTest
  static final ArchRule noContainerFields = noFields()
      .should().beAnnotatedWith("org.testcontainers.junit.jupiter.Container");

  @ArchTest
  static final ArchRule noTestcontainersExtension = noClasses()
      .should().beMetaAnnotatedWith("org.testcontainers.junit.jupiter.Testcontainers");

  // The meta form is required: @SpringBootTest usually arrives inside @WorkspaceIntegrationTest. Annotation
  // types are excluded because @WorkspaceIntegrationTest itself ends with "Test".
  @ArchTest
  static final ArchRule unitTestsLoadNoContext = noClasses()
      .that().haveSimpleNameEndingWith("Test")
      .and().areNotAnnotations()
      .should().beMetaAnnotatedWith("org.springframework.boot.test.context.SpringBootTest");

  @ArchTest
  static final ArchRule noSleeping = noClasses()
      .should().callMethod(Thread.class, "sleep", long.class)
      .orShould().callMethod(Thread.class, "sleep", long.class, int.class)
      .orShould().callMethod(Thread.class, "sleep", Duration.class);

  @ArchTest
  static final ArchRule noMockitoBeanInIntegrationTests = noFields()
      .that().areDeclaredInClassesThat().haveSimpleNameEndingWith("IT")
      .should().beAnnotatedWith("org.springframework.test.context.bean.override.mockito.MockitoBean");

  @ArchTest
  static final ArchRule noDirtiesContextOnClasses = noClasses()
      .should().beMetaAnnotatedWith("org.springframework.test.annotation.DirtiesContext");

  @ArchTest
  static final ArchRule noDirtiesContextOnMethods = noMethods()
      .should().beMetaAnnotatedWith("org.springframework.test.annotation.DirtiesContext");
}
