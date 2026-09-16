package io.mosire.simos.map.generate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.terrain.TerrainCatalog;
import java.lang.reflect.RecordComponent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * spec §6.4 / L8：{@code GenerationSpec} 的**参数面**。
 *
 * <p>★ 本文件承接了原 {@code GameMapTest#generationSpecDefaultsCarryTheSeed} 里那条 {@code defaults(7L) ==
 * new GenerationSpec(7L)}（骨架期只有 seed 一个组件，只能那么写）。 参数面扩到 9 个组件后，那条断言的**真意** —— "收种子、只让 seed
 * 变，其余是规范默认值" —— 在 {@link #defaultsCarryOnlyTheSeed} 里以**逐组件**的形态重新钉住（更强：它看得见全部组件）。
 *
 * <p>★ **两条结构性断言**（{@link #noTerrainHeightThresholds} 与 {@link #noWorldIdNoCoastRoughness}） 都走
 * {@link #generationRecordTree()}：它们不依赖对字段语义的判断，故**不会随着参数面长大而失效** —— 新加一个子 record，它自动被覆盖。
 */
class GenerationSpecTest {

  /** 默认值里那四个"GSimulator 有三份拷贝且已分歧"的标量（spec §6.4）—— 钉住"唯一一份"这件事本身。 */
  @Test
  void defaultsIsUsable() {
    GenerationSpec d = GenerationSpec.defaults(1L);

    assertThat(d).isNotNull();
    assertThat(d.mapRadius()).as("取 MapConfig.DEFAULT.defaultMapRadius").isEqualTo(80);
    assertThat(d.mainRidges()).as("取 MCP 工具与 WebUI 的共同默认").isEqualTo(2);
    assertThat(d.fragments()).as("取 MCP 工具与 WebUI 的共同默认").isEqualTo(5);
    assertThat(d.contourCacheMax()).as("取 ContourQueryEngine.MAX_CACHE").isEqualTo(5000);
    assertThat(d.baseSeaLevel()).isBetween(0.0, 1.0);
    assertThat(d.bands()).isNotNull();
    assertThat(d.ridges()).isNotNull();
    assertThat(d.fragmentParams()).isNotNull();
  }

  /** ★ 从 {@code GameMapTest} 搬来的那条断言的**逐组件**形态：只有 {@code seed} 随入参变。 */
  @Test
  void defaultsCarryOnlyTheSeed() {
    GenerationSpec seven = GenerationSpec.defaults(7L);
    GenerationSpec eight = GenerationSpec.defaults(8L);

    assertThat(seven.seed()).isEqualTo(7L);
    assertThat(eight.seed()).isEqualTo(8L);
    for (RecordComponent rc : GenerationSpec.class.getRecordComponents()) {
      if (rc.getName().equals("seed")) {
        continue;
      }
      assertThat(read(seven, rc))
          .as("组件 %s 不该随种子变（它是规范默认值）", rc.getName())
          .isEqualTo(read(eight, rc));
    }
    assertThat(seven).as("种子不同的两个 spec 不相等").isNotEqualTo(eight);
  }

  /**
   * ★ **本任务的核心**：GSimulator 的 {@code Math.max(1, Math.min(mainCount, 2))}
   * （`MapGenerator.java:61`）让传 5 **静默变成 2** —— 调用方以为改了参数、实际没有。 本类型改为构造期抛。
   */
  @Test
  void mainRidgesFiveThrows() {
    assertThatThrownBy(() -> scalars(1L, 80, 0.2, 5, 5, 5000))
        .as("越界必须抛，不许静默夹到 2")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mainRidges");
  }

  /** 边界内可用：上界 2 与下界 1 都收。★ 它同时反证上界**恰为** 2（多了 3 也该抛）。 */
  @Test
  void mainRidgesTwoIsAccepted() {
    assertThat(scalars(1L, 80, 0.2, 2, 5, 5000).mainRidges()).isEqualTo(2);
    assertThat(scalars(1L, 80, 0.2, 1, 5, 5000).mainRidges()).isEqualTo(1);
  }

  /**
   * ★ **那条可为负的差值**：{@code frags = fragmentCount - secondary}（`MapGenerator.java:105`）。
   *
   * <p>先用 {@link FragmentParams} 的算术把"负的是差值、不是总数"摆出来：{@code fragments = 1} 是**正数**， 但它要供 {@code
   * secondaryCountFloor = 2} 条次级脊线，差值 {@code 1 - 2 = -1}。 GSimulator 那边这个负数只让碎片循环一次都不执行 ——
   * **不抛任何异常**。
   */
  @Test
  void negativeFragmentDifferenceThrows() {
    FragmentParams fp = GenerationSpec.defaults(1L).fragmentParams();
    assertThat(fp.secondaryCount(1)).as("1 条预算仍要摆 2 条次级脊线").isEqualTo(2);
    assertThat(fp.remainingCount(1)).as("差值确为负").isEqualTo(-1);
    assertThat(fp.remainingCount(2)).as("差值恰好为 0 是可行的下界").isZero();

    assertThatThrownBy(() -> scalars(1L, 80, 0.2, 2, 1, 5000))
        .as("差值为负必须抛")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("fragments");
    assertThat(scalars(1L, 80, 0.2, 2, 2, 5000).fragments()).as("差值为 0 收").isEqualTo(2);
  }

  @Test
  void mapRadiusOneIsAccepted() {
    assertThat(scalars(1L, 1, 0.2, 2, 5, 5000).mapRadius()).isEqualTo(1);
  }

  @Test
  void mapRadiusZeroThrows() {
    assertThatThrownBy(() -> scalars(1L, 0, 0.2, 2, 5, 5000))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mapRadius");
  }

  /** 海平面在 [0,1] 闭区间内可用；越界与 NaN 都抛（NaN 会让"有没有海"恒为 false，是静默的那一种）。 */
  @Test
  void baseSeaLevelRangeChecked() {
    assertThat(scalars(1L, 80, 0.0, 2, 5, 5000).baseSeaLevel()).isEqualTo(0.0);
    assertThat(scalars(1L, 80, 1.0, 2, 5, 5000).baseSeaLevel()).isEqualTo(1.0);
    for (double bad : new double[] {-0.1, 1.1, Double.NaN}) {
      assertThatThrownBy(() -> scalars(1L, 80, bad, 2, 5, 5000))
          .as("baseSeaLevel = %s", bad)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("baseSeaLevel");
    }
  }

  /** 采样缓存上限取 0 时缓存**静默失效**（每写一条就逐出），故同样在构造期挡。 */
  @Test
  void contourCacheMaxZeroThrows() {
    assertThatThrownBy(() -> scalars(1L, 80, 0.2, 2, 5, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("contourCacheMax");
  }

  /**
   * ★ 三个子 record 非 null。**消息必须精确匹配**（形态 2）：{@code Objects.requireNonNull} 的失败消息 恰是字段名，而"没有守卫"时那个
   * null 会在生成期以一条同样含字段名的 JDK 热心 NPE 出现 —— 单看消息分不出两者，判别力全在**"构造期抛不抛"**上。
   */
  @Test
  void nullBandsThrows() {
    GenerationSpec d = GenerationSpec.defaults(1L);
    assertThatThrownBy(() -> subRecords(d, null, d.ridges(), d.fragmentParams()))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("bands");
  }

  /** 见 {@link #nullBandsThrows}。 */
  @Test
  void nullRidgeParamsThrows() {
    GenerationSpec d = GenerationSpec.defaults(1L);
    assertThatThrownBy(() -> subRecords(d, d.bands(), null, d.fragmentParams()))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("ridges");
  }

  /** 见 {@link #nullBandsThrows}。 */
  @Test
  void nullFragmentParamsThrows() {
    GenerationSpec d = GenerationSpec.defaults(1L);
    assertThatThrownBy(() -> subRecords(d, d.bands(), d.ridges(), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("fragmentParams");
  }

  /**
   * ★ 频率面守卫：七个频率**逐个**自证（`assertAll` 让 7 个都在一次运行里响， 于是"某个调用点忘了接守卫"与"守卫本身被删"都会被看见）。
   *
   * <p>为什么频率值得守：取 0 时该带在整张图上退化成同一个常数，这一层地貌**静默消失**。
   */
  @Test
  void noiseFrequencyMustBePositiveAndFinite() {
    assertAll(
        frequencyRejected("shelfFreq", 0.0, 3.5, 8.0, 20.0, 3.5, 0.02, 0.018),
        frequencyRejected("lowFreq", 1.8, 0.0, 8.0, 20.0, 3.5, 0.02, 0.018),
        frequencyRejected("midFreq", 1.8, 3.5, 0.0, 20.0, 3.5, 0.02, 0.018),
        frequencyRejected("highFreq", 1.8, 3.5, 8.0, 0.0, 3.5, 0.02, 0.018),
        frequencyRejected("coastFreq", 1.8, 3.5, 8.0, 20.0, 0.0, 0.02, 0.018),
        frequencyRejected("moistureFreq", 1.8, 3.5, 8.0, 20.0, 3.5, 0.0, 0.018),
        frequencyRejected("warpFreq", 1.8, 3.5, 8.0, 20.0, 3.5, 0.02, 0.0),
        frequencyRejected("shelfFreq", Double.NaN, 3.5, 8.0, 20.0, 3.5, 0.02, 0.018),
        frequencyRejected("shelfFreq", Double.POSITIVE_INFINITY, 3.5, 8.0, 20.0, 3.5, 0.02, 0.018),
        frequencyRejected("moistureFreq", 1.8, 3.5, 8.0, 20.0, 3.5, Double.NaN, 0.018),
        frequencyRejected(
            "moistureFreq", 1.8, 3.5, 8.0, 20.0, 3.5, Double.POSITIVE_INFINITY, 0.018));
  }

  // ── ★ 结构性断言（不依赖对字段语义的判断） ────────────────────────────────────

  /**
   * ★ **两个装饰形参不许复活**（spec §6.4）。
   *
   * <p>{@code worldId}：生成器不需要知道世界 ID（`MapGenerator.java:243-245` 收到却从未读过）。 {@code
   * coastRoughness}：**函数体内从未被引用** —— 它从 WebUI/MCP 一路传到最内层重载后就没有下文， 真正决定海岸的是 {@code coastFreq}。
   *
   * <p>★ 断言走**整棵树**（不只顶层）：{@code coastRoughness} 若被塞进某个子 record， 只看顶层就漏了。
   */
  @Test
  void noWorldIdNoCoastRoughness() {
    assertThat(componentNamesInTree()).doesNotContain("worldId", "coastRoughness");
  }

  /** ★ L7 的落盘前提：参数面里必须真有 {@code seed} 这个组件（且是 {@code long}）。 */
  @Test
  void seedIsAComponent() {
    assertThat(GenerationSpec.class.getRecordComponents())
        .filteredOn(rc -> rc.getName().equals("seed"))
        .singleElement()
        .extracting(RecordComponent::getType)
        .isEqualTo(long.class);
  }

  /**
   * ★★ **U1 的守卫**：高度带只许有一份，持有者是 {@link TerrainCatalog}。
   *
   * <p>两条判据，缺一不可：
   *
   * <ol>
   *   <li>**形态判据**：树里任何类型都不得**同时**持有浮点组件与 {@code String} 组件 ——
   *       也就是"没有任何类型同时知道**一个高度数**与**一个地形名**"。这一条不看名字， 所以"给阈值起个 {@code terrainKey} 之类的名字"绕不过去。
   *   <li>**名字判据**：任何组件名都不得等于词表里的 key（抓"把阈值直接叫 {@code plains}"这种： 它是浮点字段、没有 String，第 1 条抓不到它）。
   * </ol>
   *
   * <p>★ **为什么不能只写第 2 条**：那会是个装饰 —— 变异体给阈值起的名字是 {@code mountainAbove}、 给地形名起的名字是 {@code
   * terrainKey}，**两个都不是** {@code KEYS} 里的字面量，名字判据一条都拦不住。 真正的判据只能是形态（字段类型），不是命名。这一点在 Task 8
   * 的变异实验室里实测过（m8v-4 红在第 1 条）。
   *
   * <p>★ 本类型现在**一个 String 组件都没有**，故第 1 条是"看着松、实际紧"的：它禁止的是 **将来**往参数面里塞地形名。参数面里本来也不该有字符串参数。
   */
  @Test
  void noTerrainHeightThresholds() {
    for (Class<?> type : generationRecordTree()) {
      boolean hasFloatingPoint = false;
      boolean hasString = false;
      for (RecordComponent rc : type.getRecordComponents()) {
        hasFloatingPoint |= rc.getType() == double.class || rc.getType() == float.class;
        hasString |= rc.getType() == String.class;
      }
      assertThat(hasFloatingPoint && hasString)
          .as(
              "%s 同时持有浮点字段与字符串字段 —— 它可能同时知道「一个高度数」与「一个地形名」；" + "地形名与高度带只许由 %s 持有",
              type.getSimpleName(), TerrainCatalog.class.getSimpleName())
          .isFalse();
    }
    assertThat(componentNamesInTree())
        .as("组件名不得等于词表 key（那是「阈值直接叫 plains」的形态）")
        .doesNotContainAnyElementsOf(TerrainCatalog.KEYS);
  }

  /**
   * ★ **R-48-e：{@code spec} 从不 null**。
   *
   * <p>★ 它的身份是**守卫**（钉住"从不 null"这条不变量），**不是"收紧动作"的证明** —— 前提已被推翻：Task 5 的 {@code empty()} 起就非
   * null，本任务**无 null 可收紧**； {@code MapChangeSet.apply} 也不写 null 兜底（Task 8 核实，见报告）。
   */
  @Test
  void specIsNeverNullAfterTask8() {
    assertThat(GameMap.empty().spec()).isNotNull();
    assertThat(GameMap.empty().spec()).isEqualTo(GenerationSpec.defaults(0L));
  }

  /** 等值**逐组件**：任何一个组件不同就不相等，子 record 也按组件参与（record 的 equals 语义，此处钉住它没被换掉）。 */
  @Test
  void equalityIsComponentwise() {
    GenerationSpec base = GenerationSpec.defaults(7L);

    assertThat(base).isEqualTo(GenerationSpec.defaults(7L));
    assertThat(base).hasSameHashCodeAs(GenerationSpec.defaults(7L));
    assertThat(base).isNotEqualTo(scalars(7L, 81, base.baseSeaLevel(), 2, 5, 5000));
    assertThat(base).isNotEqualTo(scalars(7L, 80, 0.3, 2, 5, 5000));
    assertThat(base).isNotEqualTo(scalars(7L, 80, base.baseSeaLevel(), 1, 5, 5000));
    assertThat(base).isNotEqualTo(scalars(7L, 80, base.baseSeaLevel(), 2, 6, 5000));
    assertThat(base).isNotEqualTo(scalars(7L, 80, base.baseSeaLevel(), 2, 5, 4999));

    NoiseBands b = base.bands();
    assertThat(b).as("逐字段重建后相等 ⇒ 等值确实是逐组件的").isEqualTo(noiseWithGamma(b.gamma()));
    assertThat(subRecords(base, base.bands(), base.ridges(), base.fragmentParams()))
        .as("子 record 逐件重建后顶层相等")
        .isEqualTo(base);
    assertThat(
            subRecords(
                base, noiseWithGamma(b.gamma() + 0.01), base.ridges(), base.fragmentParams()))
        .as("★ 只动子 record 里的一个组件，顶层就必须不相等 —— 反向证明子 record 真的参与顶层等值")
        .isNotEqualTo(base);
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────

  /**
   * 逐字段可替换的负例夹具：以 {@link GenerationSpec#defaults(long)} 为底，把**五个标量**整件换掉。
   *
   * <p>★ 之所以让所有负例都走这一个构造点：变异体"给 record 加一个组件"要能推到测试期， 就得把构造点全改到编译得过 ——
   * 构造点越集中，那一轮的红就越干净（否则红的会是一堆编译错误）。
   */
  private static GenerationSpec scalars(
      long seed,
      int mapRadius,
      double baseSeaLevel,
      int mainRidges,
      int fragments,
      int contourCacheMax) {
    GenerationSpec d = GenerationSpec.defaults(seed);
    return new GenerationSpec(
        seed,
        mapRadius,
        baseSeaLevel,
        mainRidges,
        fragments,
        d.bands(),
        d.ridges(),
        d.fragmentParams(),
        contourCacheMax);
  }

  /**
   * 三个子 record 可整件替换的夹具：**标量全部取自 {@code base}**，于是"只换子 record"这件事在顶层可见。
   *
   * <p>★ 取 {@code base} 的标量而不是另写一份字面量：另写那份会让"子 record 参与顶层等值"这条断言顺带 测到标量，红起来分不清是哪种组件的问题。
   */
  private static GenerationSpec subRecords(
      GenerationSpec base, NoiseBands bands, RidgeParams ridges, FragmentParams fragmentParams) {
    return new GenerationSpec(
        base.seed(),
        base.mapRadius(),
        base.baseSeaLevel(),
        base.mainRidges(),
        base.fragments(),
        bands,
        ridges,
        fragmentParams,
        base.contourCacheMax());
  }

  /** 只改 {@code gamma} 一个字段的 {@link NoiseBands}：用于"子 record 的**一个组件**变了 ⇒ 顶层不相等"这条负例。 */
  private static NoiseBands noiseWithGamma(double gamma) {
    NoiseBands b = GenerationSpec.defaults(0L).bands();
    return new NoiseBands(
        b.shelfFreq(),
        b.lowFreq(),
        b.midFreq(),
        b.highFreq(),
        b.coastFreq(),
        b.moistureFreq(),
        b.shelfScale(),
        b.shelfOffset(),
        b.shelfHeightWeight(),
        b.lowWeight(),
        b.midWeight(),
        b.highWeight(),
        b.multiHeightWeight(),
        b.coastAmplitude(),
        gamma,
        b.warpFreq(),
        b.warpAmplitude());
  }

  /** 把七个频率整件替换掉的 {@link NoiseBands} 夹具（其余字段取默认值）。 */
  private static NoiseBands bandsWith(
      double shelfFreq,
      double lowFreq,
      double midFreq,
      double highFreq,
      double coastFreq,
      double moistureFreq,
      double warpFreq) {
    return new NoiseBands(
        shelfFreq,
        lowFreq,
        midFreq,
        highFreq,
        coastFreq,
        moistureFreq,
        0.35,
        0.15,
        0.35,
        0.40,
        0.25,
        0.12,
        0.45,
        0.35,
        0.92,
        warpFreq,
        10.0);
  }

  /** "这个频率必须被构造期挡住"的可执行断言，供 {@code assertAll} 逐个报告。 */
  private static Executable frequencyRejected(
      String name,
      double shelfFreq,
      double lowFreq,
      double midFreq,
      double highFreq,
      double coastFreq,
      double moistureFreq,
      double warpFreq) {
    return () ->
        assertThatThrownBy(
                () ->
                    bandsWith(
                        shelfFreq, lowFreq, midFreq, highFreq, coastFreq, moistureFreq, warpFreq))
            .as("%s 必须被构造期挡住", name)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(name);
  }

  /** 按组件读取器取值（反射，供逐组件比较用）。 */
  private static Object read(GenerationSpec spec, RecordComponent rc) {
    try {
      return rc.getAccessor().invoke(spec);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("读不出组件 " + rc.getName(), e);
    }
  }

  /**
   * {@link GenerationSpec} 及其**全部嵌套 record**（只跟进本仓 {@code map} 包里的 record， 免得顺着 {@code String}/JDK
   * 类型爬出去）。
   */
  private static Set<Class<?>> generationRecordTree() {
    Set<Class<?>> seen = new LinkedHashSet<>();
    Deque<Class<?>> pending = new ArrayDeque<>();
    pending.add(GenerationSpec.class);
    while (!pending.isEmpty()) {
      Class<?> type = pending.poll();
      if (!seen.add(type)) {
        continue;
      }
      for (RecordComponent rc : type.getRecordComponents()) {
        Class<?> component = rc.getType();
        if (component.isRecord() && component.getPackageName().startsWith("io.mosire.simos.map")) {
          pending.add(component);
        }
      }
    }
    return seen;
  }

  /** 整棵树里出现过的全部组件名。 */
  private static Set<String> componentNamesInTree() {
    Set<String> names = new LinkedHashSet<>();
    for (Class<?> type : generationRecordTree()) {
      for (RecordComponent rc : type.getRecordComponents()) {
        names.add(rc.getName());
      }
    }
    return names;
  }
}
