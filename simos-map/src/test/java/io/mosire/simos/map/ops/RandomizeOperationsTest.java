package io.mosire.simos.map.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.block.BlockId;
import io.mosire.simos.map.block.TerrainBlock;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.hex.HexGrid;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.FieldDelta;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * ★★ {@code map.RandomizeRegion} 的领域操作面（M8 spec §二，S5）：任意选区 + 调用方 seed，**确定性**（同 seed 逐字节相同、不同 seed
 * 直方图不同）、只改地形不动高度、空选区/图外格拒绝。
 *
 * <p>夹具初始地形一律取 {@code mountains}——与本类多数用例的 A/B（{@code plains}/{@code desert}）都不同，使每次指派都**真的改变**地形。
 * 选区规模取半径 10（331 格）⇒ 两 seed "逐格巧合全同"的概率可忽略（实测差异见本类断言里的字面值）。
 */
class RandomizeOperationsTest {

  private static final String THIRD = "mountains";

  /**
   * 本类多数用例用的两种地形 = **原固定配方的值**（plains / desert）。★ 这不是"配方还在"，而是**有意锚住**下面那几张 冻结的种子表（直方图逐值 /
   * 块表逐值）——换一组 A/B 就会换掉全部字面值，而那几张表的价值正在于"跨时间同一输入同一结果"。 「两种地形真的由调用方决定」另有一条专门的用例（见 {@link
   * #theCallersTwoTerrainsAreTheOnlyOnesWritten}）。
   */
  private static final String A = "plains";

  private static final String B = "desert";

  private static final int RADIUS = 10;

  private static final long SEED = 7L;

  private static final long OTHER_SEED = 8L;

  // ── 确定性 ──────────────────────────────────────────────────────────────────

  /** 同 base + 同 seed + 同选区两次 ⇒ 变更集与重切后的块表**逐字节相同**（含边界 toString、hexes 迭代序）。 */
  @Test
  void sameSeedRebuildsByteIdenticalBlocks() throws Exception {
    GameMap base = graphOf(RADIUS, THIRD);
    Set<HexCoord> selection = selectionOf(base);

    GameMap first = apply(base, RandomizeOperations.randomize(base, selection, A, B, SEED));
    GameMap second = apply(base, RandomizeOperations.randomize(base, selection, A, B, SEED));

    assertThat(second.terrainBlocks().toString()).isEqualTo(first.terrainBlocks().toString());
    assertThat(SimosObjectMapper.create().writeValueAsString(second.terrainBlocks()))
        .as("序列化后的块表逐字节相同")
        .isEqualTo(SimosObjectMapper.create().writeValueAsString(first.terrainBlocks()));
    assertThat(second.terrainBlocks().keySet())
        .containsExactlyElementsOf(first.terrainBlocks().keySet());
    for (BlockId id : first.terrainBlocks().keySet()) {
      TerrainBlock a = first.terrainBlocks().get(id);
      TerrainBlock b = second.terrainBlocks().get(id);
      assertThat(List.copyOf(b.hexes()))
          .as("块 %s 的 hexes 迭代序", id)
          .containsExactlyElementsOf(List.copyOf(a.hexes()));
      assertThat(b.boundary().toString())
          .as("块 %s 的边界 toString", id)
          .isEqualTo(a.boundary().toString());
    }
  }

  /** ★ 同 seed 两次**直方图逐值相同**。 */
  @Test
  void sameSeedGivesTheSameHistogram() {
    GameMap base = graphOf(RADIUS, THIRD);
    Set<HexCoord> selection = selectionOf(base);

    GameMap first = apply(base, RandomizeOperations.randomize(base, selection, A, B, SEED));
    GameMap second = apply(base, RandomizeOperations.randomize(base, selection, A, B, SEED));

    assertThat(histogram(second)).as("同 seed 两次直方图逐值相同").isEqualTo(histogram(first));
  }

  /** ★ 不同 seed ⇒ **直方图不同**（随机源可被证伪的护栏）。 */
  @Test
  void differentSeedGivesADifferentHistogram() {
    GameMap base = graphOf(RADIUS, THIRD);
    Set<HexCoord> selection = selectionOf(base);

    GameMap of7 = apply(base, RandomizeOperations.randomize(base, selection, A, B, SEED));
    GameMap of8 = apply(base, RandomizeOperations.randomize(base, selection, A, B, OTHER_SEED));

    Map<String, Integer> h7 = histogram(of7);
    Map<String, Integer> h8 = histogram(of8);
    assertThat(h7).as("两个 seed 的直方图必须不同").isNotEqualTo(h8);
    System.out.println("[T6-RANDOMIZE] seed7=" + h7);
    System.out.println("[T6-RANDOMIZE] seed8=" + h8);
  }

  /** 块表键序是 {@link BlockId} 的**规范全序**（{@code TreeMap} 的保证）；{@code HashMap} 装块即破坏它。 */
  @Test
  void blockTableOrderIsTheCanonicalBlockIdOrder() {
    GameMap base = graphOf(RADIUS, THIRD);

    MapChangeSet cs = RandomizeOperations.randomize(base, selectionOf(base), A, B, SEED);

    assertThat(upsertKeys(cs.terrainBlocks()).stream().map(BlockId::parse).toList())
        .as("变更集里 upsert 的块键序 == BlockId 规范全序（HashMap 装块 ⇒ 乱序）")
        .isSortedAccordingTo(BlockId::compareTo);
  }

  // ── 种子表逐值（M3 的人口种子表 / 移动逐值表同形）─────────────────────────────

  /**
   * ★ **种子表逐值**：本夹具（半径 10、331 格、初值 {@code mountains}、选区 326 格）下每个 seed 的**结果逐值冻结**。
   *
   * <p>★ 表里同时钉**直方图**与**块数**，不是冗余：**只钉直方图不判别**——实测 seed=1 与 seed=5 的直方图**完全相同** （{@code desert=161,
   * mountains=5, plains=165}），只有块数分得开（25 vs 28）。少了块数这一列，"忽略 seed"类变 异在 (1,5) 这一对上就漏过去了（下文 {@code
   * seedOneAndFiveShareAHistogram} 把这条当场自证）。
   *
   * <p>表由 `/tmp` 探针在**当前字节**上跑出，非手算（形态 5：「我验过了」与「我记得」分开）。
   */
  @Test
  void seedTableIsFrozenPerValue() {
    GameMap base = graphOf(RADIUS, THIRD);
    Set<HexCoord> selection = selectionOf(base);

    long[] seeds = {0L, 1L, 2L, 3L, 4L, 5L, 7L, 8L};
    int[] deserts = {163, 161, 151, 159, 172, 161, 164, 171};
    int[] mountains = {5, 5, 5, 5, 5, 5, 5, 5};
    int[] plains = {163, 165, 175, 167, 154, 165, 162, 155};
    int[] blocks = {24, 25, 24, 21, 27, 28, 23, 28};

    for (int i = 0; i < seeds.length; i++) {
      GameMap after = apply(base, RandomizeOperations.randomize(base, selection, A, B, seeds[i]));
      assertThat(histogram(after))
          .as("seed=%d 的地形直方图逐值", seeds[i])
          .containsExactly(
              entry("desert", deserts[i]),
              entry("mountains", mountains[i]),
              entry("plains", plains[i]));
      assertThat(after.terrainBlocks()).as("seed=%d 的块数（直方图分不开时靠它）", seeds[i]).hasSize(blocks[i]);
    }
  }

  /** ★ 上表为什么要块数那一列：**当场自证** seed=1 与 seed=5 直方图撞车而块数分得开。 */
  @Test
  void seedOneAndFiveShareAHistogram() {
    GameMap base = graphOf(RADIUS, THIRD);
    Set<HexCoord> selection = selectionOf(base);

    GameMap of1 = apply(base, RandomizeOperations.randomize(base, selection, A, B, 1L));
    GameMap of5 = apply(base, RandomizeOperations.randomize(base, selection, A, B, 5L));

    assertThat(histogram(of1)).as("seed=1 与 seed=5 的直方图实测确实相同").isEqualTo(histogram(of5));
    assertThat(of5.terrainBlocks().size())
        .as("块数分得开 ⇒ 它是被保护断言里必需的维度，别删")
        .isNotEqualTo(of1.terrainBlocks().size());
  }

  /** ★ seed=7 的**块表逐值**（23 块，id 按 {@link BlockId} 全序）——比直方图强，钉住的是**具体划分**本身。 */
  @Test
  void seedSevenBlockTableIsFrozenPerValue() {
    GameMap base = graphOf(RADIUS, THIRD);

    GameMap after = apply(base, RandomizeOperations.randomize(base, selectionOf(base), A, B, SEED));

    assertThat(after.terrainBlocks().keySet().stream().map(BlockId::toString).toList())
        .containsExactly(
            "mountains@-10_0",
            "desert@-10_5",
            "desert@-10_8",
            "desert@-9_0",
            "desert@-8_7",
            "desert@-2_4",
            "desert@-1_10",
            "desert@1_1",
            "desert@7_-9",
            "desert@10_-10",
            "plains@-10_7",
            "plains@-9_-1",
            "plains@-7_10",
            "plains@-5_8",
            "plains@-4_2",
            "plains@0_-10",
            "plains@2_-10",
            "plains@4_-10",
            "plains@5_2",
            "plains@7_2",
            "plains@9_-4",
            "plains@9_0",
            "plains@10_-3");
  }

  // ── 迭代序不进结果 ──────────────────────────────────────────────────────────

  /**
   * ★★ **{@code Set} 的迭代序不进结果**——真实风险向量是"客户端圈选顺序"：{@code MapPayloads.requireHexes} 按 **JSON 数组序**装
   * {@code LinkedHashSet}，故调用方给什么顺序，选区就是什么迭代序。
   *
   * <p>原夹具的 {@code selectionOf} 装的是**升序** {@code LinkedHashSet} ⇒ 迭代序 == 自然序 ⇒ 拿它当替身， "去掉 {@code
   * sorted()}" 这类变异**必存活**（护栏是装饰）。故这里另造两个替身，且**各自先自证迭代序确实不是自然序** ——否则本用例会退化成恒真（形态 1「夹具规模决定判别力」的同族）。
   */
  @Test
  void selectionIterationOrderDoesNotLeakIntoTheResult() throws Exception {
    GameMap base = graphOf(RADIUS, THIRD);
    List<HexCoord> natural = selectionSorted(base);

    List<HexCoord> reversed = new ArrayList<>(natural);
    Collections.reverse(reversed);
    Set<HexCoord> descending = new LinkedHashSet<>(reversed);
    Set<HexCoord> hashed = new HashSet<>(natural);

    assertThat(List.copyOf(descending)).as("替身②必须真的不是自然序（否则本用例恒真）").isNotEqualTo(natural);
    assertThat(List.copyOf(hashed)).as("替身③必须真的不是自然序（否则本用例恒真）").isNotEqualTo(natural);

    String canonical = blocksJson(base, new LinkedHashSet<>(natural));
    assertThat(blocksJson(base, descending)).as("反向迭代序 ⇒ 块表逐字节不变").isEqualTo(canonical);
    assertThat(blocksJson(base, hashed)).as("散列迭代序 ⇒ 块表逐字节不变").isEqualTo(canonical);
  }

  /** 选区的**自然序**列表（替身都以它为参照物）。 */
  private static List<HexCoord> selectionSorted(GameMap base) {
    List<HexCoord> ordered = new ArrayList<>(base.hexes().keySet());
    ordered.sort(HexCoord::compareTo);
    return List.copyOf(ordered.subList(5, ordered.size()));
  }

  /** 按给定选区随机化后，块表的**序列化字节**（比 {@code toString} 更强：含边界数值）。 */
  private static String blocksJson(GameMap base, Set<HexCoord> selection) throws Exception {
    GameMap after = apply(base, RandomizeOperations.randomize(base, selection, A, B, SEED));
    return SimosObjectMapper.create().writeValueAsString(after.terrainBlocks());
  }

  /** 变更集里"增"那一侧的键序（{@code Upsert} 与 {@code Patch} 各一路）。 */
  private static List<String> upsertKeys(FieldDelta<TerrainBlock> delta) {
    if (delta instanceof FieldDelta.Upsert<TerrainBlock> upsert) {
      return new ArrayList<>(upsert.entries().keySet());
    }
    if (delta instanceof FieldDelta.Patch<TerrainBlock> patch) {
      return new ArrayList<>(patch.upserts().entries().keySet());
    }
    throw new IllegalStateException("本用例预期有增的组件，实得: " + delta);
  }

  // ── 只碰选区 + 不动高度 ─────────────────────────────────────────────────────

  /** 选区外一格不动（地形逐值不变）；选区内确实变了；高度**逐格**不变。 */
  @Test
  void onlyTheSelectionChangesAndHeightsSurvive() {
    GameMap base = graphOf(RADIUS, THIRD);
    Set<HexCoord> selection = selectionOf(base);
    Set<HexCoord> outside = new LinkedHashSet<>(base.hexes().keySet());
    outside.removeAll(selection);

    GameMap after = apply(base, RandomizeOperations.randomize(base, selection, A, B, SEED));

    int touched = 0;
    for (HexCoord hex : selection) {
      if (!base.terrainAt(hex).equals(after.terrainAt(hex))) {
        touched++;
      }
    }
    assertThat(touched).as("选区内至少有一格被改（防恒真）").isPositive();
    for (HexCoord hex : outside) {
      assertThat(after.terrainAt(hex)).as("选区外格 %s 地形不变", hex).isEqualTo(THIRD);
    }
    for (HexCoord hex : base.hexes().keySet()) {
      assertThat(after.hexes().get(hex).height())
          .as("格 %s 高度不变", hex)
          .isEqualTo(base.hexes().get(hex).height());
    }
  }

  /** 只有 {@code terrainBlocks} 非 {@code Unchanged}（高度不动 ⇒ {@code hexes} 不动）。 */
  @Test
  void onlyTheTerrainBlocksComponentChanges() {
    GameMap base = graphOf(RADIUS, THIRD);

    MapChangeSet cs = RandomizeOperations.randomize(base, selectionOf(base), A, B, SEED);

    assertThat(cs.terrainBlocks()).isNotInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.hexes()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.regions()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.cities()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.terrainTypes()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.pathways()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.pathwayGroups()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.edges()).isInstanceOf(FieldDelta.Unchanged.class);
  }

  // ── ★ 两种地形由调用方决定（2026-09-24 用户报障的杀点）────────────────────────

  /**
   * ★★ **调用方给什么地形，就只写什么地形**（用户报障：「不管我在上面点击哪个地形，都只能把这片圈着的区域替换为沙漠和平原」）。
   *
   * <p>判别力：夹具初值 {@code mountains}，本用例给 A={@code plateau}、B={@code low_hills}，且这两者与原固定配方的 {@code
   * plains}/{@code desert} **都不同** ⇒ 拿"固定配方"的实现跑，直方图会是 plains/desert 而其断言当场红。
   */
  @Test
  void theCallersTwoTerrainsAreTheOnlyOnesWritten() {
    GameMap base = graphOf(RADIUS, THIRD);
    Set<HexCoord> selection = selectionOf(base);

    GameMap after =
        apply(base, RandomizeOperations.randomize(base, selection, "plateau", "low_hills", SEED));

    Map<String, Integer> hist = histogram(after);
    assertThat(hist)
        .as("选区内只会出现调用方给的两种地形 + 选区外的 mountains")
        .containsOnlyKeys(THIRD, "plateau", "low_hills");
    assertThat(hist.get("plateau")).as("A 侧确有格（防恒真）").isPositive();
    assertThat(hist.get("low_hills")).as("B 侧确有格（防恒真）").isPositive();
    assertThat(hist).as("★ 固定配方（plains/desert）不得再出现").doesNotContainKeys("plains", "desert");
    for (HexCoord hex : selection) {
      assertThat(after.terrainAt(hex)).as("选区内格 %s", hex).isIn("plateau", "low_hills");
    }
  }

  /** ★ A == B 是**合法**输入（占比退化 ⇒ 整区同地形）——这是"想把一片全换成某种地形"的正路。 */
  @Test
  void identicalTerrainsDegenerateToAUniformFill() {
    GameMap base = graphOf(RADIUS, THIRD);
    Set<HexCoord> selection = selectionOf(base);

    GameMap after =
        apply(base, RandomizeOperations.randomize(base, selection, "plateau", "plateau", SEED));

    for (HexCoord hex : selection) {
      assertThat(after.terrainAt(hex)).as("选区全域同地形: %s", hex).isEqualTo("plateau");
    }
    assertThat(histogram(after)).containsOnlyKeys(THIRD, "plateau");
  }

  /** 词表外地形（任一侧）⇒ 拒绝，消息是**词表自己的**（不包不吞），且两条路径都验。 */
  @Test
  void rejectingTerrainOutsideTheCatalog() {
    GameMap base = graphOf(RADIUS, THIRD);
    Set<HexCoord> selection = selectionOf(base);

    assertThatThrownBy(() -> RandomizeOperations.randomize(base, selection, "forest", B, SEED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("未知地形类型: forest");
    assertThatThrownBy(() -> RandomizeOperations.randomize(base, selection, A, "swamp", SEED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("未知地形类型: swamp");
  }

  // ── 负例 ────────────────────────────────────────────────────────────────────

  @Test
  void rejectsEmptySelection() {
    GameMap base = graphOf(RADIUS, THIRD);

    assertThatThrownBy(() -> RandomizeOperations.randomize(base, Set.of(), A, B, SEED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("hexes 不得为空：一条 map.RandomizeRegion 至少要选一格");
  }

  @Test
  void rejectsHexOutsideTheMap() {
    GameMap base = graphOf(RADIUS, THIRD);

    assertThatThrownBy(
            () -> RandomizeOperations.randomize(base, Set.of(new HexCoord(9999, 9999)), A, B, SEED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("hex 不在图上: 9999_9999");
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────

  private static GameMap apply(GameMap base, MapChangeSet cs) {
    return MapChangeSet.apply(cs, base);
  }

  /** 选区：全部格去掉自然序前 5 个（保证确有"选区外"可供断言）。 */
  private static Set<HexCoord> selectionOf(GameMap base) {
    List<HexCoord> ordered = new ArrayList<>(base.hexes().keySet());
    ordered.sort(HexCoord::compareTo);
    return new LinkedHashSet<>(ordered.subList(5, ordered.size()));
  }

  private static Map<String, Integer> histogram(GameMap map) {
    Map<String, Integer> hist = new TreeMap<>();
    for (String terrain : map.terrainIndex().values()) {
      hist.merge(terrain, 1, Integer::sum);
    }
    return hist;
  }

  private static GameMap graphOf(int radius, String terrain) {
    Set<HexCoord> all = HexGrid.withinRadius(new HexCoord(0, 0), radius);
    Map<HexCoord, HexCell> cells = new LinkedHashMap<>();
    double h = 0.3;
    for (HexCoord hex : all) {
      cells.put(hex, new HexCell(h));
      h = h >= 0.9 ? 0.3 : h + 0.03;
    }
    return new GameMap(
        cells,
        TerrainBlocks.uniform(all, terrain),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }
}
