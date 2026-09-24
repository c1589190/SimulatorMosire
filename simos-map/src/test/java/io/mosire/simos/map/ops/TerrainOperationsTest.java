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
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainHeights;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.FieldDelta;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★ {@code map.SetTerrain} 的领域操作面：**改块 + 重切分**（合并/拆分）、分割不变式、确定性、词表 fail-closed。
 *
 * <p>块表一律经真切分（{@link TerrainBlocks#split}）构造 ⇒ 正例走的是真算法，不是手搓坏块表。
 */
class TerrainOperationsTest {

  private static final HexCoord H0 = new HexCoord(0, 0);

  private static final HexCoord H1 = new HexCoord(1, 0);

  private static final HexCoord H2 = new HexCoord(2, 0);

  // ── 正常：改 1 格 / 改多格（**地形与高度一起写**，2026-09-24 用户裁定）──────────

  /** 改一格：目标格地形变、**高度写成该地形的涂色高度**、未列的格逐值不变；{@code hexes} 组件随之非 Unchanged。 */
  @Test
  void changingOneHexWritesBothTerrainAndThePaintHeight() {
    GameMap base = graphOf(List.of(H0, H1, H2), Map.of(H0, "plains", H1, "plains", H2, "plains"));
    double h0 = base.hexes().get(H0).height();
    double h2 = base.hexes().get(H2).height();

    MapChangeSet cs = TerrainOperations.setTerrain(base, Set.of(H1), "desert");
    GameMap after = MapChangeSet.apply(cs, base);

    assertThat(after.terrainAt(H0)).isEqualTo("plains");
    assertThat(after.terrainAt(H1)).isEqualTo("desert");
    assertThat(after.terrainAt(H2)).isEqualTo("plains");
    assertThat(after.hexes().get(H1).height())
        .as("目标格高度 = 沙漠的涂色高度（= 平原带中点 + 0.005）")
        .isEqualTo(TerrainHeights.paintHeight("desert"));
    assertThat(after.hexes().get(H0).height()).as("未列的格高度逐值不变").isEqualTo(h0);
    assertThat(after.hexes().get(H2).height()).as("未列的格高度逐值不变").isEqualTo(h2);
    assertThat(cs.hexes())
        .as("高度随地形一起写 ⇒ hexes 组件非 Unchanged")
        .isNotInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.terrainBlocks())
        .as("地形改动必须进 terrainBlocks 组件")
        .isNotInstanceOf(FieldDelta.Unchanged.class);
  }

  /** **幂等**：对已是对目标地形（且高度已是涂色高度）的格再涂一次 ⇒ 8 个组件全 Unchanged（不白落一条 revision）。 */
  @Test
  void repaintingTheSameTerrainIsAnExactNoOp() {
    GameMap base = graphOf(List.of(H0), Map.of(H0, "plains"));
    GameMap painted =
        MapChangeSet.apply(TerrainOperations.setTerrain(base, Set.of(H0), "desert"), base);

    MapChangeSet again = TerrainOperations.setTerrain(painted, Set.of(H0), "desert");

    assertThat(again.hexes()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(again.terrainBlocks()).isInstanceOf(FieldDelta.Unchanged.class);
  }

  /** 一条命令改多格：载荷里的每一格都生效（地形 + 高度），未列的格不变。 */
  @Test
  void changingMultipleHexesAppliesToEachOfThem() {
    GameMap base = graphOf(List.of(H0, H1, H2), Map.of(H0, "plains", H1, "plains", H2, "plains"));
    double h1 = base.hexes().get(H1).height();

    MapChangeSet cs = TerrainOperations.setTerrain(base, Set.of(H0, H2), "mountains");
    GameMap after = MapChangeSet.apply(cs, base);

    assertThat(after.terrainAt(H0)).isEqualTo("mountains");
    assertThat(after.terrainAt(H1)).isEqualTo("plains");
    assertThat(after.terrainAt(H2)).isEqualTo("mountains");
    assertThat(after.hexes().get(H0).height())
        .as("两格都写到山地的涂色高度")
        .isEqualTo(TerrainHeights.paintHeight("mountains"));
    assertThat(after.hexes().get(H2).height()).isEqualTo(TerrainHeights.paintHeight("mountains"));
    assertThat(after.hexes().get(H1).height()).as("未列的格高度逐值不变").isEqualTo(h1);
  }

  // ── 重切分：合并与拆分 ──────────────────────────────────────────────────────

  /** **合并**：被隔开的两个同地形块，把中间的格改成同地形 ⇒ 三格连成一块。 */
  @Test
  void fillingTheGapMergesTwoSameTerrainBlocksIntoOne() {
    GameMap base = graphOf(List.of(H0, H1, H2), Map.of(H0, "plains", H1, "desert", H2, "plains"));
    assertThat(base.terrainBlocks()).as("前置：被 desert 隔开的两块 plains + 中间的 desert").hasSize(3);

    MapChangeSet cs = TerrainOperations.setTerrain(base, Set.of(H1), "plains");
    GameMap after = MapChangeSet.apply(cs, base);

    assertThat(after.terrainBlocks()).as("三格连成一块 plains").hasSize(1);
    TerrainBlock block = after.terrainBlocks().get(BlockId.parse("plains@0_0"));
    assertThat(block).as("合并后的块含全部三格").isNotNull();
    assertThat(List.copyOf(block.hexes())).as("hexes 按自然序（逐字节可复现）").containsExactly(H0, H1, H2);
  }

  /** **拆分**：一整块 plains 的中间格改成 desert ⇒ 原块裂成两个 singleton。 */
  @Test
  void carvingTheMiddleSlotSplitsOneBlockIntoTwo() {
    GameMap base = graphOf(List.of(H0, H1, H2), Map.of(H0, "plains", H1, "plains", H2, "plains"));
    assertThat(base.terrainBlocks()).as("前置：一整块").hasSize(1);

    MapChangeSet cs = TerrainOperations.setTerrain(base, Set.of(H1), "desert");
    GameMap after = MapChangeSet.apply(cs, base);

    assertThat(after.terrainBlocks())
        .as("原块裂成两个单格 plains + 一个 desert")
        .containsOnlyKeys(
            BlockId.parse("plains@0_0"), BlockId.parse("desert@1_0"), BlockId.parse("plains@2_0"));
  }

  // ── 分割不变式 ──────────────────────────────────────────────────────────────

  /**
   * 重切后**分割不变式仍成立**（并集 == 全 hex、两两不交），且**块表恰是 {@code terrainIndex()} 的切分**——后者才是"没有只改字段、
   * 忘了重切"的判别点：只把某块的 terrain 字段改写而不重切，块键集就会与真切分分叉。
   */
  @Test
  void theReSplitResultIsStillAPartition() {
    Set<HexCoord> all = HexGrid.withinRadius(new HexCoord(0, 0), 2);
    GameMap base = graphOf(all, uniform(all, "plains"));
    Set<HexCoord> targets = Set.of(new HexCoord(0, 0), new HexCoord(1, 0), new HexCoord(0, 1));

    GameMap after = MapChangeSet.apply(TerrainOperations.setTerrain(base, targets, "desert"), base);

    TerrainBlocks.requirePartition(after.hexes(), after.terrainBlocks());
    assertThat(TerrainBlocks.split(after.terrainIndex()).keySet())
        .as("块表恰是 terrainIndex() 的切分（不重切 ⇒ 键集分叉）")
        .containsExactlyInAnyOrderElementsOf(after.terrainBlocks().keySet());
    for (TerrainBlock block : after.terrainBlocks().values()) {
      for (HexCoord hex : block.hexes()) {
        assertThat(block.terrain())
            .as("块地形 == 该格 terrainAt: %s", hex)
            .isEqualTo(after.terrainAt(hex));
      }
    }
  }

  // ── 确定性 ──────────────────────────────────────────────────────────────────

  /** 块表键序必须是 {@link BlockId} 的**规范全序**（{@code TreeMap} 的保证）；用 {@code HashMap} 装块即破坏它。 */
  @Test
  void blockTableOrderIsTheCanonicalBlockIdOrder() {
    Set<HexCoord> all = HexGrid.withinRadius(new HexCoord(0, 0), 2);
    GameMap base = graphOf(all, uniform(all, "plains"));
    List<HexCoord> ordered = new ArrayList<>(all);
    ordered.sort(HexCoord::compareTo);
    Set<HexCoord> scattered = new LinkedHashSet<>();
    for (int i = 0; i < ordered.size(); i += 2) {
      scattered.add(ordered.get(i));
    }

    GameMap after =
        MapChangeSet.apply(TerrainOperations.setTerrain(base, scattered, "desert"), base);

    assertThat(new ArrayList<>(after.terrainBlocks().keySet()))
        .as("块表键序 == BlockId 规范全序（HashMap 装块 ⇒ 乱序）")
        .isSortedAccordingTo(BlockId::compareTo);
  }

  /** 同一 base + 同一命令两次 ⇒ 变更集与重切后的块表**逐字节相同**（含边界 toString、hexes 迭代序）。 */
  @Test
  void rebuildIsByteIdenticalForTheSameInput() throws Exception {
    Set<HexCoord> all = HexGrid.withinRadius(new HexCoord(0, 0), 2);
    GameMap base = graphOf(all, uniform(all, "plains"));
    Set<HexCoord> targets = Set.of(new HexCoord(0, 0), new HexCoord(1, 0), new HexCoord(0, 1));

    GameMap first = MapChangeSet.apply(TerrainOperations.setTerrain(base, targets, "desert"), base);
    GameMap second =
        MapChangeSet.apply(TerrainOperations.setTerrain(base, targets, "desert"), base);

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

  // ── 词表 fail-closed + 边界 ────────────────────────────────────────────────

  /** 词表外（含真实旧世界用过的 {@code forest}）⇒ 拒绝，消息是词表自己的。 */
  @Test
  void rejectingTerrainOutsideTheCatalog() {
    GameMap base = graphOf(List.of(H0), Map.of(H0, "plains"));

    for (String outside : List.of("forest", "tundra", "swamp", "PLAINS", "")) {
      assertThatThrownBy(() -> TerrainOperations.setTerrain(base, Set.of(H0), outside))
          .as("词表外地形必须拒绝: %s", outside)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("未知地形类型: " + outside);
    }
  }

  /** 词表内 7 类**全部接受**（fail-closed 只挡表外，不误伤表内）。 */
  @Test
  void acceptingEveryCatalogKey() {
    GameMap base = graphOf(List.of(H0), Map.of(H0, "plains"));

    for (String key : TerrainCatalog.KEYS) {
      GameMap after = MapChangeSet.apply(TerrainOperations.setTerrain(base, Set.of(H0), key), base);
      assertThat(after.terrainAt(H0)).as("词表内地形 %s", key).isEqualTo(key);
    }
  }

  @Test
  void rejectingEmptyHexes() {
    GameMap base = graphOf(List.of(H0), Map.of(H0, "plains"));

    assertThatThrownBy(() -> TerrainOperations.setTerrain(base, Set.of(), "plains"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("hexes 不得为空：一条 map.SetTerrain 至少要改一格");
  }

  @Test
  void rejectingHexOutsideTheMap() {
    GameMap base = graphOf(List.of(H0), Map.of(H0, "plains"));

    assertThatThrownBy(
            () -> TerrainOperations.setTerrain(base, Set.of(new HexCoord(9, 9)), "plains"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("hex 不在图上: 9_9");
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────

  /** 用一份 hexes 与一份地形**原子地**建图（P1 之后两者耦合：分割不变式要求块并集 == hex 键集）。 */
  private static GameMap graphOf(Iterable<HexCoord> hexes, Map<HexCoord, String> terrainByHex) {
    Map<HexCoord, HexCell> cells = new LinkedHashMap<>();
    double h = 0.35;
    for (HexCoord hex : hexes) {
      cells.put(hex, new HexCell(h));
      h = h >= 0.9 ? 0.35 : h + 0.05;
    }
    return new GameMap(
        cells,
        TerrainBlocks.split(terrainByHex),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  private static Map<HexCoord, String> uniform(Set<HexCoord> hexes, String terrain) {
    Map<HexCoord, String> byHex = new LinkedHashMap<>();
    List<HexCoord> ordered = new ArrayList<>(hexes);
    ordered.sort(HexCoord::compareTo);
    for (HexCoord hex : ordered) {
      byHex.put(hex, terrain);
    }
    return byHex;
  }
}
