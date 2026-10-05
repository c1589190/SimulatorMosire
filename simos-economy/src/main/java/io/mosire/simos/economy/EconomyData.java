package io.mosire.simos.economy;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.id.AssetRuleId;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CandidateId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ClassShareId;
import io.mosire.simos.economy.api.id.ClassStructureId;
import io.mosire.simos.economy.api.id.CrisisSignalId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ModeTransitionId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import io.mosire.simos.economy.api.id.PledgeId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.migrate.DebtReferenceReconciler;
import io.mosire.simos.economy.migrate.LegacyHouseholdMigration;
import io.mosire.simos.economy.model.AssetRule;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassPosition;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassShare;
import io.mosire.simos.economy.model.ClassStanding;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.DemandEntry;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.LiquidationPolicy;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.MerchantFirm;
import io.mosire.simos.economy.model.ModeTransition;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.Pledge;
import io.mosire.simos.economy.model.ProductionCandidate;
import io.mosire.simos.economy.model.ProductionMode;
import io.mosire.simos.economy.model.ProductionOrganization;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 经济切片的完整状态树（新经济设计 §3 逐字）：激活元信息 + 产业表 + 阶层行 + 债务表 + 周期流水 + **劳动供给表 + 劳动分配表**（R2） + 生产关系表（T2）+
 * **市场表**（H4）。
 *
 * <p>★★ **{@code meta} 为空 {@code Optional} = 经济未激活**（§3.3 + §6.6）：未激活时日制世界仍可沿用简化人口查询（人口查询走 {@code
 * social}），但**日推进仍要求切片在场**。空快照 ≠ 已激活。
 *
 * <p>★★ **本切片只写自己的数据**（§2 + §6.1）：商品/货币/人口的总量守恒由**命令层/协调器**校验，**不落成第二份真相**——这里只有状态，
 * 没有"校验结论"。任何经济公式（产量/分配/税/市场盈亏）都不在本切片（§八 R1 行："模块化、无公式"）。
 *
 * <p>★ **三十个组件与 {@link io.mosire.simos.economy.change.EconomyChangeSet} 的三十个组件一一对应**（铁律 5）：
 * 新增状态组件必须同时进变更集，由 {@code EconomyRoundTripTest} 的反射枚举把守。
 *
 * <p>★★ **E1–E6 追加组件清单**（逐阶段；数字是本记录全表的组件序号/个数，E6b/E6c 零新状态组件）：
 *
 * <ul>
 *   <li>E1（第 17–20 个，4 个）：{@code modes} / {@code classStructures} / {@code classPositions} / {@code
 *       classStandings}；
 *   <li>E2（第 21–22 个，2 个）：{@code productionOrganizations} / {@code assetRules}；
 *   <li>E3（第 23–24 个，2 个）：{@code governments} / {@code moneyIssuances}；
 *   <li>E4（第 25 个，另替换第 4 个组件的旧 {@code debts} 槽为 {@code debtContracts}）：{@code pledges}；
 *   <li>E5（第 26–27 个，2 个）：{@code liquidationPolicies} / {@code crisisSignals}；
 *   <li>E6（第 28–29 个，2 个）：{@code modeTransitions} / {@code classShares}；
  *   <li>P10.1（第 30 个，1 个）：{@code merchantFirms}（商号表，见 {@link MerchantFirm}；键 = 值内 organizationId）。
 * </ul>
 *
 * E6b（GM 经济调整命令与预览审计）与 E6c（统一 dashboard 读口）都只读/写既有组件，**不追加新状态组件**， 故本记录的全表组件数在 E6 之后仍为 **29 个组件**；★
 * <b>P10.1 追加第 30 个组件 {@code merchantFirms}</b>（商号表，见 {@link MerchantFirm}），逐条对应关系见 {@link EconomyChangeSet}。
 *
 * <p>★★ **跨表同键不变式**（§6.2 的身份部分）：{@code classes} 的每个键必须等于其 {@link ClassRow#key()}；{@code flows}
 * 的每个键必须等于其 {@link FlowRow#key()}；{@code laborSupply} / {@code allocations} 同理各自等于行内的 group / id。
 * 否则同一份身份就有两处可能不一致的记录。
 *
 * <p>★★ **R2：本阶段最重要的不变量在这里判死**（第三阶段设计稿 §四）：
 *
 * <pre>
 * Σ_{a ∈ allocations(group)} a.laborMilli  ≤  availableLabor(supply(group))
 * </pre>
 *
 * 它**必须**在构造期判，而不是在结算里"顺手算对"：劳动是**可分配但不能凭空重复**的资源（本轮的目标原话），而"同一批人被两个产业各算一次满额" 正是设计稿 §一.2
 * 实测出的空洞。判在构造期 ⇒ 任何一条路径（命令、旧档读入、夹具、将来的协调器）都不可能造出"配额超过可支配劳动"的状态—— 那正是本仓栽过的同族教训（{@code progressDays
 * == cycleDays} 与 {@code WageFirst}：模型允许的状态，结算与用例都得处理）。
 *
 * <p>★ **两条配套的结构判据**（同上，都判在构造期）：
 *
 * <ol>
 *   <li>**actor ↔ 产业 的对应关系**：结算按 {@code actor.id()} 把配额归到产业（"这一格的劳动被哪个产业占了多少"的唯一判据）⇒ 产业型主体（{@link
 *       ActorKind#ESTATE}/{@link ActorKind#WORKSHOP}）**必须**指名一个已存在的产业，而非产业型主体的 id **不得**与任何产业 id
 *       撞名（撞名 ⇒ 家户的配额被静默算进那个产业）。★ **R3 起 {@link ActorKind#HOUSEHOLD} 是自由档**：农村家庭纺织是"家户
 *       自己承担的一个生产过程"（spec §四）⇒ 家户的 actor id 可以命名一个产业（那时配额照进该产业的 {@code cycleLaborMilli}），
 *       也可以不命名（那时它只是消费主体）；其余四个非产业型种类仍不许撞名；
 *   <li>**每条配额必须有一份同期的供给记录**（{@code (group, period)} 命中）：没有供给记录的配额**没有上限**——
 *       那等于把"不能凭空重复"这条判据本身留成后门。
 * </ol>
 *
 * <p>★★ **缺键 = 空**（§11 的旧档兼容口径，照 {@code LedgerData} 的先例）：全部组件在本切片**都是新引入的**， 故 Jackson 绑成 null
 * 时一律收成空表 / 未激活，**此处不抛** —— 抛了等于"旧档全部读不回来"。方向是 fail-closed： 缺键 ⇒
 * 没有产业/没有阶层/没有债务/没有流水/没有劳动供给与配额/没有生产关系/<b>没有市场</b>/未激活。
 *
 * <p>★ **三十张表都保序不可变**：{@code LinkedHashMap} + {@code Collections.unmodifiableMap}，**绝不用 {@code
 * Map.copyOf}**——它的迭代序不是内容的纯函数（字节级往返因此不成立）。冻结那一步**写在字段赋值处** （SpotBugs 的 {@code EI_EXPOSE_REP}
 * 不做跨过程分析，只认它看得见的包装）。
 *
 * <p>★★ **{@code relations} 是第 8 个组件**（S1 阶段 4+5 Task 2；计划 R3）：键 = {@code IndustryId}（ {@link
 * ProductionRelation} 不另造 id —— 身份就是它结算的那个 {@code activity}，铁律 1），值 = 一次生产的结算规则。 ★ 两条**跨表守卫**：
 *
 * <ol>
 *   <li>每个键**必须**是该格上已存在的产业（"{@code 关系指名的产业不存在}"）——否则结算时按 id 取不到产业；
 *   <li>{@code relations[k].operator()} **必须**等于 {@code industries[k].operator()}：**同一件事不许有两处拼写**
 *       （"谁经营"若能在关系表里另写一遍，两边不一致时没有任何一处能判谁对）；键还**必须**等于 {@code ProductionRelation.activity()}（同
 *       {@code classes}/{@code flows} 的"键 == 值内 key"口径）。
 * </ol>
 *
 * <p>★ <b>S1 起 {@code classes}/{@code flows} 的键都是稳定家户身份 {@link HouseholdId}</b>（旧档的 {@link
 * CohortKey} 由 {@code EconomyCodec} 读入时映射成 {@code HouseholdIds.ofLegacy}）：键不再随地点/阶层变化（铁律 1），
 * 行的当前视图住在 {@code ClassRow.view}。⇒ "这个产业有哪些行"不再由键的产业段回答，而由**劳动配额表**推（{@code 旧结算引擎（R3a
 * 已删除）.householdKeysOf}，唯一拼写点）—— 一个家户给两个产业出劳动时，它<b>只有一行</b>（V9/I1.2）。
 *
 * <p>★★ **{@code markets} 是第 9 个组件**（H4；裁定 M1-A）：键 = {@link HexCoord}（**格**），值 = {@link Market}
 * （每格**单一计价货币** + 一张商品价格表）。★ 三条口径：
 *
 * <ol>
 *   <li><b>缺格 = 该格没有市场</b>（合法状态，不抛）：结算对它什么都不做 —— 不造默认价、不猜一种货币；
 *   <li><b>键是格而不是市场 id</b>：本批的市场就是"某格的现货池"（同格供需直接撮合，见 {@code MarketSettlement}）， 多一个 {@code
 *       MarketId} 只会多一处可以漂开的身份（{@code MarketId} 那个契约留给"跨格市场节点"的后续增量）；
 *   <li>★ <b>它不含任何数量</b>：价格是数据、供需是每周期现算的（{@code MarketSettlement} 从家户账的会话工作副本读） ——
 *       市场表里没有"本期成交量"这类会过期的读数（读数在当天的 {@code ProductionLedger} 里；★ M2.7 的逐区读数是 {@code MarketReadout}
 *       在**读时**用它 + 进程内 {@code MarketReport} 现算的，同样不落进本切片）。
 * </ol>
 *
 * <p>★★ **{@code shipments} 是第 10 个组件**（M2.4）：键 = {@link ShipmentId}，值 = {@link
 * ShipmentBatch}（在途批次）。★ 它是**跨 tick 状态**：发运日建、到货日销；到货前目的地消费不到它。
 *
 * <p>★★ **S1 的第 12 个组件 {@code assetShares}**（键 = {@link AssetShareId}；R3B.1 起是<b>独立的实物资产份额总账</b>，
 * 逐行判"键 == 值内 id / industry 存在 / 非空 / quantity ≥ 0"；★ <b>没有</b>"Σ quantity ≤ Industry.capacity" 这类
 * 把技术模板与实物账本绑死的上界守卫）。★ 第 13 个组件 {@code operatorConditions}（{@link OperatorCondition}；S3.2 的经营者状态机）由
 * S1 一次性补齐，本阶段空表缺省。★ <b>P2-A A3：成员份额已从本切片整体删除</b>——家户人口组成的唯一权威是 Social 的
 * {@code Household.members}，Economy 只在结算入口读一份只读投影（不进状态、不进变更集/Codec）。
 *
 * <p>★★ **E1 追加第 17–20 个组件**（{@code modes} / {@code classStructures} / {@code classPositions} /
 * {@code classStandings}）：它们建立"生产方式 → 阶层结构 → 阶层位置 → 家户归属"的权威状态。★ **E1 不接线结算**： 这四张表为空时，旧 {@code
 * HouseholdClassRule}、旧 {@code settle*} 路径与旧档行为逐值不变；有值时也只做状态与读口， 旧路径仍以 {@code ClassRow.view} 为准（见
 * {@code ClassStanding} 的类注）。★ 跨表守卫按"对侧是否已提供"分段生效， 以便 {@code with*} 能逐组件构造；两侧都非空时引用完整性 fail-closed。
 *
 * <p>★★ **E2 追加第 21–22 个组件**（{@code productionOrganizations} / {@code assetRules}）：前者是"生产方式 + 阶层结构
 * + 劳动 + 资产"之间的桥（{@code ProductionOrganization}），由日结算的自动组织阶段 upsert；后者是 mode 下每种生产资料的
 * 租佃/抵押/清算/转移规则（{@code AssetRule}）。★ 两张表为空时自动组织阶段整体 no-op，旧路径逐值不变；非空时的引用完整性同样按 "对侧是否已提供"分段生效（unit /
 * assetShare / classPosition / mode 存在性）。
 *
 * <p>★★ **E3 追加第 23–24 个组件**（{@code governments} / {@code moneyIssuances}）：政府表建立"谁是哪个币种的发行主体"，
 * 发行审计表记录 INITIAL_ENDOWMENT / FISCAL_ISSUE / WITHDRAWAL。两表为空时旧结算路径逐值不变（零登记 ⇒ 付方余额不足照旧
 * fail-closed）；旧档缺这两个键 ⇒ 空表。
 *
 * <p>★★ **E4a：第 4 个组件从旧 {@code debts} 槽替换为 {@code debtContracts}（第 25 个组件 {@code pledges}
 * 追加在末尾）**：债务唯一权威表 = {@code Map<DebtContractId, DebtContract>}；同一 {@code (debtor, creditor, unit,
 * terms)} 跨周期同一条合同，不新开条；旧 {@code debt-cN-...} 的周期聚合条由 {@code EconomyCodec} 的旧档迁移按四元组合并（principal 用
 * {@code Math.addExact} 求和）。{@code pledges} 是质押基础形状（E4a 只落形状/Codec/守卫，清算行为留 E5）；两表为空时旧路径逐值不变。
 *
 * <p>★★ **E5a 追加第 26/27 个组件**（{@code liquidationPolicies} / {@code crisisSignals}）：前者按 {@link
 * AssetRuleId} 键清算制度参数（{@code assetRules} 已提供时被引用规则必须存在），后者按 {@link CrisisSignalId} 键 hex 危机信号（键 ==
 * 由 {@code (hex, kind)} 确定性派生；同 hex 同 kind 覆盖即更新）。 ★ 两张表为空 = 旧行为逐值不变，E5a
 * 不产生任何清算/信号；跨表守卫同样按“对侧已提供”分段，保证 {@code with*} 能逐组件构造。
 *
 * <p>★★ **E6a 追加第 28/29 个组件**（{@code modeTransitions} / {@code classShares}）：前者按 {@link
 * ModeTransitionId} 键模式变迁（键 == 由 {@code (organizationId, toModeId, effectiveDay)} 确定性派生；同一组织至多一条
 * PENDING）；后者按 {@link ClassShareId} 键阶层保留份额（键 == 由 {@code (transitionId, householdId,
 * classPositionId)} 派生；同一 {@code (transitionId, householdId)} 的 Σ 必须 = 1000‰）。两表为空 = 旧行为逐值不变；
 * 跨表守卫按“对侧已提供”分段（组织 / mode / 变迁 / 家户 / 位置存在性）。
 *
 * <p>★ <b>守卫**不**检查 cohort 侧的行是否存在</b>（有意不加，同 {@code ActorData}「表与表之间没有引用完整性约束」的口径）： 逐组件增量落盘 ⇒
 * **关系先到、行后到是合法写序**；而 cohort 解析不到行在结算里是**正常状态**（人口为 0 的那些 cohort 就是如此，那一笔留在 {@code
 * residualOwner}）——把它判成非法会让"人口尚未种入"的世界构造不出来。
 */
// ★ 豁免 EI_EXPOSE_REP（R4a，canonical verify 实测 28 条）：本 record 的每张表都在 compact 构造器里逐键复制 +
//   Collections.unmodifiableMap（见下方各 *Copy 段），访问器返回的是冻结副本、调用方改不动。SpotBugs 对
//   29 个 Map 记录组件的生成访问器保守报"暴露内部表示"；ArmyPlan 已有同类豁免先例。
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "28 张 Map 组件均在 compact 构造器内逐键复制并 Collections.unmodifiableMap；访问器返回冻结副本")
public record EconomyData(
    Optional<EconomyMeta> meta,
    Map<IndustryId, Industry> industries,
    Map<HouseholdId, ClassRow> classes,
    Map<DebtContractId, DebtContract> debtContracts,
    Map<HouseholdId, FlowRow> flows,
    Map<LaborAllocationId, LaborAllocation> allocations,
    Map<ProductionUnitId, ProductionRelation> relations,
    Map<HexCoord, Market> markets,
    Map<ShipmentId, ShipmentBatch> shipments,
    Map<AssetShareId, AssetShare> assetShares,
    Map<ProductionUnitId, OperatorCondition> operatorConditions,
    Map<ProductionUnitId, ProductionUnit> units,
    Map<DemandId, DemandEntry> demands,
    Map<CandidateId, ProductionCandidate> candidates,
    Map<ProductionModeId, ProductionMode> modes,
    Map<ClassStructureId, ClassStructure> classStructures,
    Map<ClassPositionId, ClassPosition> classPositions,
    Map<HouseholdId, ClassStanding> classStandings,
    Map<ProductionOrganizationId, ProductionOrganization> productionOrganizations,
    Map<AssetRuleId, AssetRule> assetRules,
    Map<GovernmentId, Government> governments,
    Map<MoneyIssuanceId, MoneyIssuanceRecord> moneyIssuances,
    Map<PledgeId, Pledge> pledges,
    Map<AssetRuleId, LiquidationPolicy> liquidationPolicies,
    Map<CrisisSignalId, HexCrisisSignal> crisisSignals,
    Map<ModeTransitionId, ModeTransition> modeTransitions,
    Map<ClassShareId, ClassShare> classShares,
    Map<ProductionOrganizationId, MerchantFirm> merchantFirms) {

  /** 往返用例的起点：未激活 + 二十七张空表（P2-A A3/A4 起成员份额表与劳动供给表已删除）。 */
  public static EconomyData empty() {
    return new EconomyData(
        Optional.empty(),
        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
  }

  public EconomyData {
    // ★ 缺键（null）⇒ 未激活 / 空表，见类注释（旧档兼容，fail-closed 方向）。
    if (meta == null) {
      meta = Optional.empty();
    }
    if (industries == null) {
      industries = Map.of();
    }
    if (classes == null) {
      classes = Map.of();
    }
    if (debtContracts == null) {
      debtContracts = Map.of();
    }
    if (flows == null) {
      flows = Map.of();
    }
    // ★ R2 的两个新组件：同一口径（缺键 ⇒ 空表，见类注释）。
    if (allocations == null) {
      allocations = Map.of();
    }
    // ★ S1 的新组件：同一口径（缺键 ⇒ 空表，见类注释）。
    //   （由 LegacyHouseholdMigration 补齐）；运行期的类状态与成员份额必须同源（见下面的守恒守卫）。
    if (assetShares == null) {
      assetShares = Map.of();
    }
    // ★ S3 预留的第 13 个组件（S3.2 的经营者状态机）：缺键 ⇒ 空表（与其余组件同一条旧档兼容口径）。
    if (operatorConditions == null) {
      operatorConditions = Map.of();
    }
    // ★★ R3B.2 第 14 个组件（生产单元）：缺键 ⇒ 空表（旧档由迁移器从资产份额反推默认 unit）。
    if (units == null) {
      units = Map.of();
    }
    // ★★ R4-E2 第 15/16 个组件（需求账本 + 候选预设）：缺键 ⇒ 空表（旧档不因缺键失败）。
    if (demands == null) {
      demands = Map.of();
    }
    if (candidates == null) {
      candidates = Map.of();
    }
    // ★★ E1 的四个新组件（生产方式/阶层结构/阶层位置/家户阶层归属）：旧档缺键 ⇒ 空表（同一条旧档兼容口径；
    //   新状态为空时旧结算路径逐值不变）。空表不是"坏数据"，是"这个档还没有新地基"。
    if (modes == null) {
      modes = Map.of();
    }
    if (classStructures == null) {
      classStructures = Map.of();
    }
    if (classPositions == null) {
      classPositions = Map.of();
    }
    if (classStandings == null) {
      classStandings = Map.of();
    }
    // ★★ E2 的第 21/22 个组件（生产组织 / 生产资料规则）：旧档缺键 ⇒ 空表（同上面每一条的口径）。
    if (productionOrganizations == null) {
      productionOrganizations = Map.of();
    }
    if (assetRules == null) {
      assetRules = Map.of();
    }
    // ★★ E3 的第 23/24 个组件（政府 / 货币发行审计）：旧档缺键 ⇒ 空表（同上面每一条的口径）。
    //   空表 = 没有政府、没有发行记录、MoneyIssuance 零登记 ⇒ 旧结算路径的 fail-closed 行为逐字不变。
    if (governments == null) {
      governments = Map.of();
    }
    if (moneyIssuances == null) {
      moneyIssuances = Map.of();
    }
    // ★★ E4a 的第 25 个组件（质押）：旧档缺键 ⇒ 空表（同上面每一条的口径）。空表 = 没有质押，
    //   “Σ活跃质押 ≤ share.quantity”守卫整体 no-op，不改变任何旧路径。
    if (pledges == null) {
      pledges = Map.of();
    }
    // ★★ E5a 的第 26/27 个组件（清算政策 / hex 危机信号）：旧档缺键 ⇒ 空表（同上面每一条的口径）。
    //   空表 = 没有清算制度参数、没有危机信号 ⇒ 旧结算路径逐值不变（E5a 不产生任何信号/清算）。
    if (liquidationPolicies == null) {
      liquidationPolicies = Map.of();
    }
    if (crisisSignals == null) {
      crisisSignals = Map.of();
    }
    // ★★ E6a 的第 28/29 个组件（模式变迁 / 阶层保留份额）：旧档缺键 ⇒ 空表（同上面每一条的口径）。
    //   空表 = 没有模式变迁请求、没有保留份额记录 ⇒ 旧结算路径逐值不变（E6a 不产生任何变迁）。
    if (modeTransitions == null) {
      modeTransitions = Map.of();
    }
    if (classShares == null) {
      classShares = Map.of();
    }
    // ★★ P10.1 第 30 个组件（商号表）：旧档缺键 ⇒ 空表（同上面每一条的口径）。真正的旧档由版本门在激活前拒绝，
    //   这里的空表兜底只服务 with* 逐组件构造与"对侧尚未提供"的中间态。
    if (merchantFirms == null) {
      merchantFirms = Map.of();
    }
    // ★ 第 8 个组件（S1 阶段 4+5 Task 2）：同一口径（缺键 ⇒ 空表，见类注释）。★ 迁移器要读它，故提到迁移之前。
    if (relations == null) {
      relations = Map.of();
    }
    // ★★ R3B.2b：旧档 Industry 兼容位（record 末尾 5 个）→ 默认 unit + 整额 OWNED AssetShare。
    //   ★ **必须在 LegacyHouseholdMigration 之前**：迁移器要靠新造的 unit 才能把旧 relations /
    //     operatorConditions 键与 allocations.activity 对齐。
    //   ★ 它是 **Timeline 直读旧 changeset** 的兜底（那条路径不经过 EconomyCodec.decodeChangeSet 的节点整形）；
    //     codec/载荷已把兼容位摘成中性的路径下，这里的判据不成立 ⇒ 本段 no-op，两条路径幂等共存。
    //   ★ 判据 = "兼容位非中性"（operator 非 null / progress/劳动 > 0 / 两张表非空）；新形状恒中性。
    if (hasLegacyProductionBits(industries)) {
      Map<IndustryId, Industry> normalizedIndustries = new LinkedHashMap<>();
      Map<ProductionUnitId, ProductionUnit> normalizedUnits = new LinkedHashMap<>(units);
      Map<AssetShareId, AssetShare> normalizedShares = new LinkedHashMap<>(assetShares);
      Set<IndustryId> unitIndustries = new LinkedHashSet<>();
      for (ProductionUnit unit : normalizedUnits.values()) {
        unitIndustries.add(unit.industry());
      }
      Set<IndustryId> shareIndustries = new LinkedHashSet<>();
      for (AssetShare share : normalizedShares.values()) {
        shareIndustries.add(share.industry());
      }
      for (Map.Entry<IndustryId, Industry> entry : industries.entrySet()) {
        Industry industry = entry.getValue();
        IndustryId industryId = entry.getKey();
        if (industry == null || !hasLegacyProductionBits(industry)) {
          normalizedIndustries.put(industryId, industry);
          continue;
        }
        // ★ operator 缺省：与 EconomyCodec.migrateLegacyProductionComponents / EconomyPayloads 同一拼写点 ——
        //   industry.operator 有就用；否则 RegimeOperators.defaultOperator(regime, industryId)（不另拼）。
        ActorRef operator =
            industry.operator() != null
                ? industry.operator()
                : RegimeOperators.defaultOperator(industry.regime(), industryId);
        // ★ AssetShare：该 industry 尚无任何份额时才物化；只造 quantity > 0 的项（0 值不造行、也不构成生产规模）。
        //   id 走 AssetShare.idOf(..., sequence=0)：与既有迁移同一条 id 规则 ⇒ 重放/重跑不会重复。
        boolean hasExistingShare = shareIndustries.contains(industryId);
        boolean hasPositiveCapacity = false;
        for (Map.Entry<AssetKind, Long> capacity : industry.capacity().entrySet()) {
          if (capacity.getValue() <= 0L) {
            continue;
          }
          hasPositiveCapacity = true;
          if (hasExistingShare) {
            continue;
          }
          AssetShareId shareId =
              AssetShare.idOf(
                  industryId,
                  capacity.getKey(),
                  operator,
                  operator,
                  AssetShare.RightKind.OWNED,
                  0L);
          normalizedShares.putIfAbsent(
              shareId,
              new AssetShare(
                  shareId,
                  industryId,
                  capacity.getKey(),
                  operator,
                  operator,
                  capacity.getValue(),
                  AssetShare.RightKind.OWNED));
        }
        // ★ 默认 unit：同 industry 已有 unit 则不动（幂等）；capacity 全 0/空 ⇒ 旧档"规模恒 0、无生产"，不造 unit。
        //   进度/劳动/投入从兼容位原样带过。
        if (!unitIndustries.contains(industryId) && hasPositiveCapacity) {
          ProductionUnitId unitId = ProductionUnitId.idOf(industryId, operator);
          normalizedUnits.putIfAbsent(
              unitId,
              new ProductionUnit(
                  unitId,
                  industryId,
                  operator,
                  industryId.value(),
                  industry.progressDays(),
                  industry.cycleLaborMilli(),
                  industry.cycleInputUsedMilli()));
          unitIndustries.add(industryId);
        }
        // ★ 归一化后换成 12 参模板：兼容位清成中性 ⇒ EconomyChangeSet.between/diff 不再产生兼容位漂移。
        normalizedIndustries.put(
            industryId,
            new Industry(
                industryId,
                industry.name(),
                industry.regime(),
                industry.cycleDays(),
                industry.capacityPerUnit(),
                industry.dailyInputPerUnit(),
                industry.dailyLaborPerUnit(),
                industry.laborPerUnit(),
                industry.outputPerUnit(),
                industry.cycleInputPerUnit(),
                industry.slots(),
                industry.allocation()));
      }
      industries = normalizedIndustries;
      units = normalizedUnits;
      assetShares = normalizedShares;
    }
    // ★★ S1 旧档迁移（显式、幂等、可重放；见 LegacyHouseholdMigration 的类注）：
    //   旧 LaborAllocation 没有 household 时，在这里按配额反查补一个稳定家户身份。
    //   ★ 它必须发生在**所有守卫之前**：迁移后的状态才参与 pending 检查、Σ 守卫与关系归一。
    //   ★★ Minor-5（2026-09-28 评审）：这一步是**构造期自动**跑的 ⇒ 任何旧形状测试夹具（classes 非空但
    //   assetShares 为空、或 allocation 的 household 是 pending 占位）都会被静默补齐 assetShares 后才进守卫。
    //   V 阶段适配旧测试/旧档时必须点名这条自动迁移（它不是夹具"本来就有"的组件，是构造期补出来的）。
    if (LegacyHouseholdMigration.needed(
        industries,
        units,
        classes,
        allocations,
        assetShares,
        relations,
        operatorConditions,
        meta)) {
      LegacyHouseholdMigration.Result migrated =
          LegacyHouseholdMigration.migrate(
              industries,
              units,
              classes,
              allocations,
              assetShares,
              relations,
              operatorConditions,
              meta);
      units = migrated.units();
      allocations = migrated.allocations();
      assetShares = migrated.assetShares();
      relations = migrated.relations();
      operatorConditions = migrated.operatorConditions();
      meta = migrated.meta();
    }
    // ★ 空表 = **全归 residualOwner 的等价路径**（裁定 E9）：没有规则不是坏数据，是"全部自留"。
    // ★ 第 9 个组件（H4）：同一口径（缺键 ⇒ 空表 = 世界上一个市场都没有，见类注释）。
    if (markets == null) {
      markets = Map.of();
    }
    Map<IndustryId, Industry> industriesCopy = new LinkedHashMap<>();
    for (Map.Entry<IndustryId, Industry> entry : industries.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("industries 的键与值都不得为 null: " + entry.getKey());
      }
      industriesCopy.put(entry.getKey(), entry.getValue());
    }
    industries = Collections.unmodifiableMap(industriesCopy); // ★ 冻在赋值处
    Map<HouseholdId, ClassRow> classesCopy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, ClassRow> entry : classes.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("classes 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().id())) {
        throw new IllegalArgumentException(
            "classes 的键必须与 ClassRow.id 一致（S1 起键 = 家户稳定身份）：键="
                + entry.getKey()
                + "，行内 id="
                + entry.getValue().id());
      }
      ClassRow row = entry.getValue();
      // ★★ R4-B.4（R3 决策单 §0.1/§1.5，R3B.4）：**view 不再受 Industry.slots 约束**。
      //   旧守卫（view 必须命中该格产业的 slots 且 participationPerMille ≤ 该 slot 的
      //   laborParticipationPerMille）已删除；Industry.slots / ClassSlot 只作为生产方式内部的角色/劳动配置。
      //   ClassRow 自己的 [0,1000] 参与率守卫仍在（见 ClassRow 构造期）。
      classesCopy.put(entry.getKey(), row);
    }
    classes = Collections.unmodifiableMap(classesCopy); // ★ 冻在赋值处
    Map<DebtContractId, DebtContract> debtContractsCopy = new LinkedHashMap<>();
    for (Map.Entry<DebtContractId, DebtContract> entry : debtContracts.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("debtContracts 的键与值都不得为 null: " + entry.getKey());
      }
      DebtContract contract = entry.getValue();
      if (!entry.getKey().equals(contract.id())) {
        throw new IllegalArgumentException(
            "debtContracts 的键必须与 DebtContract.id 一致：键="
                + entry.getKey()
                + "，行内 id="
                + contract.id());
      }
      // ★★ 冻结的条件：id 必须确实是 (debtor, creditor, unit, terms) 的确定性派生 —— 合同的“连续身份”
      //   一旦可以手写，同一四元组就能在两条记录里各写一个 id，跨周期连续这条地基当场失效。
      if (!contract.idMatchesIdentity()) {
        throw new IllegalArgumentException(
            "DebtContract.id 必须由 (debtor, creditor, unit, terms) 确定性派生："
                + entry.getKey()
                + " ≠ "
                + DebtContractId.idOf(
                    contract.debtor(), contract.creditor(), contract.unit(), contract.terms()));
      }
      debtContractsCopy.put(entry.getKey(), contract);
    }
    debtContracts = Collections.unmodifiableMap(debtContractsCopy); // ★ 冻在赋值处
    // ★★ B.3b（R3 决策单 §0.3）：孤儿债对账 —— 以 debtContracts 表为唯一权威，按 debtor 分组、
    //   DebtContractId canonical 升序重建每个 ClassRow.debts 引用。★ 必须在**跨表守卫之前**：
    //   守卫要求“引用的合同存在”，而孤儿债是“合同存在、引用缺失”；对账不碰合同表本身
    //   （principal/status 守恒），只在 debtor/creditor 家户行缺失时 fail-closed 具名抛。
    classesCopy = DebtReferenceReconciler.reconcile(debtContractsCopy, classesCopy);
    classes = Collections.unmodifiableMap(classesCopy); // ★ 冻在赋值处（可能与上面同一实例）
    // ★ v2 spec §八.2：两张表的**交叉引用完整性**。★ 必须等两张表都建完再判 ——
    //   在任一段内查对方会陷入循环依赖（debtContracts 要查 classes、classes 要查 debtContracts），故不能靠调顺序解决。
    //   ★ B.3b 起 classes 侧的引用已由上面的 DebtReferenceReconciler 重建过，本循环是对账后的兜底断言。
    for (Map.Entry<DebtContractId, DebtContract> entry : debtContractsCopy.entrySet()) {
      DebtContract contract = entry.getValue();
      if (!classesCopy.containsKey(contract.debtor())
          || !classesCopy.containsKey(contract.creditor())) {
        throw new IllegalArgumentException(
            "债务的 debtor/creditor 必须是已存在的阶层行（v2 spec §八.2）："
                + entry.getKey()
                + " "
                + contract.debtor()
                + " → "
                + contract.creditor());
      }
    }
    for (Map.Entry<HouseholdId, ClassRow> entry : classesCopy.entrySet()) {
      for (DebtContractId contractId : entry.getValue().debts()) {
        if (!debtContractsCopy.containsKey(contractId)) {
          throw new IllegalArgumentException(
              "ClassRow.debts 引用了不存在的债务合同（v2 spec §八.2）：" + entry.getKey() + " → " + contractId);
        }
      }
    }
    Map<HouseholdId, FlowRow> flowsCopy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, FlowRow> entry : flows.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("flows 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().id())) {
        throw new IllegalArgumentException(
            "flows 的键必须与 FlowRow.id 一致（S1 起键 = 家户稳定身份）：键="
                + entry.getKey()
                + "，行内 id="
                + entry.getValue().id());
      }
      ClassRow flowRow = classesCopy.get(entry.getKey());
      if (flowRow == null) {
        throw new IllegalArgumentException("flows 的键必须是已存在的家户（S1 起两表同键）：" + entry.getKey());
      }
      requireIndustryRegistered(industriesCopy, flowRow.view(), "flows");
      flowsCopy.put(entry.getKey(), entry.getValue());
    }
    flows = Collections.unmodifiableMap(flowsCopy); // ★ 冻在赋值处
    // ── R2：劳动分配表 + 三条结构判据（见类注释）────────────────────────────────────────────
    Set<String> industryIds = new LinkedHashSet<>();
    for (IndustryId id : industriesCopy.keySet()) {
      industryIds.add(id.value());
    }
    // ★★ R3B.2：劳动配额的 activity 是 unit id（String）⇒ "这份劳动喂哪条生产活动"按值查 unit。
    //   ★ 用 values 建索引（id 值 → unit）：units 表自身的键一致性由后面的 unit 守卫判。
    Map<String, ProductionUnit> unitsByValue = new LinkedHashMap<>();
    for (ProductionUnit unit : units.values()) {
      unitsByValue.put(unit.id().value(), unit);
    }
    Map<LaborAllocationId, LaborAllocation> allocationsCopy = new LinkedHashMap<>();
    Map<HouseholdId, Long> allocatedPerHousehold = new LinkedHashMap<>();
    for (Map.Entry<LaborAllocationId, LaborAllocation> entry : allocations.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("allocations 的键与值都不得为 null: " + entry.getKey());
      }
      LaborAllocation allocation = entry.getValue();
      if (!entry.getKey().equals(allocation.id())) {
        throw new IllegalArgumentException(
            "allocations 的键必须与 LaborAllocation.id 一致：键="
                + entry.getKey()
                + "，行内 id="
                + allocation.id());
      }
      // ①-b ★★ S1：这份劳动必须属于一个已存在的家户（或旧档迁移期的 pending 占位 —— 只有
      //   EconomyCodec 的旧档反序列化器会造它，迁移器必须在交回状态前换成真实家户）。
      HouseholdId allocatedHousehold = allocation.household();
      if (!HouseholdIds.isPending(allocatedHousehold) && !classesCopy.containsKey(allocatedHousehold)) {
        throw new IllegalArgumentException(
            "劳动分配的家户必须是已存在的家户（S1 起身份与视图分离）："
                + entry.getKey()
                + " 的 household="
                + allocatedHousehold
                + " 不在 classes 里");
      }
      // ① actor ↔ 产业 的**撞名判据**（结算按 actor id 归属劳动）：
      //    非产业型不得与产业 id 撞名 —— 撞名会让"家户/单位的配额"静默算进那个产业。
      //   ★★ **R3 起 HOUSEHOLD 是"自由档"**：家户 actor id **可以**命名一个产业（那时配额照进该产业），也可以不命名。
      //   ★★ **P2-A §13.3**：ESTATE/WORKSHOP 退役 ⇒ 生产主体身份改走 ORGANIZATION（产业经营者）与 HOUSEHOLD；
      //     两者都允许命中产业 id（不再有"必须命中"的 kind —— ORGANIZATION 也可用来表示非产业组织）。
      ActorKind kind = allocation.actor().kind();
      boolean mayResolveToIndustry =
          kind == ActorKind.ORGANIZATION || kind == ActorKind.HOUSEHOLD;
      boolean resolvesToIndustry = industryIds.contains(allocation.actor().id());
      if (resolvesToIndustry && !mayResolveToIndustry) {
        throw new IllegalArgumentException(
            "劳动分配的 actor 与产业 id 的对应关系不成立（结算按 actor id 把配额归给产业）：kind="
                + kind
                + "，id="
                + allocation.actor().id()
                + " ⇒ 非产业型主体的 id 不得与任何产业 id 相同");
      }
      // ①-c ★★ R3B.2：activity 若指名了现存 unit，则收劳动的主体必须就是该 unit 的 operator ——
      //   "劳动喂了谁"（activity）与"谁收劳动"（actor）是同一件事的两处拼写，不一致时没有哪一处能判谁对。
      //   ★ activity 不命中 unit 的配额合法（自由家户劳动/旧档未接线档）：它不喂任何生产，只进守恒与读口。
      ProductionUnit referencedUnit = unitsByValue.get(allocation.activity());
      if (referencedUnit == null && allocation.activity().startsWith(UNIT_ID_PREFIX)) {
        throw new IllegalArgumentException(
            "劳动配额的 activity 看起来是 unit id（以 \""
                + UNIT_ID_PREFIX
                + "\" 开头）但该 unit 不存在（悬空引用；自由家户劳动请用非 unit 的活动词）: "
                + allocation);
      }
      if (referencedUnit != null && !referencedUnit.operator().equals(allocation.actor())) {
        throw new IllegalArgumentException(
            "劳动配额的 activity 指名的 unit 与 actor 不一致（同一件事不许两处拼写）：activity="
                + allocation.activity()
                + " unit.operator="
                + referencedUnit.operator()
                + "，actor="
                + allocation.actor());
      }
      // ★★ P2-A A4：配额上限改为**家户每 tick 时间预算**（{@code ClassRow.laborMilli}，毫小时）——
      //   不再有"每批次供给表"这第二权威（LaborSupply 已删除；预算每 tick 由 Social 人口组成重算）。
      allocatedPerHousehold.merge(allocation.household(), allocation.laborMilli(), Long::sum);
      allocationsCopy.put(entry.getKey(), allocation);
    }
    allocations = Collections.unmodifiableMap(allocationsCopy); // ★ 冻在赋值处
    // ③ ★★ **Σ allocated(household) ≤ household time budget**（本阶段最重要的不变量，见类注释）。
    for (Map.Entry<HouseholdId, Long> entry : allocatedPerHousehold.entrySet()) {
      ClassRow row = classesCopy.get(entry.getKey());
      if (row == null) {
        continue; // 旧档迁移期的 pending 家户：迁移器会换成真实家户（见 LegacyHouseholdMigration）
      }
      if (entry.getValue() > row.laborMilli()) {
        throw new IllegalArgumentException(
            "家户 "
                + entry.getKey()
                + " 的劳动配额之和 "
                + entry.getValue()
                + " 超过它的每 tick 时间预算 "
                + row.laborMilli()
                + " 毫小时（同一份家户时间不得被多个生产活动各算一次满额，计划 §13.5）");
      }
    }
    // ── R3B.1 第 12 个组件：实物资产份额表 ──────────────────────────────────────────────
    //   ★ 键 == 值内 id；industry 必须存在；asset/owner/operator/kind 非空、quantity ≥ 0（非空与 quantity
    //     由 AssetShare 构造期判，这里判跨表的 industry 引用与键身份）。
    //   ★★ **这里不再有"Σ quantity ≤ Industry.capacity"的上界守卫，也不写 Σ == capacity**：AssetShare 是
    //     独立的实物资产总账，Industry.capacity 在 B.1 里只是过渡字段（B.2 移出生产模型）；把技术模板当
    //     实物账本上界，会把"实物已存在、模板尚未及更新"这类合法状态误判成坏数据。
    //   ★ 关系表的 operator 与 unit 的关系由**第 14 个组件（units）**的守卫判（R3B.2 起关系挂在 unit 上）。
    Map<AssetShareId, AssetShare> assetSharesCopy = new LinkedHashMap<>();
    for (Map.Entry<AssetShareId, AssetShare> entry : assetShares.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("assetShares 的键与值都不得为 null: " + entry.getKey());
      }
      AssetShare share = entry.getValue();
      if (!entry.getKey().equals(share.id())) {
        throw new IllegalArgumentException(
            "assetShares 的键必须与 AssetShare.id 一致：键=" + entry.getKey() + "，行内 id=" + share.id());
      }
      if (!industriesCopy.containsKey(share.industry())) {
        throw new IllegalArgumentException("资产份额指名的产业不存在: " + share.industry());
      }
      assetSharesCopy.put(entry.getKey(), share);
    }
    assetShares = Collections.unmodifiableMap(assetSharesCopy); // ★ 冻在赋值处

    // ── 第 8 个组件：生产关系表（S1 阶段 4+5 Task 2；计划 R3；R3B.2 起键/activity = unit id）────────
    Map<ProductionUnitId, ProductionRelation> relationsRaw = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, ProductionRelation> entry : relations.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("relations 的键与值都不得为 null: " + entry.getKey());
      }
      relationsRaw.put(entry.getKey(), entry.getValue());
    }
    // ★★ S1：把旧档/默认关系里的 {@code ToCohort(view)} 一对一归一到 {@code ToHousehold(id)}
    //   （同一 view 恰有一个家户时才归一 —— 一一对应是旧档的既有事实；view 有歧义时保留 ToCohort
    //   作为 S3 的视图选择器，不在构造期猜）。归一化是"键/受方迁移"的唯一落点，幂等。
    Map<ProductionUnitId, ProductionRelation> relationsCopy =
        normalizeRecipients(relationsRaw, classesCopy);
    relations = Collections.unmodifiableMap(relationsCopy); // ★ 冻在赋值处
    // ★★ 两条跨表守卫（R3B.2 改口径）：① 键 == 值内 activity（同 classes/flows 的键身份口径）；
    //   ② 关系挂在一个**已存在的 unit** 上、且 operator 与 unit.operator 逐值相等
    //      （"谁经营"若能在关系表里另写一遍，两边不一致时没有任何一处能判谁对）。
    for (Map.Entry<ProductionUnitId, ProductionRelation> entry : relationsCopy.entrySet()) {
      ProductionRelation relation = entry.getValue();
      if (!entry.getKey().equals(relation.activity())) {
        throw new IllegalArgumentException(
            "relations 的键必须与 ProductionRelation.activity 一致：键="
                + entry.getKey()
                + "，行内 activity="
                + relation.activity());
      }
      ProductionUnit unit = unitsByValue.get(entry.getKey().value());
      if (unit == null) {
        throw new IllegalArgumentException(
            "关系指名的生产单元（unit）不存在："
                + entry.getKey()
                + "（旧档请先由 LegacyHouseholdMigration 把旧 industry 键对齐到 unit）");
      }
      if (!relation.operator().equals(unit.operator())) {
        throw new IllegalArgumentException(
            "关系的 operator 必须与 unit.operator 一致（同一件事不许两处拼写）："
                + entry.getKey()
                + " 关系="
                + relation.operator()
                + "，unit="
                + unit.operator());
      }
    }
    // ── 第 9 个组件：市场表（H4；每格一个现货市场）──────────────────────────────────────
    //   ★ 键 = 格（{@link HexCoord}）；**缺格 = 该格没有市场**（合法状态 —— 结算对它什么都不做，见 Market 的类注）。
    //   ★ 只判 null 与结构：**价格是数据**（GM 可调），这里没有"价格该是多少""哪些商品该有价"的判据 ——
    //     那些是 GM 的判断（信条十二），不是状态类型的守卫。
    Map<HexCoord, Market> marketsCopy = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, Market> entry : markets.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("markets 的键与值都不得为 null: " + entry.getKey());
      }
      marketsCopy.put(entry.getKey(), entry.getValue());
    }
    markets = Collections.unmodifiableMap(marketsCopy); // ★ 冻在赋值处
    // ── 第 10 个组件：在途批次表（M2.4；跨 tick 状态）───────────────────────────────────────
    //   ★ 键 = {@link ShipmentId}（{@code sh-<day>-<seq>}）；**缺键 = 这个世界的货物都在账上、没有在途**（合法状态）。
    //   ★ 它在到货日由日循环销账、在发运日由区域撮合建账；到货前目的地**消费不到它**（判据落在这一维）。
    if (shipments == null) {
      shipments = Map.of();
    }
    Map<ShipmentId, ShipmentBatch> shipmentsCopy = new LinkedHashMap<>();
    for (Map.Entry<ShipmentId, ShipmentBatch> entry : shipments.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("shipments 的键与值都不得为 null: " + entry.getKey());
      }
      shipmentsCopy.put(entry.getKey(), entry.getValue());
    }
    shipments = Collections.unmodifiableMap(shipmentsCopy); // ★ 冻在赋值处
    // ── 第 13 个组件：经营者状态表（S3.2；R3B.2 起键 = ProductionUnitId，形状/词表不变）──────────
    //   ★ 键 = unit id；值内 {@code industry} 仍是技术模板 id（形状不动）⇒ 守卫判"key 的 unit 存在"且
    //     "值内 industry == unit.industry"。
    //   ★ 空表合法：S3 之前没有状态机读者，空表 = "尚未登记任何经营者状态"（不是坏数据）。
    Map<ProductionUnitId, OperatorCondition> operatorConditionsCopy = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, OperatorCondition> entry : operatorConditions.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("operatorConditions 的键与值都不得为 null: " + entry.getKey());
      }
      ProductionUnit unit = unitsByValue.get(entry.getKey().value());
      if (unit == null) {
        throw new IllegalArgumentException(
            "operatorConditions 指名的生产单元（unit）不存在："
                + entry.getKey()
                + "（旧档请先由 LegacyHouseholdMigration 把旧 industry 键对齐到 unit）");
      }
      if (!unit.industry().equals(entry.getValue().industry())) {
        throw new IllegalArgumentException(
            "operatorConditions 值内的 industry 必须与 unit.industry 一致：键="
                + entry.getKey()
                + "，unit.industry="
                + unit.industry()
                + "，值内="
                + entry.getValue().industry());
      }
      operatorConditionsCopy.put(entry.getKey(), entry.getValue());
    }
    operatorConditions = Collections.unmodifiableMap(operatorConditionsCopy); // ★ 冻在赋值处
    // ── R3B.2 第 14 个组件：生产单元表（units）────────────────────────────────────────────
    //   ★ 键 == unit.id；industry 必须存在；progressDays ≤ industry.cycleDays（跨表上界）。
    //   ★★ **迁移完成态**：每个非 EXITED/ABANDONED 的 unit，其 operator 至少有一条同 industry 的
    //     AssetShare（否则明确抛，不静默）—— 这条守卫是"AssetShare 是实物总账、unit 是实际生产"两件事
    //     之间的桥：没有份额的 unit 没有任何产能来源，让它留在 ACTIVE 或让它在结算里静默产出都是坏数据。
    Map<ProductionUnitId, ProductionUnit> unitsCopy = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, ProductionUnit> entry : units.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("units 的键与值都不得为 null: " + entry.getKey());
      }
      ProductionUnit unit = entry.getValue();
      if (!entry.getKey().equals(unit.id())) {
        throw new IllegalArgumentException(
            "units 的键必须与 ProductionUnit.id 一致：键=" + entry.getKey() + "，行内 id=" + unit.id());
      }
      Industry industryTemplate = industriesCopy.get(unit.industry());
      if (industryTemplate == null) {
        throw new IllegalArgumentException("生产单元指名的产业（技术模板）不存在: " + unit.industry());
      }
      if (unit.progressDays() > industryTemplate.cycleDays()) {
        throw new IllegalArgumentException(
            "ProductionUnit.progressDays 必须 ∈ [0, industry.cycleDays]：unit="
                + entry.getKey()
                + " progressDays="
                + unit.progressDays()
                + "，cycleDays="
                + industryTemplate.cycleDays());
      }
      unitsCopy.put(entry.getKey(), unit);
    }
    units = Collections.unmodifiableMap(unitsCopy); // ★ 冻在赋值处
    // ★★ B.3b：**unit 不要求必须有 AssetShare**（R4 计划禁把"资产闲置/退出"判成坏数据）。
    //   资产可以全部转走（见 economy.TransferAssetShare），此时该 unit 的规模由
    //   ProductionUnitBook 纯派生为 0 —— "有经营者、无资产、不生产"是合法状态（也是 E1 退出处置的前态）。
    //   unit↔relation / operatorConditions 的 operator/industry 一致性已由上面两段守卫把守，不因本放宽而松。
    // ── R4-E2 第 15 个组件：需求账本 ───────────────────────────────────────────────────────
    //   ★ 键 == 值内 id；scope ↔ household/hex 的互斥与必填由 DemandEntry 构造期判；
    //     这里判跨表引用：HOUSEHOLD 的家户必须存在；HEX 的格必须在本世界里可定位
    //     （产业/家户行/市场三者任一登记的格，见 hexRegistered）。
    Map<DemandId, DemandEntry> demandsCopy = new LinkedHashMap<>();
    for (Map.Entry<DemandId, DemandEntry> entry : demands.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("demands 的键与值都不得为 null: " + entry.getKey());
      }
      DemandEntry demand = entry.getValue();
      if (!entry.getKey().equals(demand.id())) {
        throw new IllegalArgumentException(
            "demands 的键必须与 DemandEntry.id 一致：键=" + entry.getKey() + "，行内 id=" + demand.id());
      }
      if (demand.scope() == DemandEntry.DemandScope.HOUSEHOLD) {
        HouseholdId household = demand.household().orElseThrow();
        if (!classesCopy.containsKey(household)) {
          throw new IllegalArgumentException(
              "HOUSEHOLD 范围的需求指名的家户不存在: " + demand.id().value() + " → " + household.value());
        }
      } else {
        HexCoord hex = demand.hex().orElseThrow();
        if (!hexRegistered(industriesCopy, classesCopy, markets, hex)) {
          throw new IllegalArgumentException(
              "HEX 范围的需求指名的格没有经济状态（该格不存在/未播种，也不在任何市场键里）: " + demand.id().value() + " → " + hex);
        }
      }
      demandsCopy.put(entry.getKey(), demand);
    }
    demands = Collections.unmodifiableMap(demandsCopy); // ★ 冻在赋值处
    // ── R4-E2 第 16 个组件：候选预设表 ────────────────────────────────────────────────────
    //   ★ 键 == 值内 id；regime 必须是已登记的制度（否则 E2b 建 relation 时必然抛 —— 登记处 fail-closed）；
    //     output 必须在本行的 outputPerUnit 里（ProductionCandidate 构造期已判，这里对状态入口再兜一层）。
    Map<CandidateId, ProductionCandidate> candidatesCopy = new LinkedHashMap<>();
    for (Map.Entry<CandidateId, ProductionCandidate> entry : candidates.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("candidates 的键与值都不得为 null: " + entry.getKey());
      }
      ProductionCandidate candidate = entry.getValue();
      if (!entry.getKey().equals(candidate.id())) {
        throw new IllegalArgumentException(
            "candidates 的键必须与 ProductionCandidate.id 一致：键="
                + entry.getKey()
                + "，行内 id="
                + candidate.id());
      }
      if (!RegimeOperators.registered().containsKey(candidate.regime().value())) {
        throw new IllegalArgumentException(
            "候选预设的 regime 未登记（进入算法无法为它推导默认经营主体/关系）: "
                + candidate.id().value()
                + " regime="
                + candidate.regime().value()
                + "；已登记: "
                + RegimeOperators.registered().keySet());
      }
      if (!candidate.outputPerUnit().containsKey(candidate.output())) {
        throw new IllegalArgumentException(
            "候选预设的 output 必须出现在 outputPerUnit 里: "
                + candidate.id().value()
                + " output="
                + candidate.output().value());
      }
      candidatesCopy.put(entry.getKey(), candidate);
    }
    candidates = Collections.unmodifiableMap(candidatesCopy); // ★ 冻在赋值处
    // ── E1 第 17–20 个组件：生产方式 / 阶层结构 / 阶层位置 / 家户阶层归属 ────────────────────
    //   ★ 缺键 ⇒ 空表（上面已归一）；**新状态为空时本段整体 no-op**，旧档逐值行为不受影响。
    //   ★ 引用完整性是 fail-closed 的，但按"对侧是否已提供"分段生效：`with*` 是逐组件写口，
    //     四个组件之间有两处循环引用（mode ↔ structure）与层次引用（position → mode、standing → household/position）；
    //     若每一段都无条件要求完整闭环，单项 `with*` 永远构造不出中间态。空表在这里读作"这一侧还没提供"，
    //     一旦对侧非空，键身份、引用与的位置形状就必须逐值自洽 —— 最终完整状态因此仍是 fail-closed 的。
    Map<ProductionModeId, ProductionMode> modesCopy = new LinkedHashMap<>();
    for (Map.Entry<ProductionModeId, ProductionMode> entry : modes.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("modes 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().id())) {
        throw new IllegalArgumentException(
            "modes 的键必须与 ProductionMode.id 一致：键="
                + entry.getKey()
                + "，行内 id="
                + entry.getValue().id());
      }
      modesCopy.put(entry.getKey(), entry.getValue());
    }
    modes = Collections.unmodifiableMap(modesCopy); // ★ 冻在赋值处
    Map<ClassStructureId, ClassStructure> structuresCopy = new LinkedHashMap<>();
    for (Map.Entry<ClassStructureId, ClassStructure> entry : classStructures.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("classStructures 的键与值都不得为 null: " + entry.getKey());
      }
      ClassStructure structure = entry.getValue();
      if (!entry.getKey().equals(structure.id())) {
        throw new IllegalArgumentException(
            "classStructures 的键必须与 ClassStructure.id 一致：键="
                + entry.getKey()
                + "，行内 id="
                + structure.id());
      }
      if (!modesCopy.isEmpty() && !modesCopy.containsKey(structure.modeId())) {
        throw new IllegalArgumentException(
            "classStructures 的 modeId 必须是已存在的生产方式：结构="
                + entry.getKey()
                + "，modeId="
                + structure.modeId());
      }
      structuresCopy.put(entry.getKey(), structure);
    }
    classStructures = Collections.unmodifiableMap(structuresCopy); // ★ 冻在赋值处
    for (ProductionMode mode : modesCopy.values()) {
      if (!structuresCopy.isEmpty() && !structuresCopy.containsKey(mode.classStructureId())) {
        throw new IllegalArgumentException(
            "ProductionMode.classStructureId 必须是已存在的阶层结构：mode="
                + mode.id()
                + "，classStructureId="
                + mode.classStructureId());
      }
    }
    Map<ClassPositionId, ClassPosition> positionsCopy = new LinkedHashMap<>();
    for (Map.Entry<ClassPositionId, ClassPosition> entry : classPositions.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("classPositions 的键与值都不得为 null: " + entry.getKey());
      }
      ClassPosition position = entry.getValue();
      if (!entry.getKey().equals(position.id())) {
        throw new IllegalArgumentException(
            "classPositions 的键必须与 ClassPosition.id 一致：键="
                + entry.getKey()
                + "，行内 id="
                + position.id());
      }
      if (!modesCopy.isEmpty() && !modesCopy.containsKey(position.modeId())) {
        throw new IllegalArgumentException(
            "classPositions 的 modeId 必须是已存在的生产方式：位置="
                + entry.getKey()
                + "，modeId="
                + position.modeId());
      }
      positionsCopy.put(entry.getKey(), position);
    }
    // ★★ 结构内的位置必须与全局位置表逐值相等；全局表也不得残留不属于任何结构的孤儿位置。
    //   同一身份只有一处权威形状，避免"结构里写一套、全局表里另写一套"（对侧为空 = 该侧尚未提供，见段首口径）。
    if (!positionsCopy.isEmpty()) {
      for (ClassStructure structure : structuresCopy.values()) {
        for (Map.Entry<ClassPositionId, ClassPosition> entry : structure.positions().entrySet()) {
          ClassPosition flat = positionsCopy.get(entry.getKey());
          if (flat == null || !flat.equals(entry.getValue())) {
            throw new IllegalArgumentException(
                "classStructures.positions 的位置必须与 classPositions 逐值一致：结构="
                    + structure.id()
                    + "，位置="
                    + entry.getKey());
          }
        }
      }
    }
    if (!structuresCopy.isEmpty()) {
      for (ClassPosition position : positionsCopy.values()) {
        boolean registered = false;
        for (ClassStructure structure : structuresCopy.values()) {
          if (position.equals(structure.positions().get(position.id()))) {
            registered = true;
            break;
          }
        }
        if (!registered) {
          throw new IllegalArgumentException(
              "classPositions 中的位置必须至少属于一个 ClassStructure：位置=" + position.id());
        }
      }
    }
    classPositions = Collections.unmodifiableMap(positionsCopy); // ★ 冻在赋值处
    Map<HouseholdId, ClassStanding> standingsCopy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, ClassStanding> entry : classStandings.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("classStandings 的键与值都不得为 null: " + entry.getKey());
      }
      ClassStanding standing = entry.getValue();
      if (!entry.getKey().equals(standing.householdId())) {
        throw new IllegalArgumentException(
            "classStandings 的键必须与 ClassStanding.householdId 一致：键="
                + entry.getKey()
                + "，行内 householdId="
                + standing.householdId());
      }
      if (!classesCopy.isEmpty() && !classesCopy.containsKey(entry.getKey())) {
        throw new IllegalArgumentException(
            "classStandings 的家户必须是已存在的家户（S1 起身份与视图分离）：" + entry.getKey());
      }
      if (!positionsCopy.isEmpty()) {
        if (!positionsCopy.containsKey(standing.currentPositionId())) {
          throw new IllegalArgumentException(
              "ClassStanding.currentPositionId 必须是已存在的阶层位置：家户="
                  + entry.getKey()
                  + "，当前位置="
                  + standing.currentPositionId());
        }
        if (!positionsCopy.containsKey(standing.originalPositionId())) {
          throw new IllegalArgumentException(
              "ClassStanding.originalPositionId 必须是已存在的阶层位置：家户="
                  + entry.getKey()
                  + "，原所属="
                  + standing.originalPositionId());
        }
        for (ClassPositionId retained : standing.retainedShares().keySet()) {
          if (!positionsCopy.containsKey(retained)) {
            throw new IllegalArgumentException(
                "ClassStanding.retainedShares 的键必须是已存在的阶层位置：家户="
                    + entry.getKey()
                    + "，保留位置="
                    + retained);
          }
        }
        // ★★ P2-B：追加参与的生产位置（多生产方式）必须逐个是已存在的位置 —— 组织阶段按它们建
        //   生产组织/unit，悬空引用会让"可参与"变成组织期的静默跳过。
        for (ClassPositionId participating : standing.participatingPositionIds()) {
          if (!positionsCopy.containsKey(participating)) {
            throw new IllegalArgumentException(
                "ClassStanding.participatingPositionIds 必须是已存在的阶层位置：家户="
                    + entry.getKey()
                    + "，参与位置="
                    + participating);
          }
        }
      }
      standingsCopy.put(entry.getKey(), standing);
    }
    classStandings = Collections.unmodifiableMap(standingsCopy); // ★ 冻在赋值处
    // ── E2 第 21/22 个组件：生产组织 / 生产资料规则 ───────────────────────────────────
    //   ★ 旧档缺键 ⇒ 空表（上面已归一）；两张表为空时本段整体 no-op，旧结算路径逐值不变。
    //   ★ 引用完整性按"对侧是否已提供"分段生效（与 E1 四条同款），保证 with* 能逐组件构造。
    Map<ProductionOrganizationId, ProductionOrganization> organizationsCopy = new LinkedHashMap<>();
    for (Map.Entry<ProductionOrganizationId, ProductionOrganization> entry :
        productionOrganizations.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "productionOrganizations 的键与值都不得为 null: " + entry.getKey());
      }
      ProductionOrganization organization = entry.getValue();
      if (!entry.getKey().equals(organization.id())) {
        throw new IllegalArgumentException(
            "productionOrganizations 的键必须与 ProductionOrganization.id 一致：键="
                + entry.getKey()
                + "，行内 id="
                + organization.id());
      }
      if (!modesCopy.isEmpty() && !modesCopy.containsKey(organization.modeId())) {
        throw new IllegalArgumentException(
            "生产组织的 modeId 必须是已存在的生产方式：组织=" + entry.getKey() + "，modeId=" + organization.modeId());
      }
      if (!positionsCopy.isEmpty() && !positionsCopy.containsKey(organization.classPositionId())) {
        throw new IllegalArgumentException(
            "生产组织的 classPositionId 必须是已存在的阶层位置：组织="
                + entry.getKey()
                + "，位置="
                + organization.classPositionId());
      }
      if (organization.unitId().isPresent()
          && !units.isEmpty()
          && !units.containsKey(organization.unitId().get())) {
        throw new IllegalArgumentException(
            "生产组织指名的 unit 必须已存在：组织=" + entry.getKey() + "，unitId=" + organization.unitId().get());
      }
      if (organization.unitId().isPresent() && units.containsKey(organization.unitId().get())) {
        ProductionUnit organizedUnit = units.get(organization.unitId().get());
        if (!organizedUnit.operator().equals(organization.organizer())) {
          throw new IllegalArgumentException(
              "生产组织的 organizer 必须与它指名 unit 的 operator 一致（同一件事不许两处拼写）：组织="
                  + entry.getKey()
                  + " organizer="
                  + organization.organizer()
                  + "，unit.operator="
                  + organizedUnit.operator());
        }
      }
      for (AssetShareId assetSource : organization.assetSources()) {
        if (!assetShares.isEmpty() && !assetShares.containsKey(assetSource)) {
          throw new IllegalArgumentException(
              "生产组织使用的资产份额必须已存在：组织=" + entry.getKey() + "，份额=" + assetSource);
        }
        if (!assetShares.isEmpty()) {
          AssetShare organizedShare = assetShares.get(assetSource);
          if (organizedShare != null
              && !organizedShare.operator().equals(organization.organizer())) {
            throw new IllegalArgumentException(
                "生产组织使用的 AssetShare 必须由 organizer 经营（operator 一致）：组织="
                    + entry.getKey()
                    + "，份额="
                    + assetSource
                    + "，份额 operator="
                    + organizedShare.operator());
          }
        }
      }
      for (HouseholdId laborSource : organization.laborSources()) {
        if (!classesCopy.isEmpty() && !classesCopy.containsKey(laborSource)) {
          throw new IllegalArgumentException(
              "生产组织的劳动来源家户必须已存在：组织=" + entry.getKey() + "，家户=" + laborSource);
        }
      }
      organizationsCopy.put(entry.getKey(), organization);
    }
    productionOrganizations = Collections.unmodifiableMap(organizationsCopy); // ★ 冻在赋值处
    Map<AssetRuleId, AssetRule> assetRulesCopy = new LinkedHashMap<>();
    Set<String> assetRuleModeKindKeys = new LinkedHashSet<>();
    for (Map.Entry<AssetRuleId, AssetRule> entry : assetRules.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("assetRules 的键与值都不得为 null: " + entry.getKey());
      }
      AssetRule rule = entry.getValue();
      if (!entry.getKey().equals(rule.id())) {
        throw new IllegalArgumentException(
            "assetRules 的键必须与 AssetRule.id 一致：键=" + entry.getKey() + "，行内 id=" + rule.id());
      }
      if (!modesCopy.isEmpty() && !modesCopy.containsKey(rule.modeId())) {
        throw new IllegalArgumentException(
            "生产资料规则的 modeId 必须是已存在的生产方式：规则=" + entry.getKey() + "，modeId=" + rule.modeId());
      }
      // ★ 身份 = (modeId, assetKind) 的纯函数 ⇒ 同一组合只允许一条规则（重复 = 同一件事两个拼写点）。
      String modeKindKey = rule.modeId().value() + "|" + rule.assetKind().name();
      if (!assetRuleModeKindKeys.add(modeKindKey)) {
        throw new IllegalArgumentException(
            "同一 (modeId, assetKind) 只允许一条 AssetRule（重复 = 同一件事两处拼写）: "
                + rule.modeId()
                + " / "
                + rule.assetKind());
      }
      assetRulesCopy.put(entry.getKey(), rule);
    }
    assetRules = Collections.unmodifiableMap(assetRulesCopy); // ★ 冻在赋值处
    // ── E3 第 23/24 个组件：政府（发行主体）与货币发行审计记录 ──────────────────────────────
    //   ★ 政府表：键 == 值内 id；同一 actor 不能同时是两届政府（否则发行记录无法归属），
    //     且一个币种只能有一个发行主体（跨政府判死 —— 不靠"最后写入者赢"）。
    Map<GovernmentId, Government> governmentsCopy = new LinkedHashMap<>();
    Set<ActorRef> treasuries = new LinkedHashSet<>();
    Map<CurrencyId, ActorRef> issuersByCurrency = new LinkedHashMap<>();
    for (Map.Entry<GovernmentId, Government> entry : governments.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("governments 的键与值都不得为 null: " + entry.getKey());
      }
      Government government = entry.getValue();
      if (!entry.getKey().equals(government.id())) {
        throw new IllegalArgumentException(
            "governments 的键必须与 Government.id 一致：键=" + entry.getKey() + "，行内 id=" + government.id());
      }
      if (!treasuries.add(government.treasury())) {
        throw new IllegalArgumentException(
            "同一个国库 actor 不能同时是两届政府（发行记录无法归属）：" + government.treasury());
      }
      for (CurrencyId currency : government.issuable()) {
        ActorRef issuer = government.authorityOf(currency);
        if (issuer == null) {
          throw new IllegalArgumentException(
              "Government.authorityOf 不得为 null：政府=" + government.id() + "，币种=" + currency);
        }
        ActorRef existing = issuersByCurrency.putIfAbsent(currency, issuer);
        if (existing != null && !existing.equals(issuer)) {
          throw new IllegalArgumentException(
              "同一币种只能有一个发行主体：币种="
                  + currency
                  + "，已有发行主体="
                  + existing
                  + "，新发行主体="
                  + issuer
                  + "（政府="
                  + government.id()
                  + "）");
        }
      }
      governmentsCopy.put(entry.getKey(), government);
    }
    governments = Collections.unmodifiableMap(governmentsCopy); // ★ 冻在赋值处
    Map<MoneyIssuanceId, MoneyIssuanceRecord> issuancesCopy = new LinkedHashMap<>();
    for (Map.Entry<MoneyIssuanceId, MoneyIssuanceRecord> entry : moneyIssuances.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("moneyIssuances 的键与值都不得为 null: " + entry.getKey());
      }
      MoneyIssuanceRecord record = entry.getValue();
      if (!entry.getKey().equals(record.id())) {
        throw new IllegalArgumentException(
            "moneyIssuances 的键必须与 MoneyIssuanceRecord.id 一致：键="
                + entry.getKey()
                + "，行内 id="
                + record.id());
      }
      if (!governmentsCopy.containsKey(record.governmentId())) {
        throw new IllegalArgumentException(
            "发行记录的 governmentId 必须是已存在的政府：记录="
                + entry.getKey()
                + "，governmentId="
                + record.governmentId());
      }
      issuancesCopy.put(entry.getKey(), record);
    }
    moneyIssuances = Collections.unmodifiableMap(issuancesCopy); // ★ 冻在赋值处
    // ── E4a 第 25 个组件：质押（Pledge）基础形状 ─────────────────────────────────────────────
    //   ★ 旧档缺键 ⇒ 空表（上面已归一）；空表整体 no-op。
    //   ★ 守卫按“对侧已提供”分段生效（与 E1/E2 的引用完整性同款）：合同表/资产份额表为空 = 该侧尚未提供
    //     ⇒ 只判结构（键、null、quantity/priority 已由 Pledge 构造期判）；非空才判引用与数量上界。
    //   ★ “Σ活跃质押 ≤ share.quantity”只对 ACTIVE 求和；RELEASED/EXECUTED 不再占额度（E5 的释放/执行写口）。
    Map<PledgeId, Pledge> pledgesCopy = new LinkedHashMap<>();
    Map<AssetShareId, Long> activePledgedByShare = new LinkedHashMap<>();
    for (Map.Entry<PledgeId, Pledge> entry : pledges.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("pledges 的键与值都不得为 null: " + entry.getKey());
      }
      Pledge pledge = entry.getValue();
      if (!entry.getKey().equals(pledge.id())) {
        throw new IllegalArgumentException(
            "pledges 的键必须与 Pledge.id 一致：键=" + entry.getKey() + "，行内 id=" + pledge.id());
      }
      if (!debtContractsCopy.isEmpty() && !debtContractsCopy.containsKey(pledge.debtContractId())) {
        throw new IllegalArgumentException(
            "质押指名的债务合同必须已存在：质押=" + entry.getKey() + "，合同=" + pledge.debtContractId());
      }
      if (!assetSharesCopy.isEmpty() && !assetSharesCopy.containsKey(pledge.assetShareId())) {
        throw new IllegalArgumentException(
            "质押指名的资产份额必须已存在：质押=" + entry.getKey() + "，份额=" + pledge.assetShareId());
      }
      if (!modes.isEmpty() && !modes.containsKey(pledge.modeId())) {
        throw new IllegalArgumentException(
            "质押指名的生产方式必须已存在：质押=" + entry.getKey() + "，modeId=" + pledge.modeId());
      }
      if (pledge.status() == Pledge.Status.ACTIVE) {
        activePledgedByShare.merge(pledge.assetShareId(), pledge.quantity(), Math::addExact);
      }
      pledgesCopy.put(entry.getKey(), pledge);
    }
    for (Map.Entry<AssetShareId, Long> entry : activePledgedByShare.entrySet()) {
      AssetShare share = assetSharesCopy.get(entry.getKey());
      if (share == null) {
        continue; // 对侧（资产份额表）尚未提供 ⇒ 数量上界留给该侧就绪后的下一次构造
      }
      if (entry.getValue() > share.quantity()) {
        throw new IllegalArgumentException(
            "Σ活跃质押必须 ≤ 资产份额 quantity：份额="
                + entry.getKey()
                + " 质押合计="
                + entry.getValue()
                + "，份额数量="
                + share.quantity());
      }
    }
    pledges = Collections.unmodifiableMap(pledgesCopy); // ★ 冻在赋值处
    // ── E5a 第 26 个组件：清算政策（键 == 值内 ruleId；assetRules 已提供时被引用规则必须存在）──────────
    //   ★ 守卫按“对侧已提供”分段：assetRules 为空 = 规则侧尚未提供 ⇒ 只判键身份与形状；非空 ⇒ 引用完整性 fail-closed。
    Map<AssetRuleId, LiquidationPolicy> liquidationPoliciesCopy = new LinkedHashMap<>();
    for (Map.Entry<AssetRuleId, LiquidationPolicy> entry : liquidationPolicies.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("liquidationPolicies 的键与值都不得为 null: " + entry.getKey());
      }
      LiquidationPolicy policy = entry.getValue();
      if (!entry.getKey().equals(policy.ruleId())) {
        throw new IllegalArgumentException(
            "liquidationPolicies 的键必须与 LiquidationPolicy.ruleId 一致：键="
                + entry.getKey()
                + "，行内 ruleId="
                + policy.ruleId());
      }
      if (!assetRulesCopy.isEmpty() && !assetRulesCopy.containsKey(policy.ruleId())) {
        throw new IllegalArgumentException(
            "清算政策指名的生产资料规则不存在：政策=" + entry.getKey() + "，assetRules 里没有该规则");
      }
      liquidationPoliciesCopy.put(entry.getKey(), policy);
    }
    liquidationPolicies = Collections.unmodifiableMap(liquidationPoliciesCopy); // ★ 冻在赋值处
    // ── E5a 第 27 个组件：hex 危机信号（键 == 值内 id == CrisisSignalId.idOf(hex, kind)）────────────
    //   ★ 键由 (hex, kind) 确定性派生 ⇒ 同 hex 同 kind 只保留最新一条（写口 put 即覆盖；本表不追加历史）。
    //   ★ 家户表已提供时，信号点名的 households 必须存在（fail-closed）；classes 是 SocialClassId 词表身份，
    //     economy 侧没有以它为键的第二张表，不在构造期另造真相。
    Map<CrisisSignalId, HexCrisisSignal> crisisSignalsCopy = new LinkedHashMap<>();
    for (Map.Entry<CrisisSignalId, HexCrisisSignal> entry : crisisSignals.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("crisisSignals 的键与值都不得为 null: " + entry.getKey());
      }
      HexCrisisSignal signal = entry.getValue();
      if (!entry.getKey().equals(signal.id())) {
        throw new IllegalArgumentException(
            "crisisSignals 的键必须与 HexCrisisSignal.id 一致：键="
                + entry.getKey()
                + "，行内 id="
                + signal.id());
      }
      CrisisSignalId derived = CrisisSignalId.idOf(signal.hex(), signal.kind().name());
      if (!entry.getKey().equals(derived)) {
        throw new IllegalArgumentException(
            "crisisSignals 的键必须由 (hex, kind) 确定性派生（同 hex 同 kind 只保留最新一条）：键="
                + entry.getKey()
                + "，派生="
                + derived);
      }
      if (!classesCopy.isEmpty()) {
        for (HouseholdId household : signal.households()) {
          if (!classesCopy.containsKey(household)) {
            throw new IllegalArgumentException(
                "危机信号点名的家户不存在（家户表已提供 ⇒ fail-closed）：信号=" + entry.getKey() + "，家户=" + household);
          }
        }
      }
      crisisSignalsCopy.put(entry.getKey(), signal);
    }
    crisisSignals = Collections.unmodifiableMap(crisisSignalsCopy); // ★ 冻在赋值处
    // ── E6a 第 28 个组件：模式变迁（键 == 值内 id == (organizationId, toModeId, effectiveDay) 的确定性派生）──
    //   ★ 旧档缺键 ⇒ 空表（上面已归一）；空表 = 没有变迁请求，旧结算路径逐值不变。
    //   ★ 引用完整性按“对侧已提供”分段（与 E1/E2/E5 同款）：modes/productionOrganizations 为空 = 该侧尚未提供
    //     ⇒ 只判结构；非空才判组织/mode 存在与 fromMode 一致性。★ “同一组织至多一条 PENDING”在构造期判死 ——
    //     它是模式变迁命令幂等与“一条 revision 只应用一次”的地基（重复 PENDING 会让同一次切换被两次结算）。
    Map<ModeTransitionId, ModeTransition> modeTransitionsCopy = new LinkedHashMap<>();
    Set<ProductionOrganizationId> pendingOrganizations = new LinkedHashSet<>();
    for (Map.Entry<ModeTransitionId, ModeTransition> entry : modeTransitions.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("modeTransitions 的键与值都不得为 null: " + entry.getKey());
      }
      ModeTransition transition = entry.getValue();
      if (!entry.getKey().equals(transition.id())) {
        throw new IllegalArgumentException(
            "modeTransitions 的键必须与 ModeTransition.id 一致：键="
                + entry.getKey()
                + "，行内 id="
                + transition.id());
      }
      ModeTransitionId derived =
          ModeTransitionId.idOf(
              transition.organizationId(), transition.toModeId(), transition.effectiveDay());
      if (!entry.getKey().equals(derived)) {
        throw new IllegalArgumentException(
            "modeTransitions 的键必须由 (organizationId, toModeId, effectiveDay) 确定性派生"
                + "（同一请求重复提交必须得到同一 id）：键="
                + entry.getKey()
                + "，派生="
                + derived);
      }
      if (!productionOrganizations.isEmpty()
          && !productionOrganizations.containsKey(transition.organizationId())) {
        throw new IllegalArgumentException(
            "模式变迁指名的生产组织不存在：变迁=" + entry.getKey() + "，组织=" + transition.organizationId());
      }
      if (!productionOrganizations.isEmpty()) {
        ProductionOrganization organization =
            productionOrganizations.get(transition.organizationId());
        if (organization != null && !organization.modeId().equals(transition.fromModeId())) {
          throw new IllegalArgumentException(
              "模式变迁的 fromModeId 必须等于组织当前的 modeId（同一件事不许两处拼写）：变迁="
                  + entry.getKey()
                  + "，fromMode="
                  + transition.fromModeId()
                  + "，组织 mode="
                  + organization.modeId());
        }
      }
      if (!modesCopy.isEmpty()) {
        if (!modesCopy.containsKey(transition.fromModeId())) {
          throw new IllegalArgumentException(
              "模式变迁的 fromModeId 必须是已存在的生产方式：变迁="
                  + entry.getKey()
                  + "，fromMode="
                  + transition.fromModeId());
        }
        if (!modesCopy.containsKey(transition.toModeId())) {
          throw new IllegalArgumentException(
              "模式变迁的 toModeId 必须是已存在的生产方式：变迁="
                  + entry.getKey()
                  + "，toMode="
                  + transition.toModeId());
        }
      }
      if (transition.status() == ModeTransition.Status.PENDING
          && !pendingOrganizations.add(transition.organizationId())) {
        throw new IllegalArgumentException(
            "同一生产组织至多允许一条 PENDING 模式变迁（重复会让同一次切换被结算两次）：组织=" + transition.organizationId());
      }
      modeTransitionsCopy.put(entry.getKey(), transition);
    }
    modeTransitions = Collections.unmodifiableMap(modeTransitionsCopy); // ★ 冻在赋值处
    // ── E6a 第 29 个组件：阶层保留份额（键 == 值内 id；同一 (transitionId, householdId) 的 Σ = 1000‰）──────────
    //   ★ 对侧（modeTransitions / classes / classPositions）为空 = 该侧尚未提供 ⇒ 只判结构与分组和；
    //     一旦对侧非空，引用完整性 fail-closed（变迁/家户/位置必须存在）。
    Map<ClassShareId, ClassShare> classSharesCopy = new LinkedHashMap<>();
    Map<String, Long> sharePerGroup = new LinkedHashMap<>();
    for (Map.Entry<ClassShareId, ClassShare> entry : classShares.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("classShares 的键与值都不得为 null: " + entry.getKey());
      }
      ClassShare share = entry.getValue();
      if (!entry.getKey().equals(share.id())) {
        throw new IllegalArgumentException(
            "classShares 的键必须与 ClassShare.id 一致：键=" + entry.getKey() + "，行内 id=" + share.id());
      }
      if (!modeTransitionsCopy.isEmpty()
          && !modeTransitionsCopy.containsKey(share.transitionId())) {
        throw new IllegalArgumentException(
            "阶层保留份额指名的模式变迁不存在：份额=" + entry.getKey() + "，变迁=" + share.transitionId());
      }
      if (!classesCopy.isEmpty() && !classesCopy.containsKey(share.householdId())) {
        throw new IllegalArgumentException(
            "阶层保留份额指名的家户不存在：份额=" + entry.getKey() + "，家户=" + share.householdId());
      }
      if (!positionsCopy.isEmpty() && !positionsCopy.containsKey(share.classPositionId())) {
        throw new IllegalArgumentException(
            "阶层保留份额指名的阶层位置不存在：份额=" + entry.getKey() + "，位置=" + share.classPositionId());
      }
      String groupKey = share.transitionId().value() + "|" + share.householdId().value();
      sharePerGroup.merge(groupKey, share.sharePerMille(), Math::addExact);
      classSharesCopy.put(entry.getKey(), share);
    }
    for (Map.Entry<String, Long> entry : sharePerGroup.entrySet()) {
      if (entry.getValue() != 1000L) {
        throw new IllegalArgumentException(
            "同一 (transitionId, householdId) 的 Σ ClassShare.sharePerMille 必须 = 1000：组="
                + entry.getKey()
                + "，合计="
                + entry.getValue());
      }
    }
    classShares = Collections.unmodifiableMap(classSharesCopy); // ★ 冻在赋值处
    // ── P10.1 第 30 个组件：商号（键 == 值内 organizationId；引用完整性按“对侧已提供”分段）──────────────
    //   ★ productionOrganizations 为空 = 组织侧尚未提供 ⇒ 只判键身份与值形状（tier/容量/金额已由 MerchantFirm 构造期判）。
    //   ★ 组织侧非空 ⇒ fail-closed：每个商号必须指名一个已存在的生产组织，且该组织的 modeId 必须是 merchant
    //     （商号只能挂在商人 mode 的组织上；mode 不符 = 同一件事两处拼写不一致）。
    Map<ProductionOrganizationId, MerchantFirm> merchantFirmsCopy = new LinkedHashMap<>();
    for (Map.Entry<ProductionOrganizationId, MerchantFirm> entry : merchantFirms.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("merchantFirms 的键与值都不得为 null: " + entry.getKey());
      }
      MerchantFirm firm = entry.getValue();
      if (!entry.getKey().equals(firm.organizationId())) {
        throw new IllegalArgumentException(
            "merchantFirms 的键必须与 MerchantFirm.organizationId 一致：键="
                + entry.getKey()
                + "，行内 organizationId="
                + firm.organizationId());
      }
      if (!productionOrganizations.isEmpty()) {
        ProductionOrganization organization = productionOrganizations.get(entry.getKey());
        if (organization == null) {
          throw new IllegalArgumentException(
              "商号指名的生产组织不存在（组织表已提供 ⇒ fail-closed）：商号=" + entry.getKey());
        }
        if (!DefaultProductionModes.MERCHANT.equals(organization.modeId())) {
          throw new IllegalArgumentException(
              "商号对应的生产组织 modeId 必须是 merchant（同一件事不许两处拼写）：商号="
                  + entry.getKey()
                  + "，组织 modeId="
                  + organization.modeId());
        }
      }
      merchantFirmsCopy.put(entry.getKey(), firm);
    }
    merchantFirms = Collections.unmodifiableMap(merchantFirmsCopy); // ★ 冻在赋值处
  }

  /**
   * ★★ R4-E2：一个格在 economy 侧是否"可定位" —— 该格至少登记过一条产业、一行家户，或一张市场。
   *
   * <p>★ <b>为什么市场也算</b>：{@code economy.SetMarketPrice} 可以先给一个尚无家户/产业的格建市场（命令只要求格存在），其后落在该格的 HEX
   * 需求不应因为"还没有家户行"被判成坏状态 —— 需求路径会在订单生成时按当时人口摊到 0 户（合法）。
   *
   * <p>★ <b>为什么没有格键的产业不算</b>：{@code IndustryId} 允许不带格键的值（说不出它在哪一格）⇒ 不拿它当"该格存在"的证据（同 {@code
   * requireIndustryRegistered} 的保守方向）。
   */
  private static boolean hexRegistered(
      Map<IndustryId, Industry> industries,
      Map<HouseholdId, ClassRow> classes,
      Map<HexCoord, Market> markets,
      HexCoord hex) {
    String hexKey = IndustryHexKeys.hexKey(hex.q(), hex.r());
    for (IndustryId id : industries.keySet()) {
      if (IndustryHexKeys.hexKeyOf(id).filter(hexKey::equals).isPresent()) {
        return true;
      }
    }
    for (ClassRow row : classes.values()) {
      if (row.view().hex().equals(hex)) {
        return true;
      }
    }
    return markets.containsKey(hex);
  }

  /**
   * ★★ <b>flows 行的结构引用完整性</b>：flow 行的 view 指出它住在哪一格，该格必须登记过至少一个产业 —— 否则这行流水没有落点。
   *
   * <p>★★ <b>R4-B.4 收窄</b>：本方法**只**判"该格存在产业"。旧版还判 view 必须命中该格产业的 {@code ClassSlot}、并返回最紧槽位供调用方限制
   * {@code participationPerMille}；该槽位耦合已删除 —— {@code Industry.slots} / {@code ClassSlot}
   * 只作为生产方式内部的角色/劳动配置， 不再是 {@code ClassRow.view} 的限制来源。{@code classes} 侧也不再调用本方法（view 完全由 {@code
   * HouseholdClassRule} 纯派生）。
   */
  private static void requireIndustryRegistered(
      Map<IndustryId, Industry> industries, CohortKey key, String what) {
    String hexKey = IndustryHexKeys.hexKey(key.hex().q(), key.hex().r());
    for (Map.Entry<IndustryId, Industry> entry : industries.entrySet()) {
      // ★ **没有格键的产业 id**（{@code IndustryId} 允许这种值，真档里不会出现）：说不出它在哪一格 ⇒
      //   **不拿它来否决**（对任何格都算"可能"）。★ 反过来，带了格键的必须**逐字相等**才算命中 ——
      //   `1_10` 与 `1_1` 因此不会互相误命中（同 {@code IndustryHexKeys.at} 的口径）。
      if (IndustryHexKeys.hexKeyOf(entry.getKey()).filter(hexKey::equals).isEmpty()
          && IndustryHexKeys.hexKeyOf(entry.getKey()).isPresent()) {
        continue;
      }
      return; // 该格有产业登记
    }
    // ★ 消息保留"不存在的产业"这几个字：它是既有的判据用语（{@code EconomyInvariantsTest} 逐字钉着）。
    throw new IllegalArgumentException(
        what + " 引用了不存在的产业（该格上没有登记任何产业）: " + hexKey + "（键=" + key + "）");
  }

  /** 一个组件一个 with（照 {@code LedgerData} 的形制）。 */
  public EconomyData withMeta(Optional<EconomyMeta> value) {
    return new EconomyData(
        value,
        industries,
        classes,
        debtContracts,
        flows,
        allocations,
        relations,
        markets,
        shipments,
        assetShares,
        operatorConditions,
        units,
        demands,
        candidates,
        modes,
        classStructures,
        classPositions,
        classStandings,
        productionOrganizations,
        assetRules,
        governments,
        moneyIssuances,
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        merchantFirms);
  }

  /** 一个组件一个 with（照 {@code LedgerData} 的形制）。 */
  public EconomyData withIndustries(Map<IndustryId, Industry> value) {
    return new EconomyData(
        meta,
        value,
        classes,
        debtContracts,
        flows,
        allocations,
        relations,
        markets,
        shipments,
        assetShares,
        operatorConditions,
        units,
        demands,
        candidates,
        modes,
        classStructures,
        classPositions,
        classStandings,
        productionOrganizations,
        assetRules,
        governments,
        moneyIssuances,
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        merchantFirms);
  }

  /** 一个组件一个 with（照 {@code LedgerData} 的形制）。 */
  public EconomyData withClasses(Map<HouseholdId, ClassRow> value) {
    return new EconomyData(
        meta,
        industries,
        value,
        debtContracts,
        flows,
        allocations,
        relations,
        markets,
        shipments,
        assetShares,
        operatorConditions,
        units,
        demands,
        candidates,
        modes,
        classStructures,
        classPositions,
        classStandings,
        productionOrganizations,
        assetRules,
        governments,
        moneyIssuances,
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        merchantFirms);
  }

  /**
   * ★★ E4a：债务合同表（第 4 个组件，替换旧的 {@code debts} 槽）—— 键 = {@link DebtContractId}， 值 = {@link
   * DebtContract}。其余 29 个组件原样带过（全表共 30 个组件）。
   */
  public EconomyData withDebtContracts(Map<DebtContractId, DebtContract> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        value,
        flows,
        allocations,
        relations,
        markets,
        shipments,
        assetShares,
        operatorConditions,
        units,
        demands,
        candidates,
        modes,
        classStructures,
        classPositions,
        classStandings,
        productionOrganizations,
        assetRules,
        governments,
        moneyIssuances,
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        merchantFirms);
  }

  /** 一个组件一个 with（照 {@code LedgerData} 的形制）。 */
  public EconomyData withFlows(Map<HouseholdId, FlowRow> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debtContracts,
        value,
        allocations,
        relations,
        markets,
        shipments,
        assetShares,
        operatorConditions,
        units,
        demands,
        candidates,
        modes,
        classStructures,
        classPositions,
        classStandings,
        productionOrganizations,
        assetRules,
        governments,
        moneyIssuances,
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        merchantFirms);
  }

  public EconomyData withAllocations(Map<LaborAllocationId, LaborAllocation> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debtContracts,
        flows,
        value,
        relations,
        markets,
        shipments,
        assetShares,
        operatorConditions,
        units,
        demands,
        candidates,
        modes,
        classStructures,
        classPositions,
        classStandings,
        productionOrganizations,
        assetRules,
        governments,
        moneyIssuances,
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        merchantFirms);
  }

  /** 一个组件一个 with（T2：生产关系表）；其余 29 个组件原样带过（全表共 30 个组件）。 */
  public EconomyData withRelations(Map<ProductionUnitId, ProductionRelation> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debtContracts,
        flows,
        allocations,
        value,
        markets,
        shipments,
        assetShares,
        operatorConditions,
        units,
        demands,
        candidates,
        modes,
        classStructures,
        classPositions,
        classStandings,
        productionOrganizations,
        assetRules,
        governments,
        moneyIssuances,
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        merchantFirms);
  }

  /**
   * 一个组件一个 with（H4：市场表）；其余 29 个组件原样带过（全表共 30 个组件）。
   *
   * <p>★ <b>它是"GM 定价格"的唯一写入口</b>（铁律 2：所有修改最终表示为 Command → ChangeSet → Revision）——
   * 本批还没有"设价"命令，故它现在只被载荷（创世播种）与用例用到；命令留待 GM 参数目录落地。
   */
  public EconomyData withMarkets(Map<HexCoord, Market> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debtContracts,
        flows,
        allocations,
        relations,
        value,
        shipments,
        assetShares,
        operatorConditions,
        units,
        demands,
        candidates,
        modes,
        classStructures,
        classPositions,
        classStandings,
        productionOrganizations,
        assetRules,
        governments,
        moneyIssuances,
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        merchantFirms);
  }

  /**
   * ★★ <b>第 10 个组件（M2.4）：在途批次表</b>；其余 29 个组件原样带过（全表共 30 个组件）。
   *
   * <p>★ 与 {@link #withMarkets} 同款：它是"跨 tick 状态"的唯一写入口（在日循环的到货销账与发运建账里被调用）， 不是 GM 命令面。
   */
  public EconomyData withShipments(Map<ShipmentId, ShipmentBatch> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debtContracts,
        flows,
        allocations,
        relations,
        markets,
        value,
        assetShares,
        operatorConditions,
        units,
        demands,
        candidates,
        modes,
        classStructures,
        classPositions,
        classStandings,
        productionOrganizations,
        assetRules,
        governments,
        moneyIssuances,
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        merchantFirms);
  }

  public EconomyData withAssetShares(Map<AssetShareId, AssetShare> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debtContracts,
        flows,
        allocations,
        relations,
        markets,
        shipments,
        value,
        operatorConditions,
        units,
        demands,
        candidates,
        modes,
        classStructures,
        classPositions,
        classStandings,
        productionOrganizations,
        assetRules,
        governments,
        moneyIssuances,
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        merchantFirms);
  }

  /** 一个组件一个 with（S3.2：经营者状态表）；其余 29 个组件原样带过（全表共 30 个组件）。 */
  public EconomyData withOperatorConditions(Map<ProductionUnitId, OperatorCondition> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debtContracts,
        flows,
        allocations,
        relations,
        markets,
        shipments,
        assetShares,
        value,
        units,
        demands,
        candidates,
        modes,
        classStructures,
        classPositions,
        classStandings,
        productionOrganizations,
        assetRules,
        governments,
        moneyIssuances,
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        merchantFirms);
  }

  /** ★★ R3B.2：生产单元表（第 14 个组件）；其余 29 个组件原样带过（全表共 30 个组件）。 */
  public EconomyData withUnits(Map<ProductionUnitId, ProductionUnit> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debtContracts,
        flows,
        allocations,
        relations,
        markets,
        shipments,
        assetShares,
        operatorConditions,
        value,
        demands,
        candidates,
        modes,
        classStructures,
        classPositions,
        classStandings,
        productionOrganizations,
        assetRules,
        governments,
        moneyIssuances,
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        merchantFirms);
  }

  /** ★★ R4-E2：需求账本（第 15 个组件）；其余 29 个组件原样带过（全表共 30 个组件）（GM 命令的唯一写入口）。 */
  public EconomyData withDemands(Map<DemandId, DemandEntry> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debtContracts,
        flows,
        allocations,
        relations,
        markets,
        shipments,
        assetShares,
        operatorConditions,
        units,
        value,
        candidates,
        modes,
        classStructures,
        classPositions,
        classStandings,
        productionOrganizations,
        assetRules,
        governments,
        moneyIssuances,
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        merchantFirms);
  }

  /** ★★ R4-E2：候选预设表（第 16 个组件）；其余 29 个组件原样带过（全表共 30 个组件）（GM 命令的唯一写入口）。 */
  public EconomyData withCandidates(Map<CandidateId, ProductionCandidate> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debtContracts,
        flows,
        allocations,
        relations,
        markets,
        shipments,
        assetShares,
        operatorConditions,
        units,
        demands,
        value,
        modes,
        classStructures,
        classPositions,
        classStandings,
        productionOrganizations,
        assetRules,
        governments,
        moneyIssuances,
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        merchantFirms);
  }

  /** ★★ E1：生产方式表（第 17 个组件）；其余 29 个组件原样带过（全表共 30 个组件）。 */
  public EconomyData withModes(Map<ProductionModeId, ProductionMode> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debtContracts,
        flows,
        allocations,
        relations,
        markets,
        shipments,
        assetShares,
        operatorConditions,
        units,
        demands,
        candidates,
        value,
        classStructures,
        classPositions,
        classStandings,
        productionOrganizations,
        assetRules,
        governments,
        moneyIssuances,
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        merchantFirms);
  }

  /** ★★ E1：阶层结构表（第 18 个组件）；其余 29 个组件原样带过（全表共 30 个组件）。 */
  public EconomyData withClassStructures(Map<ClassStructureId, ClassStructure> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debtContracts,
        flows,
        allocations,
        relations,
        markets,
        shipments,
        assetShares,
        operatorConditions,
        units,
        demands,
        candidates,
        modes,
        value,
        classPositions,
        classStandings,
        productionOrganizations,
        assetRules,
        governments,
        moneyIssuances,
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        merchantFirms);
  }

  /** ★★ E1：阶层位置表（第 19 个组件）；其余 29 个组件原样带过（全表共 30 个组件）。 */
  public EconomyData withClassPositions(Map<ClassPositionId, ClassPosition> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debtContracts,
        flows,
        allocations,
        relations,
        markets,
        shipments,
        assetShares,
        operatorConditions,
        units,
        demands,
        candidates,
        modes,
        classStructures,
        value,
        classStandings,
        productionOrganizations,
        assetRules,
        governments,
        moneyIssuances,
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        merchantFirms);
  }

  /**
   * ★★ <b>E1/P7：阶层结构与全局阶层位置表的成对写口</b>（第 18/19 两个组件一次落值）；其余 29 个组件原样带过（全表共 30 个组件）。
   *
   * <p>★ <b>为什么必须成对</b>：{@link ClassStructure#positions()} 与全局 {@code classPositions} 有"逐值相等 +
   * 每条位置至少属于一个结构"的双向守卫，而现有 {@code withClassStructures}/{@code withClassPositions} 各自只改一个组件 ——
   * 单独改任一侧都会让中间态过不了守卫（"结构里写一套、全局表里另写一套"）。GM 编辑（{@code economy.GmAdjust} 的
   * upsertClassStructure/upsertClassPosition）需要同时 upsert 结构与全局位置，故这里提供唯一的成对落值口；
   * <b>不是第二套状态</b>：两个参数就是那两个既有组件，最终仍走同一个 canonical 构造器与全部守卫。
   */
  public EconomyData withClassStructuresAndPositions(
      Map<ClassStructureId, ClassStructure> structures,
      Map<ClassPositionId, ClassPosition> positions) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debtContracts,
        flows,
        allocations,
        relations,
        markets,
        shipments,
        assetShares,
        operatorConditions,
        units,
        demands,
        candidates,
        modes,
        structures,
        positions,
        classStandings,
        productionOrganizations,
        assetRules,
        governments,
        moneyIssuances,
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        merchantFirms);
  }

  /** ★★ E1：家户阶层归属表（第 20 个组件）；其余 29 个组件原样带过（全表共 30 个组件）。 */
  public EconomyData withClassStandings(Map<HouseholdId, ClassStanding> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debtContracts,
        flows,
        allocations,
        relations,
        markets,
        shipments,
        assetShares,
        operatorConditions,
        units,
        demands,
        candidates,
        modes,
        classStructures,
        classPositions,
        value,
        productionOrganizations,
        assetRules,
        governments,
        moneyIssuances,
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        merchantFirms);
  }

  /** ★★ E2：生产组织表（第 21 个组件）；其余 29 个组件原样带过（全表共 30 个组件）。 */
  public EconomyData withProductionOrganizations(
      Map<ProductionOrganizationId, ProductionOrganization> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debtContracts,
        flows,
        allocations,
        relations,
        markets,
        shipments,
        assetShares,
        operatorConditions,
        units,
        demands,
        candidates,
        modes,
        classStructures,
        classPositions,
        classStandings,
        value,
        assetRules,
        governments,
        moneyIssuances,
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        merchantFirms);
  }

  /** ★★ E2：生产资料规则表（第 22 个组件）；其余 29 个组件原样带过（全表共 30 个组件）。 */
  public EconomyData withAssetRules(Map<AssetRuleId, AssetRule> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debtContracts,
        flows,
        allocations,
        relations,
        markets,
        shipments,
        assetShares,
        operatorConditions,
        units,
        demands,
        candidates,
        modes,
        classStructures,
        classPositions,
        classStandings,
        productionOrganizations,
        value,
        governments,
        moneyIssuances,
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        merchantFirms);
  }

  /** ★★ E3：政府表（第 23 个组件）；其余 29 个组件原样带过（全表共 30 个组件）。 */
  public EconomyData withGovernments(Map<GovernmentId, Government> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debtContracts,
        flows,
        allocations,
        relations,
        markets,
        shipments,
        assetShares,
        operatorConditions,
        units,
        demands,
        candidates,
        modes,
        classStructures,
        classPositions,
        classStandings,
        productionOrganizations,
        assetRules,
        value,
        moneyIssuances,
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        merchantFirms);
  }

  /** ★★ E3：货币发行审计表（第 24 个组件）；其余 29 个组件原样带过（全表共 30 个组件）。 */
  public EconomyData withMoneyIssuances(Map<MoneyIssuanceId, MoneyIssuanceRecord> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debtContracts,
        flows,
        allocations,
        relations,
        markets,
        shipments,
        assetShares,
        operatorConditions,
        units,
        demands,
        candidates,
        modes,
        classStructures,
        classPositions,
        classStandings,
        productionOrganizations,
        assetRules,
        governments,
        value,
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        merchantFirms);
  }

  /**
   * ★★ E4a：质押表（第 25 个组件，追加在末尾）；其余 29 个组件原样带过（全表共 30 个组件）。
   *
   * <p>★ {@code pledges} 为空 = 没有质押；本方法只让调用方逐组件构造，跨表守卫 （Σ活跃质押 ≤ share.quantity）仍由规范构造器按“对侧已提供”分段判。
   */
  public EconomyData withPledges(Map<PledgeId, Pledge> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debtContracts,
        flows,
        allocations,
        relations,
        markets,
        shipments,
        assetShares,
        operatorConditions,
        units,
        demands,
        candidates,
        modes,
        classStructures,
        classPositions,
        classStandings,
        productionOrganizations,
        assetRules,
        governments,
        moneyIssuances,
        value,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        merchantFirms);
  }

  /**
   * ★★ E5a：清算政策表（第 26 个组件）；其余 29 个组件原样带过（全表共 30 个组件）。
   *
   * <p>★ 键 == 值内 {@code ruleId}；{@code assetRules} 非空时被引用规则必须存在 —— 两条守卫都由规范构造器 fail-closed。 空表 =
   * 没有清算制度参数，旧行为逐值不变。
   */
  public EconomyData withLiquidationPolicies(Map<AssetRuleId, LiquidationPolicy> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debtContracts,
        flows,
        allocations,
        relations,
        markets,
        shipments,
        assetShares,
        operatorConditions,
        units,
        demands,
        candidates,
        modes,
        classStructures,
        classPositions,
        classStandings,
        productionOrganizations,
        assetRules,
        governments,
        moneyIssuances,
        pledges,
        value,
        crisisSignals,
        modeTransitions,
        classShares,
        merchantFirms);
  }

  /**
   * ★★ E5a：hex 危机信号表（第 27 个组件，追加在末尾）；其余 29 个组件原样带过（全表共 30 个组件）。
   *
   * <p>★ 键 == 值内 id == {@code CrisisSignalId.idOf(hex, kind)}；同 hex 同 kind 覆盖即更新（不追加历史）。 空表 =
   * 没有信号；E5a 不产生任何信号。
   */
  public EconomyData withCrisisSignals(Map<CrisisSignalId, HexCrisisSignal> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debtContracts,
        flows,
        allocations,
        relations,
        markets,
        shipments,
        assetShares,
        operatorConditions,
        units,
        demands,
        candidates,
        modes,
        classStructures,
        classPositions,
        classStandings,
        productionOrganizations,
        assetRules,
        governments,
        moneyIssuances,
        pledges,
        liquidationPolicies,
        value,
        modeTransitions,
        classShares,
        merchantFirms);
  }

  /**
   * ★★ E6a：模式变迁表（第 28 个组件）；其余 29 个组件原样带过（全表共 30 个组件）。
   *
   * <p>★ 键 == 值内 id == {@code ModeTransitionId.idOf(organizationId, toModeId,
   * effectiveDay)}；同一组织至多一条 PENDING。空表 = 没有模式变迁请求，旧行为逐值不变。
   */
  public EconomyData withModeTransitions(Map<ModeTransitionId, ModeTransition> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debtContracts,
        flows,
        allocations,
        relations,
        markets,
        shipments,
        assetShares,
        operatorConditions,
        units,
        demands,
        candidates,
        modes,
        classStructures,
        classPositions,
        classStandings,
        productionOrganizations,
        assetRules,
        governments,
        moneyIssuances,
        pledges,
        liquidationPolicies,
        crisisSignals,
        value,
        classShares,
        merchantFirms);
  }

  /**
   * ★★ E6a：阶层保留份额表（第 29 个组件，追加在末尾）；其余 29 个组件原样带过（全表共 30 个组件）。
   *
   * <p>★ 键 == 值内 id == {@code ClassShareId.idOf(transitionId, householdId, classPositionId)}；同一
   * {@code (transitionId, householdId)} 的 Σ sharePerMille 必须 = 1000（规范构造器逐组判）。空表 = 没有份额记录。
   */
  public EconomyData withClassShares(Map<ClassShareId, ClassShare> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debtContracts,
        flows,
        allocations,
        relations,
        markets,
        shipments,
        assetShares,
        operatorConditions,
        units,
        demands,
        candidates,
        modes,
        classStructures,
        classPositions,
        classStandings,
        productionOrganizations,
        assetRules,
        governments,
        moneyIssuances,
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        value,
        merchantFirms);
  }

  /**
   * ★★ P10.1：商号表（第 30 个组件，追加在末尾）；其余 29 个组件原样带过（全表共 30 个组件）。
   *
   * <p>键 = {@link ProductionOrganizationId}，且必须等于值内 {@link MerchantFirm#organizationId()}。本批只落持久形状与
   * 构造期引用守卫（组织表已提供时，商号必须挂在 {@code modeId = merchant} 的现存组织上）；运力增减 / 农村惩罚 / 承运选择等结算行为留给
   * P10.2+，本方法不产生任何数值行为。
   */
  public EconomyData withMerchantFirms(Map<ProductionOrganizationId, MerchantFirm> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debtContracts,
        flows,
        allocations,
        relations,
        markets,
        shipments,
        assetShares,
        operatorConditions,
        units,
        demands,
        candidates,
        modes,
        classStructures,
        classPositions,
        classStandings,
        productionOrganizations,
        assetRules,
        governments,
        moneyIssuances,
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        value);
  }

  /** {@link ProductionUnitId#idOf} 的固定前缀（唯一拼写点；用来识别"这看起来是一个 unit id"）。 */
  private static final String UNIT_ID_PREFIX = "unit-";

  /** ★★ B.2b：任一 Industry 带非中性旧档兼容位 ⇒ 构造期归一化（见 compact 构造器那一段）。 */
  private static boolean hasLegacyProductionBits(Map<IndustryId, Industry> industries) {
    for (Industry industry : industries.values()) {
      if (industry != null && hasLegacyProductionBits(industry)) {
        return true;
      }
    }
    return false;
  }

  /**
   * ★★ B.2b：单个 {@link Industry} 的旧档兼容位判据（与修复规格逐字一致）：{@code operator != null || progressDays > 0 ||
   * cycleLaborMilli > 0 || !capacity.isEmpty() || !cycleInputUsedMilli.isEmpty()}。
   *
   * <p>★ 新形状（12 参构造器 / 归一化后）恒为 {@code false} —— 这是"codec 已整形路径 no-op、Timeline 直读 changeset
   * 路径兜底"的幂等边界。
   */
  private static boolean hasLegacyProductionBits(Industry industry) {
    return industry.operator() != null
        || industry.progressDays() > 0L
        || industry.cycleLaborMilli() > 0L
        || !industry.capacity().isEmpty()
        || !industry.cycleInputUsedMilli().isEmpty();
  }

  /**
   * ★★ S1：把 {@link Recipient.ToCohort} 一对一归一到 {@link Recipient.ToHousehold}。
   *
   * <p>判据：<b>该 view 恰有一个家户</b>（旧档的既有事实）⇒ 迁移；view 有歧义（S3 才允许）⇒ 保留旧变体交由 S3
   * 的视图选择器解释。归一化是构造期的纯函数、幂等（归一后的规则不再含 ToCohort）。
   *
   * <p>★ <b>S3 审计</b>：索引每次都用**当前** {@code classes} 行集合重建，不缓存历史 view。默认关系在第一次构造时就已归一为 {@code
   * ToHousehold(稳定身份)} ⇒ 之后的阶层写回只改 {@code ClassRow.view}，不会让这些规则改指到别的家户；仍保留的 {@code ToCohort}
   * 是旧档/多义视图的兼容窄口，按当前行集合解释。
   */
  private static Map<ProductionUnitId, ProductionRelation> normalizeRecipients(
      Map<ProductionUnitId, ProductionRelation> raw, Map<HouseholdId, ClassRow> classes) {
    Map<CohortKey, HouseholdId> unique = new LinkedHashMap<>();
    Set<CohortKey> ambiguous = new LinkedHashSet<>();
    for (ClassRow row : classes.values()) {
      if (unique.putIfAbsent(row.view(), row.id()) != null) {
        ambiguous.add(row.view());
      }
    }
    for (CohortKey view : ambiguous) {
      unique.remove(view);
    }
    Map<ProductionUnitId, ProductionRelation> normalized = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, ProductionRelation> entry : raw.entrySet()) {
      ProductionRelation relation = entry.getValue();
      List<CompensationRule> rules = new ArrayList<>(relation.rules().size());
      boolean changed = false;
      for (CompensationRule rule : relation.rules()) {
        if (rule.recipient() instanceof Recipient.ToCohort toCohort) {
          HouseholdId household = unique.get(toCohort.cohort());
          if (household != null) {
            rules.add(
                new CompensationRule(
                    rule.type(),
                    new Recipient.ToHousehold(household),
                    rule.pool(),
                    rule.weight(),
                    rule.ratePerMille(),
                    rule.fixedAmount(),
                    rule.commodity(),
                    rule.currency(),
                    rule.priority()));
            changed = true;
            continue;
          }
        }
        rules.add(rule);
      }
      normalized.put(
          entry.getKey(),
          changed
              ? new ProductionRelation(
                  relation.activity(),
                  relation.operator(),
                  relation.inputSupplier(),
                  rules,
                  relation.residualOwner(),
                  relation.laborSource())
              : relation);
    }
    return normalized;
  }
}
