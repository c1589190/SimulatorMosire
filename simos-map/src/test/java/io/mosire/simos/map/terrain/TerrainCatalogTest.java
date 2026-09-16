package io.mosire.simos.map.terrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** spec §6.1（U1）：全模块唯一的地形词表 —— 7 项、高度升序、保序落盘。 */
class TerrainCatalogTest {

  @Test
  void catalogHasExactlySevenKeys() {
    assertThat(TerrainCatalog.KEYS).hasSize(7);
  }

  @Test
  void defaultsKeySetEqualsKeys() {
    // 钉保序：keyset 的**迭代序**必须与 KEYS 逐项一致（不是"集合相等"，那会放过 copyOf）。
    assertThat(TerrainCatalog.defaults().keySet()).containsExactlyElementsOf(TerrainCatalog.KEYS);
  }

  @Test
  void defaultsIterationOrderIsStable() {
    List<String> first = new ArrayList<>(TerrainCatalog.defaults().keySet());
    List<String> second = new ArrayList<>(TerrainCatalog.defaults().keySet());
    assertThat(second).containsExactlyElementsOf(first);
  }

  @Test
  void defaultsIsUnmodifiable() {
    Map<String, TerrainType> catalog = TerrainCatalog.defaults();
    assertThatThrownBy(() -> catalog.put("nope", catalog.get("plains")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void ofThrowsOnUnknownKey() {
    assertThatThrownBy(() -> TerrainCatalog.of("nope"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知地形类型");
  }

  @Test
  void everyTypeHasDistinctNameAndColor() {
    List<String> names =
        TerrainCatalog.defaults().values().stream().map(TerrainType::name).toList();
    List<String> colors =
        TerrainCatalog.defaults().values().stream().map(TerrainType::color).toList();
    assertThat(names).hasSize(7).doesNotHaveDuplicates();
    assertThat(colors).hasSize(7).doesNotHaveDuplicates();
  }

  @Test
  void everyColorMatchesHexPattern() {
    for (TerrainType t : TerrainCatalog.defaults().values()) {
      assertThat(t.color()).as("地形 %s 的颜色", t.key()).matches("#[0-9A-Fa-f]{6}");
    }
  }

  /** 逐项按自身字段重建：构造期校验若误伤了词表里的合法行（例如把 {@code maxHeight == 1.0} 判成非法），此处当场红。 */
  @Test
  void everyTypeIsConstructible() {
    List<TerrainType> rebuilt = new ArrayList<>();
    for (TerrainType t : TerrainCatalog.defaults().values()) {
      rebuilt.add(
          new TerrainType(
              t.key(),
              t.name(),
              t.color(),
              t.minHeight(),
              t.maxHeight(),
              t.food(),
              t.gold(),
              t.stone(),
              t.moveCost(),
              t.description()));
    }
    assertThat(rebuilt).containsExactlyElementsOf(TerrainCatalog.defaults().values());
  }

  /**
   * 高度带**连续、不重叠、覆盖 [0,1]**（spec §6.1 / L9 的结构性判据）。
   *
   * <p>★ 相接处用**浮点 {@code ==}**、不用容差：那两个数是**同一批字面量**，不是两次数值计算的结果。容差会把"差 0.001 的缝"变成绿 ——
   * 而那正是本用例要抓的东西。
   */
  @Test
  void heightBandsAreContiguousAndCoverUnitInterval() {
    List<TerrainType> byHeight = new ArrayList<>(TerrainCatalog.defaults().values());
    byHeight.sort(Comparator.comparingDouble(TerrainType::minHeight));
    assertThat(byHeight).hasSize(7);

    assertThat(byHeight.get(0).minHeight()).as("最低带必须从 0.0 起").isEqualTo(0.0);
    assertThat(byHeight.get(6).maxHeight()).as("最高带必须到 1.0 止").isEqualTo(1.0);
    for (int i = 0; i + 1 < byHeight.size(); i++) {
      TerrainType lower = byHeight.get(i);
      TerrainType upper = byHeight.get(i + 1);
      assertThat(lower.maxHeight())
          .as("%s 的带尾与 %s 的带头相接", lower.key(), upper.key())
          .isEqualTo(upper.minHeight());
    }
  }

  @Test
  void keysAreInAscendingHeightOrder() {
    List<TerrainType> byHeight = new ArrayList<>(TerrainCatalog.defaults().values());
    byHeight.sort(Comparator.comparingDouble(TerrainType::minHeight));
    assertThat(TerrainCatalog.KEYS)
        .containsExactlyElementsOf(byHeight.stream().map(TerrainType::key).toList());
  }

  /** "特性"栏里那句相对大小的断言化。★ 只钉计划给定的三条，**其余两两之间不设断言**（没依据的不编成断言）。 */
  @Test
  void moveCostOrderMatchesCharacteristics() {
    Map<String, TerrainType> catalog = TerrainCatalog.defaults();
    TerrainType plains = catalog.get("plains");

    for (TerrainType t : catalog.values()) {
      if (!t.key().equals(plains.key())) {
        assertThat(plains.moveCost()).as("平原必须严格最好走（与 %s 比）", t.key()).isLessThan(t.moveCost());
      }
    }
    // "不可通行"（海洋）不弱于"几乎不可通行"（高原山地）。
    assertThat(catalog.get("ocean").moveCost())
        .isGreaterThanOrEqualTo(catalog.get("plateau_mountains").moveCost());
    // "高但平坦" —— 海拔高于山地，却相对好走。
    assertThat(catalog.get("plateau").moveCost()).isLessThan(catalog.get("mountains").moveCost());
  }

  /** 旧词表 B 里 key {@code plains} 的 name 是"山区"（spec §6.1 的命名事故），新表不许复活它。 */
  @Test
  void plainsIsPlainsNotMountains() {
    assertThat(TerrainCatalog.of("plains").name()).doesNotContain("山");
  }

  /**
   * ★ 排除用例：{@code #6CC261} 是旧词表 A 的平原绿，也正是 {@code ContourQueryEngine.terrainColor} 的兜底色
   * ——**跨词表串味的物证**。
   *
   * <p>★ 它钉的**不是**"plains 的颜色长什么样"，而是"**这个已知污染值不许在任何一项上复活**"。
   */
  @Test
  void plainsGreenIsNotTheOldFallback() {
    for (TerrainType t : TerrainCatalog.defaults().values()) {
      assertThat(t.color()).as("地形 %s 复活了旧的兜底色", t.key()).isNotEqualTo("#6CC261");
    }
  }
}
