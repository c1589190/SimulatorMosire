package io.mosire.simos.map.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.CommandHandler;
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
 * M8 T4 三条区域命令的**命令边界**：type 名、成功折 {@code Applied}、各种坏载荷/域规则违反折 {@code Rejected}。
 *
 * <p>这里是**命令边界**（不打 DB）：重复 id / 目标不存在 / 空集 / 图外等语义由 {@link
 * io.mosire.simos.map.ops.RegionOperations} 抛出、在本层折成拒绝理由。
 */
class RegionHandlersTest {

  private static final HexCoord H0 = new HexCoord(0, 0);

  private static final HexCoord H1 = new HexCoord(1, 0);

  private static final HexCoord H2 = new HexCoord(2, 0);

  private static final RegionId R1 = new RegionId("r1");

  private static final RegionId R2 = new RegionId("r2");

  private static final CreateRegionHandler CREATE = new CreateRegionHandler();

  private static final UpdateRegionHandler UPDATE = new UpdateRegionHandler();

  private static final DeleteRegionHandler DELETE = new DeleteRegionHandler();

  @Test
  void handlersReportTheirNamespacedTypes() {
    assertThat(new CommandHandler[] {CREATE, UPDATE, DELETE})
        .extracting(CommandHandler::type)
        .containsExactly("map.CreateRegion", "map.UpdateRegion", "map.DeleteRegion");
  }

  // ── 正常 ────────────────────────────────────────────────────────────────────

  @Test
  void createRegionHandlerAppliesAReadablePayload() {
    HandlerOutcome outcome =
        CREATE.handle(
            stateOf(emptyRegionMap()),
            "{\"regionId\":\"r1\",\"name\":\"甲\",\"hexes\":[{\"q\":0,\"r\":0},{\"q\":1,\"r\":0}],"
                + "\"meta\":{\"color\":\"#abc\",\"tag\":\"Nation\"}}");

    HandlerOutcome.Applied applied = (HandlerOutcome.Applied) outcome;
    GameMap after = MapChangeSet.apply((MapChangeSet) applied.changeSet(), emptyRegionMap());
    Region region = after.regions().get(R1);
    assertThat(region.name()).isEqualTo("甲");
    assertThat(region.hexes()).containsExactlyInAnyOrder(H0, H1);
    assertThat(region.meta()).isEqualTo(new RegionMeta("#abc", "Nation", null, null));
  }

  @Test
  void createRegionHandlerWithoutMetaDefaultsToEmptyMeta() {
    HandlerOutcome outcome =
        CREATE.handle(
            stateOf(emptyRegionMap()),
            "{\"regionId\":\"r1\",\"name\":\"甲\",\"hexes\":[{\"q\":0,\"r\":0}]}");

    HandlerOutcome.Applied applied = (HandlerOutcome.Applied) outcome;
    GameMap after = MapChangeSet.apply((MapChangeSet) applied.changeSet(), emptyRegionMap());
    assertThat(after.regions().get(R1).meta()).isEqualTo(RegionMeta.empty());
  }

  /** ★ 重叠正例在命令层的复现：新区域与 r1 共享 H0 ⇒ 成功。 */
  @Test
  void createRegionHandlerAllowsOverlap() {
    HandlerOutcome outcome =
        CREATE.handle(
            stateOf(mapWithR1()),
            "{\"regionId\":\"r2\",\"name\":\"乙\",\"hexes\":[{\"q\":0,\"r\":0},{\"q\":2,\"r\":0}]}");

    HandlerOutcome.Applied applied = (HandlerOutcome.Applied) outcome;
    GameMap after = MapChangeSet.apply((MapChangeSet) applied.changeSet(), mapWithR1());
    assertThat(after.regionIndex().regionOf(H0)).containsExactly(R1, R2);
  }

  @Test
  void updateRegionHandlerAppliesHexesAndMeta() {
    HandlerOutcome outcome =
        UPDATE.handle(
            stateOf(mapWithR1()),
            "{\"regionId\":\"r1\",\"hexes\":[{\"q\":2,\"r\":0}],\"meta\":{\"tag\":\"T\"}}");

    HandlerOutcome.Applied applied = (HandlerOutcome.Applied) outcome;
    GameMap after = MapChangeSet.apply((MapChangeSet) applied.changeSet(), mapWithR1());
    assertThat(after.regions().get(R1).hexes()).containsExactly(H2);
    assertThat(after.regions().get(R1).meta()).isEqualTo(new RegionMeta(null, "T", null, null));
  }

  /** 只给 meta（**无 hexes 字段**）⇒ 内容不动。 */
  @Test
  void updateRegionHandlerAppliesMetaOnly() {
    HandlerOutcome outcome =
        UPDATE.handle(stateOf(mapWithR1()), "{\"regionId\":\"r1\",\"meta\":{\"tag\":\"T\"}}");

    HandlerOutcome.Applied applied = (HandlerOutcome.Applied) outcome;
    GameMap after = MapChangeSet.apply((MapChangeSet) applied.changeSet(), mapWithR1());
    assertThat(after.regions().get(R1).hexes()).containsExactlyInAnyOrder(H0, H1);
    assertThat(after.regions().get(R1).meta()).isEqualTo(new RegionMeta(null, "T", null, null));
  }

  @Test
  void deleteRegionHandlerApplies() {
    HandlerOutcome outcome = DELETE.handle(stateOf(mapWithR1()), "{\"regionId\":\"r1\"}");

    HandlerOutcome.Applied applied = (HandlerOutcome.Applied) outcome;
    GameMap after = MapChangeSet.apply((MapChangeSet) applied.changeSet(), mapWithR1());
    assertThat(after.regions()).isEmpty();
    assertThat(after.regionIndex().regionOf(H0)).isEmpty();
  }

  @Test
  void duplicateHexCoordinatesInTheArrayAreDeduplicated() {
    HandlerOutcome outcome =
        CREATE.handle(
            stateOf(emptyRegionMap()),
            "{\"regionId\":\"r1\",\"name\":\"甲\",\"hexes\":[{\"q\":0,\"r\":0},{\"q\":0,\"r\":0}]}");

    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
  }

  // ── 负例 ────────────────────────────────────────────────────────────────────

  @Test
  void rejectsDuplicateCreate() {
    assertRejected(
        CREATE, "{\"regionId\":\"r1\",\"name\":\"覆盖\",\"hexes\":[{\"q\":2,\"r\":0}]}", "区域已存在: r1");
  }

  @Test
  void rejectsUpdateOfMissingRegion() {
    assertRejected(UPDATE, "{\"regionId\":\"nope\",\"meta\":{\"tag\":\"T\"}}", "区域不存在: nope");
  }

  @Test
  void rejectsDeleteOfMissingRegion() {
    assertRejected(DELETE, "{\"regionId\":\"nope\"}", "区域不存在: nope");
  }

  @Test
  void rejectsUpdateWithNeitherHexesNorMeta() {
    assertRejected(UPDATE, "{\"regionId\":\"r1\"}", "必须至少给 hexes 与 meta 之一");
  }

  @Test
  void rejectsEmptyHexes() {
    assertRejected(CREATE, "{\"regionId\":\"r-new\",\"name\":\"甲\",\"hexes\":[]}", "hexes 不得为空");
    assertRejected(UPDATE, "{\"regionId\":\"r1\",\"hexes\":[]}", "hexes 不得为空");
  }

  @Test
  void rejectsHexOutsideTheMap() {
    assertRejected(
        CREATE,
        "{\"regionId\":\"r-new\",\"name\":\"甲\",\"hexes\":[{\"q\":9,\"r\":9}]}",
        "hex 不在图上: 9_9");
  }

  @Test
  void rejectsBadPayloadShapes() {
    assertRejected(CREATE, "not json", "payload 不是合法 JSON");
    assertRejected(CREATE, "[1,2,3]", "payload 必须是 JSON 对象");
    assertRejected(CREATE, "{\"name\":\"甲\",\"hexes\":[{\"q\":0,\"r\":0}]}", "字段 regionId 必须是字符串");
    assertRejected(
        CREATE,
        "{\"regionId\":\" \",\"name\":\"甲\",\"hexes\":[{\"q\":0,\"r\":0}]}",
        "RegionId 不得为空白");
    assertRejected(CREATE, "{\"regionId\":\"r1\",\"hexes\":[{\"q\":0,\"r\":0}]}", "字段 name 必须是字符串");
    assertRejected(CREATE, "{\"regionId\":\"r1\",\"name\":\"甲\"}", "字段 hexes 必须是 [{q,r}…] 数组");
    assertRejected(
        CREATE,
        "{\"regionId\":\"r1\",\"name\":\"甲\",\"hexes\":[{\"q\":0,\"r\":0}],\"meta\":\"x\"}",
        "字段 meta 必须是 {color,tag,description,annexedBy} 对象");
    assertRejected(
        CREATE,
        "{\"regionId\":\"r1\",\"name\":\"甲\",\"hexes\":[{\"q\":0,\"r\":0}],"
            + "\"meta\":{\"color\":7}}",
        "meta 字段 color 必须是字符串");
    assertRejected(CREATE, "{\"regionId\":\"r1\",\"name\":\"甲\",\"hexes\":[1]}", "的元素必须是 {q,r} 对象");
    assertRejected(
        CREATE, "{\"regionId\":\"r1\",\"name\":\"甲\",\"hexes\":[{\"q\":0}]}", "必须有整数 q 与 r");
    assertRejected(DELETE, "{\"regionId\":\" \"}", "RegionId 不得为空白");
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────

  private static void assertRejected(
      CommandHandler handler, String payloadJson, String reasonFragment) {
    HandlerOutcome outcome = handler.handle(stateOf(mapWithR1()), payloadJson);
    assertThat(outcome)
        .as("%s 对 payload %s 必须被拒", handler.type(), payloadJson)
        .isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains(reasonFragment);
  }

  private static GameMap emptyRegionMap() {
    return mapOf(Map.of());
  }

  private static GameMap mapWithR1() {
    return mapOf(Map.of(R1, Region.of(R1, "甲", java.util.Set.of(H0, H1), RegionMeta.empty())));
  }

  private static GameMap mapOf(Map<RegionId, Region> regions) {
    Map<HexCoord, HexCell> cells = new LinkedHashMap<>();
    cells.put(H0, new HexCell(0.4));
    cells.put(H1, new HexCell(0.5));
    cells.put(H2, new HexCell(0.6));
    return new GameMap(
        cells,
        TerrainBlocks.uniform(cells.keySet(), "plains"),
        regions,
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
