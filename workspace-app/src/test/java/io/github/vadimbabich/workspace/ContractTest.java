package io.github.vadimbabich.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ContractTest {

  private static final Path CONTRACT = Path.of("../workspace-contract/src/main/openapi/workspace.yaml");
  private static final long LARGEST_EXACT_JSON_INTEGER = 9007199254740991L;
  private static final Set<String> HTTP_METHODS =
      Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");
  private static final Pattern LOWER_CAMEL_CASE = Pattern.compile("[a-z][a-zA-Z0-9]*");
  private static final Pattern UPPER_SNAKE_CASE = Pattern.compile("[A-Z][A-Z0-9]*(_[A-Z0-9]+)*");
  private static final Pattern KEBAB_CASE_SEGMENT = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
  private static final Pattern IDENTIFIER_SEGMENT = Pattern.compile("\\{[a-z][a-zA-Z0-9]*Id}");
  private static final String SHARED_RESPONSE = "#/components/responses/";
  private static final String SHARED_SCHEMA = "#/components/schemas/";
  private static final String SHARED_PARAMETER = "#/components/parameters/";
  private static final int MAXIMUM_NESTING = 3;
  private static final String API_ID = "55c74d5c-1319-4efe-91fa-ecdd7d7159ba";

  private static final Map<?, ?> CONTRACT_DOCUMENT = contractDocument();

  @Test
  void theFirstCutOnlyReadsFoldersAndTheirChildren() {
    List<String> operations = new ArrayList<>();

    for (Operation operation : operations()) {
      operations.add(operation.method() + " " + operation.path());
    }

    assertThat(operations).containsExactlyInAnyOrder(
        "get /api/v1/top-level-folders",
        "get /api/v1/folders/{folderId}/children");
  }

  @Test
  void theApiIsIdentifiedAndNamesWhereToAskAboutIt() {
    Map<?, ?> info = mapOf(CONTRACT_DOCUMENT.get("info"));
    Map<?, ?> contact = mapOf(info.get("contact"));

    assertThat(textOf(info.get("x-api-id"))).as("the API identifier never changes").isEqualTo(API_ID);

    assertThat(textOf(contact.get("url"))).startsWith("https://");
    assertThat(contact.containsKey("email")).as("no personal address in the contract").isFalse();
  }

  @Test
  void everyOperationIsNamedSummarizedDescribedAndTaggedOnce() {
    Set<String> declaredTags = declaredTagsWithDescriptions();
    Set<String> operationIds = new HashSet<>();

    for (Operation operation : operations()) {
      Map<?, ?> definition = operation.definition();
      String operationId = textOf(definition.get("operationId"));
      List<?> tags = listOf(definition.get("tags"));

      assertThat(operationId).matches(LOWER_CAMEL_CASE);
      assertThat(operationIds.add(operationId)).as("unique operationId %s", operationId).isTrue();
      assertThat(textOf(definition.get("summary"))).as("%s summary", operationId).isNotBlank();
      assertThat(textOf(definition.get("description"))).as("%s description", operationId).isNotBlank();

      assertThat(tags).as("%s tags", operationId).hasSize(1);
      assertThat(declaredTags).as("%s tag is declared", operationId).contains(textOf(tags.getFirst()));
    }
  }

  @Test
  void everyOperationListsItsSuccessFirstThenSharedErrorsAndADefault() {
    for (Operation operation : operations()) {
      List<String> statuses = new ArrayList<>();
      Map<?, ?> responses = mapOf(operation.definition().get("responses"));

      for (Object status : responses.keySet()) {
        statuses.add(String.valueOf(status));
      }

      assertThat(statuses.getFirst()).as("first response of %s", operation.path()).startsWith("2");
      assertThat(statuses).filteredOn(status -> status.startsWith("2")).hasSize(1);
      assertThat(statuses).contains("default");

      for (String status : statuses.subList(1, statuses.size())) {
        Map<?, ?> errorResponse = mapOf(responses.get(status));
        String reference = textOf(errorResponse.get("$ref"));

        assertThat(reference).as("%s of %s", status, operation.path()).startsWith(SHARED_RESPONSE);
      }
    }
  }

  @Test
  void sharedErrorResponsesAreDescriptionOnly() {
    Map<?, ?> sharedResponses = mapOf(mapOf(CONTRACT_DOCUMENT.get("components")).get("responses"));

    for (Map.Entry<?, ?> sharedResponse : sharedResponses.entrySet()) {
      Map<?, ?> definition = mapOf(sharedResponse.getValue());

      assertThat(textOf(definition.get("description"))).as("%s", sharedResponse.getKey()).isNotBlank();
      assertThat(definition.containsKey("content")).as("%s declares no content", sharedResponse.getKey()).isFalse();
    }
  }

  @Test
  void pathsAreKebabCaseWithIdentifiersAsSegmentsAndShallowNesting() {
    for (Object path : mapOf(CONTRACT_DOCUMENT.get("paths")).keySet()) {
      String pathText = String.valueOf(path);
      int identifierSegments = 0;

      assertThat(pathText).as(pathText).startsWith("/").doesNotEndWith("/");

      for (String segment : pathText.substring(1).split("/")) {
        if (IDENTIFIER_SEGMENT.matcher(segment).matches()) {
          identifierSegments++;
          continue;
        }

        assertThat(segment).as("segment of %s", pathText).matches(KEBAB_CASE_SEGMENT);
      }

      assertThat(identifierSegments).as("nesting of %s", pathText).isLessThanOrEqualTo(MAXIMUM_NESTING);
    }
  }

  @Test
  void pathParametersAreIdentifiersNeverNamesOrPaths() {
    for (Operation operation : operations()) {
      for (Map<?, ?> parameter : parametersOf(operation)) {
        if (!"path".equals(parameter.get("in"))) {
          continue;
        }

        Map<?, ?> schema = resolved(mapOf(parameter.get("schema")));

        assertThat(schema.get("type")).as("path parameter %s", parameter.get("name")).isEqualTo("integer");
      }
    }
  }

  @Test
  void everyIdentifierIsAPositiveIntegerThatJsonCarriesExactly() {
    List<Map<?, ?>> identifierSchemas = new ArrayList<>();

    for (Operation operation : operations()) {
      for (Map<?, ?> parameter : parametersOf(operation)) {
        if (isIdentifierName(textOf(parameter.get("name")))) {
          identifierSchemas.add(resolved(mapOf(parameter.get("schema"))));
        }
      }
    }

    identifierSchemas.addAll(propertiesNamed(ContractTest::isIdentifierName));

    assertThat(identifierSchemas).isNotEmpty().allSatisfy(schema -> {
      assertThat(schema.get("type")).isEqualTo("integer");
      assertThat(schema.get("format")).isEqualTo("int64");
      assertThat(schema.get("minimum")).isEqualTo(1);
      assertThat(schema.get("maximum")).isEqualTo(LARGEST_EXACT_JSON_INTEGER);
    });
  }

  @Test
  void everyEnumConstantIsUpperSnakeCase() {
    for (Map.Entry<?, ?> schema : schemas().entrySet()) {
      Map<?, ?> definition = mapOf(schema.getValue());

      if (definition.containsKey("enum")) {
        assertThat(listOf(definition.get("enum"))).as("%s", schema.getKey())
            .allSatisfy(constant -> assertThat(String.valueOf(constant)).matches(UPPER_SNAKE_CASE));
      }
    }
  }

  @Test
  void everyIntegerPropertyHasAFormat() {
    for (Map<?, ?> property : propertiesNamed(_ -> true)) {
      if ("integer".equals(property.get("type"))) {
        assertThat(property.get("format")).as("integer property %s", property).isNotNull();
      }
    }
  }

  private static List<Operation> operations() {
    List<Operation> operations = new ArrayList<>();

    for (Map.Entry<?, ?> path : mapOf(CONTRACT_DOCUMENT.get("paths")).entrySet()) {
      for (Map.Entry<?, ?> pathItem : mapOf(path.getValue()).entrySet()) {
        String key = String.valueOf(pathItem.getKey());

        if (HTTP_METHODS.contains(key)) {
          operations.add(new Operation(key, String.valueOf(path.getKey()), mapOf(pathItem.getValue())));
        }
      }
    }

    return operations;
  }

  private static List<Map<?, ?>> parametersOf(Operation operation) {
    List<Map<?, ?>> parameters = new ArrayList<>();

    if (!operation.definition().containsKey("parameters")) {
      return parameters;
    }

    for (Object parameter : listOf(operation.definition().get("parameters"))) {
      parameters.add(resolvedParameter(mapOf(parameter)));
    }

    return parameters;
  }

  private static Map<?, ?> schemas() {
    return mapOf(mapOf(CONTRACT_DOCUMENT.get("components")).get("schemas"));
  }

  private static List<Map<?, ?>> propertiesNamed(Predicate<String> nameMatches) {
    List<Map<?, ?>> matchingProperties = new ArrayList<>();

    for (Object schema : schemas().values()) {
      Map<?, ?> definition = mapOf(schema);

      if (!definition.containsKey("properties")) {
        continue;
      }

      for (Map.Entry<?, ?> property : mapOf(definition.get("properties")).entrySet()) {
        if (nameMatches.test(String.valueOf(property.getKey()))) {
          matchingProperties.add(resolved(mapOf(property.getValue())));
        }
      }
    }

    return matchingProperties;
  }

  private static Set<String> declaredTagsWithDescriptions() {
    Set<String> declaredTags = new HashSet<>();

    for (Object tag : listOf(CONTRACT_DOCUMENT.get("tags"))) {
      Map<?, ?> tagDefinition = mapOf(tag);

      assertThat(textOf(tagDefinition.get("description"))).as("tag %s", tagDefinition.get("name")).isNotBlank();
      declaredTags.add(textOf(tagDefinition.get("name")));
    }

    return declaredTags;
  }

  private static Map<?, ?> resolvedParameter(Map<?, ?> parameter) {
    if (!parameter.containsKey("$ref")) {
      return parameter;
    }

    String reference = textOf(parameter.get("$ref"));
    Map<?, ?> sharedParameters = mapOf(mapOf(CONTRACT_DOCUMENT.get("components")).get("parameters"));

    assertThat(reference).startsWith(SHARED_PARAMETER);
    return mapOf(sharedParameters.get(reference.substring(SHARED_PARAMETER.length())));
  }

  private static Map<?, ?> resolved(Map<?, ?> schema) {
    if (!schema.containsKey("$ref")) {
      return schema;
    }

    String reference = textOf(schema.get("$ref"));

    assertThat(reference).startsWith(SHARED_SCHEMA);
    return mapOf(schemas().get(reference.substring(SHARED_SCHEMA.length())));
  }

  private static boolean isIdentifierName(String name) {
    return name.equals("id") || name.endsWith("Id");
  }

  private static Map<?, ?> mapOf(Object node) {
    assertThat(node).isInstanceOf(Map.class);
    return (Map<?, ?>) node;
  }

  private static List<?> listOf(Object node) {
    assertThat(node).isInstanceOf(List.class);
    return (List<?>) node;
  }

  private static Map<?, ?> contractDocument() {
    try {
      return mapOf(new Yaml().load(Files.readString(CONTRACT)));
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
  }

  private static String textOf(Object node) {
    assertThat(node).isInstanceOf(String.class);
    return (String) node;
  }

  private record Operation(String method, String path, Map<?, ?> definition) {
  }
}
