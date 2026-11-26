package io.github.vadimbabich.workspace.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Records the SQL statements the PostgreSQL R2DBC driver sends while it is open, so a test can assert how many
 * statements a request costs. Open it around the request only: set-up and seeding run statements too.
 */
public final class StatementLog implements AutoCloseable {

  private static final String DRIVER_QUERY_LOGGER = "io.r2dbc.postgresql.QUERY";

  private final LogCapture driverQueries;

  private StatementLog(LogCapture driverQueries) {
    this.driverQueries = driverQueries;
  }

  public static StatementLog open() {
    return new StatementLog(LogCapture.open(DRIVER_QUERY_LOGGER, Level.DEBUG));
  }

  /**
   * The recorded statements that name the given table. Fails when nothing at all was recorded, because a silent
   * driver (a changed logger name or level) would otherwise pass for a request that ran no statement.
   */
  public List<String> statementsOn(String table) {
    List<String> recordedStatements = statements();

    Pattern tableName = Pattern.compile("\\b" + Pattern.quote(table.toLowerCase(Locale.ROOT)) + "\\b");
    List<String> statementsOnTable = new ArrayList<>();

    for (String statement : recordedStatements) {
      if (tableName.matcher(statement.toLowerCase(Locale.ROOT)).find()) {
        statementsOnTable.add(statement);
      }
    }

    return List.copyOf(statementsOnTable);
  }

  public List<String> statements() {
    List<String> recordedStatements = recordedStatements();

    if (recordedStatements.isEmpty()) {
      throw new AssertionError("no statement was logged by " + DRIVER_QUERY_LOGGER);
    }

    return List.copyOf(recordedStatements);
  }

  public List<String> onlyTransactionBegunBy(String begin) {
    List<String> recordedStatements = statements();
    int beginnings = 0;

    for (String statement : recordedStatements) {
      if (statement.endsWith(begin)) {
        beginnings++;
      }
    }

    if (beginnings != 1) {
      throw new AssertionError("expected one " + begin + ", found " + beginnings + " in " + recordedStatements);
    }

    return firstTransactionBegunBy(begin);
  }

  public List<String> firstTransactionBegunBy(String begin) {
    List<String> recordedStatements = statements();
    int beginning = indexOfFirstEnding(recordedStatements, begin, 0);
    int commit = indexOfFirstEnding(recordedStatements, "COMMIT", beginning);

    return List.copyOf(recordedStatements.subList(beginning + 1, commit));
  }

  private static int indexOfFirstEnding(List<String> statements, String ending, int from) {
    for (int index = from; index < statements.size(); index++) {
      if (statements.get(index).endsWith(ending)) {
        return index;
      }
    }

    throw new AssertionError("no statement ends with " + ending + " in " + statements);
  }

  @Override
  public void close() {
    driverQueries.close();
  }

  private List<String> recordedStatements() {
    List<String> statements = new ArrayList<>();

    for (ILoggingEvent event : driverQueries.events()) {
      statements.add(event.getFormattedMessage());
    }

    return statements;
  }
}
