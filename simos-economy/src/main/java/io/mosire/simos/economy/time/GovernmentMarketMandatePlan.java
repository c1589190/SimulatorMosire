package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.market.GovernmentMarketMandate;
import io.mosire.simos.economy.api.market.MarketMandateId;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * ★★ <b>R1：本日生效的「政府市场授权」计划</b>（逐轮瞬态；不进 {@code EconomyData}、不进变更集、不落盘）。
 *
 * <p>★★ <b>它同时回答两件事</b>：
 *
 * <ol>
 *   <li><b>哪些家户是"只按授权下单"的</b>（{@link #isAuthorizationOnly})：<b>所有</b>政府的国库家户 （{@code
 *       hh-gov-<govUnitId>}，由 {@code Government.treasury()} 派生）。它们在商品市场上<b>不生成任何自动订单</b> ——
 *       不因自然需求买、不因库存差异卖、不放贷、不参与家户外汇单；
 *   <li><b>它们今天有哪些明确的挂单授权</b>（{@link #liveFor}）：{@code EconomyData.govMarketMandates()} 里当天生效且
 *       未耗尽的行。
 * </ol>
 *
 * <p>★★ <b>为什么"只按授权下单"必须是一张独立的名单、而不是"没有需求就不下单"</b>：国库户持有税收实收的粮/银， 而自动卖单的判据是 {@code max(0, 持有 − 冻结 −
 * 必要投入 − 生活保留 − 需求目标)} —— 0 人口的国库户这三项全是 0 ⇒ <b>它会被当成卖家清仓</b> （正是 Z7b 要修的那条：run6 day50 中央粮 271,393 →
 * 0）。因此"回市场"与"不自动下单"必须同时成立： 前者靠把国库户从排除集里摘掉，后者靠本名单。
 *
 * <p>★ <b>确定性</b>：国库户按 {@code governments} 的表序、授权按 id 规范串升序 —— 同一 revision 同一 tick 两跑逐值相同（I-P7）。
 */
final class GovernmentMarketMandatePlan {

  private static final Logger MARKET = EconomyLog.market();

  /** 空计划（旧世界 / 读口不注入）：没有任何授权专属家户，逐值退回改前行为。 */
  private static final GovernmentMarketMandatePlan EMPTY =
      new GovernmentMarketMandatePlan(Map.of());

  /** 国库家户 → 本日生效的授权（**按 id 升序**；没有授权 ⇒ 空 list，键仍在 ⇒ 该户仍是"只按授权下单"）。 */
  private final Map<HouseholdId, List<GovernmentMarketMandate>> byTreasury;

  private GovernmentMarketMandatePlan(Map<HouseholdId, List<GovernmentMarketMandate>> byTreasury) {
    this.byTreasury = byTreasury;
  }

  static GovernmentMarketMandatePlan empty() {
    return EMPTY;
  }

  /**
   * ★ 按当日世界状态现算计划（唯一拼写点；生产结算与读口都走它，保证"看到的订单 == 会下的订单"）。
   *
   * @param mandates {@code EconomyData.govMarketMandates()}（可为空表 = 没有任何授权）
   * @param governments {@code EconomyData.governments()}（国库家户的唯一权威）
   * @param day 当日世界日（{@code authorizedDay ≤ day ≤ expiresOnDay} 才生效）
   */
  static GovernmentMarketMandatePlan of(
      Map<MarketMandateId, GovernmentMarketMandate> mandates,
      Map<GovernmentId, Government> governments,
      long day) {
    Objects.requireNonNull(mandates, "mandates");
    Objects.requireNonNull(governments, "governments");
    if (mandates.isEmpty() && governments.isEmpty()) {
      return EMPTY;
    }
    Map<HouseholdId, List<GovernmentMarketMandate>> byTreasury = new LinkedHashMap<>();
    // ① 先给**每一个**政府的国库户留一行（哪怕没有授权）：名单的存在性不依赖"有没有授权"。
    for (Government government : governments.values()) {
      if (government.treasury().kind() != io.mosire.simos.actor.api.actor.ActorKind.HOUSEHOLD) {
        continue; // 非家户国库本来就不在市场参与者行里（具名守卫在 EconomyData 构造期）
      }
      byTreasury.computeIfAbsent(
          HouseholdActors.householdOf(government.treasury()), ignored -> new ArrayList<>());
    }
    // ② 把当天生效且未耗尽的授权挂到它的国库户下（同一政府同一国库 ⇒ 1:1；键不等于值内政府时由状态层守卫拦下）。
    long dropped = 0L;
    for (GovernmentMarketMandate mandate : mandates.values()) {
      Government government = governments.get(mandate.government());
      if (government == null
          || government.treasury().kind() != io.mosire.simos.actor.api.actor.ActorKind.HOUSEHOLD) {
        dropped++; // 状态层构造期已 fail-closed；这里只防御"绕过构造的手工状态"，绝不静默当成零授权
        continue;
      }
      if (!mandate.effectiveOn(day) || mandate.exhausted()) {
        continue; // 未生效 / 已耗尽：今天不挂单（到期与耗尽的**清除**由日结算写状态）
      }
      byTreasury
          .computeIfAbsent(
              HouseholdActors.householdOf(government.treasury()), ignored -> new ArrayList<>())
          .add(mandate);
    }
    Map<HouseholdId, List<GovernmentMarketMandate>> frozen = new LinkedHashMap<>();
    long live = 0L;
    for (Map.Entry<HouseholdId, List<GovernmentMarketMandate>> entry : byTreasury.entrySet()) {
      List<GovernmentMarketMandate> rows = new ArrayList<>(entry.getValue());
      rows.sort(Comparator.comparing(mandate -> mandate.id().value()));
      // ★★ fail-closed：同一国库户 + 同一商品 + 同一方向**至多一条**生效授权 —— 否则成交量无法归属到
      //   哪一条（filledMilli 会被记到错的那条）。命令边界已经拒掉这种注入；这里是对"绕过命令面的手工状态"
      //   的最后一道守卫（契约故障 = ERROR 且不降级，§一.9）。
      GovernmentMarketMandate previous = null;
      for (GovernmentMarketMandate mandate : rows) {
        if (previous != null
            && previous.commodity().equals(mandate.commodity())
            && previous.side() == mandate.side()) {
          EventLog.channel(MARKET)
              .error(
                  LogEvent.of(
                      "GOV_MARKET_MANDATE_CONTRACT",
                      EconomyLogSource.ECONOMY_MARKET,
                      "day",
                      day,
                      "household",
                      entry.getKey().value(),
                      "commodity",
                      mandate.commodity().value(),
                      "side",
                      mandate.side().name(),
                      "reason",
                      "two-live-authorizations-for-same-commodity-and-side",
                      "first",
                      previous.id().value(),
                      "second",
                      mandate.id().value()));
          throw new IllegalStateException(
              "同一国库户同一商品同一方向存在多条生效授权（成交量无法归属）: household="
                  + entry.getKey().value()
                  + " commodity="
                  + mandate.commodity().value()
                  + " side="
                  + mandate.side()
                  + " first="
                  + previous.id().value()
                  + " second="
                  + mandate.id().value());
        }
        previous = mandate;
      }
      live += rows.size();
      frozen.put(entry.getKey(), Collections.unmodifiableList(rows));
    }
    if (MARKET.isDebugEnabled()) {
      // ★§一.9 DEBUG 写"为什么"：今天有几户国库户在市场上、其中几户真挂了单（0 = 只按授权、今天没有授权）。
      EventLog.channel(MARKET)
          .debug(
              LogEvent.of(
                  "GOV_MARKET_MANDATE_PLAN",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  day,
                  "authorizationOnlyHouseholds",
                  frozen.size(),
                  "liveMandates",
                  live,
                  "danglingMandates",
                  dropped));
    }
    return new GovernmentMarketMandatePlan(Collections.unmodifiableMap(frozen));
  }

  /** ★ 本户是不是"只按授权下单"的国库户（是 ⇒ 自动买卖/放贷/家户外汇单全部不生成）。 */
  boolean isAuthorizationOnly(HouseholdId household) {
    return household != null && byTreasury.containsKey(household);
  }

  /** ★ 本户今天在**这个商品**上的全部生效授权（按 id 升序；没有 ⇒ 空表）。 */
  List<GovernmentMarketMandate> liveFor(HouseholdId household, CommodityId commodity) {
    List<GovernmentMarketMandate> rows = byTreasury.get(household);
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    List<GovernmentMarketMandate> out = new ArrayList<>(2);
    for (GovernmentMarketMandate mandate : rows) {
      if (mandate.commodity().equals(commodity)) {
        out.add(mandate);
      }
    }
    return out;
  }

  /** 只按授权下单的家户数（0 = 旧世界形态）。 */
  int authorizationOnlyHouseholds() {
    return byTreasury.size();
  }

  /** 本日生效的授权总条数。 */
  int liveMandates() {
    int total = 0;
    for (List<GovernmentMarketMandate> rows : byTreasury.values()) {
      total += rows.size();
    }
    return total;
  }

  boolean isEmpty() {
    return byTreasury.isEmpty();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof GovernmentMarketMandatePlan plan && byTreasury.equals(plan.byTreasury);
  }

  @Override
  public int hashCode() {
    return byTreasury.hashCode();
  }

  @Override
  public String toString() {
    return "GovernmentMarketMandatePlan{households="
        + byTreasury.size()
        + ", live="
        + liveMandates()
        + "}";
  }
}
