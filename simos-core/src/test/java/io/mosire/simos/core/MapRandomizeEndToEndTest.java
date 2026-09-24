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
import io.mosire.simos.map.ops.RandomizeOperations;
import io.mosire.simos.map.spi.RandomizeRegionHandler;
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
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ M8 T6 的**端到端**：真 {@code map.RandomizeRegion} 经 {@link CoreSimos} 后**同 seed 逐字节相同、不同 seed
 * 直方图不同**、选区外不动、高度不动，且负例**不留 revision**。（T5 是 {@code map.SetEdge}，别与本类混记。）
 *
 * <p>★ 2026-09-24 起：**两种地形由载荷给**（{@code terrainA}/{@code terrainB}，无默认值）。本类用的是**非旧固定配方**的一对 （{@code
 * plateau}/{@code low_hills}）⇒ "handler 忽略这两个字段"的实现当场红；缺字段/词表外地形各有负例。
 */
class MapRandomizeEndToEndTest {

  private static final BranchId MAIN = new BranchId("main");

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private static final int RADIUS = 6;

  private static final long SEED = 7L;

  private static final long OTHER_SEED = 8L;

  /**
   * ★ 载荷给的两地形（2026-09-24 起由调用方给）：**故意取非旧固定配方**（旧配方是 plains/desert）——夹具初值是 {@code
   * mountains}，故"handler 忽略这两个字段、仍用旧固定配方"的实现会在下面的断言里当场红。
   */
  private static final String TERRAIN_A = "plateau";

  private static final String TERRAIN_B = "low_hills";

  /** 选区：自然序前 5 格之外的全部格（保证选区外非空）。 */
  private static final Set<HexCoord> SELECTION = selection();

  @TempDir Path tempDir;

  @Test
  void seededRandomizeSurvivesTheRealCommandPath() {
    GameMap genesisMap = genesisMap();

    GameMap afterMap;
    try (CoreSimos core = openCore(tempDir)) {
      core.bootstrapGenesis(genesis(genesisMap));

      CommandResult result = core.submit(randomizeEnvelope(1, SELECTION, SEED));
      assertThat(result).as("真命令应提交到 (main,2)").isEqualTo(new CommandResult.Committed(ref(2)));

      afterMap = mapAt(core, 2);
    }

    // 独立重建期望：纯函数 + apply，不经 DB（地形对必须与载荷里的一致）。
    GameMap expected =
        MapChangeSet.apply(
            RandomizeOperations.randomize(genesisMap, SELECTION, TERRAIN_A, TERRAIN_B, SEED),
            genesisMap);
    assertThat(afterMap).as("重放读回的图 == 独立重建的期望图").isEqualTo(expected);

    // 选区外不动 + 高度逐值不变。
    Set<HexCoord> outside = new LinkedHashSet<>(genesisMap.hexes().keySet());
    outside.removeAll(SELECTION);
    for (HexCoord hex : outside) {
      assertThat(afterMap.terrainAt(hex)).as("选区外格 %s 地形不变", hex).isEqualTo("mountains");
    }
    // ★ 两种地形来自载荷（不是旧的固定配方）：选区内只出现 A/B，且两侧都真的出现（防恒真）。
    Map<String, Integer> hist = histogram(afterMap);
    assertThat(hist.keySet())
        .as("选区内只出现载荷给的两种地形 + 选区外的 mountains；旧配方的 plains/desert 不得出现")
        .containsOnly("mountains", TERRAIN_A, TERRAIN_B);
    System.out.println("[T6-RANDOMIZE-E2E] hist=" + hist);
    assertThat(hist.get(TERRAIN_A)).as("A 侧确有格").isPositive();
    assertThat(hist.get(TERRAIN_B)).as("B 侧确有格").isPositive();
    for (HexCoord hex : genesisMap.hexes().keySet()) {
      assertThat(afterMap.hexes().get(hex).height())
          .as("格 %s 高度不变", hex)
          .isEqualTo(genesisMap.hexes().get(hex).height());
    }
    TerrainBlocks.requirePartition(afterMap.hexes(), afterMap.terrainBlocks());
  }

  /** 两个独立 store、同 seed 同一命令 ⇒ {@code terrainBlocks} 逐字节相同。 */
  @Test
  void sameSeedIsByteIdenticalAcrossTwoRuns(@TempDir Path otherDir) {
    GameMap genesisMap = genesisMap();

    GameMap first;
    try (CoreSimos core = openCore(tempDir)) {
      core.bootstrapGenesis(genesis(genesisMap));
      core.submit(randomizeEnvelope(1, SELECTION, SEED));
      first = mapAt(core, 2);
    }
    GameMap second;
    try (CoreSimos core = openCore(otherDir)) {
      core.bootstrapGenesis(genesis(genesisMap));
      core.submit(randomizeEnvelope(1, SELECTION, SEED));
      second = mapAt(core, 2);
    }

    assertThat(second.terrainBlocks().toString())
        .as("同 seed 两次独立运行的块表 toString 逐字节相同")
        .isEqualTo(first.terrainBlocks().toString());
    assertThat(second.terrainBlocks().keySet())
        .containsExactlyElementsOf(first.terrainBlocks().keySet());
  }

  /** 同 base、不同 seed ⇒ **直方图不同**。 */
  @Test
  void differentSeedGivesADifferentHistogram(@TempDir Path otherDir) {
    GameMap genesisMap = genesisMap();

    GameMap of7;
    try (CoreSimos core = openCore(tempDir)) {
      core.bootstrapGenesis(genesis(genesisMap));
      core.submit(randomizeEnvelope(1, SELECTION, SEED));
      of7 = mapAt(core, 2);
    }
    GameMap of8;
    try (CoreSimos core = openCore(otherDir)) {
      core.bootstrapGenesis(genesis(genesisMap));
      core.submit(randomizeEnvelope(1, SELECTION, OTHER_SEED));
      of8 = mapAt(core, 2);
    }

    assertThat(histogram(of8)).as("不同 seed 的直方图必须不同").isNotEqualTo(histogram(of7));
    System.out.println("[T6-RANDOMIZE-E2E] seed7=" + histogram(of7));
    System.out.println("[T6-RANDOMIZE-E2E] seed8=" + histogram(of8));
  }

  @Test
  void negativePayloadsAreRejectedAndLeaveNoRevision() {
    try (CoreSimos core = openCore(tempDir)) {
      core.bootstrapGenesis(genesis(genesisMap()));
      long revisionsBefore = core.revisions(MAIN).size();

      assertRejectedNoRevision(
          core,
          revisionsBefore,
          rawEnvelope(
              "{\"hexes\":[{\"q\":0,\"r\":0}],\"terrainA\":\""
                  + TERRAIN_A
                  + "\",\"terrainB\":\""
                  + TERRAIN_B
                  + "\"}"),
          "字段 seed 必须是整数");
      // ★ 两种地形也没有默认值（2026-09-24）：缺任一侧 ⇒ 拒，且**指出缺的是哪个字段**。
      assertRejectedNoRevision(
          core,
          revisionsBefore,
          rawEnvelope(
              "{\"hexes\":[{\"q\":0,\"r\":0}],\"terrainB\":\"" + TERRAIN_B + "\",\"seed\":7}"),
          "字段 terrainA 必须是字符串");
      assertRejectedNoRevision(
          core,
          revisionsBefore,
          rawEnvelope(
              "{\"hexes\":[{\"q\":0,\"r\":0}],\"terrainA\":\"" + TERRAIN_A + "\",\"seed\":7}"),
          "字段 terrainB 必须是字符串");
      // ★ 词表外地形由域层拒（消息是词表自己的，不包不吞）。
      assertRejectedNoRevision(
          core,
          revisionsBefore,
          rawEnvelope(
              "{\"hexes\":[{\"q\":0,\"r\":0}],\"terrainA\":\"forest\",\"terrainB\":\""
                  + TERRAIN_B
                  + "\",\"seed\":7}"),
          "未知地形类型: forest");
      assertRejectedNoRevision(
          core, revisionsBefore, randomizeEnvelope(1, Set.of(), SEED), "hexes 不得为空");
      assertRejectedNoRevision(
          core,
          revisionsBefore,
          randomizeEnvelope(1, Set.of(new HexCoord(9999, 9999)), SEED),
          "hex 不在图上: 9999_9999");
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
        .register(new RandomizeRegionHandler());
  }

  private static CommandEnvelope randomizeEnvelope(
      long expectedRevision, Set<HexCoord> hexes, long seed) {
    StringBuilder array = new StringBuilder("[");
    for (HexCoord hex : hexes) {
      if (array.length() > 1) {
        array.append(',');
      }
      array.append("{\"q\":").append(hex.q()).append(",\"r\":").append(hex.r()).append('}');
    }
    array.append(']');
    // ★ 两种地形随载荷走（2026-09-24）：用**非旧固定配方**的一对，使"handler 忽略这两个字段"当场红。
    return rawEnvelope(
        "{\"hexes\":"
            + array
            + ",\"terrainA\":\""
            + TERRAIN_A
            + "\",\"terrainB\":\""
            + TERRAIN_B
            + "\",\"seed\":"
            + seed
            + "}");
  }

  private static CommandEnvelope rawEnvelope(String payload) {
    String id = "cmd-rand-" + Math.abs(payload.hashCode());
    return new CommandEnvelope(
        id, id, "player:test", MAIN, new RevisionId(1), "map.RandomizeRegion", payload);
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

  private static Set<HexCoord> selection() {
    var all = HexGrid.withinRadius(new HexCoord(0, 0), RADIUS);
    var ordered = all.stream().sorted().toList();
    return new LinkedHashSet<>(ordered.subList(5, ordered.size()));
  }

  private static Map<String, Integer> histogram(GameMap map) {
    Map<String, Integer> hist = new TreeMap<>();
    for (String terrain : map.terrainIndex().values()) {
      hist.merge(terrain, 1, Integer::sum);
    }
    return hist;
  }

  /** 半径 6 的 127 格全 {@code mountains}，高度逐格递增。 */
  private static GameMap genesisMap() {
    Set<HexCoord> all = HexGrid.withinRadius(new HexCoord(0, 0), RADIUS);
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    double h = 0.3;
    for (HexCoord hex : all) {
      hexes.put(hex, new HexCell(h));
      h = h >= 0.9 ? 0.3 : h + 0.03;
    }
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(all, "mountains"),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }
}
