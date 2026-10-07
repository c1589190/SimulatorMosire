package io.mosire.simos.app.testing;

import io.mosire.simos.app.AppLog;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.AbstractConfiguration;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

/**
 * ★ Z6a 日志采集夹具（形制照 {@code logging/GovLoggingTest} 的 CollectingAppender）：把某 logger 的 {@code
 * level|message} 收进内存，供"具名事件 + 逐值字段"的行为断言用。
 *
 * <p>★ 只服务测试：不写状态、不碰业务。close 时还原原级别并摘掉 appender。
 */
public final class AppLogCapture implements AutoCloseable {

  private final LoggerContext context;
  private final AbstractConfiguration configuration;
  private final String loggerName;
  private final Level originalLevel;
  private final Collecting appender;
  private final String appenderName;

  private AppLogCapture(String loggerName, Level level) {
    this.loggerName = loggerName;
    this.context = (LoggerContext) LogManager.getContext(false);
    this.configuration = (AbstractConfiguration) context.getConfiguration();
    LoggerConfig config = configuration.getLoggerConfig(loggerName);
    this.originalLevel = config.getLevel();
    this.appenderName = "z6a-capture-" + Integer.toHexString(System.identityHashCode(this));
    this.appender = new Collecting(appenderName);
    appender.start();
    configuration.addAppender(appender);
    Configurator.setLevel(loggerName, level);
    attach();
    context.updateLoggers();
  }

  /** 采集 {@link AppLog#TIME_LOGGER_NAME}（组合根时间参与者：daily-loop 事件都在这里）。 */
  public static AppLogCapture appTime() {
    return new AppLogCapture(AppLog.TIME_LOGGER_NAME, Level.INFO);
  }

  public static AppLogCapture logger(String name) {
    return new AppLogCapture(name, Level.INFO);
  }

  /** 采集某 logger 并把它抬到指定级别（DEBUG 用例要读逐笔缺口 reason）。 */
  public static AppLogCapture logger(String name, Level level) {
    return new AppLogCapture(name, level);
  }

  public List<String> messages() {
    return appender.messages();
  }

  public void clear() {
    appender.clear();
  }

  /** 至少一条含全部片段的 INFO 行。 */
  public boolean hasInfo(String event, String... fragments) {
    return has("INFO", event, fragments);
  }

  /** 至少一条含全部片段的 ERROR 行（契约故障不降级）。 */
  public boolean hasError(String event, String... fragments) {
    return has("ERROR", event, fragments);
  }

  /** 至少一条指定级别 + event + 全部片段的日志行。 */
  public boolean has(String level, String event, String... fragments) {
    return messages().stream()
        .anyMatch(
            line -> {
              if (!line.startsWith(level + "|") || !line.contains("event=" + event)) {
                return false;
              }
              for (String fragment : fragments) {
                if (!line.contains(fragment)) {
                  return false;
                }
              }
              return true;
            });
  }

  @Override
  public void close() {
    Configurator.setLevel(loggerName, originalLevel);
    context.updateLoggers();
    configuration.getLoggerConfig(loggerName).removeAppender(appenderName);
    configuration.removeAppender(appenderName);
    context.updateLoggers();
    appender.stop();
  }

  private void attach() {
    LoggerConfig current = configuration.getLoggerConfig(loggerName);
    if (!current.getAppenders().containsKey(appenderName)) {
      current.addAppender(appender, null, null);
    }
  }

  private static final class Collecting extends AbstractAppender {

    private final List<String> messages = new ArrayList<>();

    private Collecting(String name) {
      super(name, null, null, true, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
      messages.add(event.getLevel() + "|" + event.getMessage().getFormattedMessage());
    }

    private List<String> messages() {
      return List.copyOf(messages);
    }

    private void clear() {
      messages.clear();
    }
  }
}
