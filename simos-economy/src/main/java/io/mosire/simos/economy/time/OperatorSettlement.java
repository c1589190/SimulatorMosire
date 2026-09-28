package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.Debt;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.OperatorCondition.IndustryStatus;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>S3.2 经营者状态机（R3B.2 起主体 = {@link ProductionUnit}）</b>——
 * 全部转移由可观察量触发（滞销/被挤出/投入不足/现金与库存缓冲/债务本息/自用可覆盖量）， 阈值取 {@link StressPolicy}，不在 {@code switch} 里写死。
 *
 * <pre>
 * ACTIVE/TRIALING --连续滞销+OUTCOMPETED--> OVERSUPPLIED --再一个周期--> CONTRACTING
 * CONTRACTING --连续债务压力--> INDEBTED --连续无法偿付--> SUSPENDED --连续停业且不能自用维生--> EXITED
 * ACTIVE/TRIALING --连续投入不足--> CONTRACTING（生产侧原因；不经过滞销）
 * CONTRACTING --不能自用维生且投入不足/滞销连续超阈值--> SUSPENDED（生产侧破产；不必先经债务表）
 * INDEBTED/— --恢复证据（有成交、不欠本息、有现金）--> ACTIVE（有限重开）
 * </pre>
 *
 * <p>★★ <b>E1 的自用维生硬门</b>：{@link #canSelfProvision} 为真时，{@code CONTRACTING} 只停在 {@code
 * CONTRACTING}（reason {@code self_provision:...}），{@code INDEBTED} 回 {@code CONTRACTING}（reason
 * {@code recovered:self_provision}），{@code SUSPENDED} 永不因停业够久退出（最多有限重开）。解析不到家户的 ESTATE / WORKSHOP /
 * 聚合 weave 仍可缩产、停业、退出，只是不会凭空产生家户债务压力。
 *
 * <p>★★ <b>本类只判"状态怎么变 + 谁该退出"</b>；退出时的库存/货币偿债与 {@code Debt.defaulted} 处置由 {@code
 * EconomySettlement.settleOperatorExits} 落账（那里才有唯一写口 {@code applyTransfer}）。缩产只乘进"计划规模系数"，
 * <b>不销毁</b> {@code AssetShare}。
 *
 * <p>★★ <b>S3 修复：关账证据改成"本周期累计"</b>—— 一个周期里会有多轮市场（例行轮 + 低库存轮 + 关账轮），
 * 旧实现只取关账日当天那一轮报告作证据，于是"更早的轮里已经滞销/被挤出"在关账日完全看不见。现在每个市场轮结束后由 {@link #accumulateMarketEvidence}
 * 把该轮逐卖方的 {@code SellerOutcome}/{@code Fill} 累加进 {@link OperatorCondition} 的 {@code cycle*} 字段；关账日
 * {@link #advance} 消费这批累计证据，然后清零，下一周期重新累计。
 *
 * <p>★★ <b>R3B.2 的归属口径</b>：条件表的键 = unit id；"这个卖方是哪个 unit"由 {@link
 * MarketReport.SellerOutcome#unitId} 回答（认不出时回退到 {@code actor + 本模板产出商品}，与 R3 首版口径兼容）。同一 operator
 * 有多个 unit 且产出同一种商品时， 回退口径会把成交证据记到每一个匹配 unit 上 —— 这是本批如实记下的边界（精确到 unit 需要卖方槽位带 unit id，见 B.3）。
 */
final class OperatorSettlement {

  private OperatorSettlement() {}

  /** 一个应进入退出处置的经营者（状态机判"该退了"，不是"已经退干净了"）。 */
  record Exit(
      ProductionUnitId unit,
      IndustryId industry,
      ActorRef operator,
      HouseholdId household,
      String reason) {
    Exit {
      Objects.requireNonNull(unit, "unit");
      Objects.requireNonNull(industry, "industry");
      Objects.requireNonNull(operator, "operator");
      Objects.requireNonNull(reason, "reason");
    }
  }

  /**
   * ★★ <b>一个市场轮结束后累加周期证据</b>（每轮调用一次；唯一写口）。
   *
   * @param conditions 经营者状态工作表（键 = unit id；会被就地更新）
   * @param units 生产单元表（键 = unit id；本轮参与累加的主体）
   * @param industries 技术模板表（产出商品判据；只读）
   * @param report 本轮市场报告（{@code null} = 本轮没开市；保持已有累计不动）
   */
  static void accumulateMarketEvidence(
      LinkedHashMap<ProductionUnitId, OperatorCondition> conditions,
      Map<ProductionUnitId, ProductionUnit> units,
      Map<IndustryId, Industry> industries,
      MarketReport report) {
    if (report == null) {
      return;
    }
    // ★★ R4-B.3a-perf：旧实现逐 unit 扫整份报告（8,940 × 每轮卖方槽/成交），这里把报告一次摊到 unit。
    //   ★ 累加顺序仍逐 unit 保持“报告内的 outcome/fill 顺序”：外层按报告序，内层才按 unit —— 每个 unit 看到的
    //     仍是同一串发生额的同一序，故整数和与旧实现逐值相同（含 outcompeted 的入场条件）。
    Map<ActorRef, List<ProductionUnitId>> unitsByOperator = new LinkedHashMap<>();
    for (ProductionUnit unit : units.values()) {
      if (unit == null) {
        continue;
      }
      unitsByOperator.computeIfAbsent(unit.operator(), ignored -> new ArrayList<>()).add(unit.id());
    }
    Map<ProductionUnitId, long[]> byUnit = new LinkedHashMap<>();
    for (MarketReport.SellerOutcome outcome : report.sellerOutcomes()) {
      List<ProductionUnitId> candidates = unitsByOperator.get(outcome.actor());
      if (candidates == null) {
        continue;
      }
      for (ProductionUnitId id : candidates) {
        ProductionUnit unit = units.get(id);
        Industry industry = unit == null ? null : industries.get(unit.industry());
        if (unit == null || industry == null || !belongsTo(outcome, unit, industry)) {
          continue;
        }
        long[] evidence = byUnit.computeIfAbsent(id, ignored -> new long[6]);
        evidence[0] += outcome.offeredQty();
        evidence[1] += outcome.filledQty();
        evidence[2] += outcome.unfilledQty();
        if (outcome.unfilledReason().orElse(null) == MarketUnfilledReason.OUTCOMPETED) {
          evidence[4] += outcome.outcompetedByActorCount();
          evidence[5] += outcome.outcompetedQty();
        }
      }
    }
    for (MarketReport.Fill fill : report.fills()) {
      List<ProductionUnitId> candidates = unitsByOperator.get(fill.seller());
      if (candidates == null) {
        continue;
      }
      for (ProductionUnitId id : candidates) {
        ProductionUnit unit = units.get(id);
        Industry industry = unit == null ? null : industries.get(unit.industry());
        if (unit == null
            || industry == null
            || !industry.outputPerUnit().containsKey(fill.commodity())) {
          continue;
        }
        long[] evidence = byUnit.computeIfAbsent(id, ignored -> new long[6]);
        evidence[3] += fill.goodsPaymentMilli();
      }
    }
    for (ProductionUnitId id : new ArrayList<>(units.keySet())) {
      ProductionUnit unit = units.get(id);
      if (unit == null) {
        continue;
      }
      Industry industry = industries.get(unit.industry());
      if (industry == null) {
        continue;
      }
      long[] accumulated = byUnit.get(id);
      long offered = accumulated == null ? 0L : accumulated[0];
      long filled = accumulated == null ? 0L : accumulated[1];
      long unfilled = accumulated == null ? 0L : accumulated[2];
      long revenue = accumulated == null ? 0L : accumulated[3];
      long outcompetedActors = accumulated == null ? 0L : accumulated[4];
      long outcompetedQty = accumulated == null ? 0L : accumulated[5];
      MarketEvidence evidence =
          new MarketEvidence(offered, filled, unfilled, revenue, outcompetedActors, outcompetedQty);
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
        prev = neutralCondition(id, industry.id());
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
   * 关账日推进一步状态机（每个关账周期、对**本日关账的 unit** 调用一次），就地更新 {@code conditions}。
   *
   * @param closingUnits 本日关账的 unit 集合；不在集合里的 unit 原样保留（它们的周期证据不能在这里被消费/清零）
   * @param inputShortfallByUnit 本日关账 unit 在周期状态被清零**之前**捕获的"投入没凑齐"事实
   * @return 本轮判定要退出处置的经营者（库存/货币先偿债，不足才 {@code defaulted}）
   */
  static List<Exit> advance(
      LinkedHashMap<ProductionUnitId, OperatorCondition> conditions,
      LinkedHashMap<ProductionUnitId, ProductionUnit> units,
      Map<IndustryId, Industry> industries,
      Map<ProductionUnitId, ProductionRelation> relations,
      Map<HouseholdId, ClassRow> rows,
      SettlementIndex index,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      Map<HexCoord, Market> markets,
      Set<ProductionUnitId> closingUnits,
      Map<ProductionUnitId, Boolean> inputShortfallByUnit) {
    List<Exit> exits = new ArrayList<>();
    for (ProductionUnitId id : new ArrayList<>(units.keySet())) {
      if (!closingUnits.contains(id)) {
        continue; // ★ 未关账：周期证据与连续计数都保留到它自己的关账日
      }
      ProductionUnit unit = units.get(id);
      if (unit == null) {
        continue;
      }
      Industry industry = industries.get(unit.industry());
      if (industry == null) {
        throw new IllegalStateException("生产单元指名的产业模板不存在（状态已被改坏）: " + unit);
      }
      // ★★ E1：经营者 → 关联经济家户由 SettlementIndex 的唯一解析结果给出（tenant/artisan/自营家户命中；ESTATE /
      //   WORKSHOP / 聚合 weave 解析不到 ⇒ household=null：不伪造家户、不强行借债，但仍可缩产/停业/退出）。
      HouseholdId household = index.economicHouseholdOf(id).orElse(null);
      OperatorCondition prev = conditions.getOrDefault(id, neutralCondition(id, industry.id()));
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
      boolean shortfall = inputShortfallByUnit.getOrDefault(id, false);
      long shortfallCycles = shortfall ? prev.consecutiveInputShortfallCycles() + 1L : 0L;
      long debtPrincipal = 0L;
      long debtServiceDue = 0L;
      boolean debtStress = false;
      if (household != null) {
        // ★ R4-B.3a-perf：debtor → debts 在日结算入口/债务阶段边界建好，只查本户的债，不再每次扫全表。
        for (Debt debt : index.debtsByDebtor().getOrDefault(household, List.of())) {
          long due = debt.principal() + debt.principal() * debt.ratePerMillePerCycle() / 1_000L;
          debtPrincipal += debt.principal();
          debtServiceDue += due;
          long available =
              debt.commodity().isPresent()
                  ? stockOf(
                      household,
                      unit.operator(),
                      debt.commodity().get(),
                      householdGoods,
                      operatorGoods)
                  : cashOf(household, unit.operator(), householdMoney, operatorMoney);
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
      // ★★ E1：自用维生硬门（唯一判据在 canSelfProvision）—— 提前算好，下面四条新路径都读同一个答案。
      long selfUsable =
          selfUsableOf(household, unit, industry, index, householdGoods, operatorGoods);
      long cash = cashOf(household, unit.operator(), householdMoney, operatorMoney);
      boolean canSelfProvision =
          canSelfProvision(unit, household, industry, index, rows, householdGoods, operatorGoods);
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
          // ★★ E1 规则 2：可自用维生 ⇒ 停在 CONTRACTING（不得进 INDEBTED/SUSPENDED/EXITED）；不能自用且再生产压力
          //   连续超阈值 ⇒ 直接停业（生产侧破产路径，不要求先经过债务表）。债务压力路径仍保留在它后面。
          if (canSelfProvision) {
            status = IndustryStatus.CONTRACTING;
            reason =
                "self_provision:shortfallCycles="
                    + shortfallCycles
                    + ",unsoldCycles="
                    + unsoldCycles
                    + ",selfUsable="
                    + selfUsable;
          } else if (shortfallCycles >= StressPolicy.INPUT_SHORTFALL_CYCLES_BEFORE_CANNOT_REPRODUCE
              || unsoldCycles >= StressPolicy.UNSOLD_CYCLES_BEFORE_CANNOT_REPRODUCE) {
            status = IndustryStatus.SUSPENDED;
            reason =
                "cannot_reproduce:shortfallCycles="
                    + shortfallCycles
                    + ",unsoldCycles="
                    + unsoldCycles;
          } else if (debtStressCycles >= StressPolicy.DEBT_STRESS_CYCLES_BEFORE_INDEBTED) {
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
          // ★★ E1 规则 3：可自用维生 ⇒ 回 CONTRACTING（recovered:self_provision），不进 SUSPENDED；债务压力路径保留。
          if (canSelfProvision) {
            status = IndustryStatus.CONTRACTING;
            reason = "recovered:self_provision";
          } else if (debtStressCycles >= StressPolicy.DEBT_STRESS_CYCLES_BEFORE_SUSPENDED) {
            status = IndustryStatus.SUSPENDED;
            reason = "suspended:debtStressCycles=" + debtStressCycles;
          } else if (hadMarket && !debtStress && prev.cycleFilledQty() > 0L && cash > 0L) {
            status = IndustryStatus.ACTIVE;
            reason = "recovered:cycleFilled=" + prev.cycleFilledQty();
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
          } else if (suspendedCycles >= StressPolicy.SUSPENDED_CYCLES_BEFORE_EXIT
              && !canSelfProvision) {
            // ★★ E1 规则 4：退出必须同时满足"停业够久"与"确实不能自用维生"；后者为真时永不退出。
            status = IndustryStatus.EXITED;
            reason = "exited:suspendedCycles=" + suspendedCycles;
            exits.add(new Exit(id, unit.industry(), unit.operator(), household, reason));
          } else if (canSelfProvision) {
            // 停业够久但还能自用 ⇒ 保持停业（绝不退出）；reason 明确写出是因为自用维生而没退。
            reason = "suspended:self_provision";
          }
        }
        case EXITING -> {
          status = IndustryStatus.EXITED;
          reason = "exited:fromExiting";
          exits.add(new Exit(id, unit.industry(), unit.operator(), household, reason));
        }
        case EXITED, ABANDONED -> {
          // ★ 已退出/弃置：状态不再自转；恢复只能走显式重开（本批没有该命令）。
        }
      }
      long plannedScale = ProductionUnitBook.plannedCapacityScaleOf(unit, industry, index, prev);
      long costEstimate = 0L;
      HexCoord hex = IndustryHexKeys.hexKeyOf(industry.id()).map(HexCoord::parse).orElse(null);
      if (hex != null) {
        Market market = markets.get(hex);
        ProducerCostBook.Estimate estimate =
            ProducerCostBook.estimate(unit, industry, index, market, relations.get(id));
        costEstimate = estimate.unitCostEstimateMilli() * plannedScale / 1_000L;
      }
      // ★ 无市场轮 ⇒ 没有新证据：lastCycle* 与滞销读数保持上一周期原值，不用 0 覆盖。
      long lastCycleRevenue = hadMarket ? prev.cycleRevenueMilli() : prev.lastCycleRevenueMilli();
      long lastCycleCost = hadMarket ? costEstimate : prev.lastCycleCostMilli();
      long lastCycleNet = hadMarket ? lastCycleRevenue - lastCycleCost : prev.lastCycleNetMilli();
      long unsoldStock = hadMarket ? prev.cycleUnfilledQty() : prev.unsoldStockMilli();
      conditions.put(
          id,
          new OperatorCondition(
              industry.id(),
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

  /** 一个 unit 在最近一轮市场里的可观察证据（由 {@link MarketReport.SellerOutcome} 聚合）。 */
  private record MarketEvidence(
      long offered,
      long filled,
      long unfilled,
      long revenue,
      long outcompetedActors,
      long outcompetedQty) {}

  /**
   * 一条卖方槽是不是本 unit 的：优先认 {@code SellerOutcome.unitId}（市场侧已经认出来的 unit）， 认不出来时回退到 {@code actor +
   * 本模板产出商品}（与 R3 首版口径兼容）。
   */
  private static boolean belongsTo(
      MarketReport.SellerOutcome outcome, ProductionUnit unit, Industry industry) {
    if (!outcome.actor().equals(unit.operator())) {
      return false;
    }
    if (!industry.outputPerUnit().containsKey(outcome.commodity())) {
      return false;
    }
    return outcome.unitId().map(unit.id()::equals).orElse(true);
  }

  /** 一个模板下的中性条件（键 = unit id；值内 industry = 模板 id）。 */
  private static OperatorCondition neutralCondition(ProductionUnitId unit, IndustryId industry) {
    return new OperatorCondition(
        industry,
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

  /**
   * ★★ <b>E1：这个 unit 能不能自用维生（只读判据；唯一拼写点）</b>。
   *
   * <pre>
   * 家户可解析（且行存在）：
   *   ① 粮库存 ≥ 本周期基本口粮 = cumulativeRationMilli(人口, industry.cycleDays)          ⇒ true
   *   ② 否则：粮库存 ≥ SELF_PROVISION_GUARD_DAYS 天的口粮（守卫，防"有种子没饭吃"）
   *      且 selfUsableOf 覆盖下一周期全部投入需求                                          ⇒ true
   *   ③ 其余                                                                              ⇒ false
   * 解析不到（ESTATE / WORKSHOP / 聚合 weave）：用 operator 账的 selfUsableOf 覆盖下一周期投入需求
   * </pre>
   *
   * <p>★★ <b>为什么自用品要"覆盖全部投入"而不是"有正数"</b>：只要有一种投入覆盖不到，下一周期就开不了工；把 {@code Σ min(库存, 需求)} 与 {@code Σ
   * 需求} 比较，两者相等当且仅当每种投入都覆盖到 —— 与 {@link #selfUsableOf} 同一口径。
   *
   * <p>★ 本判据不改任何状态；{@code CONTRACTING/INDEBTED/SUSPENDED} 的转移用它作硬门。
   */
  static boolean canSelfProvision(
      ProductionUnit unit,
      HouseholdId household,
      Industry industry,
      SettlementIndex index,
      Map<HouseholdId, ClassRow> rows,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods) {
    if (household != null) {
      ClassRow row = rows.get(household);
      if (row != null) {
        long grainStock =
            stockOf(
                household, unit.operator(), EconomySettlement.GRAIN, householdGoods, operatorGoods);
        long cycleRation =
            io.mosire.simos.util.economy.EconomyVocabulary.cumulativeRationMilli(
                row.population(), industry.cycleDays());
        if (grainStock >= cycleRation) {
          return true;
        }
        long guardRation =
            io.mosire.simos.util.economy.EconomyVocabulary.cumulativeRationMilli(
                row.population(), StressPolicy.SELF_PROVISION_GUARD_DAYS);
        return grainStock >= guardRation
            && coversNextCycleInputs(
                unit, household, industry, index, householdGoods, operatorGoods);
      }
    }
    // ★ 解析不到家户（或家户行缺失）：只看 operator 账的可自用投入覆盖 —— 不伪造家户，也不凭空给它口粮。
    return coversNextCycleInputs(unit, null, industry, index, householdGoods, operatorGoods);
  }

  /** 下一周期投入需求是否被自用库存全覆盖（{@code Σ min(库存,需求) == Σ 需求}；无投入需求视为已覆盖）。 */
  private static boolean coversNextCycleInputs(
      ProductionUnit unit,
      HouseholdId household,
      Industry industry,
      SettlementIndex index,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods) {
    long scale = ProductionUnitBook.capacityScaleOf(unit, industry, index);
    if (scale <= 0L) {
      return false; // 没有可用资产 ⇒ 没有"下一周期生产"可谈（不是"投入需求为零所以已覆盖"）
    }
    long required = 0L;
    for (Map.Entry<CommodityId, Long> entry : industry.inputPerUnit().entrySet()) {
      if (entry.getValue() <= 0L) {
        continue;
      }
      required += entry.getValue() * scale;
    }
    if (required <= 0L) {
      return true; // 没有实物投入需求 ⇒ 没有"覆盖不到"的投入
    }
    long covered = selfUsableOf(household, unit, industry, index, householdGoods, operatorGoods);
    return covered >= required;
  }

  /** 自用可覆盖量 = Σ_c min(库存_c, 下一周期投入需求_c)（库存能顶多少再生产，不是估价）。 */
  private static long selfUsableOf(
      HouseholdId household,
      ProductionUnit unit,
      Industry industry,
      SettlementIndex index,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods) {
    long scale = ProductionUnitBook.capacityScaleOf(unit, industry, index);
    long sum = 0L;
    for (Map.Entry<CommodityId, Long> entry : industry.inputPerUnit().entrySet()) {
      if (entry.getValue() <= 0L) {
        continue;
      }
      long need = entry.getValue() * scale;
      long stock =
          stockOf(household, unit.operator(), entry.getKey(), householdGoods, operatorGoods);
      sum += Math.min(need, stock);
    }
    return sum;
  }
}
