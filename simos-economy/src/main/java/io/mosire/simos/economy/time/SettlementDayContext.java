package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyDayView;
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
import io.mosire.simos.economy.time.EconomySettlement.ClosedUnit;
import io.mosire.simos.economy.time.EconomySettlement.MoneyIssuanceJournal;
import io.mosire.simos.economy.time.ProductionRunSettlement.HarvestWork;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
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
  Map<String, Long> laborByUnit;
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
  Set<HexCoord> haulServiceMarketHexes;
  MerchantCapacityPool carrierPool;
  MarketTrigger marketTrigger;
  Map<MarketMandateId, Long> govMandateFills;

  /** ★★★ P3c3：M3 第一遍预期生产编排的瞬态结果（只活一个 tick；不落盘）。 */
  ExpectedProductionPlan expectedProductionPlan;

  /** ★★★ P3c3：M6 第二遍实际生产编排的瞬态结果（只活一个 tick；不落盘）。 */
  ActualProductionPlan actualProductionPlan;

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
}
