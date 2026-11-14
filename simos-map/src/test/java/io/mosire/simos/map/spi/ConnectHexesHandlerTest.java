package io.mosire.simos.map.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.pathway.EdgeRef;
import io.mosire.simos.map.pathway.PathwayGroup;
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
import org.junit.jupiter.api.Test;

/** T3 命令边界：{@code map.ConnectHexes} 的精确目标、Applied/Rejected 与坏载荷。 */
class ConnectHexesHandlerTest {

  private static final ConnectHexesHandler HANDLER = new ConnectHexesHandler();
  private static final HexCoord A = new HexCoord(0, 0);
  private static final HexCoord B = new HexCoord(1, 0);
  private static final HexCoord C = new HexCoord(1, 1);

  @Test
  void typeTargetsAndDmUsableContractAreExact() {
    String payload =
        "{\"kind\":\"river\",\"hexes\":[{\"q\":0,\"r\":0},{\"q\":1,\"r\":0},{\"q\":1,\"r\":1}],"
            + "\"op\":\"merge\"}";
    assertThat(HANDLER.type()).isEqualTo("map.ConnectHexes");
    assertThat(HANDLER.targetPaths("Map1", payload))
        .containsExactly("Map1/hex/0_0", "Map1/hex/1_0", "Map1/hex/1_1");
    assertThat(HANDLER).isNotInstanceOf(io.mosire.simos.util.spi.DmUsableCommand.class);
  }

  @Test
  void mergeAndRemoveApplyOnlyTheNamedEdges() {
    GameMap base = twoEdgeMap();
    HandlerOutcome.Applied merged =
        (HandlerOutcome.Applied)
            HANDLER.handle(
                stateOf(base),
                "{\"kind\":\"river\",\"hexes\":[{\"q\":0,\"r\":0},{\"q\":1,\"r\":0},"
                    + "{\"q\":1,\"r\":1}],\"op\":\"merge\"}");
    GameMap afterMerge = MapChangeSet.apply((MapChangeSet) merged.changeSet(), base);
    assertThat(afterMerge.edges()).containsKeys(new EdgeRef(A, B), new EdgeRef(B, C));

    HandlerOutcome.Applied removed =
        (HandlerOutcome.Applied)
            HANDLER.handle(
                stateOf(afterMerge),
                "{\"kind\":\"river\",\"hexes\":[{\"q\":0,\"r\":0},{\"q\":1,\"r\":0}],"
                    + "\"op\":\"remove\"}");
    GameMap afterRemove = MapChangeSet.apply((MapChangeSet) removed.changeSet(), afterMerge);
    assertThat(afterRemove.edges().get(new EdgeRef(A, B)).byPathway()).containsOnlyKeys("road");
    assertThat(afterRemove.edges()).containsKey(new EdgeRef(B, C));
  }

  @Test
  void rejectsDomainViolationsAsNamedRejections() {
    for (String[] pair :
        new String[][] {
          {
            "{\"kind\":\"sea\",\"hexes\":[{\"q\":0,\"r\":0},{\"q\":1,\"r\":0}],\"op\":\"merge\"}",
            "未知连通性类型"
          },
          {
            "{\"kind\":\"river\",\"hexes\":[{\"q\":0,\"r\":0},{\"q\":1,\"r\":0}],\"op\":\"replace\"}",
            "未知 op"
          },
          {
            "{\"kind\":\"river\",\"hexes\":[{\"q\":0,\"r\":0},{\"q\":1,\"r\":0},{\"q\":8,\"r\":8}],\"op\":\"merge\"}",
            "格不在图上"
          },
          {
            "{\"kind\":\"river\",\"hexes\":[{\"q\":0,\"r\":0},{\"q\":1,\"r\":1}],\"op\":\"merge\"}",
            "不相邻"
          },
          {
            "{\"kind\":\"river\",\"hexes\":[{\"q\":0,\"r\":0},{\"q\":0,\"r\":0}],\"op\":\"merge\"}",
            "自环"
          },
          {"{\"kind\":\"river\",\"hexes\":[{\"q\":0,\"r\":0}],\"op\":\"merge\"}", "2..12001"},
        }) {
      HandlerOutcome outcome = HANDLER.handle(stateOf(twoEdgeMap()), pair[0]);
      assertThat(outcome).as(pair[0]).isInstanceOf(HandlerOutcome.Rejected.class);
      assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains(pair[1]);
    }
  }

  @Test
  void rejectsMalformedPayloadShapes() {
    for (String[] pair :
        new String[][] {
          {"not json", "JSON"},
          {"[]", "对象"},
          {"{\"hexes\":[],\"op\":\"merge\"}", "kind"},
          {"{\"kind\":\"river\",\"op\":\"merge\"}", "hexes"},
          {"{\"kind\":\"river\",\"hexes\":[1],\"op\":\"merge\"}", "对象"},
          {"{\"kind\":\"river\",\"hexes\":[{\"q\":0}],\"op\":\"merge\"}", "q 与 r"},
          {"{\"kind\":\"river\",\"hexes\":[{\"q\":1.5,\"r\":0}],\"op\":\"merge\"}", "q 与 r"},
          {"{\"kind\":\"river\",\"hexes\":[{\"q\":0,\"r\":0}],\"op\":1}", "op"},
        }) {
      HandlerOutcome outcome = HANDLER.handle(stateOf(twoEdgeMap()), pair[0]);
      assertThat(outcome).as(pair[0]).isInstanceOf(HandlerOutcome.Rejected.class);
      assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains(pair[1]);
    }
  }

  private static GameMap twoEdgeMap() {
    Map<HexCoord, HexCell> cells = new LinkedHashMap<>();
    cells.put(A, new HexCell(0.4));
    cells.put(B, new HexCell(0.5));
    cells.put(C, new HexCell(0.6));
    return new GameMap(
        cells,
        TerrainBlocks.uniform(cells.keySet(), "plains"),
        Map.of(),
        Map.of(),
        Map.of("plains", TerrainCatalog.of("plains")),
        Map.of(),
        PathwayGroup.defaults(),
        Map.of(
            new EdgeRef(A, B), new io.mosire.simos.map.pathway.EdgeTags(Map.of("road", Map.of()))),
        GenerationSpec.defaults(0L));
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
