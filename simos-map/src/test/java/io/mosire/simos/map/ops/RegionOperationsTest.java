package io.mosire.simos.map.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionBoundary;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.util.state.FieldDelta;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★ M8 T4 的领域操作面：{@code map.CreateRegion}/{@code UpdateRegion}/{@code DeleteRegion}。
 *
 * <p>判别点：**重叠一律允许**（M8-U1，本任务的方向性护栏）、存在性（重复 id / 目标不存在拒绝）、边界自洽（{@code Region.of}
 * 重算，不手造）、空集/图外拒绝、逐组件独立性（只有 {@code regions} 非 {@code Unchanged}）、删除后从属不悬空。
 */
class RegionOperationsTest {

  private static final HexCoord H0 = new HexCoord(0, 0);

  private static final HexCoord H1 = new HexCoord(1, 0);

  private static final HexCoord H2 = new HexCoord(2, 0);

  private static final RegionId R1 = new RegionId("r1");

  private static final RegionId R2 = new RegionId("r2");

  // ── Create ────────────────────────────────────────────────────────────────

  /** 新建区域：内容与边界都对、只有 {@code regions} 非 {@code Unchanged}、派生索引即时可见。 */
  @Test
  void createRegionAddsItAndOnlyTheRegionsComponentChanges() {
    GameMap base = mapOf(Set.of(H0, H1, H2), Map.of());

    MapChangeSet cs = RegionOperations.createRegion(base, R1, "甲", Set.of(H0, H1), null);
    GameMap after = MapChangeSet.apply(cs, base);

    Region created = after.regions().get(R1);
    assertThat(created).as("新区域已入 regions").isNotNull();
    assertThat(created.name()).isEqualTo("甲");
    assertThat(created.hexes()).containsExactlyInAnyOrder(H0, H1);
    assertThat(created.meta()).as("meta 缺席 ⇒ 空元数据").isEqualTo(RegionMeta.empty());
    assertThat(created.boundary())
        .as("边界恰由 hexes 算出（Region.of，不手造）")
        .isEqualTo(RegionBoundary.of(Set.of(H0, H1)));

    assertThat(after.regionIndex().regionOf(H0)).containsExactly(R1);
    assertThat(after.regionIndex().regionOf(H1)).containsExactly(R1);
    assertThat(after.regionIndex().regionOf(H2)).as("未列入的格无从属").isEmpty();

    assertThat(cs.regions()).as("regions 必须变").isNotInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.hexes()).as("高度组件不受区域改动影响").isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.terrainBlocks()).as("地形块组件不受区域改动影响").isInstanceOf(FieldDelta.Unchanged.class);
  }

  /** ★★ **核心正例**：新区域与已有区域重叠 ⇒ **成功**，重叠格同时列出两者（字典序）。 */
  @Test
  void createRegionOverlappingAnExistingOneIsAllowed() {
    GameMap base = mapOf(Set.of(H0, H1, H2), Map.of(R1, region("r1", Set.of(H0, H1))));
    assertThat(base.regionIndex().regionOf(H1)).as("前置：H1 已属 r1").containsExactly(R1);

    MapChangeSet cs = RegionOperations.createRegion(base, R2, "乙", Set.of(H1, H2), null);
    GameMap after = MapChangeSet.apply(cs, base);

    assertThat(after.regions().keySet()).as("两个区域并存（不覆盖、不报错）").containsExactly(R1, R2);
    assertThat(after.regionIndex().regionOf(H1))
        .as("★ 重叠格同时属于两者，按 RegionId 字典序")
        .containsExactly(R1, R2);
    assertThat(after.regionIndex().regionOf(H0)).containsExactly(R1);
    assertThat(after.regionIndex().regionOf(H2)).containsExactly(R2);
  }

  /** 已存在 id ⇒ 拒绝，**不静默覆盖**（原区域一字不动）。 */
  @Test
  void createRegionRejectsDuplicateId() {
    GameMap base = mapOf(Set.of(H0, H1), Map.of(R1, region("r1", Set.of(H0))));

    assertThatThrownBy(() -> RegionOperations.createRegion(base, R1, "覆盖", Set.of(H1), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("区域已存在: r1");
    assertThat(base.regions().get(R1).hexes()).as("base 未被改动（纯函数）").containsExactly(H0);
  }

  @Test
  void createRegionRejectsEmptyHexes() {
    GameMap base = mapOf(Set.of(H0), Map.of());

    assertThatThrownBy(() -> RegionOperations.createRegion(base, R1, "甲", Set.of(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("hexes 不得为空：一个区域至少要有一格");
  }

  @Test
  void createRegionRejectsHexOutsideTheMap() {
    GameMap base = mapOf(Set.of(H0), Map.of());

    assertThatThrownBy(
            () -> RegionOperations.createRegion(base, R1, "甲", Set.of(new HexCoord(9, 9)), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("hex 不在图上: 9_9");
  }

  @Test
  void createRegionKeepsTheGivenMeta() {
    GameMap base = mapOf(Set.of(H0), Map.of());
    RegionMeta meta = new RegionMeta("#abc", "Nation", "描述", "r1");

    GameMap after =
        MapChangeSet.apply(RegionOperations.createRegion(base, R1, "甲", Set.of(H0), meta), base);

    assertThat(after.regions().get(R1).meta()).isEqualTo(meta);
  }

  // ── Update ────────────────────────────────────────────────────────────────

  /** 改 hexes ⇒ 内容变、边界**重算**、派生索引跟着变；只有 regions 组件非 Unchanged。 */
  @Test
  void updateRegionChangesHexesAndRecomputesBoundary() {
    GameMap base = mapOf(Set.of(H0, H1, H2), Map.of(R1, region("r1", Set.of(H0, H1))));
    RegionBoundary before = base.regions().get(R1).boundary();

    MapChangeSet cs = RegionOperations.updateRegion(base, R1, Set.of(H2), null);
    GameMap after = MapChangeSet.apply(cs, base);

    Region updated = after.regions().get(R1);
    assertThat(updated.hexes()).containsExactly(H2);
    assertThat(updated.boundary())
        .as("边界随 hexes 重算（与旧边界不同）")
        .isEqualTo(RegionBoundary.of(Set.of(H2)))
        .isNotEqualTo(before);
    assertThat(after.regionIndex().regionOf(H0)).as("旧格已无从属").isEmpty();
    assertThat(after.regionIndex().regionOf(H1)).isEmpty();
    assertThat(after.regionIndex().regionOf(H2)).containsExactly(R1);

    assertThat(cs.regions()).isNotInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.hexes()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.terrainBlocks()).isInstanceOf(FieldDelta.Unchanged.class);
  }

  /** 只改 meta ⇒ hexes 与边界一字不动。 */
  @Test
  void updateRegionChangesMetaOnlyAndLeavesHexesAndBoundaryAlone() {
    GameMap base = mapOf(Set.of(H0, H1), Map.of(R1, region("r1", Set.of(H0, H1))));
    Region before = base.regions().get(R1);
    RegionMeta meta = new RegionMeta("#0f0", "Nation", null, null);

    GameMap after = MapChangeSet.apply(RegionOperations.updateRegion(base, R1, null, meta), base);

    Region updated = after.regions().get(R1);
    assertThat(updated.meta()).isEqualTo(meta);
    assertThat(updated.hexes()).isEqualTo(before.hexes());
    assertThat(updated.boundary()).isEqualTo(before.boundary());
  }

  /** 二者都给 ⇒ 都改。 */
  @Test
  void updateRegionChangesBothHexesAndMeta() {
    GameMap base = mapOf(Set.of(H0, H1, H2), Map.of(R1, region("r1", Set.of(H0))));
    RegionMeta meta = new RegionMeta("#f00", "Tag", "d", null);

    GameMap after =
        MapChangeSet.apply(RegionOperations.updateRegion(base, R1, Set.of(H1, H2), meta), base);

    Region updated = after.regions().get(R1);
    assertThat(updated.hexes()).containsExactlyInAnyOrder(H1, H2);
    assertThat(updated.meta()).isEqualTo(meta);
    assertThat(updated.boundary()).isEqualTo(RegionBoundary.of(Set.of(H1, H2)));
  }

  /** ★ **改 hexes 使它与别的区域重叠 ⇒ 允许**。 */
  @Test
  void updateRegionMakingItOverlapAnotherIsAllowed() {
    GameMap base =
        mapOf(
            Set.of(H0, H1, H2), Map.of(R1, region("r1", Set.of(H0)), R2, region("r2", Set.of(H2))));

    GameMap after =
        MapChangeSet.apply(RegionOperations.updateRegion(base, R1, Set.of(H1, H2), null), base);

    assertThat(after.regionIndex().regionOf(H2))
        .as("★ 改后 H2 同时属 r1 与 r2（重叠不报错）")
        .containsExactly(R1, R2);
    assertThat(after.regions()).hasSize(2);
  }

  @Test
  void updateRegionRejectsMissingTarget() {
    GameMap base = mapOf(Set.of(H0), Map.of());

    assertThatThrownBy(() -> RegionOperations.updateRegion(base, R1, Set.of(H0), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("区域不存在: r1");
  }

  @Test
  void updateRegionRejectsWhenNeitherHexesNorMetaIsGiven() {
    GameMap base = mapOf(Set.of(H0), Map.of(R1, region("r1", Set.of(H0))));

    assertThatThrownBy(() -> RegionOperations.updateRegion(base, R1, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("map.UpdateRegion 必须至少给 hexes 与 meta 之一");
  }

  @Test
  void updateRegionRejectsEmptyHexes() {
    GameMap base = mapOf(Set.of(H0), Map.of(R1, region("r1", Set.of(H0))));

    assertThatThrownBy(() -> RegionOperations.updateRegion(base, R1, Set.of(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("hexes 不得为空：一个区域至少要有一格");
  }

  @Test
  void updateRegionRejectsHexOutsideTheMap() {
    GameMap base = mapOf(Set.of(H0), Map.of(R1, region("r1", Set.of(H0))));

    assertThatThrownBy(
            () -> RegionOperations.updateRegion(base, R1, Set.of(new HexCoord(9, 9)), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("hex 不在图上: 9_9");
  }

  // ── Delete ────────────────────────────────────────────────────────────────

  /** 删区域 ⇒ 从 regions 消失；**原属它、且只属它的格变成无从属**；与别的区域共有的格仍留有那一属。 */
  @Test
  void deleteRegionRemovesItAndDoesNotDangleOwnership() {
    GameMap base =
        mapOf(
            Set.of(H0, H1), Map.of(R1, region("r1", Set.of(H0, H1)), R2, region("r2", Set.of(H1))));

    MapChangeSet cs = RegionOperations.deleteRegion(base, R1);
    GameMap after = MapChangeSet.apply(cs, base);

    assertThat(after.regions().keySet()).as("只剩 r2").containsExactly(R2);
    assertThat(after.regionIndex().regionOf(H0)).as("★ 只属 r1 的格 ⇒ 无从属，不悬空").isEmpty();
    assertThat(after.regionIndex().regionOf(H1)).as("共有的格仍归 r2").containsExactly(R2);

    assertThat(cs.regions()).isNotInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.hexes()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.terrainBlocks()).isInstanceOf(FieldDelta.Unchanged.class);
  }

  @Test
  void deleteRegionRejectsMissingTarget() {
    GameMap base = mapOf(Set.of(H0), Map.of());

    assertThatThrownBy(() -> RegionOperations.deleteRegion(base, R1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("区域不存在: r1");
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────

  private static Region region(String id, Set<HexCoord> hexes) {
    return Region.of(new RegionId(id), id, hexes, RegionMeta.empty());
  }

  private static GameMap mapOf(Set<HexCoord> hexes, Map<RegionId, Region> regions) {
    Map<HexCoord, HexCell> cells = new LinkedHashMap<>();
    double h = 0.35;
    List<HexCoord> ordered = new java.util.ArrayList<>(hexes);
    ordered.sort(HexCoord::compareTo);
    for (HexCoord hex : ordered) {
      cells.put(hex, new HexCell(h));
      h = h >= 0.9 ? 0.35 : h + 0.05;
    }
    return new GameMap(
        cells,
        TerrainBlocks.uniform(hexes, "plains"),
        regions,
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }
}
