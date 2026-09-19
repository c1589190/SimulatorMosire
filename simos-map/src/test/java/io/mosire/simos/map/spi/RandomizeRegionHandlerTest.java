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
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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

  // ── 圈选顺序不进结果 ────────────────────────────────────────────────────────

  /**
   * ★★ **客户端圈选顺序不进变更集**——真实风险向量：{@code MapPayloads.requireHexes} 按 **JSON 数组序**装 {@code
   * LinkedHashSet}，故载荷里 hexes 的次序就是选区的迭代序。同一选区**正序/逆序**两份载荷必须折出**逐字节相同**的块表。
   *
   * <p>载荷**生成**而非手写（半径 4 = 61 格）：格数太少时"逆序恰好同结果"的概率不可忽略 ⇒ 变异体能侥幸存活、用例 变装饰。
   * 另**先自证**两份载荷解析后的迭代序确实不同，否则本用例恒真。
   */
  @Test
  void payloadArrayOrderDoesNotLeakIntoTheChangeSet() throws Exception {
    List<HexCoord> forwardOrder = new ArrayList<>(HexGrid.withinRadius(new HexCoord(0, 0), 4));
    forwardOrder.sort(HexCoord::compareTo);
    List<HexCoord> backwardOrder = new ArrayList<>(forwardOrder);
    Collections.reverse(backwardOrder);

    String forward = payloadOf(forwardOrder);
    String backward = payloadOf(backwardOrder);

    assertThat(List.copyOf(MapPayloads.requireHexes(MapPayloads.parse(backward), "hexes")))
        .as("逆序载荷解析后的迭代序必须真的不同于正序（否则本用例什么都没验）")
        .isNotEqualTo(List.copyOf(MapPayloads.requireHexes(MapPayloads.parse(forward), "hexes")));

    assertThat(blocksJsonOf(4, forward)).as("逆序载荷 ⇒ 块表逐字节相同").isEqualTo(blocksJsonOf(4, backward));
  }

  /** 把 hexes 列表渲成载荷 JSON（数组序即列表序）。 */
  private static String payloadOf(List<HexCoord> hexes) {
    StringBuilder json = new StringBuilder("{\"hexes\":[");
    for (int i = 0; i < hexes.size(); i++) {
      if (i > 0) {
        json.append(',');
      }
      json.append("{\"q\":")
          .append(hexes.get(i).q())
          .append(",\"r\":")
          .append(hexes.get(i).r())
          .append('}');
    }
    return json.append("],\"seed\":7}").toString();
  }

  /** 跑一遍真 handler，返回应用后块表的**序列化字节**（比 toString 更强：含边界数值）。 */
  private static String blocksJsonOf(int radius, String payloadJson) throws Exception {
    GameMap base = graphOf(radius);
    HandlerOutcome outcome = HANDLER.handle(stateOf(base), payloadJson);
    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    GameMap after =
        MapChangeSet.apply((MapChangeSet) ((HandlerOutcome.Applied) outcome).changeSet(), base);
    return SimosObjectMapper.create().writeValueAsString(after.terrainBlocks());
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
    return graphOf(2);
  }

  private static GameMap graphOf(int radius) {
    Set<HexCoord> all = HexGrid.withinRadius(new HexCoord(0, 0), radius);
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
