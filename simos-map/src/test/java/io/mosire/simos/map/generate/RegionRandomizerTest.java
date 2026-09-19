package io.mosire.simos.map.generate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.hex.HexGrid;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.util.state.FieldDelta;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★ 本类守的是框选随机化的四件套：**确定性**（同 seed 同结果、不同 seed 不同结果、区域进了随机源派生）、 **占比**（统计口径 +
 * 两个边界值）、**只碰目标区域**（区域外一格不动、只用两种地形、高度不动）、**参数校验** （ratio 范围含 NaN、未知地形 key，且与区域空不空无关）。
 *
 * <p>★ 夹具的初始地形一律取 {@code mountains}——与两种目标地形都不同，使每次指派都**真的改变**地形 （若初始地形与目标重合，同指派会被 equals
 * 折叠成"没变"，differentSeed 等用例就丢了判别力）。
 *
 * <p>★ P1 之后地形是**权威块**：随机化产出的是 {@code terrainBlocks} 变更（{@code hexes} 只承载高度、一律不动）。本类用 {@link
 * #changedTerrains} 从"apply 前后逐格地形"取被改的格——这正是对外的稳定口径（{@code terrainAt}），不依赖块内部形状。
 *
 * <p>★ 实测数字（2026-09-17，本机，{@code /tmp/m12probe} 探针）：大区域图（半径 18 = **1027 格**）seed 7、 ratio 0.5 ⇒ **A
 * 占 559/1027 = 0.5443**；seed 8 ⇒ A 占 512，两 seed 指派不同者 **489** 格；孪生区域图 （半径 6 = 127 格、两区域同 hex 集）同
 * seed 指派不同者 **59** 格；双区域图（半径 3 = 37 格）core 的 7 格全变、rim 30 格逐格不变。
 */
class RegionRandomizerTest {

  private static final long SEED = 7L;

  /** 与 {@link #SEED} 比结果的另一个种子（实测 489 格指派不同，见类注释）。 */
  private static final long OTHER_SEED = 8L;

  private static final String TERRAIN_A = "plains";

  private static final String TERRAIN_B = "desert";

  /** 夹具初始地形：与 A/B 都不同 ⇒ 每次指派都改变地形（见类注释）。 */
  private static final String THIRD = "mountains";

  /** 大区域半径：{@code 3R²+3R+1 = 1027} 格——differentSeed 的"巧合相等"概率 2⁻¹⁰²⁷。 */
  private static final int BIG_RADIUS = 18;

  // ── 占比：统计口径与两个边界值 ───────────────────────────────────────────────

  /**
   * ★ 统计口径（补充文件收的写法①）：大区域一次抽样，实测占比落在 {@code p ± 0.05}。
   *
   * <p>不是恒真形式：变异"忽略 ratioA 恒选 A"在此红（占比变 1.0）。实测 559/1027 = 0.5443（见类注释）。
   */
  @Test
  void ratioIsRespectedStatistically() {
    GameMap map = bigMap();
    Map<String, String> changed =
        changedTerrains(
            map, RegionRandomizer.randomize(map, big(), TERRAIN_A, TERRAIN_B, 0.5, SEED));

    assertThat(changed.size()).as("大区域格数（半径 18）").isEqualTo(1027);
    long countA = changed.values().stream().filter(TERRAIN_A::equals).count();
    assertThat((double) countA / changed.size())
        .as("A 的实测占比（实测 559/1027 = 0.5443）")
        .isCloseTo(0.5, within(0.05));
  }

  /** {@code ratioA = 0}：{@code nextDouble() < 0} 恒假 ⇒ 全 B（无需特判）。 */
  @Test
  void ratioZeroGivesAllB() {
    GameMap map = twoRegionMap();
    Map<String, String> changed =
        changedTerrains(
            map, RegionRandomizer.randomize(map, core(), TERRAIN_A, TERRAIN_B, 0.0, SEED));

    assertThat(changed)
        .as("ratioA=0 ⇒ 7 格全 B")
        .allSatisfy((k, v) -> assertThat(v).isEqualTo(TERRAIN_B));
  }

  /** {@code ratioA = 1}：{@code nextDouble() ∈ [0,1)} 恒小于 1 ⇒ 全 A（无需特判）。 */
  @Test
  void ratioOneGivesAllA() {
    GameMap map = twoRegionMap();
    Map<String, String> changed =
        changedTerrains(
            map, RegionRandomizer.randomize(map, core(), TERRAIN_A, TERRAIN_B, 1.0, SEED));

    assertThat(changed)
        .as("ratioA=1 ⇒ 7 格全 A")
        .allSatisfy((k, v) -> assertThat(v).isEqualTo(TERRAIN_A));
  }

  // ── 确定性 ───────────────────────────────────────────────────────────────────

  /**
   * ★ 同 seed 同区域两次调用 ⇒ 变更集相等（record 值语义）。夹具的 ratioA 严格落在 (0,1)—— 用 0 或 1 时种子无关，这条就恒真。用 1027
   * 格的大区域：变异"用 {@code new Random()}"时两次调用 仍全同的概率是 2⁻¹⁰²⁷。
   */
  @Test
  void sameSeedGivesSameResult() {
    GameMap map = bigMap();

    MapChangeSet first = RegionRandomizer.randomize(map, big(), TERRAIN_A, TERRAIN_B, 0.5, SEED);
    MapChangeSet second = RegionRandomizer.randomize(map, big(), TERRAIN_A, TERRAIN_B, 0.5, SEED);

    assertThat(first).isEqualTo(second);
  }

  /** ★ 同夹具换 seed ⇒ 变更集**不同**——随机源可被证伪的护栏。1027 格下"巧合相等"的概率 2⁻¹⁰²⁷， 实测 489 格指派不同（重合 538）。 */
  @Test
  void differentSeedGivesDifferentResult() {
    GameMap map = bigMap();
    Map<String, String> of7 =
        changedTerrains(
            map, RegionRandomizer.randomize(map, big(), TERRAIN_A, TERRAIN_B, 0.5, SEED));
    Map<String, String> of8 =
        changedTerrains(
            map, RegionRandomizer.randomize(map, big(), TERRAIN_A, TERRAIN_B, 0.5, OTHER_SEED));

    int differing = 0;
    for (Map.Entry<String, String> e : of7.entrySet()) {
      if (!of8.get(e.getKey()).equals(e.getValue())) {
        differing++;
      }
    }
    assertThat(differing).as("两个 seed 指派不同的格数（实测 489）").isEqualTo(489);
  }

  /**
   * ★ 区域进了随机源派生：孪生区域（hex 集**完全相同** ⇒ 变更目标集相同，只有值可分叉）同 seed ⇒ 结果不同。若 RNG 种子不含 region，两条流相同 ⇒ 本条红。实测
   * 59/127 格指派不同——127 格下 "逐格巧合全同"的概率约 0.52¹²⁷ ≈ 10⁻³⁶。
   */
  @Test
  void randomizeIsDeterministicAcrossRegions() {
    GameMap map = twinRegionMap();
    Map<String, String> ofAlpha =
        changedTerrains(
            map,
            RegionRandomizer.randomize(
                map, new RegionId("alpha"), TERRAIN_A, TERRAIN_B, 0.4, SEED));
    Map<String, String> ofBeta =
        changedTerrains(
            map,
            RegionRandomizer.randomize(map, new RegionId("beta"), TERRAIN_A, TERRAIN_B, 0.4, SEED));

    assertThat(ofBeta.keySet()).as("孪生区域键集相同（夹具自证：分叉只能在值上）").isEqualTo(ofAlpha.keySet());

    int differing = 0;
    for (Map.Entry<String, String> e : ofAlpha.entrySet()) {
      if (!ofBeta.get(e.getKey()).equals(e.getValue())) {
        differing++;
      }
    }
    assertThat(differing).as("alpha 与 beta 指派不同的格数（实测 59）").isEqualTo(59);
  }

  // ── 只碰目标区域 ─────────────────────────────────────────────────────────────

  /**
   * ★ 区域外一格都不动：(1) 被改的格**恰为** core 的 7 格（少一格 = 偷懒，多一格 = 越界）且序 = {@code HexCoord} 自然序（处理序）；(2)
   * {@code apply} 后区域外 30 格**逐格 equals 原值**（地形与高度都动不得）。 变异"忽略 region 改全图"在 (1)(2) 都红。
   */
  @Test
  void onlyTargetRegionIsTouched() {
    GameMap map = twoRegionMap();
    RegionId core = core();
    MapChangeSet cs = RegionRandomizer.randomize(map, core, TERRAIN_A, TERRAIN_B, 0.5, SEED);
    Map<String, String> changed = changedTerrains(map, cs);

    assertThat(changed.keySet())
        .as("被改的格恰为 core 的 7 格，序 = 自然序（实测）")
        .containsExactly("-1_0", "-1_1", "0_-1", "0_0", "0_1", "1_-1", "1_0");
    assertThat(cs.hexes())
        .as("P1：改地形不动高度 ⇒ hexes 组件一律 Unchanged")
        .isInstanceOf(FieldDelta.Unchanged.class);

    GameMap applied = MapChangeSet.apply(cs, map);
    int checked = 0;
    for (Map.Entry<HexCoord, HexCell> e : map.hexes().entrySet()) {
      if (!map.regions().get(core).contains(e.getKey())) {
        assertThat(applied.hexes().get(e.getKey()))
            .as("区域外格 %s 必须逐格不变", e.getKey())
            .isEqualTo(e.getValue());
        checked++;
      }
    }
    assertThat(checked).as("区域外被核对过的格数（防恒真：循环必须真的走遍）").isEqualTo(30);
  }

  /** ★ 产出的地形只含 A 与 B（对 apply 后的图断言），且两者都出现——只断言"⊆ {A,B}"时"恒选 A"照样绿， 两条都钉住。实测 A=559、B=468。 */
  @Test
  void onlyTwoTerrainTypesAreUsed() {
    GameMap map = bigMap();
    MapChangeSet cs = RegionRandomizer.randomize(map, big(), TERRAIN_A, TERRAIN_B, 0.5, SEED);
    GameMap after = MapChangeSet.apply(cs, map);

    Set<String> terrains = new LinkedHashSet<>(after.terrainIndex().values());
    assertThat(terrains)
        .as("产出地形恰为 {A, B}——既不多、也不少")
        .containsExactlyInAnyOrder(TERRAIN_A, TERRAIN_B);
  }

  /** ★ 高度逐格不变（区域内外都是）：重分配只改地形，不碰高度。变异"高度也一起改"在此红——夹具高度随 {@code q+r} 变化（见类注释）， 不是全平的。 */
  @Test
  void heightsArePreserved() {
    GameMap map = twoRegionMap();
    MapChangeSet cs = RegionRandomizer.randomize(map, core(), TERRAIN_A, TERRAIN_B, 0.5, SEED);
    GameMap after = MapChangeSet.apply(cs, map);

    int checked = 0;
    for (Map.Entry<HexCoord, HexCell> e : map.hexes().entrySet()) {
      assertThat(after.hexes().get(e.getKey()).height())
          .as("格 %s 的高度不变", e.getKey())
          .isEqualTo(e.getValue().height());
      checked++;
    }
    assertThat(checked).as("全图核对的格数").isEqualTo(37);
  }

  // ── 参数校验 ─────────────────────────────────────────────────────────────────

  /**
   * ★ ratioA 越界（含 **NaN**）⇒ IAE。NaN 那条是 R-12-g 的靶子：判据若写成 {@code ratioA < 0 || ratioA > 1}，NaN
   * 与任何数比较全 false，会静默漏过。另断言**空区域上同样抛** （校验先于计算，与区域空不空无关）。
   */
  @Test
  void rejectsRatioOutOfRange() {
    GameMap map = twoRegionMap();
    for (double bad : new double[] {-0.1, 1.1, Double.NaN}) {
      assertThatThrownBy(
              () -> RegionRandomizer.randomize(map, core(), TERRAIN_A, TERRAIN_B, bad, SEED))
          .as("ratioA=%s", bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(
            () ->
                RegionRandomizer.randomize(
                    emptyShapeMap(), new RegionId("void"), TERRAIN_A, TERRAIN_B, 1.1, SEED))
        .as("空区域上 ratioA 越界同样抛（先校验完再算）")
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** ★ 未知地形 key ⇒ IAE，且消息是 {@link TerrainCatalog} 自己的（"未知地形类型"——不包不吞、不改写）。 A、B 两侧各验一次；空区域上同样抛。 */
  @Test
  void rejectsUnknownTerrainKey() {
    GameMap map = twoRegionMap();
    assertThatThrownBy(() -> RegionRandomizer.randomize(map, core(), "nope", TERRAIN_B, 0.5, SEED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知地形类型");
    assertThatThrownBy(() -> RegionRandomizer.randomize(map, core(), TERRAIN_A, "nope", 0.5, SEED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("未知地形类型");
    assertThatThrownBy(
            () ->
                RegionRandomizer.randomize(
                    emptyShapeMap(), new RegionId("void"), "nope", TERRAIN_B, 0.5, SEED))
        .as("空区域上未知 key 同样抛（先校验完再算）")
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * ★ 三种"没有目标格"的形态 ⇒ **空变更集**（非 null、{@code isEmpty()}、8 个组件逐一 {@code Unchanged}， 不是空
   * Upsert）：空区域（hexes 为空集）、区域存在但 hexes 全在图外、未知 RegionId（**不抛**）。
   */
  @Test
  void returnsEmptyChangeSetOnEmptyRegion() {
    GameMap map = emptyShapeMap();
    for (RegionId id :
        List.of(new RegionId("void"), new RegionId("outside"), new RegionId("ghost"))) {
      MapChangeSet cs = RegionRandomizer.randomize(map, id, TERRAIN_A, TERRAIN_B, 0.5, SEED);
      assertThat(cs).as("%s ⇒ 非 null", id).isNotNull();
      assertThat(cs.isEmpty()).as("%s ⇒ isEmpty()", id).isTrue();
      assertThat(cs.hexes()).as("%s 的 hexes", id).isInstanceOf(FieldDelta.Unchanged.class);
      assertThat(cs.terrainBlocks())
          .as("%s 的 terrainBlocks", id)
          .isInstanceOf(FieldDelta.Unchanged.class);
      assertThat(cs.regions()).as("%s 的 regions", id).isInstanceOf(FieldDelta.Unchanged.class);
      assertThat(cs.cities()).as("%s 的 cities", id).isInstanceOf(FieldDelta.Unchanged.class);
      assertThat(cs.terrainTypes())
          .as("%s 的 terrainTypes", id)
          .isInstanceOf(FieldDelta.Unchanged.class);
      assertThat(cs.pathways()).as("%s 的 pathways", id).isInstanceOf(FieldDelta.Unchanged.class);
      assertThat(cs.pathwayGroups())
          .as("%s 的 pathwayGroups", id)
          .isInstanceOf(FieldDelta.Unchanged.class);
      assertThat(cs.edges()).as("%s 的 edges", id).isInstanceOf(FieldDelta.Unchanged.class);
    }
  }

  // ── 不改输入 ─────────────────────────────────────────────────────────────────

  /**
   * ★ 真靶子：夹具用**调用方持有的可变 {@code LinkedHashMap}**（hexes 与 regions 都是——本类读这两个组件） 构造 {@code
   * GameMap}（构造期拷贝冻结），调用后断言那两份可变 map 内容与尺寸未变。
   */
  @Test
  void doesNotMutateInput() {
    Set<HexCoord> coreSet = HexGrid.withinRadius(new HexCoord(0, 0), 1);
    Map<HexCoord, HexCell> heldHexes = new LinkedHashMap<>();
    for (HexCoord c : HexGrid.withinRadius(new HexCoord(0, 0), 3).stream().sorted().toList()) {
      heldHexes.put(c, new HexCell((c.q() + c.r() + 3) / 6.0));
    }
    Map<RegionId, Region> heldRegions = new LinkedHashMap<>();
    heldRegions.put(core(), Region.of(core(), "核心", coreSet, null));
    Map<HexCoord, HexCell> hexesBefore = new LinkedHashMap<>(heldHexes);
    Map<RegionId, Region> regionsBefore = new LinkedHashMap<>(heldRegions);
    GameMap map =
        new GameMap(
            heldHexes,
            TerrainBlocks.uniform(heldHexes.keySet(), THIRD),
            heldRegions,
            Map.of(),
            TerrainCatalog.defaults(),
            Map.of(),
            Map.of(),
            Map.of(),
            GenerationSpec.defaults(0L));

    RegionRandomizer.randomize(map, core(), TERRAIN_A, TERRAIN_B, 0.5, SEED);

    assertThat(heldHexes).as("调用方持有的可变 hexes 必须原样").isEqualTo(hexesBefore);
    assertThat(heldRegions).as("调用方持有的可变 regions 必须原样").isEqualTo(regionsBefore);
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────

  /** 大区域图：半径 18（1027 格），单一区域 "big" 覆盖全图。 */
  private static GameMap bigMap() {
    return singleRegionMap("big", "大区域", BIG_RADIUS);
  }

  /** 双区域图：半径 3（37 格），core = 距中心 ≤ 1 的 7 格，rim = 其余 30 格。 */
  private static GameMap twoRegionMap() {
    HexCoord center = new HexCoord(0, 0);
    Set<HexCoord> coreSet = HexGrid.withinRadius(center, 1);
    Set<HexCoord> rimSet = new LinkedHashSet<>(HexGrid.withinRadius(center, 3));
    rimSet.removeAll(coreSet);
    Map<RegionId, Region> regions = new LinkedHashMap<>();
    regions.put(core(), Region.of(core(), "核心", coreSet, null));
    regions.put(new RegionId("rim"), Region.of(new RegionId("rim"), "外环", rimSet, null));
    return singleTerrainMap(hexesOf(3), regions);
  }

  /**
   * 孪生区域图：半径 6（127 格），alpha 与 beta 的 hex 集**完全相同**（见 {@link
   * #randomizeIsDeterministicAcrossRegions}）。
   */
  private static GameMap twinRegionMap() {
    Set<HexCoord> all = new LinkedHashSet<>(HexGrid.withinRadius(new HexCoord(0, 0), 6));
    Map<RegionId, Region> regions = new LinkedHashMap<>();
    regions.put(new RegionId("alpha"), Region.of(new RegionId("alpha"), "甲", all, null));
    regions.put(new RegionId("beta"), Region.of(new RegionId("beta"), "乙", all, null));
    return singleTerrainMap(hexesOf(6), regions);
  }

  /** 空形态图：半径 1（7 格），含空区域 void、全在图外的 outside；ghost 不存在（未知 id 形态）。 */
  private static GameMap emptyShapeMap() {
    Set<HexCoord> outside = Set.of(new HexCoord(50, 50), new HexCoord(51, 50));
    Map<RegionId, Region> regions = new LinkedHashMap<>();
    regions.put(new RegionId("void"), Region.of(new RegionId("void"), "空域", Set.of(), null));
    regions.put(new RegionId("outside"), Region.of(new RegionId("outside"), "图外", outside, null));
    return singleTerrainMap(hexesOf(1), regions);
  }

  private static GameMap singleRegionMap(String id, String name, int radius) {
    Set<HexCoord> all = new LinkedHashSet<>(HexGrid.withinRadius(new HexCoord(0, 0), radius));
    Map<RegionId, Region> regions = new LinkedHashMap<>();
    regions.put(new RegionId(id), Region.of(new RegionId(id), name, all, null));
    return singleTerrainMap(hexesOf(radius), regions);
  }

  /** 单地形（{@link #THIRD}）的图：hexes 与 terrainBlocks **原子**构造（两者受分割不变式约束）。 */
  private static GameMap singleTerrainMap(
      Map<HexCoord, HexCell> hexes, Map<RegionId, Region> regions) {
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(hexes.keySet(), THIRD),
        regions,
        Map.of(),
        TerrainCatalog.defaults(),
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  /**
   * 格内容：初始地形全 {@link #THIRD}（进块），高度随 {@code (q + r)} 变化（{(q+r+radius)}/{2radius} ∈ [0,1]）。
   * 高度必须**不全等**——否则"高度不动"的断言在变异（写死 0.5）下是恒真的。
   */
  private static Map<HexCoord, HexCell> hexesOf(int radius) {
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    for (HexCoord c : HexGrid.withinRadius(new HexCoord(0, 0), radius).stream().sorted().toList()) {
      hexes.put(c, new HexCell((c.q() + c.r() + radius) / (2.0 * radius)));
    }
    return hexes;
  }

  private static RegionId core() {
    return new RegionId("core");
  }

  private static RegionId big() {
    return new RegionId("big");
  }

  /**
   * 从变更集取"逐格地形真的变了的那些格"（key = {@code "q_r"}、value = 新地形，序 = {@code map.hexes()} 的序）。
   *
   * <p>★ 走 {@code apply} 前后 {@link GameMap#terrainAt(HexCoord)} 对表——这是 P1 的**稳定对外口径**， 不依赖块/BlockId
   * 的内部形状（那些是 simos-map 的实现细节）。
   */
  private static Map<String, String> changedTerrains(GameMap map, MapChangeSet cs) {
    GameMap after = MapChangeSet.apply(cs, map);
    Map<String, String> changed = new LinkedHashMap<>();
    for (HexCoord hex : map.hexes().keySet()) {
      String before = map.terrainAt(hex);
      String now = after.terrainAt(hex);
      if (!before.equals(now)) {
        changed.put(hex.toString(), now);
      }
    }
    return changed;
  }
}
