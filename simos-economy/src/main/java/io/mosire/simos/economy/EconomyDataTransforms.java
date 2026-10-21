package io.mosire.simos.economy;

import io.mosire.simos.economy.api.id.AssetRuleId;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ClassShareId;
import io.mosire.simos.economy.api.id.ClassStructureId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CrisisSignalId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.InstrumentId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ModeTransitionId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.market.GovernmentMarketMandate;
import io.mosire.simos.economy.api.market.MarketMandateId;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.api.money.CurrencyDef;
import io.mosire.simos.economy.api.money.MoneyInstrument;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.api.stock.HouseholdPeriodicAdjustment;
import io.mosire.simos.economy.api.stock.PeriodicHouseholdAdjustmentId;
import io.mosire.simos.economy.model.AssetOccupancyGrant;
import io.mosire.simos.economy.model.AssetOccupancyGrantId;
import io.mosire.simos.economy.model.AssetRule;
import io.mosire.simos.economy.model.ClassShare;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdDebtReference;
import io.mosire.simos.economy.model.HouseholdDemand;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.HouseholdFlowTable;
import io.mosire.simos.economy.model.InventoryReservation;
import io.mosire.simos.economy.model.InventoryReservationId;
import io.mosire.simos.economy.model.MarketRoundStats;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.ModeTransition;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.model.ProductionWageAnchor;
import io.mosire.simos.economy.model.TransportCapacityBatch;
import io.mosire.simos.economy.model.TransportCapacityBatchKey;
import io.mosire.simos.economy.model.TransportPriceAnchor;
import io.mosire.simos.economy.model.WageAnchorKey;
import io.mosire.simos.economy.model.WageCarryKey;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** EconomyData 逐组件 with* 写口的机械委托实现（行为不变）。 */
final class EconomyDataTransforms {
  private EconomyDataTransforms() {}

  private static final class Builder {
    Optional<EconomyMeta> meta;

    Map<HouseholdId, HouseholdEconomy> classes;

    Map<DebtContractId, DebtContract> debtContracts;

    Map<HouseholdId, FlowRow> flows;

    Map<LaborAllocationId, HouseholdLaborCommitment> allocations;

    Map<ProductionUnitId, ProductionRules> relations;

    Map<MarketZoneId, MarketTable> marketTables;

    Map<ShipmentId, ShipmentBatch> shipments;

    Map<AssetShareId, OwnershipStake> assetShares;

    Map<ProductionUnitId, OperatorCondition> operatorConditions;

    Map<DemandId, HouseholdDemand> demands;

    Map<ClassStructureId, ClassStructure> classStructures;

    Map<ClassPositionId, ProductionRole> classPositions;

    Map<HouseholdId, HouseholdClassMembership> classStandings;

    Map<ProductionOrganizationId, ProductionEnterprise> productionOrganizations;

    Map<AssetRuleId, AssetRule> assetRules;

    Map<GovernmentId, Government> governments;

    Map<MoneyIssuanceId, MoneyIssuanceRecord> moneyIssuances;

    Map<CrisisSignalId, HexCrisisSignal> crisisSignals;

    Map<ModeTransitionId, ModeTransition> modeTransitions;

    Map<ClassShareId, ClassShare> classShares;

    Map<PeriodicHouseholdAdjustmentId, HouseholdPeriodicAdjustment> periodicAdjustments;

    Map<CurrencyId, CurrencyDef> currencies;

    Map<InstrumentId, MoneyInstrument> moneyInstruments;

    Map<MarketZoneId, MarketZone> marketZones;

    Map<HouseholdDebtReference, Boolean> householdDebtRefs;

    Map<CommodityId, Long> commodityFreightBaseMilli;

    Map<MarketMandateId, GovernmentMarketMandate> govMarketMandates;

    Map<MarketZoneId, MarketRoundStats> marketRoundStats;

    Map<ProductionUnitId, ProductionRoster> productionRosters;

    Map<WageCarryKey, Long> wageCarryRemainders;

    Map<AssetOccupancyGrantId, AssetOccupancyGrant> assetOccupancies;

    Map<InventoryReservationId, InventoryReservation> inventoryReservations;

    Map<WageAnchorKey, ProductionWageAnchor> productionWageAnchors;

    Map<HouseholdId, HouseholdFlowTable> householdFlowTables;

    Map<TransportCapacityBatchKey, TransportCapacityBatch> transportCapacityBatches;

    Map<MarketZoneId, TransportPriceAnchor> transportPriceAnchors;

    Builder(EconomyData data) {
      this.meta = data.meta();

      this.classes = data.classes();

      this.debtContracts = data.debtContracts();

      this.flows = data.flows();

      this.allocations = data.allocations();

      this.relations = data.relations();

      this.marketTables = data.marketTables();

      this.shipments = data.shipments();

      this.assetShares = data.assetShares();

      this.operatorConditions = data.operatorConditions();

      this.demands = data.demands();

      this.classStructures = data.classStructures();

      this.classPositions = data.classPositions();

      this.classStandings = data.classStandings();

      this.productionOrganizations = data.productionOrganizations();

      this.assetRules = data.assetRules();

      this.governments = data.governments();

      this.moneyIssuances = data.moneyIssuances();

      this.crisisSignals = data.crisisSignals();

      this.modeTransitions = data.modeTransitions();

      this.classShares = data.classShares();

      this.periodicAdjustments = data.periodicAdjustments();

      this.currencies = data.currencies();

      this.moneyInstruments = data.moneyInstruments();

      this.marketZones = data.marketZones();

      this.householdDebtRefs = data.householdDebtRefs();

      this.commodityFreightBaseMilli = data.commodityFreightBaseMilli();

      this.govMarketMandates = data.govMarketMandates();

      this.marketRoundStats = data.marketRoundStats();

      this.productionRosters = data.productionRosters();

      this.wageCarryRemainders = data.wageCarryRemainders();

      this.assetOccupancies = data.assetOccupancies();

      this.inventoryReservations = data.inventoryReservations();

      this.productionWageAnchors = data.productionWageAnchors();

      this.householdFlowTables = data.householdFlowTables();

      this.transportCapacityBatches = data.transportCapacityBatches();

      this.transportPriceAnchors = data.transportPriceAnchors();
    }

    EconomyData build() {
      return new EconomyData(
          meta,
          classes,
          debtContracts,
          flows,
          allocations,
          relations,
          marketTables,
          shipments,
          assetShares,
          operatorConditions,
          demands,
          classStructures,
          classPositions,
          classStandings,
          productionOrganizations,
          assetRules,
          governments,
          moneyIssuances,
          crisisSignals,
          modeTransitions,
          classShares,
          periodicAdjustments,
          currencies,
          moneyInstruments,
          marketZones,
          householdDebtRefs,
          commodityFreightBaseMilli,
          govMarketMandates,
          marketRoundStats,
          productionRosters,
          wageCarryRemainders,
          assetOccupancies,
          inventoryReservations,
          productionWageAnchors,
          householdFlowTables,
          transportCapacityBatches,
          transportPriceAnchors);
    }
  }

  static EconomyData withMeta(EconomyData data, Optional<EconomyMeta> value) {
    Builder builder = new Builder(data);

    builder.meta = value;

    return builder.build();
  }

  static EconomyData withHouseholdEconomies(
      EconomyData data, Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    Builder builder = new Builder(data);

    builder.classes = householdEconomies;

    return builder.build();
  }

  static EconomyData withDebtContracts(EconomyData data, Map<DebtContractId, DebtContract> value) {
    Builder builder = new Builder(data);

    builder.debtContracts = value;

    return builder.build();
  }

  static EconomyData withFlows(EconomyData data, Map<HouseholdId, FlowRow> value) {
    Builder builder = new Builder(data);

    builder.flows = value;

    return builder.build();
  }

  static EconomyData withLaborCommitments(
      EconomyData data, Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments) {
    Builder builder = new Builder(data);

    builder.allocations = laborCommitments;

    return builder.build();
  }

  static EconomyData withRelations(EconomyData data, Map<ProductionUnitId, ProductionRules> value) {
    Builder builder = new Builder(data);

    builder.relations = value;

    return builder.build();
  }

  static EconomyData withMarketRoundStats(
      EconomyData data, Map<MarketZoneId, MarketRoundStats> value) {
    Builder builder = new Builder(data);

    builder.marketRoundStats = value;

    return builder.build();
  }

  static EconomyData withMarketTables(EconomyData data, Map<MarketZoneId, MarketTable> value) {
    Builder builder = new Builder(data);

    builder.marketTables = value;

    return builder.build();
  }

  static EconomyData withShipments(EconomyData data, Map<ShipmentId, ShipmentBatch> value) {
    Builder builder = new Builder(data);

    builder.shipments = value;

    return builder.build();
  }

  static EconomyData withOwnershipStakes(
      EconomyData data, Map<AssetShareId, OwnershipStake> value) {
    Builder builder = new Builder(data);

    builder.assetShares = value;

    return builder.build();
  }

  static EconomyData withOperatorConditions(
      EconomyData data, Map<ProductionUnitId, OperatorCondition> value) {
    Builder builder = new Builder(data);

    builder.operatorConditions = value;

    return builder.build();
  }

  static EconomyData withHouseholdDemands(
      EconomyData data, Map<DemandId, HouseholdDemand> householdDemands) {
    Builder builder = new Builder(data);

    builder.demands = householdDemands;

    return builder.build();
  }

  static EconomyData withClassStructures(
      EconomyData data, Map<ClassStructureId, ClassStructure> value) {
    Builder builder = new Builder(data);

    builder.classStructures = value;

    return builder.build();
  }

  static EconomyData withProductionRoles(
      EconomyData data, Map<ClassPositionId, ProductionRole> value) {
    Builder builder = new Builder(data);

    builder.classPositions = value;

    return builder.build();
  }

  static EconomyData withClassStructuresAndPositions(
      EconomyData data,
      Map<ClassStructureId, ClassStructure> structures,
      Map<ClassPositionId, ProductionRole> positions) {
    Builder builder = new Builder(data);

    builder.classStructures = structures;

    builder.classPositions = positions;

    return builder.build();
  }

  static EconomyData withClassMemberships(
      EconomyData data, Map<HouseholdId, HouseholdClassMembership> classMemberships) {
    Builder builder = new Builder(data);

    builder.classStandings = classMemberships;

    return builder.build();
  }

  static EconomyData withProductionEnterprises(
      EconomyData data, Map<ProductionOrganizationId, ProductionEnterprise> value) {
    Builder builder = new Builder(data);

    builder.productionOrganizations = value;

    return builder.build();
  }

  static EconomyData withAssetRules(EconomyData data, Map<AssetRuleId, AssetRule> value) {
    Builder builder = new Builder(data);

    builder.assetRules = value;

    return builder.build();
  }

  static EconomyData withGovernments(EconomyData data, Map<GovernmentId, Government> value) {
    Builder builder = new Builder(data);

    builder.governments = value;

    return builder.build();
  }

  static EconomyData withMoneyIssuances(
      EconomyData data, Map<MoneyIssuanceId, MoneyIssuanceRecord> value) {
    Builder builder = new Builder(data);

    builder.moneyIssuances = value;

    return builder.build();
  }

  static EconomyData withCrisisSignals(
      EconomyData data, Map<CrisisSignalId, HexCrisisSignal> value) {
    Builder builder = new Builder(data);

    builder.crisisSignals = value;

    return builder.build();
  }

  static EconomyData withModeTransitions(
      EconomyData data, Map<ModeTransitionId, ModeTransition> value) {
    Builder builder = new Builder(data);

    builder.modeTransitions = value;

    return builder.build();
  }

  static EconomyData withClassShares(EconomyData data, Map<ClassShareId, ClassShare> value) {
    Builder builder = new Builder(data);

    builder.classShares = value;

    return builder.build();
  }

  static EconomyData withPeriodicAdjustments(
      EconomyData data, Map<PeriodicHouseholdAdjustmentId, HouseholdPeriodicAdjustment> value) {
    Builder builder = new Builder(data);

    builder.periodicAdjustments = value;

    return builder.build();
  }

  static EconomyData withCurrencies(EconomyData data, Map<CurrencyId, CurrencyDef> value) {
    Builder builder = new Builder(data);

    builder.currencies = value;

    return builder.build();
  }

  static EconomyData withMoneyInstruments(
      EconomyData data, Map<InstrumentId, MoneyInstrument> value) {
    Builder builder = new Builder(data);

    builder.moneyInstruments = value;

    return builder.build();
  }

  static EconomyData withMarketZones(EconomyData data, Map<MarketZoneId, MarketZone> value) {
    Builder builder = new Builder(data);

    builder.marketZones = value;

    return builder.build();
  }

  static EconomyData withMarketZonesAndTables(
      EconomyData data,
      Map<MarketZoneId, MarketZone> zones,
      Map<MarketZoneId, MarketTable> tables) {
    Builder builder = new Builder(data);

    builder.marketZones = zones;

    builder.marketTables = tables;

    return builder.build();
  }

  /**
   * ★★★ D-032 P4fix2：<b>区表 + 价表 + 工资锚表三组件同一次候选态构造</b>。
   *
   * <p>{@code economy.DefineMarketZone} 把来源派生市场表的格摘走、摘空即删表，同时把该来源表下属于这些格 （或来源表被整张摘空时该表下全部）的工资锚从
   * {@code sourceId} 重键到新持久区 {@code zoneId}。三件事必须原子成立：
   *
   * <ul>
   *   <li>先落区表/价表再落锚表 ⇒ 中间态里锚键仍指向已删掉的派生表（跨片校验 {@code wage-anchor-market-zone-missing}）；
   *   <li>先落锚表再落区表 ⇒ 锚指向尚未进区表的 {@code zoneId}（同上）；
   *   <li>单组件 wither 链会在中间态撞上 {@code EconomyData} 构造期跨表守卫。
   * </ul>
   *
   * <p>本写口只把三张表一次性交给同一个 {@link Builder}，不改组件形状 / 不碰 Codec。
   */
  static EconomyData withMarketZonesTablesAndWageAnchors(
      EconomyData data,
      Map<MarketZoneId, MarketZone> zones,
      Map<MarketZoneId, MarketTable> tables,
      Map<WageAnchorKey, ProductionWageAnchor> wageAnchors) {
    Builder builder = new Builder(data);

    builder.marketZones = zones;

    builder.marketTables = tables;

    builder.productionWageAnchors = wageAnchors;

    return builder.build();
  }

  static EconomyData withCommodityFreightBaseMilli(EconomyData data, Map<CommodityId, Long> value) {
    Builder builder = new Builder(data);

    builder.commodityFreightBaseMilli = value;

    return builder.build();
  }

  static EconomyData withGovMarketMandates(
      EconomyData data, Map<MarketMandateId, GovernmentMarketMandate> value) {
    Builder builder = new Builder(data);

    builder.govMarketMandates = value;

    return builder.build();
  }

  static EconomyData withProductionRosters(
      EconomyData data, Map<ProductionUnitId, ProductionRoster> value) {
    Objects.requireNonNull(value, "value（清除全部名册请用空表）");

    Builder builder = new Builder(data);

    builder.productionRosters = value;

    return builder.build();
  }

  static EconomyData withWageCarryRemainders(EconomyData data, Map<WageCarryKey, Long> value) {
    Objects.requireNonNull(value, "value（清除全部工资进位请用空表）");

    Builder builder = new Builder(data);

    builder.wageCarryRemainders = value;

    return builder.build();
  }

  static EconomyData withAssetOccupancies(
      EconomyData data, Map<AssetOccupancyGrantId, AssetOccupancyGrant> value) {
    Objects.requireNonNull(value, "value（清除全部占用请用空表）");

    Builder builder = new Builder(data);

    builder.assetOccupancies = value;

    return builder.build();
  }

  static EconomyData withInventoryReservations(
      EconomyData data, Map<InventoryReservationId, InventoryReservation> value) {
    Objects.requireNonNull(value, "value（清除全部预留请用空表）");

    Builder builder = new Builder(data);

    builder.inventoryReservations = value;

    return builder.build();
  }

  static EconomyData withProductionWageAnchors(
      EconomyData data, Map<WageAnchorKey, ProductionWageAnchor> value) {
    Objects.requireNonNull(value, "value（清除全部工资锚请用空表）");

    Builder builder = new Builder(data);

    builder.productionWageAnchors = value;

    return builder.build();
  }

  static EconomyData withHouseholdFlowTables(
      EconomyData data, Map<HouseholdId, HouseholdFlowTable> value) {
    Objects.requireNonNull(value, "value（清除全部流动表请用空表）");

    Builder builder = new Builder(data);

    builder.householdFlowTables = value;

    return builder.build();
  }

  static EconomyData withTransportCapacityBatches(
      EconomyData data, Map<TransportCapacityBatchKey, TransportCapacityBatch> value) {
    Objects.requireNonNull(value, "value（清除全部运输能力批次请用空表）");

    Builder builder = new Builder(data);

    builder.transportCapacityBatches = value;

    return builder.build();
  }

  static EconomyData withTransportPriceAnchors(
      EconomyData data, Map<MarketZoneId, TransportPriceAnchor> value) {
    Objects.requireNonNull(value, "value（清除全部运输锚价请用空表）");

    Builder builder = new Builder(data);

    builder.transportPriceAnchors = value;

    return builder.build();
  }

  static EconomyData empty() {
    return new EconomyData(
        Optional.empty(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of());
  }
}
