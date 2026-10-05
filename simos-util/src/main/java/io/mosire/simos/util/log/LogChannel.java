package io.mosire.simos.util.log;

import java.util.Objects;
import org.slf4j.Logger;

/**
 * ★★ <b>logger 的薄 channel</b>（2026-10-09 用户裁定：通用 event 机制放 util）：只把
 * {@link LogEvent} 按级别转给 {@link EventLog#emit}，不持有任何领域状态、事件名或配置。
 *
 * <p>模块门面继续只负责 logger 命名空间（如 {@code EconomyLog.migration()}），需要新式结构化日志的调用点
 * 可以：
 *
 * <pre>{@code
 * LogChannel channel = EventLog.channel(EconomyLog.migration());
 * channel.info(LogEvent.of("MIGRATION_PLAN", "day", day, "moves", moves));
 * }</pre>
 *
 * <p>旧式 {@code LOG.info("event=... " + kv(...))} 调用点保持原样即可。
 */
public final class LogChannel {

  private final Logger logger;

  private LogChannel(Logger logger) {
    this.logger = logger;
  }

  /** 包一个 logger；不得为 null。 */
  public static LogChannel of(Logger logger) {
    Objects.requireNonNull(logger, "LogChannel.of 的 logger 不得为 null");
    return new LogChannel(logger);
  }

  /** 返回底层 logger（只读转发；调用方仍应优先用本类的结构化口）。 */
  public Logger logger() {
    return logger;
  }

  public boolean isTraceEnabled() {
    return logger.isTraceEnabled();
  }

  public boolean isDebugEnabled() {
    return logger.isDebugEnabled();
  }

  public boolean isInfoEnabled() {
    return logger.isInfoEnabled();
  }

  public boolean isWarnEnabled() {
    return logger.isWarnEnabled();
  }

  public boolean isErrorEnabled() {
    return logger.isErrorEnabled();
  }

  public void trace(LogEvent event) {
    EventLog.emit(logger, LogLevel.TRACE, event);
  }

  public void debug(LogEvent event) {
    EventLog.emit(logger, LogLevel.DEBUG, event);
  }

  public void info(LogEvent event) {
    EventLog.emit(logger, LogLevel.INFO, event);
  }

  public void warn(LogEvent event) {
    EventLog.emit(logger, LogLevel.WARN, event);
  }

  public void error(LogEvent event) {
    EventLog.emit(logger, LogLevel.ERROR, event);
  }

  public void trace(String eventName, Object... keyValues) {
    EventLog.emit(logger, LogLevel.TRACE, eventName, keyValues);
  }

  public void debug(String eventName, Object... keyValues) {
    EventLog.emit(logger, LogLevel.DEBUG, eventName, keyValues);
  }

  public void info(String eventName, Object... keyValues) {
    EventLog.emit(logger, LogLevel.INFO, eventName, keyValues);
  }

  public void warn(String eventName, Object... keyValues) {
    EventLog.emit(logger, LogLevel.WARN, eventName, keyValues);
  }

  public void error(String eventName, Object... keyValues) {
    EventLog.emit(logger, LogLevel.ERROR, eventName, keyValues);
  }
}
