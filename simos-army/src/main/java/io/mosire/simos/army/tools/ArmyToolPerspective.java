package io.mosire.simos.army.tools;

import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.state.SimulationState;
import java.util.Optional;

/**
 * 军队工具面的**视角口**：什么看得见 + sd 侧派生出来的军队主子事实。
 *
 * <p>★★ <b>为什么这两件事都必须注入、不许在领域模块里重写</b>（与 B3 试点 {@code simos-map} 的 {@code MapToolPerspective}
 * 同一条纪律）：
 *
 * <ul>
 *   <li>{@link #hexVisible} 是**权限围栏的输入**（{@code ResourceScope} 的段边界前缀匹配，两条通道取并集）。 围栏逻辑抄第二份 =
 *       两处判定可以各自漂移，而"该看见的看不见"与"不该看见的看得见"都不会抛异常 ——本仓最贵的一类失效（AGENT.md §〇 的资源路径纪律）。故实现只有一处（app 的
 *       {@code ToolSupport}）；
 *   <li>{@link #armyGovernance} 要读 {@code Army}（主子 / root 单位）—— ★ 2026-10-12 批次 3d-1：{@code armies}
 *       表随 军队身份搬进 {@code simos-army}，该事实已<b>在本模块自己的切片里</b>；保留这个注入口是为了不让工具直接摸切片提取 （同 {@code
 *       unitVisible} 的"一处实现"纪律），而实现仍是组合根的那一份。
 * </ul>
 */
public interface ArmyToolPerspective {

  /**
   * 某格是否可见 —— **两条通道取并集**：该格自身的资源 {@code map:<mapId>/hex/<q>_<r>}（军队决策人的范围是逐格前缀）， 或该格所属的**某个区域**的资源
   * {@code map:<mapId>/region/<rid>}（国家决策人的范围是区域级前缀）。
   *
   * <p>★ 只看任一条都会让一类决策人读不到自己的视野（两条通道各自落在自己的判据上）；并集**不放大权限**（两条各自 都是调用者可达面内的资源）。
   *
   * <p>★ 交战记录**没有自己的资源路径** ⇒ 可见性按记录所在格判（与 {@code map.city} 同款）；"不可见"与"不存在"因此 在调用点折成逐字相同的拒因。
   */
  boolean hexVisible(ToolContext context, String mapId, GameMap map, HexCoord coord);

  /**
   * {@code Army} 的**两个字段**（军队主子改派工具要的全部事实；★ 2026-10-12 批次 3d-1 前它们住 sd 切片，现在住 army 切片）。
   *
   * <p>★ 只取用得到的字段、不把切片提取逻辑搬进工具：{@code ArmyId.parse} 的合法性判定与"军队不存在"的具名拒仍由实现侧 与调用点各自照旧给出（实现里用的就是
   * {@code ArmyId.parse}，异常类与文案逐字不变）。
   *
   * @param state 读数所在状态
   * @param armyId Army id 文本（调用点已判非空白）
   * @return 该 Army 的两个字段；不存在 ⇒ 空
   */
  Optional<ArmyGovernance> armyGovernance(SimulationState state, String armyId);

  /**
   * {@code io.mosire.simos.army.Army} 的**最小投影**（只含军队工具用得到的两个字段）。
   *
   * <p>★ 字段类型是 unit 域的 {@link UnitId}（army **允许**依赖 unit ⇒ 不必降级成裸字符串）：这样调用点的 {@code
   * army.rootUnit().value()} / {@code army.masterGovUnitId().map(UnitId::value)} 与迁入前逐字相同。
   *
   * @param rootUnit root 单位
   * @param masterGovUnitId 认领的 GOV 单位（未认领 = 空）
   */
  record ArmyGovernance(UnitId rootUnit, Optional<UnitId> masterGovUnitId) {}
}
