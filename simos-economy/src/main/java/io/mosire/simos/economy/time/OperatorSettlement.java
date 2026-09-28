package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
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
   * 推进一步状态机（每个关账周期调用一次），就地更新 {@code conditions}。
   *
   * @param inputShortfallByIndustry 本周期关账时逐产业的"投入没凑齐"事实（在周期状态被清零**之前**由日循环捕获）
   * @return 本轮判定要退出处置的经营者（库存/货币先偿债，不足才 {@code defaulted}）
   */
  static List<Exit> advance(
      LinkedHashMap<IndustryId, OperatorCondition> conditions,
      LinkedHashMap<IndustryId, Industry> industries,
      Map<IndustryId, io.mosire.simos.economy.api.relation.ProductionRelation> relations,
      LinkedHashMap<DebtId, Debt> debts,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      Map<ActorRef, HouseholdId> householdOfActor,
      Map<HexCoord, Market> markets,
      Map<IndustryId, Boolean> inputShortfallByIndustry,
      MarketReport report,
      long day) {
    List<Exit> exits = new ArrayList<>();
    for (IndustryId id : new ArrayList<>(industries.keySet())) {
      Industry industry = industries.get(id);
      if (industry == null) {
        continue;
      }
      HouseholdId household = householdOfActor.get(industry.operator());
      MarketEvidence evidence = evidenceOf(report, industry);
      long cash = cashOf(household, industry.operator(), householdMoney, operatorMoney);
      long selfUsable = selfUsableOf(household, industry, householdGoods, operatorGoods);
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
                  : cash;
          if (due > available) {
            debtStress = true;
          }
        }
      }
      OperatorCondition prev = conditions.getOrDefault(id, neutralCondition(id));
      boolean shortfall = inputShortfallByIndustry.getOrDefault(id, false);
      boolean outcompeted = evidence.outcompetedActors >= StressPolicy.OUTCOMPETED_MIN_ACTORS;
      long unsoldCycles =
          evidence.offered > 0L && evidence.filled <= 0L && outcompeted
              ? prev.consecutiveUnsoldCycles() + 1L
              : 0L;
      long shortfallCycles = shortfall ? prev.consecutiveInputShortfallCycles() + 1L : 0L;
      long debtStressCycles = debtStress ? prev.consecutiveDebtStressCycles() + 1L : 0L;
      long suspendedCycles = 0L;
      long reopens = prev.reopens();
      IndustryStatus status = prev.status();
      String reason = prev.lastReason();
      switch (status) {
        case ACTIVE, TRIALING -> {
          if (unsoldCycles >= StressPolicy.UNSOLD_CYCLES_BEFORE_OVERSUPPLIED && outcompeted) {
            status = IndustryStatus.OVERSUPPLIED;
            reason =
                "oversupplied:unsoldCycles="
                    + unsoldCycles
                    + ",outcompeted="
                    + evidence.outcompetedActors;
          } else if (shortfallCycles >= StressPolicy.INPUT_SHORTFALL_CYCLES_BEFORE_CONTRACTING) {
            status = IndustryStatus.CONTRACTING;
            reason = "contracting:inputShortfallCycles=" + shortfallCycles;
          }
        }
        case OVERSUPPLIED -> {
          if (unsoldCycles >= StressPolicy.UNSOLD_CYCLES_BEFORE_OVERSUPPLIED) {
            status = IndustryStatus.CONTRACTING;
            reason = "contracting:oversuppliedUnsoldCycles=" + unsoldCycles;
          } else if (evidence.filled > 0L) {
            status = IndustryStatus.ACTIVE;
            reason = "recovered:filled=" + evidence.filled;
          }
        }
        case CONTRACTING -> {
          if (debtStressCycles >= StressPolicy.DEBT_STRESS_CYCLES_BEFORE_INDEBTED) {
            status = IndustryStatus.INDEBTED;
            reason = "indebted:debtStressCycles=" + debtStressCycles;
          } else if (evidence.filled > 0L && shortfallCycles == 0L && !debtStress) {
            status = IndustryStatus.ACTIVE;
            reason = "recovered:filled=" + evidence.filled;
          }
        }
        case INDEBTED -> {
          if (debtStressCycles >= StressPolicy.DEBT_STRESS_CYCLES_BEFORE_SUSPENDED) {
            status = IndustryStatus.SUSPENDED;
            reason = "suspended:debtStressCycles=" + debtStressCycles;
          } else if (!debtStress && evidence.filled > 0L && cash > 0L) {
            status = IndustryStatus.ACTIVE;
            reason = "recovered:filled=" + evidence.filled;
          }
        }
        case SUSPENDED -> {
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
              evidence.revenue,
              costEstimate,
              evidence.revenue - costEstimate,
              evidence.unfilled,
              selfUsable,
              debtStressCycles,
              suspendedCycles,
              reopens,
              reason));
    }
    return exits;
  }

  /** 一个经营者在最近一轮市场里的可观察证据（由 {@link MarketReport.SellerOutcome} 聚合）。 */
  private record MarketEvidence(
      long offered, long filled, long unfilled, long revenue, long outcompetedActors) {}

  private static MarketEvidence evidenceOf(MarketReport report, Industry industry) {
    if (report == null) {
      return new MarketEvidence(0L, 0L, 0L, 0L, 0L);
    }
    ActorRef operator = industry.operator();
    long offered = 0L;
    long filled = 0L;
    long unfilled = 0L;
    long revenue = 0L;
    long outcompeted = 0L;
    for (MarketReport.SellerOutcome outcome : report.sellerOutcomes()) {
      if (!outcome.actor().equals(operator)
          || !industry.outputPerUnit().containsKey(outcome.commodity())) {
        continue; // ★ 多产业共用一个 operator 时，只认本产业产出的商品（不把别的产业的成交量算进来）
      }
      offered += outcome.offeredQty();
      filled += outcome.filledQty();
      unfilled += outcome.unfilledQty();
      if (outcome.unfilledReason().orElse(null) == MarketUnfilledReason.OUTCOMPETED) {
        outcompeted += outcome.outcompetedByActorCount();
      }
    }
    for (MarketReport.Fill fill : report.fills()) {
      if (fill.seller().equals(operator)
          && industry.outputPerUnit().containsKey(fill.commodity())) {
        revenue += fill.goodsPaymentMilli();
      }
    }
    return new MarketEvidence(offered, filled, unfilled, revenue, outcompeted);
  }

  private static OperatorCondition neutralCondition(IndustryId id) {
    return new OperatorCondition(
        id, IndustryStatus.ACTIVE, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, "");
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
