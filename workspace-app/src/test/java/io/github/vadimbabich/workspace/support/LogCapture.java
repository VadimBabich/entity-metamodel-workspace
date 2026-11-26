package io.github.vadimbabich.workspace.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

public final class LogCapture implements AutoCloseable {

  private final Logger capturedLogger;
  private final ListAppender<ILoggingEvent> recorder;
  private final Level previousLevel;
  private final boolean previouslyAdditive;

  private LogCapture(Logger capturedLogger, ListAppender<ILoggingEvent> recorder) {
    this.capturedLogger = capturedLogger;
    this.recorder = recorder;
    this.previousLevel = capturedLogger.getLevel();
    this.previouslyAdditive = capturedLogger.isAdditive();
  }

  public static LogCapture open(String loggerName, Level level) {
    Logger capturedLogger = (Logger) LoggerFactory.getLogger(loggerName);
    ListAppender<ILoggingEvent> recorder = new ListAppender<>();
    LogCapture capture = new LogCapture(capturedLogger, recorder);

    recorder.start();
    capturedLogger.addAppender(recorder);
    capturedLogger.setLevel(level);
    capturedLogger.setAdditive(false);

    return capture;
  }

  public List<ILoggingEvent> events() {
    synchronized (recorder) {
      return List.copyOf(recorder.list);
    }
  }

  @Override
  public void close() {
    capturedLogger.setAdditive(previouslyAdditive);
    capturedLogger.setLevel(previousLevel);
    capturedLogger.detachAppender(recorder);
    recorder.stop();
  }
}
