package io.mosire.simos.util.log;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>通用结构化日志事件</b>（2026-10-09 用户裁定：单纯 event 完全应该放在 util）。
 *
 * <p>它只知道"事件名 + 保序字段表"，<b>不认识任何领域类型、事件词表或模块</b>： {@code MIGRATION_PLAN} / {@code POPULATION_SETTLE}
 * / {@code TAX_COLLECTED} 这些名字以及每个字段 都由产生事件的那一行代码传入。领域事件（如 {@code
 * HouseholdPopulationEvent}）仍是各域的状态类型， 不搬进 util；本类型只做日志信封。
 *
 * <p>行格式与既有 {@code EconomyLog}/{@code SocialLog} 的 {@code kv} 行完全一致：
 *
 * <pre>
 * event=MIGRATION_PLAN day=120 moves=7 rowsBefore=138
 * </pre>
 *
 * <p>★ 与 {@link io.mosire.simos.util.time.Event} 区分：那个是"时刻 + 值 + ADD/SET"的时态值类型， 不是日志事件；两者不合并。
 *
 * @param name 非空白的事件名（线格式里 {@code event=} 后面的规范串）
 * @param fields 保序不可变字段表；键非 null/空白，值可为 null（按既有 {@code kv} 原样打印）
 */
public record LogEvent(String name, Map<String, Object> fields) {

  public LogEvent {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("LogEvent.name 不得为 null/空白");
    }
    Objects.requireNonNull(fields, "LogEvent.fields 不得为 null（无字段用空 Map）");
    LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : fields.entrySet()) {
      String key = entry.getKey();
      if (key == null || key.isBlank()) {
        throw new IllegalArgumentException("LogEvent.fields 的键不得为 null/空白: " + entry);
      }
      copy.put(key, entry.getValue());
    }
    fields = Collections.unmodifiableMap(copy);
  }

  /**
   * 便捷构造：{@code of("MIGRATION_PLAN", "day", 120, "moves", 7)}。
   *
   * @param name 非空白事件名
   * @param keyValues 偶数个 key/value；键必须是非空文本，值可为任意对象（含 null）
   */
  public static LogEvent of(String name, Object... keyValues) {
    Objects.requireNonNull(keyValues, "LogEvent.of 的 keyValues 不得为 null（无字段传空数组）");
    if (keyValues.length % 2 != 0) {
      throw new IllegalArgumentException("LogEvent.of 需要偶数个 key/value: " + keyValues.length);
    }
    LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
    for (int index = 0; index < keyValues.length; index += 2) {
      Object rawKey = keyValues[index];
      if (rawKey == null || rawKey.toString().isBlank()) {
        throw new IllegalArgumentException("LogEvent.of 第 " + (index / 2) + " 个键不得为 null/空白");
      }
      fields.put(rawKey.toString(), keyValues[index + 1]);
    }
    return new LogEvent(name, fields);
  }

  /** 渲染为一行：{@code event=<name> k=v k2=v2}；无字段时只有 {@code event=<name>}。 */
  public String toLine() {
    String body = renderFields(fields);
    return body.isEmpty() ? "event=" + name : "event=" + name + " " + body;
  }

  /** 字段表 → {@code k=v k2=v2}（唯一格式化实现；旧 {@code XxxLog.kv} 也委托这里）。 */
  static String renderFields(Map<String, Object> fields) {
    StringBuilder text = new StringBuilder();
    for (Map.Entry<String, Object> entry : fields.entrySet()) {
      if (text.length() > 0) {
        text.append(' ');
      }
      text.append(entry.getKey()).append('=').append(entry.getValue());
    }
    return text.toString();
  }
}
