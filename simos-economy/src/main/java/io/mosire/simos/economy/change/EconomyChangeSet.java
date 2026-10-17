package io.mosire.simos.economy.change;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
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
import io.mosire.simos.economy.api.id.PledgeId;
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
import io.mosire.simos.economy.model.LiquidationPolicy;
import io.mosire.simos.economy.model.MarketRoundStats;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.ModeTransition;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.Pledge;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.model.ProductionWageDefault;
import io.mosire.simos.economy.model.ProductionWagePosting;
import io.mosire.simos.economy.model.WageCarryKey;
import io.mosire.simos.economy.model.WageDefaultKey;
import io.mosire.simos.economy.model.WagePostingKey;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.FieldDelta;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * 经济状态的变更集。**组件与 {@link EconomyData} 的 record 组件一一对应**（当前 35 个；★ D-032 B4 / §13.14.2 裁定十五起
 * <b>没有</b> {@code industries} 这一维 —— 产业定义的权威唯一归 production）：{@code meta} / {@code classes} /
 * {@code debtContracts} / {@code flows} / {@code laborSupply} / {@code allocations} / {@code
 * relations} / {@code markets} / {@code shipments} / {@code assetShares} / {@code
 * operatorConditions} / {@code units} / {@code demands} / {@code modes} / {@code classStructures} /
 * {@code classPositions} / {@code classStandings} / {@code productionOrganizations} / {@code
 * assetRules} + E3 的 {@code governments} / {@code moneyIssuances} + E4a 的 {@code debtContracts} /
 * {@code pledges} + E5a 的 {@code liquidationPolicies} / {@code crisisSignals} + E6a 的 {@code
 * modeTransitions} / {@code classShares} + P4a 的 {@code periodicAdjustments} + Z1 的 {@code
 * outputQuantityOverrides} / {@code productionEfficiency} + A1 的 {@code currencies} / {@code
 * moneyInstruments} + B2 的 {@code marketZones}）。
 *
 * <p>★★ <b>A1（2026-10-08）追加两张货币词表</b>：{@code currencies}（键 = {@link CurrencyId}，值 = {@link
 * CurrencyDef}）与 {@code moneyInstruments}（键 = {@link InstrumentId}，值 = {@link MoneyInstrument}）。 ★
 * <b>改名（只换 displayName）也走 {@code currencies} 这一条 Upsert</b>——它是"I16 改名不动身份"在变更集层的形态： 键（{@code
 * CurrencyId}）逐字不变，只有值里的显示名变，因而任何余额/流水/债务/市场键都不可能被改名碰到。
 *
 * <p>铁律 5：变更集从完整状态类型派生，由 {@code EconomyRoundTripTest} 的**反射枚举**把守——新增状态组件若不进 变更集，那个测试自动红。
 *
 * <p>★★ **E1–E6 追加组件与 {@link EconomyData} 逐条对应**：E1 {@code
 * modes/classStructures/classPositions/classStandings}； E2 {@code
 * productionOrganizations/assetRules}；E3 {@code governments/moneyIssuances}；E4 {@code
 * debtContracts} （替换旧 {@code debts} 槽）/{@code pledges}；E5 {@code
 * liquidationPolicies/crisisSignals}；E6 {@code modeTransitions/classShares}。E6b（GM 经济调整命令）与 E6c（统一
 * dashboard 读口）都只读写既有组件， **零新状态组件**；P10.1 的 {@code merchantFirms}（商号表）已于 2026-10-10 M-A1
 * **退役**（运力改派生量，用户裁定"旧设计和数据类型直接重建不用留"）；P4a 追加 {@code periodicAdjustments} 后，本变更集与 {@link
 * EconomyData} 的组件面逐条对应（上面那份逐条清单就是全表）。
 *
 * <p>★ **差异与重建的语义不在这里**：一律委托 {@link FieldDelta#diff} / {@link FieldDelta#rebuild}（与 {@code
 * MapChangeSet} / {@code SocialChangeSet} / {@code UnitChangeSet} / {@code SdChangeSet} / {@code
 * LedgerChangeSet} 共用同一份机制，R1 守卫把守"全仓恰一份"）。
 *
 * <p>★★ **{@code meta} 是单值组件，用"单键表"投影进同一份机制**：{@code FieldDelta} 是对**表**的差异（键 → 值），而 {@code meta} 是
 * {@code Optional<EconomyMeta>}。若为它另写一份"单值差异"机制，就有了与 {@code FieldDelta} 分叉的第二份实现。 故把 {@code
 * Optional} 投影成至多一行的表（键 = {@link #META_KEY}），diff/rebuild 全走既有机制，再投影回 {@code Optional}。
 * 语义是纯的：{@code 空 → 有值} = {@code Upsert}、{@code 有值 → 空} = {@code Remove}、{@code 有值 → 另一个值} = {@code
 * Upsert}。同 {@code LedgerChangeSet.economyMeta} 的键选择，记入说明。
 *
 * <p>★ **实现 util 的 {@code ChangeSet} 标记接口**（M4 / spec §十）：该接口已收窄为**标记接口**，实现它不带来任何新义务。
 *
 * <p>★ **R2 的两个新组件走同一份机制**（{@code laborSupply} = 每批次有多少可支配劳动、{@code allocations} = 每批次把多少给了谁）：
 * 它们是普通的"键 → 值"表，故 diff/rebuild 一字不用改——rebuild 的键解析器 = 各自的 {@code parse} （{@link PeopleLotId#parse}
 * / {@link LaborAllocationId#parse}），与 {@link CohortKey} 一族同款。
 *
 * <p>★ **T2 的第 8 个组件同款**（{@code relations} = 每个产业一次生产的结算规则）：键 = {@code IndustryId} （已有键反序列化器），值 =
 * {@link ProductionRules}（**深层**带一个 sealed 多态类型 {@code Payee} ⇒ 它的 线格式由类型上的 Jackson
 * 注解把守，见那个接口的类注；此处无须有任何分支）。
 */
// ★★ 退役组件的**具名**兼容（2026-10-09 纠正批）：F 批（f3366545）的组件 {@code commodityFreightPerMille}
//   （商品运费**系数**，乘在整条费率上）已随用户裁定「甲」撤销；本类型现在叫
//   {@code commodityFreightBaseMilli}（商品**基础运费**表）。
//   ★ 为什么必须是**类型级**注解、而不是只在 codec 里摘键：**重放走的是 Core 的第四台 mapper**
//   （{@code Timeline.readChangeSet} → CHANGESET_MAPPER，见
// simos-core/timeline/Timeline.java:115），它不经过
//   EconomyCodec 的整形层 ⇒ 只在 codec 里摘键的话，"打开自己刚写的档"会在重放路径上当场死在
//   UnrecognizedPropertyException（本批探针实测复现：新码读 F 批写的 store 时 readChangeSet 抛）。
//   类型级注解对四台 mapper 一致生效，且**只忽略这一个名字**：{@code @JsonIgnoreProperties} 不等于
//   {@code ignoreUnknown=true}，其余未知字段照旧 fail-closed（漂移信号不丢）。
//   ★ 被忽略 ⇒ 该组件读成 {@code Unchanged} ⇒ apply 保留 base 的表：旧 revision 行里的那一维**整体作废**，
//   基础费回落到现行硬编码分档（与 EconomyCodec 的具名摘除+INFO 同一条语义）。
@JsonIgnoreProperties("commodityFreightPerMille")
public record EconomyChangeSet(
    FieldDelta<EconomyMeta> meta,
    FieldDelta<HouseholdEconomy> classes,
    FieldDelta<DebtContract> debtContracts,
    FieldDelta<FlowRow> flows,
    FieldDelta<HouseholdLaborCommitment> allocations,
    FieldDelta<ProductionRules> relations,
    FieldDelta<MarketTable> marketTables,
    FieldDelta<ShipmentBatch> shipments,
    FieldDelta<OwnershipStake> assetShares,
    FieldDelta<OperatorCondition> operatorConditions,
    // ★★★ B2b（§13.11.2 裁定六）+ B2c（§13.12.2 裁定八）：`units` / `candidates` /
    //   `outputQuantityOverrides` / `productionEfficiency`
    //   四个组件随 {@link EconomyData} 的同名组件一起从本变更集删除 —— 它们的唯一权威在 production
    //   （{@code production.change.ProductionChangeSet} 携带这些维度的差异，与本次 Advance 的 economy 变更集
    //   在同一条 Revision 里原子提交）。economy 不再有"第二份可独立修改的实例/定义/覆盖/效率"。
    FieldDelta<HouseholdDemand> demands,
    FieldDelta<ClassStructure> classStructures,
    FieldDelta<ProductionRole> classPositions,
    FieldDelta<HouseholdClassMembership> classStandings,
    FieldDelta<ProductionEnterprise> productionOrganizations,
    FieldDelta<AssetRule> assetRules,
    FieldDelta<Government> governments,
    FieldDelta<MoneyIssuanceRecord> moneyIssuances,
    FieldDelta<Pledge> pledges,
    FieldDelta<LiquidationPolicy> liquidationPolicies,
    FieldDelta<HexCrisisSignal> crisisSignals,
    FieldDelta<ModeTransition> modeTransitions,
    FieldDelta<ClassShare> classShares,
    FieldDelta<HouseholdPeriodicAdjustment> periodicAdjustments,
    // ── A1（2026-10-08）货币词表的两张表（与 EconomyData 的两个新组件一一对应，铁律 5）──
    FieldDelta<CurrencyDef> currencies,
    FieldDelta<MoneyInstrument> moneyInstruments,
    // ── B2（2026-10-08）市场区的持久状态（与 EconomyData 的第 36 个组件一一对应，铁律 5）──
    FieldDelta<MarketZone> marketZones,
    // ── 2026-10-09 选项 A：家户债务引用派生索引（与 EconomyData 的第 37 个组件一一对应，铁律 5）──
    //   键 = HouseholdDebtReference（家户@合同）的规范串，值 = 标记位 TRUE。★ 拆表的目的就是让这里每天只出现
    //   **真正变化的引用对**（改前那 3.7KB/户的引用列表是随 classes 的整行 Upsert 一起被重写的）。
    FieldDelta<Boolean> householdDebtRefs,
    // ── 第 38 个组件：商品**基础运费**表（与 EconomyData 的第 38 个组件一一对应，铁律 5）──────────────
    //   键 = CommodityId 的裸值（toString），值 = 该商品的**基础运费**（毫计价货币/商品单位/程，值域 ≥ 0）。
    //   ★★ 值类型是 **Long 而不是 Map**：表本身就住在 EconomyData 里，这里只需逐商品的 Upsert ⇒ 线格式的键仍是 String
    //   （HouseholdDebtReference 同款），于是**第四台 mapper（Timeline.readChangeSet）不需要任何 CommodityId 键
    //   反序列化器**就能读回重放（debts/issuingGov 两次的教训：类型侧注解缺一不可，而"根本不引入领域键类型"比"补注册"更稳）。
    FieldDelta<Long> commodityFreightBaseMilli,
    // ── R1（第 39 个组件）：政府市场授权表（与 EconomyData 的第 39 个组件一一对应，铁律 5）────────────
    //   键 = MarketMandateId 的裸值（toString），值 = 授权行（含累计成交 filledMilli）。
    //   ★ 日结算会写它（成交累加 + 耗尽/到期清除）⇒ 每 tick 只落**真正变化的那几行**（Upsert/Remove）。
    FieldDelta<GovernmentMarketMandate> govMarketMandates,
    // ── ★★ W1（第 40 个组件）：市场轮统计簿（键 = 市场 id；纯读数组件）─────────────────────────
    FieldDelta<MarketRoundStats> marketRoundStats,
    // ── ★★★ D-032 C2（D2 §5.3）：第 32 个组件 —— 生产参与名册（键 = ProductionUnitId）────────────
    //   与 EconomyData 的第 32 个组件一一对应（铁律 5）。键解析器 = ProductionUnitId::parse（与 toString 互逆）。
    FieldDelta<ProductionRoster> productionRosters,
    // ── ★★★ D-032 W2a：第 33/34 个组件（与 EconomyData 一一对应，铁律 5）──────────────────────
    //   键解析器 = WageDefaultKey::parse / WagePostingKey::parse（各自的 toString 与 parse 互逆）。
    //   空表/Unchanged 均为合法中性态；第 35 个 wageCarryRemainders 归 W2b，不在此批。
    FieldDelta<ProductionWageDefault> productionWageDefaults,
    FieldDelta<ProductionWagePosting> productionWagePostings,
    // ── ★★★ D-032 W2b：第 35 个组件 —— 逐 (unit, role, worker) 工资进位余数（0..999）──
    FieldDelta<Long> wageCarryRemainders)
    implements ChangeSet {

  /** {@code meta} 投影成表时的唯一键（与字段同名，便于读字节时一眼对上）。 */
  private static final String META_KEY = "meta";

  public EconomyChangeSet {
    // ★ **旧档兼容**（§11 的口径，照 {@code LedgerChangeSet}）：升级前落盘的这条变更集没有这些
    //   键时，Jackson 绑成 null ⇒ 缺省 = Unchanged（"一字未动"），**此处不抛** —— 读成 null 的话 isEmpty()
    //   与 apply 都会 NPE。方向是 fail-closed：旧档没提该组件，就是没动它。
    if (meta == null) {
      meta = new FieldDelta.Unchanged<>();
    }
    if (classes == null) {
      classes = new FieldDelta.Unchanged<>();
    }
    if (debtContracts == null) {
      debtContracts = new FieldDelta.Unchanged<>();
    }
    if (flows == null) {
      flows = new FieldDelta.Unchanged<>();
    }
    if (allocations == null) {
      allocations = new FieldDelta.Unchanged<>();
    }
    // ★ 第 8 个组件（T2）：同一口径（旧档没提该组件，就是没动它）。
    if (relations == null) {
      relations = new FieldDelta.Unchanged<>();
    }
    // ★ 第 9 个组件（H4）：同一口径（旧档没提该组件，就是没动它）。
    if (marketTables == null) {
      marketTables = new FieldDelta.Unchanged<>();
    }
    // ★ 第 10 个组件（M2.4）：同一口径（旧档没提该组件，就是没动它）。
    if (shipments == null) {
      shipments = new FieldDelta.Unchanged<>();
    }
    // ★ S1 的两个新组件：同一口径（旧档没提该组件，就是没动它）。
    if (assetShares == null) {
      assetShares = new FieldDelta.Unchanged<>();
    }
    // ★ 第 13 个组件（S3.2）：同一口径（旧档没提该组件，就是没动它）。
    if (operatorConditions == null) {
      operatorConditions = new FieldDelta.Unchanged<>();
    }
    // ★★ R4-E2 第 15 个组件（需求账本）：同一口径（旧档没提该组件，就是没动它）。
    //   ★ B2b/B2c：第 14 个组件 `units` 与第 16 个组件 `candidates` 的变更集维已删除（权威在 production）。
    if (demands == null) {
      demands = new FieldDelta.Unchanged<>();
    }
    // ★★ E1 的四个新组件：同一口径（旧变更集没提该组件，就是没动它）。
    if (classStructures == null) {
      classStructures = new FieldDelta.Unchanged<>();
    }
    if (classPositions == null) {
      classPositions = new FieldDelta.Unchanged<>();
    }
    if (classStandings == null) {
      classStandings = new FieldDelta.Unchanged<>();
    }
    // ★★ E2 的两个新组件：同一口径（旧变更集没提该组件，就是没动它）。
    if (productionOrganizations == null) {
      productionOrganizations = new FieldDelta.Unchanged<>();
    }
    if (assetRules == null) {
      assetRules = new FieldDelta.Unchanged<>();
    }
    // ★★ E3 的第 23/24 个组件：同一口径（旧变更集没提该组件，就是没动它）。
    if (governments == null) {
      governments = new FieldDelta.Unchanged<>();
    }
    if (moneyIssuances == null) {
      moneyIssuances = new FieldDelta.Unchanged<>();
    }
    // ★★ E4a 第 25 个组件：同一口径（旧变更集没提该组件，就是没动它）。
    if (pledges == null) {
      pledges = new FieldDelta.Unchanged<>();
    }
    // ★★ E5a 第 26/27 个组件：同一口径（旧变更集没提该组件，就是没动它）。
    if (liquidationPolicies == null) {
      liquidationPolicies = new FieldDelta.Unchanged<>();
    }
    if (crisisSignals == null) {
      crisisSignals = new FieldDelta.Unchanged<>();
    }
    // ★★ E6a 第 28/29 个组件：同一口径（旧变更集没提该组件，就是没动它）。
    if (modeTransitions == null) {
      modeTransitions = new FieldDelta.Unchanged<>();
    }
    if (classShares == null) {
      classShares = new FieldDelta.Unchanged<>();
    }
    // ★★ P4a 第 31 个组件（周期家户扣增规则表）：旧变更集没提该组件，就是没动它。
    if (periodicAdjustments == null) {
      periodicAdjustments = new FieldDelta.Unchanged<>();
    }
    // ★★ R1 第 39 个组件（政府市场授权表）：旧变更集没提该组件，就是没动它。
    if (govMarketMandates == null) {
      govMarketMandates = new FieldDelta.Unchanged<>();
    }
    if (marketRoundStats == null) {
      marketRoundStats = new FieldDelta.Unchanged<>();
    }
    // ★★ D-032 C2：名册维（旧档没提该组件，就是没动它）。
    if (productionRosters == null) {
      productionRosters = new FieldDelta.Unchanged<>();
    }
    // ★★ D-032 W2a：工资 default/posting 两维（旧档/旧变更集没提该组件，就是没动它）。
    if (productionWageDefaults == null) {
      productionWageDefaults = new FieldDelta.Unchanged<>();
    }
    if (productionWagePostings == null) {
      productionWagePostings = new FieldDelta.Unchanged<>();
    }
    // ★★★ D-032 W2b：进位维（旧档/旧变更集没提该组件，就是没动它）。
    if (wageCarryRemainders == null) {
      wageCarryRemainders = new FieldDelta.Unchanged<>();
    }
    // ★★ B2b：Z1 第 32/33 个组件（产品产出数量覆盖表 / 生产效率累计与余数表）的变更集维已删除
    //   （两维的唯一权威在 production 的 {@code ProductionChangeSet}）。
    // ★★ A1 的第 34/35 个组件（货币词表两张表）：旧变更集没提该组件，就是没动它
    //   （旧档读到 null ⇒ Unchanged，旧世界词表照旧走 EconomyData 的旧世界默认归一）。
    if (currencies == null) {
      currencies = new FieldDelta.Unchanged<>();
    }
    if (moneyInstruments == null) {
      moneyInstruments = new FieldDelta.Unchanged<>();
    }
    // ★★ B2 第 36 个组件（市场区表）：旧变更集没提该组件，就是没动它
    //   （旧档读到 null ⇒ Unchanged；旧世界区表照旧为空 ⇒ 市场区走派生路径）。
    if (marketZones == null) {
      marketZones = new FieldDelta.Unchanged<>();
    }
    // ★★ 2026-10-09 选项 A 第 37 个组件（家户债务引用派生索引）：旧变更集没提该组件，就是没动它
    //   （旧档读到 null ⇒ Unchanged；引用表由构造期对账从合同表重建，不依赖变更集携带它）。
    if (householdDebtRefs == null) {
      householdDebtRefs = new FieldDelta.Unchanged<>();
    }
    // ★★ 第 38 个组件（商品基础运费表）：旧变更集没提该组件，就是没动它
    //   （旧档读到 null ⇒ Unchanged；旧世界表为空 ⇒ 运费逐值不变）。
    if (commodityFreightBaseMilli == null) {
      commodityFreightBaseMilli = new FieldDelta.Unchanged<>();
    }
  }

  /** 逐组件比较。全相等 ⇒ **全 Unchanged**（不是空对象）。 */
  public static EconomyChangeSet between(EconomyData base, EconomyData target) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(target, "target");
    return new EconomyChangeSet(
        FieldDelta.diff(metaTable(base.meta()), metaTable(target.meta())),
        FieldDelta.diff(base.classes(), target.classes()),
        FieldDelta.diff(base.debtContracts(), target.debtContracts()),
        FieldDelta.diff(base.flows(), target.flows()),
        FieldDelta.diff(base.allocations(), target.allocations()),
        FieldDelta.diff(base.relations(), target.relations()),
        FieldDelta.diff(base.marketTables(), target.marketTables()),
        FieldDelta.diff(base.shipments(), target.shipments()),
        FieldDelta.diff(base.assetShares(), target.assetShares()),
        FieldDelta.diff(base.operatorConditions(), target.operatorConditions()),
        FieldDelta.diff(base.demands(), target.demands()),
        FieldDelta.diff(base.classStructures(), target.classStructures()),
        FieldDelta.diff(base.classPositions(), target.classPositions()),
        FieldDelta.diff(base.classStandings(), target.classStandings()),
        FieldDelta.diff(base.productionOrganizations(), target.productionOrganizations()),
        FieldDelta.diff(base.assetRules(), target.assetRules()),
        FieldDelta.diff(base.governments(), target.governments()),
        FieldDelta.diff(base.moneyIssuances(), target.moneyIssuances()),
        FieldDelta.diff(base.pledges(), target.pledges()),
        FieldDelta.diff(base.liquidationPolicies(), target.liquidationPolicies()),
        FieldDelta.diff(base.crisisSignals(), target.crisisSignals()),
        FieldDelta.diff(base.modeTransitions(), target.modeTransitions()),
        FieldDelta.diff(base.classShares(), target.classShares()),
        FieldDelta.diff(base.periodicAdjustments(), target.periodicAdjustments()),
        FieldDelta.diff(base.currencies(), target.currencies()),
        FieldDelta.diff(base.moneyInstruments(), target.moneyInstruments()),
        FieldDelta.diff(base.marketZones(), target.marketZones()),
        FieldDelta.diff(base.householdDebtRefs(), target.householdDebtRefs()),
        // ★★ 第 38 个组件：商品基础运费表（键 = CommodityId 裸值；值 = 基础运费，毫/单位/程）。
        FieldDelta.diff(base.commodityFreightBaseMilli(), target.commodityFreightBaseMilli()),
        // ★★ R1（第 39 个组件）：政府市场授权表（成交累加与耗尽/到期清除写它，铁律 5 的一一对应）。
        FieldDelta.diff(base.govMarketMandates(), target.govMarketMandates()),
        FieldDelta.diff(base.marketRoundStats(), target.marketRoundStats()),
        FieldDelta.diff(base.productionRosters(), target.productionRosters()),
        FieldDelta.diff(base.productionWageDefaults(), target.productionWageDefaults()),
        FieldDelta.diff(base.productionWagePostings(), target.productionWagePostings()),
        FieldDelta.diff(base.wageCarryRemainders(), target.wageCarryRemainders()));
  }

  /** 逐组件重建（铁律 5 的原文）：{@code apply(between(base, target), base).equals(target)}。 */
  public static EconomyData apply(EconomyChangeSet cs, EconomyData base) {
    Objects.requireNonNull(cs, "cs");
    Objects.requireNonNull(base, "base");
    return new EconomyData(
        metaOf(FieldDelta.rebuild(metaTable(base.meta()), cs.meta(), Function.identity())),
        // ★★★ D-032 B4（§13.14.2 裁定十五）：A 批续做的"占位带过 base.industries()"一行**已删** ——
        //   组件本身已从 EconomyData 删除（定义权威唯一归 production），本变更集从来没有这一维。
        FieldDelta.rebuild(base.classes(), cs.classes(), EconomyChangeSet::householdId),
        FieldDelta.rebuild(base.debtContracts(), cs.debtContracts(), DebtContractId::parse),
        FieldDelta.rebuild(base.flows(), cs.flows(), EconomyChangeSet::householdId),
        FieldDelta.rebuild(base.allocations(), cs.allocations(), LaborAllocationId::parse),
        FieldDelta.rebuild(base.relations(), cs.relations(), ProductionUnitId::parse),
        FieldDelta.rebuild(base.marketTables(), cs.marketTables(), MarketZoneId::parse),
        FieldDelta.rebuild(base.shipments(), cs.shipments(), ShipmentId::parse),
        FieldDelta.rebuild(base.assetShares(), cs.assetShares(), AssetShareId::parse),
        FieldDelta.rebuild(
            base.operatorConditions(), cs.operatorConditions(), ProductionUnitId::parse),
        FieldDelta.rebuild(base.demands(), cs.demands(), DemandId::parse),
        // ★★★ B5（§13.15.2 裁定十八）：第 17 个组件 `modes` 已从 EconomyData 删除 ⇒ 本处不再有
        //   "逐值带过 base 的表"这一腿（本变更集从来没有 modes 组件 —— 生产方式定义唯一归 production）。
        FieldDelta.rebuild(base.classStructures(), cs.classStructures(), ClassStructureId::parse),
        FieldDelta.rebuild(base.classPositions(), cs.classPositions(), ClassPositionId::parse),
        FieldDelta.rebuild(
            base.classStandings(), cs.classStandings(), EconomyChangeSet::householdId),
        FieldDelta.rebuild(
            base.productionOrganizations(),
            cs.productionOrganizations(),
            ProductionOrganizationId::parse),
        FieldDelta.rebuild(base.assetRules(), cs.assetRules(), AssetRuleId::parse),
        FieldDelta.rebuild(base.governments(), cs.governments(), GovernmentId::parse),
        FieldDelta.rebuild(base.moneyIssuances(), cs.moneyIssuances(), MoneyIssuanceId::parse),
        FieldDelta.rebuild(base.pledges(), cs.pledges(), PledgeId::parse),
        FieldDelta.rebuild(
            base.liquidationPolicies(), cs.liquidationPolicies(), AssetRuleId::parse),
        FieldDelta.rebuild(base.crisisSignals(), cs.crisisSignals(), CrisisSignalId::parse),
        FieldDelta.rebuild(base.modeTransitions(), cs.modeTransitions(), ModeTransitionId::parse),
        FieldDelta.rebuild(base.classShares(), cs.classShares(), ClassShareId::parse),
        FieldDelta.rebuild(
            base.periodicAdjustments(),
            cs.periodicAdjustments(),
            PeriodicHouseholdAdjustmentId::parse),
        // ★★ B2b：Z1 的两维（outputQuantityOverrides / productionEfficiency）已从本变更集删除 ——
        //   apply 不再重建它们（权威在 production 的切片与变更集里）。
        // ★★ A1：货币词表的两张表（键 = 币种身份 / 工具身份）。
        FieldDelta.rebuild(base.currencies(), cs.currencies(), CurrencyId::parse),
        FieldDelta.rebuild(base.moneyInstruments(), cs.moneyInstruments(), InstrumentId::parse),
        // ★★ B2：市场区表（键 = 区身份；成员格/法定币/发行者/区级汇率都在值里）。
        FieldDelta.rebuild(base.marketZones(), cs.marketZones(), MarketZoneId::parse),
        // ★★ 2026-10-09 选项 A：引用表的键解析器 = HouseholdDebtReference::parse（与 toString 互逆）。
        FieldDelta.rebuild(
            base.householdDebtRefs(), cs.householdDebtRefs(), HouseholdDebtReference::parse),
        // ★★ 第 38 个组件：商品基础运费表（键解析器 = CommodityId::parse，与 toString 互逆；值 = Long 直读）。
        FieldDelta.rebuild(
            base.commodityFreightBaseMilli(), cs.commodityFreightBaseMilli(), CommodityId::parse),
        FieldDelta.rebuild(
            base.govMarketMandates(), cs.govMarketMandates(), MarketMandateId::parse),
        FieldDelta.rebuild(base.marketRoundStats(), cs.marketRoundStats(), MarketZoneId::parse),
        // ★★ D-032 C2：名册维（键解析器 = ProductionUnitId::parse）。
        FieldDelta.rebuild(
            base.productionRosters(), cs.productionRosters(), ProductionUnitId::parse),
        // ★★ D-032 W2a：候选 default / 执行期 posting 两维（键解析器各自 toString/parse 互逆）。
        FieldDelta.rebuild(
            base.productionWageDefaults(), cs.productionWageDefaults(), WageDefaultKey::parse),
        FieldDelta.rebuild(
            base.productionWagePostings(), cs.productionWagePostings(), WagePostingKey::parse),
        FieldDelta.rebuild(
            base.wageCarryRemainders(), cs.wageCarryRemainders(), WageCarryKey::parse));
  }

  /** 是否所有组件都未变。 */
  public boolean isEmpty() {
    return !(meta.changed()
        || classes.changed()
        || debtContracts.changed()
        || flows.changed()
        || allocations.changed()
        || relations.changed()
        || marketTables.changed()
        || shipments.changed()
        || assetShares.changed()
        || operatorConditions.changed()
        || demands.changed()
        || classStructures.changed()
        || classPositions.changed()
        || classStandings.changed()
        || productionOrganizations.changed()
        || assetRules.changed()
        || governments.changed()
        || moneyIssuances.changed()
        || pledges.changed()
        || liquidationPolicies.changed()
        || crisisSignals.changed()
        || modeTransitions.changed()
        || classShares.changed()
        || periodicAdjustments.changed()
        || currencies.changed()
        || moneyInstruments.changed()
        || marketZones.changed()
        || householdDebtRefs.changed()
        || commodityFreightBaseMilli.changed()
        || govMarketMandates.changed()
        || marketRoundStats.changed()
        || productionRosters.changed()
        || productionWageDefaults.changed()
        || productionWagePostings.changed()
        || wageCarryRemainders.changed());
  }

  /** {@code Optional<EconomyMeta>} → 至多一行的表（键固定为 {@link #META_KEY}）。 */
  private static Map<String, EconomyMeta> metaTable(Optional<EconomyMeta> meta) {
    return meta.map(value -> Map.of(META_KEY, value)).orElseGet(Map::of);
  }

  /** 上一条的逆：单键表 → {@code Optional}。空表 ⇒ 未激活。 */
  private static Optional<EconomyMeta> metaOf(Map<String, EconomyMeta> table) {
    return Optional.ofNullable(table.get(META_KEY));
  }

  /**
   * ★★ S1：变更集里的键是 {@code toString()} 的产物 ⇒ 旧档的 CohortKey 串（{@code 0_0|rural|poor}，含 {@code |} 且非
   * legacy- 前缀）必须在**重建时**映射成 {@link HouseholdIds#ofLegacy}，否则它与快照迁移后的 classes 键对不上。 已是 {@code
   * legacy-} 前缀的新键 ⇒ 原样 parse（幂等）。
   */
  private static HouseholdId householdId(String text) {
    if (text != null && !text.startsWith(HouseholdIds.LEGACY_PREFIX) && text.indexOf('|') >= 0) {
      return HouseholdIds.ofLegacy(CohortKey.parse(text));
    }
    return HouseholdId.parse(text);
  }
}
