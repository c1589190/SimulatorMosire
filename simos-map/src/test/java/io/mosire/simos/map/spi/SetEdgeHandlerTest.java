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
import io.mosire.simos.map.pathway.EdgeTags;
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
 * {@code map.SetEdge} 命令边界：载荷解析、成功折 {@code Applied}、各种坏载荷/域规则折 {@code Rejected}。 ★ 首重 **{@code
 * mode} 缺字段 ⇒ 拒绝**（没有默认值）。
 */
class SetEdgeHandlerTest {

  private static final HexCoord H0 = new HexCoord(0, 0);

  private static final HexCoord H1 = new HexCoord(1, 0);

  private static final String EDGE = "0_0|1_0";

  private static final SetEdgeHandler HANDLER = new SetEdgeHandler();

  @Test
  void typeIsTheNamespacedCommandId() {
    assertThat(HANDLER.type()).isEqualTo("map.SetEdge");
  }

  /** merge 载荷 ⇒ Applied，且变更集作用在 base 上真的给该边加了 river。 */
  @Test
  void appliesAMergePayload() {
    GameMap base = twoHexes();

    HandlerOutcome outcome =
        HANDLER.handle(
            stateOf(base), "{\"kind\":\"river\",\"edges\":[\"" + EDGE + "\"],\"mode\":\"merge\"}");

    GameMap after =
        MapChangeSet.apply((MapChangeSet) ((HandlerOutcome.Applied) outcome).changeSet(), base);
    assertThat(after.edges().get(EdgeRef.parse(EDGE)).byPathway()).containsOnlyKeys("river");
  }

  /** replace 载荷 ⇒ Applied（既有 river 标注被整份覆盖）。 */
  @Test
  void appliesAReplacePayload() {
    GameMap base =
        twoHexesWithEdges(
            Map.of(EdgeRef.parse(EDGE), new EdgeTags(Map.of("river", Map.of("width", 3)))));

    HandlerOutcome outcome =
        HANDLER.handle(
            stateOf(base),
            "{\"kind\":\"river\",\"edges\":[\"" + EDGE + "\"],\"mode\":\"replace\"}");

    GameMap after =
        MapChangeSet.apply((MapChangeSet) ((HandlerOutcome.Applied) outcome).changeSet(), base);
    assertThat(after.edges().get(EdgeRef.parse(EDGE)).byPathway().get("river")).isEqualTo(Map.of());
  }

  // ── 负例 ────────────────────────────────────────────────────────────────────

  /** ★★ 缺 {@code mode} ⇒ 拒绝（**不许给默认值**）——这是 m1 变异要杀的那条。 */
  @Test
  void rejectsPayloadWithoutMode() {
    assertRejected("{\"kind\":\"river\",\"edges\":[\"" + EDGE + "\"]}", "字段 mode 必须是字符串");
  }

  @Test
  void rejectsUnknownKind() {
    assertRejected(
        "{\"kind\":\"sea\",\"edges\":[\"" + EDGE + "\"],\"mode\":\"merge\"}", "未知连通性类型: sea");
  }

  @Test
  void rejectsInvalidMode() {
    assertRejected(
        "{\"kind\":\"river\",\"edges\":[\"" + EDGE + "\"],\"mode\":\"overwrite\"}",
        "未知 mode: overwrite");
  }

  @Test
  void rejectsEmptyEdges() {
    assertRejected("{\"kind\":\"river\",\"edges\":[],\"mode\":\"merge\"}", "edges 不得为空");
  }

  @Test
  void rejectsEdgeOutsideTheMap() {
    assertRejected(
        "{\"kind\":\"river\",\"edges\":[\"0_0|9_9\"],\"mode\":\"merge\"}", "边的端点不在图上: 0_0|9_9");
  }

  @Test
  void rejectsBadPayloadShapes() {
    assertRejected("not json", "payload 不是合法 JSON");
    assertRejected("[1,2,3]", "payload 必须是 JSON 对象");
    assertRejected("{\"edges\":[\"" + EDGE + "\"],\"mode\":\"merge\"}", "字段 kind 必须是字符串");
    assertRejected("{\"kind\":\"river\",\"mode\":\"merge\"}", "字段 edges 必须是 [\"q_r|q_r\"…] 数组");
    assertRejected(
        "{\"kind\":\"river\",\"edges\":{},\"mode\":\"merge\"}", "字段 edges 必须是 [\"q_r|q_r\"…] 数组");
    assertRejected(
        "{\"kind\":\"river\",\"edges\":[1],\"mode\":\"merge\"}", "的元素必须是 \"q_r|q_r\" 字符串");
    assertRejected("{\"kind\":\"river\",\"edges\":[\"0_0\"],\"mode\":\"merge\"}", "非法边串: 0_0");
    assertRejected("{\"kind\":\"river\",\"edges\":[\"0_0|0_0\"],\"mode\":\"merge\"}", "边不能自环");
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────

  private static void assertRejected(String payloadJson, String reasonFragment) {
    HandlerOutcome outcome = HANDLER.handle(stateOf(twoHexes()), payloadJson);
    assertThat(outcome)
        .as("payload %s 必须被拒", payloadJson)
        .isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains(reasonFragment);
  }

  private static GameMap twoHexes() {
    return twoHexesWithEdges(Map.of());
  }

  private static GameMap twoHexesWithEdges(Map<EdgeRef, EdgeTags> edges) {
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
        edges,
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
