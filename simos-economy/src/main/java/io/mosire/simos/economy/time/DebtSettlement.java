package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.EconomySettlement.CAPITALIZATION_TERMS_SOURCE;
import static io.mosire.simos.economy.time.EconomySettlement.GRAIN;
import static io.mosire.simos.economy.time.ProductionInputDrawSettlement.cycleDaysByHousehold;
import static io.mosire.simos.economy.time.SettlementLogSupport.DEBT;
import static io.mosire.simos.economy.time.SettlementLogSupport.TRACE;
import static io.mosire.simos.economy.time.SettlementMath.grainOf;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.debt.DebtSource;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.debt.InterestTiming;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.model.DebtCapacity;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/** 由 EconomySettlement 按职责机械抽取；包内实现，不构成跨模块 API。 */
final class DebtSettlement {

  /**
   * ★★ <b>E4b：本日借粮用的 {@link DebtCapacity} 映射</b>（唯一算法在 {@link DebtCapacityBook}）。
   *
   * <p>★★ <b>三个“流量”的窗口为什么这样取</b>（与终态 {@code FlowRow} 的写入逐字对齐）：
   *
   * <pre>
   * afterAllocationGrainIncome = (本周期翻篇 ? 0 : 流水中的 income[grain])  + 今日 earned[grain]
   * cycleToDateGrainConsumed   = (本周期翻篇 ? 0 : 流水中的 consumed[grain]) + 今日 consumed[grain]
   * taxPaid                    = (本周期翻篇 ? 0 : 流水中的 taxPaid)          // 当前生产路径恒 0，照实读
   * </pre>
   *
   * ★ 三条与 {@code settleOneDayInto} 末尾写 {@code FlowRow} 时的 {@code acc =
   * newCycleHouseholds.contains(key) ? null : flows.get(key)} 是同一条口径 ⇒ 借粮读到的“本周期” 与关账那一支读到的不是两个窗口（旧
   * {@code creditLinesOf} 只读当日 {@code income} 局部量，是另一个窗口 —— 见交付报告）。
   *
   * <p>★ 库存由调用点保证（{@code requireHouseholdAccounts}）⇒ 本方法里每行都“读得到”；读口那边才可能出现空（具名缺失）。
   *
   * <p>★★ <b>债务价格钩子由调用方注入</b>（D-030 §3.4）：生产借贷/清算路径传 {@link
   * DebtCapacityBook#marketPriceLookup(java.util.Map)} 的市场价目表 lookup；旧读口仍可传 {@link
   * DebtCapacityBook#NO_UNIT_PRICES} 保留旧口径。
   */
  static Map<HouseholdId, DebtCapacity> debtCapacitiesForDay(
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, FlowRow> flows,
      Set<HouseholdId> newCycleHouseholds,
      Map<HouseholdId, Map<CommodityId, Long>> income,
      Map<HouseholdId, Map<CommodityId, Long>> consumed,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Long> cycleDaysByHousehold,
      Map<DebtContractId, DebtContract> debts,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<IndustryId, IndustryDefinitionView> industries,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
      SettlementIndex settlementIndex,
      DebtCapacityBook.DebtUnitValueLookup debtUnitValueLookup) {
    Map<HouseholdId, Long> cycleToDateIncome = new LinkedHashMap<>();
    Map<HouseholdId, Long> cycleToDateConsumed = new LinkedHashMap<>();
    Map<HouseholdId, Long> taxPaid = new LinkedHashMap<>();
    for (HouseholdId key : householdEconomies.keySet()) {
      FlowRow accrual = newCycleHouseholds.contains(key) ? null : flows.get(key);
      long earnedToday = income.getOrDefault(key, Map.of()).getOrDefault(GRAIN, 0L);
      long consumedToday = consumed.getOrDefault(key, Map.of()).getOrDefault(GRAIN, 0L);
      cycleToDateIncome.put(
          key,
          Math.addExact(
              accrual == null ? 0L : accrual.income().getOrDefault(GRAIN, 0L), earnedToday));
      cycleToDateConsumed.put(
          key,
          Math.addExact(
              accrual == null ? 0L : accrual.consumed().getOrDefault(GRAIN, 0L), consumedToday));
      taxPaid.put(key, accrual == null ? 0L : accrual.taxPaid());
    }
    return DebtCapacityBook.capacities(
        householdEconomies,
        cycleToDateIncome,
        cycleToDateConsumed,
        taxPaid,
        key -> OptionalLong.of(grainOf(householdGoods, key)),
        cycleDaysByHousehold,
        debts,
        units,
        industries,
        assetShares,
        operatorConditions,
        settlementIndex
            .d4AssetOccupancyBudget()
            .orElseThrow(
                () -> new IllegalStateException("asset-occupancy-unavailable: debt-capacity")),
        DebtCapacity.CREDIT_ASSET_POLICY_VALUE_NOT_LANDED,
        debtUnitValueLookup);
  }

  /*
   * ★ E4b/D-030/D-031：新口径的唯一算法仍在 DebtCapacityBook.capacities，但现在只服务清算/阶层下滑/只读诊断；
   * 借粮（4b）与市场信用都已不再把 headroom 当借款门（D-031：借款人无上限，唯一上限 = 放贷人实际可借头寸）。
   * 旧 {@code LOAN_INCOME_MULTIPLE_PER_MILLE} 兼容别名已删（W2-fix6；κ 的唯一拼写点是 {@code DebtCapacity.CREDIT_F_MULTIPLE_PER_MILLE}）。
   */

  /**
   * ★★ <b>D-030 §3.4/§3.5 的家户 → 市场区默认价目表索引</b>：
   *
   * <pre>
   * ① 该家户本格有市场条目 ⇒ 用它；
   * ② 否则若该格在本日拓扑的成员表里 ⇒ 用该区锚格的市场条目（区默认价目表）；
   * ③ 都没有 ⇒ 缺键（该债 unpriced、identity 腿仍可付）。
   * </pre>
   *
   * <p>★ 只读派生、不写市场表；单区（D-027）时锚格就是规范序第一个市场格。缺格不是坏数据：没有市场默认价目表的家户 仍可原物原还。
   */
  static Map<HouseholdId, MarketTable> marketIndexByHousehold(
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<MarketZoneId, MarketTable> markets,
      MarketTopology topology) {
    LinkedHashMap<HouseholdId, MarketTable> byHousehold = new LinkedHashMap<>();
    for (HouseholdEconomy householdEconomy : householdEconomies.values()) {
      MarketTable market = marketForHex(householdEconomy.view().hex(), markets, topology);
      if (market != null) {
        byHousehold.put(householdEconomy.id(), market);
      }
    }
    return byHousehold;
  }

  /** 单格 → 市场区默认价目表：本格优先，缺则区锚格；没有归属 ⇒ null（合法状态）。 */
  static MarketTable marketForHex(
      HexCoord hex, Map<MarketZoneId, MarketTable> markets, MarketTopology topology) {
    if (topology == null || !topology.contains(hex)) {
      return null;
    }
    // ★ W1：本格价表 = 本格所属市场的那一张（区内任何格同一张；区 id = 拓扑节点 id）。
    return markets.get(new MarketZoneId(topology.regionOf(hex).node().nodeId()));
  }

  /**
   * ★★ <b>E4c：同一天内资本化事件的稳定去重键</b>——{@code (payer, payee, unit, activity, rule)}。
   *
   * <p>★ 合同身份仍是 {@code DebtContractId} 的四元组 {@code (debtor, creditor, unit, terms)}；这里多出的 {@code
   * activity} / {@code rule} 只用于<b>同一份日账本内</b>识别"两笔读数是不是同一次事件"： 同一 unit +
   * 同一规则只有同一次事件才会重复，重复则累加、只写一次合同。跨周期同四元组照旧连续累加（E4a 语义不变）。
   */
  record CapitalizationEventKey(
      ActorRef payer,
      ActorRef payee,
      String unitKey,
      ProductionUnitId activity,
      CompensationRule rule) {}

  /** 契约/跨切片一致性故障：ERROR 不降级，带稳定词返回给调用方抛出。 */
  static IllegalStateException contractFault(String word, String detail) {
    EventLog.channel(TRACE)
        .error(
            LogEvent.of(
                "ECONOMY_SETTLEMENT_CONTRACT_FAULT",
                EconomyLogSource.ECONOMY_DEBT,
                "failure",
                word,
                "detail",
                detail == null ? "-" : detail));
    return new IllegalStateException(word + ": " + detail);
  }

  /**
   * ★★ <b>E4c：欠租/欠薪资本化</b>（生产/租金阶段之后；见 {@code settleOneDayInto} 的 3b）。
   *
   * <pre>
   * 输入   = 本日 ProductionLedger 的 Arrear 读数（owed = 应付 − 实付 > 0）
   * 去重   = 同一天内 (payer, payee, Money.key(), activity, rule) 相同 ⇒ 读数额相加、只 upsert 一次
   * unit   = Arrear.currency 有值 ⇒ DebtUnit.Money(currency)
   *          否则 commodity 有值 ⇒ 按 activity 所在市场区折成该区 numeraire 的 DebtUnit.Money
   *          无 activity/无市场/无价/非正价 ⇒ 具名 commodity-price-unavailable，不建债、不 0 折、不 1:1
   * terms  = Arrear.Kind.WAGE ⇒ DebtTerms.wageArrearsDefault()（0‰）+ DebtSource.WAGE_ARREARS
   *          Arrear.Kind.RENT ⇒ DebtTerms.legacyDefault()（20‰）+ DebtSource.RENT_ARREARS
   *          其余类别 ⇒ fail-closed debt-source-missing（不得猜来源，也不得套工资债）
   * amount = 货币本金：currency 原额；商品欠款 = max(1, floor(owed × price ÷ 1000))
   * day    = 当日；dueCycle = 当前周期 + 1
   * 端点   = P2：{@link DebtPartyResolver} 唯一解析点到责任家户；多户按人口最大余数拆 owed，
   *          再对每个 debtor × creditor 组合经 {@link DebtPartyResolver#splitDebtAmounts} 守恒拆分。
   *          任一端解析不到 ⇒ 具名 unresolved，跳过，不伪造端点；同户自债显式净额（不落合同）。
   * 库存   = **不动**：资本化只写债权本金；不铸转移、不扣库存/货币，也不清零 Arrear 读数
   * </pre>
   *
   * <p>★★ <b>手算例子</b>：某作坊 operator（家户 H1）本周期应付实物工资 1,000 毫粮、实付 400 ⇒ Arrear{@code owed = 600}；
   * 活动所在市场区 numeraire = 银、粮价 = 2（毫银/商品单位）⇒ 货币本金 = max(1, floor(600 × 2 ÷ 1000)) = 1 毫银。 资本化后合同是
   * {@code DebtContractId.idOf(H1, H2, Money(silver), wageArrearsDefault)}，本金 +1、状态 NORMAL、{@code
   * dueCycle = 当前周期 + 1}；H1 的行流水 {@code capitalizedArrears["money:silver"] += 1}； 粮库存一分不动（H1 与 H2
   * 的账都保持原值）。同一日如果又读到一条完全相同的 600 欠款读数（同 activity/rule）， 去重后只写一次、金额 2（{@code eventCount = 2}）。
   *
   * <p>★★ <b>多户 × 多户的金额守恒</b>：折价后的货币本金先按债务人份额、再逐债务人按债权人份额走最大余数法；每个组合金额 {@code > 0} 才落合同，Σ合同金额 +
   * 自债净额 == 货币本金由 {@link DebtPartyResolver#splitDebtAmounts} 用 {@code Math.addExact} 当场核对。组合数超过
   * {@link DebtPartyResolver#MAX_PARTY_COMBINATIONS} 时具名拒绝 （整笔进 unresolved，不静默丢）。
   *
   * @param data 结算前的不可变状态（读 classes / classPositions / classStandings /
   *     productionOrganizations）；不得为 null
   * @param index 当日只读派生索引（主体↔unit / 主体↔组织 / 家户 actor）；不得为 null
   * @param topology 市场区拓扑（商品欠款折价时定位活动所在市场区）；可为 null = 没有市场区
   * @param ledger 当日累加器（读 {@code arrears()}、写资本化/跳过审计）；不得为 null
   * @param rows 家户行工作表（解析债务人后补派生引用）；不得为 null
   * @param debts 债务工作表（唯一写口 {@link DebtContractBook#upsert}）；不得为 null
   * @param capitalizedByHousehold 本日逐户逐 unit 的资本化累加器（就地更新 ⇒ 进 {@code FlowRow.capitalizedArrears}）；
   *     不得为 null
   * @param day 资本化发生日（进新合同 {@code openedDay}）；不得为负
   * @param dueCycle 资本化合同的到期周期（当前周期 + 1）
   */
  static void capitalizeArrears(
      EconomyDayView data,
      SettlementIndex index,
      MarketTopology topology,
      ProductionLedger.Accumulator ledger,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<DebtContractId, DebtContract> debts,
      LinkedHashMap<HouseholdId, Map<String, Long>> capitalizedByHousehold,
      long day,
      long dueCycle,
      Set<DebtContractId> sameDayWageDebtIds) {
    List<ProductionLedger.Arrear> arrears = ledger.arrears();
    if (arrears.isEmpty()) {
      return; // 没有欠款读数 ⇒ 连去重表都不建（旧路径逐值不变）
    }
    // ★★ 第一步：按事件键稳定去重并累加（键序 = 读数在当日账本里的出现序 ⇒ 可复现）。
    LinkedHashMap<CapitalizationEventKey, Long> owedByEvent = new LinkedHashMap<>();
    LinkedHashMap<CapitalizationEventKey, ProductionLedger.Arrear> sampleByEvent =
        new LinkedHashMap<>();
    LinkedHashMap<CapitalizationEventKey, Integer> countByEvent = new LinkedHashMap<>();
    for (ProductionLedger.Arrear arrear : arrears) {
      MoneyResolution resolution = resolveMoney(data, index, topology, arrear);
      if (!resolution.resolved()) {
        ledger.addUnresolvedDebtCapitalization(
            new ProductionLedger.UnresolvedDebtCapitalization(
                arrear.payer(),
                arrear.payee(),
                arrear.activity(),
                arrear.rule(),
                arrear.commodity(),
                arrear.currency(),
                arrear.owed(),
                resolution.failureReason()));
        if (DEBT.isDebugEnabled()) {
          EventLog.channel(DEBT)
              .debug(
                  LogEvent.of(
                      "COMMODITY_ARREAR_PRICE_UNAVAILABLE",
                      EconomyLogSource.ECONOMY_DEBT,
                      "payer",
                      arrear.payer().id(),
                      "payee",
                      arrear.payee().id(),
                      "activity",
                      arrear.activity().value(),
                      "owed",
                      arrear.owed(),
                      "reason",
                      resolution.failureReason()));
        }
        continue;
      }
      DebtUnit.Money unit = resolution.unit();
      CapitalizationEventKey key =
          new CapitalizationEventKey(
              arrear.payer(), arrear.payee(), unit.key(), arrear.activity(), arrear.rule());
      owedByEvent.merge(key, arrear.owed(), Math::addExact);
      sampleByEvent.putIfAbsent(key, arrear);
      countByEvent.merge(key, 1, Integer::sum);
    }
    // ★★ 第二步：逐事件解析两端 → 守恒拆金额 → 逐组合 upsert → 补派生引用 → 记审计。
    for (Map.Entry<CapitalizationEventKey, Long> entry : owedByEvent.entrySet()) {
      CapitalizationEventKey key = entry.getKey();
      ProductionLedger.Arrear sample = sampleByEvent.get(key);
      long owed = entry.getValue();
      MoneyResolution resolution = resolveMoney(data, index, topology, sample);
      if (!resolution.resolved()) {
        // 第一轮已解析成功 ⇒ 第二轮仍失败只可能是结算中途状态损坏；不静默改写成 0，仍具名留账。
        ledger.addUnresolvedDebtCapitalization(
            new ProductionLedger.UnresolvedDebtCapitalization(
                sample.payer(),
                sample.payee(),
                sample.activity(),
                sample.rule(),
                sample.commodity(),
                sample.currency(),
                owed,
                resolution.failureReason()));
        continue;
      }
      DebtUnit.Money unit = resolution.unit();
      long owedMoney =
          resolution.pricePerUnitMilli() > 0L ? convertCommodityArrear(owed, resolution) : owed;
      if (resolution.pricePerUnitMilli() > 0L && DEBT.isDebugEnabled()) {
        EventLog.channel(DEBT)
            .debug(
                LogEvent.of(
                    "COMMODITY_ARREAR_MONETIZED",
                    EconomyLogSource.ECONOMY_DEBT,
                    "activity",
                    sample.activity().value(),
                    "commodity",
                    sample.commodity().map(CommodityId::value).orElse("-"),
                    "owedCommodityMilli",
                    owed,
                    "pricePerCommodityUnitMilli",
                    resolution.pricePerUnitMilli(),
                    "owedMoneyMilli",
                    owedMoney,
                    "currency",
                    unit.currency().value()));
      }
      Optional<ProductionUnitId> activity = Optional.of(sample.activity());
      DebtPartyResolver.Resolution debtorShares =
          DebtPartyResolver.resolveActor(data, index, sample.payer(), null, activity);
      DebtPartyResolver.Resolution creditorShares =
          DebtPartyResolver.resolvePayee(data, index, sample.rule().recipient(), null, activity);
      if (!debtorShares.isResolved() || !creditorShares.isResolved()) {
        String reason = unresolvedDebtCapitalizationReason(debtorShares, creditorShares);
        ledger.addUnresolvedDebtCapitalization(
            new ProductionLedger.UnresolvedDebtCapitalization(
                sample.payer(),
                sample.payee(),
                sample.activity(),
                sample.rule(),
                sample.commodity(),
                sample.currency(),
                owed,
                reason));
        continue;
      }
      DebtPartyResolver.DebtSplit split =
          DebtPartyResolver.splitDebtAmounts(
              owedMoney, debtorShares.shares(), creditorShares.shares());
      if (split.rejected()) {
        ledger.addUnresolvedDebtCapitalization(
            new ProductionLedger.UnresolvedDebtCapitalization(
                sample.payer(),
                sample.payee(),
                sample.activity(),
                sample.rule(),
                sample.commodity(),
                sample.currency(),
                owedMoney,
                "split-rejected:" + split.rejectionReason()));
        continue;
      }
      if (split.selfNettedAmount() > 0L) {
        // ★ 同户自债没有可落合同的真实债权；金额仍守恒（split 内已核），这里显式记审计、不伪造对方。
        ledger.addUnresolvedDebtCapitalization(
            new ProductionLedger.UnresolvedDebtCapitalization(
                sample.payer(),
                sample.payee(),
                sample.activity(),
                sample.rule(),
                sample.commodity(),
                sample.currency(),
                split.selfNettedAmount(),
                "self-party-netted:" + split.selfHouseholds().size() + "-households"));
      }
      DebtTerms terms;
      DebtSource source;
      switch (sample.kind()) {
        case WAGE -> {
          // ★★★ D3/U23/R-11：旧 wage 路径也不再生息；必须同时写 WAGE_ARREARS 与 0‰。
          //   P2c：货币为空的实物工资欠款已在上面按活动市场区价折成 Money；无价则 unresolved，不静默丢。
          terms = DebtTerms.wageArrearsDefault();
          source = DebtSource.WAGE_ARREARS;
        }
        case RENT -> {
          terms = DebtTerms.legacyDefault();
          source = DebtSource.RENT_ARREARS;
        }
        default ->
            throw contractFault(
                "debt-source-missing",
                "capitalizeArrears 无法为欠款类别确定 DebtSource（不得猜）：kind="
                    + sample.kind()
                    + " activity="
                    + sample.activity().value());
      }
      for (DebtPartyResolver.DebtAmount amount : split.amounts()) {
        DebtContract contract =
            DebtContractBook.upsert(
                debts,
                amount.debtor(),
                amount.creditor(),
                unit,
                terms,
                source,
                amount.amount(),
                day,
                OptionalLong.of(dueCycle));
        if (source == DebtSource.WAGE_ARREARS) {
          // ★ D3-I13：旧 wage 路径同日新资本化的工资债也不得在同一 tick 还款轮被再付。
          sameDayWageDebtIds.add(contract.id());
        }
        HouseholdEconomy debtorHouseholdEconomy = householdEconomies.get(amount.debtor());
        if (debtorHouseholdEconomy == null) {
          // 端点来自 DebtPartyResolver（同源于 data.classes），解析到却查不到行 = 状态已被改坏，fail-closed。
          throw new IllegalStateException(
              "资本化解出的债务人不在 rows 里（DebtPartyResolver 与 rows 漂开）: " + amount.debtor());
        }
        if (!householdEconomies.containsKey(amount.creditor())) {
          throw new IllegalStateException(
              "资本化解出的债权人不在 rows 里（DebtPartyResolver 与 rows 漂开）: " + amount.creditor());
        }
        // ★★ 2026-10-09 选项 A：改前这里把资本化新合同的派生引用补进债务人行；拆表后引用表由构造期对账
        //   按 debts 工作表整表重建 ⇒ 不必再补（端点存在性校验照旧保留在上面）。
        ledger.addDebtCapitalization(
            new ProductionLedger.DebtCapitalization(
                sample.payer(),
                sample.payee(),
                sample.activity(),
                sample.rule(),
                amount.debtor(),
                amount.creditor(),
                contract.id(),
                unit,
                amount.amount(),
                day,
                dueCycle,
                terms,
                CAPITALIZATION_TERMS_SOURCE,
                contract.principal(),
                countByEvent.get(key)));
        capitalizedByHousehold
            .computeIfAbsent(amount.debtor(), ignored -> new LinkedHashMap<>())
            .merge(unit.key(), amount.amount(), Math::addExact);
      }
    }
  }

  /** ★★ P2：未解析端点的具名 reason（payer / payee 各自带解析器内部原因，便于逐跳诊断）。 */
  static String unresolvedDebtCapitalizationReason(
      DebtPartyResolver.Resolution debtors, DebtPartyResolver.Resolution creditors) {
    if (!debtors.isResolved() && !creditors.isResolved()) {
      return "payer-not-resolvable:"
          + debtors.reason()
          + ";payee-not-resolvable:"
          + creditors.reason();
    }
    if (!debtors.isResolved()) {
      return "payer-not-resolvable:" + debtors.reason();
    }
    return "payee-not-resolvable:" + creditors.reason();
  }

  /**
   * ★★ P2c：把 {@link ProductionLedger.Arrear} 解释成普通货币债的计量口径。
   *
   * <ul>
   *   <li>{@code currency} 有值 ⇒ 直接 {@link DebtUnit.Money}(currency)；
   *   <li>{@code currency} 空、{@code commodity} 有值 ⇒ 用该 arrear 的 activity 所在格 → 市场区 → 该区 {@code
   *       MarketTable}；取 {@code numeraire} 与 {@code priceOf(commodity)}。无 activity/无市场/无价/非正价 ⇒
   *       具名失败（{@code commodity-price-unavailable:…}），由调用方写 unresolved，<b>不 0 折、不 1:1</b>。
   * </ul>
   */
  private static MoneyResolution resolveMoney(
      EconomyDayView data,
      SettlementIndex index,
      MarketTopology topology,
      ProductionLedger.Arrear arrear) {
    if (arrear.currency().isPresent()) {
      return new MoneyResolution(new DebtUnit.Money(arrear.currency().orElseThrow()), 0L, null);
    }
    if (arrear.commodity().isEmpty()) {
      throw new IllegalStateException("Arrear 既没有 currency 也没有 commodity（构造期应已判死）: " + arrear);
    }
    CommodityId commodity = arrear.commodity().orElseThrow();
    String activity = arrear.activity().value();
    String hexKey = index.hexOf(arrear.activity());
    if (hexKey == null || hexKey.isBlank()) {
      return MoneyResolution.unresolved(
          "commodity-price-unavailable:no-activity-hex:activity="
              + activity
              + ";commodity="
              + commodity.value());
    }
    HexCoord hex;
    try {
      hex = HexCoord.parse(hexKey);
    } catch (IllegalArgumentException invalidHex) {
      return MoneyResolution.unresolved(
          "commodity-price-unavailable:invalid-activity-hex:activity="
              + activity
              + ";hex="
              + hexKey
              + ";commodity="
              + commodity.value());
    }
    Map<MarketZoneId, MarketTable> marketTables = data.marketTables();
    MarketTable market = marketTables == null ? null : marketForHex(hex, marketTables, topology);
    if (market == null) {
      return MoneyResolution.unresolved(
          "commodity-price-unavailable:no-market:activity="
              + activity
              + ";hex="
              + hexKey
              + ";commodity="
              + commodity.value());
    }
    if (!market.hasPrice(commodity)) {
      return MoneyResolution.unresolved(
          "commodity-price-unavailable:no-price:activity="
              + activity
              + ";hex="
              + hexKey
              + ";market="
              + market.numeraire().value()
              + ";commodity="
              + commodity.value());
    }
    long price = market.priceOf(commodity);
    if (price <= 0L) {
      return MoneyResolution.unresolved(
          "commodity-price-unavailable:non-positive-price:activity="
              + activity
              + ";hex="
              + hexKey
              + ";market="
              + market.numeraire().value()
              + ";commodity="
              + commodity.value()
              + ";price="
              + price);
    }
    return new MoneyResolution(new DebtUnit.Money(market.numeraire()), price, null);
  }

  /** 商品欠款金额（毫商品）× 单价（毫 numeraire/商品单位）÷ 1000，floor；至少 1 毫货币。 */
  private static long convertCommodityArrear(long owed, MoneyResolution resolution) {
    long converted =
        SaturatedArithmetic.floorMultiplyDivide(
            owed,
            resolution.pricePerUnitMilli(),
            1000L,
            DEBT,
            EconomyLogSource.ECONOMY_DEBT,
            "commodity-arrear-price-conversion",
            "unit",
            resolution.unit().key());
    return Math.max(1L, converted);
  }

  /** 折价结果：成功时 unit 非空；失败时 failureReason 以 {@code commodity-price-unavailable:} 开头。 */
  private record MoneyResolution(
      DebtUnit.Money unit, long pricePerUnitMilli, String failureReason) {

    boolean resolved() {
      return unit != null;
    }

    static MoneyResolution unresolved(String reason) {
      return new MoneyResolution(null, 0L, reason);
    }
  }

  /**
   * ★★ **周期末计息**（v2 spec §7.1 第三处 + §四 周期结算第 6 步；V6 落地）：
   *
   * <pre>
   * interest = principal × ratePerMillePerCycle ÷ 1000      // 向下取整；0 ⇒ 这一条本轮不计
   * principal' = principal + interest                       // ★ **并入本金**（复利；V1 的 dueCycle 语义不变）
   * 债务人本行流水.interestDue += interest                   // 同一笔数，流量口径
   * </pre>
   *
   * <p>★★ **一处 spec 内部张力，这里的取舍是明确的**：v1 spec §3.3 末条说「绝不用'生产成本'或'资产减少'冒充负债—— **债务只能由 借入/赊购 产生**」，而
   * §四 周期结算第 6 步允许"计息（写新应付款或**并入本金**）"。本批取 §四： 计息**并入本金**。理由：§3.3
   * 那条防的是"**凭空**造负债"（拿成本/折旧冒充欠款），而利息是**合同约定的真实义务** —— 它挂在一条**已经由借入产生**的债务上，不是"第二个来源"。若不计息，{@code
   * ratePerMillePerCycle = 20} 就只是一个写进去、谁也不读的数（账面记成"债务"、语义却是**无限量无偿赈济**）。
   *
   * <p>★★ **它不搬运任何粮、不动任何库存**（纯数学）⇒ §6.1 的守恒式不受影响；债务人的"更穷"体现在 ①本金变大、②本行流水的 {@code interestDue}（以及
   * {@code netSurplus}：见 {@code settleOneDayInto} 里那条注释）。 **债权人这一侧本批不记**：它的资产增值体现在债务表里，而 {@code
   * FlowRow.income} 是**粮食**口径 （往里加"利息收入"会让守恒式当场不成立）⇒ 记在债权侧要等 §五 的索取源协议/市场折算（V7+）。
   *
   * <p>★ **偿还先于计息**（R3 续修）：关账日的日序是 借粮 → 偿还 → 报缺口 → 计息（见 {@link #repayDebts}）， 这里只负责把利息并入本金； {@link
   * DebtContract#dueCycle()} 由借款路径滚动写入（读口与退出处置的排序会读它），{@link DebtContract#lastInterestDay()}
   * 在本步真的并入利息时写； E4a 只接线 {@link InterestTiming#AFTER_REPAYMENT_ON_CLOSE}，其它计息时点 fail-closed 具名抛（E4b
   * 接线）。
   *
   * <p>★★ **计息本金取"当日起始"快照，不取当日 lend/repay 之后的本金**（M0.5 守恒式的权威口径：计息日 {@code + ⌊昨 × 率 ÷ 1000⌋}，"昨" =
   * 当日开始时的本金）：当天新借的债当天不计息；当天还掉的那部分本金 **今天照样计息**（"先还后计"的偿还次序不变，只是计息基数不跟着偿还缩水）。计息仍并入**当前**本金 ⇒ 与守恒式
   * {@code 本金_今 = 昨 + 放出 − 偿还 + ⌊昨 × 率 ÷ 1000⌋} 逐值一致。★ 快照由 {@code settleOneDayInto} 在任何 lend/repay
   * 之前建好传入。
   *
   * <p>★★ <b>W8（2026-10-11）：户已绝（债务人行人口 ≤ 0）⇒ 不计息</b>（见 body 里的 {@code
   * DEBT_INTEREST_EXTINCT_HOUSEHOLD_SKIPPED}）。判据取社会侧权威（行人口），与"迁移不销账、死亡才销账"那条口径配套： 这里对**任何**成因为 0
   * 的空户都停息（②只处理死亡户绝的本金消除，③兜住迁移归零与历史空户）。
   *
   * @param debts 债务表（就地更新：本金并入利息）
   * @param principalAtDayStart 当日起始的逐债本金快照（当日新借的债不在快照里 ⇒ 今天不计息）
   * @param interest 本日利息的逐行累加器（**只记债务人**那一侧）
   * @param householdEconomies 经济家户行工作表（★ W8：只读它判"债务人户是否已绝"）
   * @param day 本结算日（真的并入利息时写进 {@code lastInterestDay}）
   */
  static void chargeInterest(
      LinkedHashMap<DebtContractId, DebtContract> debts,
      Map<DebtContractId, Long> principalAtDayStart,
      LinkedHashMap<HouseholdId, Long> interest,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      long day) {
    for (Map.Entry<DebtContractId, DebtContract> entry : new ArrayList<>(debts.entrySet())) {
      DebtContractId id = entry.getKey();
      Long startPrincipal = principalAtDayStart.get(id);
      if (startPrincipal == null || startPrincipal <= 0L) {
        continue; // 当日新借（不在快照里）或本金本来就是 0 ⇒ 今天不计息
      }
      DebtContract debt = entry.getValue();
      if (DebtContractBook.isSelfHeld(debt)) {
        // ★★★ W5-fix6（D5，2026-10-11）：**自持合同不计息**（同族漏网之一；还债轮与退出清算已在 W5-fix 排除）。
        //   它是"发行额 = 政府负债"的记账事实 ⇒ 未偿额必须恰好等于累计发行额；计息会让政府负债每周期凭空长出一笔
        //   **没有对应新钱**的利息（W5 验收报告 §3-D5 的真读数：500 → 1,624@d360，全是利息）。
        //   ★ 不静默：逐条 DEBUG 具名（谁 / 哪条合同 / 为什么不动它）。
        if (DEBT.isDebugEnabled()) {
          EventLog.channel(DEBT)
              .debug(
                  LogEvent.of(
                      "DEBT_INTEREST_SELF_HELD_SKIPPED",
                      EconomyLogSource.ECONOMY_DEBT_STATE,
                      "day",
                      day,
                      "debtContract",
                      id.value(),
                      "debtor",
                      debt.debtor().value(),
                      "unit",
                      debt.unit().key(),
                      "startPrincipal",
                      startPrincipal,
                      "wouldCharge",
                      startPrincipal * debt.terms().interestRatePerMillePerCycle() / 1000L,
                      "outstandingPrincipal",
                      debt.principal(),
                      "why",
                      "self-held-is-the-issuance-bookkeeping-fact-not-interest-bearing"));
        }
        continue;
      }
      if (debt.terms().interestTiming() != InterestTiming.AFTER_REPAYMENT_ON_CLOSE) {
        throw new IllegalStateException(
            "E4a 尚未接线非 legacy 计息时点：合同="
                + id
                + " timing="
                + debt.terms().interestTiming()
                + "（E4b 接线；此处 fail-closed，不静默当成默认档）");
      }
      // ★★★ W8（2026-10-11 用户裁定「穷人的话，死了账一并消除」）：**户已绝（人口 ≤ 0）⇒ 不计息**。
      //   判据取**社会侧权威**：经济家户行的 population 是 app 每日从 Social 展开后注入的当日物化视图
      //   （AGENTS §〇 的 social 权威条款），不是"债务表里有没有对应的 classes 行"这类间接形态判据。
      //   ★ 为什么必须在这里兜底（调查报告 D2）：活路径的户绝核销（applyHouseholdPopulationDeltasInto 的
      //     W8 双向销账）只在"**死亡**把人口打到 0"的那一天触发；而"人口 → 0"里绝大多数是**迁移**（sw 21 例里
      //     19 例），迁移不销账（用户口径）⇒ 那些空户名下的余额若不在这里跳过，就会继续无限复利
      //     （真读数：hh-2_-2-rural-landlord 户绝后 50 天 ×153.93）。
      //   ★ 与"先销账还是先跳过"的次序（W8 实现账本）：两者**不在同一相位** —— ③ 在日结算的周期末计息（step 内），
      //     ② 在 step **之后**的社会生死回写里；经济侧先关账、Social 后判死是 W3 两段式裁定。所以日序上 ③ 必然
      //     先于 ②，而 ③ **不依赖** ②：② 把本金删成 0 之后 ③ 对那条合同本就无事可做（上面的 startPrincipal ≤ 0 已 continue）。
      //     反过来 ③ 也不是 ② 的替代：③ 只"停止增长"，② 才"消除这笔账"。
      //   ★ 行缺席（拿不到该债务人的人口）⇒ **照旧计息**：说不出"已绝"就不许当作已绝（fail-closed 取"不改行为"那一侧）；
      //     非家户端点（政府/单位户）本来就没有经济家户行，行为与改前逐值相同。
      HouseholdEconomy debtorEconomy = householdEconomies.get(debt.debtor());
      if (debtorEconomy != null && debtorEconomy.population() <= 0L) {
        if (DEBT.isDebugEnabled()) {
          EventLog.channel(DEBT)
              .debug(
                  LogEvent.of(
                      "DEBT_INTEREST_EXTINCT_HOUSEHOLD_SKIPPED",
                      EconomyLogSource.ECONOMY_DEBT_STATE,
                      "day",
                      day,
                      "debtContract",
                      id.value(),
                      "debtor",
                      debt.debtor().value(),
                      "creditor",
                      debt.creditor().value(),
                      "unit",
                      debt.unit().key(),
                      "startPrincipal",
                      startPrincipal,
                      "outstandingPrincipal",
                      debt.principal(),
                      "debtorPopulation",
                      debtorEconomy.population(),
                      "why",
                      "w8:household-extinct-population<=0-no-interest (social-side-authority)"));
        }
        continue;
      }
      long charged = startPrincipal * debt.terms().interestRatePerMillePerCycle() / 1000L;
      if (charged <= 0L) {
        continue; // 本金小到算不出 1 毫粮（或利率 0）⇒ 本轮不记：不写"看起来在记、其实永远是 0"的流水
      }
      // ★★ E4c：利息并入本金也走唯一写口（唯一额外语义：写 lastInterestDay）。
      DebtContractBook.compoundInterest(debts, id, charged, day);
      interest.merge(debt.debtor(), charged, Long::sum);
    }
  }
}
