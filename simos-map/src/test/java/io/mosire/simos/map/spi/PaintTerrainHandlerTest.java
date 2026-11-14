package io.mosire.simos.map.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.City;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.HexLand;
import io.mosire.simos.map.LandComponent;
import io.mosire.simos.map.LandComponentId;
import io.mosire.simos.map.LandTypeArea;
import io.mosire.simos.map.LandTypeId;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.generate.GenerationSpec;
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
import io.mosire.simos.map.terrain.TerrainHeights;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * T2 {@code map.PaintTerrain}：直接、原子多格异类地形；高度缺省/显式 0/1；全部负例整批拒绝；土地树/其他组件逐字段保持； ChangeSet/Snapshot
 * codec 往返；GM-only 与精确 CommandTargets。
 */
class PaintTerrainHandlerTest {

  private static final HexCoord H0 = new HexCoord(0, 0);
  private static final HexCoord H1 = new HexCoord(1, 0);
  private static final HexCoord H2 = new HexCoord(2, 0);
  private static final HexCoord EXTRA = new HexCoord(9, 9);

  private static final PaintTerrainHandler HANDLER = new PaintTerrainHandler();

  @Test
  void typeTargetsAndDmUsableContractAreExact() {
    String payload =
        "{\"cells\":[{\"q\":0,\"r\":0,\"terrain\":\"mountains\"},{\"q\":2,\"r\":0,\"terrain\":\"ocean\"}]}";
    assertThat(HANDLER.type()).isEqualTo("map.PaintTerrain");
    assertThat(HANDLER.targetPaths("Map1", payload))
        .containsExactly("Map1/hex/0_0", "Map1/hex/2_0");
    assertThat(HANDLER).isNotInstanceOf(io.mosire.simos.util.spi.DmUsableCommand.class);
  }

  @Test
  void paintsDifferentTerrainsInOneAtomicCommandAndDefaultsHeight() {
    GameMap base = richMap();
    String payload =
        "{\"cells\":["
            + "{\"q\":0,\"r\":0,\"terrain\":\"mountains\"},"
            + "{\"q\":1,\"r\":0,\"terrain\":\"desert\",\"height\":0},"
            + "{\"q\":2,\"r\":0,\"terrain\":\"ocean\",\"height\":1}]}";

    HandlerOutcome.Applied applied =
        (HandlerOutcome.Applied) HANDLER.handle(stateOf(base), payload);
    MapChangeSet delta = (MapChangeSet) applied.changeSet();
    GameMap after = MapChangeSet.apply(delta, base);

    assertThat(after.terrainAt(H0)).isEqualTo("mountains");
    assertThat(after.hexes().get(H0).height())
        .as("height 字段缺失 ⇒ 该地形标准编辑高度中值")
        .isEqualTo(TerrainHeights.paintHeight("mountains"));
    assertThat(after.terrainAt(H1)).isEqualTo("desert");
    assertThat(after.hexes().get(H1).height()).isZero();
    assertThat(after.terrainAt(H2)).isEqualTo("ocean");
    assertThat(after.hexes().get(H2).height()).isEqualTo(1.0);

    assertThat(after.hexes().get(H0).land()).as("土地树逐字段保持").isEqualTo(base.hexes().get(H0).land());
    assertThat(after.hexes().get(EXTRA)).as("未被点名的格逐字段保持").isEqualTo(base.hexes().get(EXTRA));
    assertThat(after.regions()).isEqualTo(base.regions());
    assertThat(after.cities()).isEqualTo(base.cities());
    assertThat(after.pathways()).isEqualTo(base.pathways());
    assertThat(after.pathwayGroups()).isEqualTo(base.pathwayGroups());
    assertThat(after.edges()).isEqualTo(base.edges());
    assertThat(after.spec()).isEqualTo(base.spec());
    TerrainBlocks.requirePartition(after.hexes(), after.terrainBlocks());
    assertThat(after.terrainBlocks())
        .isEqualTo(TerrainBlocks.split(after.terrainIndex(), after.wrap()));

    // ChangeSet 与 Snapshot 都完整往返到同一个 target。
    MapCodec codec = new MapCodec();
    MapChangeSet back = (MapChangeSet) codec.decodeChangeSet(codec.encodeChangeSet(delta));
    assertThat(MapChangeSet.apply(back, base)).isEqualTo(after);
    MapSnapshot snapshot =
        new MapSnapshot(stateOf(base).meta().ref(), stateOf(base).meta().timestamp(), after);
    assertThat(codec.decodeSnapshot(codec.encodeSnapshot(snapshot))).isEqualTo(snapshot);
  }

  @Test
  void acceptsOneCellAndBothHeightEndpoints() {
    GameMap base = richMap();
    for (String height : new String[] {"0", "1", "0.0", "1.0"}) {
      HandlerOutcome outcome =
          HANDLER.handle(
              stateOf(base),
              "{\"cells\":[{\"q\":0,\"r\":0,\"terrain\":\"plains\",\"height\":" + height + "}]}");
      assertThat(outcome).as("height=%s", height).isInstanceOf(HandlerOutcome.Applied.class);
    }
  }

  @Test
  void rejectsEmptyOrOversizedBatchesBeforeTouchingTheMap() {
    reject("{\"cells\":[]}", "cells");
    reject("{\"cells\":" + hugeCells(12_001) + "}", "cells");
    reject("{}", "cells");
    reject("{\"cells\":{}}", "cells");
    reject("{\"cells\":[1]}", "对象");
  }

  @Test
  void explicitNullHeightIsRejectedNotSilentlyDefaulted() {
    reject(
        "{\"cells\":[{\"q\":0,\"r\":0,\"terrain\":\"mountains\",\"height\":null}]}", "显式 null 非法");
  }

  @Test
  void rejectsBadCoordinatesWithoutTruncating() {
    for (String q : new String[] {"1.5", "2147483648", "-2147483649", "\"0\"", "null", "true"}) {
      reject(
          "{\"cells\":[{\"q\":" + q + ",\"r\":0,\"terrain\":\"plains\",\"height\":0.4}]}", "q/r");
    }
  }

  @Test
  void rejectsBadHeightsTerrainsDuplicatesAndWideCoordinates() {
    for (String height :
        new String[] {"\"0.4\"", "null", "true", "{}", "[]", "-0.001", "1.001", "1e999"}) {
      reject(
          "{\"cells\":[{\"q\":0,\"r\":0,\"terrain\":\"plains\",\"height\":" + height + "}]}",
          "height");
    }
    reject("{\"cells\":[{\"q\":0,\"r\":0,\"terrain\":\"forest\"}]}", "地形");
    reject(
        "{\"cells\":[{\"q\":0,\"r\":0,\"terrain\":\"plains\"},{\"q\":0,\"r\":0,\"terrain\":\"ocean\"}]}",
        "重复");
    reject("{\"cells\":[{\"q\":99,\"r\":99,\"terrain\":\"plains\"}]}", "图外");
  }

  @Test
  void aValidCellBeforeAnInvalidOneStillRejectsTheWholeBatchWithoutMutation() {
    GameMap base = richMap();
    Map<HexCoord, HexCell> before = new LinkedHashMap<>(base.hexes());
    HandlerOutcome outcome =
        HANDLER.handle(
            stateOf(base),
            "{\"cells\":[{\"q\":2,\"r\":0,\"terrain\":\"mountains\",\"height\":0.8},"
                + "{\"q\":0,\"r\":0,\"terrain\":\"plains\",\"height\":1.5}]}");
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains("height");
    assertThat(base.hexes()).isEqualTo(before);
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────

  private static void reject(String payload, String reason) {
    HandlerOutcome outcome = HANDLER.handle(stateOf(richMap()), payload);
    assertThat(outcome).as(payload).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains(reason);
  }

  private static String hugeCells(int count) {
    return "["
        + "{\"q\":0,\"r\":0,\"terrain\":\"plains\"},".repeat(count - 1)
        + "{\"q\":0,\"r\":0,\"terrain\":\"plains\"}]";
  }

  private static GameMap richMap() {
    LandTypeArea arable =
        new LandTypeArea(
            LandTypeId.ARABLE,
            List.of(new LandComponent(LandComponentId.parse("arable@0_0:1"), 1234, Map.of())),
            2);
    Map<HexCoord, HexCell> cells = new LinkedHashMap<>();
    cells.put(H0, new HexCell(0.4, new HexLand(Map.of(LandTypeId.ARABLE, arable))));
    cells.put(H1, new HexCell(0.42));
    cells.put(H2, new HexCell(0.5));
    cells.put(EXTRA, new HexCell(0.2));
    RegionId rid = RegionId.parse("keep-region");
    CityId cid = CityId.parse("keep-city");
    PathwayId pid = PathwayId.parse("keep-road");
    EdgeRef edge = new EdgeRef(H0, H1);
    return new GameMap(
        cells,
        TerrainBlocks.uniform(cells.keySet(), "plains"),
        Map.of(rid, Region.of(rid, "保持区域", Set.of(H0, H1), RegionMeta.empty())),
        Map.of(cid, new City(cid, "保持城市", H1, rid, Map.of("label", "unchanged"))),
        Map.of("plains", TerrainCatalog.of("plains")),
        Map.of(pid, new Pathway(pid, "保持道路", "road", List.of(edge), Map.of())),
        PathwayGroup.defaults(),
        Map.of(edge, new EdgeTags(Map.of("road", Map.of("width", 3)))),
        GenerationSpec.defaults(7));
  }

  private static SimulationState stateOf(GameMap map) {
    StateRef ref = new StateRef(new BranchId("main"), new RevisionId(1));
    SimosTimestamp t = SimosTimestamp.of(0);
    return new SimulationState(
        new StateMeta(ref, t),
        Map.of("map", new MapSnapshot(ref, t, map)),
        InMemoryInfoSystem.empty());
  }
}
