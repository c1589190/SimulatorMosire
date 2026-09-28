package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.model.Debt;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.OperatorCondition.IndustryStatus;
import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>S3.2 经营者状态机</b>—— 全部转移由可观察量触发（滞销/被挤出/投入不足/现金与库存缓冲/债务本息/自用可覆盖量）， 阈值取 {@link StressPolicy}，不在
 * {@code switch} 里写死。
 *
 * <pre>
 * ACTIVE/TRIALING --连续滞销+OUTCOMPETED--> OVERSUPPLIED --再一个周期--> CONTRACTING
 * CONTRACTING --连续债务压力--> INDEBTED --连续无法偿付--> SUSPENDED --连续停业--> EXITED
 * ACTIVE/TRIALING --连续投入不足--> CONTRACTING（生产侧原因；不经过滞销）
 * INDEBTED/— --恢复证据（有成交、不欠本息、有现金）--> ACTIVE（有限重开）
 * </pre>
 *
 * <p>★★ <b>本类只判"状态怎么变 + 谁该退出"</b>；退出时的库存/货币偿债与 {@code Debt.defaulted} 处置由 {@code
 * EconomySettlement.settleOperatorExits} 落账（那里才有唯一写口 {@code applyTransfer}）。缩产只乘进"计划规模系数"，
 * <b>不销毁</b> {@code Industry.capacity} / {@code UseRight}。
 *
 * <p>★★ <b>S3 修复：关账证据改成"本周期累计"</b>—— 一个周期里会有多轮市场（例行轮 + 低库存轮 + 关账轮），
 * 旧实现只取关账日当天那一轮报告作证据，于是"更早的轮里已经滞销/被挤出"在关账日完全看不见。现在每个市场轮结束后由 {@link #accumulateMarketEvidence}
 * 把该轮逐卖方的 {@code SellerOutcome}/{@code Fill} 累加进 {@link OperatorCondition} 的 {@code cycle*} 字段；关账日
 * {@link #advance} 消费这批累计证据，然后清零，下一周期重新累计。证据住在 {@code EconomyData.operatorConditions} 里、随 {@code
 * FieldDelta} 跨 revision 持久，不是进程内会话。
 *
 * <p>★★ <b>无市场轮 ≠ 零成交</b>：若本周期一个市场轮都没有（{@code cycleMarketRounds == 0}），{@code advance} 保留已有连续计数、 不用
 * 0 覆盖（"没开市"不是"卖不出去"的证据）。
 *
 * <p>★★ <b>退出时的使用权去向（如实记）</b>：本批的 {@code UseRight} 只有 {@code holder} 一栏、没有独立的"土地所有者" 主体（S1
 * 迁移生成的整额权利默认 holder = 经营者）⇒ "按 RightKind 退回 holder" 在当前数据形状下只能落实为 <b>权利原样留在 holder 名下、退出不删除也不改
 * holder</b>（不凭空发明一个地主 actor）。真正的退回/重新分配要等 "所有权人身份"这一维落地；在那之前不静默删权利、也不造假转移。
 */
final class OperatorSettlement {

  private OperatorSettlement() {}

  /** 一个应进入退出处置的经营者（状态机判"该退了"，不是"已经退干净了"）。 */
  record Exit(IndustryId industry, ActorRef operator, HouseholdId household, String reason) {
    Exit {
      Objects.requireNonNull(industry, "industry");
      Objects.requireNonNull(operator, "operator");
      Objects.requireNonNull(reason, "reason");
    }
  }

  /**
   * ★★ <b>一个市场轮结束后累加周期证据</b>（每轮调用一次；唯一写口）。
   *
   * <p>★ 只有这个经营者在这一轮里被市场观察到的产业才建/改条件；但一旦有条件，即使本轮没有卖单也把 {@code cycleMarketRounds}
   * +1（"这轮开过市"是可观察事实，不是"卖了多少"）。
   *
   * @param conditions 经营者状态工作表（会被就地更新）
   * @param industries 产业表（键 = 本轮参与累加的产业）
   * @param report 本轮市场报告（{@code null} = 本轮没开市；保持已有累计不动）
   */
  static void accumulateMarketEvidence(
      LinkedHashMap<IndustryId, OperatorCondition> conditions,
      Map<IndustryId, Industry> industries,
      MarketReport report) {
    if (report == null) {
      return;
    }
    for (IndustryId id : new ArrayList<>(industries.keySet())) {
      Industry industry = industries.get(id);
      if (industry == null) {
        continue;
      }
      MarketEvidence evidence = evidenceOf(report, industry);
      OperatorCondition prev = conditions.get(id);
      boolean observed =
          evidence.offered > 0L
              || evidence.filled > 0L
              || evidence.unfilled > 0L
              || evidence.revenue > 0L
              || evidence.outcompetedActors > 0L
              || evidence.outcompetedQty > 0L;
      if (prev == null && !observed) {
        continue; // 没见过的经营者且本轮没有它的卖方槽 ⇒ 不凭空造条件
      }
      if (prev == null) {
        prev = neutralCondition(id);
      }
      conditions.put(
          id,
          prev.plusCycleEvidence(
              evidence.offered,
              evidence.filled,
              evidence.unfilled,
              evidence.revenue,
              evidence.outcompetedActors,
              evidence.outcompetedQty));
    }
  }

  /**
   * 关账日推进一步状态机（每个关账周期、对**本日关账的产业**调用一次），就地更新 {@code conditions}。
   *
   * @param closingIndustries 本日关账的产业集合；不在集合里的产业原样保留（它们的周期证据不能在这里被消费/清零）
   * @param inputShortfallByIndustry 本日关账产业在周期状态被清零**之前**捕获的"投入没凑齐"事实
   * @return 本轮判定要退出处置的经营者（库存/货币先偿债，不足才 {@code defaulted}）
   */
  static List<Exit> advance(
      LinkedHashMap<IndustryId, OperatorCondition> conditions,
      LinkedHashMap<IndustryId, Industry> industries,
      Map<IndustryId, ProductionRelation> relations,
      LinkedHashMap<DebtId, Debt> debts,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      Map<ActorRef, HouseholdId> householdOfActor,
      Map<HexCoord, Market> markets,
      Set<IndustryId> closingIndustries,
      Map<IndustryId, Boolean> inputShortfallByIndustry) {
    List<Exit> exits = new ArrayList<>();
    for (IndustryId id : new ArrayList<>(industries.keySet())) {
      if (!closingIndustries.contains(id)) {
        continue; // ★ 未关账：周期证据与连续计数都保留到它自己的关账日
      }
      Industry industry = industries.get(id);
      if (industry == null) {
        continue;
      }
      HouseholdId household = householdOfActor.get(industry.operator());
      OperatorCondition prev = conditions.getOrDefault(id, neutralCondition(id));
      boolean hadMarket = prev.cycleMarketRounds() > 0L;
      boolean outcompeted = prev.cycleOutcompetedActors() >= StressPolicy.OUTCOMPETED_MIN_ACTORS;
      long unsoldCycles;
      if (!hadMarket) {
        // ★ 没有市场轮 ⇒ 没有新证据：保留已有连续计数，不用 0 覆盖（"没开市"不是"卖不出去"）。
        unsoldCycles = prev.consecutiveUnsoldCycles();
      } else if (prev.cycleOfferedQty() > 0L && prev.cycleFilledQty() <= 0L && outcompeted) {
        unsoldCycles = prev.consecutiveUnsoldCycles() + 1L;
      } else {
        unsoldCycles = 0L; // 本周期有市场且至少卖出一笔（或没有 OUTCOMPETED 证据）⇒ 连续滞销清零
      }
      boolean shortfall = inputShortfallByIndustry.getOrDefault(id, false);
      long shortfallCycles = shortfall ? prev.consecutiveInputShortfallCycles() + 1L : 0L;
      long debtPrincipal = 0L;
      long debtServiceDue = 0L;
      boolean debtStress = false;
      if (household != null) {
        for (Debt debt : debts.values()) {
          if (!debt.debtor().equals(household)) {
            continue;
          }
          long due = debt.principal() + debt.principal() * debt.ratePerMillePerCycle() / 1_000L;
          debtPrincipal += debt.principal();
          debtServiceDue += due;
          long available =
              debt.commodity().isPresent()
                  ? stockOf(
                      household,
                      industry.operator(),
                      debt.commodity().get(),
                      householdGoods,
                      operatorGoods)
                  : cashOf(household, industry.operator(), householdMoney, operatorMoney);
          if (due > available) {
            debtStress = true;
          }
        }
      }
      long debtStressCycles = debtStress ? prev.consecutiveDebtStressCycles() + 1L : 0L;
      long suspendedCycles = prev.consecutiveSuspendedCycles();
      long reopens = prev.reopens();
      IndustryStatus status = prev.status();
      String reason = prev.lastReason();
      switch (status) {
        case ACTIVE, TRIALING -> {
          if (hadMarket
              && unsoldCycles >= StressPolicy.UNSOLD_CYCLES_BEFORE_OVERSUPPLIED
              && outcompeted) {
            status = IndustryStatus.OVERSUPPLIED;
            reason =
                "oversupplied:cycleUnsoldCycles="
                    + unsoldCycles
                    + ",cycleOffered="
                    + prev.cycleOfferedQty()
                    + ",outcompeted="
                    + prev.cycleOutcompetedActors();
          } else if (shortfallCycles >= StressPolicy.INPUT_SHORTFALL_CYCLES_BEFORE_CONTRACTING) {
            status = IndustryStatus.CONTRACTING;
            reason = "contracting:inputShortfallCycles=" + shortfallCycles;
          }
        }
        case OVERSUPPLIED -> {
          if (hadMarket && unsoldCycles >= StressPolicy.UNSOLD_CYCLES_BEFORE_OVERSUPPLIED) {
            status = IndustryStatus.CONTRACTING;
            reason = "contracting:oversuppliedUnsoldCycles=" + unsoldCycles;
          } else if (hadMarket && prev.cycleFilledQty() > 0L) {
            status = IndustryStatus.ACTIVE;
            reason = "recovered:cycleFilled=" + prev.cycleFilledQty();
          }
        }
        case CONTRACTING -> {
          if (debtStressCycles >= StressPolicy.DEBT_STRESS_CYCLES_BEFORE_INDEBTED) {
            status = IndustryStatus.INDEBTED;
            reason = "indebted:debtStressCycles=" + debtStressCycles;
          } else if (hadMarket
              && prev.cycleFilledQty() > 0L
              && shortfallCycles == 0L
              && !debtStress) {
            status = IndustryStatus.ACTIVE;
            reason = "recovered:cycleFilled=" + prev.cycleFilledQty();
          }
        }
        case INDEBTED -> {
          long cash = cashOf(household, industry.operator(), householdMoney, operatorMoney);
          if (debtStressCycles >= StressPolicy.DEBT_STRESS_CYCLES_BEFORE_SUSPENDED) {
            status = IndustryStatus.SUSPENDED;
            reason = "suspended:debtStressCycles=" + debtStressCycles;
          } else if (hadMarket && !debtStress && prev.cycleFilledQty() > 0L && cash > 0L) {
            status = IndustryStatus.ACTIVE;
            reason = "recovered:cycleFilled=" + prev.cycleFilledQty();
          }
        }
        case SUSPENDED -> {
          long cash = cashOf(household, industry.operator(), householdMoney, operatorMoney);
          long selfUsable = selfUsableOf(household, industry, householdGoods, operatorGoods);
          suspendedCycles = prev.consecutiveSuspendedCycles() + 1L;
          // ★ 停业期间计划系数为 0 ⇒ 它自己没有卖单、不会有 filled>0 的"恢复证据"；恢复只能看缓冲：
          //   债务压力解除 + 有现金/可自用库存可垫下一周期投入。
          if (reopens < StressPolicy.MAX_REOPENS && !debtStress && (cash > 0L || selfUsable > 0L)) {
            status = IndustryStatus.ACTIVE;
            reopens++;
            suspendedCycles = 0L;
            reason = "reopened:cash=" + cash + ",selfUsable=" + selfUsable;
          } else if (suspendedCycles >= StressPolicy.SUSPENDED_CYCLES_BEFORE_EXIT) {
            status = IndustryStatus.EXITED;
            reason = "exited:suspendedCycles=" + suspendedCycles;
            exits.add(new Exit(id, industry.operator(), household, reason));
          }
        }
        case EXITING -> {
          status = IndustryStatus.EXITED;
          reason = "exited:fromExiting";
          exits.add(new Exit(id, industry.operator(), household, reason));
        }
        case EXITED, ABANDONED -> {
          // ★ 已退出/弃置：状态不再自转；恢复只能走显式重开（本批没有该命令）。
        }
      }
      long plannedScale =
          EconomySettlement.capacityScaleOf(industry)
              * StressPolicy.plannedScalePerMille(status)
              / 1_000L;
      long costEstimate = 0L;
      HexCoord hex = IndustryHexKeys.hexKeyOf(id).map(HexCoord::parse).orElse(null);
      if (hex != null) {
        Market market = markets.get(hex);
        ProducerCostBook.Estimate estimate =
            ProducerCostBook.estimate(industry, market, relations.get(id));
        costEstimate = estimate.unitCostEstimateMilli() * plannedScale / 1_000L;
      }
      // ★ 无市场轮 ⇒ 没有新证据：lastCycle* 与滞销读数保持上一周期原值，不用 0 覆盖。
      long lastCycleRevenue = hadMarket ? prev.cycleRevenueMilli() : prev.lastCycleRevenueMilli();
      long lastCycleCost = hadMarket ? costEstimate : prev.lastCycleCostMilli();
      long lastCycleNet = hadMarket ? lastCycleRevenue - lastCycleCost : prev.lastCycleNetMilli();
      long unsoldStock = hadMarket ? prev.cycleUnfilledQty() : prev.unsoldStockMilli();
      long selfUsable = selfUsableOf(household, industry, householdGoods, operatorGoods);
      long cash = cashOf(household, industry.operator(), householdMoney, operatorMoney);
      conditions.put(
          id,
          new OperatorCondition(
              id,
              status,
              unsoldCycles,
              shortfallCycles,
              cash,
              debtPrincipal,
              debtServiceDue,
              lastCycleRevenue,
              lastCycleCost,
              lastCycleNet,
              unsoldStock,
              selfUsable,
              debtStressCycles,
              suspendedCycles,
              reopens,
              reason,
              0L, // cycleOfferedQty：关账消费后清零，新周期重新累计
              0L, // cycleFilledQty
              0L, // cycleUnfilledQty
              0L, // cycleRevenueMilli
              0L, // cycleOutcompetedActors
              0L, // cycleOutcompetedQty
              0L, // cycleMarketRounds
              shortfall ? 1L : 0L)); // 最近一次关账的投入不足读数，保留到下一个关账日
    }
    return exits;
  }

  /** 一个经营者在最近一轮市场里的可观察证据（由 {@link MarketReport.SellerOutcome} 聚合）。 */
  private record MarketEvidence(
      long offered,
      long filled,
      long unfilled,
      long revenue,
      long outcompetedActors,
      long outcompetedQty) {}

  private static MarketEvidence evidenceOf(MarketReport report, Industry industry) {
    if (report == null) {
      return new MarketEvidence(0L, 0L, 0L, 0L, 0L, 0L);
    }
    ActorRef operator = industry.operator();
    long offered = 0L;
    long filled = 0L;
    long unfilled = 0L;
    long revenue = 0L;
    long outcompeted = 0L;
    long outcompetedQty = 0L;
    for (MarketReport.SellerOutcome outcome : report.sellerOutcomes()) {
      if (!belongsTo(outcome, industry)) {
        continue;
      }
      offered += outcome.offeredQty();
      filled += outcome.filledQty();
      unfilled += outcome.unfilledQty();
      if (outcome.unfilledReason().orElse(null) == MarketUnfilledReason.OUTCOMPETED) {
        outcompeted += outcome.outcompetedByActorCount();
        outcompetedQty += outcome.outcompetedQty();
      }
    }
    for (MarketReport.Fill fill : report.fills()) {
      if (fill.seller().equals(operator)
          && industry.outputPerUnit().containsKey(fill.commodity())) {
        revenue += fill.goodsPaymentMilli();
      }
    }
    return new MarketEvidence(offered, filled, unfilled, revenue, outcompeted, outcompetedQty);
  }

  /**
   * 一条卖方槽是不是本产业的：优先认 {@code SellerOutcome.industryId}（市场侧已经认出来的产业），认不出来时回退到 {@code actor +
   * 本产业产出商品}（与 R3 首版口径兼容）。★ 回退不是猜：它只在"市场侧没给出产业 id"时启用， 且仍要求是本产业的产出商品。
   */
  private static boolean belongsTo(MarketReport.SellerOutcome outcome, Industry industry) {
    if (!outcome.actor().equals(industry.operator())) {
      return false;
    }
    if (!industry.outputPerUnit().containsKey(outcome.commodity())) {
      return false;
    }
    return outcome.industryId().map(industry.id()::equals).orElse(true);
  }

  private static OperatorCondition neutralCondition(IndustryId id) {
    return new OperatorCondition(
        id,
        IndustryStatus.ACTIVE,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        "",
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L);
  }

  private static long cashOf(
      HouseholdId household,
      ActorRef operator,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney) {
    long sum = 0L;
    if (household != null) {
      for (long value : householdMoney.getOrDefault(household, Map.of()).values()) {
        sum += value;
      }
      return sum;
    }
    for (long value : operatorMoney.getOrDefault(operator, Map.of()).values()) {
      sum += value;
    }
    return sum;
  }

  private static long stockOf(
      HouseholdId household,
      ActorRef operator,
      CommodityId commodity,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods) {
    return household != null
        ? householdGoods.getOrDefault(household, Map.of()).getOrDefault(commodity, 0L)
        : operatorGoods.getOrDefault(operator, Map.of()).getOrDefault(commodity, 0L);
  }

  /** 自用可覆盖量 = Σ_c min(库存_c, 下一周期投入需求_c)（库存能顶多少再生产，不是估价）。 */
  private static long selfUsableOf(
      HouseholdId household,
      Industry industry,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods) {
    long scale = EconomySettlement.capacityScaleOf(industry);
    long sum = 0L;
    for (Map.Entry<CommodityId, Long> entry : industry.inputPerUnit().entrySet()) {
      if (entry.getValue() <= 0L) {
        continue;
      }
      long need = entry.getValue() * scale;
      long stock =
          stockOf(household, industry.operator(), entry.getKey(), householdGoods, operatorGoods);
      sum += Math.min(need, stock);
    }
    return sum;
  }
}
