package io.github.vadimbabich.workspace.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Records the previews the repository fragments log for their typed queries while it is open, each split into the
 * statement and its binding lines.
 */
public final class QueryPreviewLog implements AutoCloseable {

  private static final String APPLICATION_LOGGER = "io.github.vadimbabich.workspace";
  private static final String FRAGMENT_LOGGER_SUFFIX = "QueriesImpl";
  private static final String LABEL_SEPARATOR = ": ";
  private static final String STATEMENT_START = LABEL_SEPARATOR + "SELECT ";
  private static final Pattern MARKER = Pattern.compile("\\$\\d+");
  private static final Pattern REDACTED_BINDING = Pattern.compile(" {2}" + MARKER.pattern() + " = <redacted>");

  private final LogCapture previews;

  private QueryPreviewLog(LogCapture previews) {
    this.previews = previews;
  }

  public static QueryPreviewLog open() {
    return new QueryPreviewLog(LogCapture.open(APPLICATION_LOGGER, Level.DEBUG));
  }

  public List<QueryPreview> previews() {
    List<QueryPreview> recordedPreviews = new ArrayList<>();

    for (ILoggingEvent event : previews.events()) {
      if (event.getLoggerName().endsWith(FRAGMENT_LOGGER_SUFFIX)) {
        recordedPreviews.add(QueryPreview.parse(event.getFormattedMessage()));
      }
    }

    return List.copyOf(recordedPreviews);
  }

  @Override
  public void close() {
    previews.close();
  }

  public record QueryPreview(String statement, List<String> bindingLines) {

    static QueryPreview parse(String message) {
      List<String> lines = message.lines().toList();
      String header = lines.getFirst();
      int statementStart = header.indexOf(STATEMENT_START);

      if (statementStart < 0) {
        throw new AssertionError("no SELECT statement in the preview: " + message);
      }

      String statement = header.substring(statementStart + LABEL_SEPARATOR.length());

      return new QueryPreview(statement, List.copyOf(lines.subList(1, lines.size())));
    }

    public List<String> bindingLinesShowingAValue() {
      List<String> linesShowingAValue = new ArrayList<>();

      for (String bindingLine : bindingLines) {
        if (!REDACTED_BINDING.matcher(bindingLine).matches()) {
          linesShowingAValue.add(bindingLine);
        }
      }

      return List.copyOf(linesShowingAValue);
    }

    public int markerCount() {
      Set<String> markers = new HashSet<>();
      Matcher marker = MARKER.matcher(statement);

      while (marker.find()) {
        markers.add(marker.group());
      }

      return markers.size();
    }
  }
}
