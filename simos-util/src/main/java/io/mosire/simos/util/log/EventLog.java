package io.mosire.simos.util.log;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * ★★ <b>通用日志事件发射口</b>（2026-10-09 用户裁定：单纯 event 机制放 util）。
 *
 * <p>它只做四件事：把 {@code key/value} 拼成既有的 {@code k=v} 行、把 {@link LogEvent} 渲染成
 * {@code event=NAME ...}、按 {@link LogLevel} 判断/调用传入的 {@link Logger}、把 logger 包成
 * {@link LogChannel}。<b>它不知道任何模块、事件名和领域类型</b>；事件名与字段完全由调用方给。
 *
 * <p>★ 兼容口径：原 11 个 {@code XxxLog.kv(...)} 的实现逐字符收口到 {@link #kv(Object...)}；
 * 旧调用点不改也能保持完全相同的输出。新代码建议直接
 * {@code EventLog.channel(EconomyLog.migration()).info(LogEvent.of(...))}。
 *
 * <p>★ 纪律：日志只读、不改状态/公式；开关仍由各模块 logger 名 + {@code log4j2.xml} 控制，
 * 本类不读配置、不碰文件系统。
 */
public final class EventLog {

  private EventLog() {}

  /**
   * 结构化字段拼接：{@code kv("day", 3, "rows", 100)} ⇒ {@code day=3 rows=100}。
   *
   * <p>这是全仓唯一实现（原 11 个模块各一份的收口点）；奇数个参数是拼写错误 ⇒ 当场抛。
   * 键/值按 {@code String.valueOf} 原样打印，与旧实现逐字符一致。</p>
   */
  public static String kv(Object... keyValues) {
    Objects.requireNonNull(keyValues, "EventLog.kv 的 keyValues 不得为 null（无字段传空数组）");
    if (keyValues.length % 2 != 0) {
      throw new IllegalArgumentException("EventLog.kv 需要偶数个 key/value: " + keyValues.length);
    }
    Map<String, Object> fields = new LinkedHashMap<>();
    for (int index = 0; index < keyValues.length; index += 2) {
      fields.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
    }
    return LogEvent.renderFields(fields);
  }

  /** 构造事件；等价于 {@link LogEvent#of(String, Object...)}，方便调用方少 import 一个类型。 */
  public static LogEvent event(String name, Object... keyValues) {
    return LogEvent.of(name, keyValues);
  }

  /** 按级别发射一条事件；logger 未开启对应级别 ⇒ 不渲染、不分配字符串。 */
  public static void emit(Logger logger, LogLevel level, LogEvent event) {
    Objects.requireNonNull(logger, "EventLog.emit 的 logger 不得为 null");
    Objects.requireNonNull(level, "EventLog.emit 的 level 不得为 null");
    Objects.requireNonNull(event, "EventLog.emit 的 event 不得为 null");
    switch (level) {
      case TRACE -> {
        if (logger.isTraceEnabled()) {
          logger.trace(event.toLine());
        }
      }
      case DEBUG -> {
        if (logger.isDebugEnabled()) {
          logger.debug(event.toLine());
        }
      }
      case INFO -> {
        if (logger.isInfoEnabled()) {
          logger.info(event.toLine());
        }
      }
      case WARN -> {
        if (logger.isWarnEnabled()) {
          logger.warn(event.toLine());
        }
      }
      case ERROR -> {
        if (logger.isErrorEnabled()) {
          logger.error(event.toLine());
        }
      }
    }
  }

  /** 便捷重载：先用 {@link #event(String, Object...)} 构造，再 {@link #emit(Logger, LogLevel, LogEvent)}。 */
  public static void emit(Logger logger, LogLevel level, String name, Object... keyValues) {
    emit(logger, level, LogEvent.of(name, keyValues));
  }

  /** 把 logger 包成带 {@link LogEvent} 重载的薄 channel（模块门面只需提供 logger）。 */
  public static LogChannel channel(Logger logger) {
    return LogChannel.of(logger);
  }
}
