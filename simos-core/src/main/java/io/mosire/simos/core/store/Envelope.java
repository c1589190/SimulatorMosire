package io.mosire.simos.core.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 快照信封的手工拼装与拆解（spec §6.3，C26 的落点）。形态：
 *
 * <pre>{ "ref": {"branch":"main","revision":100},
 *   "timestamp": {"tick":480,"calendarLabel":null},
 *   "timeBase": "DAY",
 *   "modules": { "map": "&lt;载荷 JSON 文本，原样内嵌&gt;", "social": "…", "unit": "…" },
 *   "info": { … } }</pre>
 *
 * <p>★ **{@code modules} 的 value 是 JSON 文本（{@code String}），不是嵌套对象**——这是 C26 的字面要求：Core
 * 把模块载荷**当文本**，因此**从不反序列化模块类型**，多态反序列化的整类问题在 Core 侧不存在。据此 {@link #encode} 对载荷**不 parse、不 trim、不
 * re-serialize**（R11 的同族承诺：逐字节保真），{@link #decode} 读回时同样**原样**给出。要比较载荷，比字符串；要解出对象，交给对应模块的
 * codec——那不是本类的事。
 *
 * <p>★ {@code info} 段：{@code InfoSystem} 与 {@code Address} 都是 **util 的类型**（不是领域类型），Core 可以
 * 自己序列化（spec §6.3）。它按 JSON 文本进、按 JSON 文本出，**不承诺逐字节**（parse 成树再落文本，空白与 键序可能归一化）——承诺逐字节的只有 modules 的
 * value。比较 info 按树（{@code equals} 语义），不按字节。
 *
 * <p>★ 本类里的 {@link ObjectMapper} 只服务**信封自己的字段**（ref/timestamp/modules/info 的树操作）——它们 全部是 util/Core
 * 的类型，不违反 C26；被禁的是让 Core 反序列化**模块**的类型。Core 侧的 ObjectMapper 装配点 归 util.json（Task
 * 3），本类是信封层的独立小消费者，不经它。
 */
public final class Envelope {

  /**
   * 日制时间基标签（2026-09-24 日制裁定，POLITICAL_ECONOMY_DESIGN.md §3）：信封的 {@code timeBase} 与 SQLite 的 {@code
   * store_meta.time_base} **共用这一个字面量**——两处必须说同一种时间语义，不同源就会出现"库按天读、档按小时写"的错配。
   */
  public static final String TIME_BASE_DAY = "DAY";

  /** 只碰信封自己的四个字段；配置保持默认——这里没有需要定制的 feature。 */
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Envelope() {}

  /**
   * 拼装信封。载荷按 {@code namespace → JSON 文本} 传入，**原样**内嵌为文本值。
   *
   * @throws NullPointerException {@code meta} / {@code moduleJson} / {@code infoJson} 或其中的键值为 null
   * @throws IllegalArgumentException {@code infoJson} 不是合法 JSON、或不是 JSON 对象
   */
  public static ObjectNode encode(StateMeta meta, Map<String, String> moduleJson, String infoJson) {
    Objects.requireNonNull(meta, "meta");
    Objects.requireNonNull(moduleJson, "moduleJson");
    Objects.requireNonNull(infoJson, "infoJson");

    ObjectNode root = MAPPER.createObjectNode();

    ObjectNode ref = root.putObject("ref");
    ref.put("branch", meta.ref().branch().value());
    ref.put("revision", meta.ref().revision().value());

    ObjectNode timestamp = root.putObject("timestamp");
    timestamp.put("tick", meta.timestamp().tick());
    // 无 label 落成 JSON null（spec §6.3 的示例形态），有 label 落成文本
    timestamp.put("calendarLabel", meta.timestamp().calendarLabel().orElse(null));

    // ★ 时间基标签（日制裁定）：新档一律带；旧档（无此字段）在 decode 侧 fail-closed，不静默按天读
    root.put("timeBase", TIME_BASE_DAY);

    ObjectNode modules = root.putObject("modules");
    for (Map.Entry<String, String> entry : moduleJson.entrySet()) {
      // ★ C26：作为**文本值**原样放入——这里绝不 readTree（那一步就是"Core 反序列化模块载荷"）
      modules.put(
          Objects.requireNonNull(entry.getKey(), "moduleJson 的键"),
          Objects.requireNonNull(entry.getValue(), "moduleJson 的值"));
    }

    root.set("info", parseInfo(infoJson));
    return root;
  }

  /**
   * 拆解信封：读回元信息与各模块载荷的**原样文本**。
   *
   * @throws IllegalArgumentException JSON 非法、顶层不是对象、必有字段缺失或类型不对、或某个模块载荷不是文本
   */
  public static Decoded decode(String json) {
    Objects.requireNonNull(json, "json");
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("信封不是合法 JSON", e);
    }
    if (!root.isObject()) {
      throw new IllegalArgumentException("信封必须是 JSON 对象");
    }

    ObjectNode ref = requireObject(root, "ref");
    StateRef stateRef =
        new StateRef(
            new BranchId(requireText(ref, "branch")), new RevisionId(requireLong(ref, "revision")));

    ObjectNode timestamp = requireObject(root, "timestamp");
    JsonNode label = timestamp.get("calendarLabel");
    SimosTimestamp simosTimestamp =
        new SimosTimestamp(
            requireLong(timestamp, "tick"),
            label == null || label.isNull() ? Optional.empty() : Optional.of(label.asText()));

    // ★ 时间基（日制裁定）：**缺字段 = 旧格式** ⇒ fail-closed。旧档的 tick 数值代表小时，
    //   按天读会把"持续 24 小时"读成"持续 24 天"；宁可拒读，也不静默劣化（设计稿 §3）。
    JsonNode timeBase = root.get("timeBase");
    if (timeBase == null || !timeBase.isTextual()) {
      throw new IllegalArgumentException(
          "信封缺 timeBase（时间基标签）：日制裁定（2026-09-24）之前的旧档不予读取，请换新库或等离线迁移工具（POLITICAL_ECONOMY_DESIGN.md §3）");
    }
    if (!TIME_BASE_DAY.equals(timeBase.asText())) {
      throw new IllegalArgumentException(
          "信封的 timeBase 不是 " + TIME_BASE_DAY + "（实得 " + timeBase.asText() + "）：本引擎只读日制档");
    }

    ObjectNode modulesNode = requireObject(root, "modules");
    Map<String, String> modules = new LinkedHashMap<>();
    Iterator<Map.Entry<String, JsonNode>> fields = modulesNode.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      JsonNode value = field.getValue();
      // ★ C26 的形态守卫：载荷在信封里**必须是文本**——嵌套对象意味着有人把载荷 parse 后内嵌了，
      //   那一步正是 C26 禁止的"Core 碰模块载荷的结构"，在这里显式炸掉而不是静默收下。
      if (!value.isTextual()) {
        throw new IllegalArgumentException("模块载荷必须是 JSON 文本（C26）：namespace=" + field.getKey());
      }
      modules.put(field.getKey(), value.asText());
    }

    return new Decoded(
        new StateMeta(stateRef, simosTimestamp), modules, requireObject(root, "info").toString());
  }

  /** decode 的结果：元信息 + 各模块载荷的原样文本（保序、不可变）+ info 段的文本。 */
  public record Decoded(StateMeta meta, Map<String, String> modules, String infoJson) {

    public Decoded {
      Objects.requireNonNull(meta, "meta");
      Objects.requireNonNull(modules, "modules");
      Objects.requireNonNull(infoJson, "infoJson");
      // 返回处加固：保序 + 不可变（消费方改它不得反灌进 decode 的结果）
      modules = Collections.unmodifiableMap(new LinkedHashMap<>(modules));
    }
  }

  private static JsonNode parseInfo(String infoJson) {
    JsonNode info;
    try {
      info = MAPPER.readTree(infoJson);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("info 段不是合法 JSON", e);
    }
    if (!info.isObject()) {
      throw new IllegalArgumentException("info 段必须是 JSON 对象");
    }
    return info;
  }

  private static ObjectNode requireObject(JsonNode node, String name) {
    JsonNode field = node.get(name);
    if (field == null || !field.isObject()) {
      throw new IllegalArgumentException("信封缺字段（或不是对象）：" + name);
    }
    return (ObjectNode) field;
  }

  /** 取必须存在且为文本的字段——缺失时 {@code asText()} 的默认值（""）是静默劣化，必须显式拒绝。 */
  private static String requireText(JsonNode node, String name) {
    JsonNode field = node.get(name);
    if (field == null || !field.isTextual()) {
      throw new IllegalArgumentException("信封缺字段（或不是文本）：" + name);
    }
    return field.asText();
  }

  private static long requireLong(JsonNode node, String name) {
    JsonNode field = node.get(name);
    if (field == null || !field.canConvertToLong()) {
      throw new IllegalArgumentException("信封缺字段（或不是整数）：" + name);
    }
    return field.asLong();
  }
}
