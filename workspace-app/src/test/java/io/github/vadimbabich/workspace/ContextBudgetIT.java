package io.github.vadimbabich.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.github.vadimbabich.workspace.support.WorkspaceIntegrationTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.cache.ContextCache;
import org.springframework.test.context.cache.ContextCacheUtils;
import org.springframework.test.context.cache.DefaultCacheAwareContextLoaderDelegate;

/**
 * Fails the build when the integration tests of this JVM load more Spring contexts than the committed budget. Each
 * context starts its own PostgreSQL container, so an unnoticed configuration is the costliest test mistake.
 */
@WorkspaceIntegrationTest
@Order(Integer.MAX_VALUE)
class ContextBudgetIT {

  private static final String BUDGET_PROPERTY = "it.context-budget";

  @Test
  void theIntegrationTestsStayWithinTheContextBudget() throws IOException {
    ContextCache contextCache = SharedContextCache.instance();
    int budget = committedBudget();
    int maxSize = ContextCacheUtils.retrieveMaxCacheSize();

    writeStatistics(contextCache, maxSize, budget);

    assertThat(contextCache.getMissCount())
        .as("contexts loaded in this JVM, against %s; a new test configuration raises %s in the same commit",
            BUDGET_PROPERTY, BUDGET_PROPERTY)
        .isLessThanOrEqualTo(budget);

    assertThat(contextCache.size())
        .as("contexts held in the cache; at its maximum, evictions reload contexts and the count above is wrong")
        .isLessThan(maxSize);
  }

  private static int committedBudget() {
    String configuredBudget = System.getProperty(BUDGET_PROPERTY);

    if (configuredBudget == null) {
      return fail("the system property %s is not set; Failsafe's configuration passes it", BUDGET_PROPERTY);
    }

    try {
      return Integer.parseInt(configuredBudget.trim());
    } catch (NumberFormatException notANumber) {
      return fail("the system property %s is not a number: '%s'", BUDGET_PROPERTY, configuredBudget);
    }
  }

  private static void writeStatistics(ContextCache contextCache, int maxSize, int budget) throws IOException {
    Path statisticsFile = Path.of("target", "context-cache.txt");

    String statistics = "size=" + contextCache.size()
        + " maxSize=" + maxSize
        + " missCount=" + contextCache.getMissCount()
        + " budget=" + budget
        + System.lineSeparator();

    Files.writeString(statisticsFile, statistics);
  }

  // The test framework's context cache has no public accessor; this subclass reaches the shared one through the
  // protected getter, without reflection.
  private static final class SharedContextCache extends DefaultCacheAwareContextLoaderDelegate {

    static ContextCache instance() {
      return new SharedContextCache().getContextCache();
    }
  }
}
