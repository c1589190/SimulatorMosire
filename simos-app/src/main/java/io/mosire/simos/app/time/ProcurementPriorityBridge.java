package io.mosire.simos.app.time;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.app.household.GovernmentServiceLaborBridge;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.time.ProcurementPriorityInput;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>P-T1d：逐政府的"政府采购优先级"折算（唯一折算点，组合根）</b>（2026-10-10 口岸设计书 §16.3 / §17）。
 *
 * <pre>
 * ① 触发（法律层）  gov 政策 {@code govState.portPolicyOrDefault(unitId).controlsMarket()}（= 用户"政府要求管控市场"）
 * ② 编制劳动力      supply(unitId) 的**行政维**供给劳动 = securityLaborMilli + paperworkLaborMilli
 *                  （★ 口岸维**不进**：§16.4 M-2「按行政效率执行用行政维效率，口岸维只管边界」）
 * ③ 行政力池（单位 = 家户，设计书 §17.4 N-1）
 *                  一份行政力 = 一个**标准岗位**一 tick 的劳动定额（C8 唯一权威
 *                  {@code SocialProvisioning.standardLaborMilliHoursPerTick()}）= 一个家户 ⇒
 *                  池 = ⌊(治安 + 公文) 供给劳动 ÷ 标准岗位定额⌋
 * ④ 注入经济侧      国库户（{@code Government.treasury()}，权威不按 {@code hh-gov-} 前缀猜）→ 池
 *                  ⇒ {@link ProcurementPriorityInput}（逐轮瞬态、不落盘）
 * </pre>
 *
 * <p>★★ <b>为什么折算只能在组合根</b>：{@code simos-gov} 看不见经济状态（政府记录/国库户）、{@code simos-economy} 不认识
 * gov（政策与编制）—— 只有 app 同时看得见三边（与 {@link PortRegimeBridge} 同一条理由）。
 *
 * <p>★★ <b>缺省语义中性（I-C2）</b>：没有任何 GOV 要求管控市场（缺 {@code portPolicies} 键 / 键上 {@code
 * marketControl=false}）⇒ 空表 ⇒ 经济侧不注入、不置顶、不消耗 ⇒ 一个数都不动。
 *
 * <p>★★ <b>fail-closed（§17.2 失败语义）</b>：算不出池（没有该政府的编制供给 / 定额非正 / 国库不是家户）⇒ 该政府
 * <b>不进表</b>（连"要求管控"都不注入）并具名记一条 —— "要管控却一户也超不了"在语义上等价于没管控，且不会静默： 决策人从日志里看得到是哪一条断的。
 *
 * <p>★ <b>时序</b>：本折算读的是<b>当日结算之后</b>算出的编制劳动力（{@code GovEfficiencyDay.supplyByUnit}），
 * 注入值作用于<b>下一次</b>市场轮（一 tick 滞后；与口岸管制力/三层税同一条既有形制）。
 */
public final class ProcurementPriorityBridge {

  /** 组合根日志通道（与日循环同一 logger/来源，§一.9）。 */
  private static final LogChannel LOG = EventLog.channel(AppLog.time());

  private ProcurementPriorityBridge() {}

  /**
   * 一次折算的产物。
   *
   * @param input 注入经济侧的国库户 → 行政力池（空 ⇒ 没有政府要求管控，逐值退回改前）
   * @param controllingGovernments 要求管控市场的政府数（读数）
   * @param poolUnits 池子合计（"这一轮最多能超越多少户"；读数）
   * @param zeroPoolGovernments 要求管控但池 = 0（编制不足/算不出定额）的政府数（读数；它们进表但不置顶）
   * @param unitsByGovernment 逐政府读数（保序：政府 id 升序；只进日志/读口）
   */
  public record ProcurementPriorityDay(
      ProcurementPriorityInput input,
      int controllingGovernments,
      long poolUnits,
      int zeroPoolGovernments,
      Map<String, Long> unitsByGovernment) {

    public ProcurementPriorityDay {
      Objects.requireNonNull(input, "input");
      unitsByGovernment = Collections.unmodifiableMap(new LinkedHashMap<>(unitsByGovernment));
    }

    /** 本轮有没有政府采购优先级可注入（表非空 = 至少一个政府要求管控；<b>池可以是 0</b>：要求了但一户也超不了）。 */
    public boolean active() {
      return input.isActive();
    }
  }

  /**
   * ★★ <b>算本轮的政府采购优先级</b>（纯函数；不写状态、不落盘、不用随机数）。
   *
   * @param govState 政府切片（政策 = 逐 GOV 的口岸/市场管制政策，含 {@code marketControl}）；不得为 null
   * @param supplyByUnit 当日逐 GOV 的编制供给（{@code GovEfficiencyDay.supplyByUnit}；缺项 = 该政府没有编制供给 ⇒ 池
   *     0）；不得为 null
   * @param economy 经济切片（政府记录 = 国库户的唯一权威）；不得为 null
   * @param standardLaborMilliHoursPerTick 标准岗位定额（毫小时/tick；C8 唯一权威，由调用方从 Social 读出传入）；非正 ⇒ 全员池
   *     0（fail-closed）
   * @param day 世界日（只进日志）
   */
  public static ProcurementPriorityDay compute(
      GovState govState,
      Map<UnitId, GovernmentServiceLaborBridge.Supply> supplyByUnit,
      EconomyData economy,
      long standardLaborMilliHoursPerTick,
      long day) {
    Objects.requireNonNull(govState, "govState");
    Objects.requireNonNull(supplyByUnit, "supplyByUnit");
    Objects.requireNonNull(economy, "economy");
    if (standardLaborMilliHoursPerTick <= 0L) {
      // ★ fail-closed：说不出"一份行政力值多少劳动"就不能超任何一户（绝不拿 0 当分母、也不猜）。
      LOG.info(
          LogEvent.of(
              "PROCUREMENT_PRIORITY_NO_STANDARD_QUOTA",
              AppLogSource.DAILY_LOOP,
              "day",
              day,
              "standardLaborMilliHoursPerTick",
              standardLaborMilliHoursPerTick,
              "governments",
              govState.portPolicies().size(),
              "reason",
              "non-positive-standard-post-quota-no-overtake-fail-closed"));
      return new ProcurementPriorityDay(ProcurementPriorityInput.none(), 0, 0L, 0, Map.of());
    }
    // ★ 政府序 = GOV 单位 id 升序（内容的纯函数；I7），且只遍历**显式设过政策**的那些键。
    List<UnitId> orderedUnits = new ArrayList<>(govState.portPolicies().keySet());
    orderedUnits.sort(Comparator.comparing(UnitId::value));
    Map<HouseholdId, Long> poolByTreasury = new LinkedHashMap<>();
    Map<String, Long> unitsByGovernment = new LinkedHashMap<>();
    int zeroPool = 0;
    for (UnitId unitId : orderedUnits) {
      if (!govState.portPolicyOrDefault(unitId).controlsMarket()) {
        continue; // 这个政府没要求管控市场 ⇒ 不进表（缺省语义中性）
      }
      GovernmentId governmentId;
      try {
        governmentId = GovernmentIds.ofUnit(unitId.value());
      } catch (IllegalArgumentException bad) {
        logSkipped(day, unitId.value(), "-", "government-unit-id-cannot-be-derived");
        continue;
      }
      Government government = economy.governments().get(governmentId);
      if (government == null) {
        logSkipped(day, unitId.value(), governmentId.value(), "no-government-record");
        continue;
      }
      if (government.treasury().kind() != ActorKind.HOUSEHOLD) {
        logSkipped(day, unitId.value(), governmentId.value(), "treasury-not-household");
        continue;
      }
      HouseholdId treasury = HouseholdActors.householdOf(government.treasury());
      GovernmentServiceLaborBridge.Supply supply = supplyByUnit.get(unitId);
      long administrativeLaborMilli =
          supply == null
              ? 0L
              : Math.addExact(supply.securityLaborMilli(), supply.paperworkLaborMilli());
      long units = Math.floorDiv(administrativeLaborMilli, standardLaborMilliHoursPerTick);
      if (units <= 0L) {
        zeroPool++;
      }
      poolByTreasury.put(treasury, units);
      unitsByGovernment.put(governmentId.value(), units);
      if (LOG.isDebugEnabled()) {
        // §一.9 DEBUG 写"为什么"：这一轮这个政府为什么能超这几户（编制 × 定额 ⇒ 池）。
        LOG.debug(
            LogEvent.of(
                "PROCUREMENT_PRIORITY_POOL_COMPUTED",
                AppLogSource.DAILY_LOOP,
                "day",
                day,
                "govUnit",
                unitId.value(),
                "government",
                governmentId.value(),
                "treasury",
                treasury.value(),
                "securityLaborMilli",
                supply == null ? 0L : supply.securityLaborMilli(),
                "paperworkLaborMilli",
                supply == null ? 0L : supply.paperworkLaborMilli(),
                "administrativeLaborMilli",
                administrativeLaborMilli,
                "standardLaborMilliHoursPerTick",
                standardLaborMilliHoursPerTick,
                "poolUnits",
                units,
                "reason",
                "one-unit-equals-one-standard-post-quota-equals-one-household"));
      }
    }
    long poolUnits = 0L;
    for (Long units : poolByTreasury.values()) {
      poolUnits = Math.addExact(poolUnits, units);
    }
    if (poolByTreasury.isEmpty()) {
      return new ProcurementPriorityDay(ProcurementPriorityInput.none(), 0, 0L, 0, Map.of());
    }
    return new ProcurementPriorityDay(
        new ProcurementPriorityInput(poolByTreasury),
        poolByTreasury.size(),
        poolUnits,
        zeroPool,
        unitsByGovernment);
  }

  /** 一个"要求管控却折算不出池"的政府：具名 INFO（不静默丢），键取它当时能给出的身份。 */
  private static void logSkipped(long day, String govUnit, String government, String reason) {
    LOG.info(
        LogEvent.of(
            "PROCUREMENT_PRIORITY_GOVERNMENT_SKIPPED",
            AppLogSource.DAILY_LOOP,
            "day",
            day,
            "govUnit",
            govUnit,
            "government",
            government,
            "reason",
            reason));
  }
}
