package io.mosire.simos.app.world;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.ga.GaSnapshot;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图片底图世界的空白创世状态：地图没有 hex，但保留完整地形词表和 10 个模块空切片。
 *
 * <p>它只提供一个“空白画布”；真正的六角网格由前端 {@code map.ImportHexGrid} 命令从用户导入的底图生成。
 */
public final class BlankWorld {

  private static final BranchId MAIN = new BranchId("main");
  private static final SimosTimestamp AT = SimosTimestamp.of(0);

  private BlankWorld() {}

  public static SimulationState state(String mapId) {
    if (mapId == null || mapId.isBlank()) {
      throw new IllegalArgumentException("mapId 不得为空白");
    }
    StateRef ref = new StateRef(MAIN, new RevisionId(1));
    GameMap map =
        new GameMap(
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            TerrainCatalog.defaults(),
            Map.of(),
            Map.of(),
            Map.of(),
            GenerationSpec.defaults(0L));
    Map<String, Snapshot> modules = new LinkedHashMap<>();
    modules.put("map", new MapSnapshot(ref, AT, map));
    modules.put("social", new SocialSnapshot(ref, AT, SocialData.empty()));
    modules.put("unit", new UnitSnapshot(ref, AT, UnitState.empty()));
    modules.put("economy", new EconomySnapshot(ref, AT, EconomyData.empty()));
    modules.put("actor", new ActorSnapshot(ref, AT, ActorData.empty()));
    modules.put("gov", new GovSnapshot(ref, AT, GovState.empty()));
    modules.put("army", new ArmySnapshot(ref, AT, ArmyData.empty()));
    modules.put("ga", new GaSnapshot(ref, AT, GaState.empty()));
    GenesisModuleSlices.ensureEmptyProductionAndCulture(modules, ref, AT);
    return new SimulationState(new StateMeta(ref, AT), modules, InMemoryInfoSystem.empty());
  }
}
