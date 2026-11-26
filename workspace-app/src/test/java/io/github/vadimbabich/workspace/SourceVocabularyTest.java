package io.github.vadimbabich.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SourceVocabularyTest {

  private static final Path MAIN_SOURCES = Path.of("src/main/java");
  private static final Path CONTRACT = Path.of("../workspace-contract/src/main/openapi/workspace.yaml");
  private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
  private static final Path LOCAL_STACK = Path.of("../deploy/compose");

  @Test
  void noMainCodeStatementNamesACollation() throws IOException {
    List<Path> sourcesNamingACollation = new ArrayList<>();

    for (Path javaSource : mainJavaSources()) {
      if (lowerCaseTextOf(javaSource).contains("collate")) {
        sourcesNamingACollation.add(javaSource);
      }
    }

    assertThat(sourcesNamingACollation)
        .as("the name column's collation is the schema's; a statement that names one bypasses its index")
        .isEmpty();
  }

  @Test
  void noMigrationOrLocalStackFileCreatesAUser() throws IOException {
    List<Path> filesCreatingUsers = new ArrayList<>();

    for (Path sourceRoot : List.of(MIGRATIONS, LOCAL_STACK)) {
      for (Path scriptFile : filesUnder(sourceRoot, ".sql", ".sh")) {
        if (lowerCaseTextOf(scriptFile).contains("insert into app_user")) {
          filesCreatingUsers.add(scriptFile);
        }
      }
    }

    assertThat(filesCreatingUsers).as("a user row is created by the user's first request only").isEmpty();
  }

  @Test
  void theContractNeverSaysTenant() throws IOException {
    assertThat(lowerCaseTextOf(CONTRACT)).doesNotContain("tenant");
  }

  private static List<Path> mainJavaSources() throws IOException {
    return filesUnder(MAIN_SOURCES, ".java");
  }

  private static List<Path> filesUnder(Path sourceRoot, String... suffixes) throws IOException {
    List<Path> matchingFiles = new ArrayList<>();

    try (Stream<Path> paths = Files.walk(sourceRoot)) {
      for (Path path : paths.toList()) {
        if (endsWithAny(path, suffixes)) {
          matchingFiles.add(path);
        }
      }
    }

    assertThat(matchingFiles).as("files under %s", sourceRoot.toAbsolutePath()).isNotEmpty();
    return matchingFiles;
  }

  private static boolean endsWithAny(Path path, String... suffixes) {
    for (String suffix : suffixes) {
      if (path.toString().endsWith(suffix)) {
        return true;
      }
    }

    return false;
  }

  private static String lowerCaseTextOf(Path file) throws IOException {
    return Files.readString(file).toLowerCase(Locale.ROOT);
  }
}
