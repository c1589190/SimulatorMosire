package io.mosire.simos.unit.tools;

import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.state.SimulationState;

/**
 * 单位工具面的**视角口**：什么看得见、这个 id 是不是已被占用、这个 GOV 的国库家户是谁、落点算城还是乡。
 *
 * <p>★★ <b>为什么这四件事都必须注入、不许在领域模块里重写</b>：
 *
 * <ul>
 *   <li>{@link #unitVisible} 是**权限围栏的输入**（{@code ResourceScope} 的段边界前缀匹配）。围栏逻辑抄第二份 = 两处判定可以各自漂移，
 *       而"该看见的看不见"与"不该看见的看得见"都不会抛异常——本仓最贵的一类失效（AGENT.md §〇 的资源路径纪律）。故实现只有一处（app 的 {@code
 *       ToolSupport}）；
 *   <li>{@link #armyExists} 要用 sd 域的 {@code ArmyId}（{@code sd} 在 unit 的**禁列**里，且不成环不可能放开）——unit
 *       **永远不认识 sd**（铁律 3）⇒ 只有认识两侧的 app 能判；
 *   <li>{@link #governmentHousehold} 的既有唯一实现是 app 的 {@code GovernmentHouseholdResolver}（{@code
 *       GovRemitTool} / {@code GovPayTool} 两条仍在 app 的财政路径与 levy 共用它）；抄一份进 unit = 把"国库 =
 *       政府家户账户"这条判据变成两处， 而它对不上时**不抛异常**、只是把别家的账当国库；
 *   <li>{@link #residenceAt} 的既有实现是 app 的 {@code GovSelectExamineesPlan.residenceAt}（{@code
 *       GovDispatchTeamPlan} / {@code GovSelectExamineesPlan} 的既有唯一口径）——同一族理由。
 * </ul>
 *
 * <p>★ 本接口只能住在 {@code simos-unit}：签名里有 {@link Unit} / {@link UnitId} / {@link SocialData} / {@link
 * HexCoord}——契约层 {@code simos-ga-spi} 不许依赖任何领域模块（I-G2）。
 */
public interface UnitToolPerspective {

  /** 某单位是否可见：{@code unit:<unitId>}（app 的 {@code ToolSupport.unitVisible} 唯一实现）。 */
  boolean unitVisible(ToolContext context, UnitId unitId);

  /**
   * 该 army id 是否已被占用（{@code sd.CreateArmy} 的既有前置判据：{@code
   * sdState(state).armies().containsKey(...)}）。
   *
   * <p>★ 拒因不在这里拼：本方法只回事实，文案由调用点按既有口径给。
   */
  boolean armyExists(SimulationState state, String armyId);

  /**
   * GOV 单位 → 国库家户（稳定 id {@code hh-gov-<unitId>}）：app 的 {@code GovernmentHouseholdResolver} 唯一实现，形状
   * / 引用对不上 ⇒ 具名 {@link IllegalArgumentException} 原样抛出（文案逐字不变）。
   */
  HouseholdId governmentHousehold(Unit unit, String unitId);

  /** 落点是否某座 Social 城 ⇒ {@link ResidenceKind#URBAN}，否则 {@link ResidenceKind#RURAL}（既有唯一口径）。 */
  ResidenceKind residenceAt(SocialData social, HexCoord at);
}
