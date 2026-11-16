package io.github.vadimbabich.workspace.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;

/**
 * Records the SQL statements the PostgreSQL R2DBC driver sends while it is open, so a test can assert how many
 * statements a request costs. Open it around the request only: set-up and seeding run statements too.
 */
public final class StatementLog implements AutoCloseable {

  private static final String DRIVER_QUERY_LOGGER = "io.r2dbc.postgresql.QUERY";

  private final Logger queryLogger;
  private final ListAppender<ILoggingEvent> recorder;
  private final Level previousLevel;
  private final boolean previouslyAdditive;

  private StatementLog(Logger queryLogger, ListAppender<ILoggingEvent> recorder) {
    this.queryLogger = queryLogger;
    this.recorder = recorder;
    this.previousLevel = queryLogger.getLevel();
    this.previouslyAdditive = queryLogger.isAdditive();
  }

  public static StatementLog open() {
    Logger queryLogger = (Logger) LoggerFactory.getLogger(DRIVER_QUERY_LOGGER);
    ListAppender<ILoggingEvent> recorder = new ListAppender<>();
    StatementLog statementLog = new StatementLog(queryLogger, recorder);

    recorder.start();
    queryLogger.addAppender(recorder);
    queryLogger.setLevel(Level.DEBUG);
    queryLogger.setAdditive(false);

    return statementLog;
  }

  /**
   * The recorded statements that name the given table. Fails when nothing at all was recorded, because a silent
   * driver (a changed logger name or level) would otherwise pass for a request that ran no statement.
   */
  public List<String> statementsOn(String table) {
    List<String> recordedStatements = recordedStatements();

    if (recordedStatements.isEmpty()) {
      throw new AssertionError("no statement was logged by " + DRIVER_QUERY_LOGGER);
    }

    Pattern tableName = Pattern.compile("\\b" + Pattern.quote(table.toLowerCase(Locale.ROOT)) + "\\b");
    List<String> statementsOnTable = new ArrayList<>();

    for (String statement : recordedStatements) {
      if (tableName.matcher(statement.toLowerCase(Locale.ROOT)).find()) {
        statementsOnTable.add(statement);
      }
    }

    return List.copyOf(statementsOnTable);
  }

  @Override
  public void close() {
    queryLogger.setAdditive(previouslyAdditive);
    queryLogger.setLevel(previousLevel);
    queryLogger.detachAppender(recorder);
    recorder.stop();
  }

  // The driver logs from its event-loop threads; the appender's own lock orders those writes before this read.
  private List<String> recordedStatements() {
    List<String> statements = new ArrayList<>();

    synchronized (recorder) {
      for (ILoggingEvent event : recorder.list) {
        statements.add(event.getFormattedMessage());
      }
    }

    return statements;
  }
}
