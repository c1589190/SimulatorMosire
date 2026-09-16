package io.mosire.simos.util.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
