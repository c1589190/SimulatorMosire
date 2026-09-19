package io.mosire.simos.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.BlockId;
import io.mosire.simos.map.block.TerrainBlock;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.generate.RegionRandomizer;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.hex.HexGrid;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ M9 T6 §5.5 的**端到端**：真命令（{@link RegionRandomizer} 路径）经 {@link CoreSimos}（真 store、真 checkpoint、真
 * replay）后，**块与逐格高度都逐值对上**。
 *
 * <p>handler 是 test-only 的（M8 的 {@code map.RandomizeRegion} 尚未在本基线落地），但它走的是**真**的 {@code Command →
 * ChangeSet → Revision} 全链：信封 → handler → {@link MapChangeSet} → 落盘 → 重放读回。
 */
class MapTerrainBlockEndToEndTest {

  private static final BranchId MAIN = new BranchId("main");

  private static final RegionId CORE = new RegionId("core");

  private static final long SEED = 7L;

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  @TempDir Path tempDir;

  @Test
  void randomizedTerrainSurvivesTheRealCommandPathWithBlocksAndHeightsIntact() {
    GameMap genesisMap = genesisMap();
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref(1), T0),
            Map.of("map", new MapSnapshot(ref(1), T0, genesisMap)),
            InMemoryInfoSystem.empty());

    GameMap afterMap;
    try (CoreSimos core =
        new CoreSimos(new CoreConfig(tempDir, 100, MAPPER))
            .register(new MapCodec())
            .register(new RandomizeHandler())) {
      core.bootstrapGenesis(genesis);

      CommandResult result =
          core.submit(
              new CommandEnvelope(
                  "cmd-1",
                  "corr-1",
                  "player:test",
                  MAIN,
                  new RevisionId(1),
                  "map.RandomizeTest",
                  "{\"region\":\"core\"}"));
      assertThat(result).as("真命令应提交到 (main,2)").isEqualTo(new CommandResult.Committed(ref(2)));

      afterMap = ((MapSnapshot) core.replay(ref(2)).module("map").orElseThrow()).map();
    }

    // ── 独立重建期望：直接调纯函数 + apply，不经 DB ─────────────────────────────
    MapChangeSet cs = RegionRandomizer.randomize(genesisMap, CORE, "plains", "desert", 0.5, SEED);
    GameMap expected = MapChangeSet.apply(cs, genesisMap);

    assertThat(afterMap).as("重放读回的图 == 独立重建的期望图").isEqualTo(expected);

    // ── 高度逐值不变 ──────────────────────────────────────────────────────────
    for (HexCoord hex : genesisMap.hexes().keySet()) {
      assertThat(afterMap.hexes().get(hex).height())
          .as("格 %s 的高度不变", hex)
          .isEqualTo(genesisMap.hexes().get(hex).height());
    }

    // ── 地形逐值：被改的 7 格恰为 core，且块与 hex 一致 ─────────────────────────
    int changed = 0;
    for (HexCoord hex : genesisMap.hexes().keySet()) {
      String before = genesisMap.terrainAt(hex);
      String after = afterMap.terrainAt(hex);
      if (!before.equals(after)) {
        assertThat(genesisMap.regions().get(CORE).contains(hex))
            .as("被改的格 %s 必在 core 里", hex)
            .isTrue();
        assertThat(after).as("新地形只能是 A/B").isIn("plains", "desert");
        changed++;
      }
    }
    assertThat(changed).as("core 的 7 格里被改成 desert 的数目（实测）").isGreaterThan(0);

    // ── 块：分割不变式 + 重建确定性 ───────────────────────────────────────────
    TerrainBlocks.requirePartition(afterMap.hexes(), afterMap.terrainBlocks());
    Map<BlockId, TerrainBlock> rebuilt = TerrainBlocks.split(afterMap.terrainIndex());
    assertThat(rebuilt.keySet())
        .as("按 terrainIndex 重切与存档里的块键**作为集合**相同（顺序由各自的构建路径决定，不保证一致）")
        .containsExactlyInAnyOrderElementsOf(afterMap.terrainBlocks().keySet());
  }

  // ── 夹具与 handler ──────────────────────────────────────────────────────────

  private static StateRef ref(long revision) {
    return new StateRef(MAIN, new RevisionId(revision));
  }

  /** 半径 2 的 19 格全 {@code plains}，区域 core = 半径 1 的 7 格。 */
  private static GameMap genesisMap() {
    Set<HexCoord> all = HexGrid.withinRadius(new HexCoord(0, 0), 2);
    Set<HexCoord> core = HexGrid.withinRadius(new HexCoord(0, 0), 1);
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    double h = 0.3;
    for (HexCoord hex : all) {
      hexes.put(hex, new HexCell(h));
      h = h >= 0.9 ? 0.3 : h + 0.03;
    }
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(all, "plains"),
        Map.of(CORE, Region.of(CORE, "核心", core, RegionMeta.empty())),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  /** test-only 的 map 命令：固定 seed 调 {@link RegionRandomizer}，使期望能在测试里独立重算。 */
  private static final class RandomizeHandler implements CommandHandler {

    @Override
    public String type() {
      return "map.RandomizeTest";
    }

    @Override
    public HandlerOutcome handle(SimulationState state, String payloadJson) {
      Snapshot slice = state.module("map").orElseThrow();
      GameMap map = ((MapSnapshot) slice).map();
      return new HandlerOutcome.Applied(
          RegionRandomizer.randomize(map, CORE, "plains", "desert", 0.5, SEED));
    }
  }
}
