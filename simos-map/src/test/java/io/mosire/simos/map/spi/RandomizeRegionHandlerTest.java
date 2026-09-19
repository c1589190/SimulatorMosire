package io.mosire.simos.map.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.hex.HexGrid;
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

/**
 * {@code map.RandomizeRegion} 命令边界：载荷解析、成功折 {@code Applied}、坏载荷/域规则折 {@code Rejected}。 ★ 首重 **缺
 * {@code seed} ⇒ 拒绝**（没有默认值）。
 */
class RandomizeRegionHandlerTest {

  private static final RandomizeRegionHandler HANDLER = new RandomizeRegionHandler();

  @Test
  void typeIsTheNamespacedCommandId() {
    assertThat(HANDLER.type()).isEqualTo("map.RandomizeRegion");
  }

  /** 正常载荷 ⇒ Applied，且选区内地形的确变了。 */
  @Test
  void appliesASeededSelection() {
    GameMap base = graphOf();

    HandlerOutcome outcome =
        HANDLER.handle(
            stateOf(base), "{\"hexes\":[{\"q\":0,\"r\":0},{\"q\":1,\"r\":0}],\"seed\":7}");

    GameMap after =
        MapChangeSet.apply((MapChangeSet) ((HandlerOutcome.Applied) outcome).changeSet(), base);
    boolean changed = false;
    for (HexCoord hex : base.hexes().keySet()) {
      changed |= !base.terrainAt(hex).equals(after.terrainAt(hex));
    }
    assertThat(changed).as("随机化确实改了地形（防恒真）").isTrue();
  }

  // ── 负例 ────────────────────────────────────────────────────────────────────

  /** ★ 缺 {@code seed} ⇒ 拒绝（**不许给默认值**）。 */
  @Test
  void rejectsPayloadWithoutSeed() {
    assertRejected("{\"hexes\":[{\"q\":0,\"r\":0}]}", "字段 seed 必须是整数");
  }

  @Test
  void rejectsEmptyHexes() {
    assertRejected("{\"hexes\":[],\"seed\":7}", "hexes 不得为空");
  }

  @Test
  void rejectsHexOutsideTheMap() {
    assertRejected("{\"hexes\":[{\"q\":9,\"r\":9}],\"seed\":7}", "hex 不在图上: 9_9");
  }

  @Test
  void rejectsBadPayloadShapes() {
    assertRejected("not json", "payload 不是合法 JSON");
    assertRejected("[1,2,3]", "payload 必须是 JSON 对象");
    assertRejected("{\"seed\":7}", "字段 hexes 必须是 [{q,r}…] 数组");
    assertRejected("{\"hexes\":[{\"q\":0,\"r\":0}],\"seed\":\"7\"}", "字段 seed 必须是整数");
    assertRejected("{\"hexes\":[{\"q\":0,\"r\":0}],\"seed\":1.5}", "字段 seed 必须是整数");
    assertRejected("{\"hexes\":[1],\"seed\":7}", "的元素必须是 {q,r} 对象");
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────

  private static void assertRejected(String payloadJson, String reasonFragment) {
    HandlerOutcome outcome = HANDLER.handle(stateOf(graphOf()), payloadJson);
    assertThat(outcome)
        .as("payload %s 必须被拒", payloadJson)
        .isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains(reasonFragment);
  }

  private static GameMap graphOf() {
    Set<HexCoord> all = HexGrid.withinRadius(new HexCoord(0, 0), 2);
    Map<HexCoord, HexCell> cells = new LinkedHashMap<>();
    for (HexCoord hex : all) {
      cells.put(hex, new HexCell(0.4));
    }
    return new GameMap(
        cells,
        TerrainBlocks.uniform(all, "mountains"),
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
