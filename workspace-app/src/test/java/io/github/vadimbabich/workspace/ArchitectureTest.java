package io.github.vadimbabich.workspace;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.github.vadimbabich.entitymetamodel.runtime.SqlExpr;
import io.github.vadimbabich.entitymetamodel.runtime.r2dbc.MetamodelQueryExecutor;
import jakarta.validation.ConstraintViolationException;
import org.reactivestreams.Publisher;
import org.springframework.data.annotation.Id;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@AnalyzeClasses(packages = "io.github.vadimbabich.workspace", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  private static final String WORKSPACE = "io.github.vadimbabich.workspace";
  private static final String CONFIG = WORKSPACE + ".config..";
  private static final String QUERY = WORKSPACE + ".query..";
  private static final String CONTRACT = WORKSPACE + ".contract..";
  private static final String DOMAIN = WORKSPACE + ".domain..";
  private static final String LIBRARY_RUNTIME = "io.github.vadimbabich.entitymetamodel..";

  @ArchTest
  static final ArchRule onlyConfigurationReferencesTheDatabaseClient = noClasses()
      .that().resideOutsideOfPackage(CONFIG)
      .should().dependOnClassesThat().areAssignableTo(DatabaseClient.class)
      .because("handlers run typed queries through the executor bean, never SQL strings");

  @ArchTest
  static final ArchRule onlyConfigurationConstructsTheQueryExecutor = noClasses()
      .that().resideOutsideOfPackage(CONFIG)
      .should().callConstructorWhere(target(owner(assignableTo(MetamodelQueryExecutor.class))))
      .because("the executor is one bean, built where its renderer and converter share a mapping context");

  @ArchTest
  static final ArchRule configurationDependsOnNoFeaturePackage = noClasses()
      .that().resideInAPackage(CONFIG)
      .should().dependOnClassesThat(featurePackages())
      .because("configuration wires feature beans through Spring and library types, so features never form a cycle"
          + " with it");

  @ArchTest
  static final ArchRule targetedWritesLiveInRepositoryFragments = noClasses()
      .that().haveSimpleNameNotEndingWith("UpdatesImpl")
      .should().dependOnClassesThat().areAssignableTo(R2dbcEntityTemplate.class)
      .because("a write that is not a whole-row save is a named repository method, with its property names typed");

  @ArchTest
  static final ArchRule noStringQueries = noMethods()
      .should().beAnnotatedWith(Query.class)
      .because("a query string escapes the metamodel");

  @ArchTest
  static final ArchRule rawSqlStaysInTheQueryPackage = noClasses()
      .that().resideOutsideOfPackage(QUERY)
      .should().callMethodWhere(callTo(SqlExpr.class, "raw"))
      .because("raw SQL fragments are reviewed in one place, and never assembled from request input");

  @ArchTest
  static final ArchRule theQueryPackageHoldsNoController = noClasses()
      .that().resideInAPackage(QUERY)
      .should().beMetaAnnotatedWith(RestController.class)
      .because("the package allowed raw SQL must not also receive requests")
      .allowEmptyShould(true);

  @ArchTest
  static final ArchRule packagesAreFreeOfCycles = slices()
      .matching(WORKSPACE + ".(*)..")
      .should().beFreeOfCycles();

  @ArchTest
  static final ArchRule noJdbcInApplicationCode = noClasses()
      .should().dependOnClassesThat().resideInAPackage("java.sql..")
      .because("the application is reactive end to end; Flyway's JDBC connection is configuration, not code");

  @ArchTest
  static final ArchRule theFrameworkSubscribes = noClasses()
      .should().callMethodWhere(callTo(Publisher.class, "subscribe|subscribeWith"))
      .because("a pipeline is composed and returned to WebFlux; a detached subscription cannot be cancelled");

  @ArchTest
  static final ArchRule onlyBlockingAdaptersChooseAScheduler = noClasses()
      .that().doNotHaveFullyQualifiedName(WORKSPACE + ".identity.NimbusSigningKeys")
      .should().dependOnClassesThat().areAssignableTo(Schedulers.class)
      .because("a scheduler is chosen once, where a blocking call leaves the event loop");

  @ArchTest
  static final ArchRule securityContextComesFromTheReactorContext = noClasses()
      .should().dependOnClassesThat().areAssignableTo(SecurityContextHolder.class)
      .because("a thread-local security context is empty on a reactive request path");

  @ArchTest
  static final ArchRule onlyServiceClassesAreTransactional = noClasses()
      .that().haveSimpleNameNotEndingWith("Service")
      .should().beMetaAnnotatedWith(Transactional.class)
      .because("a use case runs inside one application service boundary");

  @ArchTest
  static final ArchRule onlyServicesComposeTransactions = noClasses()
      .that().haveSimpleNameNotEndingWith("Service")
      .should().dependOnClassesThat().areAssignableTo(TransactionalOperator.class)
      .because("a use case runs inside one application service boundary");

  @ArchTest
  static final ArchRule controllersReachDataThroughServices = noClasses()
      .that().areMetaAnnotatedWith(RestController.class)
      .should().dependOnClassesThat().areAssignableTo(Repository.class)
      .because("a use case's statements run inside its service's transaction, not one by one from the controller");

  @ArchTest
  static final ArchRule onlyServiceMethodsAreTransactional = noMethods()
      .that().areDeclaredInClassesThat().haveSimpleNameNotEndingWith("Service")
      .should().beMetaAnnotatedWith(Transactional.class)
      .because("a use case runs inside one application service boundary");

  @ArchTest
  static final ArchRule transactionalMethodsDoNotStream = noMethods()
      .that().areMetaAnnotatedWith(Transactional.class)
      .or().areDeclaredInClassesThat().areMetaAnnotatedWith(Transactional.class)
      .should().haveRawReturnType(Flux.class)
      .because("a cancel from outside a live stream rolls the boundary's writes back")
      .allowEmptyShould(true);

  @ArchTest
  static final ArchRule entitiesLiveInTheDomainModule = classes()
      .that().areAnnotatedWith(Table.class)
      .should().resideInAPackage(DOMAIN)
      .because("the processor generates the metamodels of the domain module's entities");

  @ArchTest
  static final ArchRule entityFieldsAreFinal = fields()
      .that().areDeclaredInClassesThat().areAnnotatedWith(Table.class)
      .and().areNotStatic()
      .should().beFinal()
      .because("an entity is a value; a changed entity is a new instance");

  @ArchTest
  static final ArchRule entityFieldsNameTheirColumn = fields()
      .that().areDeclaredInClassesThat().areAnnotatedWith(Table.class)
      .and().areNotStatic()
      .should().beAnnotatedWith(Column.class)
      .because("the processor leaves a field without @Column out of the metamodel with only a note");

  @ArchTest
  static final ArchRule entitiesDeclareExactlyOneId = classes()
      .that().areAnnotatedWith(Table.class)
      .should(declareExactlyOneIdField());

  @ArchTest
  static final ArchRule entitiesHaveNoSetters = noMethods()
      .that().areDeclaredInClassesThat().areAnnotatedWith(Table.class)
      .should().haveNameStartingWith("set")
      .because("an entity is a value; a changed entity is a new instance");

  @ArchTest
  static final ArchRule noJakartaPersistence = noClasses()
      .should().dependOnClassesThat().resideInAPackage("jakarta.persistence..")
      .because("the processor reads Spring Data's mapping annotations only");

  @ArchTest
  static final ArchRule nothingIsValidatedThroughAProxy = noClasses()
      .should().beMetaAnnotatedWith(Validated.class)
      .because("a class-level @Validated turns a violated contract constraint into a 500 instead of a 400");

  @ArchTest
  static final ArchRule noCodeHandlesConstraintViolationsItself = noClasses()
      .should().dependOnClassesThat().areAssignableTo(ConstraintViolationException.class)
      .because("contract constraints are enforced by Spring's built-in method validation and answered by one handler");

  @ArchTest
  static final ArchRule oneHandlerAnswersEveryControllerError = classes()
      .that().areAssignableTo(ResponseEntityExceptionHandler.class)
      .and().doNotHaveFullyQualifiedName(ResponseEntityExceptionHandler.class.getName())
      .should().haveFullyQualifiedName(WORKSPACE + ".web.ApiExceptionHandler")
      .because("every error on the controller path is one problem format from one place");

  private static ArchCondition<JavaClass> declareExactlyOneIdField() {
    return new ArchCondition<>("declare exactly one @Id field") {
      @Override
      public void check(JavaClass entityType, ConditionEvents events) {
        int idFields = 0;

        for (JavaField declaredField : entityType.getFields()) {
          if (declaredField.isAnnotatedWith(Id.class)) {
            idFields++;
          }
        }

        if (idFields != 1) {
          String violation = entityType.getName() + " declares " + idFields + " @Id fields";
          events.add(SimpleConditionEvent.violated(entityType, violation));
        }
      }
    };
  }

  private static ArchCondition<JavaClass> declareNoMethods() {
    return new ArchCondition<>("declare no methods of their own") {
      @Override
      public void check(JavaClass repositoryType, ConditionEvents events) {
        if (!repositoryType.getMethods().isEmpty()) {
          String violation = repositoryType.getName() + " declares " + repositoryType.getMethods().size() + " methods";
          events.add(SimpleConditionEvent.violated(repositoryType, violation));
        }
      }
    };
  }

  private static DescribedPredicate<JavaClass> featurePackages() {
    DescribedPredicate<JavaClass> moduleOrConfiguration = resideInAnyPackage(CONFIG, CONTRACT, DOMAIN);

    return resideInAPackage(WORKSPACE + "..").and(not(moduleOrConfiguration)).as("reside in a feature package");
  }

  private static DescribedPredicate<JavaCall<?>> callTo(Class<?> ownerType, String methodNamePattern) {
    DescribedPredicate<JavaCall<?>> ownedByType = target(owner(assignableTo(ownerType)));
    DescribedPredicate<JavaCall<?>> namedLike = target(nameMatching(methodNamePattern));

    return ownedByType.and(namedLike).as("a call to %s.%s", ownerType.getSimpleName(), methodNamePattern);
  }
}
