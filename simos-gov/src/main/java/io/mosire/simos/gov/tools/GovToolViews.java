package io.mosire.simos.gov.tools;

import io.mosire.simos.gov.GovServiceFlow;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.state.SimulationState;
import java.util.Map;
import java.util.Optional;

/**
 * 政府工具面的**视图口**：跨切片投影与"需要组合根装配件（历法时钟 / 进程内流量表）才读得出"的那几项读数。
 *
 * <p>★★ <b>为什么这些不能直接读</b>：它们的唯一实现住在 app，且**签名或实现里带 gov 看不见的东西**：
 *
 * <ul>
 *   <li>{@code ApiViews.economyGovernment} —— GUI 与 MCP 共用的形状函数（视图只有一份，AGENT.md §8.3）；
 *   <li>{@code GovernmentServiceLaborBridge.supply/committedLaborByHousehold}、{@code
 *       HouseholdUnitConsistency.staffHouseholdProjection} —— 吃 {@code EconomyData}（**economy
 *       实现**，不在 gov 允许面）；
 *   <li>{@code GovServiceFlowFeed.last} —— 进程内按 tick 的流量表（app 时间面）；★ 它返回的 {@link GovServiceFlow} 是
 *       **gov 自己的类型**，故这里只转发；
 *   <li>{@code social.householdLaborMilli(hh, tick, clock)} —— 需要 {@code CalendarClock}（{@code
 *       simos-calendar} 不在 gov 允许面）⇒ 时钟由 app 适配器持有（与 unit 批的 {@code UnitToolAllocations} 同款）。
 * </ul>
 *
 * <p>★ <b>端口实现逐条转调既有唯一实现</b>，不重写任何形状/判据；{@link TreasuryAvailable} 的三项来自 actor 切片 （{@code
 * AvailableStock}），gov 看不见 actor ⇒ 由 app 读好之后交值。
 */
public interface GovToolViews {

  /**
   * 国库/政府投影：直接嵌 {@code ApiViews.economyGovernment(state)} 的结果（不另造第二套口径）。
   *
   * <p>返回形状与 {@code ApiViews.economyGovernment} **逐字段相同**（工具只从里面取 {@code governments} 列表）。
   */
  Map<String, Object> economyGovernment(SimulationState state);

  /**
   * {@code HouseholdUnitConsistency.staffHouseholdProjection} 的当刻投影：键形如 {@code <govUnitId>:<role>}。
   *
   * <p>★ 抛出语义与那条桥一致（算不出 ⇒ 异常），工具侧按既有口径折成具名 unavailable。
   */
  Map<String, Long> staffHouseholdProjection(SimulationState state, long tick);

  /** {@code GovernmentServiceLaborBridge.supply} 的两维有效供给 + 承诺 + 欠供户数（gov 侧只读这五项）。 */
  Supply supply(SimulationState state, UnitId govUnitId, GovernmentFormation formation, long tick);

  /** 逐户承诺劳动（{@code GovernmentServiceLaborBridge.committedLaborByHousehold}；保序）。 */
  Map<HouseholdId, Long> committedLaborByHousehold(
      SimulationState state, UnitId govUnitId, long tick);

  /** 本 tick 的政府服务流量（{@code GovServiceFlowFeed.last}）；读不到 ⇒ {@link Optional#empty()}（绝不填 0）。 */
  Optional<Map<UnitId, GovServiceFlow>> serviceFlow(String mapId, long tick);

  /** 流量读不到时的具名原因（{@code GovServiceFlowFeed.UNAVAILABLE_REASON} 的唯一拼写点）。 */
  String serviceFlowUnavailableReason();

  /**
   * 国库家户的三个可支配量（{@code ApiViews.actorData} → {@code AvailableStock}）。
   *
   * <p>★ {@code null} 表示**该家户没有 actor 账**（既有口径：不填 0）；字段名对应三种资源。
   */
  TreasuryAvailable treasuryAvailable(SimulationState state, String householdId);

  /**
   * 单币种（银）可支配量，**账不存在 ⇒ 0**（{@code AvailableStock.available(actors, household, SILVER)} 的既有口径， 与
   * {@link #treasuryAvailable} 的"账不存在 ⇒ null"是两条不同的调用点语义，都保留）。
   */
  long availableSilver(SimulationState state, String householdId);

  /** {@code GovernmentServiceLaborBridge.Supply} 里 gov 读口用到的那五项（gov 允许面里放不进 economy 实现类型）。 */
  record Supply(
      long securityLaborMilli,
      long paperworkLaborMilli,
      long committedSecurityLaborMilli,
      long committedPaperworkLaborMilli,
      long underfedHouseholds) {}

  /** 国库家户的三个可支配量；{@code null} = 账不存在（不填 0）。 */
  record TreasuryAvailable(Long grain, Long cloth, Long money) {}
}
