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
 *   <li>{@link #armyGovernance} 要读 **sd 切片**的 {@code Army}（主子 / root 单位）—— 军队模块**永远不认识 sd** （铁律
 *       3；enforcer 禁列、且 sd 不在本模块的传递可见面里）⇒ 只能由认识两侧的 app 把用得到的那两个字段取出来。
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
   * sd 切片里 {@code Army} 的**两个字段**（军队主子改派工具要的全部 sd 侧事实）。
   *
   * <p>★ 只取用得到的字段、不搬 sd 的类型进 army：{@code ArmyId.parse} 的合法性判定与"军队不存在"的具名拒仍由实现侧 与调用点各自照旧给出（实现里用的就是
   * sd 的 {@code ArmyId.parse}，异常类与文案逐字不变）。
   *
   * @param state 读数所在状态
   * @param armyId Army id 文本（调用点已判非空白）
   * @return 该 Army 的两个字段；不存在 ⇒ 空
   */
  Optional<ArmyGovernance> armyGovernance(SimulationState state, String armyId);

  /**
   * {@code sd.model.Army} 的**最小投影**（只含军队工具用得到的两个字段）。
   *
   * <p>★ 字段类型是 unit 域的 {@link UnitId}（army **允许**依赖 unit ⇒ 不必降级成裸字符串）：这样调用点的 {@code
   * army.rootUnit().value()} / {@code army.masterGovUnitId().map(UnitId::value)} 与迁入前逐字相同。
   *
   * @param rootUnit root 单位
   * @param masterGovUnitId 认领的 GOV 单位（未认领 = 空）
   */
  record ArmyGovernance(UnitId rootUnit, Optional<UnitId> masterGovUnitId) {}
}
