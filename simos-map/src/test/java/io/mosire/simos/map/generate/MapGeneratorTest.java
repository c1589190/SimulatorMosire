package io.mosire.simos.map.generate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.assertAll;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainCatalog;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★ **本类守的是 L7**：seed 随结果落盘、同 spec 必然同图、生成只有一条路径（{@code MapGenerator}）。
 *
 * <p>三条**黄金钉子**（{@link #measuredSeedProfileMatchesReferencePort} / {@link
 * #measuredSeedsProduceEveryCatalogKey} / {@link #hexesAreInNaturalOrder}）不测"结构"而测**具体数值** ——
 * 生成管线是移植来的，结构对而某个系数抄错，只有拿实数钉得住（这正是 GSimulator 的 {@code MapDiff} 教训的另一面： 不断言具体值的不变量会退化成装饰）。
 *
 * <p>★ 反例一律走 {@link #specWithRadius} 这一个构造点（形态同 {@code GenerationSpecTest}）：变异体"给 spec
 * 加一个组件"要能推到测试期，构造点越集中，红的越干净。
 */
class MapGeneratorTest {

  /** 黄金钉子与多数用例的种子。 */
  private static final long SEED = 42L;

  /**
   * 默认参数的 42 号图。**共享一份**：{@code generate} 是纯函数（本类的 {@link #sameSeedGivesIdenticalMap} 就在钉这件事），而一张图
   * 19441 格、生成不便宜； 只读用例共用它，把"确定"这件事留给专门那条用例。
   */
  private static final GameMap SEED_42 = MapGenerator.generate(GenerationSpec.defaults(SEED));

  // ── L7 的四条 ────────────────────────────────────────────────────────────────

  /** ★ L7 的核心：两次独立调用必须逐格相同 —— seed 落盘的意义就是"拿它能再算出一模一样的图"。 */
  @Test
  void sameSeedGivesIdenticalMap() {
    GenerationSpec spec = GenerationSpec.defaults(SEED);

    assertThat(MapGenerator.generate(spec)).isEqualTo(MapGenerator.generate(spec));
  }

  /** 反证：上一条不是因为"所有图都相等"而恒真。 */
  @Test
  void differentSeedGivesDifferentMap() {
    assertThat(MapGenerator.generate(GenerationSpec.defaults(SEED)))
        .isNotEqualTo(MapGenerator.generate(GenerationSpec.defaults(SEED + 1)));
  }

  /** ★ seed 随**结果**落盘（不是"调用方自己记着"）：复现无需任何外部输入。 */
  @Test
  void specIsCarriedOnTheResult() {
    GameMap map = MapGenerator.generate(GenerationSpec.defaults(SEED));

    assertThat(map.spec()).isNotNull();
    assertThat(map.spec().seed()).isEqualTo(SEED);
  }

  /** ★ **参数面的守卫**：能影响结果的每一个输入都在 {@code spec} 里 ⇒ {@code generate} 只有一个形参。 */
  @Test
  void generateHasExactlyOneParameter() {
    Method generate = declaredGenerate();

    assertThat(generate.getParameterCount()).isEqualTo(1);
    assertThat(generate.getParameterTypes()).containsExactly(GenerationSpec.class);
  }

  /**
   * ★ **第二条生成路径的守卫**（反射，不查仓库）：存在性（{@code generate} 恰有一个）、签名（static / 返回 {@link GameMap} / 形参恰为
   * {@link GenerationSpec}），以及**类里没有别的 public static 方法** —— 再长出一个 public static 入口，就是 GSimulator
   * "MCP 路径不写 contour"那种不可复现的岔路。
   */
  @Test
  void noSecondPathToGenerate() {
    Method generate = declaredGenerate();
    assertThat(Modifier.isStatic(generate.getModifiers())).isTrue();
    assertThat(generate.getReturnType()).isEqualTo(GameMap.class);
    assertThat(generate.getParameterTypes()).containsExactly(GenerationSpec.class);

    assertThat(
            Arrays.stream(MapGenerator.class.getDeclaredMethods())
                .filter(
                    m -> Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers()))
                .toList())
        .as("MapGenerator 只许有 generate 这一个 public static 方法")
        .containsExactly(generate);
  }

  // ── 网格形状与逐格不变量 ──────────────────────────────────────────────────────

  /** 半径 r 的六边形球 = {@code 3r(r+1) + 1} 格：r=1 ⇒ 7、r=2 ⇒ 19。 */
  @Test
  void radiusGivesExpectedHexCount() {
    assertThat(MapGenerator.generate(specWithRadius(SEED, 1)).hexes()).hasSize(7);
    assertThat(MapGenerator.generate(specWithRadius(SEED, 2)).hexes()).hasSize(19);
  }

  /**
   * 全部高度落在 [0,1] 且有限。
   *
   * <p>★ 真执行者是 {@link HexCell} 的**构造期**守卫（越界/NaN 会让 {@code generate} 当场抛 IAE，不夹取）——
   * 故这条同时是"生成全图没有一格越界"的**活着证明**，而不是一句重复的断言。
   */
  @Test
  void everyHexHasFiniteHeightInRange() {
    assertThat(SEED_42.hexes().values())
        .allSatisfy(cell -> assertThat(cell.height()).isFinite().isBetween(0.0, 1.0));
  }

  /** 每个格上的地形 key 都在词表里（分类器是总函数，返回**恒**在 {@link TerrainCatalog#KEYS} 内）。 */
  @Test
  void everyHexTerrainIsInCatalog() {
    assertThat(SEED_42.terrainIndex().values().stream().distinct().toList())
        .isSubsetOf(TerrainCatalog.KEYS);
  }

  /**
   * ★ 结果是**词表本身**（不是又抄一份），且**顺序一致** —— {@code terrainTypes} 的迭代序 = 高度升序 = 落盘序，改用 {@code Map.copyOf}
   * 会让同一份数据产出不同字节（GSimulator 的老毛病）。
   */
  @Test
  void terrainTypesComponentIsTheCatalog() {
    assertThat(SEED_42.terrainTypes()).isEqualTo(TerrainCatalog.defaults());
    assertThat(SEED_42.terrainTypes().keySet()).containsExactlyElementsOf(TerrainCatalog.KEYS);
  }

  /**
   * ★ 接 Task 7：生成出的整张图能过变更集往返（{@code apply(between(base, target), base) == target}）。
   *
   * <p>★ base 必须**自带同一个 spec**：{@code spec} 不进变更集（它是生成输入、不是可变更状态，见 {@link MapChangeSet#apply}），只能由
   * base 提供 —— 空图的 spec 是 {@code defaults(0L)}，故要换成本图的。
   */
  @Test
  void generatedMapRoundTripsThroughChangeSet() {
    GameMap base = GameMap.empty().withSpec(GenerationSpec.defaults(SEED));

    assertThat(MapChangeSet.apply(MapChangeSet.between(base, SEED_42), base)).isEqualTo(SEED_42);
  }

  // ── 黄金钉子（实测值） ────────────────────────────────────────────────────────

  /**
   * ★★ **高度管线的保真钉子**：7 个地形各取一格，地形名精确相等、高度差 ≤ {@code 1e-9}。
   *
   * <p>期望值来自**控制器对 GSimulator 管线的独立移植**（{@code placeRidges} + {@code compute} + {@code
   * SimplexNoise}，噪声直接用入参 seed = R-10-a 的变体）；坐标为"逐地形 q 升 r 升首个、且离带边界 ≥ 0.004 的格"。实测七条全部对得上（4
   * 条逐位相同，最大偏差 1.4e-15（13 ulp），远在 1e-9 内）。
   *
   * <p>★ 容差取 1e-9 是**刻意的**：把 {@code wpx * (1.8 / radius)} 写成 {@code wpx * 1.8 / radius} 只差约 1
   * ulp（实测：|px|、|py| ≤ 240 上最大绝对差 7.1e-15、相对差 2.8e-16），**不该红**；而改掉任一系数/项（域扭曲、谷地、大陆架…）会差 ≫ 1e-9 ⇒
   * 必红。
   *
   * <p>★ {@code (-68, 62)} 那行是**水**（h = 0.3236 ≥ 0.30，本该落在 plains 带，而 seaLevel = 0.3344）——
   * 它同时钉住"海平面检查真的在判水"：删掉那一行，这格会变成 plains。
   */
  @Test
  void measuredSeedProfileMatchesReferencePort() {
    assertAll(
        () -> assertCell(-80, 26, "plains", 0.3230012074591211),
        () -> assertCell(-80, 36, "low_hills", 0.5696824967590466),
        () -> assertCell(-80, 38, "mountains", 0.6597175038152930),
        () -> assertCell(-68, 62, "ocean", 0.32359307124203934),
        () -> assertCell(-67, 67, "desert", 0.4629656946763558),
        () -> assertCell(-56, 59, "plateau", 0.7848587387403958),
        () -> assertCell(-46, 34, "plateau_mountains", 0.8679581119465682));
  }

  /**
   * ★★ **词表在图上真的产得出**：种子 12 与 42 两张默认图的 terrain 并集 = 全部 7 个 key。
   *
   * <p>实测依据：48 张默认图（24 种子 × 2 噪声变体）的扫描里，最高带下界在 0.90 时**没有一格**够高（最大 0.8969）⇒ {@code
   * plateau_mountains} 结构性产不出来；改成 0.85 后 24 个种子里有 6 个产得出。下面这两个种子 **各自** 7 项俱全（种子 42 的见证格 {@code
   * (-46, 34)}，种子 12 的 {@code (62, -11)}）。
   *
   * <p>没有这条：湿度恒 0.5（沙漠消失）或最高带被挪回 0.90（高原山地消失）都能**全绿** —— 词表里躺着一条永远 产不出的地形，正是 GSimulator
   * "词表一份、分类器另一份"的旧病。
   */
  @Test
  void measuredSeedsProduceEveryCatalogKey() {
    Set<String> produced = new HashSet<>();
    for (long seed : new long[] {12L, SEED}) {
      produced.addAll(MapGenerator.generate(GenerationSpec.defaults(seed)).terrainIndex().values());
    }

    assertThat(produced).containsExactlyInAnyOrderElementsOf(TerrainCatalog.KEYS);
  }

  /**
   * ★ **枚举序 = 自然序（q 升、r 升）**，逐元素按下标检查。
   *
   * <p>理由（R-10-h）：格子集合取自 {@code HexGrid.withinRadius}，它内部是 {@code Set.copyOf} ⇒
   * 迭代序是**散列槽位序**，跨进程会随哈希盐变（M2 Task 5 实测过）。不排序就落盘，同一张图在两个进程里会产出 不同字节序的地图 —— 字节级往返与 diff 都因此不成立。
   */
  @Test
  void hexesAreInNaturalOrder() {
    assertThat(new ArrayList<>(SEED_42.hexes().keySet()))
        .isSortedAccordingTo(Comparator.naturalOrder());
  }

  // ── 夹具与断言 ───────────────────────────────────────────────────────────────

  /** 只换半径的 spec：其余组件原样取自默认值（负例集中在**这一个**构造点）。 */
  private static GenerationSpec specWithRadius(long seed, int radius) {
    GenerationSpec d = GenerationSpec.defaults(seed);
    return new GenerationSpec(
        seed,
        radius,
        d.baseSeaLevel(),
        d.mainRidges(),
        d.fragments(),
        d.bands(),
        d.ridges(),
        d.fragmentParams(),
        d.contourCacheMax());
  }

  /** 按坐标取格，地形名精确相等 + 高度在容差内。 */
  private static void assertCell(int q, int r, String terrain, double height) {
    HexCell cell = SEED_42.hexes().get(new HexCoord(q, r));
    assertThat(cell).as("格 (%s, %s) 必须在图里", q, r).isNotNull();
    assertThat(SEED_42.terrainAt(new HexCoord(q, r))).as("格 (%s, %s) 的地形", q, r).isEqualTo(terrain);
    assertThat(cell.height()).as("格 (%s, %s) 的高度", q, r).isCloseTo(height, within(1e-9));
  }

  /** 名字为 {@code generate} 的**唯一**那个方法。 */
  private static Method declaredGenerate() {
    List<Method> candidates =
        Arrays.stream(MapGenerator.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("generate"))
            .toList();
    assertThat(candidates).as("名字叫 generate 的方法只能有一个").hasSize(1);
    return candidates.getFirst();
  }
}
