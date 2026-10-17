package io.mosire.simos.economy.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.debt.DebtSource;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
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
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.InstrumentId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ModeTransitionId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import io.mosire.simos.economy.api.id.PledgeId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.market.GovernmentMarketMandate;
import io.mosire.simos.economy.api.market.LossBearer;
import io.mosire.simos.economy.api.market.MarketMandateId;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.api.market.ShipmentAllocation;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.api.market.TradeRoute;
import io.mosire.simos.economy.api.money.CurrencyDef;
import io.mosire.simos.economy.api.money.InstrumentKind;
import io.mosire.simos.economy.api.money.MoneyInstrument;
import io.mosire.simos.economy.api.money.MoneyIssuanceKind;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.api.relation.Weight;
import io.mosire.simos.economy.api.stock.DeductionReason;
import io.mosire.simos.economy.api.stock.HouseholdPeriodicAdjustment;
import io.mosire.simos.economy.api.stock.PeriodicHouseholdAdjustmentId;
import io.mosire.simos.economy.model.AssetOccupancyGrant;
import io.mosire.simos.economy.model.AssetOccupancyGrantId;
import io.mosire.simos.economy.model.AssetOccupancyStatus;
import io.mosire.simos.economy.model.AssetRule;
import io.mosire.simos.economy.model.ClassShare;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.CommodityRound;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.EconomyMeta;
// ★★ W10（2026-10-11）：EconomyMeta 第 6 组件（政策预设表）的键
import io.mosire.simos.economy.model.EconomyPolicyKey;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdDemand;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.InventoryReservation;
import io.mosire.simos.economy.model.InventoryReservationId;
import io.mosire.simos.economy.model.InventoryReservationStatus;
import io.mosire.simos.economy.model.LiquidationPolicy;
import io.mosire.simos.economy.model.MarketRoundStats;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.ModeTransition;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.Pledge;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionParticipant;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.model.ProductionWageDefault;
import io.mosire.simos.economy.model.ProductionWagePosting;
import io.mosire.simos.economy.model.TransferRule;
import io.mosire.simos.economy.model.WageCarryKey;
import io.mosire.simos.economy.model.WageQuoteSource;
import io.mosire.simos.economy.testing.IndustryViews;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.PerCycleCarry;
import io.mosire.simos.production.contract.ProductionEfficiencyState;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionModeDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★ 铁律 5 的机械化落地（照 {@code LedgerRoundTripTest}）：反射枚举 {@link EconomyData} 的 record 组件，逐组件造 差异，三条断言
 * ——新增状态组件若忘了进变更集，本测试自动红。
 *
 * <p>★ **它也是本轮"变异自证"的落点**：把 {@code EconomyChangeSet.between} 里任一分量改成恒 {@code Unchanged}，
 * "该组件参与"那条断言当场红。
 *
 * <p>★★ <b>S1/R3B.2/R4/E1–E6 的 API 漂移已在这里就位</b>：{@code classes}/{@code flows} 的键 = {@link
 * HouseholdId}（旧视图用 {@code HouseholdIds.ofLegacy}）；{@code relations} 挂 {@link ProductionUnitId}；
 * operator / 周期进度住在 {@link ProductionProcess} 上（不是 {@code IndustryDefinitionView} 的旧档兼容位）；{@code
 * EconomyData} 是 31 组件记录（E1–E6 追加组件 + P10.1 {@code merchantFirms} + P4a {@code periodicAdjustments}
 * + Z1 {@code outputQuantityOverrides}/{@code productionEfficiency}，全部在 {@link EconomyChangeSet}
 * 里逐一对齐）。
 *
 * <p>★ P0.1（2026-10-05）删除了 {@code classFirst} 组件：唯一经济路线是 production-runtime，本往返面同步收敛为 31 组件。
 */
class EconomyRoundTripTest {

  private static final IndustryId FARM = new IndustryId("farm");
  private static final SocialClassId PEASANT = new SocialClassId("poor_peasant");
  private static final SocialClassId LANDLORD = new SocialClassId("landlord");
  private static final CohortKey KEY =
      new CohortKey(new HexCoord(0, 0), ResidenceKind.RURAL, PEASANT);
  private static final CohortKey OTHER_KEY =
      new CohortKey(new HexCoord(0, 0), ResidenceKind.RURAL, LANDLORD);

  /** ★ S1：键 = 稳定家户身份；{@code KEY} 只是它的旧视图。 */
  private static final HouseholdId KEY_HH = HouseholdIds.ofLegacy(KEY);

  private static final HouseholdId OTHER_HH = HouseholdIds.ofLegacy(OTHER_KEY);
  private static final CommodityId GRAIN = new CommodityId("grain");

  /** ★ R3：第二种商品（"所得逐商品"的那一维在往返里要真的被带上，只有一个商品的夹具挡不住"退回标量"）。 */
  private static final CommodityId CLOTH = new CommodityId("cloth");

  /**
   * ★★ W1 收尾批（2026-10-11）：<b>明确 0 价</b>那一行的商品 —— {@code prices} 里**有这一行且值为 0** = 明确免费交易， **没有这一行**
   * = 不交易；两种状态是两件事（{@link MarketTable#hasPrice} / {@link MarketTable#isFree}）， 往返夹具必须在值层面把"0
   * 价那一行"与"没这一行"分开（{@link MarketTable#describe} 与逐字段相等的断言都看得见它）。
   */
  private static final CommodityId TOOL = new CommodityId("tool");

  /** ★ R2：劳动供给与配额的夹具身份（一格一批人 ⇒ 供给一条、配额一条）。 */
  private static final PeopleLotId LOT = new PeopleLotId("rural:0_0:MALE:1");

  /** ★ H4：市场夹具的计价货币（每格恰一种 —— 裁定 M1-A）。 */
  private static final CurrencyId SILVER = new CurrencyId("silver");

  /**
   * ★★ W1：市场夹具的**市场 id**（价表键）。★ 派生形态（{@code marketZones} 为空）⇒ 键 = 派生市场 id、成员格住在价表条目里 （{@link
   * MarketTable#hexes()}）；本夹具的成员格就是 {@link #KEY} 所在那一格（与改动前"键 = 格"同一份格集）。
   */
  private static final MarketZoneId MARKET = new MarketZoneId("market-round-trip");

  private static final LaborAllocationId ALLOCATION =
      new LaborAllocationId("alloc-farm-rural:0_0:MALE:1");

  /** ★ M2.4：在途批次的夹具身份（键 = 批次 id；值里带路线/商品/发运到达日/逐票分配）。 */
  private static final ShipmentId SHIPMENT = new ShipmentId("shipment-1");

  // ── E1–E6 追加组件的夹具身份（RoundTrip 必须为每个组件都能造出 target） ───────────────────

  /** ★ E4a：连续粮债的稳定身份（key == 值内 id，且 id 由四元组派生）。 */
  private static final DebtContractId D1 =
      DebtContractId.idOf(KEY_HH, OTHER_HH, DebtUnit.commodity(GRAIN), DebtTerms.legacyDefault());

  private static final ProductionModeId MODE = new ProductionModeId("mode-1");
  private static final ProductionModeId MODE_TARGET = new ProductionModeId("mode-2");
  private static final ClassStructureId STRUCTURE = new ClassStructureId("structure-1");
  private static final ClassPositionId POSITION = new ClassPositionId("position-1");
  private static final ProductionOrganizationId ORGANIZATION =
      new ProductionOrganizationId("organization-1");
  private static final AssetRuleId ASSET_RULE = AssetRuleId.idOf(MODE, AssetKind.CATTLE);
  private static final GovernmentId GOVERNMENT = new GovernmentId("government-1");

  /** ★★ R1（2026-10-09）：政府市场授权表的键。 */
  private static final MarketMandateId MANDATE = new MarketMandateId("mandate-1");

  private static final MoneyIssuanceId ISSUANCE = new MoneyIssuanceId("issuance-1");
  private static final PledgeId PLEDGE = new PledgeId("pledge-1");
  private static final CrisisSignalId CRISIS = CrisisSignalId.idOf(KEY.hex(), "FOOD");
  private static final ModeTransitionId TRANSITION =
      ModeTransitionId.idOf(ORGANIZATION, MODE_TARGET, 10L);
  private static final ClassShareId CLASS_SHARE = ClassShareId.idOf(TRANSITION, KEY_HH, POSITION);

  /** ★ P4a：周期家户扣增规则夹具身份（键 == 值内 id）。 */
  private static final PeriodicHouseholdAdjustmentId PERIODIC_ADJUSTMENT =
      new PeriodicHouseholdAdjustmentId("adjustment-1");

  /**
   * ★★ A1（2026-10-08）的第 32/33 个组件夹具：默认词表只有 {@code silver} + {@code silver-specie} ⇒
   * 要看见差异就必须给一个**非默认**币种与一张非默认工具（空表会被构造期归一成 legacy 词表，"没进变更集"与"没加币"在值层面就不可区分了）。
   */
  private static final CurrencyDef GOLD = new CurrencyDef("gold", 3, "金");

  /** 金的金币（金属币：价值来自金属本身 ⇒ 没有发行人）。 */
  private static final MoneyInstrument GOLD_SPECIE =
      new MoneyInstrument(
          new InstrumentId("gold-specie"),
          GOLD.currencyId(),
          InstrumentKind.SPECIE,
          Optional.empty(),
          Optional.empty());

  /** ★ B2（2026-10-08）的第 34 个组件夹具：一个区 + 它的发行政府（区的法定币必须在该政府的 {@code issuable} 里）。 */
  private static final MarketZoneId ZONE = new MarketZoneId("zone-1");

  private static final GovernmentId ZONE_GOV = new GovernmentId("zone-government-1");

  /**
   * ★★ <b>W1 收尾批（2026-10-11）：<b>持久形态</b>价表那一维的区</b> —— 它的锚格/成员格**刻意不等于** {@link #KEY} 的格：派生市场
   * {@link #MARKET} 的成员格就是那一格，而构造期守卫②（"价表的成员格不得已被某个区覆盖"） 不许两者重叠。计价币 = 默认词表里的 {@code
   * silver}（区法定币必须在币种表里）。
   */
  private static final MarketZoneId ZONE_EAST = new MarketZoneId("zone-east");

  private static final HexCoord ZONE_EAST_HEX = new HexCoord(2, 0);

  /**
   * ★★ T2/D9 的 operator 夹具：**非派生值**（`HOUSEHOLD:house-7`；本文件 `industry` 的 regime 是 `tenant`， 推导值 =
   * `HOUSEHOLD:farm`）。R3B.2 起它是 {@link ProductionProcess} 的字段，不再挂在 {@code IndustryDefinitionView}
   * 上。
   */
  static final ActorRef NON_DEFAULT_OPERATOR = new ActorRef(ActorKind.HOUSEHOLD, "house-7");

  /** ★ R3B.2：unit id 的唯一拼写点；本文件三个组件（units / relations / allocations）共用同一条。 */
  private static final ProductionUnitId FARM_UNIT =
      ProductionUnitId.idOf(FARM, NON_DEFAULT_OPERATOR);

  /** D-032 C2/D3：单角色方案的稳定缺省角色 id（与 production 的 defaultOperator 同键）。 */
  private static final ProductionRoleId OPERATOR_ROLE = ProductionRoleId.parse("operator");

  private static final ProductionLocation FARM_LOCATION = new ProductionLocation("0_0");

  /** ★ R3B.1：assetShares 夹具的键（键 == 值内 id；industry 必须存在）。 */
  private static final AssetShareId FARM_SHARE =
      OwnershipStake.idOf(
          FARM,
          AssetKind.CATTLE,
          NON_DEFAULT_OPERATOR,
          NON_DEFAULT_OPERATOR,
          AssetRightKind.OWNED,
          FARM_LOCATION,
          0L);

  /** ★ R4-E2：后两个组件的夹具键。 */
  private static final DemandId DEMAND = new DemandId("demand-1");

  /** ★ 唯一的豁免集合：v1 的 EconomyData 没有"不进变更集"的组件 ⇒ 必须是空集，且被单独钉死。 */
  private static final Set<String> EXCLUDED_FROM_CHANGE_SET = Set.of();

  @Test
  void everyEconomyDataComponentParticipatesInTheChangeSet() {
    for (RecordComponent rc : EconomyData.class.getRecordComponents()) {
      if (EXCLUDED_FROM_CHANGE_SET.contains(rc.getName())) {
        continue;
      }
      String name = rc.getName();
      EconomyData base = EconomyData.empty();
      EconomyData target = mutate(base, name);
      EconomyChangeSet cs = EconomyChangeSet.between(base, target);

      assertThat(cs.isEmpty()).as("组件 %s 必须进变更集（漏了它 ⇒ 变更集整个为空）", name).isFalse();
      assertThat(changedOf(cs, name)).as("组件 %s 必须被 between 报成非 Unchanged", name).isTrue();
      assertThat(EconomyChangeSet.apply(cs, base)).as("组件 %s 的往返", name).isEqualTo(target);
    }
  }

  // ★★★ D-032 T1（测试 owner）：本区原有一条 `anOperatorThatIsNotTheRegimeDefaultSurvivesTheChangeSet`
  //   （"显式绑定、非制度默认的 operator 参与 `FieldDelta<ProductionProcess>` 的差异与重建"）已**整方法移出本文件**。
  //   ★ 这不是删除判据，而是**换源**（§14.3 第 3 条"实例读数改读 production"）：
  //   ① 被测对象 `EconomyChangeSet.units()`（economy 侧实例表）**已随迁移整体迁出**（I1：实例唯一权威在
  //      production；实现账本 B2b）⇒ 旧断言点在本模块里**语法上不存在**（`EconomyChangeSet` 的 record 组件表
  //      里没有 `units`）；
  //   ② 同一条判别力（operator 那一维必须进差异、且重建时**不得**被"按制度重新推导"顶替）已在 production 侧
  //      **逐字重钉**于 `ProductionChangeSet.instances()`：见
  //
  // `simos-production/src/test/java/io/mosire/simos/production/change/ProductionRoundTripTest.java`
  //      的 `anOperatorThatIsNotTheRegimeDefaultSurvivesTheProductionChangeSet`（T2 交付，含非默认夹具前置断言）。

  @Test
  void theExclusionListIsEmpty() {
    assertThat(EXCLUDED_FROM_CHANGE_SET).as("EconomyData 没有豁免项；要加名字必须在 diff 里现形").isEmpty();
  }

  /**
   * ★★ <b>W1 收尾批（2026-10-11）：两种价表形态 + 多区三段统计窗口，一次过变更集</b>（铁律 5 的**真往返**）。
   *
   * <p>★★ <b>为什么上面那条逐组件用例不够</b>：它按组件逐个变异、每个夹具只要"最小可用"即可 ⇒ 三种形状长期没进过往返： ① 只有**派生形态**价表（持久形态的"成员格为空 +
   * 计价币 == 该区法定币"从未被重建过）；② 价格表只有正价（ <b>明确 0 价</b>那一行从未被带上）；③
   * 统计簿只有**一个区**、每段只有一个商品（三段窗口之间的串味、多区只按第一个键成账， 都看不出来）。★ 本用例把这三种形状放进**同一份
   * target**，判据与既有断言面一致：{@code apply(changeSet, base)} **逐字段重建** target。
   *
   * <p>★ <b>判别力（改坏 ⇒ 红）</b>：把 {@code EconomyChangeSet.between} 里 {@code marketTables} 或 {@code
   * marketRoundStats} 的差异改成恒 {@code Unchanged}，本用例的 {@code changed()} 断言当场红；把 {@code apply}
   * 里这两个分量的重建写漏，逐字段那几句当场红。
   */
  @Test
  void bothMarketTableFormsAndTheWholeStatsWindowSurviveTheChangeSet() {
    EconomyData base = EconomyData.empty();
    Map<MarketZoneId, MarketTable> tables = Map.of(MARKET, market(), ZONE_EAST, zoneMarket());
    Map<MarketZoneId, MarketRoundStats> stats = statsBook();
    EconomyData target =
        base.withMarketZones(Map.of(ZONE_EAST, zoneEast()))
            .withMarketTables(tables)
            .withMarketRoundStats(stats);

    // ── 前置：夹具自身的判别力（不引用被测物）—— 两形态真的同时在、0 价行真的存在、三段窗口真的非空 ──
    assertThat(tables.get(MARKET).hexes()).as("派生形态：成员格非空").isNotEmpty();
    assertThat(tables.get(MARKET).hasPrice(TOOL)).as("夹具必须含**明确 0 价**那一行").isTrue();
    assertThat(tables.get(MARKET).isFree(TOOL)).isTrue();
    assertThat(tables.get(ZONE_EAST).hexes()).as("持久形态：成员格必须为空").isEmpty();
    assertThat(tables.get(ZONE_EAST).numeraire())
        .as("持久形态：计价币 == 该区法定币")
        .isEqualTo(zoneEast().legalTender());
    assertThat(stats).as("多区统计簿").hasSize(2);
    for (Map.Entry<MarketZoneId, MarketRoundStats> entry : stats.entrySet()) {
      assertThat(entry.getValue().lastRound()).as("%s 的本轮明细非空", entry.getKey().value()).hasSize(2);
      assertThat(entry.getValue().cycleToDate())
          .as("%s 的本周期累计非空", entry.getKey().value())
          .hasSize(2);
      assertThat(entry.getValue().previousCycle())
          .as("%s 的上周期累计非空", entry.getKey().value())
          .hasSize(2);
    }
    assertThat(stats.get(MARKET)).as("两区不是同一份值").isNotEqualTo(stats.get(ZONE_EAST));

    EconomyChangeSet cs = EconomyChangeSet.between(base, target);

    assertThat(cs.marketZones().changed()).as("区表必须进变更集").isTrue();
    assertThat(cs.marketTables().changed()).as("价表必须进变更集").isTrue();
    assertThat(cs.marketRoundStats().changed()).as("统计簿必须进变更集").isTrue();

    EconomyData back = EconomyChangeSet.apply(cs, base);

    assertThat(back).as("整份状态逐字段重建 == target").isEqualTo(target);
    assertThat(back.marketZones()).as("区表逐键重建").containsOnlyKeys(ZONE_EAST);
    assertThat(back.marketTables()).as("两个市场都在").containsOnlyKeys(MARKET, ZONE_EAST);
    assertThat(back.marketTables().get(MARKET)).as("派生形态价表逐字段重建").isEqualTo(market());
    assertThat(back.marketTables().get(MARKET).prices()).as("三个商品价格都带上").hasSize(3);
    assertThat(back.marketTables().get(MARKET).hasPrice(TOOL)).as("明确 0 价那一行必须还在").isTrue();
    assertThat(back.marketTables().get(MARKET).priceOf(TOOL)).isZero();
    assertThat(back.marketTables().get(MARKET).isFree(TOOL)).isTrue();
    assertThat(back.marketTables().get(ZONE_EAST)).as("持久形态价表逐字段重建").isEqualTo(zoneMarket());
    assertThat(back.marketTables().get(ZONE_EAST).hexes()).isEmpty();
    assertThat(back.marketTables().get(ZONE_EAST).numeraire()).isEqualTo(zoneEast().legalTender());
    assertThat(back.marketRoundStats()).as("统计簿逐键重建").isEqualTo(stats);
    for (MarketZoneId marketId : List.of(MARKET, ZONE_EAST)) {
      MarketRoundStats readBack = back.marketRoundStats().get(marketId);
      assertThat(readBack.lastRound()).as("%s 的本轮明细", marketId.value()).hasSize(2);
      assertThat(readBack.cycleToDate()).as("%s 的本周期累计", marketId.value()).hasSize(2);
      assertThat(readBack.previousCycle()).as("%s 的上周期累计", marketId.value()).hasSize(2);
    }
    assertThat(back.marketRoundStats().get(MARKET))
        .as("两区统计不是同一份值（否则「只按第一个键成账」也能过）")
        .isNotEqualTo(back.marketRoundStats().get(ZONE_EAST));
  }

  /**
   * ★★★ <b>W10（2026-10-11）：{@code EconomyMeta.economyPolicies}（政策预设表）的变更集真往返 —— 两个方向都走一遍</b>（铁律
   * 5：{@code apply(between(base, target), base)} 逐字段重建 target）。
   *
   * <p>★★ <b>为什么上面那条逐组件的用例挡不住它</b>：那条把 {@code meta} 组件的目标造成"<b>空</b>政策表"（{@link #meta()} 是 5
   * 参便捷构造器）⇒ 表里有键、键序、多键这些形态<b>从没进过变更集</b>；而政策表恰恰是"一个组件里的嵌套表" （最容易被 {@code FieldDelta} 的 record
   * 相等判成"没变化"或重建时丢掉的那类形状）。
   *
   * <p>★★ <b>两个方向</b>：① base = 空表 → target = 非空表（{@code Upsert}）；② 反向 target → base（值→另一个值，仍是
   * {@code Upsert}）。
   *
   * <p>★ <b>夹具刻意乱序插入</b>（PROFIT_GAP → SLIDE_ROUND → FAMINE，与枚举声明序不同）⇒ 规范序 = 声明序，重建后 {@code
   * keySet()} 必须落回声明序；若哪一层把保序容器换成 {@code HashMap}/{@code Map.copyOf}，下面那句当场红。
   *
   * <p>★ <b>判别力（改坏 ⇒ 红）</b>：把 {@code EconomyChangeSet.between} 的 {@code meta} 分量改成恒 {@code
   * Unchanged} ⇒ {@code changed()} 与 {@code isInstanceOf(Upsert)} 两句红；把 {@code apply} 的 {@code
   * meta} 重建写漏（或让它落回"空表 meta"） ⇒ 逐字段那几句红。
   */
  @Test
  void theEconomyPolicyTableSurvivesTheChangeSetInBothDirections() {
    EconomyData base = EconomyData.empty().withMeta(Optional.of(meta()));
    Map<EconomyPolicyKey, Long> configured = new LinkedHashMap<>();
    configured.put(EconomyPolicyKey.PROFIT_GAP_SPEED_SLOPE_PER_MILLE, 250L);
    configured.put(EconomyPolicyKey.SLIDE_ROUND_DAYS, 9L);
    configured.put(EconomyPolicyKey.FAMINE_MAX_PPM_PER_TICK, 7_000L);
    EconomyMeta configuredMeta =
        new EconomyMeta(
            meta().mapId(),
            meta().activatedDay(),
            meta().lastClosedCycle(),
            meta().rulesVersion(),
            meta().migrationSource(),
            configured,
            java.util.Map.of(), // ★ D-032 第 7 组件 planAssetSources（缺键 = 只认自有份额，逐值 == 旧缺省）
            java.util.Map.of());
    EconomyData target = base.withMeta(Optional.of(configuredMeta));

    // 前置：两份夹具的差别就是那张表（否则下面的断言会对着别的差异变绿）。
    assertThat(base.meta().orElseThrow().economyPolicies()).as("base 的政策表是空的").isEmpty();
    assertThat(target.meta().orElseThrow().economyPolicies()).hasSize(3);

    EconomyChangeSet cs = EconomyChangeSet.between(base, target);
    assertThat(cs.meta())
        .as("政策表变了 ⇒ meta 分量不得退化成 Unchanged")
        .isInstanceOf(FieldDelta.Upsert.class);
    assertThat(cs.meta().changed()).as("差异必须看得见政策表").isTrue();

    EconomyData rebuilt = EconomyChangeSet.apply(cs, base);
    assertThat(rebuilt).as("★ 重建后的整份状态 == target（逐字段）").isEqualTo(target);
    assertThat(rebuilt.meta().orElseThrow().economyPolicies())
        .as("政策表逐键逐值重建")
        .isEqualTo(configured);
    assertThat(rebuilt.meta().orElseThrow().economyPolicies().keySet())
        .as("★ 重建后的**顺序** = 枚举声明序（保序容器不得在变更集这一层被换掉）")
        .containsExactly(
            EconomyPolicyKey.FAMINE_MAX_PPM_PER_TICK,
            EconomyPolicyKey.SLIDE_ROUND_DAYS,
            EconomyPolicyKey.PROFIT_GAP_SPEED_SLOPE_PER_MILLE);
    assertThat(rebuilt.meta().orElseThrow().policyOf(EconomyPolicyKey.SLIDE_COST_PER_LEVEL))
        .as("缺键语义在重建后仍成立（缺键 ⇒ 具名缺省）")
        .isEqualTo(EconomyPolicyKey.SLIDE_COST_PER_LEVEL.defaultValue());

    // ② 反向：非空 → 空（同一个单键表投影的另一侧）。
    EconomyChangeSet reverse = EconomyChangeSet.between(target, base);
    assertThat(reverse.meta())
        .as("值 → 另一个值 = Upsert（不是 Remove：两侧都有 meta）")
        .isInstanceOf(FieldDelta.Upsert.class);
    assertThat(reverse.meta().changed()).isTrue();
    EconomyData backToEmpty = EconomyChangeSet.apply(reverse, target);
    assertThat(backToEmpty).as("★ 反向重建 == base（政策表回到空表）").isEqualTo(base);
    assertThat(backToEmpty.meta().orElseThrow().economyPolicies())
        .as("反向重建后政策表是空的（缺键 = 全部走缺省）")
        .isEmpty();
  }

  /**
   * ★★★ <b>W10（2026-10-11）：{@code EconomyMeta.withPolicy} 的"写缺省 ⇒ 删行 ⇒ 空表"语义</b>（用户裁定的写口
   * 形状：政策表里<b>不留</b>"等于现值的冗余行"）。
   *
   * <p>★ 这条为什么必须有独立用例：写口的**写入**语义（非缺省值进表）由 {@link
   * #theEconomyPolicyTableSurvivesTheChangeSetInBothDirections} 与 MCP 覆盖用例把住，而"写回缺省值是<b>删除</b>、
   * 删到最后一个键时表<b>变空</b>"是另一条路径 —— 一个"往表里塞缺省值"的实现能让上面两条全绿，却让
   * "从没被写过的世界逐值等于改动前"（缺省中性）在**改过又改回来**之后不再成立（表里躺着冗余行 ⇒ 字节不同）。
   *
   * <p>★ <b>三条路都走</b>：① 非缺省 → 缺省（删该行，其它键保留）；② 删掉最后一个键 ⇒ 空表；③ 对<b>本来就没有</b>该键的 meta 写缺省 ⇒
   * 仍是空表（不凭空补一行）。
   *
   * <p>★ 另加一条<b>负向</b>：越域值当场抛（fail-closed），且 policy 表不被改动。
   */
  @Test
  void writingTheDefaultValueDeletesTheRowAndEmptiesTheTable() {
    EconomyPolicyKey key = EconomyPolicyKey.SLIDE_ROUND_DAYS;
    EconomyPolicyKey other = EconomyPolicyKey.FAMINE_MAX_PPM_PER_TICK;
    long custom = key.defaultValue() + 4L;

    // ① 非缺省 → 缺省：删该行，**其它键保留**（不是把整张表清空）。
    EconomyMeta twoKeys =
        meta().withPolicy(key, custom).withPolicy(other, other.defaultValue() + 1_000L);
    assertThat(twoKeys.economyPolicies())
        .as("前置：两个键都在表里")
        .containsOnlyKeys(key, other)
        .containsEntry(key, custom);
    EconomyMeta afterDefault = twoKeys.withPolicy(key, key.defaultValue());
    assertThat(afterDefault.economyPolicies())
        .as("★ 写回缺省值 ⇒ 该行被**删掉**（不是留一行等于缺省值的冗余行）")
        .doesNotContainKey(key);
    assertThat(afterDefault.economyPolicies())
        .as("其它键不受影响")
        .containsEntry(other, other.defaultValue() + 1_000L);

    // ② 删掉最后一个键 ⇒ 表变空（= 从没被写过的世界）。
    EconomyMeta emptied = afterDefault.withPolicy(other, other.defaultValue());
    assertThat(emptied.economyPolicies()).as("★ 最后一个键被写回缺省 ⇒ 政策表**空**").isEmpty();
    assertThat(emptied.policyOf(key)).as("空表读口 = 全部走具名缺省").isEqualTo(key.defaultValue());
    assertThat(emptied.policyOf(other)).isEqualTo(other.defaultValue());

    // ③ 对**本来就没有**该键的 meta 写缺省 ⇒ 仍是空表（不凭空补一行）。
    assertThat(meta().withPolicy(key, key.defaultValue()).economyPolicies())
        .as("写缺省到空表 ⇒ 仍是空表（不留冗余行）")
        .isEmpty();

    // ④ 非缺省值仍然进表（反向对照：上面的"删行"不是"什么都不写"）。
    assertThat(meta().withPolicy(key, custom).economyPolicies())
        .as("非缺省值必须真的进表（否则①②的'删除'断言可能是对着一个恒空实现变绿）")
        .containsExactly(Map.entry(key, custom));

    // ⑤ 负向：越域当场抛（fail-closed），且原实例不动（record 不可变）。
    EconomyMeta before = meta().withPolicy(key, custom);
    assertThatThrownBy(() -> before.withPolicy(key, key.maxValue() + 1L))
        .as("越域值（上界 +1）必须 fail-closed")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("越域");
    assertThatThrownBy(() -> before.withPolicy(key, key.minValue() - 1L))
        .as("越域值（下界 −1）必须 fail-closed")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("越域");
    assertThat(before.economyPolicies()).as("被拒的写不得改动原实例").containsExactly(Map.entry(key, custom));
  }

  /**
   * ★★ <b>组件计数（R4 16 → E3 24 → E4 25 → E5 27 → E6 29 → P10.1 30 → P2-A 删 laborSupply / memberships
   * ⇒ 28 → P4a {@code periodicAdjustments} ⇒ 29 → Z1 追加两组件 ⇒ 31；classFirst 已删）</b>： {@code meta} /
   * {@code industries} / {@code classes} / {@code debtContracts} / {@code flows} / {@code
   * allocations} / {@code relations} / {@code markets} / {@code shipments} / {@code assetShares} /
   * {@code operatorConditions} / {@code units} / {@code demands} / {@code candidates} / E1 的四个 / E2
   * 的两个 / E3 的两个 / E4 的 {@code pledges} / E5 的两个 / E6 的两个 / P10.1 的 {@code merchantFirms} / P4a 的
   * {@code periodicAdjustments} / Z1 的 {@code outputQuantityOverrides} 与 {@code
   * productionEfficiency} / A1（2026-10-08）的 {@code currencies} 与 {@code moneyInstruments} /
   * B2（2026-10-08）的 {@code marketZones} / 2026-10-09 选项 A 的 {@code householdDebtRefs}。
   *
   * <p>★ 这个名字里的数字**故意写死**（R4 16 → … → P4a 29 → Z1 31 → A1 33 → B2 34 → 选项 A
   * 35）：它就是"又加/删了一个状态组件"这件事在编译/测试面上的**唯一提醒** ——改动 {@code EconomyData} 而没同步变更集时，本用例当场红。
   *
   * <p>★★ <b>本条的欠账如实记（2026-10-09 选项 A 迁移时实测发现）</b>：这个数字自 A1/B2 起就**已经过期**（那两批各加了组件却 没改这里：31 →
   * 34），也就是说在本轮拆表<b>之前</b>它就已经是红的 —— 不是本次结构改动弄红的。本次按**实测**改成 35（ {@code EconomyChangeSet} 与 {@code
   * EconomyData} 两侧同为 35，上面两条子集断言现在两边同名同数）。
   *
   * <p>★★ <b>F 批（2026-10-09）再 +1 ⇒ 36</b>：{@code commodityFreightBaseMilli}（商品运费**基础费表**，缺键 ⇒
   * 现行硬编码分档；★ 2026-10-09 用户裁定「甲」后由"费率乘数"纠正为"基础费"）。
   *
   * <p>★★ <b>M-A1（2026-10-10）−1：37 → 36</b>：{@code merchantFirms}（原第 30 个持久组件）已随 "商号行退役、运力改派生量"（用户
   * 2026-10-10 追加裁定 4）从 {@code EconomyData} 与 {@code EconomyChangeSet} **两侧同时删除**。⇒ 本数字按**实测**改成
   * 36（两侧同名同数，子集断言仍然双向成立）。★ 这条改动是**删维度**，不是 "旧档兼容位"：新测试不补任何 {@code merchantFirms} 的往返夹具（§一.11 第 2/3
   * 条）。
   *
   * <p>★★ <b>W1（2026-10-10）+1：36 → 37</b>：第 9 个组件**改名** {@code markets → marketTables}（形状级改造：键从格改成
   * 市场 id，组件数不变）+ 新增第 40 个组件 {@code marketRoundStats}（市场轮统计簿：逐市场 D/S/成交量/均价/未满足量 + 成交额的三段固定窗口）。⇒
   * 本数字按**实测**改成 37，{@code mutate}/{@code changedOf} 两个 switch 里同步给 {@code marketTables} 与 {@code
   * marketRoundStats} 各一条**非空**夹具（空表与"字段没进变更集"在值层面不可区分）。★★ W1 收尾批：这两个夹具再加强为 <b>两种价表形态</b>（派生 + 持久）与
   * <b>多区、每段两商品且三段互不相同</b>的统计簿 —— 单形态/单区/单商品的"最小可用" 夹具挡不住形状错误（见 {@link
   * #bothMarketTableFormsAndTheWholeStatsWindowSurviveTheChangeSet}）。
   *
   * <p>★★ <b>D-032（T5 按实测收口）：37 → 33 → 31</b>：{@code industries}（定义副本）· {@code units}（实例表）· {@code
   * outputQuantityOverrides} · {@code productionEfficiency} · {@code candidates}（候选预设表）· {@code
   * modes}（生产方式定义副本）六个"第二权威"整段删除（I1：定义/实例/进度/经验/产出覆盖的唯一权威在 production； 组件数迁移轨迹 37 →（B2b 删
   * units/覆盖/效率）33 →（B4 删 industries, B2c 删 candidates）33→31 →（B5 删 modes）**31**）。★ 断的是 record
   * 组件数**实测值**，两侧子集断言仍然双向成立 ⇒ 再动状态必须同步本用例。
   */
  @Test
  void changeSetHasExactlyThirtySevenComponents() {
    assertThat(EconomyChangeSet.class.getRecordComponents()).hasSize(37);
    assertThat(componentNames(EconomyChangeSet.class))
        .as("变更集的每个组件都必须在 EconomyData 里有同名的 record 组件")
        .isSubsetOf(componentNames(EconomyData.class));
    assertThat(componentNames(EconomyData.class))
        .as("反向也成立 ⇒ 两边组件集相同")
        .isSubsetOf(componentNames(EconomyChangeSet.class));
  }

  @Test
  void snapshotNamespaceIsEconomy() {
    EconomySnapshot snapshot =
        new EconomySnapshot(
            new StateRef(new BranchId("main"), new RevisionId(1)),
            SimosTimestamp.of(5),
            EconomyData.empty());
    assertThat(snapshot.namespace()).isEqualTo("economy");
  }

  /** ★ P4a：最小自洽的周期扣增规则（sink 档；商品腿正数即可，无需账户/家户支撑）。 */
  private static HouseholdPeriodicAdjustment periodicAdjustment() {
    return new HouseholdPeriodicAdjustment(
        PERIODIC_ADJUSTMENT,
        KEY_HH,
        Optional.empty(),
        Map.of(GRAIN, 5L),
        Map.of(),
        DeductionReason.MILITARY_SALARY,
        30L,
        0L,
        0L,
        OptionalLong.empty(),
        "gm:test");
  }

  /** ★★ Z1：生产单元的效率累计夹具（五个字段全非零；域与 {@code FARM.cycleDays()==120}、{@code lpu==7} 相容）。 */
  private static ProductionEfficiencyState efficiencyState() {
    return new ProductionEfficiencyState(new PerCycleCarry(120_000L, 3L, 1L));
  }

  /** C2：最小自洽名册（恰好一个 organizer；角色与 production 默认角色同键）。 */
  private static ProductionRoster roster() {
    return new ProductionRoster(
        FARM_UNIT, FARM, List.of(ProductionParticipant.of(KEY_HH, OPERATOR_ROLE, true, 1_000L)));
  }

  private static ProductionWageDefault wageDefault() {
    return new ProductionWageDefault(FARM, OPERATOR_ROLE, MARKET, 5L, 1_000L);
  }

  private static ProductionWagePosting wagePosting() {
    return new ProductionWagePosting(
        FARM_UNIT,
        OPERATOR_ROLE,
        1_000L,
        SILVER,
        5L,
        WageQuoteSource.SEED,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L);
  }

  private static AssetOccupancyGrant assetOccupancy() {
    return new AssetOccupancyGrant(
        AssetOccupancyGrantId.idOf(FARM_UNIT, FARM_SHARE, 0L),
        FARM_SHARE,
        FARM_UNIT,
        1L,
        0L,
        30L,
        AssetOccupancyStatus.ACTIVE,
        -1L);
  }

  private static InventoryReservation inventoryReservation() {
    ActorRef payer = HouseholdActors.of(KEY_HH);
    return new InventoryReservation(
        InventoryReservationId.idOf(FARM_UNIT, payer, FARM_LOCATION, GRAIN, 0L),
        FARM_UNIT,
        payer,
        FARM_LOCATION,
        GRAIN,
        1L,
        0L,
        30L,
        InventoryReservationStatus.ACTIVE,
        -1L);
  }

  private static EconomyData mutate(EconomyData base, String name) {
    return switch (name) {
      case "meta" -> base.withMeta(Optional.of(meta()));
      case "classes" ->
          // ★ S1：键 = HouseholdId（视图住在行内）。先落 meta 标记（见 meta()），避免构造期自动跑旧档迁移。
          base.withMeta(Optional.of(meta()))
              .withHouseholdEconomies(Map.of(KEY_HH, classRow(KEY_HH, KEY)));
      case "debtContracts" ->
          // ★ E4a：债务的两端必须在 classes 里（v2 spec §八.2）⇒ 这个变异体必须**自带支撑的 classes**：
          //   从 EconomyData.empty() 只改债务表的旧形态在新不变量下无法自洽（本用例只断言
          //   "目标组件进了变更集 + 往返相等"，多带支撑组件不破坏任何断言）。
          //   ★ 2026-10-09 选项 A：同一组支撑也是 householdDebtRefs 那一维的唯一造法（引用表是对账的产物）。
          oneDebtContractTarget(base);
      case "flows" ->
          base.withMeta(Optional.of(meta()))
              .withHouseholdEconomies(Map.of(KEY_HH, classRow(KEY_HH, KEY)))
              .withFlows(Map.of(KEY_HH, flowRow(KEY_HH)));
      // ★ R2 的配额组件：自带 unit 支撑 —— activity 指到 unit、actor == unit.operator（同一件事不许两处拼写），
      //   家户必须在 classes 里；P2-A 起上限是 HouseholdEconomy.laborMilli（这里 60_000 恰好用满）。
      case "allocations" ->
          base.withMeta(Optional.of(meta()))
              .withHouseholdEconomies(Map.of(KEY_HH, classRow(KEY_HH, KEY)))
              .withLaborCommitments(Map.of(ALLOCATION, laborAllocation()));
      // ★ T2 的第 8 个组件：**自带同 operator 的 unit**（跨表守卫要求 relations 键 == 值内 activity == 已存在的
      //   unit id，且 relation.operator() == unit.operator()）。
      //   ★ 夹具是**非派生**值：operator 取 `HOUSEHOLD:house-7`（本文件夹具的 regime 是 `tenant`，
      //   推导值是 `HOUSEHOLD:farm`）⇒ "把 relations 整个按 regime 重新推导"这种坏实现会读到别的值 ⇒ 红。
      case "relations" ->
          base.withMeta(Optional.of(meta()))
              .withRelations(Map.of(FARM_UNIT, relation(FARM_UNIT, NON_DEFAULT_OPERATOR)));
      // ★★ W1 的第 9 个组件（**每区一张**：键从格改成市场 id）：**两种形态各一张**（★ W1 收尾批补持久形态）——
      //   ① 派生形态（键不在区表里、成员格 = {@link #KEY} 那一格、三个商品含一行**明确 0 价**）；
      //   ② 持久形态（键 = 区 {@link #ZONE_EAST}、成员格**为空**、计价币 == 该区法定币 silver）。
      //   ★ 两形态一次落地（{@code withZoneAndItsTable} = "区 + 它的价表"的原子写法；★ W1d 起"缺表的区"其实也
      //     合法 —— 那条守卫已删，见该帮手方法的注 —— 这里仍走原子写法，免得夹具依赖"中间态碰巧合法"）。
      case "marketTables" ->
          withZoneAndItsTable(base, zoneEast(), zoneMarket())
              .withMarketTables(Map.of(MARKET, market(), ZONE_EAST, zoneMarket()));
      // ★★ W1 的第 40 个组件（市场轮统计簿）：**空表与"字段没进变更集"在值层面不可区分** ⇒ 判别力要求读数非空
      //   （★ W1 收尾批：**多区** + 三段窗口各两个商品、三段取互不相同的读数，见 {@link #statsBook()}）。
      case "marketRoundStats" -> base.withMarketRoundStats(statsBook());
      // ★ M2.4 的第 10 个组件：**自带支撑的票**（在途批次没有跨表守卫，但仍要有真实的路线与至少一票，
      //   否则构造期就会拦下"在途必须能追到票"）。
      case "shipments" -> base.withShipments(Map.of(SHIPMENT, shipment()));
      // ★ R3B.1 的第 12 个组件：**自带支撑的产业**（份额指名的 industry 必须存在；键 == 值内 id）。
      case "assetShares" -> base.withOwnershipStakes(Map.of(FARM_SHARE, share()));
      // ★ S3.2 的第 13 个组件：**自带支撑的 unit**（键 = unit id；值内 industry 必须等于 unit.industry）。
      case "operatorConditions" ->
          base.withMeta(Optional.of(meta()))
              .withOperatorConditions(Map.of(FARM_UNIT, operatorCondition()));
      // ★ R3B.2 的第 14 个组件：**自带支撑的产业**（unit 指名的技术模板必须存在；progressDays ≤ cycleDays）。
      // ★ R4-E2 的第 15 个组件：HEX 范围的需求自带一张**登记了该格的市场**（hexRegistered 的口径）。
      case "demands" ->
          base.withMarketTables(Map.of(MARKET, market()))
              .withHouseholdDemands(Map.of(DEMAND, demand()));
      // ★★ E1–E6 的追加组件：每个都自带一个最小自洽实例（引用完整性按"对侧是否提供"分段，
      //   故单组件 target 合法；moneyIssuances 是唯一需要连带 government 支撑的一组）。
      case "classStructures" -> base.withClassStructures(Map.of(STRUCTURE, classStructure()));
      case "classPositions" -> base.withProductionRoles(Map.of(POSITION, classPosition()));
      case "classStandings" -> base.withClassMemberships(Map.of(KEY_HH, classStanding()));
      case "productionOrganizations" ->
          base.withProductionEnterprises(Map.of(ORGANIZATION, productionOrganization()));
      case "assetRules" -> base.withAssetRules(Map.of(ASSET_RULE, assetRule()));
      case "governments" -> base.withGovernments(Map.of(GOVERNMENT, government()));
      case "moneyIssuances" ->
          base.withGovernments(Map.of(GOVERNMENT, government()))
              .withMoneyIssuances(Map.of(ISSUANCE, moneyIssuance()));
      case "pledges" -> base.withPledges(Map.of(PLEDGE, pledge()));
      case "liquidationPolicies" ->
          base.withLiquidationPolicies(Map.of(ASSET_RULE, liquidationPolicy()));
      case "crisisSignals" -> base.withCrisisSignals(Map.of(CRISIS, crisisSignal()));
      case "modeTransitions" -> base.withModeTransitions(Map.of(TRANSITION, modeTransition()));
      case "classShares" -> base.withClassShares(Map.of(CLASS_SHARE, classShare()));
      // ★★ M-A1（2026-10-10）：第 30 个组件 merchantFirms 已退役（运力改为派生量）⇒ 本条不再存在。
      //    组件数随之 37 → 36（见 changeSetHasExactlyThirtySixComponents）。
      // ★ P4a 的第 31 个组件：规则只描述"从谁扣多少"，不落账户；键 == 值内 id 是唯一守卫。
      case "periodicAdjustments" ->
          base.withPeriodicAdjustments(Map.of(PERIODIC_ADJUSTMENT, periodicAdjustment()));
      // ★★ Z1 的第 32 个组件：覆盖值**必须非默认**（题面同款理由——默认值/空表在值层面与"字段没进变更集"
      //   不可区分，判别力会静默流失）；自带支撑产业（守卫要求 key 指向现存产业，命令/载入边界另判配方）。
      // ★★ Z1 的第 33 个组件：自带支撑 unit（键 = unit id；五个字段全部取非零，且与 FARM.cycleDays=120 同域）。
      // ★★★ D-032 T1（测试 owner）：`units` / `outputQuantityOverrides` / `productionEfficiency`
      //   三个分量**已随迁移从 `EconomyData` 与 `EconomyChangeSet` 整体迁出**（唯一权威在 production，I1；
      //   实现账本 B2b/B2c/B5）⇒ 旧分量名不再出现在 `EconomyData` 的 record 组件表里，本 switch 的分量清单
      //   必须同步收口（保留旧标签会让"未登记的组件"这条守卫失去意义）。
      //   判据**没有丢**：三个分量的往返由 production 侧 `ProductionChangeSet`/`ProductionCodec` 承担
      //   （见 `simos-production/src/test/.../change/ProductionRoundTripTest.java` 与 test owner 在 T2
      // 新增的
      //   `ProductionChangeSetOperatorTest`）。
      // ★★ A1（2026-10-08）的第 32 个组件：默认词表只有 silver ⇒ 加一个**非默认**币种再落回（连带保住 silver，
      //   否则既有的 silver-specie 工具会因"币种不在词表里"被构造期拒）。
      case "currencies" -> {
        Map<CurrencyId, CurrencyDef> currencies = new LinkedHashMap<>(base.currencies());
        currencies.put(GOLD.currencyId(), GOLD);
        yield base.withCurrencies(currencies);
      }
      // ★★ A1 的第 33 个组件：新工具的币种必须已在词表里 ⇒ 与上一个 case 同源，连带把 gold 币种一起落上。
      case "moneyInstruments" -> {
        Map<CurrencyId, CurrencyDef> currencies = new LinkedHashMap<>(base.currencies());
        currencies.put(GOLD.currencyId(), GOLD);
        Map<InstrumentId, MoneyInstrument> instruments =
            new LinkedHashMap<>(base.moneyInstruments());
        instruments.put(GOLD_SPECIE.id(), GOLD_SPECIE);
        yield base.withCurrencies(currencies).withMoneyInstruments(instruments);
      }
      // ★★ B2（2026-10-08）的第 34 个组件：区表非空 ⇒ 必须自带"发行政府 + 它的 issuable 含该区法定币"
      //   （说不出谁发行法定币的区不许存在，fail-closed）⇒ 变异体自带一个 silver 发行政府。★ 法定币取 silver
      //   （默认词表里已有）⇒ 不必为了造一个区再去改词表，判别力仍落在 marketZones 这一维上。
      //   ★★ W1c：W1 起"区表 + 它的价表"是**一对不可分的组件**（构造期守卫：每个持久区必须有价表条目、持久区
      //   价表的成员格必须为空、计价币必须 = 区法定币）⇒ 两个单组件 wither 之间**没有合法中间态**：
      //   `withMarketZones` 先调 ⇒ 那个区还没有表（"缺表 = 该区没有任何价格"）；`withMarketTables` 先调 ⇒
      //   带空成员格的条目会被读成派生市场（"派生市场必须有成员格"）。本用例原先是"基础态里没有该区的表" ⇒
      //   撞上守卫（W1c 的第一条红灯）。修法是**自洽**：把两个组件**一次落地**（见 withZoneAndItsTable）。
      case "marketZones" ->
          withZoneAndItsTable(
              base.withGovernments(Map.of(ZONE_GOV, silverIssuingGovernment())),
              zone(),
              zoneMarket());
      // ★★ 2026-10-09 选项 A 的第 35 个组件（家户债务引用**派生索引**）：它是构造期由合同表重建的产物 ⇒
      //   目标只能靠"合同 + 两端家户行"造出来；空表与"字段没进变更集"在值层面不可区分 ⇒ 判别力要求引用表非空。
      case "householdDebtRefs" -> oneDebtContractTarget(base);
      // ★★ F/F2（2026-10-09，用户裁定「甲」）的第 36 个组件（商品运费**基础费**表）：**缺键与"字段没进
      //   变更集"在值层面不可区分** ⇒ 判别力要求表**非空**且基础费**不是缺省值**
      //   （tool 现状缺省 = 3 毫 ⇒ 用 5 造一个明确偏离默认的行）。
      case "commodityFreightBaseMilli" ->
          base.withCommodityFreightBaseMilli(Map.of(new CommodityId("tool"), 5L));
      // ★★ R1（2026-10-09）的第 37 个组件（政府市场授权表）：**空表与"字段没进变更集"在值层面不可区分**
      //   ⇒ 判别力要求表非空；授权体自带**已登记**政府（ZONE_GOV，构造期守卫要求）与商品（GRAIN），过期日晚于生效日。
      case "govMarketMandates" ->
          base.withGovernments(Map.of(ZONE_GOV, householdTreasuryGovernment()))
              .withGovMarketMandates(
                  Map.of(
                      MANDATE,
                      new GovernmentMarketMandate(
                          MANDATE,
                          ZONE_GOV,
                          GRAIN,
                          GovernmentMarketMandate.Side.BUY,
                          1_000L,
                          5L,
                          1L,
                          30L,
                          0L,
                          "round-trip-fixture")));
      // ★★ D-032 C2/W2a/W2b/D4-Ib1：第 32–37 个组件。每个目标都非空，且键 == 值内身份。
      case "productionRosters" -> {
        ProductionRoster value = roster();
        yield base.withProductionRosters(Map.of(value.unit(), value));
      }
      case "productionWageDefaults" -> {
        ProductionWageDefault value = wageDefault();
        yield base.withProductionWageDefaults(Map.of(value.key(), value));
      }
      case "productionWagePostings" -> {
        ProductionWagePosting value = wagePosting();
        yield base.withProductionWagePostings(Map.of(value.key(), value));
      }
      case "wageCarryRemainders" ->
          base.withWageCarryRemainders(
              Map.of(
                  new WageCarryKey(
                      FARM_UNIT, OPERATOR_ROLE, new HouseholdId("hh-wage-carry-roundtrip")),
                  7L));
      case "assetOccupancies" -> {
        AssetOccupancyGrant value = assetOccupancy();
        yield base.withAssetOccupancies(Map.of(value.id(), value));
      }
      case "inventoryReservations" -> {
        InventoryReservation value = inventoryReservation();
        yield base.withInventoryReservations(Map.of(value.id(), value));
      }
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  /**
   * ★ 2026-10-09 选项 A：{@code debtContracts} 与 {@code householdDebtRefs} 两个组件共用的支撑 —— 两端都在 {@code
   * classes} 里的一条粮债。构造期 {@code DebtReferenceReconciler} 据此重建引用表 ⇒ 同一份 target 同时让这两个组件非 Unchanged。
   *
   * <p>★ 为什么必须是同一组支撑：引用表是**派生**的，没有"只改引用表"这种目标（构造期会被对账覆盖回去）。
   */
  private static EconomyData oneDebtContractTarget(EconomyData base) {
    return base.withMeta(Optional.of(meta()))
        .withHouseholdEconomies(
            Map.of(KEY_HH, classRow(KEY_HH, KEY), OTHER_HH, classRow(OTHER_HH, OTHER_KEY)))
        .withDebtContracts(Map.of(D1, debt()));
  }

  private static boolean changedOf(EconomyChangeSet cs, String name) {
    return switch (name) {
      case "meta" -> cs.meta().changed();
      case "classes" -> cs.classes().changed();
      case "debtContracts" -> cs.debtContracts().changed();
      case "flows" -> cs.flows().changed();
      case "allocations" -> cs.allocations().changed();
      case "relations" -> cs.relations().changed();
      case "marketTables" -> cs.marketTables().changed();
      case "shipments" -> cs.shipments().changed();
      case "assetShares" -> cs.assetShares().changed();
      case "operatorConditions" -> cs.operatorConditions().changed();
      case "demands" -> cs.demands().changed();
      case "classStructures" -> cs.classStructures().changed();
      case "classPositions" -> cs.classPositions().changed();
      case "classStandings" -> cs.classStandings().changed();
      case "productionOrganizations" -> cs.productionOrganizations().changed();
      case "assetRules" -> cs.assetRules().changed();
      case "governments" -> cs.governments().changed();
      case "moneyIssuances" -> cs.moneyIssuances().changed();
      case "pledges" -> cs.pledges().changed();
      case "liquidationPolicies" -> cs.liquidationPolicies().changed();
      case "crisisSignals" -> cs.crisisSignals().changed();
      case "modeTransitions" -> cs.modeTransitions().changed();
      case "classShares" -> cs.classShares().changed();
      // ★★ M-A1（2026-10-10）：merchantFirms 已退役 ⇒ 该分量不存在（见 mutate 的同名 note）。
      case "periodicAdjustments" -> cs.periodicAdjustments().changed();
      // ★ A1 / B2 / 选项 A 的四个组件（见 mutate 的同名 case）
      case "currencies" -> cs.currencies().changed();
      case "moneyInstruments" -> cs.moneyInstruments().changed();
      case "marketZones" -> cs.marketZones().changed();
      case "householdDebtRefs" -> cs.householdDebtRefs().changed();
      // ★ F（2026-10-09）的第 36 个组件（见 mutate 的同名 case）
      case "commodityFreightBaseMilli" -> cs.commodityFreightBaseMilli().changed();
      // ★★ R1（2026-10-09）的第 37 个组件（见 mutate 的同名 case）
      case "govMarketMandates" -> cs.govMarketMandates().changed();
      // ★★ W1 的第 40 个组件（见 mutate 的同名 case）
      case "marketRoundStats" -> cs.marketRoundStats().changed();
      case "productionRosters" -> cs.productionRosters().changed();
      case "productionWageDefaults" -> cs.productionWageDefaults().changed();
      case "productionWagePostings" -> cs.productionWagePostings().changed();
      case "wageCarryRemainders" -> cs.wageCarryRemainders().changed();
      case "assetOccupancies" -> cs.assetOccupancies().changed();
      case "inventoryReservations" -> cs.inventoryReservations().changed();
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  private static Set<String> componentNames(Class<? extends Record> type) {
    Set<String> names = new LinkedHashSet<>();
    for (RecordComponent rc : type.getRecordComponents()) {
      names.add(rc.getName());
    }
    return names;
  }

  // ── 夹具（本类自足；codec 测试自带一套，照 ledger 两测试各自展开的先例） ──

  /**
   * ★ <b>把 rulesVersion 标成"已迁移"</b>（{@code pre-modern-v1}）：本文件从 {@code EconomyData.empty()}
   * 逐组件搭新形状， 若不标，构造期会对"classes 非空但 memberships 为空"的中间态自动跑 {@code
   * LegacyHouseholdMigration}（那是给旧档的兜底， 不是本用例要量的东西）。标了之后迁移的第一条触发判据为假，逐组件的目标就是"只改了那一个组件"。
   */
  static EconomyMeta meta() {
    return new EconomyMeta(
        "m1", 0L, OptionalLong.empty(), EconomyMeta.RULES_VERSION_PRE_MODERN_V1, Optional.empty());
  }

  /**
   * 一个合规矩的产业（R3B.2 起是**纯技术模板**的 12 参构造；operator / 周期进度住在 {@link ProductionProcess}
   * 上）：两个槽位各持**劳动投入率上限** （R1.1 起不再是"人口占比"，故**不必合计 1000‰**）。{@code capacityPerUnit}
   * 非空且为正（"单位规模"的锚），各表的值侧都带商品维度。
   */
  static IndustryDefinitionView industry(IndustryId id) {
    List<ClassSlot> slots =
        List.of(new ClassSlot(PEASANT, "贫农", 950), new ClassSlot(LANDLORD, "地主", 900));
    return IndustryViews.industry(
        id,
        "农业",
        new RegimeId("tenant"),
        120L,
        // ★ R3（V7）：产能那一路非空且为正（"单位规模"的锚）；这一行的规模单位 = "1 头耕牛"。
        Map.of(AssetKind.CATTLE, 1L),
        // ★ 必须非空：空 map 与"字段没进变更集"在值层面不可区分（R3 起值侧再带一层商品维度）。
        Map.of(AssetKind.CATTLE, Map.of(GRAIN, 1L)),
        500L,
        7L,
        Map.of(GRAIN, 7L),
        Map.of(AssetKind.CATTLE, Map.of(GRAIN, 1L)),
        slots,
        new AllocationRule.Split(700, 300));
  }

  /** 一个合规矩的生产单元：身份 / operator 由调用方给，modeKey = 产业 id，周期状态取中性值。 */
  static ProductionProcess unit(ProductionUnitId id, ActorRef operator) {
    return new ProductionProcess(
        id,
        FARM,
        ProductionLocation.ofPlan(FARM).orElseThrow(),
        operator,
        FARM.value(),
        0L,
        Map.of(),
        Map.of());
  }

  static CohortKey otherKey() {
    return OTHER_KEY;
  }

  /**
   * ★ 无债务的阶层行：**债务引用完整性**（v2 spec §八.2）要求行内 {@code debts} 的每个 id 都在债务表里， 故"只变异 classes
   * 一个组件"的用例只能用不引用债务的行。
   *
   * <p>★ S1：行 = {@code (id, view, …)} 两件事（键 = 稳定家户身份，view = 当前格/居住/阶层）。
   */
  static HouseholdEconomy classRow(HouseholdId id, CohortKey view) {
    // ★★ H1（K1）：行里没有 goods 了（家户的商品库存住在 actor 切片的 HouseholdInventory / 经济侧的会话工作副本里）。
    return new HouseholdEconomy(
        id, view, 120L, 60000L, 800, 50L, Map.of(GRAIN, 40L), Map.of(GRAIN, 30L), 0L);
  }

  static DebtContract debt() {
    return new DebtContract(
        D1,
        KEY_HH,
        OTHER_HH,
        DebtUnit.commodity(GRAIN),
        DebtTerms.legacyDefault(),
        DebtSource.ORDINARY_LOAN,
        100L,
        0L,
        OptionalLong.empty(),
        OptionalLong.empty(),
        DebtStatus.NORMAL);
  }

  static FlowRow flowRow(HouseholdId id) {
    // ★ R3：income 由标量改成**逐商品**的表（与 consumed 对称）——这里刻意给两种商品，往返要真的带上它。
    // ★ R4：unmetNeed 也变成**逐商品**的表、并多一个与 deaths 对称的 births ⇒ 三种商品维度的账都要带上。
    return new FlowRow(
        id,
        Map.of(GRAIN, 200L, CLOTH, 11L),
        Map.of(GRAIN, 120L),
        10L,
        5L,
        0L,
        0L,
        65L,
        Map.of(GRAIN, 7L, CLOTH, 2L),
        3L,
        4L,
        Map.of(SILVER, 9L),
        Map.of(DebtUnit.commodity(GRAIN).key(), 5L));
  }

  /**
   * ★ R2 的配额夹具：批次 {@link #LOT} 把 60,000 千分劳动供给 {@link #FARM_UNIT}（activity = unit id、actor =
   * unit.operator，R3B.2 的一致性两处逐字相同）。
   */
  static HouseholdLaborCommitment laborAllocation() {
    return new HouseholdLaborCommitment(
        ALLOCATION,
        LOT,
        KEY_HH,
        NON_DEFAULT_OPERATOR,
        FARM_UNIT.value(),
        60_000L,
        1L,
        LaborCommitmentKind.PRODUCTION,
        Optional.of(OPERATOR_ROLE));
  }

  /** ★ R3B.1 的资产份额夹具：键 == 值内 id、industry 存在；键与值都由 {@link #FARM_SHARE} 一处拼写。 */
  static OwnershipStake share() {
    return new OwnershipStake(
        FARM_SHARE,
        FARM,
        AssetKind.CATTLE,
        NON_DEFAULT_OPERATOR,
        NON_DEFAULT_OPERATOR,
        1L,
        AssetRightKind.OWNED,
        FARM_LOCATION);
  }

  /**
   * ★ S3.2 的经营者状态夹具：键 = {@link #FARM_UNIT}，值内 industry = {@link #FARM}（跨表守卫要求两者一致）。
   *
   * <p>★★ <b>P-T4（2026-10-10）：两个收入字段改为逐币表</b>（{@code lastCycleRevenueByCurrency} / {@code
   * cycleRevenueByCurrency}，禁跨币种求和 I-C10）⇒ 夹具按新形状给<b>空表</b>（= 没有任何收入读数）。 ★ 空表也参与往返：两个维度的判别力由"表被写成恒
   * Unchanged 会红"承担（值层面空表与"字段没进变更集"不可区分 —— 但那一条由 {@code
   * everyEconomyDataComponentParticipatesInTheChangeSet} 对 <b>整个</b> operatorConditions 分量的非
   * Unchanged 断言覆盖）。
   */
  static OperatorCondition operatorCondition() {
    return new OperatorCondition(
        FARM,
        OperatorCondition.IndustryStatus.ACTIVE,
        0L, // consecutiveUnsoldCycles
        0L, // consecutiveInputShortfallCycles
        0L, // cashReserveMilli
        0L, // debtPrincipalMilli
        0L, // debtServiceDueMilli
        Map.of(), // ★ P-T4：lastCycleRevenueByCurrency（逐币）
        0L, // lastCycleCostMilli
        0L, // lastCycleNetMilli
        0L, // unsoldStockMilli
        0L, // selfUsableStockMilli
        0L, // consecutiveDebtStressCycles
        0L, // consecutiveSuspendedCycles
        0L, // reopens
        "", // lastReason
        0L, // cycleOfferedQty
        0L, // cycleFilledQty
        0L, // cycleUnfilledQty
        Map.of(), // ★ P-T4：cycleRevenueByCurrency（逐币）
        0L, // cycleOutcompetedActors
        0L, // cycleOutcompetedQty
        0L, // cycleMarketRounds
        0L); // cycleInputShortfallCycles
  }

  /**
   * ★★ <b>H4 的市场夹具</b>（{@code EconomyData} 的第 9 个组件）：一格、单一计价货币 {@link #SILVER}、一张**非空**价表。
   *
   * <p>★ <b>为什么价表非空</b>：{@code Map.of()} 与"这个字段压根没进变更集"在值层面不可区分（同 {@code outputPerUnit} 那条理由）——
   * 空价表会让"把 markets 分量写成恒 Unchanged"这种坏实现**假绿**。
   *
   * <p>★ <b>为什么不逐格建不同的价</b>：本用例量的是"变更集看得见市场表"，不是价格业务；逐格差异留给价格那一轮。
   *
   * <p>★★ <b>W1：成员格住在价表条目里</b>（{@link MarketTable#hexes()}）—— 改动前"市场在哪一格"由键回答（键 = 格），
   * 现在由价表条目的成员格回答（键 = 市场 id）。
   *
   * <p>★★ <b>W1 收尾批（2026-10-11）：三个商品、其中 {@link #TOOL} 是<b>明确 0 价</b></b> —— 单商品/全正价的夹具 分不开"0
   * 价那一行"与"缺这一行"（{@code hasPrice}/{@code isFree} 两条判据），也挡不住"价格表退回标量/只带第一个商品" 这类形状错误。★
   * 本夹具是**派生形态**（{@code marketZones} 为空 ⇒ 成员格非空是它的合法形态）。
   */
  static MarketTable market() {
    return new MarketTable(SILVER, Set.of(KEY.hex()), Map.of(GRAIN, 3L, CLOTH, 5L, TOOL, 0L));
  }

  /**
   * ★★ <b>W1：市场轮统计簿夹具</b>（{@code EconomyData} 的第 40 个组件 {@code marketRoundStats}）：一个市场的三段窗口，
   * 本轮明细与本/上周期读数**都非空**（判别力要求：空窗口与"字段没进变更集"在值层面不可区分）。
   *
   * <p>★★ <b>W1 收尾批（2026-10-11）：三段窗口各带<b>两个商品</b>、且三段取**互不相同**的读数</b> —— 三段是三个独立的
   * Map，单商品/三段同值的夹具下"把其中一段写成另一段 / 把三段都写成 lastRound"这类形状错误仍会假绿（ {@code withRound} 的关账轮转语义正是"本周期 →
   * 上周期"这一维）。逐量量纲见 {@link CommodityRound}。
   *
   * @param day 最近一轮的绝对日
   * @param lastRoundTurnoverMilli 本轮成交额（毫）
   * @param cycleTurnoverMilli 本周期累计成交额（毫）
   * @param previousCycleTurnoverMilli 上周期累计成交额（毫）
   */
  static MarketRoundStats roundStats(
      long day,
      long lastRoundTurnoverMilli,
      long cycleTurnoverMilli,
      long previousCycleTurnoverMilli) {
    return new MarketRoundStats(
        day,
        Map.of(GRAIN, round(1_000L, 900L, 800L, 4L, 100L), CLOTH, round(500L, 480L, 470L, 5L, 30L)),
        lastRoundTurnoverMilli,
        Map.of(
            GRAIN, round(2_000L, 1_800L, 1_600L, 4L, 200L),
            CLOTH, round(900L, 880L, 860L, 6L, 40L)),
        cycleTurnoverMilli,
        Map.of(
            GRAIN, round(3_000L, 2_800L, 2_700L, 5L, 150L),
            CLOTH, round(700L, 690L, 680L, 5L, 20L)),
        previousCycleTurnoverMilli);
  }

  /**
   * ★★ <b>W1 收尾批：<b>多区</b>统计簿</b> —— 两个市场（派生形态的 {@link #MARKET} 与持久形态的 {@link #ZONE_EAST}）
   * 各自成账，日号与三段成交额**逐区不同**。★ 单区夹具下"统计簿只按第一个键成账 / 两个键指向同一份值"这类错误看不出来。 ★ 构造期**不要求**统计簿的键在 {@code
   * marketTables} 里（撤市之后统计仍可读，见 {@code EconomyData} 的口径注）， 故本夹具可单独使用。
   */
  static Map<MarketZoneId, MarketRoundStats> statsBook() {
    return Map.of(
        MARKET, roundStats(5L, 3_200L, 6_400L, 5_601L),
        ZONE_EAST, roundStats(7L, 900L, 1_800L, 1_200L));
  }

  /** ★ W1：一行供需读数（D / S / 成交 / 均价 / 未满足；逐值 ≥ 0 由 {@link CommodityRound} 构造期判死）。 */
  private static CommodityRound round(
      long demandMilli,
      long supplyMilli,
      long tradedMilli,
      long averagePriceMilli,
      long unmetMilli) {
    return new CommodityRound(demandMilli, supplyMilli, tradedMilli, averagePriceMilli, unmetMilli);
  }

  /**
   * ★★ <b>M2.4 的在途夹具</b>（{@code EconomyData} 的第 10 个组件 {@code shipments}）：一条 1 天的路线、一票买方承担损耗的 grain
   * 在途。★ 数量与票面之和取非 0 且相等 —— 构造期就判"批次量 == 各票之和（同一件事不许两处拼写）"。
   */
  static ShipmentBatch shipment() {
    TradeRoute route = new TradeRoute(new HexCoord(0, 0), new HexCoord(1, 0), 1_000L, 1L, 1L, 5);
    return new ShipmentBatch(
        route,
        GRAIN,
        10L,
        11L,
        500L,
        List.of(
            new ShipmentAllocation(
                new ActorRef(ActorKind.ORGANIZATION, FARM.value()),
                new ActorRef(ActorKind.HOUSEHOLD, "house-7"),
                new HexCoord(1, 0),
                500L,
                LossBearer.BUYER)));
  }

  /** ★★ <b>R4-E2 的需求夹具</b>（第 15 个组件）：HEX 范围 ⇒ 自带一张登记了该格的市场（{@code hexRegistered} 接受市场键）。 */
  static HouseholdDemand demand() {
    return new HouseholdDemand(
        DEMAND,
        HouseholdDemand.DemandScope.HEX,
        Optional.empty(),
        Optional.of(KEY.hex()),
        GRAIN,
        HouseholdDemand.DemandKind.RECURRING,
        HouseholdDemand.DemandUnit.TOTAL,
        100L,
        0L,
        -1L,
        0,
        "fixture");
  }

  // ── E1–E6 追加组件的最小自洽夹具（单组件 target 合法；跨表引用按“对侧已提供”分段生效） ──

  static ProductionModeDefinitionView productionMode() {
    return new ProductionModeDefinitionView(MODE, "生产方式", 1, STRUCTURE);
  }

  static ClassStructure classStructure() {
    return new ClassStructure(STRUCTURE, MODE, Map.of(), Map.of());
  }

  static ProductionRole classPosition() {
    return new ProductionRole(
        POSITION,
        MODE,
        "阶层位置",
        ProductionRole.RelationToMeans.DIRECT_LABORER,
        ProductionRole.LaborRole.PROVIDER,
        ProductionRole.SurplusRole.WAGE_EARNER);
  }

  static HouseholdClassMembership classStanding() {
    return new HouseholdClassMembership(KEY_HH, POSITION, POSITION, Map.of(), 0L, 0L, "");
  }

  static ProductionEnterprise productionOrganization() {
    return new ProductionEnterprise(
        ORGANIZATION,
        MODE,
        POSITION,
        Optional.empty(),
        NON_DEFAULT_OPERATOR,
        List.of(),
        List.of(),
        List.of(),
        new Payee.ToActor(NON_DEFAULT_OPERATOR),
        Optional.empty(),
        ProductionEnterprise.Status.SHORTAGE,
        "缺资产");
  }

  static AssetRule assetRule() {
    return new AssetRule(
        ASSET_RULE,
        MODE,
        AssetKind.CATTLE,
        true,
        true,
        10,
        Optional.empty(),
        new TransferRule(true, true, false));
  }

  static Government government() {
    return new Government(
        GOVERNMENT, "world", new ActorRef(ActorKind.GOVERNMENT, "treasury"), Set.of());
  }

  /**
   * ★★ R1（2026-10-09）：{@code govMarketMandates} 那一维的政府行 —— 构造期守卫要求 <b>授权主体国库必须是家户</b>（账户主体只有家户）⇒
   * 本夹具的 treasury 是 HOUSEHOLD actor。
   */
  private static Government householdTreasuryGovernment() {
    return new Government(
        ZONE_GOV,
        "zone-world",
        HouseholdActors.of(KEY_HH),
        Set.of(MoneyVocabulary.SILVER_CURRENCY));
  }

  /**
   * ★ B2：{@code marketZones} 那一维的政府行 —— {@code issuable} 含 {@code silver}。
   *
   * <p>★★ C 批（2026-10-09，用户裁定「法定货币发行者也丢掉」）：市场区**不再记发行政府**，构造期那条 "发行政府必须 issuable 含法定币"的跨表守卫**已退役**
   * ⇒ 本夹具不再与 {@link #zone()} 绑定，只作政府表的一行存在。
   */
  private static Government silverIssuingGovernment() {
    return new Government(
        ZONE_GOV,
        "zone-world",
        new ActorRef(ActorKind.GOVERNMENT, "zone-treasury"),
        Set.of(MoneyVocabulary.SILVER_CURRENCY));
  }

  /** ★ B2：最小自洽的市场区（半径 0 ⇒ 成员只有锚格；法定币 = 默认词表里的 silver）。 */
  private static MarketZone zone() {
    return new MarketZone(
        ZONE, KEY.hex(), 0, Set.of(KEY.hex()), MoneyVocabulary.SILVER_CURRENCY, Map.of());
  }

  /**
   * ★★ <b>W1 收尾批：持久形态价表的区</b>（{@link #ZONE_EAST}）—— 与 {@link #zone()} 同形，锚格换到 {@link
   * #ZONE_EAST_HEX}：它的成员格不能与派生形态价表 {@link #market()} 的成员格（{@link #KEY} 那一格）重叠。
   */
  static MarketZone zoneEast() {
    return new MarketZone(
        ZONE_EAST,
        ZONE_EAST_HEX,
        0,
        Set.of(ZONE_EAST_HEX),
        MoneyVocabulary.SILVER_CURRENCY,
        Map.of());
  }

  /**
   * ★★ <b>W1c：{@link #zone()} 那一份价表</b>（持久形态：**成员格为空** —— 持久区的成员格权威 = {@code
   * MarketZone.hexes}，I22；计价币 = 该区法定币 {@code silver}）。
   *
   * <p>★ 价目取 {@link #market()} 的同值（非空，见那条理由）：判别力仍落在"区表 / 价表各自有没有进变更集"上。
   */
  static MarketTable zoneMarket() {
    return new MarketTable(MoneyVocabulary.SILVER_CURRENCY, Set.of(), Map.of(GRAIN, 3L, CLOTH, 5L));
  }

  /**
   * ★★ <b>W1c：把"市场区 + 它的价表"两个组件一次落地</b>（本文件唯一一处手写 canonical 构造）。
   *
   * <p>★ <b>为什么不能用 wither 链</b>：{@code EconomyData} 的 compact 构造器跑 W1 的跨表守卫（{@code
   * EconomyData.java:1977-2029} 的"每区一张价表"三条），而**每一次** {@code with*()} 都会重新构造并重跑它 ——
   * 两个单组件路径的中间态各自违反一条：{@code withMarketZones(zone)} 时那个区还没有价表 ⇒ "市场区没有价表条目"； {@code
   * withMarketTables(成员格为空的表)} 时它还不是区 ⇒ "派生市场必须有成员格"。 ⇒ 与 {@code EconomyClearRegionHandler} 末尾对
   * {@code classes}+{@code classStandings} 的处理同款： <b>两个互相约束的组件必须在同一次规范构造里改变</b>。
   *
   * <p>★ <b>代价与护栏</b>：这里显式列出全部 37 个组件 ⇒ 将来新增组件时本调用**编译不过**（不是静默丢字段）， 且本文件的 {@code
   * changeSetHasExactlyThirtyOneComponents} 把两侧组件数钉住 —— 两处一起构成提醒。
   *
   * <p>★★ <b>2026-10-11 W1d 更正（W1 收尾批核对代码时发现，本注原先已过期）</b>：上面"两个单组件 wither 之间没有合法 中间态"这条理由**已不成立**
   * —— {@code EconomyData} 构造期那条"每个持久区必须有价表条目"的守卫已按设计书 §4.2 N-4 **删除**（"缺表 =
   * 该区不交易任何商品"）。保留本帮手方法是因为它仍是"区 + 它的价表一次落地"的原子写法（{@code withMarketZonesAndTables}
   * 的同款先例），**不是**因为中间态非法。
   */
  private static EconomyData withZoneAndItsTable(
      EconomyData base, MarketZone zone, MarketTable table) {
    return new EconomyData(
        base.meta(),
        base.classes(),
        base.debtContracts(),
        base.flows(),
        base.allocations(),
        base.relations(),
        // ★★ 两个不可分组件：区表（marketTables）与它的价表条目同一次落地。
        Map.of(zone.zoneId(), table),
        base.shipments(),
        base.assetShares(),
        base.operatorConditions(),
        base.demands(),
        base.classStructures(),
        base.classPositions(),
        base.classStandings(),
        base.productionOrganizations(),
        base.assetRules(),
        base.governments(),
        base.moneyIssuances(),
        base.pledges(),
        base.liquidationPolicies(),
        base.crisisSignals(),
        base.modeTransitions(),
        base.classShares(),
        base.periodicAdjustments(),
        base.currencies(),
        base.moneyInstruments(),
        Map.of(zone.zoneId(), zone),
        base.householdDebtRefs(),
        base.commodityFreightBaseMilli(),
        base.govMarketMandates(),
        base.marketRoundStats(),
        base.productionRosters(),
        base.productionWageDefaults(),
        base.productionWagePostings(),
        base.wageCarryRemainders(),
        base.assetOccupancies(),
        base.inventoryReservations());
  }

  static MoneyIssuanceRecord moneyIssuance() {
    return new MoneyIssuanceRecord(
        ISSUANCE, GOVERNMENT, 0L, 1L, SILVER, 100L, MoneyIssuanceKind.INITIAL_ENDOWMENT, "fixture");
  }

  static Pledge pledge() {
    return new Pledge(PLEDGE, D1, FARM_SHARE, 1L, MODE, 10, Pledge.Status.ACTIVE);
  }

  static LiquidationPolicy liquidationPolicy() {
    return new LiquidationPolicy(
        ASSET_RULE,
        1_000,
        0L,
        LiquidationPolicy.PriceSource.POLICY,
        3L,
        LiquidationPolicy.PayeeRule.CREDITOR_FIRST);
  }

  static HexCrisisSignal crisisSignal() {
    return new HexCrisisSignal(
        CRISIS,
        KEY.hex(),
        HexCrisisSignal.Kind.FOOD,
        1,
        0L,
        Map.of("grain", 1L),
        List.of(),
        List.of(),
        "fixture");
  }

  static ModeTransition modeTransition() {
    return new ModeTransition(
        TRANSITION,
        ORGANIZATION,
        MODE,
        MODE_TARGET,
        1_000,
        0L,
        10L,
        ModeTransition.Status.PENDING,
        "fixture");
  }

  static ClassShare classShare() {
    return new ClassShare(CLASS_SHARE, TRANSITION, KEY_HH, POSITION, 1_000L);
  }

  /**
   * ★★ T2 的关系夹具（R3B.2：键 / activity = unit id；**逐值非派生**）：两条规则把 E4 的两个要点各钉一条 —— 地租**显式**给 {@code
   * (hex, landlord)} cohort、自留由 {@code residualOwner} 表达而不是写一条 {@code SELF_RETENTION}。
   */
  static ProductionRules relation(ProductionUnitId activity, ActorRef operator) {
    return new ProductionRules(
        activity,
        operator,
        null,
        List.of(
            new CompensationRule(
                RuleType.OUTPUT_SHARE,
                new Payee.ToCohort(
                    new CohortKey(new HexCoord(0, 0), ResidenceKind.RURAL, LANDLORD)),
                Pool.GROSS_OUTPUT,
                Weight.NONE,
                300,
                0L,
                Optional.of(GRAIN),
                Optional.empty(),
                10),
            new CompensationRule(
                RuleType.FIXED_IN_KIND_PER_LABOR,
                new Payee.ToCohort(new CohortKey(new HexCoord(0, 0), ResidenceKind.RURAL, PEASANT)),
                Pool.NET_AFTER_INPUTS,
                Weight.LABOR_AMOUNT,
                0,
                144L,
                Optional.of(GRAIN),
                Optional.empty(),
                20)),
        operator);
  }
}
