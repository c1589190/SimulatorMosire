package io.mosire.simos.map.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.City;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.codec.MapCodec;
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
import io.mosire.simos.util.state.FieldDelta;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * T3 {@code EdgeOperations.connectChain}：有序相邻链 merge/remove 局部语义、props/其他 kind/外部边保留、幂等、复合支流、
 * 闭环、整批拒绝、环绕邻接、坐标极值不溢出、只有 edges 变化与 codec 往返。
 */
class ConnectChainOperationsTest {

  private static final HexCoord A = new HexCoord(0, 0);
  private static final HexCoord B = new HexCoord(1, 0);
  private static final HexCoord C = new HexCoord(1, 1);
  private static final HexCoord D = new HexCoord(2, 1);
  private static final HexCoord E = new HexCoord(3, 1);

  /** 闭环用的第三点：A(0,0)-B(1,0)-T(0,1)-A 三边互异且首尾相邻。 */
  private static final HexCoord T = new HexCoord(0, 1);

  /** 图外远格：只用于"链外既有边不得被 remove 全图剥离"。 */
  private static final HexCoord OUT = new HexCoord(8, 8);

  @Test
  void mergeOnlyAddsTheNamedKindAndKeepsEveryOtherEdgeKindAndProps() {
    GameMap base = richMap();
    EdgeRef ab = new EdgeRef(A, B);
    EdgeRef bc = new EdgeRef(B, C);
    EdgeRef cd = new EdgeRef(C, D);
    EdgeRef dOut = new EdgeRef(D, OUT);

    GameMap after =
        apply(base, EdgeOperations.connectChain(base, "river", List.of(A, B, C), "merge"));

    assertThat(after.edges().get(ab).byPathway())
        .as("同 kind 既有 props 保留；其他 kind 不动")
        .containsEntry("river", Map.of("depth", 2))
        .containsEntry("road", Map.of("width", 3));
    assertThat(after.edges().get(bc).byPathway()).containsOnlyKeys("river");
    assertThat(after.edges().get(bc).byPathway().get("river")).isEqualTo(Map.of());
    assertThat(after.edges().get(cd)).as("链外既有边逐值不动").isEqualTo(base.edges().get(cd));
    assertThat(after.edges().get(dOut)).as("链外 river 边逐值不动").isEqualTo(base.edges().get(dOut));
    assertThat(after.edges()).containsOnlyKeys(ab, bc, cd, dOut);

    assertThat(after.hexes()).isEqualTo(base.hexes());
    assertThat(after.regions()).isEqualTo(base.regions());
    assertThat(after.cities()).isEqualTo(base.cities());
    assertThat(after.pathways()).isEqualTo(base.pathways());
    assertThat(after.pathwayGroups()).isEqualTo(base.pathwayGroups());
    assertThat(after.spec()).isEqualTo(base.spec());
  }

  @Test
  void removeOnlyStripsTheNamedKindFromTheNamedEdgesAndDeletesEmptyTagsEdges() {
    GameMap base = richMap();
    EdgeRef ab = new EdgeRef(A, B);
    EdgeRef bc = new EdgeRef(B, C);
    EdgeRef cd = new EdgeRef(C, D);
    EdgeRef dOut = new EdgeRef(D, OUT);

    GameMap after =
        apply(base, EdgeOperations.connectChain(base, "river", List.of(A, B, C), "remove"));

    assertThat(after.edges().get(ab).byPathway())
        .as("只摘 river，road props 原样")
        .containsOnlyKeys("road")
        .containsEntry("road", Map.of("width", 3));
    assertThat(after.edges()).as("B-C 只剩 river ⇒ 整个边条目删除").doesNotContainKey(bc);
    assertThat(after.edges().get(cd)).isEqualTo(base.edges().get(cd));
    assertThat(after.edges().get(dOut)).isEqualTo(base.edges().get(dOut));
    assertThat(after.edges()).containsOnlyKeys(ab, cd, dOut);
  }

  @Test
  void repeatedMergeIsIdempotentAndCompoundBranchesKeepEarlierCalls() {
    GameMap base = richMap();
    GameMap first =
        apply(base, EdgeOperations.connectChain(base, "river", List.of(A, B, C), "merge"));
    MapChangeSet secondDelta =
        EdgeOperations.connectChain(first, "river", List.of(A, B, C), "merge");
    assertThat(secondDelta.isEmpty()).as("重复调用幂等").isTrue();
    assertThat(apply(first, secondDelta)).isEqualTo(first);

    GameMap branch =
        apply(first, EdgeOperations.connectChain(first, "river", List.of(C, D, E), "merge"));
    assertThat(branch.edges())
        .containsKeys(new EdgeRef(A, B), new EdgeRef(B, C), new EdgeRef(C, D), new EdgeRef(D, E));
    assertThat(branch.edges().get(new EdgeRef(A, B)).byPathway()).containsKey("river");
  }

  @Test
  void aClosedLoopMayReuseTheStartPointButNotRepeatAnEdge() {
    GameMap triangle =
        graphOf(List.of(A, B, T), Map.of(), PathwayGroup.defaults(), GenerationSpec.defaults(1));
    GameMap after =
        apply(
            triangle, EdgeOperations.connectChain(triangle, "road", List.of(A, B, T, A), "merge"));
    assertThat(after.edges())
        .containsOnlyKeys(new EdgeRef(A, B), new EdgeRef(B, T), new EdgeRef(T, A));

    assertThatThrownBy(
            () -> EdgeOperations.connectChain(triangle, "road", List.of(A, B, A), "merge"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("重复");
  }

  @Test
  void rejectsSelfLoopsJumpsOutOfMapUnknownKindBadOpAndShortOrLongChains() {
    GameMap base = richMap();
    assertThatThrownBy(() -> EdgeOperations.connectChain(base, "river", List.of(A, A), "merge"))
        .hasMessageContaining("自环");
    assertThatThrownBy(() -> EdgeOperations.connectChain(base, "river", List.of(A, C), "merge"))
        .hasMessageContaining("第 1 段不相邻");
    assertThatThrownBy(
            () ->
                EdgeOperations.connectChain(
                    base, "river", List.of(A, B, new HexCoord(99, 99)), "merge"))
        .hasMessageContaining("格不在图上");
    assertThatThrownBy(() -> EdgeOperations.connectChain(base, "sea", List.of(A, B), "merge"))
        .hasMessageContaining("未知连通性类型");
    assertThatThrownBy(() -> EdgeOperations.connectChain(base, "river", List.of(A, B), "replace"))
        .hasMessageContaining("未知 op");
    assertThatThrownBy(() -> EdgeOperations.connectChain(base, "river", List.of(A), "merge"))
        .hasMessageContaining("2..12001");
    assertThatThrownBy(() -> EdgeOperations.connectChain(base, "river", null, "merge"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                EdgeOperations.connectChain(
                    base, "river", java.util.Collections.nCopies(12_002, A), "merge"))
        .hasMessageContaining("2..12001");
    for (String bad : new String[] {"River "}) {
      assertThatThrownBy(() -> EdgeOperations.connectChain(base, bad, List.of(A, B), "merge"))
          .as("词表外 kind %s", bad)
          .hasMessageContaining("未知连通性类型");
    }
  }

  @Test
  void wrapAdjacencyIsMapWrapAwareAndDoesNotChangeWrapOrSpec() {
    GenerationSpec plane = GenerationSpec.defaults(3);
    GenerationSpec wrapped =
        new GenerationSpec(
            plane.seed(),
            plane.mapRadius(),
            plane.baseSeaLevel(),
            plane.mainRidges(),
            plane.fragments(),
            plane.bands(),
            plane.ridges(),
            plane.fragmentParams(),
            plane.contourCacheMax(),
            new MapWrap(true, 0, 3));
    GameMap base =
        graphOf(
            List.of(new HexCoord(0, 0), new HexCoord(2, 0)),
            Map.of(),
            PathwayGroup.defaults(),
            wrapped);

    GameMap after =
        apply(
            base,
            EdgeOperations.connectChain(
                base, "river", List.of(new HexCoord(0, 0), new HexCoord(2, 0)), "merge"));

    assertThat(after.edges()).containsKey(new EdgeRef(new HexCoord(0, 0), new HexCoord(2, 0)));
    assertThat(after.wrap()).isEqualTo(wrapped.wrap());
    assertThat(after.spec()).isEqualTo(wrapped);
  }

  @Test
  void extremeCoordinatesCannotOverflowIntoTreatingDistantCellsAsAdjacent() {
    HexCoord max = new HexCoord(Integer.MAX_VALUE, 0);
    HexCoord min = new HexCoord(Integer.MIN_VALUE, 0);
    HexCoord maxNeighbor = new HexCoord(Integer.MAX_VALUE - 1, 0);
    GameMap base =
        graphOf(
            List.of(max, min, maxNeighbor),
            Map.of(),
            PathwayGroup.defaults(),
            GenerationSpec.defaults(4));

    assertThatThrownBy(() -> EdgeOperations.connectChain(base, "river", List.of(max, min), "merge"))
        .as("int 距离溢出不得把两极判成相邻")
        .hasMessageContaining("不相邻");
    GameMap after =
        apply(base, EdgeOperations.connectChain(base, "river", List.of(max, maxNeighbor), "merge"));
    assertThat(after.edges()).containsKey(new EdgeRef(max, maxNeighbor));
  }

  @Test
  void onlyTheEdgesComponentChangesAndCodecRoundTripsTheCompleteTarget() {
    GameMap base = richMap();
    MapChangeSet delta = EdgeOperations.connectChain(base, "river", List.of(A, B, C), "merge");

    assertThat(delta.edges()).isNotInstanceOf(FieldDelta.Unchanged.class);
    assertThat(delta.hexes()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(delta.terrainBlocks()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(delta.regions()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(delta.cities()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(delta.terrainTypes()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(delta.pathways()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(delta.pathwayGroups()).isInstanceOf(FieldDelta.Unchanged.class);

    MapCodec codec = new MapCodec();
    MapChangeSet back = (MapChangeSet) codec.decodeChangeSet(codec.encodeChangeSet(delta));
    GameMap after = apply(base, delta);
    assertThat(apply(base, back)).isEqualTo(after);
    MapSnapshot snapshot =
        new MapSnapshot(
            new io.mosire.simos.util.state.StateRef(
                new io.mosire.simos.util.state.BranchId("main"),
                new io.mosire.simos.util.state.RevisionId(1)),
            io.mosire.simos.util.time.SimosTimestamp.of(0),
            after);
    assertThat(codec.decodeSnapshot(codec.encodeSnapshot(snapshot))).isEqualTo(snapshot);
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────

  private static GameMap apply(GameMap base, MapChangeSet delta) {
    return MapChangeSet.apply(delta, base);
  }

  private static GameMap richMap() {
    EdgeRef ab = new EdgeRef(A, B);
    EdgeRef cd = new EdgeRef(C, D);
    EdgeRef dOut = new EdgeRef(D, OUT);
    Map<EdgeRef, EdgeTags> edges = new LinkedHashMap<>();
    edges.put(ab, new EdgeTags(Map.of("road", Map.of("width", 3), "river", Map.of("depth", 2))));
    edges.put(cd, new EdgeTags(Map.of("road", Map.of())));
    edges.put(dOut, new EdgeTags(Map.of("river", Map.of("flow", 1))));
    return graphOf(
        List.of(A, B, C, D, E, OUT), edges, PathwayGroup.defaults(), GenerationSpec.defaults(7));
  }

  private static GameMap graphOf(
      List<HexCoord> coords,
      Map<EdgeRef, EdgeTags> edges,
      Map<String, PathwayGroup> groups,
      GenerationSpec spec) {
    Map<HexCoord, HexCell> cells = new LinkedHashMap<>();
    coords.forEach(coord -> cells.put(coord, new HexCell(0.4)));
    RegionId rid = RegionId.parse("keep-region");
    CityId cid = CityId.parse("keep-city");
    PathwayId pid = PathwayId.parse("keep-path");
    EdgeRef firstEdge = edges.keySet().stream().findFirst().orElse(new EdgeRef(A, B));
    return new GameMap(
        cells,
        TerrainBlocks.uniform(cells.keySet(), "plains"),
        Map.of(rid, Region.of(rid, "保持区域", Set.copyOf(coords), RegionMeta.empty())),
        Map.of(cid, new City(cid, "保持城市", A, rid, Map.of("label", "unchanged"))),
        Map.of("plains", TerrainCatalog.of("plains")),
        Map.of(pid, new Pathway(pid, "保持通路", "road", List.of(firstEdge), Map.of())),
        groups,
        edges,
        spec);
  }
}
