package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.core.state.WorldChangeSet;
import io.mosire.simos.core.store.CheckpointEncoder;
import io.mosire.simos.core.store.CheckpointStore;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.info.InMemoryInfoSystem;
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
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A6 的端到端验收：经 **{@code Shell}**（真装配点）提交 {@code map.DeleteRegion}，带国家 tag 的区域被 {@code
 * RegionDeleteGuard} 拒、去 tag 的放行。★ 判别力在"Shell 真的装了这个 guard"（m1：删掉那行装配 ⇒ 第一条应删除成功 ⇒ 红）。
 */
class SdRegionDeleteGuardEndToEndTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final RegionId TAGGED = new RegionId("r1");
  private static final RegionId UNTAGGED = new RegionId("r2");
  private static final int CHECKPOINT_INTERVAL = 100;

  @TempDir Path tempDir;

  private Shell shell;

  @BeforeEach
  void startShell() {
    seedGenesis();
    shell = Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0, 0));
  }

  @AfterEach
  void stopShell() {
    if (shell != null) {
      shell.close();
    }
  }

  @Test
  void taggedRegionDeleteIsRejectedAndLeavesNoRevision() {
    CommandResult result = delete(TAGGED);
    assertThat(result).isInstanceOf(CommandResult.Rejected.class);
    assertThat(((CommandResult.Rejected) result).reason()).contains("r1").contains("国家 tag");
    assertThat(shell.coreSimos().head(MAIN).orElseThrow())
        .as("拒绝是原子的：head 不动")
        .isEqualTo(new RevisionId(1));
  }

  @Test
  void untaggedRegionDeleteIsAllowed() {
    CommandResult result = delete(UNTAGGED);
    assertThat(result).as("去 tag ⇒ 放行（guard 不是恒拒）").isInstanceOf(CommandResult.Committed.class);
    assertThat(shell.coreSimos().head(MAIN).orElseThrow()).isEqualTo(new RevisionId(2));
  }

  @Test
  void otherCommandsAreUnaffectedByTheGuard() {
    CommandResult result =
        shell
            .coreSimos()
            .submit(
                new CommandEnvelope(
                    "cmd-put",
                    "cmd-put",
                    "agent:test",
                    MAIN,
                    new RevisionId(1),
                    "sd.PutInfo",
                    "{\"address\":\"map:Map1\",\"key\":\"k\",\"value\":\"v\"}"));
    assertThat(result).isInstanceOf(CommandResult.Committed.class);
  }

  private CommandResult delete(RegionId regionId) {
    return shell
        .coreSimos()
        .submit(
            new CommandEnvelope(
                "cmd-del",
                "cmd-del",
                "player:gui",
                MAIN,
                new RevisionId(1),
                "map.DeleteRegion",
                "{\"regionId\":\"" + regionId.value() + "\"}"));
  }

  private void seedGenesis() {
    try (SqliteStore seedStore = SqliteStore.open(tempDir.resolve(CoreSimos.DB_FILE_NAME))) {
      new Timeline(seedStore, CHECKPOINT_INTERVAL)
          .appendRevision(
              new RevisionRow(
                  MAIN,
                  new RevisionId(1),
                  Optional.empty(),
                  T0,
                  "cmd-genesis",
                  "corr-genesis",
                  "player:local",
                  "core.AdvanceTime",
                  Timeline.changeSetJson(WorldChangeSet.empty())));
    }
    SimulationState genesis =
        new SimulationState(
            new StateMeta(ref(1), T0),
            Map.of(
                "map", new MapSnapshot(ref(1), T0, map()),
                "sd", new SdSnapshot(ref(1), T0, SdState.empty())),
            InMemoryInfoSystem.empty());
    new CheckpointStore(tempDir)
        .write(ref(1), CheckpointEncoder.encode(genesis, List.of(new MapCodec(), new SdCodec())));
  }

  private static GameMap map() {
    TerrainType desert = TerrainCatalog.of("desert");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(HEX, new HexCell(0.5));
    Map<RegionId, Region> regions = new LinkedHashMap<>();
    regions.put(
        TAGGED,
        Region.of(TAGGED, "国家区域", Set.of(HEX), new RegionMeta(null, "nation:n1", null, null)));
    regions.put(UNTAGGED, Region.of(UNTAGGED, "无标签区域", Set.of(HEX), RegionMeta.empty()));
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(desert.key(), desert);
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(hexes.keySet(), desert.key()),
        regions,
        Map.of(),
        terrainTypes,
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  private static StateRef ref(long revision) {
    return new StateRef(MAIN, new RevisionId(revision));
  }
}
