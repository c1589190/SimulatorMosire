package io.mosire.simos.app.tools.map;

import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.tools.MapToolViews;
import io.mosire.simos.util.facet.FacetEntry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * {@link MapToolViews} 的宿主实现（组合根）：**每条都只做一件事——转调本仓唯一那份 view 函数**。
 *
 * <p>★★ <b>为什么是转调、不是重写</b>：GUI 与 MCP 共用同一份视图（{@code ApiViews} / {@code ToolSupport} 的类注： "GUI
 * 端点加字段、MCP 工具没加"这类漂移结构上必须不可能）。工具搬进 {@code simos-map} 后若在域里另写一份形状， 就正好复活那条纪律要消灭的形态 ⇒
 * 形状**注入**、实现只有一处。
 *
 * <p>★ 本类对下放工具的行为是**恒等**的：同一个 {@code map}、同一组可见性谓词、同一个 {@code neighbors} 进、 同一个 {@code Map}
 * 出（连插入序都不变）。
 */
public final class AppMapToolViews implements MapToolViews {

  @Override
  public Map<String, Object> hexCoord(HexCoord coord) {
    return ToolSupport.hexCoord(coord);
  }

  @Override
  public List<Map<String, Object>> facets(List<FacetEntry> entries) {
    return ToolSupport.facets(entries);
  }

  @Override
  public Map<String, Object> mapBlock(HexCoord coord, String terrain, List<HexCoord> hexes) {
    return ApiViews.mapBlock(coord, terrain, hexes);
  }

  @Override
  public Map<String, Object> regionDetail(Region region) {
    return ApiViews.regionDetail(region);
  }

  @Override
  public Map<String, Object> pathResult(boolean reachable, List<HexCoord> path) {
    return ApiViews.pathResult(reachable, path);
  }

  @Override
  public Map<String, Object> overview(
      String mapId,
      GameMap map,
      Predicate<HexCoord> hexVisible,
      Predicate<RegionId> regionVisible,
      Optional<Set<String>> neighbors) {
    return ToolSupport.mapOverview(mapId, map, hexVisible, regionVisible, neighbors);
  }
}
