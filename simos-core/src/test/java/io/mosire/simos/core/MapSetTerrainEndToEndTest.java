package io.mosire.simos.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.hex.HexGrid;
import io.mosire.simos.map.ops.TerrainOperations;
import io.mosire.simos.map.spi.SetTerrainHandler;
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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ M8 T3 的**端到端**：真 {@code map.SetTerrain} 命令经 {@link CoreSimos}（真 store、真 checkpoint、真 replay）后，
 * **地形逐值对上、块仍是合法分割、重放一致、两次逐字节相同**，且负例三连**不留 revision**。
 */
class MapSetTerrainEndToEndTest {

  private static final BranchId MAIN = new BranchId("main");

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  /** 多格载荷：半径 2 图上的三个相邻格（一条命令改三格）。 */
  private static final Set<HexCoord> TARGETS =
      new LinkedHashSet<>(Set.of(new HexCoord(0, 0), new HexCoord(1, 0), new HexCoord(0, 1)));

  @TempDir Path tempDir;

  @Test
  void multiHexSetTerrainSurvivesTheRealCommandPath() {
    GameMap genesisMap = genesisMap();

    GameMap afterMap;
    try (CoreSimos core = openCore(tempDir)) {
      core.bootstrapGenesis(genesis(genesisMap));

      CommandResult result = core.submit(envelope(1, TARGETS, "desert"));
      assertThat(result).as("真命令应提交到 (main,2)").isEqualTo(new CommandResult.Committed(ref(2)));

      afterMap = mapAt(core, 2);
    }

    // ── 独立重建期望：纯函数 + apply，不经 DB ────────────────────────────────────
    MapChangeSet cs = TerrainOperations.setTerrain(genesisMap, TARGETS, "desert");
    GameMap expected = MapChangeSet.apply(cs, genesisMap);
    assertThat(afterMap).as("重放读回的图 == 独立重建的期望图").isEqualTo(expected);

    // ── 地形逐值 + 高度逐值不变 ────────────────────────────────────────────────
    for (HexCoord hex : genesisMap.hexes().keySet()) {
      String want = TARGETS.contains(hex) ? "desert" : "plains";
      assertThat(afterMap.terrainAt(hex)).as("格 %s 的地形逐值对上", hex).isEqualTo(want);
      assertThat(afterMap.hexes().get(hex).height())
          .as("格 %s 的高度不变", hex)
          .isEqualTo(genesisMap.hexes().get(hex).height());
    }

    // ── 块仍是合法分割 ────────────────────────────────────────────────────────
    TerrainBlocks.requirePartition(afterMap.hexes(), afterMap.terrainBlocks());
  }

  @Test
  void sameBaseAndSameCommandAreByteIdenticalAcrossTwoRuns(@TempDir Path otherDir) {
    GameMap genesisMap = genesisMap();

    GameMap first;
    try (CoreSimos core = openCore(tempDir)) {
      core.bootstrapGenesis(genesis(genesisMap));
      core.submit(envelope(1, TARGETS, "desert"));
      first = mapAt(core, 2);
    }
    GameMap second;
    try (CoreSimos core = openCore(otherDir)) {
      core.bootstrapGenesis(genesis(genesisMap));
      core.submit(envelope(1, TARGETS, "desert"));
      second = mapAt(core, 2);
    }

    assertThat(second.terrainBlocks().toString())
        .as("两次独立运行的块表 toString 逐字节相同")
        .isEqualTo(first.terrainBlocks().toString());
    assertThat(second.terrainBlocks().keySet())
        .containsExactlyElementsOf(first.terrainBlocks().keySet());
  }

  @Test
  void negativePayloadsAreRejectedAndLeaveNoRevision() {
    try (CoreSimos core = openCore(tempDir)) {
      core.bootstrapGenesis(genesis(genesisMap()));
      long revisionsBefore = core.revisions(MAIN).size();

      assertRejectedNoRevision(
          core, revisionsBefore, envelope(1, Set.of(), "desert"), "hexes 不得为空");
      assertRejectedNoRevision(
          core,
          revisionsBefore,
          envelope(1, Set.of(new HexCoord(0, 0)), "forest"),
          "未知地形类型: forest");
      assertRejectedNoRevision(
          core,
          revisionsBefore,
          envelope(1, Set.of(new HexCoord(9, 9)), "desert"),
          "hex 不在图上: 9_9");
    }
  }

  // ── 助手 ────────────────────────────────────────────────────────────────────

  private static void assertRejectedNoRevision(
      CoreSimos core, long revisionsBefore, CommandEnvelope envelope, String reasonFragment) {
    CommandResult result = core.submit(envelope);
    assertThat(result)
        .as("必须被拒: %s", envelope.payloadJson())
        .isInstanceOf(CommandResult.Rejected.class);
    assertThat(((CommandResult.Rejected) result).reason()).contains(reasonFragment);
    assertThat(core.revisions(MAIN)).as("被拒的命令不得多留一行 revision").hasSize((int) revisionsBefore);
    assertThat(core.head(MAIN).orElseThrow()).as("被拒的命令不动 head").isEqualTo(new RevisionId(1));
  }

  private static CoreSimos openCore(Path dir) {
    return new CoreSimos(new CoreConfig(dir, 100, MAPPER))
        .register(new MapCodec())
        .register(new SetTerrainHandler());
  }

  private static CommandEnvelope envelope(
      long expectedRevision, Set<HexCoord> hexes, String terrain) {
    StringBuilder array = new StringBuilder("[");
    for (HexCoord hex : hexes) {
      if (array.length() > 1) {
        array.append(',');
      }
      array.append("{\"q\":").append(hex.q()).append(",\"r\":").append(hex.r()).append('}');
    }
    array.append(']');
    String payload = "{\"hexes\":" + array + ",\"terrain\":\"" + terrain + "\"}";
    return new CommandEnvelope(
        "cmd-" + terrain + "-" + hexes.size(),
        "corr-" + terrain + "-" + hexes.size(),
        "player:test",
        MAIN,
        new RevisionId(expectedRevision),
        "map.SetTerrain",
        payload);
  }

  private static GameMap mapAt(CoreSimos core, long revision) {
    return ((MapSnapshot) core.replay(ref(revision)).module("map").orElseThrow()).map();
  }

  private static StateRef ref(long revision) {
    return new StateRef(MAIN, new RevisionId(revision));
  }

  private static SimulationState genesis(GameMap map) {
    return new SimulationState(
        new StateMeta(ref(1), T0),
        Map.of("map", new MapSnapshot(ref(1), T0, map)),
        InMemoryInfoSystem.empty());
  }

  /** 半径 2 的 19 格全 {@code plains}，高度逐格递增（用于验证地形改动不动高度）。 */
  private static GameMap genesisMap() {
    Set<HexCoord> all = HexGrid.withinRadius(new HexCoord(0, 0), 2);
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    double h = 0.3;
    for (HexCoord hex : all) {
      hexes.put(hex, new HexCell(h));
      h = h >= 0.9 ? 0.3 : h + 0.03;
    }
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(all, "plains"),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }
}
