package io.github.vadimbabich.workspace.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The images of the local stack's services, read from compose.yaml, so the tests run the image Dependabot keeps
 * current there. Paths resolve from the module directory, the working directory of Surefire, Failsafe and PIT.
 */
public final class ComposeImages {

  private static final Path COMPOSE_FILE = Path.of("../deploy/compose/compose.yaml");
  private static final String IMAGE_KEY = "image:";

  private ComposeImages() {
  }

  public static String imageOf(String service) {
    String serviceKey = "  " + service + ":";
    boolean insideService = false;

    for (String line : composeLines()) {
      if (line.equals(serviceKey)) {
        insideService = true;
        continue;
      }

      if (insideService && isNextService(line)) {
        break;
      }

      String trimmedLine = line.trim();
      if (insideService && trimmedLine.startsWith(IMAGE_KEY)) {
        return trimmedLine.substring(IMAGE_KEY.length()).trim();
      }
    }

    throw new IllegalStateException("no image for the service '" + service + "' in " + COMPOSE_FILE.toAbsolutePath());
  }

  private static List<String> composeLines() {
    try {
      return Files.readAllLines(COMPOSE_FILE);
    } catch (IOException unreadable) {
      throw new UncheckedIOException("cannot read " + COMPOSE_FILE.toAbsolutePath(), unreadable);
    }
  }

  private static boolean isNextService(String line) {
    boolean indentedByTwo = line.startsWith("  ") && !line.startsWith("   ");

    return indentedByTwo && line.trim().endsWith(":");
  }
}
