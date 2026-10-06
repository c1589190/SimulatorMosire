package io.mosire.simos.social.spi;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.workorder.HouseholdWorkOrder;
import io.mosire.simos.social.workorder.HouseholdWorkOrderPlan;
import io.mosire.simos.util.spi.CommandTarget;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>家户 → 命令目标</b>的唯一解析点（2026-10-20 用户裁定：跨命名空间家户目标）。
 *
 * <p>★★ <b>用户裁定的两条权限规则</b>：
 *
 * <ol>
 *   <li>家户在**可见 hex** 上 ⇒ 目标是 {@code social:<q>_<r>}；
 *   <li>家户属于**可见单位**（含可见单位的全部下属单位、下辖 GOV）⇒ 目标是 {@code unit:<unitId>}；
 * </ol>
 *
 * <p>★★ <b>为什么只有一个实现</b>：同一条"家户位置 → 目标"的规则如果散在 9 个 handler 里，改一处漏一处不会报错， 只会让某条命令静默多判/少判一条资源
 * ——所以这里集中解析，各 handler 只负责"从载荷里取出家户 id / 新位置"。
 *
 * <p>★ <b>现有家户查无 ⇒ 具名 {@link IllegalArgumentException}</b>（"家户不存在: …"），由命令边界折成拒因；
 * 创建型（CREATE_HOUSEHOLD / SetHouseholdLocation 的**新位置**）按载荷给的位置判，不查家户是否存在。
 *
 * <p>★ <b>WorkOrder 的计划内可见性</b>：plan 是有序工作副本，前一步 CREATE / SET_LOCATION 的结果对后一步可见 ⇒ 本类按 plan 顺序维护
 * {@code planned} 位置表：创建型用载荷 location，后续引用先查工作副本、再查 {@link SocialData} 现值。 这样
 * "先建户到某单位、再往该户调人"的工单不会因为家户还不存在而被误判成坏载荷。
 */
final class HouseholdCommandTargets {

  /** 家户目标的命名空间字面量（与 {@code ResourcePaths} 的命名空间约定同源；本类是 social 模块的私有拼写点）。 */
  private static final String SOCIAL_NAMESPACE = "social";

  private static final String UNIT_NAMESPACE = "unit";

  private HouseholdCommandTargets() {}

  /** 家户位置 → 目标：{@code HEX(q,r) → social:q_r}、{@code UNIT(u) → unit:u}。 */
  static CommandTarget forLocation(HouseholdLocation location) {
    Objects.requireNonNull(location, "location");
    if (location instanceof HouseholdLocation.Hex hex) {
      return hexTarget(hex.hex());
    }
    if (location instanceof HouseholdLocation.Unit unit) {
      return new CommandTarget(UNIT_NAMESPACE, ResourcePaths.unit(unit.unitId()));
    }
    // 位置接口 sealed，理论上到不了这里；到了说明有第三档位置没在本解析点登记（不静默放过）。
    throw new IllegalArgumentException("未知家户位置类型: " + location.getClass().getName());
  }

  /** 单个 hex 的 social 目标（MovePopulationLots 等只给格、不给家户的命令用）。 */
  static CommandTarget hexTarget(HexCoord hex) {
    Objects.requireNonNull(hex, "hex");
    return new CommandTarget(SOCIAL_NAMESPACE, ResourcePaths.social(hex.q(), hex.r()));
  }

  /**
   * 现有家户的当前位置目标。
   *
   * @throws IllegalArgumentException 家户不存在（{@link SocialData#requireHousehold(HouseholdId)} 的具名拒因）
   */
  static CommandTarget currentTarget(SimulationState state, HouseholdId householdId) {
    Objects.requireNonNull(householdId, "householdId");
    return forLocation(SocialSnapshots.of(state).data().requireHousehold(householdId).location());
  }

  /** {@code SetHouseholdLocation} 的双边目标：**旧位置 + 新位置**（防止借"改位置"把家户移出/移入越权范围）。 */
  static List<CommandTarget> oldAndNew(
      SimulationState state, HouseholdId householdId, HouseholdLocation newLocation) {
    Objects.requireNonNull(newLocation, "newLocation");
    CommandTarget old = currentTarget(state, householdId);
    return List.of(old, forLocation(newLocation));
  }

  /** 转移类命令的双边目标：{@code from + to}，保序逐条（同一家户重复也给两条，交给调用方逐条判）。 */
  static List<CommandTarget> bothCurrent(SimulationState state, HouseholdId from, HouseholdId to) {
    return List.of(currentTarget(state, from), currentTarget(state, to));
  }

  /**
   * {@code social.SubmitHouseholdWorkOrder} 的目标：解析 plan 的每一步，返回全部目标（**保序去重**）。
   *
   * <p>逐操作口径：
   *
   * <ul>
   *   <li>{@code CREATE_HOUSEHOLD} ⇒ 载荷 {@code location}（创建型，不查 SocialData）；
   *   <li>{@code SET_LOCATION} ⇒ 该家户的**旧位置（工作副本现值）+ 新位置**（与独立命令同一条双边规则）；
   *   <li>{@code ADD/REMOVE_MEMBERS} / {@code ADJUST_POPULATION} / {@code SET_VITAL_RATES} ⇒
   *       该家户的目标；
   *   <li>{@code TRANSFER_MEMBERS} ⇒ {@code from + to} 两条。
   * </ul>
   *
   * <p>★ **创建型只认载荷 location**；现有家户（含本 plan 先前步骤新建/改位的）按工作副本现值解析——与 {@code
   * HouseholdWorkOrderBook.apply} 的顺序语义同源。
   */
  static List<CommandTarget> workOrderTargets(SimulationState state, HouseholdWorkOrder order) {
    Objects.requireNonNull(order, "order");
    SocialData base = SocialSnapshots.of(state).data();
    Map<HouseholdId, HouseholdLocation> planned = new LinkedHashMap<>();
    Set<CommandTarget> targets = new LinkedHashSet<>();
    for (HouseholdWorkOrderPlan.Step step : order.plan().steps()) {
      switch (step) {
        case HouseholdWorkOrderPlan.CreateHousehold create -> {
          targets.add(forLocation(create.location()));
          planned.put(create.householdId(), create.location());
        }
        case HouseholdWorkOrderPlan.SetLocation setLocation -> {
          targets.add(forLocation(resolve(base, planned, setLocation.householdId())));
          targets.add(forLocation(setLocation.location()));
          planned.put(setLocation.householdId(), setLocation.location());
        }
        case HouseholdWorkOrderPlan.AddMembers add ->
            targets.add(forLocation(resolve(base, planned, add.householdId())));
        case HouseholdWorkOrderPlan.RemoveMembers remove ->
            targets.add(forLocation(resolve(base, planned, remove.householdId())));
        case HouseholdWorkOrderPlan.AdjustPopulation adjust ->
            targets.add(forLocation(resolve(base, planned, adjust.householdId())));
        case HouseholdWorkOrderPlan.SetVitalRates rates ->
            targets.add(forLocation(resolve(base, planned, rates.householdId())));
        case HouseholdWorkOrderPlan.TransferMembers transfer -> {
          targets.add(forLocation(resolve(base, planned, transfer.from())));
          targets.add(forLocation(resolve(base, planned, transfer.to())));
        }
      }
    }
    return List.copyOf(targets);
  }

  /**
   * 工单计划内的家户位置：先查本 plan 的工作副本（CREATE / SET_LOCATION 的已生效位置），再查 {@link SocialData} 现值。
   *
   * @throws IllegalArgumentException 两边都查无 ⇒ "家户不存在: …"（非创建型的目标判不出来，fail-closed）
   */
  private static HouseholdLocation resolve(
      SocialData base, Map<HouseholdId, HouseholdLocation> planned, HouseholdId householdId) {
    Objects.requireNonNull(householdId, "householdId");
    HouseholdLocation plannedLocation = planned.get(householdId);
    if (plannedLocation != null) {
      return plannedLocation;
    }
    return base.requireHousehold(householdId).location();
  }
}
