package io.mosire.simos.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.hex.HexGrid;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionBoundary;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.resolve.MapResolver;
import io.mosire.simos.map.spi.CreateRegionHandler;
import io.mosire.simos.map.spi.DeleteRegionHandler;
import io.mosire.simos.map.spi.UpdateRegionHandler;
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
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ M8 T4 的**端到端**：真三条区域命令经 {@link CoreSimos}（真 store、真 checkpoint、真 replay）后，**重叠正例成功、
 * 世界逐值对上、重放一致**，且**负例四连不留 revision**、**删除后从属不悬空**。
 *
 * <p>★ `/api/map/hex` 的 `regions` 与 {@link MapResolver#regionOfHex} 同源（GuiServer 即调它），故本类用后者断言"重叠格
 * 同时列出两个区域"，不是另造一份解析。
 */
class MapRegionEndToEndTest {

  private static final BranchId MAIN = new BranchId("main");

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private static final HexCoord H00 = new HexCoord(0, 0);

  private static final HexCoord H10 = new HexCoord(1, 0);

  private static final HexCoord H20 = new HexCoord(2, 0);

  private static final RegionId SEED = new RegionId("seed");

  private static final RegionId T4 = new RegionId("t4");

  @TempDir Path tempDir;

  @Test
  void createRegionOverlappingAnExistingOneCommitsReplaysAndShowsBothOwners() {
    GameMap genesisMap = genesisMap();
    GameMap afterMap;
    try (CoreSimos core = openCore(tempDir)) {
      core.bootstrapGenesis(genesis(genesisMap));
      long rowsBefore = core.revisions(MAIN).size();

      CommandResult result =
          core.submit(
              envelope(
                  1,
                  "map.CreateRegion",
                  "{\"regionId\":\"t4\",\"name\":\"T4\",\"hexes\":[{\"q\":0,\"r\":0},{\"q\":1,\"r\":0}]}"));
      assertThat(result).as("重叠的新区域应提交到 (main,2)").isEqualTo(new CommandResult.Committed(ref(2)));
      afterMap = mapAt(core, 2);

      assertThat(core.revisions(MAIN)).as("恰好 +1 行 revision").hasSize((int) rowsBefore + 1);
    }

    assertThat(afterMap.regions().keySet()).as("seed 与 t4 并存（不覆盖、不报错）").containsExactly(SEED, T4);
    assertThat(afterMap.regions().get(T4).boundary())
        .as("边界恰由 hexes 算出（Region 构造期自洽）")
        .isEqualTo(RegionBoundary.of(Set.of(H00, H10)));
    // /api/map/hex 的 regions 与 MapResolver.regionOfHex 同源：重叠格同时列出两者，字典序。
    assertThat(MapResolver.regionOfHex(afterMap, H00))
        .as("★ 重叠格 regions 同时列出两者")
        .containsExactly(SEED, T4);
    assertThat(MapResolver.regionOfHex(afterMap, H10)).containsExactly(SEED, T4);
    assertThat(MapResolver.regionOfHex(afterMap, H20)).as("未列入的格无从属").isEmpty();
  }

  @Test
  void updateThenDeleteSurviveTheRealCommandPathAndDeleteDoesNotDangle() {
    GameMap genesisMap = genesisMap();
    try (CoreSimos core = openCore(tempDir)) {
      core.bootstrapGenesis(genesis(genesisMap));

      CommandResult created =
          core.submit(
              envelope(
                  1,
                  "map.CreateRegion",
                  "{\"regionId\":\"t4\",\"name\":\"T4\",\"hexes\":[{\"q\":2,\"r\":0}]}"));
      assertThat(created).isEqualTo(new CommandResult.Committed(ref(2)));
      GameMap atTwo = mapAt(core, 2);
      assertThat(MapResolver.regionOfHex(atTwo, H20)).containsExactly(T4);

      CommandResult updated =
          core.submit(
              envelope(
                  2,
                  "map.UpdateRegion",
                  "{\"regionId\":\"t4\",\"hexes\":[{\"q\":1,\"r\":0},{\"q\":2,\"r\":0}],"
                      + "\"meta\":{\"tag\":\"T\"}}"));
      assertThat(updated).isEqualTo(new CommandResult.Committed(ref(3)));
      GameMap atThree = mapAt(core, 3);
      assertThat(atThree.regions().get(T4).hexes()).containsExactlyInAnyOrder(H10, H20);
      assertThat(atThree.regions().get(T4).meta()).isEqualTo(new RegionMeta(null, "T", null, null));
      assertThat(MapResolver.regionOfHex(atThree, H10))
          .as("改后与 seed 重叠，同时列出两者")
          .containsExactly(SEED, T4);

      CommandResult deleted = core.submit(envelope(3, "map.DeleteRegion", "{\"regionId\":\"t4\"}"));
      assertThat(deleted).isEqualTo(new CommandResult.Committed(ref(4)));
      GameMap atFour = mapAt(core, 4);
      assertThat(atFour.regions().keySet()).as("只剩 seed").containsExactly(SEED);
      assertThat(MapResolver.regionOfHex(atFour, H10)).as("共有格仍归 seed").containsExactly(SEED);
      assertThat(MapResolver.regionOfHex(atFour, H20)).as("★ 只属 t4 的格删除后无从属，不悬空").isEmpty();
      assertThat(core.revisions(MAIN)).as("create/update/delete 各留一行").hasSize(4);
      assertThat(core.head(MAIN).orElseThrow()).isEqualTo(new RevisionId(4));
    }
  }

  @Test
  void negativeCommandsAreRejectedAndLeaveNoRevision() {
    try (CoreSimos core = openCore(tempDir)) {
      core.bootstrapGenesis(genesis(genesisMap()));
      long rowsBefore = core.revisions(MAIN).size();

      assertRejectedNoRevision(
          core,
          rowsBefore,
          "map.CreateRegion",
          "{\"regionId\":\"seed\",\"name\":\"撞名\",\"hexes\":[{\"q\":2,\"r\":0}]}",
          "区域已存在: seed");
      assertRejectedNoRevision(
          core,
          rowsBefore,
          "map.UpdateRegion",
          "{\"regionId\":\"nope\",\"meta\":{\"tag\":\"T\"}}",
          "区域不存在: nope");
      assertRejectedNoRevision(
          core, rowsBefore, "map.DeleteRegion", "{\"regionId\":\"nope\"}", "区域不存在: nope");
      assertRejectedNoRevision(
          core,
          rowsBefore,
          "map.CreateRegion",
          "{\"regionId\":\"t4\",\"name\":\"空\",\"hexes\":[]}",
          "hexes 不得为空");
      assertRejectedNoRevision(
          core,
          rowsBefore,
          "map.CreateRegion",
          "{\"regionId\":\"t4\",\"name\":\"图外\",\"hexes\":[{\"q\":9999,\"r\":9999}]}",
          "hex 不在图上: 9999_9999");
    }
  }

  // ── 助手 ────────────────────────────────────────────────────────────────────

  private static void assertRejectedNoRevision(
      CoreSimos core, long revisionsBefore, String type, String payload, String reasonFragment) {
    CommandResult result = core.submit(envelope(1, type, payload));
    assertThat(result).as("必须被拒: %s %s", type, payload).isInstanceOf(CommandResult.Rejected.class);
    assertThat(((CommandResult.Rejected) result).reason()).contains(reasonFragment);
    assertThat(core.revisions(MAIN)).as("被拒的命令不得多留一行 revision").hasSize((int) revisionsBefore);
    assertThat(core.head(MAIN).orElseThrow()).as("被拒的命令不动 head").isEqualTo(new RevisionId(1));
  }

  private static CoreSimos openCore(Path dir) {
    return new CoreSimos(new CoreConfig(dir, 100, MAPPER))
        .register(new MapCodec())
        .register(new CreateRegionHandler())
        .register(new UpdateRegionHandler())
        .register(new DeleteRegionHandler());
  }

  private static CommandEnvelope envelope(long expectedRevision, String type, String payload) {
    return new CommandEnvelope(
        "cmd-" + type,
        "corr-" + type,
        "player:test",
        MAIN,
        new RevisionId(expectedRevision),
        type,
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

  /** 半径 2 的 19 格全 {@code plains}，并预置一个区域 {@code seed}（{@code H00,H10}）供重叠正例。 */
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
}
