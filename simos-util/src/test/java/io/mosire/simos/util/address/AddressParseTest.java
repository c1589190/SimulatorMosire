package io.mosire.simos.util.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** spec §3.6：总纲 §4.4 的冻结样例逐条核对——解析结果与 canonical 往返。 */
class AddressParseTest {

  /** 冻结样例（逐字抄自 spec §3.6 表）。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "map:Map1",
        "map:Map1:hex.4_3",
        "map:Map1:terra.Grass",
        "map:Map1:terra.Grass:height",
        "map:Map1:region.Nation.区域A",
        "map:Map1:region.Nation.区域A:hexes",
        "map:Map1:conn.river.r-f82a",
        "social:Map1:hex.4_3:population",
        "social:Map1:hex.4_3:population_growth",
        "unit:U:member",
        "unit:U:equipment.步枪",
        "unit:U:hex",
        "unit:U:speed",
        "agent:bind.b-f82a",
        "agent:map:Map1:region.Nation.区域A"
      })
  void frozenSamplesRoundTrip(String canonical) {
    assertThat(Address.parse(canonical).canonical()).isEqualTo(canonical);
  }

  @Test
  void secondSegmentIsRootSubjectWithAbsentKind() {
    Address a = Address.parse("map:Map1");
    assertThat(a.namespace()).isEqualTo("map");
    assertThat(a.segments()).hasSize(2);
    assertThat(a.segments().get(1)).isEqualTo(Entity.of("Map1"));
  }

  @Test
  void kindNameSplitHappensOnFirstUnquotedDot() {
    assertThat(Address.parse("map:Map1:region.Nation.区域A").segments().get(2))
        .isEqualTo(Entity.of("region", "Nation.区域A"));
    assertThat(Address.parse("map:Map1:conn.river.r-f82a").segments().get(2))
        .isEqualTo(Entity.of("conn", "river.r-f82a"));
  }

  @Test
  void bareWordAfterPositionTwoIsProperty() {
    assertThat(Address.parse("unit:U:member").segments().get(2)).isEqualTo(new Property("member"));
    assertThat(Address.parse("map:Map1:region.Nation.区域A:hexes").segments().get(3))
        .isEqualTo(new Property("hexes"));
  }

  /**
   * spec §3.4 末段：引号只在有未加引号的 `.` 时才参与段类型判定——第 ≥3 段无未加引号 `.` 的 token 先去引再判，去引后是裸词即
   * `Property`（引号冗余，归一掉）。
   */
  @Test
  void quotedBareWordAtPositionThreeNormalizesToProperty() {
    assertThat(Address.parse("unit:U:\"member\"").segments().get(2))
        .isEqualTo(new Property("member"));
    assertThat(Address.parse("unit:U:\"member\"").canonical()).isEqualTo("unit:U:member");
    assertThat(Address.parse("unit:\"U\":member").segments().get(1)).isEqualTo(Entity.of("U"));
  }

  /** spec §3.4 条件 4：带 kind 的 name 有空组件（首/尾 `.`、连续 `..`）必须加引，否则解析不回来。 */
  @Test
  void emptyNameComponentsRoundTrip() {
    for (String name : List.of("1..2", "1.", ".1", "...", "Nation..区域A")) {
      Entity original = Entity.of("hex", name);
      Address round = Address.parse("map:Map1:" + original.canonical());
      assertThat(round.segments().get(2)).isEqualTo(original);
      assertThat(round.canonical()).isEqualTo("map:Map1:" + original.canonical());
    }
  }

  /** spec §3.6 表第二列写成完整段序列的行：整条 `segments()` 逐段核对（`map:Map1` 见构造器用例）。 */
  @Test
  void frozenFullRowsParseToExpectedSegments() {
    assertThat(Address.parse("social:Map1:hex.4_3:population").segments())
        .containsExactly(
            new Namespace("social"),
            Entity.of("Map1"),
            Entity.of("hex", "4_3"),
            new Property("population"));
    assertThat(Address.parse("social:Map1:hex.4_3:population_growth").segments())
        .containsExactly(
            new Namespace("social"),
            Entity.of("Map1"),
            Entity.of("hex", "4_3"),
            new Property("population_growth"));
    assertThat(Address.parse("unit:U:member").segments())
        .containsExactly(new Namespace("unit"), Entity.of("U"), new Property("member"));
    assertThat(Address.parse("agent:bind.b-f82a").segments())
        .containsExactly(new Namespace("agent"), Entity.of("bind", "b-f82a"));
    // §3.7：内嵌地址原样承载，语法层不做特殊处理——故第 3 段的 `Map1` 是 Property。
    assertThat(Address.parse("agent:map:Map1:region.Nation.区域A").segments())
        .containsExactly(
            new Namespace("agent"),
            Entity.of("map"),
            new Property("Map1"),
            Entity.of("region", "Nation.区域A"));
  }

  /** spec §3.6 表第二列写成增量（`+ Entity(...)` / `+ Property(...)`）的行：核对末段。 */
  @ParameterizedTest
  @MethodSource("incrementalRows")
  void frozenIncrementalRowsEndWithExpectedSegment(String text, AddressSegment last) {
    assertThat(Address.parse(text).segments().getLast()).isEqualTo(last);
  }

  static Stream<Arguments> incrementalRows() {
    return Stream.of(
        Arguments.of("map:Map1:hex.4_3", Entity.of("hex", "4_3")),
        Arguments.of("map:Map1:terra.Grass", Entity.of("terra", "Grass")),
        Arguments.of("map:Map1:terra.Grass:height", new Property("height")),
        Arguments.of("map:Map1:region.Nation.区域A", Entity.of("region", "Nation.区域A")),
        Arguments.of("map:Map1:region.Nation.区域A:hexes", new Property("hexes")),
        Arguments.of("map:Map1:conn.river.r-f82a", Entity.of("conn", "river.r-f82a")),
        Arguments.of("unit:U:equipment.步枪", Entity.of("equipment", "步枪")),
        Arguments.of("unit:U:hex", new Property("hex")),
        Arguments.of("unit:U:speed", new Property("speed")));
  }

  @Test
  void indexSegmentIsParsed() {
    assertThat(Address.parse("map:Map1:[4,3]").segments().get(2))
        .isEqualTo(new Index(List.of(4, 3)));
    assertThat(Address.parse("map:Map1:[7]").segments().get(2)).isEqualTo(new Index(List.of(7)));
  }

  @Test
  void quotedKindlessComponentsCollapseIntoOneName() {
    assertThat(Address.parse("map:Map1:\"Nation\".\"区域A\"").segments().get(2))
        .isEqualTo(Entity.of("Nation.区域A"));
  }

  @Test
  void addressRequiresAtLeastTwoSegmentsAndNamespaceFirst() {
    assertThat(Address.parse("map:Map1").segments().get(0)).isInstanceOf(Namespace.class);
    assertThatThrownBy(() -> new Address(List.of(Entity.of("Map1"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Address(List.of(Entity.of("Map1"), new Property("x"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** G13 自证：第 ≥3 段缺 kind 的裸词主体必须被拒——否则它与 `Property` 的 canonical 撞车。 */
  @Test
  void kindlessEntityOutsideRootPositionIsRejected() {
    assertThatThrownBy(
            () ->
                new Address(List.of(new Namespace("social"), Entity.of("U"), Entity.of("member"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** G13 自证：命名空间只允许第 1 段——第 ≥2 段的 `Namespace` 渲染成裸词，与 `Entity(∅,…)` 撞 canonical。 */
  @Test
  void namespaceOutsideFirstPositionIsRejected() {
    assertThatThrownBy(() -> new Address(List.of(new Namespace("map"), new Namespace("social"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** G13 自证：第 2 段是根主体，不得是 `Property`——它与 `Entity(∅,…)` 的 canonical 逐字相同。 */
  @Test
  void propertyAtRootPositionIsRejected() {
    assertThatThrownBy(() -> new Address(List.of(new Namespace("map"), new Property("x"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** spec §3.4 条件 2（已收紧为含任意空白即加引）：`region."A B"` 往返恒等。 */
  @Test
  void internalWhitespaceNameRoundTrips() {
    Entity original = Entity.of("region", "A B");
    String canonical = "map:Map1:" + original.canonical();
    assertThat(canonical).isEqualTo("map:Map1:region.\"A B\"");
    assertThat(Address.parse(canonical).segments().get(2)).isEqualTo(original);
    assertThat(Address.parse(canonical).canonical()).isEqualTo(canonical);
  }

  /** spec §3.4 条件 2 的另一半：空名字要写成 `""`，它与真·空段（`::`）不是一回事。 */
  @Test
  void emptyNameParsesButTrulyEmptySegmentThrows() {
    assertThat(Address.parse("map:Map1:region.\"\"").segments().get(2))
        .isEqualTo(Entity.of("region", ""));
    assertThat(Address.parse("map:Map1:region.\"\"").canonical()).isEqualTo("map:Map1:region.\"\"");
    assertThatThrownBy(() -> Address.parse("map:Map1::x"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
