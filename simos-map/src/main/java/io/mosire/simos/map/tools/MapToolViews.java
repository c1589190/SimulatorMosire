package io.mosire.simos.map.tools;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.util.facet.FacetEntry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 地图工具面的**视图口**：工具算事实，**形状由宿主给**。
 *
 * <p>★★ <b>为什么形状不写在工具里</b>：这些视图（GUI/MCP 共用的那一份）今天住在 app（{@code ApiViews} 与 {@code ToolSupport}
 * 的视图装配），且本仓有一条既有硬纪律——"GUI 与 MCP **共用同一个函数**，不许各写一份同形视图" （{@code ToolSupport.unit}/{@code
 * population} 的类注：加字段就得记得改两处，那是漂移的温床）。工具下放到 {@code simos-map} 后，若把形状抄一份进领域模块，就正好复活那条纪律要消灭的形态 ⇒
 * 视图**注入**：实现留在 app， 逐条转调**同一份**既有函数（输出因此逐字节不变，I-G1）。
 *
 * <p>★ 本接口只能住在 {@code simos-map}：它的签名里有 {@link GameMap}/{@link HexCoord}/{@link Region} ——契约层
 * {@code simos-ga-spi} 不许依赖任何领域模块（I-G2），所以"带领域类型的端口"必须由该领域自己声明、 由 app 实现（依赖倒置）。
 */
public interface MapToolViews {

  /** 单格坐标视图：{@code {q, r}}（插入序）。 */
  Map<String, Object> hexCoord(HexCoord coord);

  /** facet 条目 → 视图清单（{@code namespace/label/typeName/value} 四项，插入序）。 */
  List<Map<String, Object>> facets(List<FacetEntry> entries);

  /**
   * 地形块视图（{@code simos.map.block}）：{@code {q, r, terrain, hexCount, hexes[]}}。
   *
   * @param coord 块内任取一格（工具已判它在图上）
   * @param terrain 该格地形
   * @param hexes 整块成员格（顺序原样，不重排）
   */
  Map<String, Object> mapBlock(HexCoord coord, String terrain, List<HexCoord> hexes);

  /**
   * 区域详情视图（{@code simos.map.region}）：{@code {id, name, meta, hexCount, hexes[]}}（成员格按 q 再 r 升序）。
   */
  Map<String, Object> regionDetail(Region region);

  /**
   * 寻路试算视图（{@code simos.map.path}）：{@code {reachable, path[]}}。
   *
   * @param reachable 是否可达
   * @param path 可达时的路径；不可达时实现按既有口径发空表
   */
  Map<String, Object> pathResult(boolean reachable, List<HexCoord> path);

  /**
   * 地图总览视图（{@code simos.map.overview}）：逐项按可见性筛（不是整调拒）。
   *
   * @param mapId 地图 id
   * @param map 当前地图
   * @param hexVisible 逐格可见性（调用者现算；越界格不进结果，{@code hexCount} 也取可见格数）
   * @param regionVisible 逐区域可见性
   * @param neighbors 邻国标识；**空 ⇒ 整个字段不出现**（"你不是国家决策人"与"你没有邻国"是两件事）
   */
  Map<String, Object> overview(
      String mapId,
      GameMap map,
      Predicate<HexCoord> hexVisible,
      Predicate<RegionId> regionVisible,
      Optional<Set<String>> neighbors);
}
