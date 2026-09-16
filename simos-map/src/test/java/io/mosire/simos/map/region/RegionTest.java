package io.mosire.simos.map.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 权威区域：身份与内容分离（铁律 1）、边界是组件且被构造期钉死（用户裁决 U2）。 */
class RegionTest {

  private static final RegionId ID = new RegionId("r1");

  private static final Set<HexCoord> HEXES =
      Set.of(new HexCoord(0, 0), new HexCoord(1, 0), new HexCoord(0, 1));

  private static Region region() {
    return Region.of(ID, "北境", HEXES, null);
  }

  @Test
  void constructorRejectsBlankIdAndName() {
    // id 的空白校验在 RegionId 自己身上（Region 构造器只需判 null，见下一个用例）
    assertThatThrownBy(() -> new RegionId(""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为空白");
    assertThatThrownBy(() -> new RegionId("   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为空白");
    assertThatThrownBy(() -> Region.of(ID, "   ", HEXES, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name");
  }

  /** ★ 冻结字面量。写成"两个实例的 toString 相等"是自证循环，钉不住 record 的默认实现。 */
  @Test
  void regionIdToStringIsBareValue() {
    assertThat(new RegionId("r1").toString()).isEqualTo("r1");
  }

  /** ★ 地址的往返：变更集拿 {@code toString()} 当 String key，apply 侧靠 {@link RegionId#parse} 还原。 */
  @Test
  void regionIdParseRoundTripsFrozenLiteral() {
    assertThat(RegionId.parse("r1")).isEqualTo(new RegionId("r1"));
    assertThat(RegionId.parse(new RegionId("r1").toString())).isEqualTo(new RegionId("r1"));

    assertThatThrownBy(() -> RegionId.parse("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RegionId.parse("  ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RegionId.parse(null)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void constructorRejectsNullId() {
    assertThatThrownBy(() -> new Region(null, "北境", HEXES, RegionBoundary.of(HEXES), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("id");
  }

  /**
   * ★ 消息必须**精确**匹配：若这道守卫被删掉，{@code recomputed.equals(null)} 会为假、于是紧接着的"不一致"校验抛出 —— 同样是 {@link
   * IllegalArgumentException}。只断言类型的话，用例在"守卫被删"与"守卫生效"两种情况下都绿。
   */
  @Test
  void constructorRejectsNullBoundary() {
    assertThatThrownBy(() -> new Region(ID, "北境", HEXES, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("boundary 不得为 null");
  }

  @Test
  void hexesIsImmutable() {
    Set<HexCoord> source = new HashSet<>(HEXES);
    Region region = Region.of(ID, "北境", source, null);

    source.add(new HexCoord(9, 9));
    source.remove(new HexCoord(0, 0));

    assertThat(region.hexes()).containsExactlyInAnyOrderElementsOf(HEXES);
    assertThat(region.hexes()).isUnmodifiable();
  }

  @Test
  void containsIsSetBased() {
    Region region = region();
    assertThat(region.contains(new HexCoord(0, 0))).isTrue();
    assertThat(region.contains(new HexCoord(0, 1))).isTrue();
    assertThat(region.contains(new HexCoord(1, 1))).isFalse();
    assertThat(region.contains(new HexCoord(9, 9))).isFalse();
  }

  /**
   * ★ 逐组件判等。{@code boundary} 一项**无法单独变异** —— 构造器把它钉成 {@code hexes} 的函数，故"边界不同而其余全同"的 Region
   * 根本构造不出来（要构造就得连 {@code hexes} 一起改，那是另一种不同）。边界自身的判等由 {@code RegionBoundaryTest} 直接钉。
   */
  @Test
  void equalityIsComponentwise() {
    Region base = region();
    // 同五元组 → 相等。★ 入参 Set 的迭代序**故意**与 base 相反：这条相等同时也是"边界规范化"的旁证
    Region sameContent = Region.of(ID, "北境", reversedOrder(HEXES), null);
    assertThat(sameContent).isEqualTo(base).hasSameHashCodeAs(base);

    assertThat(Region.of(new RegionId("r2"), "北境", HEXES, null)).isNotEqualTo(base);
    assertThat(Region.of(ID, "南境", HEXES, null)).isNotEqualTo(base);
    assertThat(Region.of(ID, "北境", Set.of(new HexCoord(4, 4)), null)).isNotEqualTo(base);
    assertThat(Region.of(ID, "北境", HEXES, new RegionMeta("#FFF", null, null, null)))
        .isNotEqualTo(base);
  }

  /** ★ 铁律 1：内容是内容，身份是身份 —— 换地盘不动 id/name。 */
  @Test
  void withHexesKeepsIdAndName() {
    Region moved = region().withHexes(Set.of(new HexCoord(7, 7), new HexCoord(8, 7)));

    assertThat(moved.id()).isEqualTo(ID);
    assertThat(moved.name()).isEqualTo("北境");
    assertThat(moved.meta()).isEqualTo(RegionMeta.empty());
    assertThat(moved.hexes()).containsExactlyInAnyOrder(new HexCoord(7, 7), new HexCoord(8, 7));
  }

  /** ★ 铁律 1：改名不改身份，也不动内容。 */
  @Test
  void withNameKeepsId() {
    Region renamed = region().withName("南境");

    assertThat(renamed.id()).isEqualTo(ID);
    assertThat(renamed.name()).isEqualTo("南境");
    assertThat(renamed.hexes()).isEqualTo(region().hexes());
  }

  @Test
  void metaDefaultsToEmptyWhenNull() {
    assertThat(region().meta()).isEqualTo(RegionMeta.empty());

    RegionMeta meta = new RegionMeta("#FFF", "t", "d", "annexer");
    assertThat(Region.of(ID, "北境", HEXES, meta).meta()).isEqualTo(meta);
  }

  /**
   * ★ **U2 的钉子**：边界是**入存储的组件**，不是派生物。与旧裁定的 {@code boundaryIsDerivedNotStored} **恰好相反** ——
   * 旧裁定下这条断言本身才是错的。
   *
   * <p>顺带钉住五元组的**声明序**：{@code Region} 是 record，{@code equals}/{@code hashCode} 由此派生，将来 {@code
   * MapChangeSet} 的字段也照它来。
   */
  @Test
  void boundaryIsAStoredComponent() {
    assertThat(Region.class.getRecordComponents())
        .extracting(RecordComponent::getName)
        .containsExactly("id", "name", "hexes", "boundary", "meta");
    assertThat(Region.class.getRecordComponents())
        .filteredOn(c -> c.getName().equals("boundary"))
        .singleElement()
        .extracting(RecordComponent::getType)
        .isEqualTo(RegionBoundary.class);
  }

  @Test
  void factoryComputesBoundaryFromHexes() {
    assertThat(region().boundary()).isEqualTo(RegionBoundary.of(HEXES));
  }

  @Test
  void constructorRejectsBoundaryThatDisagreesWithHexes() {
    RegionBoundary stray = RegionBoundary.of(Set.of(new HexCoord(9, 9)));

    assertThatThrownBy(() -> new Region(ID, "北境", HEXES, stray, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不一致");
  }

  @Test
  void withHexesRecomputesBoundary() {
    Set<HexCoord> moved = Set.of(new HexCoord(4, 4), new HexCoord(5, 4));
    Region after = region().withHexes(moved);

    assertThat(after.hexes()).isEqualTo(moved);
    assertThat(after.boundary()).isEqualTo(RegionBoundary.of(moved));
    assertThat(after.boundary()).isNotEqualTo(region().boundary());
  }

  /** 改名不动内容 ⇒ 边界**引用**都该复用（连重算都不必）。 */
  @Test
  void withNameKeepsBoundary() {
    Region base = region();
    assertThat(base.withName("南境").boundary()).isSameAs(base.boundary());
  }

  /** 同一批 hex、**迭代序相反**的集合（判等用例的两个入参必须是这种，否则断言在两种实现下都成立）。 */
  private static Set<HexCoord> reversedOrder(Set<HexCoord> hexes) {
    List<HexCoord> list = new ArrayList<>(hexes);
    Collections.reverse(list);
    return new LinkedHashSet<>(list);
  }
}
