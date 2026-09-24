package io.mosire.simos.map.ops;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.block.BlockId;
import io.mosire.simos.map.block.TerrainBlock;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainHeights;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 地形操作面（M8 spec §二，S4 的"语义化命令"）：**规则放领域模块**，Core 只转发信封（ADR-1 / 铁律 4）。
 *
 * <p>★ **每个操作都是纯函数**（只读 {@code base}，产出走 {@link MapChangeSet}）；变更集**唯一**的生产路径是 {@link
 * MapChangeSet#between(GameMap, GameMap)}——**不做**"操作直接拼增量变更集"的第二条路径（两条路径必然分叉，正是本项目最贵的教训形态）。
 *
 * <p>★ **改地形 = 改权威块 + 重切分**（M9 T6 取代说明）：P1 之后地形不再逐格存（{@code HexCell} 只剩高度）， 故本操作把目标格叠进 {@link
 * GameMap#terrainIndex()} 后**整体重切**（{@link TerrainBlocks#split}），受影响块自然**合并/拆分**。**不逐格写地形**——{@code
 * HexCell} 已无 terrain 字段，编译期就挡。
 *
 * <p>★★ **高度随地形一起写**（2026-09-24 用户裁定，取代此前的"高度一字不动"）：每一格写入 {@link
 * TerrainHeights#paintHeight}（缺省带中点；沙漠 = 平原中点 + 0.005）。理由是手绘出来的格要在格详情里读出与地形相称的高度，
 * 而不是继承上一手地形的旧高度（用户报的正是"高原地形被趋低"这类对不上的现象）。⇒ **{@code hexes} 组件现在可能非 {@code
 * Unchanged}**；判"这条命令是否白写"的口径也随之变成「地形与高度**都**已是目标值」。
 *
 * <p>★ **确定性**：目标格按 {@link HexCoord} 自然序覆盖，{@link TerrainBlocks#split} 用 {@code TreeMap} 全序 ⇒ 同一
 * base + 同一 {@code (hexes, terrain)} 两次必得**逐字节相同**的块表与变更集（{@code BlockId} 集合、{@code hexes} 迭代序、
 * {@code boundary().toString()}）。**不依赖任何 {@code HashMap} 迭代序**。
 *
 * <p>★ **词表 fail-closed**：{@code terrain} 必须 ∈ {@link TerrainCatalog#KEYS}（7
 * 类高度带），**词表外拒绝**——不是兜底色。
 */
public final class TerrainOperations {

  private TerrainOperations() {}

  /**
   * 把 {@code hexes} 里的每一格地形设为 {@code terrain}，**并把该格高度写为 {@link
   * TerrainHeights#paintHeight}**，返回变更集。
   *
   * <p>校验次序（都在算出任何结果之前）：{@code base}/{@code hexes}/{@code terrain} 判空 → **词表** （{@link
   * TerrainCatalog#of}，未知 key 抛它自己的 IAE）→ {@code hexes} 非空 → 每一格都在图上。任一不满足 ⇒ {@link
   * IllegalArgumentException}，命令边界折成 {@code Rejected}。
   *
   * @param base 现图（只读；目标格与原有地形取自它）
   * @param hexes 要改的格；**不得为空**，且每一格都必须在 {@code base.hexes()} 里
   * @param terrain 目标地形 key；必须在 {@link TerrainCatalog} 词表内
   * @return {@code hexes}（高度）与 {@code terrainBlocks} 可能非 {@code Unchanged} 的变更集；目标格**地形与高度都已是目标值**
   *     ⇒ 8 个组件全 {@code Unchanged}
   */
  public static MapChangeSet setTerrain(GameMap base, Set<HexCoord> hexes, String terrain) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(hexes, "hexes");
    Objects.requireNonNull(terrain, "terrain");
    // 调用只为校验：未知 key 由词表自己抛（R-12-h 不包不吞）。
    TerrainCatalog.of(terrain);
    if (hexes.isEmpty()) {
      throw new IllegalArgumentException("hexes 不得为空：一条 map.SetTerrain 至少要改一格");
    }
    // ★ 自然序覆盖：迭代序只由集合内容决定，两次同输入必得同一条覆盖序列（不取 Set 迭代序）。
    List<HexCoord> ordered = new ArrayList<>(hexes);
    ordered.sort(Comparator.naturalOrder());
    // ★ 涂色高度：所有目标格同一值（同一个 terrain 的 paintHeight），与格序无关。
    double paintHeight = TerrainHeights.paintHeight(terrain);
    Map<HexCoord, String> terrainByHex = new LinkedHashMap<>(base.terrainIndex());
    Map<HexCoord, HexCell> nextHexes = new LinkedHashMap<>(base.hexes());
    for (HexCoord hex : ordered) {
      if (!base.hexes().containsKey(hex)) {
        throw new IllegalArgumentException("hex 不在图上: " + hex);
      }
      terrainByHex.put(hex, terrain);
      nextHexes.put(hex, new HexCell(paintHeight));
    }
    Map<BlockId, TerrainBlock> nextBlocks = TerrainBlocks.split(terrainByHex);
    // ★ withHexes/withTerrainBlocks 触发 GameMap 构造期的分割不变式；between 逐组件比较，未动的组件恒 Unchanged。
    return MapChangeSet.between(base, base.withHexes(nextHexes).withTerrainBlocks(nextBlocks));
  }
}
