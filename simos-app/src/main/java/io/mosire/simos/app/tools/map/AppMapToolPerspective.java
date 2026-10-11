package io.mosire.simos.app.tools.map;

import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.access.HexOwner;
import io.mosire.simos.app.access.NeighborNations;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.tools.MapToolPerspective;
import io.mosire.simos.util.state.SimulationState;
import java.util.Optional;
import java.util.Set;

/**
 * {@link MapToolPerspective} 的宿主实现（组合根）：**可见性判定**逐条转调 {@link ToolSupport}（围栏逻辑只有那一份），
 * **"格归谁"与"我的邻国是谁"**逐条转调 {@link HexOwner} / {@link NeighborNations}（那两个类要用 sd 域的 {@code
 * NationTag}/{@code NationId}，地图模块不认识 sd——铁律 3）。
 *
 * <p>★ 本类对下放工具的行为是**恒等**的：同一个 {@link ToolContext}、同一份 {@link GameMap} 进去，同一个布尔 / 同一个有序集合出来。
 */
public final class AppMapToolPerspective implements MapToolPerspective {

  @Override
  public boolean hexVisible(ToolContext context, String mapId, GameMap map, HexCoord coord) {
    return ToolSupport.hexVisible(context, mapId, map, coord);
  }

  @Override
  public boolean regionVisible(ToolContext context, String mapId, RegionId regionId) {
    return ToolSupport.regionVisible(context, mapId, regionId);
  }

  @Override
  public Set<String> hexNations(GameMap map, HexCoord coord) {
    return HexOwner.nationsOf(map, coord);
  }

  @Override
  public Optional<Set<String>> viewerNeighbors(
      ToolContext context, SimulationState state, GameMap map) {
    return DecisionCallerFactory.viewerNationOf(context, state)
        .map(nation -> NeighborNations.of(map, nation));
  }
}
