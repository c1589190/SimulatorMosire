package io.mosire.simos.economy;

import io.mosire.simos.economy.api.id.AssetRuleId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ClassStructureId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ModeTransitionId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.model.AssetOccupancyGrant;
import io.mosire.simos.economy.model.AssetOccupancyGrantId;
import io.mosire.simos.economy.model.AssetRule;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.EconomyPolicyKey;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdDemand;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.InventoryReservation;
import io.mosire.simos.economy.model.InventoryReservationId;
import io.mosire.simos.economy.model.MarketRoundStats;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.ModeTransition;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.model.ProductionWageAnchor;
import io.mosire.simos.economy.model.WageAnchorKey;
import io.mosire.simos.economy.model.WageCarryKey;
import io.mosire.simos.production.contract.ProductionDefinitionLookup;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>revision 边界的装配视图：{@link EconomyData} + production 定义来源 ⇒ 一个完整的 {@link EconomyDayView}</b>
 * （D-032 B4 / §13.14.2 裁定十五的配套）。
 *
 * <p>★★ <b>为什么必须有它</b>：{@link EconomyDayView#industries()} 与 {@link EconomyDayView#modes()} 是
 * default 方法 —— 它们走 {@link EconomyDayView#definitions()}，而 {@code EconomyData} <b>不持有定义</b>（它继承的
 * {@code definitions()} 直接抛）。裁定十五把 economy 的 {@code industries} 副本删除之后，"手上只有 {@code EconomyData} +
 * 一份 production 定义来源"的调用方（{@code simos-app} 的读口/时间参与者）就需要一个把两者配起来的只读视图 —— 就是本类。
 *
 * <p>★ <b>瞬态、只读、零状态</b>（P1 设计书 §3.2 明文允许）：它不进 {@link EconomyData}、不进变更集、不进 codec、不落盘； 只把 {@code
 * data} 的 15 个读口逐条转调，外加一个定义来源。⇒ 它不是第二份权威，只是"当刻值 + 当刻定义"的语法糖。
 *
 * <p>★★ <b>两条读源的口径（B5 起<b>不再</b>不对称）</b>：
 *
 * <ul>
 *   <li>{@link #definitions()} → <b>注入的 production 来源</b> ⇒ {@link
 *       #industries()}（default）读的是<b>定义权威</b> （裁定十五：economy 侧的副本已删，没有第二份可读）；
 *   <li>{@link #modes()} → <b>接口 default</b>（{@code definitions().modes()} = production 的 mode 表）。
 *       ★★ B5（§13.15.2 裁定十八）：{@code EconomyData.modes} 副本与它的 GM 写腿 `economy.GmAdjust.
 *       upsertProductionMode/deactivateProductionMode` 已删除 ⇒ B4 那处"刻意转调 economy 副本"的覆盖
 *       <b>已撤</b>，两条读源现在同源（都读 production 定义权威）。
 * </ul>
 *
 * <p>★ <b>与日循环视图（{@code WorkingDayView}）的关系</b>：那个视图挂在会话工作表上、逐日"活解析"当刻值；本视图是 <b>revision
 * 边界</b>的快照（{@code EconomyData} 本身就是那一份不可变状态）⇒ 两者对 {@code industries()} 与 {@code modes()}
 * 的口径<b>一致</b>（都读 production 定义权威）。
 */
public final class EconomyBoundaryView implements EconomyDayView {

  private final EconomyData data;
  private final ProductionDefinitionLookup definitions;

  private EconomyBoundaryView(EconomyData data, ProductionDefinitionLookup definitions) {
    this.data = data;
    this.definitions = definitions;
  }

  /** 组装一个只读边界视图；两个来源都必须显式给出（缺定义来源 ⇒ 当场拒绝，不静默退化）。 */
  public static EconomyBoundaryView of(EconomyData data, ProductionDefinitionLookup definitions) {
    Objects.requireNonNull(data, "data");
    Objects.requireNonNull(definitions, "definitions（定义权威在 production，必须显式注入）");
    return new EconomyBoundaryView(data, definitions);
  }

  /** ★ 本视图读的 economy 状态（只读引用）。 */
  public EconomyData data() {
    return data;
  }

  @Override
  public ProductionDefinitionLookup definitions() {
    return definitions;
  }

  @Override
  public long economyPolicyOf(EconomyPolicyKey key) {
    return data.economyPolicyOf(key);
  }

  @Override
  public boolean economyPolicyConfigured(EconomyPolicyKey key) {
    return data.economyPolicyConfigured(key);
  }

  // ★★★ B5（§13.15.2 裁定十八）：原来这里有一处**刻意偏离接口 default** 的 `modes()` 覆盖
  //   （转调 `data.modes()` = economy 的那张副本表）—— B5 删掉了那个组件与它的 GM 写腿 ⇒ 覆盖**已撤**，
  //   `modes()` 现在走 {@code EconomyDayView} 的 default：{@code definitions().modes()}
  //   （= production 的 mode 定义表）。两条读源不再有不对称。

  @Override
  public Map<HouseholdId, HouseholdEconomy> classes() {
    return data.classes();
  }

  @Override
  public Map<HouseholdId, HouseholdClassMembership> classStandings() {
    return data.classStandings();
  }

  @Override
  public Map<ProductionOrganizationId, ProductionEnterprise> productionOrganizations() {
    return data.productionOrganizations();
  }

  @Override
  public Map<ShipmentId, ShipmentBatch> shipments() {
    return data.shipments();
  }

  @Override
  public Map<MarketZoneId, MarketTable> marketTables() {
    return data.marketTables();
  }

  @Override
  public Map<GovernmentId, Government> governments() {
    return data.governments();
  }

  @Override
  public Map<MoneyIssuanceId, MoneyIssuanceRecord> moneyIssuances() {
    return data.moneyIssuances();
  }

  @Override
  public Map<ModeTransitionId, ModeTransition> modeTransitions() {
    return data.modeTransitions();
  }

  @Override
  public Map<ClassPositionId, ProductionRole> classPositions() {
    return data.classPositions();
  }

  @Override
  public Map<ClassStructureId, ClassStructure> classStructures() {
    return data.classStructures();
  }

  @Override
  public Map<DemandId, HouseholdDemand> demands() {
    return data.demands();
  }

  @Override
  public Map<AssetRuleId, AssetRule> assetRules() {
    return data.assetRules();
  }

  @Override
  public Map<MarketZoneId, io.mosire.simos.economy.api.market.MarketZone> marketZones() {
    return data.marketZones();
  }

  @Override
  public Map<MarketZoneId, MarketRoundStats> marketRoundStats() {
    return data.marketRoundStats();
  }

  @Override
  public Map<ProductionUnitId, ProductionRoster> productionRosters() {
    return data.productionRosters();
  }

  @Override
  public Map<WageCarryKey, Long> wageCarryRemainders() {
    return data.wageCarryRemainders();
  }

  @Override
  public Map<WageAnchorKey, ProductionWageAnchor> productionWageAnchors() {
    return data.productionWageAnchors();
  }

  @Override
  public Map<AssetOccupancyGrantId, AssetOccupancyGrant> assetOccupancies() {
    return data.assetOccupancies();
  }

  @Override
  public Map<InventoryReservationId, InventoryReservation> inventoryReservations() {
    return data.inventoryReservations();
  }
}
