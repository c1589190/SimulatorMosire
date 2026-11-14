package io.mosire.simos.map.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ImportHexGridHandlerTest {

  private static final HexCoord H0 = new HexCoord(0, 0);
  private static final HexCoord H1 = new HexCoord(1, 0);
  private static final ImportHexGridHandler HANDLER = new ImportHexGridHandler();

  @Test
  void importsTerrainHexesIntoABlankMap() {
    GameMap base = blankMap();
    HandlerOutcome outcome =
        HANDLER.handle(
            stateOf(base),
            "{\"hexes\":[{\"q\":0,\"r\":0,\"terrain\":\"plains\"},"
                + "{\"q\":1,\"r\":0,\"terrain\":\"ocean\"}]}");
    HandlerOutcome.Applied applied = (HandlerOutcome.Applied) outcome;
    GameMap after = MapChangeSet.apply((MapChangeSet) applied.changeSet(), base);
    assertThat(after.hexes()).containsOnlyKeys(H0, H1);
    assertThat(after.terrainAt(H0)).isEqualTo("plains");
    assertThat(after.terrainAt(H1)).isEqualTo("ocean");
    assertThat(after.terrainBlocks()).hasSize(2);
  }

  @Test
  void rejectsNonBlankMapsAndEmptyGrids() {
    GameMap nonBlank =
        new GameMap(
            Map.of(H0, new HexCell(0.37)),
            TerrainBlocks.uniform(Set.of(H0), "plains"),
            Map.of(),
            Map.of(),
            TerrainCatalog.defaults(),
            Map.of(),
            Map.of(),
            Map.of(),
            GenerationSpec.defaults(0L));
    // 非空地图：即使 blocks 不完整，也在进入构造前被 blank 守卫拒绝。
    assertThat(
            HANDLER.handle(
                stateOf(nonBlank), "{\"hexes\":[{\"q\":0,\"r\":0,\"terrain\":\"plains\"}]}"))
        .isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(HANDLER.handle(stateOf(blankMap()), "{\"hexes\":[]}"))
        .isInstanceOf(HandlerOutcome.Rejected.class);
  }

  @Test
  void rejectsUnknownTerrainDuplicateCoordinatesAndBadShapes() {
    assertThat(
            HANDLER.handle(
                stateOf(blankMap()), "{\"hexes\":[{\"q\":0,\"r\":0,\"terrain\":\"forest\"}]}"))
        .isInstanceOf(HandlerOutcome.Rejected.class)
        .extracting(outcome -> ((HandlerOutcome.Rejected) outcome).reason())
        .asString()
        .contains("未知地形类型");
    assertThat(
            HANDLER.handle(
                stateOf(blankMap()),
                "{\"hexes\":[{\"q\":0,\"r\":0,\"terrain\":\"plains\"},"
                    + "{\"q\":0,\"r\":0,\"terrain\":\"ocean\"}]}"))
        .isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(HANDLER.handle(stateOf(blankMap()), "{\"hexes\":[{\"q\":0,\"r\":0}]}"))
        .isInstanceOf(HandlerOutcome.Rejected.class);
  }

  private static GameMap blankMap() {
    return new GameMap(
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        TerrainCatalog.defaults(),
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  private static SimulationState stateOf(GameMap map) {
    StateRef ref = new StateRef(new BranchId("main"), new RevisionId(1));
    SimosTimestamp at = SimosTimestamp.of(0);
    Map<String, io.mosire.simos.util.state.Snapshot> modules = new LinkedHashMap<>();
    modules.put("map", new MapSnapshot(ref, at, map));
    return new SimulationState(new StateMeta(ref, at), modules, InMemoryInfoSystem.empty());
  }
}
