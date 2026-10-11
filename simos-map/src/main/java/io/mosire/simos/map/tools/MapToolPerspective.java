package io.mosire.simos.map.tools;

import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.Optional;
import java.util.Set;

/**
 * 地图工具面的**视角口**：什么看得见、这格归谁、我的邻国是谁。
 *
 * <p>★★ <b>为什么这三件事都必须注入、不许在领域模块里重写</b>：
 *
 * <ul>
 *   <li>{@link #hexVisible} / {@link #regionVisible} 是**权限围栏的输入**（{@code ResourceScope} 的段边界前缀
 *       匹配）。围栏逻辑抄第二份 = 两处判定可以各自漂移，而"该看见的看不见"与"不该看见的看得见"都不会抛异常 ——本仓最贵的一类失效（AGENT.md §〇
 *       的资源路径纪律）。故实现只有一处（app 的 {@code ToolSupport}）；
 *   <li>{@link #hexNations} 要用 sd 域的 {@code NationTag}（{@code nation:<id>} 前缀是那个类的常量）， {@link
 *       #viewerNeighbors} 要用 sd 的决策人身份与 {@code NeighborNations} —— 地图模块**永远不认识 sd** （铁律 3；enforcer
 *       禁列）⇒ 只能由认识两侧的 app 实现。
 * </ul>
 */
public interface MapToolPerspective {

  /**
   * 某格是否可见 —— **两条通道取并集**：该格自身的资源 {@code map:<mapId>/hex/<q>_<r>}（军队决策人的范围是逐格前缀）， 或该格所属的**某个区域**的资源
   * {@code map:<mapId>/region/<rid>}（国家决策人的范围是区域级前缀）。
   *
   * <p>★ 只看任一条都会让一类决策人读不到自己的视野（两条通道各自落在自己的判据上）；并集**不放大权限**（两条各自 都是调用者可达面内的资源）。
   */
  boolean hexVisible(ToolContext context, String mapId, GameMap map, HexCoord coord);

  /** 某区域是否可见：{@code map:<mapId>/region/<rid>}。 */
  boolean regionVisible(ToolContext context, String mapId, RegionId regionId);

  /**
   * 该格归属的国家（可能多个、可能为空）——**有序**返回（无序集会让同一个状态产出不同字节）。
   *
   * <p>★ 一个格可以同时落在若干不同国家的区域里（M8-U1：格只是地形块，兼容多种从属）⇒ 不许读成"唯一归属"。 ★
   * **不按"区域是否可见"过滤**：格的可见性由调用点判过（{@code map.hex} 先 {@code hexVisible} 才产出视图），
   * 这里给的是"这一格归谁"这个被授予的派生事实本身。
   */
  Set<String> hexNations(GameMap map, HexCoord coord);

  /**
   * 调用者若是**国家决策人**，它与哪些国家相邻；其余身份（GM / 军队决策人 / 认不出身份）一律**空**
   * （fail-closed：判不了"你是谁"就不给派生信息，而不是"反正没害处"地给一份）。
   *
   * <p>★ 口径逐条：本国不算邻国；邻接是逐 hex 的六邻（不是"距离近"）；只有 {@code nation:} tag 算国家。
   */
  Optional<Set<String>> viewerNeighbors(ToolContext context, SimulationState state, GameMap map);
}
