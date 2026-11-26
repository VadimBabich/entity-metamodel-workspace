package io.github.vadimbabich.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class ErrorMessagesTest {

  private static final Path DEFAULT_BUNDLE = Path.of("src/main/resources/messages.properties");

  @Test
  void noErrorMessageIsATemplateThatRequestDataCouldFill() throws IOException {
    Properties messages = defaultBundle();

    assertThat(messages).isNotEmpty();

    for (String key : messages.stringPropertyNames()) {
      assertThat(messages.getProperty(key))
          .as("message %s", key)
          .doesNotContain("{")
          .doesNotContain("$");
    }
  }

  @Test
  void everyConditionHasATitleAndADetail() throws IOException {
    Properties messages = defaultBundle();
    List<String> problemCodes =
        List.of("invalid-request", "unauthenticated", "identity-provider-unavailable", "item-not-found");

    for (String code : problemCodes) {
      assertThat(messages.getProperty("problem." + code + ".title")).as("title of %s", code).isNotBlank();
      assertThat(messages.getProperty("problem." + code + ".detail")).as("detail of %s", code).isNotBlank();
    }
  }

  private static Properties defaultBundle() throws IOException {
    Properties messages = new Properties();

    try (Reader bundle = Files.newBufferedReader(DEFAULT_BUNDLE, StandardCharsets.UTF_8)) {
      messages.load(bundle);
    }

    return messages;
  }
}
