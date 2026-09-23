package io.mosire.simos.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandBus;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandOutcome;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.hex.HexGrid;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.spi.CreateRegionHandler;
import io.mosire.simos.map.spi.UpdateRegionHandler;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.unit.spi.CreateUnitHandler;
import io.mosire.simos.unit.spi.RenameUnitHandler;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ {@code CoreSimos.submitBatch} 的**真模块端到端**（map + unit，真 store、真 checkpoint、真 replay）： 用真实的
 * {@code ModuleDiffer}（{@code MapCodec}/{@code UnitCodec} 各自实现）证明"一批 = 一条 revision、变更集**从完整状态派生**"。
 *
 * <p>★ 为什么必须有一条真模块用例：玩具 differ 只能自证机制；"派生"这条铁律 5 的判别力来自**真实的 {@code XChangeSet.between}**
 * ——尤其是同域两条命令（先建区域、再改区域），派生出的变更集必须**同时体现两条的效果**，且 {@code apply(派生, base) == candidate}。
 *
 * <p>★ 与 {@code CommandBusBatchTest}（玩具）的分工：本类管"真语义对不对"，玩具类管"恰好一条 revision / 每条结局 / 冲突口径"
 * 这些与领域无关的机制。
 */
class CoreSimosBatchEndToEndTest {

  private static final BranchId MAIN = new BranchId("main");

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private static final HexCoord H00 = new HexCoord(0, 0);

  private static final HexCoord H10 = new HexCoord(1, 0);

  private static final HexCoord H20 = new HexCoord(2, 0);

  private static final RegionId SEED = new RegionId("seed");

  private static final RegionId T4 = new RegionId("t4");

  private static final UnitId U2 = new UnitId("u-2");

  @TempDir Path tempDir;

  // ── 类别 1（真模块）：跨命名空间一批 ⇒ 恰好一条 revision，每 namespace 一个**派生**键 ──────────

  @Test
  void crossNamespaceBatchCommitsOneRevisionWithPerNamespaceDerivedChangeSets() {
    try (CoreSimos core = openCore(tempDir)) {
      core.bootstrapGenesis(genesis(genesisMap()));

      BatchResult result =
          core.submitBatch(
              List.of(
                  envelope(
                      "map.CreateRegion",
                      "{\"regionId\":\"t4\",\"name\":\"T4\",\"hexes\":[{\"q\":2,\"r\":0}]}"),
                  envelope(
                      "unit.CreateUnit",
                      "{\"id\":\"u-2\",\"name\":\"第二连\",\"position\":{\"q\":0,\"r\":0},"
                          + "\"member\":80,\"equipment\":{},\"speed\":3,\"mobilityPerMille\":900}")));

      assertThat(result).isInstanceOf(BatchResult.Committed.class);
      assertThat(((BatchResult.Committed) result).ref()).isEqualTo(ref(2));
      assertThat(core.revisions(MAIN)).as("两命令只落一条 revision").hasSize(2);

      WorldChangeSet cs = changesetAt(core, 2);
      assertThat(cs.modules().keySet())
          .as("每 namespace 一个键，保序（首次出现序）")
          .containsExactly("map", "unit");
      assertThat(cs.modules().get("map"))
          .as("★ map 键 = 从 base(1) 到 candidate(2) 的真派生")
          .isEqualTo(MapChangeSet.between(mapAt(core, 1), mapAt(core, 2)));
      assertThat(cs.modules().get("unit"))
          .as("★ unit 键 = 从 base(1) 到 candidate(2) 的真派生")
          .isEqualTo(UnitChangeSet.between(unitAt(core, 1), unitAt(core, 2)));

      // 每条结局齐全（两条都生效于同一个新坐标）
      assertThat(result.outcomes()).hasSize(2);
      assertThat(result.outcomes())
          .allSatisfy(
              outcome ->
                  assertThat(outcome.result()).isEqualTo(new CommandResult.Committed(ref(2))));
    }
  }

  // ── 类别 2（真模块）：同域两条 ⇒ 仍一条 revision，派生变更集同时体现两条效果 ──────────────────

  @Test
  void sameNamespaceBatchDerivesOneChangeSetThatCarriesBothEffects() {
    try (CoreSimos core = openCore(tempDir)) {
      core.bootstrapGenesis(genesis(genesisMap()));

      BatchResult result =
          core.submitBatch(
              List.of(
                  envelope(
                      "map.CreateRegion",
                      "{\"regionId\":\"t4\",\"name\":\"T4\",\"hexes\":[{\"q\":2,\"r\":0}]}"),
                  envelope(
                      "map.UpdateRegion",
                      "{\"regionId\":\"t4\",\"hexes\":[{\"q\":1,\"r\":0},{\"q\":2,\"r\":0}],"
                          + "\"meta\":{\"tag\":\"T\"}}")));

      assertThat(result).isInstanceOf(BatchResult.Committed.class);
      assertThat(core.revisions(MAIN)).as("同域两条也只落一条 revision").hasSize(2);

      WorldChangeSet cs = changesetAt(core, 2);
      assertThat(cs.modules().keySet()).as("同域 ⇒ 只有一个键").containsExactly("map");

      GameMap base = mapAt(core, 1);
      GameMap candidate = mapAt(core, 2);
      assertThat(cs.modules().get("map"))
          .as("★ 派生 = MapChangeSet.between(base, candidate)")
          .isEqualTo(MapChangeSet.between(base, candidate));
      // ★ 变更集自洽：施加回 base 恰得 candidate（这正是"两条效果都在里面"的强形式）
      assertThat(MapChangeSet.apply((MapChangeSet) cs.modules().get("map"), base))
          .as("★ apply(派生, base) == candidate")
          .isEqualTo(candidate);
      // 效果 1（创建）与效果 2（改成 H10,H20 + tag=T）同时可见
      assertThat(candidate.regions().get(T4).hexes()).containsExactlyInAnyOrder(H10, H20);
      assertThat(candidate.regions().get(T4).meta())
          .isEqualTo(new RegionMeta(null, "T", null, null));
    }
  }

  // ── 类别 4（真模块）：顺序可见性 —— 后一条命令看见前一条的效果 ─────────────────────────────

  @Test
  void laterCommandSeesTheEarlierOneCreateThenRenameUnit() {
    try (CoreSimos core = openCore(tempDir)) {
      core.bootstrapGenesis(genesis(genesisMap()));

      // 先证判别力：在 base(1) 单条改名会被拒——base 里没有 u-2（该提交不留行，head 仍为 1）
      CommandResult solo =
          core.submit(envelope("unit.RenameUnit", "{\"id\":\"u-2\",\"name\":\"改名\"}"));
      assertThat(solo).isInstanceOf(CommandResult.Rejected.class);
      assertThat(((CommandResult.Rejected) solo).reason()).contains("单位不存在");
      assertThat(core.head(MAIN)).contains(new RevisionId(1));

      // 同一批：先建 u-2、再改名 —— 若顺序不生效，第二条必然被拒
      BatchResult result =
          core.submitBatch(
              List.of(
                  envelope(
                      "unit.CreateUnit",
                      "{\"id\":\"u-2\",\"name\":\"第二连\",\"position\":{\"q\":0,\"r\":0},"
                          + "\"member\":80,\"equipment\":{},\"speed\":3,\"mobilityPerMille\":900}"),
                  envelope("unit.RenameUnit", "{\"id\":\"u-2\",\"name\":\"改名\"}")));

      assertThat(result).as("第二条通过了 ⇒ 它看见的是第一条之后的候选态").isInstanceOf(BatchResult.Committed.class);
      assertThat(core.revisions(MAIN)).as("整批一条 revision").hasSize(2);
      assertThat(unitAt(core, 2).units().get(U2).name()).as("★ 同批顺序可见：改名作用在新建的单位上").isEqualTo("改名");
    }
  }

  // ── 类别 3（真模块）：一条被拒 ⇒ 整体拒绝、不留行、每条结局齐全 ─────────────────────────────

  @Test
  void rejectedBatchLeavesNoRevisionAndReportsEveryOutcome() {
    try (CoreSimos core = openCore(tempDir)) {
      core.bootstrapGenesis(genesis(genesisMap()));

      BatchResult result =
          core.submitBatch(
              List.of(
                  envelope(
                      "map.CreateRegion",
                      "{\"regionId\":\"t4\",\"name\":\"T4\",\"hexes\":[{\"q\":2,\"r\":0}]}"),
                  envelope(
                      "map.CreateRegion",
                      "{\"regionId\":\"seed\",\"name\":\"撞名\",\"hexes\":[{\"q\":2,\"r\":0}]}")));

      assertThat(result).isInstanceOf(BatchResult.Rejected.class);
      assertThat(core.head(MAIN)).as("整体拒绝 ⇒ head 不动").contains(new RevisionId(1));
      assertThat(core.revisions(MAIN)).as("只留创世那一行").hasSize(1);

      List<CommandOutcome> outcomes = result.outcomes();
      assertThat(outcomes).as("每条结局齐全").hasSize(2);
      assertThat(((CommandResult.Rejected) outcomes.get(0).result()).reason())
          .as("首条被接受但随整批复原")
          .contains("整批未提交");
      assertThat(((CommandResult.Rejected) outcomes.get(1).result()).reason())
          .as("次条是真拒因")
          .contains("区域已存在: seed");
    }
  }

  // ── 助手 ────────────────────────────────────────────────────────────────────────────

  private static CoreSimos openCore(Path dir) {
    return new CoreSimos(new CoreConfig(dir, 100, MAPPER))
        .register(new MapCodec())
        .register(new UnitCodec())
        .register(new CreateRegionHandler())
        .register(new UpdateRegionHandler())
        .register(new CreateUnitHandler())
        .register(new RenameUnitHandler());
  }

  private static CommandEnvelope envelope(String type, String payload) {
    return new CommandEnvelope(
        "cmd-" + type, "corr-" + type, "player:test", MAIN, new RevisionId(1), type, payload);
  }

  private static WorldChangeSet changesetAt(CoreSimos core, long revision) {
    return Timeline.readChangeSet(core.revisions(MAIN).get((int) revision - 1).changesetJson());
  }

  private static GameMap mapAt(CoreSimos core, long revision) {
    return ((MapSnapshot) core.replay(ref(revision)).module("map").orElseThrow()).map();
  }

  private static UnitState unitAt(CoreSimos core, long revision) {
    return ((UnitSnapshot) core.replay(ref(revision)).module("unit").orElseThrow()).state();
  }

  private static StateRef ref(long revision) {
    return new StateRef(MAIN, new RevisionId(revision));
  }

  private static SimulationState genesis(GameMap map) {
    return new SimulationState(
        new StateMeta(ref(1), T0),
        Map.of(
            "map", new MapSnapshot(ref(1), T0, map),
            "unit", new UnitSnapshot(ref(1), T0, UnitState.empty())),
        InMemoryInfoSystem.empty());
  }

  /** 半径 2 的 19 格全 {@code plains}，并预置区域 {@code seed}（{@code H00,H10}）供重叠/撞名用例。 */
  private static GameMap genesisMap() {
    Set<HexCoord> all = HexGrid.withinRadius(new HexCoord(0, 0), 2);
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    double h = 0.3;
    for (HexCoord hex : all) {
      hexes.put(hex, new HexCell(h));
      h = h >= 0.9 ? 0.3 : h + 0.03;
    }
    Map<RegionId, Region> regions = new LinkedHashMap<>();
    regions.put(SEED, Region.of(SEED, "seed", Set.of(H00, H10), RegionMeta.empty()));
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(all, "plains"),
        regions,
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  /** 批行的类型标签（本类顺带把它锚在真装配上）。 */
  @Test
  void batchRowCarriesTheCoreBatchCommandType() {
    try (CoreSimos core = openCore(tempDir)) {
      core.bootstrapGenesis(genesis(genesisMap()));
      core.submitBatch(
          List.of(
              envelope(
                  "map.CreateRegion",
                  "{\"regionId\":\"t4\",\"name\":\"T4\",\"hexes\":[{\"q\":2,\"r\":0}]}")));
      assertThat(core.revisions(MAIN).get(1).commandType())
          .isEqualTo(CommandBus.BATCH_COMMAND_TYPE);
    }
  }
}
