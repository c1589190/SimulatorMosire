package io.mosire.simos.ga.spi;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具面的 **JSON Schema 小件 + 入参解析**（模型给的参数 → 强类型 / 坏输入当场抛）。纯函数、零状态。
 *
 * <p>★ 迁自 {@code io.mosire.simos.app.tools.ToolSupport}（2026-10-12 B1，设计书 §4.2 的 "ToolResource /
 * ToolSchema（资源与 schema 助手）"一行）：只挑**不依赖 app / 领域 / core** 的那部分。 旧的 {@code ToolSupport}
 * **一行未改**（本批纯新增；B3 的 ToolSupport 三分再收口）。
 *
 * <p>★ <b>异常口径</b>：坏输入一律 {@link IllegalArgumentException}（由各工具的 {@code execute} 折成具名 {@code
 * BAD_REQUEST}，不静默按缺省办）——"判不了就放行 / 就当没给"正是本仓要消灭的形态。
 *
 * <p>★ <b>没迁的</b>：{@code json} / {@code ok} / {@code fold} / {@code plannedCommandsView} / {@code
 * committedView} 等结果折叠（它们要么吃 core 的 {@code CommandResult}，要么是 app 侧视图口径）， 以及 {@code target(...)}（返回
 * app 的 {@code QueryTarget}）——都留给 B3 的 {@code ToolSupport} 三分。
 */
public final class ToolSchema {

  private ToolSchema() {}

  // ── 参数解析（模型给的 JSON 参数；坏输入折 ToolResult.error，不抛给管线）────────────────

  /** 必填非空文本；缺失 / 空串 / 非文本 ⇒ {@link IllegalArgumentException}。 */
  public static String requiredText(Map<String, Object> args, String name) {
    Object value = args.get(name);
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("参数 " + name + " 必填且为非空文本");
    }
    return text;
  }

  /** 可选非空文本；缺失 ⇒ {@code fallback}；给了但空/非文本 ⇒ {@link IllegalArgumentException}。 */
  public static String optionalText(Map<String, Object> args, String name, String fallback) {
    Object value = args.get(name);
    if (value == null) {
      return fallback;
    }
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("参数 " + name + " 若给出必须是非空文本");
    }
    return text;
  }

  /** 必填整数；缺失 ⇒ {@link IllegalArgumentException}（"必填且为整数"）。 */
  public static long requiredLong(Map<String, Object> args, String name) {
    Long value = optionalLong(args, name);
    if (value == null) {
      throw new IllegalArgumentException("参数 " + name + " 必填且为整数");
    }
    return value;
  }

  /** 可选整数；缺失 ⇒ {@code null}；数字原样取 long，非空文本走 {@code Long.parseLong}，其余 ⇒ 抛。 */
  public static Long optionalLong(Map<String, Object> args, String name) {
    Object value = args.get(name);
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value instanceof String text && !text.isBlank()) {
      try {
        return Long.parseLong(text.trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("参数 " + name + " 必须是整数: " + text);
      }
    }
    throw new IllegalArgumentException("参数 " + name + " 必须是整数");
  }

  /** 参数是否**给了值**（{@code null} 视为没给；空串视为给了——由各自的 text 解析器判空）。 */
  public static boolean has(Map<String, Object> args, String name) {
    return args.get(name) != null;
  }

  /**
   * 可选布尔参数（缺省 = {@link Optional#empty()}；字符串 {@code "true"}/{@code "false"} 也接受， 与既有窄工具的 {@code
   * dryRun} 形态一致）。类型不对 ⇒ {@link IllegalArgumentException} （由工具折成具名 {@code BAD_REQUEST}，不静默按缺省办）。
   */
  public static Optional<Boolean> optionalBoolean(Map<String, Object> args, String name) {
    Object value = args.get(name);
    if (value == null) {
      return Optional.empty();
    }
    if (value instanceof Boolean bool) {
      return Optional.of(bool);
    }
    if (value instanceof String text && !text.isBlank()) {
      if ("true".equalsIgnoreCase(text.trim())) {
        return Optional.of(true);
      }
      if ("false".equalsIgnoreCase(text.trim())) {
        return Optional.of(false);
      }
    }
    throw new IllegalArgumentException("参数 " + name + " 必须是布尔值");
  }

  // ── JSON Schema 小件（工具定义给模型看）──────────────────────────────────────────────

  /** 单个 schema 属性（{@code {"type":…, "description":…}}，保持插入序）。 */
  public static Map<String, Object> prop(String type, String description) {
    Map<String, Object> property = new LinkedHashMap<>();
    property.put("type", type);
    property.put("description", description);
    return property;
  }

  /** 查/写共用的 {@code {branch, revision}} 两个可选属性（缺省见 {@link ToolResources#DEFAULT_BRANCH}）。 */
  public static Map<String, Object> targetProps() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("branch", prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put("revision", prop("integer", "版本号（缺省 = 该分支 head）"));
    return props;
  }

  /** 组一个 object schema：{@code type=object} + {@code properties}，{@code required} 为空则整项不出现。 */
  public static Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", properties);
    if (!required.isEmpty()) {
      schema.put("required", required);
    }
    return schema;
  }
}
