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
import io.mosire.simos.map.pathway.PathwayGroup;
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
import java.util.Objects;

/** 用于底图导入的空白世界；只组装空切片，不调用任何业务播种器。 */
public final class BlankEditorWorld {
  private BlankEditorWorld() {}

  public static SimulationState state(String mapId) {
    Objects.requireNonNull(mapId, "mapId");
    StateRef ref = new StateRef(new BranchId("main"), new RevisionId(1));
    SimosTimestamp at = SimosTimestamp.of(0);
    GameMap map =
        GameMap.empty()
            .withTerrainTypes(TerrainCatalog.defaults())
            .withPathwayGroups(PathwayGroup.defaults());
    Map<String, Snapshot> modules = new LinkedHashMap<>();
    modules.put("map", new MapSnapshot(ref, at, map));
    modules.put(
        "social", new SocialSnapshot(ref, at, new SocialData(Map.of(), Map.of(), Map.of())));
    modules.put("unit", new UnitSnapshot(ref, at, UnitState.empty()));
    modules.put("economy", new EconomySnapshot(ref, at, EconomyData.empty()));
    modules.put("actor", new ActorSnapshot(ref, at, ActorData.empty()));
    modules.put("gov", new GovSnapshot(ref, at, GovState.empty()));
    modules.put("army", new ArmySnapshot(ref, at, ArmyData.empty()));
    modules.put("ga", new GaSnapshot(ref, at, GaState.empty()));
    GenesisModuleSlices.ensureEmptyProductionAndCulture(modules, ref, at);
    return new SimulationState(new StateMeta(ref, at), modules, InMemoryInfoSystem.empty());
  }
}
