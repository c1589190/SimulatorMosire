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

  // ★ 这里原有一条 `defaultsIterationOrderIsStable`（连调两次 defaults()、比 key 序），**已删**：
  // 变异体 m2 把 defaults() 换成 Map.copyOf，它**照样绿** —— 而 copyOf 正是它要抓的那个实现。
  // 原因是 copyOf 在**同一 JVM 内**对同一批 key 是确定的（探针实测：进程内两次相同，跨进程才不同）。
  // 真正钉住保序的是上面那条 `defaultsKeySetEqualsKeys`（与冻结的 KEYS 逐项比字面量序）。
  // 残留风险（记台账，不在本模块解决）：**跨进程**序的稳定性无人把守，单进程用例够不到它。

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
   * ★ 排除用例：**已知的串味兜底色不许在任何一项上复活**。
   *
   * <p>两个物证来自**两个不同的**旧词表：{@code #6CC261} 是词表 A 的平原绿、也是 {@code ContourQueryEngine.terrainColor}
   * 的兜底色；{@code #5B8C3E} 是词表 B 的低地绿、{@code CompressionService.terrainColor} 的兜底色（后者由 Task 2 实测补出，
   * spec §6.1 原先只记了前者）。
   *
   * <p>★ 它钉的**不是**"plains 的颜色长什么样"，而是"**这两个已知污染值一个都不许复活**"。
   *
   * <p>★ **必须大小写不敏感**：本类型的颜色校验正则允许小写（{@code #[0-9A-Fa-f]{6}}），所以 {@code "#6cc261"} 是一条与物证
   * **同值**的真实漏路，逐字符的 {@code isNotEqualTo} 会放过它。
   */
  @Test
  void noTypeRevivesAKnownFallbackColor() {
    List<String> polluted = List.of("#6CC261", "#5B8C3E");
    for (TerrainType t : TerrainCatalog.defaults().values()) {
      for (String bad : polluted) {
        assertThat(t.color()).as("地形 %s 的颜色复活了旧兜底色 %s", t.key(), bad).isNotEqualToIgnoringCase(bad);
      }
    }
  }

  /**
   * ★ 构造期校验**逐条自证**（G13）。m9 只证明了"高度带"那一条，其余几条若无人故意违规过就只是装饰。
   *
   * <p>判别力来源是**抛不抛**：删掉任一条守卫，对应的 {@code assertThatThrownBy} 会因"压根没抛"而红（这些守卫的失败消息都是 自定义文案，不是 {@code
   * requireNonNull} 那种"消息恰是字段名"的形态 2）。消息断言用来钉**是哪一条**守卫响的。
   *
   * <p>{@code description} **有意不设校验**——它只作文档用途，既不参与判据也不被解引用，null 也不破坏往返。
   */
  @Test
  void constructorRejectsInvalidFields() {
    assertThatThrownBy(() -> type(" ", "平原", "#9CCB5B", 0.30, 0.45, 3, 0, 0, 1))
        .as("key 空白")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("key");
    assertThatThrownBy(() -> type("plains", " ", "#9CCB5B", 0.30, 0.45, 3, 0, 0, 1))
        .as("name 空白")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name");
    assertThatThrownBy(() -> type("plains", "平原", "9CCB5B", 0.30, 0.45, 3, 0, 0, 1))
        .as("color 缺 # 号")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("color");
    assertThatThrownBy(() -> type("plains", "平原", "#9CCB5B", 0.30, 0.45, 3, 0, 0, 0))
        .as("moveCost < 1")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("moveCost");
    assertThatThrownBy(() -> type("plains", "平原", "#9CCB5B", 0.30, 0.45, -1, 0, 0, 1))
        .as("food 为负")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("产出");
    assertThatThrownBy(() -> type("plains", "平原", "#9CCB5B", 0.55, 0.45, 3, 0, 0, 1))
        .as("minHeight >= maxHeight")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("高度带");
    assertThatThrownBy(() -> type("plains", "平原", "#9CCB5B", 0.30, 1.45, 3, 0, 0, 1))
        .as("maxHeight > 1.0")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("高度带");
  }

  /** 逐字段可替换地造一个 {@code TerrainType}——只为负例用例服务，不进词表。 */
  private static TerrainType type(
      String key,
      String name,
      String color,
      double minHeight,
      double maxHeight,
      int food,
      int gold,
      int stone,
      int moveCost) {
    return new TerrainType(
        key, name, color, minHeight, maxHeight, food, gold, stone, moveCost, "负例用例");
  }
}
