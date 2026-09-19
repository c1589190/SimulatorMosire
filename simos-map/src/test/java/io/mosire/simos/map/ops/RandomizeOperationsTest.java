package io.mosire.simos.map.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * <p>夹具初始地形一律取 {@code mountains}——与固定配方的 {@code plains}/{@code desert} 都不同，使每次指派都**真的改变**地形。
 * 选区规模取半径 10（331 格）⇒ 两 seed "逐格巧合全同"的概率可忽略（实测差异见本类断言里的字面值）。
 */
class RandomizeOperationsTest {

  private static final String THIRD = "mountains";

  private static final int RADIUS = 10;

  private static final long SEED = 7L;

  private static final long OTHER_SEED = 8L;

  // ── 确定性 ──────────────────────────────────────────────────────────────────

  /** 同 base + 同 seed + 同选区两次 ⇒ 变更集与重切后的块表**逐字节相同**（含边界 toString、hexes 迭代序）。 */
  @Test
  void sameSeedRebuildsByteIdenticalBlocks() throws Exception {
    GameMap base = graphOf(RADIUS, THIRD);
    Set<HexCoord> selection = selectionOf(base);

    GameMap first = apply(base, RandomizeOperations.randomize(base, selection, SEED));
    GameMap second = apply(base, RandomizeOperations.randomize(base, selection, SEED));

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

    GameMap first = apply(base, RandomizeOperations.randomize(base, selection, SEED));
    GameMap second = apply(base, RandomizeOperations.randomize(base, selection, SEED));

    assertThat(histogram(second)).as("同 seed 两次直方图逐值相同").isEqualTo(histogram(first));
  }

  /** ★ 不同 seed ⇒ **直方图不同**（随机源可被证伪的护栏）。 */
  @Test
  void differentSeedGivesADifferentHistogram() {
    GameMap base = graphOf(RADIUS, THIRD);
    Set<HexCoord> selection = selectionOf(base);

    GameMap of7 = apply(base, RandomizeOperations.randomize(base, selection, SEED));
    GameMap of8 = apply(base, RandomizeOperations.randomize(base, selection, OTHER_SEED));

    Map<String, Integer> h7 = histogram(of7);
    Map<String, Integer> h8 = histogram(of8);
    assertThat(h7).as("两个 seed 的直方图必须不同").isNotEqualTo(h8);
    System.out.println("[T5-RANDOMIZE] seed7=" + h7);
    System.out.println("[T5-RANDOMIZE] seed8=" + h8);
  }
  /** 块表键序是 {@link BlockId} 的**规范全序**（{@code TreeMap} 的保证）；{@code HashMap} 装块即破坏它。 */
  @Test
  void blockTableOrderIsTheCanonicalBlockIdOrder() {
    GameMap base = graphOf(RADIUS, THIRD);

    MapChangeSet cs = RandomizeOperations.randomize(base, selectionOf(base), SEED);

    assertThat(upsertKeys(cs.terrainBlocks()).stream().map(BlockId::parse).toList())
        .as("变更集里 upsert 的块键序 == BlockId 规范全序（HashMap 装块 ⇒ 乱序）")
        .isSortedAccordingTo(BlockId::compareTo);
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

    GameMap after = apply(base, RandomizeOperations.randomize(base, selection, SEED));

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

    MapChangeSet cs = RandomizeOperations.randomize(base, selectionOf(base), SEED);

    assertThat(cs.terrainBlocks()).isNotInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.hexes()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.regions()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.cities()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.terrainTypes()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.pathways()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.pathwayGroups()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.edges()).isInstanceOf(FieldDelta.Unchanged.class);
  }

  // ── 负例 ────────────────────────────────────────────────────────────────────

  @Test
  void rejectsEmptySelection() {
    GameMap base = graphOf(RADIUS, THIRD);

    assertThatThrownBy(() -> RandomizeOperations.randomize(base, Set.of(), SEED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("hexes 不得为空：一条 map.RandomizeRegion 至少要选一格");
  }

  @Test
  void rejectsHexOutsideTheMap() {
    GameMap base = graphOf(RADIUS, THIRD);

    assertThatThrownBy(
            () -> RandomizeOperations.randomize(base, Set.of(new HexCoord(9999, 9999)), SEED))
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
