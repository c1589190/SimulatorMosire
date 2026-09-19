package io.mosire.simos.map.block;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.hex.HexGrid;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★ P1 的核心护栏：**分割不变式**（块并集 == hex 全集、两两不交）+ **确定性**（同一 hex 集合必得逐字节相同的块）。
 *
 * <p>形态 1 的落法：正例走**真切分**（{@link TerrainBlocks#split}），故意违规是**手工造的坏块表**（不是改切分器）——
 * 这样"少了/重了"两条各有精确失败消息可断言，且不依赖变异体。
 */
class TerrainBlocksTest {

  private static final HexCoord H_A = new HexCoord(5, 5);

  private static final HexCoord H_B = new HexCoord(0, 0);

  // ── 分割：正例（并集 + 不交）─────────────────────────────────────────────────

  /** ★ 真切分的不变式：并集 == 全部 hex、块 hexes 总数 == 格总数（无重叠）。**两条分开断言**，因为切分器漏一块与吞一格 恰好各打红一条。 */
  @Test
  void splitProducesAPartition() {
    Map<HexCoord, String> terrain =
        terrainOf(
            HexGrid.withinRadius(new HexCoord(0, 0), 2),
            Map.of(new HexCoord(0, 0), "ocean"), // 中心水，四周平原
            "plains");

    Map<BlockId, TerrainBlock> blocks = TerrainBlocks.split(terrain);

    Set<HexCoord> union = new HashSet<>();
    int total = 0;
    for (TerrainBlock block : blocks.values()) {
      union.addAll(block.hexes());
      total += block.hexes().size();
    }
    assertThat(union).as("并集 == 全部 hex（漏一块 ⇒ 有格无主）").isEqualTo(terrain.keySet());
    assertThat(total).as("块 hexes 总和 == 格总数（吞一格 ⇒ 一格属两块）").isEqualTo(terrain.size());
    // 两条块：中心的 ocean + 外圈一整个 plains 连通分量
    assertThat(blocks.keySet())
        .containsExactly(BlockId.parse("ocean@0_0"), BlockId.parse("plains@-2_0"));
  }

  /**
   * ★ **带洞边界**：中心是异地形飞地 ⇒ 外圈的块边界有**两条环**（外轮廓 + 洞）。这是 {@link TerrainBlock} 复用 {@link
   * io.mosire.simos.map.region.RegionBoundary} 的直接收益，逐条断言（不是"看起来像"）。
   */
  @Test
  void boundaryCarriesHoleRings() {
    Map<HexCoord, String> terrain =
        terrainOf(
            HexGrid.withinRadius(new HexCoord(0, 0), 1),
            Map.of(new HexCoord(0, 0), "ocean"),
            "plains");

    Map<BlockId, TerrainBlock> blocks = TerrainBlocks.split(terrain);

    TerrainBlock ring = blocks.get(BlockId.parse("plains@-1_0"));
    assertThat(ring.hexes()).as("外圈 6 格是一个连通分量").hasSize(6);
    assertThat(ring.boundary().rings()).as("带洞：外轮廓一条环 + 中心飞地一条洞环").hasSize(2);
    TerrainBlock hole = blocks.get(BlockId.parse("ocean@0_0"));
    assertThat(hole.boundary().rings()).as("单格块：一条 6 顶点的环，无洞").hasSize(1);
  }

  /** ★ **全部建块（P6）**：相邻两格不同地形 ⇒ 两个各 1 格的块；**没有最小尺寸阈值、没有散格通道**。 */
  @Test
  void everyHexBelongsToABlockEvenWhenTiny() {
    Map<HexCoord, String> terrain = new LinkedHashMap<>();
    terrain.put(H_A, "plains");
    terrain.put(H_B, "desert");

    Map<BlockId, TerrainBlock> blocks = TerrainBlocks.split(terrain);

    assertThat(blocks).hasSize(2);
    assertThat(blocks.get(BlockId.parse("plains@5_5")).hexes()).containsExactly(H_A);
    assertThat(blocks.get(BlockId.parse("desert@0_0")).hexes()).containsExactly(H_B);
  }

  // ── 分割：故意违规（精确失败消息）────────────────────────────────────────────

  /** ★ **违规①：少一块 ⇒ 有 hex 无主**。消息必须精确到那个 hex（否则用例只能弱断言）。 */
  @Test
  void partitionRejectsAnOrphanHex() {
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H_A, new HexCell(0.5));
    hexes.put(H_B, new HexCell(0.5));
    Map<BlockId, TerrainBlock> blocks = TerrainBlocks.uniform(Set.of(H_A), "plains"); // 少了 H_B

    assertThatThrownBy(() -> TerrainBlocks.requirePartition(hexes, blocks))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("hex 0_0 不属于任何地形块（分割不变式要求并集覆盖全部 hex）");
  }

  /** ★ **违规②：一格属两块**。消息给出该 hex 与两个块 id。 */
  @Test
  void partitionRejectsDoubleOwnership() {
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H_A, new HexCell(0.5));
    hexes.put(H_B, new HexCell(0.5));
    Map<BlockId, TerrainBlock> blocks = new LinkedHashMap<>();
    blocks.put(BlockId.of("plains", Set.of(H_A, H_B)), TerrainBlock.of("plains", Set.of(H_A, H_B)));
    blocks.put(BlockId.of("desert", Set.of(H_A)), TerrainBlock.of("desert", Set.of(H_A)));

    assertThatThrownBy(() -> TerrainBlocks.requirePartition(hexes, blocks))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("hex 5_5 同时属于地形块 plains@0_0 与 desert@5_5（分割不变式要求两两不交）");
  }

  /** ★ **违规③：块键与内容不符**（P5 的 id 被换成别的东西时当场响）。 */
  @Test
  void partitionRejectsAMismatchedBlockId() {
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H_A, new HexCell(0.5));
    Map<BlockId, TerrainBlock> blocks = new LinkedHashMap<>();
    blocks.put(BlockId.of("desert", Set.of(H_A)), TerrainBlock.of("plains", Set.of(H_A)));

    assertThatThrownBy(() -> TerrainBlocks.requirePartition(hexes, blocks))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("地形块键 desert@5_5 与块内容不符");
  }

  /** ★ **强制点在 {code GameMap} 构造期**：同一份坏块表经构造器也当场响（不是只有显式调校验器才拦）。 */
  @Test
  void gameMapConstructorEnforcesThePartition() {
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H_A, new HexCell(0.5));
    hexes.put(H_B, new HexCell(0.5));

    assertThatThrownBy(
            () ->
                new GameMap(
                    hexes,
                    TerrainBlocks.uniform(Set.of(H_A), "plains"),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    GenerationSpec.defaults(0L)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("hex 0_0 不属于任何地形块（分割不变式要求并集覆盖全部 hex）");
  }

  // ── 确定性（P5）───────────────────────────────────────────────────────────

  /**
   * ★★ **同一状态两次重建块 ⇒ 逐字节相同**：{@link BlockId} 集合、每个块的 {@code hexes}、边界的 {@code toString()}、 整块的
   * {@code toString()} 四项都比。
   *
   * <p>变异 m3（{@code BlockId} 改用随机/自增序号）在这里红：两项 {@code BlockId} 集合不再相等，且键也彼此不同。
   */
  @Test
  void rebuildIsByteIdentical() {
    Map<HexCoord, String> terrain =
        terrainOf(
            HexGrid.withinRadius(new HexCoord(0, 0), 2),
            Map.of(new HexCoord(0, 0), "ocean"),
            "plains");

    Map<BlockId, TerrainBlock> first = TerrainBlocks.split(terrain);
    Map<BlockId, TerrainBlock> second = TerrainBlocks.split(new LinkedHashMap<>(terrain));

    assertThat(second.keySet())
        .as("两次重建的 BlockId 集合逐项相同")
        .containsExactlyElementsOf(first.keySet());
    for (BlockId id : first.keySet()) {
      TerrainBlock a = first.get(id);
      TerrainBlock b = second.get(id);
      assertThat(b.hexes()).as("块 %s 的 hexes 集合相同", id).isEqualTo(a.hexes());
      assertThat(new ArrayList<>(b.hexes()))
          .as("块 %s 的 hexes **迭代序**也相同（toString 的字节依赖它）", id)
          .isEqualTo(new ArrayList<>(a.hexes()));
      assertThat(b.boundary().toString())
          .as("块 %s 的边界逐字节相同", id)
          .isEqualTo(a.boundary().toString());
      assertThat(b.toString()).as("块 %s 整块逐字节相同", id).isEqualTo(a.toString());
    }
  }

  /** ★ {@link BlockId} 的规范串与 parse 三件套：{@code <terrain>@<最小hex>}，确定性由最小 hex 定序保证。 */
  @Test
  void blockIdIsDeterministicAndRoundTrips() {
    // 入参集合的迭代序故意与自然序相反 —— id 仍取最小 hex
    Set<HexCoord> hexes = new LinkedHashSet<>();
    hexes.add(new HexCoord(3, 1));
    hexes.add(new HexCoord(-5, -59));
    hexes.add(new HexCoord(0, 0));

    BlockId id = BlockId.of("plains", hexes);
    assertThat(id.toString()).isEqualTo("plains@-5_-59");
    assertThat(BlockId.parse("plains@-5_-59")).isEqualTo(id);
    assertThat(BlockId.parse(id.toString())).isEqualTo(id);

    assertThatThrownBy(() -> BlockId.of("plains", Set.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("空 hex 集合");
    assertThatThrownBy(() -> BlockId.parse("plains")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new BlockId("a@b", new HexCoord(0, 0)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得含");
  }

  /** 切分器只把**同地形且六邻连通**的格并成一块：对角线不相邻即分块。 */
  @Test
  void splitUsesSixNeighbourConnectivity() {
    Map<HexCoord, String> terrain = new LinkedHashMap<>();
    terrain.put(H_A, "plains");
    terrain.put(H_B, "plains"); // 与 H_A 相距远，不与任何格相邻

    Map<BlockId, TerrainBlock> blocks = TerrainBlocks.split(terrain);

    assertThat(blocks).as("同地形但不连通 ⇒ 两块").hasSize(2);
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────

  /** 造一张地形表：{@code hexes} 里不在 {@code special} 的格一律取 {@code defaultTerrain}。 */
  private static Map<HexCoord, String> terrainOf(
      Set<HexCoord> hexes, Map<HexCoord, String> special, String defaultTerrain) {
    Map<HexCoord, String> terrain = new LinkedHashMap<>();
    for (HexCoord hex : hexes) {
      terrain.put(hex, special.getOrDefault(hex, defaultTerrain));
    }
    return terrain;
  }
}
