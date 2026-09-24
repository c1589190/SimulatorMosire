package io.mosire.simos.core.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link Envelope} 的往返用例（C26）：载荷**逐字节**保真、modules 的 value 是文本不是嵌套对象、 decode 的负向形态。 */
class EnvelopeTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * 有判别力的载荷（M1 的"纯转发型 SPI"形态 4，R11 的同族形态）：**首尾空白、键序非字典序、转义字符**—— 只测 {@code "{}"} 的用例对"Core 有没有 trim
   * / re-serialize"零判别力。
   */
  private static final String MAP_PAYLOAD = "  {\"z\":1,\"a\":\"  引号\\\"内嵌  \"}  \n";

  private static StateMeta metaAt(long revision, long tick) {
    return new StateMeta(
        new StateRef(new BranchId("main"), new RevisionId(revision)), SimosTimestamp.of(tick));
  }

  /** 核心往返：encode → 文本 → decode，各字段逐条 equals；modules 的每个 value 与传入串**逐字节**相同。 */
  @Test
  void roundTripsAllFieldsAndKeepsPayloadsByteExact() throws Exception {
    StateMeta meta = metaAt(100, 480);
    LinkedHashMap<String, String> moduleJson = new LinkedHashMap<>();
    moduleJson.put("map", MAP_PAYLOAD);
    moduleJson.put("unit", "{\"id\":\"u-1\"}");
    String infoJson = "{\"key\":\"值\",\"n\":2}";

    String json = Envelope.encode(meta, moduleJson, infoJson).toString();
    Envelope.Decoded decoded = Envelope.decode(json);

    assertThat(decoded.meta()).isEqualTo(meta);
    assertThat(decoded.modules().keySet()).as("decode 保序").containsExactly("map", "unit");
    assertThat(decoded.modules().get("map")).as("C26：载荷逐字节保真").isEqualTo(MAP_PAYLOAD);
    assertThat(decoded.modules().get("unit")).isEqualTo("{\"id\":\"u-1\"}");
    assertThat(MAPPER.readTree(decoded.infoJson()))
        .as("info 是 Core 自己的段，按树比较（不承诺逐字节）")
        .isEqualTo(MAPPER.readTree(infoJson));
  }

  /** {@code calendarLabel} 的双向映射：present ↔ 文本；empty ↔ JSON null（spec §6.3 的示例形态）。 */
  @Test
  void roundTripsCalendarLabelBothWays() {
    StateMeta labeled =
        new StateMeta(
            new StateRef(new BranchId("b2"), new RevisionId(1)), SimosTimestamp.of(600, "正午"));
    String labeledJson = Envelope.encode(labeled, Map.of(), "{}").toString();
    assertThat(Envelope.decode(labeledJson).meta()).isEqualTo(labeled);

    String bareJson = Envelope.encode(metaAt(1, 7), Map.of(), "{}").toString();
    assertThat(bareJson).as("无 label 落成 JSON null").contains("\"calendarLabel\":null");
    assertThat(Envelope.decode(bareJson).meta()).isEqualTo(metaAt(1, 7));
  }

  /** C26 的字面形态：modules 的 value 是**文本**节点——嵌套对象即违反（见下一条 decode 侧的对称守卫）。 */
  @Test
  void embedsModulePayloadsAsTextNotAsObjects() {
    var root = Envelope.encode(metaAt(2, 3), Map.of("map", MAP_PAYLOAD), "{}");

    assertThat(root.get("modules").get("map").isTextual()).isTrue();
    assertThat(root.get("modules").get("map").asText()).isEqualTo(MAP_PAYLOAD);
  }

  /** decode 侧的对称守卫：载荷若被 parse 成嵌套对象内嵌 ⇒ 拒绝（那一步正是 C26 禁止的）。 */
  @Test
  void decodeRejectsNonTextualModulePayload() {
    String json =
        "{\"ref\":{\"branch\":\"main\",\"revision\":1},"
            + "\"timestamp\":{\"tick\":1,\"calendarLabel\":null},"
            + "\"timeBase\":\"DAY\","
            + "\"modules\":{\"map\":{\"nested\":true}},"
            + "\"info\":{}}";

    assertThatThrownBy(() -> Envelope.decode(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("C26");
  }

  /** ★ 时间基（2026-09-24 日制裁定）：encode 必带 {@code timeBase=DAY}（与 store_meta 同源同值）。 */
  @Test
  void encodeWritesTheDayTimeBaseLabel() {
    var root = Envelope.encode(metaAt(1, 1), Map.of(), "{}");

    assertThat(root.get("timeBase").asText())
        .as("新档一律带日制标签——旧档在 decode 侧 fail-closed 的对照面")
        .isEqualTo(Envelope.TIME_BASE_DAY);
  }

  /**
   * ★★ 缺 {@code timeBase} = **日制裁定之前的旧档** ⇒ 拒读，不静默按天读：旧档的 tick 代表小时， 按天重放会把"持续 24 小时"读成"持续 24
   * 天"（设计稿 §3）。
   */
  @Test
  void decodeRejectsLegacyEnvelopeWithoutTimeBase() {
    String legacy =
        "{\"ref\":{\"branch\":\"main\",\"revision\":1},"
            + "\"timestamp\":{\"tick\":1,\"calendarLabel\":null},"
            + "\"modules\":{},\"info\":{}}";

    assertThatThrownBy(() -> Envelope.decode(legacy))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("timeBase");
  }

  /** 标签存在但不是 DAY（异基/未知基）⇒ 同样拒。 */
  @Test
  void decodeRejectsNonDayTimeBase() {
    String hourly =
        "{\"ref\":{\"branch\":\"main\",\"revision\":1},"
            + "\"timestamp\":{\"tick\":1,\"calendarLabel\":null},"
            + "\"timeBase\":\"HOUR\","
            + "\"modules\":{},\"info\":{}}";

    assertThatThrownBy(() -> Envelope.decode(hourly))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DAY");
  }

  @Test
  void decodeRejectsMalformedAndNonObjectJson() {
    assertThatThrownBy(() -> Envelope.decode("not json"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Envelope.decode("[]"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("对象");
  }

  @Test
  void decodeRejectsMissingField() {
    String json = "{\"timestamp\":{\"tick\":1,\"calendarLabel\":null},\"modules\":{},\"info\":{}}";

    assertThatThrownBy(() -> Envelope.decode(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ref");
  }

  @Test
  void encodeRejectsNullArgumentsAndNullPayload() {
    Map<String, String> nullValue = new LinkedHashMap<>();
    nullValue.put("map", null);

    assertThatThrownBy(() -> Envelope.encode(null, Map.of(), "{}"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> Envelope.encode(metaAt(1, 1), null, "{}"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> Envelope.encode(metaAt(1, 1), Map.of(), null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> Envelope.encode(metaAt(1, 1), nullValue, "{}"))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("moduleJson 的值");
  }
}
