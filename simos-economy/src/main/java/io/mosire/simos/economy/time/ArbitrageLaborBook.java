package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.LaborCandidateBook.marketOf;
import static io.mosire.simos.economy.time.LaborQueueSettlement.ARBITRAGE_ROLE;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.time.LaborCandidateBook.LaborSlot;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ArbitrageLaborBook {
  private ArbitrageLaborBook() {}

  // ── 套利活动（§4.3；阶段 1 新增）────────────────────────────────────────────────────────

  /**
   * ★★ <b>阶段 0：逐户资源快照（§4.3.5 的"同一份"）</b>。
   *
   * <p>★ <b>为什么必须建在排序之前、且只建一次</b>：排序用的所有投入必须是"本 tick 开始的那一份"；若某活动执行后 回头重算，顺序就依赖执行副效果 ⇒ 同一存档两跑不同（不变量
   * I7/E6）。
   *
   * <p>★ <b>库存/货币怎么取</b>：{@code 可动 = max(0, 余额 − 冻结)}。{@code accounts == null}（单模块夹具/读口）⇒ 一律按 0 ——
   * 于是套利活动<b>评估得出来但吃不到任何货</b>（buy 的 {@code cashAffordable = 0} ⇒ 具名 {@code NO_FUNDS}），不会凭空行动。
   *
   * <p>★ <b>价表怎么取</b>：优先本户居住格（与市场轮同一口径 {@code marketOf}）；本户无市场 ⇒ {@code null} （活动具名 {@code
   * NO_MARKET}，不猜价）。
   */
  static Map<HouseholdId, HouseholdResourceSnapshot> buildArbitrageSnapshots(
      long day,
      List<HouseholdId> orderedHouseholds,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, List<LaborSlot>> candidatesByHousehold,
      MarketIndex markets,
      AccountSession accounts) {
    Map<HouseholdId, HouseholdResourceSnapshot> snapshots = new LinkedHashMap<>();
    for (HouseholdId household : orderedHouseholds) {
      HouseholdEconomy householdEconomy = householdEconomies.get(household);
      if (householdEconomy == null) {
        continue;
      }
      List<ProductionProcess> candidateUnits = new ArrayList<>();
      for (LaborSlot slot : candidatesByHousehold.getOrDefault(household, List.of())) {
        if (!candidateUnits.contains(slot.unit())) {
          candidateUnits.add(slot.unit());
        }
      }
      MarketTable market = marketOf(householdEconomy, candidateUnits, markets);
      Map<CommodityId, Long> goods =
          accounts == null
              ? Map.of()
              : netOfGoods(
                  accounts.householdGoods().get(household),
                  accounts.householdFrozenGoods().get(household));
      Map<CurrencyId, Long> money =
          accounts == null
              ? Map.of()
              : netOfMoney(
                  accounts.householdMoney().get(household),
                  accounts.householdFrozenMoney().get(household));
      snapshots.put(
          household,
          new HouseholdResourceSnapshot(
              day, household, market, goods, money, Math.max(0L, householdEconomy.laborMilli())));
    }
    return snapshots;
  }

  /** {@code 余额 − 冻结}（逐键；缺冻结 = 0；结果夹到 ≥ 0；保序）。 */
  static Map<CommodityId, Long> netOfGoods(
      Map<CommodityId, Long> stock, Map<CommodityId, Long> frozen) {
    if (stock == null || stock.isEmpty()) {
      return Map.of();
    }
    LinkedHashMap<CommodityId, Long> net = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : stock.entrySet()) {
      long held = entry.getValue() == null ? 0L : entry.getValue();
      long blocked = frozen == null ? 0L : frozen.getOrDefault(entry.getKey(), 0L);
      long available = Math.max(0L, held - blocked);
      if (available > 0L) {
        net.put(entry.getKey(), available);
      }
    }
    return net;
  }

  /** {@code 余额 − 冻结}（逐币种；缺冻结 = 0；结果夹到 ≥ 0；保序）。 */
  static Map<CurrencyId, Long> netOfMoney(
      Map<CurrencyId, Long> stock, Map<CurrencyId, Long> frozen) {
    if (stock == null || stock.isEmpty()) {
      return Map.of();
    }
    LinkedHashMap<CurrencyId, Long> net = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> entry : stock.entrySet()) {
      long held = entry.getValue() == null ? 0L : entry.getValue();
      long blocked = frozen == null ? 0L : frozen.getOrDefault(entry.getKey(), 0L);
      long available = Math.max(0L, held - blocked);
      if (available > 0L) {
        net.put(entry.getKey(), available);
      }
    }
    return net;
  }

  /**
   * ★★ <b>把套利活动变成一条 {@link LaborQueueBook.Offer}</b>（§4.3.4：与生产 offer 同住一个列表、同一个排序器）。
   *
   * <pre>
   * 有机会：maxAbsorbable = 活动需求劳动；netPerLaborScaled = 活动收益率（同一把尺）；
   *         outputPriced = true（= "有可算的价差"）⇒ 进队列、可被 grant
   * 无机会：maxAbsorbable = 0、键 = 0、reason = ARBITRAGE_&lt;具名理由&gt;；outputPriced 仍为 true
   *         ⇒ **照样进队列**，作用只有一个：让写回阶段删掉昨天的套利配额行（否则它会永远占住劳动预算）
   * </pre>
   *
   * <p>★ {@code outputPriced} 在无机会时也置 true 是<b>刻意的</b>：该字段在既有机器的唯一用途是 {@code
   * LaborQueueBook.isPreservedByQueue}（"估不出价的活动原样保留"）。套利**不是**"估不出价"，而是"这一轮 没有价差" ——
   * 语义上属于"该被排空配额"的一侧，不能借"保留"名义把旧配额留成永久占位。
   */
  static LaborQueueBook.Offer arbitrageOffer(
      ProductionUnitId arbitrageUnit, TradeArbitrageActivity.Evaluation evaluation) {
    if (!evaluation.hasOpportunity()) {
      return new LaborQueueBook.Offer(
          arbitrageUnit,
          ARBITRAGE_ROLE,
          Optional.empty(),
          arbitrageUnit.value(),
          0L,
          true,
          0L,
          0L,
          0L,
          0L,
          0L,
          "ARBITRAGE_" + evaluation.reason());
    }
    TradeArbitrageActivity.Opportunity opportunity =
        evaluation.activity().orElseThrow().opportunity();
    return new LaborQueueBook.Offer(
        arbitrageUnit,
        ARBITRAGE_ROLE,
        Optional.empty(),
        arbitrageUnit.value(), // ★ 并列键 = 活动键（"收益率 desc, activityId asc"里的 activityId）
        opportunity.laborMilli(),
        true,
        opportunity.netMilli(), // expectedOutputValueMilli：这笔套利创造的净价值（毫 numeraire）
        0L, // expectedInputCostMilli：套利的"投入"就是现金与货，它们的估值已含在净额里（不重复计）
        opportunity.netMilli(),
        opportunity.laborMilli(),
        opportunity.netPerLaborScaled(),
        "");
  }

  /**
   * ★ <b>每日清理"没有生产候选的家户"残留的套利配额行</b>。
   *
   * <p>★ <b>为什么需要它</b>：写回（{@code applyPlan}）只处理**进了队列**的家户；而"没有生产候选"的户整户被跳过 ⇒
   * 它昨天留下的套利配额行会<b>永远</b>占着 {@code Σ PRODUCTION ≤ 预算} 的可用量（静默占位）。本方法把那一条删掉，
   * 其余既有配额一个都不动（与改前的"不动它的任何既有配额"逐字一致）。
   */
  static void removeStaleArbitrageCommitments(
      LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      List<HouseholdLaborCommitment> householdLaborCommitments,
      long day) {
    for (HouseholdLaborCommitment laborCommitment : householdLaborCommitments) {
      if (laborCommitment.kind() != LaborCommitmentKind.PRODUCTION
          || !TradeArbitrageActivity.isArbitrageActivity(laborCommitment.activity())) {
        continue;
      }
      laborCommitments.remove(laborCommitment.id());
      if (EconomyLog.population().isDebugEnabled()) {
        EventLog.channel(EconomyLog.population())
            .debug(
                LogEvent.of(
                    "LABOR_QUEUE_ARBITRAGE_STALE_REMOVED",
                    EconomyLogSource.ECONOMY_ARBITRAGE,
                    "day",
                    day,
                    "household",
                    laborCommitment.household().value(),
                    "activity",
                    laborCommitment.activity(),
                    "laborMilli",
                    laborCommitment.laborMilli(),
                    "reason",
                    "no-production-candidate"));
      }
    }
  }

  /** 某户有市场的具名读数（日志用；{@code false} = 无市场 ⇒ 套利具名 NO_MARKET）。 */
  static boolean arbHasMarket(HouseholdResourceSnapshot snapshot) {
    return snapshot != null && snapshot.market() != null;
  }

  /** 逐户套利决定的 DEBUG 一条（谁、哪个方向、多少量、为什么排这个序 —— AGENTS §一.9 的"谁/方向/量/为什么"）。 */
  static void logArbitrageDecision(
      long day,
      HouseholdId household,
      ProductionUnitId arbitrageUnit,
      TradeArbitrageActivity.Evaluation evaluation) {
    TradeArbitrageActivity.Opportunity opportunity =
        evaluation.activity().orElseThrow().opportunity();
    EventLog.channel(EconomyLog.settlement())
        .debug(
            LogEvent.of(
                "ARBITRAGE_OPPORTUNITY",
                EconomyLogSource.ECONOMY_ARBITRAGE,
                "day",
                day,
                "household",
                household.value(),
                "activity",
                arbitrageUnit.value(),
                "direction",
                opportunity.direction(),
                "commodity",
                opportunity.commodity().value(),
                "quantityMilli",
                opportunity.quantityMilli(),
                // ★ 2026-10-08 诊断缺陷修复：字段名 = 真实量纲（三个价格都是**微**；毫与微差 1000 倍，名字不许说谎）
                "reservationMicro",
                opportunity.reservationMicro(),
                "marketMicro",
                opportunity.marketMicro(),
                "edgeMicro",
                opportunity.edgeMicro(),
                "netMilli",
                opportunity.netMilli(),
                "laborMilli",
                opportunity.laborMilli(),
                "yieldScaled",
                opportunity.netPerLaborScaled(),
                "shortfallPerMille",
                opportunity.shortfallPerMille(),
                "saturationPerMille",
                opportunity.saturationPerMille()));
  }
}
