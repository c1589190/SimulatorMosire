package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.api.classes.OwnershipClass;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.land.LandHoldingKey;
import io.mosire.simos.economy.api.market.MarketMandateId;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.TransportRoundPlan;
import io.mosire.simos.economy.time.EconomySettlement.ClosedUnit;
import io.mosire.simos.economy.time.EconomySettlement.MoneyIssuanceJournal;
import io.mosire.simos.economy.time.ProductionRunSettlement.HarvestWork;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionOpportunityBook;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;

/** 单日结算的瞬态上下文：只活一次 {@code settleOneDayInto} 调用，不落盘、不进 ChangeSet。 */
final class SettlementDayContext {

  final EconomySession session;
  final long day;
  final AccountSession accounts;
  final MarketTopology topology;
  final boolean plantingDrawsFirst;
  final ProductionLedger.Accumulator ledger;
  final EconomyParallelism parallelism;
  final EnterpriseProfitBook.CycleAccumulator profitCycle;
  final Map<HouseholdId, Map<PeopleLotId, Long>> composition;
  final Set<HouseholdId> marketExcludedHouseholds;
  final PortEnforcementInput portEnforcement;
  final PortTaxInput portTax;
  final ProcurementPriorityInput procurementPriority;
  final SurvivalSocialFacts survivalSocialFacts;
  EconomyData base;
  EconomyDayView dayView;
  Set<HouseholdId> effectiveMarketExcludedHouseholds;
  LinkedHashMap<HouseholdId, FlowRow> flows;
  Map<HouseholdId, Map<CommodityId, Long>> householdGoods;
  Map<HouseholdId, Map<CurrencyId, Long>> householdMoney;
  Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods;
  Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney;
  EconomyMeta meta;
  long currentCycle;
  long dueCycle;
  MoneyIssuanceJournal issuanceJournal;
  LinkedHashMap<IndustryId, IndustryDefinitionView> industries;
  Map<ProductionUnitId, ProductionProcess> units;
  LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies;
  LinkedHashMap<DebtContractId, DebtContract> debts;
  Map<DebtContractId, Long> principalAtDayStart;
  LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments;
  LinkedHashMap<AssetShareId, OwnershipStake> assetShares;
  LinkedHashMap<ProductionUnitId, OperatorCondition> operatorConditions;
  LinkedHashMap<MarketZoneId, MarketTable> markets;
  Map<ProductionUnitId, ProductionRules> relations;
  SettlementIndex settlementIndex;
  long dayStartPopulation;
  Set<ProductionUnitId> enteredToday;
  List<EntryOutcome> entryOutcomes;
  Map<IndustryId, IndustryDefinitionView> planPool;
  Map<IndustryId, io.mosire.simos.actor.api.actor.ActorRef> planAssetSources;
  Map<IndustryId, io.mosire.simos.economy.api.relation.LaborSource> planLaborSources;
  LinkedHashMap<HouseholdId, Map<CommodityId, Long>> consumedGoods;
  LinkedHashMap<HouseholdId, Map<CommodityId, Long>> income;
  LinkedHashMap<HouseholdId, Map<CommodityId, Long>> unmetToday;
  LinkedHashMap<HouseholdId, Long> birthsToday;
  LinkedHashMap<HouseholdId, Long> interestToday;
  LinkedHashMap<HouseholdId, Long> repaidToday;
  LinkedHashMap<HouseholdId, Map<CurrencyId, Long>> repaidMoneyToday;
  LinkedHashMap<DebtContractId, Long> repaidPrincipalByDebt;
  LinkedHashMap<HouseholdId, Map<String, Long>> capitalizedArrearsToday;
  LinkedHashMap<HouseholdId, Long> deficitToday;
  boolean dueModeTransition;
  EconomyEnterpriseSettlement.Outcome enterpriseOutcome;
  Map<HouseholdId, Set<ProductionUnitId>> unitsOfHousehold;
  Map<ActorRef, HouseholdId> householdOfActor;
  LinkedHashMap<ShipmentId, ShipmentBatch> shipments;
  int shipmentsBefore;
  MarketArbitragePlan.Collector arbitrageCollector;
  Map<HouseholdId, Long> cycleDaysByHousehold;
  io.mosire.simos.production.contract.ProductionDayResult productionDay;
  boolean anyCycleClosed;
  Set<ProductionUnitId> newCycleUnits;
  List<ProductionProcess> haulExpiryUnits;
  long haulServiceProducedMilli;
  long haulServiceExpiredMilli;
  Map<String, List<IndustryId>> hexToIndustries;
  Set<HouseholdId> newCycleHouseholds;
  List<ClosedUnit> closed;
  List<EnterpriseProfitBook.CloseFact> closedFacts;
  List<HarvestWork> harvestWorks;
  int harvestOrder;
  Map<ProductionUnitId, Long> closedUnitScales;
  Map<IndustryId, Map<CommodityId, Long>> outputQuantityOverrides;
  Map<ProductionUnitId, ProductionEnterprise> enterpriseByProcess;
  Map<CohortKey, HouseholdId> viewIndex;
  AccountSnapshot wageAccountSnapshot;
  Map<ProductionUnitId, ProductionProcess> wageSettlementUnits;
  WageSettlement.Result wageSettlement;
  Set<DebtContractId> sameDayWageDebtIds;
  ProductionLedger capitalized;
  MarketSettlement.MarketRound marketRound;

  /**
   * ★★★ <b>D-032 C7b1：本市场轮运输需求/lane 承诺/本地观察计划（瞬态）。</b>
   *
   * <p>只活在本 {@link SettlementDayContext}：不落 {@code EconomyData}、不进 {@code ChangeSet}/Codec。 C7b2 商品
   * matcher 只读本字段的 {@code demands/commitments/observationsByShippingHex/demandSupplyByZone}，
   * 不得重新派生需求或复制 lane 分配算法。
   */
  TransportRoundPlan transportRoundPlan;

  Set<HexCoord> haulServiceMarketHexes;
  MerchantCapacityPool carrierPool;
  MarketTrigger marketTrigger;
  Map<MarketMandateId, Long> govMandateFills;

  /** ★★★ D-032 C6：S5 服务产出交给 economy 批次的 sink（由 driveProduction 注入；瞬态，不落盘）。 */
  TransportCapacitySink transportCapacitySink;

  /** ★★★ P3c3：M3 第一遍预期生产编排的瞬态结果（只活一个 tick；不落盘）。 */
  ExpectedProductionPlan expectedProductionPlan;

  /** ★★★ P3c3：M6 第二遍实际生产编排的瞬态结果（只活一个 tick；不落盘）。 */
  ActualProductionPlan actualProductionPlan;

  /**
   * ★★★ <b>D-032 L1c：本 tick 的只读生产机会簿</b>（瞬态；缺省 = 空簿）。
   *
   * <p>由 S3 之前的 {@code EconomyDayPipeline} 从 {@code session.inventoryLotView()} + {@code
   * ctx.industries} + 市场价函数纯派生；只赋值、只读，不进 {@code EconomyData}、变更集或 Codec。L1c 暂不消费（不改 S3/S4/S5 输入输出）。
   */
  ProductionOpportunityBook productionOpportunityBook = new ProductionOpportunityBook(List.of());

  /**
   * ★★★ <b>RC8c：M6 实际编排选中的位置事实（瞬态；不落盘、不进 ChangeSet/Codec）。</b>
   *
   * <p>账单的 {@code PRODUCTION_INPUT} 行取 {@link
   * ProductionPlanBridge.PositionFacts#inputRequired()}（probe 的当前 本周期投入需求），{@code
   * PRODUCTION_DAILY_EXTRA} 行取同一 probe 的 {@code baseScale} × 产业 {@code dailyInputPerUnit}；S7 的 LAND
   * 意向也读这份实际位置事实（{@link MarketLandIntentBook} 的标量语义不变）。
   */
  List<ProductionPlanBridge.PositionFacts> actualPositionFacts = List.of();

  /**
   * ★★★ <b>RC8c：每户每 tick 唯一 {@link DailyDemandBill}（瞬态；不落盘、不进 ChangeSet/Codec）。</b>
   *
   * <p>键 = 家户稳定身份；同一 tick 同一户只能有一张。S4 写 {@code providedFromStock}，S7 在同一对象上写 {@code
   * marketPurchaseNeed}/{@code purchasedFromMarket}/{@code unmet}。
   */
  final LinkedHashMap<HouseholdId, DailyDemandBill> dailyDemandBills = new LinkedHashMap<>();

  /** RC8c：取（必要时创建）本户本 tick 的唯一账单；重复调用返回同一对象。 */
  DailyDemandBill dailyDemandBill(HouseholdId household) {
    if (household == null) {
      throw new IllegalArgumentException("dailyDemandBill 的家户不得为 null");
    }
    return dailyDemandBills.computeIfAbsent(household, key -> DailyDemandBill.create(day, key));
  }

  /** ★★★ D-032 L1c：本 tick 的只读生产机会簿（缺省空簿；L1c 暂不消费）。 */
  ProductionOpportunityBook productionOpportunityBook() {
    return productionOpportunityBook;
  }

  /** ★★★ P3.0：prepare 阶段算好的纯商号集合；execute 只消费，不再现算。 */
  Set<HouseholdId> preparedPureMerchantHouseholds;

  /** ★★★ P3.0：prepare 阶段构造的开盘前结算序快照；execute 只把它交给唯一撮合入口。 */
  SettlementOrderBook preparedSettlementOrdering;

  Map<HouseholdId, MarketTable> marketByHousehold;
  DebtCapacityBook.DebtUnitValueLookup debtUnitValueLookup;
  Set<ProductionUnitId> closingUnits;
  OptionalLong lastClosed;
  EconomyMeta nextMeta;
  List<MoneyIssuanceRecord> issuedToday;
  long dayEndPopulation;
  MarketReport dayMarketReport;

  /**
   * ★★ <b>P2e：本 step 内“新取得土地”的瞬态集合</b>——M2 抵债取得写这里；P2f 市场买地也写同一集合。
   *
   * <p>★ 只活在 {@link SettlementDayContext}（每 step new、非持久）；<b>不落盘、不进 ChangeSet/Codec/快照</b>， 不得用持久
   * {@code createdDay} 替代。P3 的 M3/M6/M7 消费它来排除本轮新土地。
   */
  final LinkedHashSet<AssetShareId> landAcquiredThisRound = new LinkedHashSet<>();

  /**
   * ★★★ <b>D-032 C1：S7-M2 完成后、S7-A 市场交易开始前的 ownershipClass 快照</b>（瞬态；不落盘、不进 ChangeSet/Codec）。
   *
   * <p>它只服务 S7-B “本轮市场买入是否算 PROLETARIAN 买方”的判定：S7-M2 working copy 内的债偿取得资产必须在快照里 已经体现为 OWNER，不能回落到
   * S7 前旧 class。S7-M2 内部自己的 lookup 必须读同一份活工作副本，不得读本快照。
   */
  Map<HouseholdId, OwnershipClass> ownershipClassAtMarketTradeStart = Map.of();

  /**
   * ★★ D-032 C8：与 {@link #ownershipClassAtMarketTradeStart} 同一时点拍下的买方 homeHex 快照（瞬态；不落盘、 不进
   * ChangeSet/Codec）。
   *
   * <p>马匹向上跃迁的 target hex 只能读本表；不得取市场成交格、卖方格或 LAND 目标 hex。
   */
  Map<HouseholdId, HexCoord> homeHexAtMarketTradeStart = Map.of();

  SettlementDayContext(
      EconomySession session,
      long day,
      AccountSession accounts,
      MarketTopology topology,
      boolean plantingDrawsFirst,
      ProductionLedger.Accumulator ledger,
      EconomyParallelism parallelism,
      EnterpriseProfitBook.CycleAccumulator profitCycle,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      Set<HouseholdId> marketExcludedHouseholds,
      PortEnforcementInput portEnforcement,
      PortTaxInput portTax,
      ProcurementPriorityInput procurementPriority,
      SurvivalSocialFacts survivalSocialFacts) {
    this.session = session;
    this.day = day;
    this.accounts = accounts;
    this.topology = topology;
    this.plantingDrawsFirst = plantingDrawsFirst;
    this.ledger = ledger;
    this.parallelism = parallelism;
    this.profitCycle = profitCycle;
    this.composition = composition;
    this.marketExcludedHouseholds = marketExcludedHouseholds;
    this.portEnforcement = portEnforcement;
    this.portTax = portTax;
    this.procurementPriority = procurementPriority;
    this.survivalSocialFacts = survivalSocialFacts;
  }

  /** ★ P2e/P2f：登记一块本轮新取得的土地（M2 抵债 / M4 买入）。id 重复时保持首次出现序。 */
  void markLandAcquired(AssetShareId landShare) {
    if (landShare == null) {
      throw new IllegalArgumentException("landAcquiredThisRound 不得登记 null 土地块");
    }
    landAcquiredThisRound.add(landShare);
  }

  /** ★ P2e/P2f：本轮新取得土地块的只读视图（P3 的 M3/M6/M7 消费；不落盘）。 */
  Set<AssetShareId> landAcquiredThisRound() {
    return Collections.unmodifiableSet(landAcquiredThisRound);
  }

  /**
   * ★★★ <b>D-032 RA1b2a：本轮按 {@code (hex, owner)} 实际取得的土地数量</b>（瞬态；不落盘、不进 ChangeSet/Codec）。
   *
   * <p>★ 与旧的 {@link #landAcquiredThisRound()} 并存：旧集合按整块 {@link AssetShareId} 排除，新表保存<b>数量</b>，
   * 让"原持有 100、本轮取得 30 ⇒ 可用 70"这类<b>部分取得</b>可被后续 S3 消费；本单暂不驱动任何业务。
   */
  final LinkedHashMap<LandHoldingKey, Long> landAcquiredQuantityThisRound = new LinkedHashMap<>();

  /** ★★ 登记一笔本轮取得的标量土地数量；同 key 累加。数量必须严格为正（0/负数具名拒绝），溢出同样拒绝。 */
  void markLandAcquired(LandHoldingKey key, long quantityMilliMu) {
    if (key == null) {
      throw new IllegalArgumentException("landAcquiredQuantityThisRound 不得登记 null key");
    }
    if (quantityMilliMu <= 0L) {
      throw new IllegalArgumentException(
          "landAcquiredQuantityThisRound 的数量必须 > 0: key=" + key + " -> " + quantityMilliMu);
    }
    long previous = landAcquiredQuantityThisRound.getOrDefault(key, 0L);
    try {
      landAcquiredQuantityThisRound.put(key, Math.addExact(previous, quantityMilliMu));
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException(
          "landAcquiredQuantityThisRound 累加溢出: key="
              + key
              + " "
              + previous
              + " + "
              + quantityMilliMu,
          overflow);
    }
  }

  /** ★★ 本轮按 key 取得的土地数量只读视图（稳定插入序；本单不落盘、不驱动业务）。 */
  Map<LandHoldingKey, Long> landAcquiredQuantityThisRound() {
    return Collections.unmodifiableMap(landAcquiredQuantityThisRound);
  }
}
