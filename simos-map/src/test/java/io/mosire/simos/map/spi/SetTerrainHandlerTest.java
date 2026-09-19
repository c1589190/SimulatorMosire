package io.mosire.simos.map.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
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

/**
 * {@code map.SetTerrain} 命令边界：载荷解析、成功折 {@code Applied}、各种坏载荷折 {@code Rejected}。
 *
 * <p>这里是**命令边界**（不打 DB）：域规则违反（词表外/图外/空集）由 {@link io.mosire.simos.map.ops.TerrainOperations}
 * 抛出、在本层折成拒绝理由。
 */
class SetTerrainHandlerTest {

  private static final HexCoord H0 = new HexCoord(0, 0);

  private static final HexCoord H1 = new HexCoord(1, 0);

  private static final SetTerrainHandler HANDLER = new SetTerrainHandler();

  @Test
  void typeIsTheNamespacedCommandId() {
    assertThat(HANDLER.type()).isEqualTo("map.SetTerrain");
  }

  /** 多格载荷 ⇒ Applied，且变更集作用在 base 上逐格对上。 */
  @Test
  void appliesAMultiHexPayload() {
    GameMap base = twoPlainsHexes();

    HandlerOutcome outcome =
        HANDLER.handle(
            stateOf(base),
            "{\"hexes\":[{\"q\":0,\"r\":0},{\"q\":1,\"r\":0}],\"terrain\":\"desert\"}");

    HandlerOutcome.Applied applied = (HandlerOutcome.Applied) outcome;
    GameMap after = MapChangeSet.apply((MapChangeSet) applied.changeSet(), base);
    assertThat(after.terrainAt(H0)).isEqualTo("desert");
    assertThat(after.terrainAt(H1)).isEqualTo("desert");
  }

  /** 载荷里重复的格去重（Set 语义），不报错。 */
  @Test
  void duplicatesInTheHexArrayAreDeduplicated() {
    HandlerOutcome outcome =
        HANDLER.handle(
            stateOf(twoPlainsHexes()),
            "{\"hexes\":[{\"q\":0,\"r\":0},{\"q\":0,\"r\":0}],\"terrain\":\"desert\"}");

    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
  }

  // ── 负例：词表外 / 图外 / 空集 / 形态坏 ────────────────────────────────────────

  @Test
  void rejectsTerrainOutsideTheCatalog() {
    assertRejected("{\"hexes\":[{\"q\":0,\"r\":0}],\"terrain\":\"forest\"}", "未知地形类型: forest");
  }

  @Test
  void rejectsHexOutsideTheMap() {
    assertRejected("{\"hexes\":[{\"q\":9,\"r\":9}],\"terrain\":\"plains\"}", "hex 不在图上: 9_9");
  }

  @Test
  void rejectsEmptyHexes() {
    assertRejected("{\"hexes\":[],\"terrain\":\"plains\"}", "hexes 不得为空");
  }

  @Test
  void rejectsBadPayloadShapes() {
    assertRejected("not json", "payload 不是合法 JSON");
    assertRejected("[1,2,3]", "payload 必须是 JSON 对象");
    assertRejected("{\"hexes\":[{\"q\":0,\"r\":0}]}", "字段 terrain 必须是字符串");
    assertRejected("{\"terrain\":\"plains\"}", "字段 hexes 必须是 [{q,r}…] 数组");
    assertRejected("{\"hexes\":{},\"terrain\":\"plains\"}", "字段 hexes 必须是 [{q,r}…] 数组");
    assertRejected("{\"hexes\":[1],\"terrain\":\"plains\"}", "的元素必须是 {q,r} 对象");
    assertRejected("{\"hexes\":[{\"q\":0}],\"terrain\":\"plains\"}", "必须有整数 q 与 r");
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────

  private static void assertRejected(String payloadJson, String reasonFragment) {
    HandlerOutcome outcome = HANDLER.handle(stateOf(twoPlainsHexes()), payloadJson);
    assertThat(outcome)
        .as("payload %s 必须被拒", payloadJson)
        .isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains(reasonFragment);
  }

  private static GameMap twoPlainsHexes() {
    Map<HexCoord, HexCell> cells = new LinkedHashMap<>();
    cells.put(H0, new HexCell(0.4));
    cells.put(H1, new HexCell(0.5));
    return new GameMap(
        cells,
        TerrainBlocks.uniform(cells.keySet(), "plains"),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  private static SimulationState stateOf(GameMap map) {
    BranchId main = new BranchId("main");
    StateRef ref = new StateRef(main, new RevisionId(1));
    SimosTimestamp t = SimosTimestamp.of(0);
    return new SimulationState(
        new StateMeta(ref, t),
        Map.of("map", new MapSnapshot(ref, t, map)),
        InMemoryInfoSystem.empty());
  }
}
