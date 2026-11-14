package io.mosire.simos.map.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.City;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.HexLand;
import io.mosire.simos.map.LandComponent;
import io.mosire.simos.map.LandComponentId;
import io.mosire.simos.map.LandTypeArea;
import io.mosire.simos.map.LandTypeId;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.generate.MapWrap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.pathway.EdgeRef;
import io.mosire.simos.map.pathway.EdgeTags;
import io.mosire.simos.map.pathway.Pathway;
import io.mosire.simos.map.pathway.PathwayGroup;
import io.mosire.simos.map.pathway.PathwayId;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.terrain.TerrainCatalog;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** R4：导入改变高度与块，保留身份、土地和全部非导入组件；坏批整体拒绝。 */
class RasterImportOperationsTest {
  private static final HexCoord A = new HexCoord(0, 0);
  private static final HexCoord B = new HexCoord(1, 0);
  private static final HexCoord C = new HexCoord(2, 0);

  @Test
  void mergeAddsAndUpdatesWithoutLosingLandOrOtherComponents() {
    GameMap base = richMap();
    MapChangeSet delta =
        RasterImportOperations.apply(
            base, "merge", List.of(cell(A, "mountains", 0.731), cell(C, "ocean", 0.12)));
    GameMap after = MapChangeSet.apply(delta, base);
    assertThat(after.hexes()).containsOnlyKeys(A, B, C);
    assertThat(after.hexes().get(A).height()).isEqualTo(0.731);
    assertThat(after.hexes().get(A).land()).isEqualTo(base.hexes().get(A).land());
    assertThat(after.hexes().get(B)).isEqualTo(base.hexes().get(B));
    assertThat(after.hexes().get(C).land()).isEqualTo(HexLand.empty());
    assertThat(after.terrainAt(A)).isEqualTo("mountains");
    assertThat(after.terrainAt(C)).isEqualTo("ocean");
    assertThat(after.regions()).isEqualTo(base.regions());
    assertThat(after.cities()).isEqualTo(base.cities());
    assertThat(after.pathways()).isEqualTo(base.pathways());
    assertThat(after.pathwayGroups()).isEqualTo(base.pathwayGroups());
    assertThat(after.edges()).isEqualTo(base.edges());
    assertThat(after.spec()).isEqualTo(base.spec());
    assertThat(after.terrainTypes()).containsAllEntriesOf(base.terrainTypes());
    assertThat(after.terrainTypes()).containsKeys("ocean", "mountains");
    TerrainBlocks.requirePartition(after.hexes(), after.terrainBlocks());
    assertThat(after.terrainBlocks())
        .isEqualTo(TerrainBlocks.split(after.terrainIndex(), after.wrap()));
    GameMap expected =
        new GameMap(
            Map.of(
                A,
                new HexCell(0.731, base.hexes().get(A).land()),
                B,
                base.hexes().get(B),
                C,
                new HexCell(0.12)),
            TerrainBlocks.split(Map.of(A, "mountains", B, "plains", C, "ocean")),
            base.regions(),
            base.cities(),
            after.terrainTypes(),
            base.pathways(),
            base.pathwayGroups(),
            base.edges(),
            base.spec());
    assertThat(MapChangeSet.apply(MapChangeSet.between(base, expected), base)).isEqualTo(expected);
    assertThat(after).isEqualTo(expected);
  }

  @Test
  void patchKeepsUnmentionedHexAndHasAnExactNoOp() {
    GameMap base = richMap();
    GameMap target =
        MapChangeSet.apply(
            RasterImportOperations.apply(base, "patch", List.of(cell(A, "plains", 0.411))), base);
    assertThat(target.hexes().get(A).height()).isEqualTo(0.411);
    assertThat(target.hexes().get(B)).isEqualTo(base.hexes().get(B));
    assertThat(
            RasterImportOperations.apply(target, "patch", List.of(cell(A, "plains", 0.411)))
                .isEmpty())
        .isTrue();
  }

  @Test
  void duplicateCoordinatesAreRejectedInsteadOfLastWriteWinning() {
    rejects("merge", List.of(cell(A, "plains", 0.4), cell(A, "mountains", 0.7)), "重复");
  }

  @Test
  void everyInvalidHeightIsRejectedBeforeAValidEarlierCellCanApply() {
    for (double bad :
        new double[] {
          -0.001, 1.001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
        }) {
      rejects("patch", List.of(cell(B, "mountains", 0.7), cell(A, "plains", bad)), "height");
    }
  }

  @Test
  void unknownTerrainMissingPatchHexAndBadModeRejectTheWholeBatch() {
    rejects("merge", List.of(cell(A, "forest", 0.4)), "地形");
    rejects("patch", List.of(cell(A, "mountains", 0.7), cell(C, "plains", 0.4)), "图外");
    rejects("replace", List.of(cell(A, "plains", 0.4)), "mode");
    rejects("merge", List.of(), "cells");
    rejects("merge", Collections.nCopies(12001, cell(A, "plains", 0.4)), "cells");
  }

  @Test
  void maximumSizedImportAndInclusiveHeightEndpointsAreAccepted() {
    List<RasterImportOperations.Cell> cells =
        java.util.stream.IntStream.range(0, 12000)
            .mapToObj(i -> cell(new HexCoord(i, 0), "plains", i == 0 ? 0 : 1))
            .toList();
    GameMap after =
        MapChangeSet.apply(
            RasterImportOperations.apply(GameMap.empty(), "merge", cells), GameMap.empty());
    assertThat(after.hexes()).hasSize(12000);
    assertThat(after.hexes().get(A).height()).isZero();
    assertThat(after.hexes().get(new HexCoord(11999, 0)).height()).isEqualTo(1);
  }

  @Test
  void wrappedMapAllowsExistingCellsButCannotExpandItsKeys() {
    GameMap base = richMap();
    GenerationSpec d = base.spec();
    GenerationSpec wrapped =
        new GenerationSpec(
            d.seed(),
            d.mapRadius(),
            d.baseSeaLevel(),
            d.mainRidges(),
            d.fragments(),
            d.bands(),
            d.ridges(),
            d.fragmentParams(),
            d.contourCacheMax(),
            new MapWrap(true, 0, 2));
    GameMap map = base.withSpec(wrapped);
    GameMap after =
        MapChangeSet.apply(
            RasterImportOperations.apply(map, "merge", List.of(cell(A, "mountains", 0.7))), map);
    assertThat(after.wrap()).isEqualTo(wrapped.wrap());
    assertThat(after.hexes().keySet()).isEqualTo(map.hexes().keySet());
    assertThatThrownBy(
            () -> RasterImportOperations.apply(map, "merge", List.of(cell(C, "plains", 0.4))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("环绕");
  }

  private static void rejects(String mode, List<RasterImportOperations.Cell> cells, String reason) {
    GameMap base = richMap();
    var before = new LinkedHashMap<>(base.hexes());
    assertThatThrownBy(() -> RasterImportOperations.apply(base, mode, cells))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(reason);
    assertThat(base.hexes()).isEqualTo(before);
  }

  private static RasterImportOperations.Cell cell(HexCoord hex, String terrain, double height) {
    return new RasterImportOperations.Cell(hex, terrain, height);
  }

  private static GameMap richMap() {
    LandTypeArea arable =
        new LandTypeArea(
            LandTypeId.ARABLE,
            List.of(new LandComponent(LandComponentId.parse("arable@0_0:1"), 1234, Map.of())),
            2);
    Map<HexCoord, HexCell> cells =
        Map.of(
            A,
            new HexCell(0.4, new HexLand(Map.of(LandTypeId.ARABLE, arable))),
            B,
            new HexCell(0.42));
    RegionId rid = RegionId.parse("keep-region");
    CityId cid = CityId.parse("keep-city");
    PathwayId pid = PathwayId.parse("keep-road");
    EdgeRef edge = new EdgeRef(A, B);
    return new GameMap(
        cells,
        TerrainBlocks.uniform(cells.keySet(), "plains"),
        Map.of(rid, Region.of(rid, "保持区域", Set.of(A, B), RegionMeta.empty())),
        Map.of(cid, new City(cid, "保持城市", B, rid, Map.of("label", "unchanged"))),
        Map.of("plains", TerrainCatalog.of("plains")),
        Map.of(pid, new Pathway(pid, "保持道路", "road", List.of(edge), Map.of())),
        PathwayGroup.defaults(),
        Map.of(edge, new EdgeTags(Map.of("road", Map.of()))),
        GenerationSpec.defaults(7));
  }
}
