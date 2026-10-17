package io.mosire.simos.economy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.fx.OfficialRate;
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
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
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
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.market.GovernmentMarketMandate;
import io.mosire.simos.economy.api.market.MarketMandateId;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.api.money.CurrencyDef;
import io.mosire.simos.economy.api.money.MoneyInstrument;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.api.stock.HouseholdPeriodicAdjustment;
import io.mosire.simos.economy.api.stock.PeriodicHouseholdAdjustmentId;
import io.mosire.simos.economy.migrate.DebtReferenceReconciler;
import io.mosire.simos.economy.model.AssetRule;
import io.mosire.simos.economy.model.ClassShare;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.CommodityFreightBase;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.EconomyPolicyKey;
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
import io.mosire.simos.economy.model.MarketTables;
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
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * <p>★ **三十五个组件与 {@link io.mosire.simos.economy.change.EconomyChangeSet} 的三十五个组件一一对应**（铁律 5）：
 * 新增状态组件必须同时进变更集，由 {@code EconomyRoundTripTest} 的反射枚举把守。
 *
 * <p>★★ **E1–E6 追加组件清单**（逐阶段；数字是本记录全表的组件序号/个数，E6b/E6c 零新状态组件）：
 *
 * <ul>
 *   <li>E1（第 18–20 个，3 个）：{@code classStructures} / {@code classPositions} / {@code
 *       classStandings}（★ 第 17 个 {@code modes} 已由 B5 删除，见下）；
 *   <li>E2（第 21–22 个，2 个）：{@code productionOrganizations} / {@code assetRules}；
 *   <li>E3（第 23–24 个，2 个）：{@code governments} / {@code moneyIssuances}；
 *   <li>E4（第 25 个，另替换第 4 个组件的旧 {@code debts} 槽为 {@code debtContracts}）：{@code pledges}；
 *   <li>E5（第 26–27 个，2 个）：{@code liquidationPolicies} / {@code crisisSignals}；
 *   <li>E6（第 28–29 个，2 个）：{@code modeTransitions} / {@code classShares}；
 *   <li>★ <b>P10.1 的第 30 个组件 {@code merchantFirms}（商号表）已于 2026-10-10 M-A1 退役</b>（用户裁定："旧设计和数据类型
 *       直接重建不用留"）—— 运力改为**派生量**（{@code MerchantCapacityPool}），不再有任何持久组件承接它。组件号沿用 历史编号，不作重排。
 *   <li>P4a（第 31 个，1 个）：{@code periodicAdjustments}（周期家户库存扣增规则表，见 {@link
 *       HouseholdPeriodicAdjustment}； 键 = 值内 id；P4a 只落规则与无状态到期执行，单位政策留 P4b）。
 *   <li>★ <b>R1（第 39 个，1 个）：{@code govMarketMandates}</b>（政府市场授权表 = 行政家户挂单的授权凭据，见 {@link
 *       GovernmentMarketMandate}；键 = {@link MarketMandateId}）。★ 它是"政府只按明确授权下单"的落点： 国库户（{@code
 *       hh-gov-*}）回到商品市场后<b>不生成任何自动订单</b>，其订单全部来自本表；空表 ⇒ 零订单。 本组件不改价格/成本、不豁免市场规则（用户 2026-10-09 §1.3
 *       禁止"额外再加一个调整机制"，不变量 I-P6）。
 * </ul>
 *
 * E6b（GM 经济调整命令与预览审计）与 E6c（统一 dashboard 读口）都只读/写既有组件，**不追加新状态组件**， 故本记录的全表组件数在 E6 之后仍为 **29 个组件**；★
 * <b>P10.1 的第 30 个组件 {@code merchantFirms} 已退役（2026-10-10 M-A1）</b>；★ <b>P4a 追加 {@code
 * periodicAdjustments}</b>（周期家户库存扣增规则表，见 {@link HouseholdPeriodicAdjustment}）。逐条对应关系以 {@link
 * EconomyChangeSet} 的组件列为准（组件数：M-A1 起比 P10.1 时期少 1）。
 *
 * <p>★★ **跨表同键不变式**（§6.2 的身份部分）：{@code classes} 的每个键必须等于其 {@link HouseholdEconomy#key()}；{@code
 * flows} 的每个键必须等于其 {@link FlowRow#key()}；{@code laborSupply} / {@code allocations} 同理各自等于行内的 group
 * / id。 否则同一份身份就有两处可能不一致的记录。
 *
 * <p>★★ **R2：本阶段最重要的不变量在这里判死**（第三阶段设计稿 §四；★ Z7d-1 按 kind 分账）：
 *
 * <pre>
 * Σ_{PRODUCTION} laborMilli  ≤  laborMilli − min(Σ_{GOV_SERVICE} laborMilli, laborMilli)
 * </pre>
 *
 * 它**必须**在构造期判，而不是在结算里"顺手算对"：劳动是**可分配但不能凭空重复**的资源（本轮的目标原话），而"同一批人被两个产业各算一次满额" 正是设计稿 §一.2
 * 实测出的空洞。判在构造期 ⇒ 任何一条路径（命令、旧档读入、夹具、将来的协调器）都不可能造出"生产配额超过可支配劳动"的状态—— 那正是本仓栽过的同族教训（{@code progressDays
 * == cycleDays} 与 {@code WageFirst}：模型允许的状态，结算与用例都得处理）。
 *
 * <p>★★ <b>Z7d-1 的 underfed 口径</b>（用户 2026-10-23："Social 层面饥饿系数应当直接决定单 tick 家户能提供的劳动力"）： {@code
 * GOV_SERVICE} 是**职位/诉求**，允许 `Σ GOV_SERVICE > 饿少后的 laborMilli`（C7 不静默缩/删）；此时上式让 PRODUCTION 可用量归
 * 0。**有效供给 = min(该户承诺, 该户实际劳动)** 由 app 的 {@code GovernmentServiceLaborBridge} 逐户 cap —— 经济侧构造期不再对
 * GOV_SERVICE 单独 fail-closed。
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
 * ProductionRules} 不另造 id —— 身份就是它结算的那个 {@code activity}，铁律 1），值 = 一次生产的结算规则。 ★ 两条**跨表守卫**：
 *
 * <ol>
 *   <li>每个键**必须**是该格上已存在的产业（"{@code 关系指名的产业不存在}"）——否则结算时按 id 取不到产业；
 *   <li>{@code relations[k].operator()} **必须**等于该实例的 {@code operator}：**同一件事不许有两处拼写**
 *       （"谁经营"若能在关系表里另写一遍，两边不一致时没有任何一处能判谁对）；键还**必须**等于 {@code ProductionRules.activity()}（同 {@code
 *       classes}/{@code flows} 的"键 == 值内 key"口径）。★ 这条判据读 production 的实例投影，落点是 {@code
 *       EconomyReferenceValidation.validateUnitCrossReferences}（B2b 起；B4 起构造期不再持有定义副本）。
 * </ol>
 *
 * <p>★ <b>S1 起 {@code classes}/{@code flows} 的键都是稳定家户身份 {@link HouseholdId}</b>（旧档的 {@link
 * CohortKey} 由 {@code EconomyCodec} 读入时映射成 {@code HouseholdIds.ofLegacy}）：键不再随地点/阶层变化（铁律 1），
 * 行的当前视图住在 {@code HouseholdEconomy.view}。⇒ "这个产业有哪些行"不再由键的产业段回答，而由**劳动配额表**推（{@code 旧结算引擎（R3a
 * 已删除）.householdKeysOf}，唯一拼写点）—— 一个家户给两个产业出劳动时，它<b>只有一行</b>（V9/I1.2）。
 *
 * <p>★★ <b>{@code marketTables} 是第 9 个组件</b>（H4；裁定 M1-A；★★ <b>W1 起每区一张</b>）：键 = {@link
 * MarketZoneId}（**市场**：持久形态 = 区 id；派生形态 = 派生市场 id），值 = {@link MarketTable} （本市场**单一计价货币** + 成员格 +
 * 一张商品价格表）。★ 四条口径：
 *
 * <ol>
 *   <li><b>缺键 = 那个市场没有价表</b> —— ★★ 2026-10-11 W1d：持久形态下这也是一种<b>合法状态</b>（"缺表 = 该区不交易任何商品"， 照设计书 §4.2
 *       N-4 的"没有这一行 = 不交易该商品"同一条语义；W1 引入的"每个区必须有价表"守卫已删，理由见构造期那一节）。 派生形态下空表 = 世上没有市场。★ 所有读点对缺表一律
 *       fail-closed 到"不交易"（{@code MarketTables.at} 返回空、 {@code MarketIndex.at} 返回 {@code
 *       null}、拓扑不装配该区），**不抛、不 NPE、不造默认价**；
 *   <li>★★ <b>键是市场而不是格</b>（W1 的形状变更）：改动前它是逐格的（键 = {@code HexCoord}）⇒ 同一区里每格各存一份"逐字
 *       相同"的价表，而区内撮合实际只取锚格那一份 —— 存储与语义长期不一致，还养出 {@code adaptPrices} 的"逐成员对齐"补丁。
 *       现在"这一格按什么价报价"由**本格所属市场**回答（{@code MarketTables.at} 的唯一拼写点），区内任何格取价一致是**结构上必然**；
 *   <li><b>成员格与计价币的权威按形态给</b>：持久区看 {@code marketZones} 的 {@code hexes}/{@code legalTender}（I22
 *       单一权威）； 派生市场看价表条目自己的 {@code hexes}/{@code numeraire}（那一形态下没有 {@code MarketZone} 可问）； ★ <b>键
 *       ∈ 区表 ⇒ 计价币必须逐值等于该区 {@code legalTender}</b>（构造期守卫判死漂开）—— "区 + 它的价表" 要成对变化时走 {@link
 *       #withMarketZonesAndTables}（原子写口，中间态不经过守卫）；
 *   <li>★ <b>它不含任何数量</b>：价格是数据、供需是每周期现算的（{@code MarketSettlement} 从家户账的会话工作副本读） ——
 *       市场表里没有"本期成交量"这类会过期的读数（★ W1 起**市场轮统计簿**是本表的独立组件 {@code marketRoundStats}）。
 * </ol>
 *
 * <p>★★ **{@code shipments} 是第 10 个组件**（M2.4）：键 = {@link ShipmentId}，值 = {@link
 * ShipmentBatch}（在途批次）。★ 它是**跨 tick 状态**：发运日建、到货日销；到货前目的地消费不到它。
 *
 * <p>★★ **S1 的第 12 个组件 {@code assetShares}**（键 = {@link AssetShareId}；R3B.1 起是<b>独立的实物资产份额总账</b>，
 * 逐行判"键 == 值内 id / industry 存在 / 非空 / quantity ≥ 0"；★ <b>没有</b>"Σ quantity ≤
 * IndustryDefinitionView.capacity" 这类 把技术模板与实物账本绑死的上界守卫）。★ 第 13 个组件 {@code
 * operatorConditions}（{@link OperatorCondition}；S3.2 的经营者状态机）由 S1 一次性补齐，本阶段空表缺省。★ <b>P2-A
 * A3：成员份额已从本切片整体删除</b>——家户人口组成的唯一权威是 Social 的 {@code Household.members}，Economy
 * 只在结算入口读一份只读投影（不进状态、不进变更集/Codec）。
 *
 * <p>★★ **E1 追加第 18–20 个组件**（{@code classStructures} / {@code classPositions} / {@code
 * classStandings}）：它们建立"阶层结构 → 阶层位置 → 家户归属"的权威状态。★ **E1 不接线结算**： 这三张表为空时，旧 {@code
 * HouseholdClassRule}、旧 {@code settle*} 路径与旧档行为逐值不变；有值时也只做状态与读口， 旧路径仍以 {@code
 * HouseholdEconomy.view} 为准（见 {@code HouseholdClassMembership} 的类注）。★ 跨表守卫按"对侧是否已提供"分段生效， 以便 {@code
 * with*} 能逐组件构造；两侧都非空时引用完整性 fail-closed。
 *
 * <p>★★ <b>B5（§13.15.2 裁定十八）：第 17 个组件 {@code modes} 已整段删除</b> —— 生产方式定义（{@code
 * ProductionModeDefinitionView}）的唯一权威在 production（{@code ProductionData.modes}，经 {@code
 * production.contract.ProductionDefinitionLookup#modes()} 只读）。economy 侧不再有任何 mode 定义副本、
 * 也没有它的写口；原"结构/位置/组织/规则/质押/变迁 → mode 存在性"六维守卫的落点是候选态守卫 {@code
 * EconomyReferenceValidation#validateModeAssociations}，反向"mode → 结构存在性"落点是 {@code
 * validateModeStructures}（两者都在 {@code EconomyProductionReferenceValidator} 里，注册点 {@code
 * Shell.java:1199}）。
 *
 * <p>★★ **E2 追加第 21–22 个组件**（{@code productionOrganizations} / {@code assetRules}）：前者是"生产方式 + 阶层结构
 * + 劳动 + 资产"之间的桥（{@code ProductionEnterprise}），由日结算的自动组织阶段 upsert；后者是 mode 下每种生产资料的
 * 租佃/抵押/清算/转移规则（{@code AssetRule}）。★ 两张表为空时自动组织阶段整体 no-op，旧路径逐值不变；非空时的引用完整性同样按 "对侧是否已提供"分段生效（unit /
 * ownershipStake / productionRole / mode 存在性）。
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
 *
 * <p>★★ <b>B2 追加第 36 个组件 {@code marketZones}</b>（阶段 2-B2，2026-10-08；约束设计书 §4.2/§4.3；不变量 I22）：键 =
 * {@link MarketZoneId}，值 = {@link MarketZone}（区 id + 锚格 + 声明半径 + <b>成员格</b> + 法定币 + 区级官方汇率覆盖）。 ★★
 * <b>它是"市场区的单一权威"</b>：空表 = 旧世界形态（市场区仍按"城市 + tier 半径"派生，既有世界逐值不变）；非空 = 一个 hex
 * 属于哪个区<b>由本表给定</b>，派生路径只服务空表。★ 四条 fail-closed 守卫（键 == 值内 zoneId、一个 hex 至多一个区、法定币与区级汇率 币对必须在币种表里）见
 * compact 构造器里那一段。★★ <b>2026-10-09 C 批：{@code issuingGov} 退役</b>（用户「法定货币发行者也丢掉」； 设计书 §4.1）——
 * 区只记"这一片用哪种钱"，"谁管这种钱"由 {@code Government.issuable} <b>反查</b>回答 （{@code
 * MarketZoneBook.possibleIssuersOf}，I-M8），故原来那条"发行政府必须登记且 {@code issuable} 必须含法定币"的跨表守卫一并删除
 * （§4.3：立区不管"谁发得出"）。
 *
 * <p>★★ <b>2026-10-09 选项 A 追加第 37 个组件 {@code householdDebtRefs}</b>（用户批准的"先用上选项 A，验证效率提升"）： 键 =
 * {@link HouseholdDebtReference}（{@code 家户@合同}），值 = 标记位 {@code Boolean.TRUE}。<b>它是"某家户是某合同的债务人"这一条
 * 派生索引</b>，唯一权威仍是 {@link #debtContracts()}；旧档缺该键 ⇒ 空表，构造期由 {@code DebtReferenceReconciler}
 * 按合同表<b>整表重建</b> ⇒ 旧世界逐值不变（等于改前那份逐行 {@code HouseholdEconomy.debts} 的重建结果）。
 *
 * <p>★★ <b>为什么必须拆出来</b>：改前那份引用列表住在每一行里（实测 ~3.7KB/户 ≈ 50 条合同 id），而 {@code FieldDelta.diff}
 * 是<b>行级</b>差异 —— 行里任何一个字段变（每天 203/203 户都变的 {@code cycleNaturalNeedMilli} 只有 6
 * 字节）就会把<b>整行</b>（含那一大坨不变的引用）序列化进 Upsert，占总变更集的 ~62%。 拆成"一户一债一对"的行之后，每个 tick
 * 只落盘<b>真正变化的引用对</b>（现实中每天最多几对）。 ★ 对账/守卫的语义逐条不变（见 {@code
 * DebtReferenceReconciler}）：合同表是权威、引用表被重建、悬空引用仍然具名抛。
 *
 * <p>★★ <b>第 38 个组件 {@code commodityFreightBaseMilli}</b>（2026-10-09 用户裁定「甲」后的<b>纠正版</b>；约束设计书 §4.1
 * 修正版）：键 = {@link CommodityId}，值 = 该商品的<b>基础运费</b>（毫计价货币 / 商品单位 / <b>程</b>）—— <b>与商品价格无关</b>，
 * 只由商品种类决定（用户 2026-10-09 原话口径「运费只和商品种类有关」）。 ★ <b>值域 ≥ 0</b>：{@code 0} 是<b>明确的</b>"该商品免基础费" （单位运费仍被
 * {@code max(1, …)} 抬到 1 毫），负值在 compact 构造器里具名判死。 ★ <b>缺键 ⇒ 取具名缺省</b> = {@link
 * CommodityFreightBase#legacyMilli(CommodityId)}（= 现行硬编码分档：粮 1 / 纤维 1 / 布 2 / 工具 3；未登记商品 ⇒ 1）；读取一律走
 * {@link #commodityFreightBaseMilliOf(CommodityId)}，唯一算式在 {@code
 * MarketSettlement.freightUnitMilli}。 ★ <b>空表 = 旧世界形态</b>：旧档缺该组件键 ⇒ 空表 ⇒ 每个商品都落到现行硬编码分档 ⇒
 * 既有世界（{@code small-world}/{@code corridor}/{@code three-powers}）<b>逐值不变</b>（不变量 I-F1）。 ★
 * <b>键域</b>（必须是词表里的商品）在 GM 命令边界判（见 {@code economy.SetCommodityFreight}），状态层不反查词表 ——
 * 于是旧档/夹具能带任意商品键而不炸。 ★ 历史留痕：F 批（{@code f3366545}）曾用同一个组件名装"运费<b>系数</b>（‰，乘在整条费率上）"， 那是被撤销的方向（费率乘数维
 * + 面值维 = 两个商品维相乘）；本组件现在是<b>面值维的唯一权威</b>。
 */
// ★ 豁免 EI_EXPOSE_REP（R4a，canonical verify 实测 28 条）：本 record 的每张表都在 compact 构造器里逐键复制 +
//   Collections.unmodifiableMap（见下方各 *Copy 段），访问器返回的是冻结副本、调用方改不动。SpotBugs 对
//   28 个 Map 记录组件的生成访问器保守报"暴露内部表示"；ArmyPlan 已有同类豁免先例。
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification =
        "全部 Map 组件（含 P4a periodicAdjustments、Z1 的 outputQuantityOverrides/productionEfficiency、"
            + " 2026-10-09 选项 A 的 householdDebtRefs 与 R1 的 govMarketMandates）均在 compact 构造器内逐键复制并"
            + " Collections.unmodifiableMap；访问器返回冻结副本")
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
// ★★ B2（2026-10-10）：本记录**自己实现** {@link EconomyDayView}（"我就是我自己的当刻值"）——
//   段级/读口/GM 工具那些本来就在 revision 边界的调用方传 EconomyData 时逐字不变；
//   只有一次 advance 的**日循环内部**改传工作副本视图（WorkingDayView），见该接口的类注。
//   ★ 加 implements 不改任何组件/形状/编解码：record 的组件访问器原样满足接口（方法名与返回类型逐字相同）。
public record EconomyData(
    Optional<EconomyMeta> meta,
    // ★★★ D-032 B4（§13.14.2 裁定十五）：第 2 个组件 `industries`（产业定义副本）**整段删除** ——
    //   它是 I1 在 economy 侧的最后一份"可独立修改的方案定义权威"。定义权威唯一归 production
    //   （`IndustryDefinitionView` 的持久面在 ProductionData），economy 侧读定义一律经
    //   `EconomyDayView.definitions()`（production.contract 的当刻只读投影）。
    //   ★ 本记录**仍然 implements EconomyDayView**，但 `industries()` 从此走接口的 default
    //     （`definitions().industries()`）：裸 EconomyData 没有 definitions ⇒ 误用当场 fail-closed
    //     （IllegalStateException），而不是静默读一张空表。
    Map<HouseholdId, HouseholdEconomy> classes,
    Map<DebtContractId, DebtContract> debtContracts,
    Map<HouseholdId, FlowRow> flows,
    Map<LaborAllocationId, HouseholdLaborCommitment> allocations,
    Map<ProductionUnitId, ProductionRules> relations,
    Map<MarketZoneId, MarketTable> marketTables,
    Map<ShipmentId, ShipmentBatch> shipments,
    Map<AssetShareId, OwnershipStake> assetShares,
    Map<ProductionUnitId, OperatorCondition> operatorConditions,
    // ★★★ B2b（§13.11.2 裁定六 / §13.11.4 第 2 条）：第 14 个组件 `units`、第 32 个组件
    //   `outputQuantityOverrides`、第 33 个组件 `productionEfficiency` **整段删除**。
    //   ⇒ 生产实例 / GM 产出覆盖 / 效率累计余数的唯一权威只有 production（I1）；
    //     economy 侧读它们一律经 `production.contract` 的当刻只读投影（I13），不再有第二份可独立修改的数值。
    //   ★ 旧档不兼容：Alpha 口径 —— 缺这些键的旧档当场由其余守卫/未知键 fail-closed。
    Map<DemandId, HouseholdDemand> demands,
    // ★★★ B2c（§13.12.2 裁定八 / §13.12.6 第 1 条）：第 16 个组件 `candidates`（候选预设表）**整段删除**
    //   —— 候选面退役：可以采用的东西就是 production 里的**方案定义**（`IndustryDefinitionView`，
    //   `hexKeyOf(id)` 为空的那些是"不绑地点的方案"）。economy 不再持有第二份定义面（I1）。
    //   ★ 它的五个字段的落点见裁定八：version 删 / output 删概念（产出按商品逐项）/
    //     buildDays 与 acceptedRightKinds 搬进方案定义 / assetSource 留 economy
    //     （`EconomyMeta.planAssetSources`）。
    // ★★★ B5（§13.15.2 裁定十八）：第 17 个组件 `modes`（生产方式定义副本）**整段删除** ——
    //   它是 I1 在 economy 侧的**最后一个第二权威**：定义权威唯一归 production
    //   （`ProductionData.modes`，经 `production.contract.ProductionDefinitionLookup#modes()` 只读）。
    //   economy 侧原来的两条写腿：① 播种（载荷 `modes[]`）② GM 命令 `economy.GmAdjust`
    //   的 `upsertProductionMode` / `deactivateProductionMode` 两个 kind —— 均已删除；
    //   写面唯一落在 production 既有的 `production.UpsertProductionMode` /
    // `production.DeactivateProductionMode`
    //   （装配点 `Shell.java:925/:926`）。
    //   ★ 定义迁移面写全，见 {@code EconomyReferenceValidation#validateModeAssociations} /
    //     {@code validateModeStructures}（候选态守卫，读 production 当刻权威）。
    Map<ClassStructureId, ClassStructure> classStructures,
    Map<ClassPositionId, ProductionRole> classPositions,
    Map<HouseholdId, HouseholdClassMembership> classStandings,
    Map<ProductionOrganizationId, ProductionEnterprise> productionOrganizations,
    Map<AssetRuleId, AssetRule> assetRules,
    Map<GovernmentId, Government> governments,
    Map<MoneyIssuanceId, MoneyIssuanceRecord> moneyIssuances,
    Map<PledgeId, Pledge> pledges,
    Map<AssetRuleId, LiquidationPolicy> liquidationPolicies,
    Map<CrisisSignalId, HexCrisisSignal> crisisSignals,
    Map<ModeTransitionId, ModeTransition> modeTransitions,
    Map<ClassShareId, ClassShare> classShares,
    Map<PeriodicHouseholdAdjustmentId, HouseholdPeriodicAdjustment> periodicAdjustments,
    // ── A1（2026-10-08）货币身份的两个新组件：词表入世界状态（约束设计书 §3.1-2）────────────────
    Map<CurrencyId, CurrencyDef> currencies,
    Map<InstrumentId, MoneyInstrument> moneyInstruments,
    // ── B2（2026-10-08）市场区的持久状态（约束设计书 §4.2 / 不变量 I22）────────────────────────
    //   第 36 个组件。★ 空表 = 旧世界形态：市场区仍按"城市 + tier 半径"派生（MarketTopologyBook），
    //   既有 small-world / corridor / v17levant 逐值不变；非空 = 成员格由本表**唯一给定**（单一权威）。
    Map<MarketZoneId, MarketZone> marketZones,
    // ── 2026-10-09 选项 A：家户债务引用派生索引（第 37 个组件）──────────────────────────────
    //   键 = HouseholdDebtReference（家户@合同），值 = 标记位（Boolean.TRUE）。
    //   ★ 空表 = "这个档还没有引用表"（旧档缺键）⇒ 构造期由 DebtReferenceReconciler 从 debtContracts 重建，
    //     逐值等于改前 HouseholdEconomy.debts 的逐行重建结果（旧世界逐值不变）。
    //   ★ 它**不是**第二本债务账：合同表 debtContracts 是唯一权威，本表只是"该户是某合同的债务人"的派生索引。
    Map<HouseholdDebtReference, Boolean> householdDebtRefs,
    // ── 第 38 个组件（2026-10-09 纠正版）：商品**基础运费**表（约束设计书 §4.1 修正版）────────────────────
    //   键 = CommodityId，值 = 该商品的基础运费（毫计价货币 / 商品单位 / 程）—— 与商品价格无关。
    //   ★ 缺键 ⇒ 具名缺省 CommodityFreightBase.legacyMilli（现行硬编码分档：粮1/纤维1/布2/工具3；未登记 ⇒ 1）
    //     ⇒ 空表（旧档缺键 / 新世界创世 / 夹具）= 现行行为，逐值不变（I-F1）。
    //   ★ 值域 ≥ 0（0 = 明确的"该商品免基础费"；负值 ⇒ 构造期具名抛）；
    //   ★ 商品维**只**活在这一处：TransportTariff 已回到纯距离/辐射/道路/城乡维（不许两个商品维）。
    Map<CommodityId, Long> commodityFreightBaseMilli,
    // ── 第 39 个组件（R1，2026-10-09 口岸/效率约束设计书 §4.5 G8/I-P6）：**政府市场授权表** ─────────────
    //   键 = MarketMandateId，值 = 一次「明确授权下单」（谁/商品/方向/量/限价/到期）。
    //   ★★ 它是"政府只按明确授权下单"的落点：国库户（hh-gov-*）回到商品市场后**不生成任何自动订单**
    //     （见 MarketSettlement 的 z7b 守卫），能出现的政府订单**全部**来自本表。
    //   ★ 空表 = 旧世界形态（没有任何授权 ⇒ 国库户在市场上零订单 ⇒ 与 Z7b 逐值同行为，I-P8）。
    //   ★ 它不是政策层/补贴层（用户 §1.3 明确禁止）：不改价格、不改成本、不豁免任何市场规则，
    //     订单仍走既有的 家户→订单→撮合→结算 全链路。
    Map<MarketMandateId, GovernmentMarketMandate> govMarketMandates,
    // ── ★★ W1（2026-10-10）第 40 个组件：市场轮统计簿 ────────────────────────────────────────
    //   键 = 市场 id（区 / 派生市场），值 = 该市场的三段固定窗口（最近一轮明细 + 本周期累计 + 上周期累计）。
    //   ★ 它是**纯读数**（写入在市场轮结算之后，不参与任何撮合/定价判定）；窗口固定 ⇒ 状态不随天数增长。
    //   ★ 空表 = 这个档还没有过任何市场轮（合法状态；新世界第一轮之前）。
    //   ★ 与 marketTables 的关系：**不要求**键在 marketTables 里 —— 市场被撤（ClearRegion/Merge）之后，
    //     它的历史统计仍是历史事实（读口按键直取，不做"键必须仍存在"的守卫）。
    Map<MarketZoneId, MarketRoundStats> marketRoundStats,
    // ── ★★★ D-032 C2（D2 §5.3）：第 32 个组件 —— 生产参与名册（unit → roster）────────────────────
    //   键 == 值内 unit；空表合法（= 没有任何名册）。名册是"谁以什么角色参与"的**唯一权威**：
    //   production 只从执行请求接收，不持久持有；economy 不得从 ProductionEnterprise / ProductionRules 反推角色。
    //   ★ D2/D3/D4 组件号约束：31 = C2 前基线；本组件 = 32；D3 只新增 33–35；D4 后续 36/37。
    Map<ProductionUnitId, ProductionRoster> productionRosters,
    // ── ★★★ D-032 W2a（D3 §2.2/§2.6）：第 33/34 个组件 ─────────────────────────────────────
    //   33 = 候选级工资默认（实例存在前）；34 = 执行期工资岗位（实例存在后的唯一报价权威）。
    //   空表合法；第 35 个 wageCarryRemainders 由 W2b 追加在末尾。
    Map<WageDefaultKey, ProductionWageDefault> productionWageDefaults,
    Map<WagePostingKey, ProductionWagePosting> productionWagePostings,
    // ── ★★★ D-032 W2b（D3 §2.4/§2.6）：第 35 个组件 —— 逐 (unit, role, worker) 工资进位余数（0..999）──
    Map<WageCarryKey, Long> wageCarryRemainders)
    implements EconomyDayView {

  /** ★★ <b>Z1：产品产出数量覆盖表的数量上界</b>（§3.1：{@code 值 ∈ [0, 1_000_000]}，防溢出）。命令边界与 load/构造边界共用这一处拼写。 */
  public static final long MAX_OUTPUT_QUANTITY = 1_000_000L;

  /**
   * ★★ <b>Z1：{@code outputQuantityOverrides} 构造期违约的稳定前缀</b>（§3.1）。
   *
   * <p>该组件的守卫在 {@link #EconomyData} 构造期内 fail-closed；{@link
   * io.mosire.simos.economy.codec.EconomyCodec} 在载入时把 Jackson 包装过的原因按本前缀识别为<b>契约 ERROR</b> （事件
   * {@code PRODUCTION_EFFICIENCY_CONTRACT}），因此它是跨类契约、不能改成自由文案。
   */
  public static final String OUTPUT_QUANTITY_OVERRIDE_CONTRACT_PREFIX =
      "outputQuantityOverrides 契约违约：";

  /**
   * ★★ <b>{@code commodityFreightBaseMilli} 构造期违约的稳定前缀</b>（约束设计书 §4.1 修正版）。
   *
   * <p>该组件的值域守卫（{@code ≥ 0}）在 {@link #EconomyData} 构造期内 fail-closed；留给 {@code EconomyCodec} 按本前缀把
   * Jackson 包装后的原因识别成可读的契约违约（与 Z1 的 {@link #OUTPUT_QUANTITY_OVERRIDE_CONTRACT_PREFIX}
   * 同款：跨类契约、不能改成自由文案）。
   */
  public static final String COMMODITY_FREIGHT_CONTRACT_PREFIX = "commodityFreightBaseMilli 契约违约：";

  /**
   * ★★★ <b>W10：政策读口</b>（{@link EconomyDayView#economyPolicyOf} 的实现）—— 缺键 ⇒ 该键的具名缺省（= 改造前的常量值）。
   *
   * <p>★ {@code meta} 为空（切片未激活）⇒ 一律缺省（与"表里没有这个键"同一支，不制造第二档语义）。
   */
  @Override
  public long economyPolicyOf(EconomyPolicyKey key) {
    Objects.requireNonNull(key, "key");
    return meta.map(value -> value.policyOf(key)).orElseGet(key::defaultValue);
  }

  /** ★★ <b>W10：该政策键是否被显式配置过</b>（{@link EconomyDayView#economyPolicyConfigured} 的实现）。 */
  @Override
  public boolean economyPolicyConfigured(EconomyPolicyKey key) {
    Objects.requireNonNull(key, "key");
    return meta.map(value -> value.economyPolicies().containsKey(key)).orElse(false);
  }

  /**
   * ★★ <b>某个商品的<b>基础运费</b>（毫计价货币 / 商品单位 / 程）—— "缺键 ⇒ 具名缺省" 的读取唯一拼写点</b>。
   *
   * <p>表里有该商品的键 ⇒ <b>状态值唯一权威</b>（GM 设过就照它，{@code 0} = 免基础费）；表里没有 ⇒ {@link
   * CommodityFreightBase#legacyMilli(CommodityId)}（= 现行硬编码分档：粮 1 / 纤维 1 / 布 2 / 工具 3；未登记商品 ⇒ 1） ⇒
   * <b>未设表的世界逐值等于改动前</b>（I-F1）。★ 表里不会有"等于现行缺省"的冗余行（命令层拒绝"与现值逐字相同"）。
   */
  public long commodityFreightBaseMilliOf(CommodityId commodity) {
    Objects.requireNonNull(commodity, "commodity");
    Long configured = commodityFreightBaseMilli.get(commodity);
    return configured == null ? CommodityFreightBase.legacyMilli(commodity) : configured;
  }

  /**
   * ★★ <b>追加裁定 12（2026-10-12 §21.9 D-1/D-2）：某个 GOV 单位对应的政府记录</b>——身份派生走既有 {@link
   * GovernmentIds#ofUnit(String)}（{@code gov-unit-<govUnitId>}，纯字符串换算，<b>不引入 unit 依赖</b>）。
   *
   * <p>★★ <b>为什么读侧要这一个方法</b>：三类政府政策（口岸 / 预算 / 编制）现在都挂在这条记录上，而<b>五个读口</b>（口岸管制折算桥 / 政府采购优先级桥 / 官吏工资桥
   * / 预算执行桥 / 缴解桥）拿到的都是 {@code UnitId}。把"单位 id → 政府记录 → 政策"这条换算收在<b>一处</b>， 才能保证 I-B6（身份 = {@code
   * GovernmentId}）与 I-B2（缺省语义唯一拼写点）都只有一个落点。
   *
   * <p>★ <b>fail-closed</b>：没有这条政府记录 ⇒ 当场抛（{@code
   * IllegalArgumentException}）——"没有政府记录"与"这个政府存在但没设过政策" 是<b>两件事</b>：前者是状态缺失（不猜、不静默给中性默认），后者才走
   * {@link #governmentOrDefault(String)} 的中性默认（I-B1）。
   *
   * @param govUnitId GOV 单位稳定 id 的<b>裸值</b>（**不是** {@code GovernmentId}；换算由 {@link
   *     GovernmentIds#ofUnit(String)} 做）—— ★ 收 {@code String} 而不是 {@code
   *     io.mosire.simos.unit.UnitId}： economy <b>不 import {@code
   *     simos-unit}</b>（D-2/I-B5），这条换算本来就是纯字符串事实
   * @throws IllegalArgumentException 单位 id 不能派生政府身份（空白/坏字符）、或该政府未登记
   */
  public Government governmentOf(String govUnitId) {
    Objects.requireNonNull(govUnitId, "govUnitId");
    GovernmentId governmentId;
    try {
      governmentId = GovernmentIds.ofUnit(govUnitId);
    } catch (IllegalArgumentException bad) {
      throw new IllegalArgumentException(
          "GOV 单位 id 不能派生政府身份（GovernmentIds.ofUnit）: " + govUnitId, bad);
    }
    Government government = governments.get(governmentId);
    if (government == null) {
      throw new IllegalArgumentException(
          "该 GOV 单位没有对应的政府记录（本世界没登记过它，或它不是 GOV 单位）: " + govUnitId + " ⇒ " + governmentId.value());
    }
    return government;
  }

  /**
   * ★★ <b>该 GOV 单位的政府记录；没有这条记录 ⇒ 中性缺省</b>（口岸不限制 / 预算不自动付 / 编制中性，I-B1）。
   *
   * <p>★ <b>它是"缺省语义的唯一拼写点"</b>（I-B2）：缺省值不在这里现拼 —— 它就是 {@code Government} 记录自身携带的缺省 （{@code
   * GovPortPolicy.empty()} / {@code GovBudgetPolicy.neutral()} / {@code
   * GovAdministrationPlan.neutral()}，构造期 恒非 null），本方法只是"查不到就交一份全中性的政府壳"。
   *
   * <p>★ 为什么"查不到"要给中性而不是抛：读侧的口径是 {@code GovState.portPolicyOrDefault} 的既有语义（"没设过 = 不限制"），
   * 且三不管/无政府的世界必须逐值退回改前行为（I-P1/I-C2/I-B1）。
   */
  public Government governmentOrDefault(String govUnitId) {
    Objects.requireNonNull(govUnitId, "govUnitId");
    GovernmentId governmentId;
    try {
      governmentId = GovernmentIds.ofUnit(govUnitId);
    } catch (IllegalArgumentException bad) {
      return neutralGovernment(govUnitId);
    }
    Government government = governments.get(governmentId);
    return government == null ? neutralGovernment(govUnitId) : government;
  }

  /**
   * 中性缺省政府壳（{@link #governmentOrDefault(String)} 专用）：三类政策都取各自的中性值，其余字段取"无主体"的合法占位。
   *
   * <p>★ 国库用 {@code GOVERNMENT/<govUnitId>} 的占位 actor（{@code Government.treasury} 的 ActorKind 判据只认
   * {@code GOVERNMENT}/{@code HOUSEHOLD}）；{@code issuable} 空集 = 非发行人；铸币旋钮 0 =
   * 不许铸币。它的<b>唯一用途</b>是承载中性政策值， 调用方只应读它的三个政策字段。
   */
  private static Government neutralGovernment(String govUnitId) {
    return new Government(
        new GovernmentId(GovernmentIds.UNIT_PREFIX + govUnitId),
        "neutral",
        new ActorRef(ActorKind.GOVERNMENT, govUnitId),
        Set.of());
  }

  /**
   * 往返用例的起点：未激活 + 34 张空表。
   *
   * <p>★★ B2b（§13.11.2 裁定六）：{@code units} / {@code outputQuantityOverrides} / {@code
   * productionEfficiency} 三个组件已从本类型删除；★★ B2c（§13.12.2 裁定八）：第四个 {@code candidates} 同批删净（唯一权威在
   * production）⇒ 四个「旧组件面便捷构造器」 一并删除：本类型只剩 canonical 构造器与 {@code with*}，不再有「取空表兜底」的第二条构造口径。
   */
  public static EconomyData empty() {
    return new EconomyData(
        Optional.empty(), // meta
        Map.of(), // classes
        Map.of(), // debtContracts
        Map.of(), // flows
        Map.of(), // allocations
        Map.of(), // relations
        Map.of(), // marketTables
        Map.of(), // shipments
        Map.of(), // assetShares
        Map.of(), // operatorConditions
        Map.of(), // demands
        // ★★ B5（裁定十八）：`modes`（第 17 个组件）已从本类型删除 ⇒ 无该实参。
        Map.of(), // classStructures
        Map.of(), // classPositions
        Map.of(), // classStandings
        Map.of(), // productionOrganizations
        Map.of(), // assetRules
        Map.of(), // governments
        Map.of(), // moneyIssuances
        Map.of(), // pledges
        Map.of(), // liquidationPolicies
        Map.of(), // crisisSignals
        Map.of(), // modeTransitions
        Map.of(), // classShares
        Map.of(), // periodicAdjustments
        Map.of(), // currencies
        Map.of(), // moneyInstruments
        Map.of(), // marketZones
        Map.of(), // householdDebtRefs
        Map.of(), // commodityFreightBaseMilli
        Map.of(), // govMarketMandates
        Map.of(), // marketRoundStats
        Map.of(), // productionRosters
        Map.of(), // productionWageDefaults
        Map.of(), // productionWagePostings
        Map.of()); // wageCarryRemainders
  }

  public EconomyData {
    // ★ 缺键（null）⇒ 未激活 / 空表，见类注释（旧档兼容，fail-closed 方向）。
    if (meta == null) {
      meta = Optional.empty();
    }
    if (classes == null) {
      classes = Map.of();
    }
    if (debtContracts == null) {
      debtContracts = Map.of();
    }
    // ★★ 2026-10-09 选项 A 第 37 个组件（家户债务引用派生索引 householdDebtRefs）：旧档缺键 ⇒ 空表 ——
    //    ★ 空表**不是**"这个档没有债"，而是"这个档还没有引用表"：后面 classes/debtContracts 那一段会用
    //    DebtReferenceReconciler 按合同表**整表重建**它 ⇒ 旧档读回后逐值等于改前 HouseholdEconomy.debts
    //    的逐行重建结果（旧世界逐值不变）。
    if (householdDebtRefs == null) {
      householdDebtRefs = Map.of();
    }
    // ★★ 第 38 个组件（商品基础运费表）：旧档缺键 ⇒ 空表 —— 空表**不是**"运费为 0"，而是"这个档还没设过基础费"：
    //    缺键一律落到 CommodityFreightBase.legacyMilli（= 现行硬编码分档：粮1/纤维1/布2/工具3；未登记 ⇒ 1）
    //    ⇒ 未设表的世界逐值等于改动前（I-F1），见 commodityFreightBaseMilliOf。
    if (commodityFreightBaseMilli == null) {
      commodityFreightBaseMilli = Map.of();
    }
    // ★★ R1 的第 39 个组件（政府市场授权表）：旧档缺键 ⇒ 空表（同上面每一条的口径）。
    //   空表 = 没有任何授权 ⇒ 国库户在商品市场上零订单（与 Z7b 的"退出商品市场"在行为上逐值相同）。
    if (govMarketMandates == null) {
      govMarketMandates = Map.of();
    }
    if (flows == null) {
      flows = Map.of();
    }
    // ★ R2 的两个新组件：同一口径（缺键 ⇒ 空表，见类注释）。
    if (allocations == null) {
      allocations = Map.of();
    }
    // ★ S1 的新组件：同一口径（缺键 ⇒ 空表，见类注释）。
    //   （★ B2b：旧档迁移段已整段删除 —— 旧档一律作废，见下面 units 组件处的说明）。
    if (assetShares == null) {
      assetShares = Map.of();
    }
    // ★ S3 预留的第 13 个组件（S3.2 的经营者状态机）：缺键 ⇒ 空表（与其余组件同一条旧档兼容口径）。
    if (operatorConditions == null) {
      operatorConditions = Map.of();
    }
    // ★★ R4-E2 第 15 个组件（需求账本）：缺键 ⇒ 空表（旧档不因缺键失败）。
    //   ★★ B2b/B2c：第 14 个组件 `units` 与第 16 个组件 `candidates` 已从本类型删除（唯一权威在 production）
    //     —— 它们的"缺键 ⇒ 空表"归一随之删除（没有该组件可归一）。
    if (demands == null) {
      demands = Map.of();
    }
    // ★★ E1 的三个新组件（阶层结构/阶层位置/家户阶层归属）：旧档缺键 ⇒ 空表（同一条旧档兼容口径；
    //   新状态为空时旧结算路径逐值不变）。空表不是"坏数据"，是"这个档还没有新地基"。
    //   ★★ B5（裁定十八）：生产方式定义副本 `modes` 已删 ⇒ 它的"缺键 ⇒ 空表"归一随之删除。
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
    // ★★ P4a 的第 31 个组件（周期家户扣增规则）：旧档缺键 ⇒ 空表（同上面每一条的口径）。
    if (periodicAdjustments == null) {
      periodicAdjustments = Map.of();
    }
    // ★ 第 8 个组件（S1 阶段 4+5 Task 2）：同一口径（缺键 ⇒ 空表，见类注释）。★ 迁移器要读它，故提到迁移之前。
    if (relations == null) {
      relations = Map.of();
    }
    // ★★ R3B.2b 的"旧档 IndustryDefinitionView 兼容位"归一化段（默认 unit + 整额 OWNED OwnershipStake）
    //   已随 D-032 A 批**整段删除**：那 5 个兼容位（operator / progressDays / capacity / cycleLaborMilli /
    //   cycleInputUsedMilli）**不再是** {@link IndustryDefinitionView} 的组件 —— 实例身份/地点/进度/逐家户
    //   周期贡献已按边界裁决搬进 {@code production.contract.ProductionProcess}，本类型不再承载运行实例字段。
    //   ⇒ "从定义上读出实例字段"在本类型上**无法表达**（读不到 = 编译期就断），故这里没有可机械改写的等价物；
    //   旧档归一化的 JSON 侧仍在（{@code EconomyCodec.migrateLegacyProductionComponents} 造 units/assetShares
    // 节点），
    //   那一条路径不经过本段。★ 旧档兼容整体作废（Alpha 不兼容旧档），本删除不是"删守卫让编译过"。
    // ★★★ B2b（D-032 §13.11.2 裁定六）：**旧档自动迁移段整段删除**（原 `LegacyHouseholdMigration.needed/migrate`
    //   调用就在这一行位）。它服务的唯一对象是**旧档**（CohortKey 占位家户 `legacy-pending-*`、旧 industry 串键的
    //   relations/operatorConditions、旧活动标签配额），而 Alpha 明令**旧存档一律作废**（`docs/agents/02-一.11`）
    //   ⇒ 为它保留一条"构造期在所有守卫之前自动改写状态"的路径，等于让已作废的旧形状继续定义新状态。
    //   ★ 为什么必须删而不是"静默跳过"：本段是 units 组件线（第 14 个组件）的**唯一构造期写者**——它存在一天，
    //     `units` 就不能从本类型删掉（B2 批的删除卡点，见 §13.11.1/§13.11.2）。
    //   ★ 删后旧档的判据全部落到**下面的守卫**上（fail-closed，不是静默放行）：pending 家户在
    //     classes/allocations 的键一致性守卫处具名抛；悬空 relations/operatorConditions 在 units 守卫处具名抛。
    //   ★ 迁移器文件本体（`economy/migrate/LegacyHouseholdMigration.java`）同批删除。
    // ★ 空表 = **全归 residualOwner 的等价路径**（裁定 E9）：没有规则不是坏数据，是"全部自留"。
    // ★ 第 9 个组件（H4）：同一口径（缺键 ⇒ 空表 = 世界上一个市场都没有，见类注释）。
    if (marketTables == null) {
      marketTables = Map.of(); // 归一方向：缺表 = 没有任何市场（见类注）
    }
    Map<HouseholdId, HouseholdEconomy> householdEconomiesCopy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, HouseholdEconomy> householdEconomyEntry : classes.entrySet()) {
      if (householdEconomyEntry.getKey() == null || householdEconomyEntry.getValue() == null) {
        throw new IllegalArgumentException(
            "classes 的键与值都不得为 null: " + householdEconomyEntry.getKey());
      }
      if (!householdEconomyEntry.getKey().equals(householdEconomyEntry.getValue().id())) {
        throw new IllegalArgumentException(
            "classes 的键必须与 ClassRow.id 一致（S1 起键 = 家户稳定身份）：键="
                + householdEconomyEntry.getKey()
                + "，行内 id="
                + householdEconomyEntry.getValue().id());
      }
      HouseholdEconomy householdEconomy = householdEconomyEntry.getValue();
      // ★★ R4-B.4（R3 决策单 §0.1/§1.5，R3B.4）：**view 不再受 IndustryDefinitionView.slots 约束**。
      //   旧守卫（view 必须命中该格产业的 slots 且 participationPerMille ≤ 该 slot 的
      //   laborParticipationPerMille）已删除；IndustryDefinitionView.slots / ClassSlot
      // 只作为生产方式内部的角色/劳动配置。
      //   HouseholdEconomy 自己的 [0,1000] 参与率守卫仍在（见 HouseholdEconomy 构造期）。
      householdEconomiesCopy.put(householdEconomyEntry.getKey(), householdEconomy);
    }
    classes = Collections.unmodifiableMap(householdEconomiesCopy); // ★ 冻在赋值处
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
    // ★★ 2026-10-09 选项 A（第 37 个组件）：引用表的形状守卫 + 冻结。缺键已在前面归一成空表。
    //   ★ 值只允许标记位 Boolean.TRUE：一个"引用对"要么在表里、要么不在 —— 让 false 也能存会立刻造出
    //     "同一条引用两种拼法"的第二真相（变更集的 diff 会把它当两次变化），故当场拒。
    Map<HouseholdDebtReference, Boolean> householdDebtRefsCopy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdDebtReference, Boolean> entry : householdDebtRefs.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("householdDebtRefs 的键与值都不得为 null: " + entry.getKey());
      }
      if (!Boolean.TRUE.equals(entry.getValue())) {
        throw new IllegalArgumentException(
            "householdDebtRefs 的值只允许标记位 TRUE（存在即引用）: " + entry.getKey() + " = " + entry.getValue());
      }
      householdDebtRefsCopy.put(entry.getKey(), Boolean.TRUE);
    }
    householdDebtRefs = Collections.unmodifiableMap(householdDebtRefsCopy); // ★ 冻在赋值处
    // ★★ B.3b（R3 决策单 §0.3）+ 2026-10-09 选项 A：孤儿债对账 —— 以 debtContracts 表为唯一权威，按 debtor 分组、
    //   DebtContractId canonical 升序重建**引用表**（改前是重建每一行的 debts 列表；结论逐字不变）。
    //   ★ 必须在**跨表守卫之前**：守卫要求“引用的合同存在”，而孤儿债是“合同存在、引用缺失”；对账不碰合同表本身
    //   （principal/status 守恒），只在 debtor/creditor 家户行缺失时 fail-closed 具名抛。
    householdDebtRefs =
        DebtReferenceReconciler.reconcile(
            debtContractsCopy, householdEconomiesCopy, householdDebtRefs);
    householdDebtRefs = Collections.unmodifiableMap(householdDebtRefs); // ★ 冻在赋值处（可能是同一实例）
    // ★ v2 spec §八.2：两张表的**交叉引用完整性**。★ 必须等两张表都建完再判 ——
    //   在任一段内查对方会陷入循环依赖（debtContracts 要查 classes、classes 要查 debtContracts），故不能靠调顺序解决。
    //   ★ B.3b 起引用侧已由上面的 DebtReferenceReconciler 重建过，本循环是对账后的兜底断言。
    for (Map.Entry<DebtContractId, DebtContract> entry : debtContractsCopy.entrySet()) {
      DebtContract contract = entry.getValue();
      if (!householdEconomiesCopy.containsKey(contract.debtor())
          || !householdEconomiesCopy.containsKey(contract.creditor())) {
        throw new IllegalArgumentException(
            "债务的 debtor/creditor 必须是已存在的阶层行（v2 spec §八.2）："
                + entry.getKey()
                + " "
                + contract.debtor()
                + " → "
                + contract.creditor());
      }
    }
    // ★ 改前这里逐行扫 ClassRow.debts；拆表后同一判据收成一个具名方法（引用表 ⊆ 合同表 + 挂对债务人 + 家户行存在）。
    //   ⇒ 悬空引用仍然具名抛，不因拆表而放松；且它可被单独调用（自证探针就是这么打这句话的）。
    DebtReferenceReconciler.requireReferencesResolvable(
        debtContractsCopy, householdEconomiesCopy, householdDebtRefs);
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
      HouseholdEconomy flowHouseholdEconomy = householdEconomiesCopy.get(entry.getKey());
      if (flowHouseholdEconomy == null) {
        throw new IllegalArgumentException("flows 的键必须是已存在的家户（S1 起两表同键）：" + entry.getKey());
      }
      // ★★★ D-032 B4（§13.14.2 裁定十五）：本行的"flow 落点必须登记过产业"判据**已平移**到 state-aware 守卫
      //   `EconomyReferenceValidation.validateFlowsOnIndustryHex`（读 production 当刻定义权威；由
      //   `EconomyProductionReferenceValidator` 在每条命令 / submitBatch 最终候选态 / restore / Advance
      // 候选态落库前执行）。
      //   ★ 构造期看不见 production ⇒ 这里不再判（判据没删，换了读源与落点）。
      flowsCopy.put(entry.getKey(), entry.getValue());
    }
    flows = Collections.unmodifiableMap(flowsCopy); // ★ 冻在赋值处
    // ── R2：劳动分配表 + 三条结构判据（见类注释）────────────────────────────────────────────
    // ★★★ D-032 B4（§13.14.2 裁定十五）：原先在这里从 `industries` 副本建的 `industryIds`
    //   （"actor 不得与产业 id 撞名"判据的参照集）**已随该副本删除**；判据本身平移到了 state-aware 守卫
    //   `EconomyReferenceValidation.validateLaborActorIndustryCollision`（读 production 当刻定义权威）。
    // ★★ R3B.2 的"activity 是 unit id ⇒ 按值查 unit"索引已随 B2b 的 units 组件删除：判据本身没删，
    //   移到跨片校验面（{@code EconomyReferenceValidation.validateUnitCrossReferences}，读 production 只读投影）。
    Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitmentsCopy = new LinkedHashMap<>();
    Map<HouseholdId, Long> productionPerHousehold = new LinkedHashMap<>();
    Map<HouseholdId, Long> govServicePerHousehold = new LinkedHashMap<>();
    for (Map.Entry<LaborAllocationId, HouseholdLaborCommitment> laborCommitmentEntry :
        allocations.entrySet()) {
      if (laborCommitmentEntry.getKey() == null || laborCommitmentEntry.getValue() == null) {
        throw new IllegalArgumentException(
            "allocations 的键与值都不得为 null: " + laborCommitmentEntry.getKey());
      }
      HouseholdLaborCommitment laborCommitment = laborCommitmentEntry.getValue();
      if (!laborCommitmentEntry.getKey().equals(laborCommitment.id())) {
        throw new IllegalArgumentException(
            "allocations 的键必须与 LaborAllocation.id 一致：键="
                + laborCommitmentEntry.getKey()
                + "，行内 id="
                + laborCommitment.id());
      }
      // ①-b ★★ S1：这份劳动必须属于一个已存在的家户。★★ B2c（§13.12.5）：迁移期 pending 占位的豁免已删
      //   （占位整套删除 ⇒ 没有"合法的不存在家户"这一档）—— 旧档缺 household 在 codec 边界就 fail-closed。
      HouseholdId allocatedHousehold = laborCommitment.household();
      if (!householdEconomiesCopy.containsKey(allocatedHousehold)) {
        throw new IllegalArgumentException(
            "劳动分配的家户必须是已存在的家户（S1 起身份与视图分离）："
                + laborCommitmentEntry.getKey()
                + " 的 household="
                + allocatedHousehold
                + " 不在 classes 里");
      }
      // ① actor ↔ 产业 的**撞名判据**（结算按 actor id 归属劳动）：
      //    非产业型不得与产业 id 撞名 —— 撞名会让"家户/单位的配额"静默算进那个产业。
      //   ★★ **R3 起 HOUSEHOLD 是"自由档"**：家户 actor id **可以**命名一个产业（那时配额照进该产业），也可以不命名。
      //   ★★ **P2-A §13.3**：ESTATE/WORKSHOP 退役 ⇒ 生产主体身份改走 ORGANIZATION（产业经营者）与 HOUSEHOLD；
      //     两者都允许命中产业 id（不再有"必须命中"的 kind —— ORGANIZATION 也可用来表示非产业组织）。
      //   ★★★ D-032 B4（§13.14.2 裁定十五）：判据本体**平移**到 state-aware 守卫
      //     `EconomyReferenceValidation.validateLaborActorIndustryCollision`（它逐条比 production 当刻定义
      // id）；
      //     构造期不再持有产业定义副本 ⇒ 判不了，也不许"因为看不见就放过"——落库前的候选态校验面照旧拦。
      // ①-c ★★ R3B.2：activity 若指名了现存 unit，则收劳动的主体必须就是该 unit 的 operator。
      //   ★ 判据**没有删**：它要读 production 的实例投影 ⇒ 落在跨片校验面
      //     （{@code EconomyReferenceValidation.validateUnitCrossReferences}，每次候选提交前跑）。
      //   ★ activity 不命中 unit 的配额合法（自由家户劳动）：它不喂任何生产，只进守恒与读口。
      // ★★ P2-A A4：配额上限改为**家户每 tick 时间预算**（{@code HouseholdEconomy.laborMilli}，毫小时）——
      //   不再有"每批次供给表"这第二权威（LaborSupply 已删除；预算每 tick 由 Social 人口组成重算）。
      //   ★★ Z7d-1：按 kind 分账 —— GOV_SERVICE 是"职位/诉求"（允许超过饿少后的预算，有效供给由 app
      //   供给桥 cap），PRODUCTION 才受"扣除政府承诺预留后的可用时间"约束。
      if (laborCommitment.kind() == LaborCommitmentKind.GOV_SERVICE) {
        govServicePerHousehold.merge(
            laborCommitment.household(), laborCommitment.laborMilli(), Long::sum);
      } else {
        productionPerHousehold.merge(
            laborCommitment.household(), laborCommitment.laborMilli(), Long::sum);
      }
      laborCommitmentsCopy.put(laborCommitmentEntry.getKey(), laborCommitment);
    }
    allocations = Collections.unmodifiableMap(laborCommitmentsCopy); // ★ 冻在赋值处
    // ③ ★★ **Σ PRODUCTION ≤ 时间预算 − min(Σ GOV_SERVICE, 时间预算)**（本阶段最重要的不变量，见类注释）。
    //   ★★ Z7d-1 口径：GOV_SERVICE 承诺行保留为职位（C7 不静默缩/删），允许 `Σ GOV_SERVICE > laborMilli`
    //   （饥饿把预算饿少后的具名 underfed 状态）；此时 PRODUCTION 可用量 = 0。有效供给 = min(承诺, 实际劳动)
    //   由 app 的 GovernmentServiceLaborBridge 逐户 cap，不在这里改任何承诺行。
    for (Map.Entry<HouseholdId, Long> entry : productionPerHousehold.entrySet()) {
      HouseholdEconomy householdEconomy = householdEconomiesCopy.get(entry.getKey());
      if (householdEconomy == null) {
        continue; // ★ B2b：旧档迁移器已删 ⇒ pending 家户不再被自动改写（旧档作废；真实家户由守卫把守）
      }
      long budget = householdEconomy.laborMilli();
      long govService = govServicePerHousehold.getOrDefault(entry.getKey(), 0L);
      long reservedGovService = Math.min(govService, budget);
      long productionBudget = budget - reservedGovService;
      if (entry.getValue() > productionBudget) {
        throw new IllegalArgumentException(
            "家户 "
                + entry.getKey()
                + " 的生产劳动配额之和 "
                + entry.getValue()
                + " 超过它的每 tick 时间预算 "
                + budget
                + " 中扣除 GOV_SERVICE 最高优先预留后的可用部分 "
                + productionBudget
                + "（GOV_SERVICE 承诺 "
                + govService
                + " 毫小时；同一份家户时间不得被多个生产活动各算一次满额，计划 §13.5）");
      }
    }
    // ── R3B.1 第 12 个组件：实物资产份额表 ──────────────────────────────────────────────
    //   ★ 键 == 值内 id；industry 必须存在；asset/owner/operator/kind 非空、quantity ≥ 0（非空与 quantity
    //     由 OwnershipStake 构造期判，这里判跨表的 industry 引用与键身份）。
    //   ★★ **这里不再有"Σ quantity ≤ IndustryDefinitionView.capacity"的上界守卫，也不写 Σ ==
    // capacity**：OwnershipStake 是
    //     独立的实物资产总账，IndustryDefinitionView.capacity 在 B.1 里只是过渡字段（B.2 移出生产模型）；把技术模板当
    //     实物账本上界，会把"实物已存在、模板尚未及更新"这类合法状态误判成坏数据。
    //   ★ 关系表的 operator 与 unit 的关系由**第 14 个组件（units）**的守卫判（R3B.2 起关系挂在 unit 上）。
    Map<AssetShareId, OwnershipStake> ownershipStakesCopy = new LinkedHashMap<>();
    for (Map.Entry<AssetShareId, OwnershipStake> entry : assetShares.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("assetShares 的键与值都不得为 null: " + entry.getKey());
      }
      OwnershipStake share = entry.getValue();
      if (!entry.getKey().equals(share.id())) {
        throw new IllegalArgumentException(
            "assetShares 的键必须与 OwnershipStake.id 一致：键=" + entry.getKey() + "，行内 id=" + share.id());
      }
      // ★★★ D-032 B4（§13.14.2 裁定十五）：本行的 assetShare→industry 存在性判据**已平移**到 state-aware 守卫
      //   `EconomyReferenceValidation.validateUnitsAndShares`（份额侧仍读 economy 的 assetShares，产业侧读
      //   production 当刻定义权威）；构造期没有定义副本可查 ⇒ 不再判。
      ownershipStakesCopy.put(entry.getKey(), share);
    }
    assetShares = Collections.unmodifiableMap(ownershipStakesCopy); // ★ 冻在赋值处

    // ── 第 8 个组件：生产关系表（S1 阶段 4+5 Task 2；计划 R3；R3B.2 起键/activity = unit id）────────
    Map<ProductionUnitId, ProductionRules> relationsRaw = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, ProductionRules> entry : relations.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("relations 的键与值都不得为 null: " + entry.getKey());
      }
      relationsRaw.put(entry.getKey(), entry.getValue());
    }
    // ★★ S1：把旧档/默认关系里的 {@code ToCohort(view)} 一对一归一到 {@code ToHousehold(id)}
    //   （同一 view 恰有一个家户时才归一 —— 一一对应是旧档的既有事实；view 有歧义时保留 ToCohort
    //   作为 S3 的视图选择器，不在构造期猜）。归一化是"键/受方迁移"的唯一落点，幂等。
    Map<ProductionUnitId, ProductionRules> relationsCopy =
        normalizePayees(relationsRaw, householdEconomiesCopy);
    relations = Collections.unmodifiableMap(relationsCopy); // ★ 冻在赋值处
    // ★★ 两条跨表守卫（R3B.2 改口径）：① 键 == 值内 activity（同 classes/flows 的键身份口径）；
    //   ② 关系挂在一个**已存在的 unit** 上、且 operator 与 unit.operator 逐值相等
    //      （"谁经营"若能在关系表里另写一遍，两边不一致时没有任何一处能判谁对）。
    for (Map.Entry<ProductionUnitId, ProductionRules> entry : relationsCopy.entrySet()) {
      ProductionRules relation = entry.getValue();
      if (!entry.getKey().equals(relation.activity())) {
        throw new IllegalArgumentException(
            "relations 的键必须与 ProductionRules.activity 一致：键="
                + entry.getKey()
                + "，行内 activity="
                + relation.activity());
      }
      // ★ B2b：原判据 ②（关系指名的 unit 存在、且 operator == unit.operator）已移到跨片校验面
      //   （{@code EconomyReferenceValidation.validateUnitCrossReferences}）——它要读 production 的实例投影。
    }
    // ── 第 9 个组件：市场价表（H4；★★ W1 起**每区一张**：键 = 市场区 id / 派生市场 id）──────────────────
    //   ★ 键 = {@link MarketZoneId}；**缺键 = 那个市场没有价表**（持久形态下"区必须有价表"由下面的跨表守卫判死）。
    //   ★ 只判 null 与结构：**价格是数据**（GM 可调），这里没有"价格该是多少""哪些商品该有价"的判据 ——
    //     那些是 GM 的判断（信条十二），不是状态类型的守卫。
    //   ★ 迭代序归一成规范序（{@link MarketTables#frozen}）：内容相同的状态必须给出同一个逐区序（I7）。
    if (marketTables == null) {
      marketTables = Map.of();
    }
    for (Map.Entry<MarketZoneId, MarketTable> entry : marketTables.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("marketTables 的键与值都不得为 null: " + entry.getKey());
      }
    }
    marketTables = MarketTables.frozen(marketTables); // ★ 规范序 + 冻在赋值处
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
      // ★ B2b：原"键的 unit 存在 + 值内 industry == unit.industry"两条守卫已移到跨片校验面
      //   （{@code EconomyReferenceValidation.validateUnitCrossReferences}）——它们要读 production 的实例投影。
      operatorConditionsCopy.put(entry.getKey(), entry.getValue());
    }
    operatorConditions = Collections.unmodifiableMap(operatorConditionsCopy); // ★ 冻在赋值处
    // ── R3B.2 第 14 个组件：生产单元表（units）—— ★★ B2b 起**本组件已从本类型删除** ──────────────
    //   生产实例（键/身份/方案引用/经营者/地点/进度/周期台账）的唯一权威在 production
    //   （{@code production.contract.ProductionRuntime#units()} 的当刻候选态，I1/I13）。
    //   ★ 原在此处的守卫**一条没删**，全部改读 production 的只读投影：
    //     ① 键 == 行内 id、② 方案存在、③ progressDays ∈ [0, cycleDays]、④ 经营者非空 / 地点非 UNSPECIFIED
    //        ⇒ {@code EconomyReferenceValidation.validateProductionInstances(runtime, label)}（B2
    // 落，运行期契约口）；
    //     ⑤ relations / operatorConditions / allocations.activity / productionOrganizations
    // 与实例的交叉引用
    //        ⇒ {@code EconomyReferenceValidation.validateUnitCrossReferences}（候选提交前的跨片校验面）。
    // ★★ B.3b：**unit 不要求必须有 OwnershipStake**（R4 计划禁把"资产闲置/退出"判成坏数据）。
    //   资产可以全部转走（见 economy.TransferAssetShare），此时该 unit 的规模由
    //   ProductionProcessBook 纯派生为 0 —— "有经营者、无资产、不生产"是合法状态（也是 E1 退出处置的前态）。
    //   unit↔relation / operatorConditions 的 operator/industry 一致性已由上面两段守卫把守，不因本放宽而松。
    // ── R4-E2 第 15 个组件：需求账本 ───────────────────────────────────────────────────────
    //   ★ 键 == 值内 id；scope ↔ household/hex 的互斥与必填由 HouseholdDemand 构造期判；
    //     这里判跨表引用：HOUSEHOLD 的家户必须存在；HEX 的格必须在本世界里可定位
    //     （产业/家户行/市场三者任一登记的格，见 hexRegistered）。
    Map<DemandId, HouseholdDemand> householdDemandsCopy = new LinkedHashMap<>();
    for (Map.Entry<DemandId, HouseholdDemand> householdDemand : demands.entrySet()) {
      if (householdDemand.getKey() == null || householdDemand.getValue() == null) {
        throw new IllegalArgumentException("demands 的键与值都不得为 null: " + householdDemand.getKey());
      }
      HouseholdDemand demand = householdDemand.getValue();
      if (!householdDemand.getKey().equals(demand.id())) {
        throw new IllegalArgumentException(
            "demands 的键必须与 DemandEntry.id 一致：键="
                + householdDemand.getKey()
                + "，行内 id="
                + demand.id());
      }
      if (demand.scope() == HouseholdDemand.DemandScope.HOUSEHOLD) {
        HouseholdId household = demand.household().orElseThrow();
        if (!householdEconomiesCopy.containsKey(household)) {
          throw new IllegalArgumentException(
              "HOUSEHOLD 范围的需求指名的家户不存在: " + demand.id().value() + " → " + household.value());
        }
      } else {
        HexCoord hex = demand.hex().orElseThrow();
        // ★★★ D-032 B4（§13.14.2 裁定十五）：本行的产业维（"该格登记过产业"）**已平移**到 state-aware 守卫
        //   `EconomyReferenceValidation.validateDemandHexes`（读 production 当刻定义权威）；构造期只剩本域两维
        //   （家户行 / 市场），判据没删、也没放宽 —— 少了"产业"这一维只会更严，而更严的那一维由落库前校验面补齐。
        if (!hexRegistered(householdEconomiesCopy, marketTables, marketZones, hex)) {
          throw new IllegalArgumentException(
              "HEX 范围的需求指名的格没有经济状态（该格不存在/未播种，也不在任何市场键里）: " + demand.id().value() + " → " + hex);
        }
      }
      householdDemandsCopy.put(householdDemand.getKey(), demand);
    }
    demands = Collections.unmodifiableMap(householdDemandsCopy); // ★ 冻在赋值处
    // ── E1 第 18–20 个组件：阶层结构 / 阶层位置 / 家户阶层归属 ────────────────────────────
    //   ★ 缺键 ⇒ 空表（上面已归一）；**新状态为空时本段整体 no-op**，旧档逐值行为不受影响。
    //   ★ 引用完整性是 fail-closed 的，但按"对侧是否已提供"分段生效：`with*` 是逐组件写口，
    //     三个组件之间有层次引用（position → mode、standing → household/position）；
    //     若每一段都无条件要求完整闭环，单项 `with*` 永远构造不出中间态。空表在这里读作"这一侧还没提供"，
    //     一旦对侧非空，键身份、引用与的位置形状就必须逐值自洽 —— 最终完整状态因此仍是 fail-closed 的。
    //   ★★★ B5（§13.15.2 裁定十八）：原第 17 个组件 `modes` 与它的键身份/结构↔mode 双向闭环守卫**已删** ——
    //     定义权威唯一在 production，构造期看不见它。判据一条没删，两处落点如下（都在候选态校验面，
    //     注册点 `Shell.java:1199`，创世态也跑）：
    //       ① "结构/位置/组织/规则/质押/变迁 → mode 存在性" ⇒
    //          `EconomyReferenceValidation.validateModeAssociations`（读 production 当刻权威）；
    //       ② "mode → 其 classStructureId 必须在 economy 候选态里存在" ⇒
    //          `EconomyReferenceValidation.validateModeStructures`（双向闭环的另一半）。
    //     ⇒ 本构造期只剩本域形状约束（键 == 值内 id 等），与 P1 §3.3"纯 codec 只验证本片固有约束"一致。
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
      structuresCopy.put(entry.getKey(), structure);
    }
    classStructures = Collections.unmodifiableMap(structuresCopy); // ★ 冻在赋值处
    Map<ClassPositionId, ProductionRole> positionsCopy = new LinkedHashMap<>();
    for (Map.Entry<ClassPositionId, ProductionRole> entry : classPositions.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("classPositions 的键与值都不得为 null: " + entry.getKey());
      }
      ProductionRole position = entry.getValue();
      if (!entry.getKey().equals(position.id())) {
        throw new IllegalArgumentException(
            "classPositions 的键必须与 ProductionRole.id 一致：键="
                + entry.getKey()
                + "，行内 id="
                + position.id());
      }
      positionsCopy.put(entry.getKey(), position);
    }
    // ★★ 结构内的位置必须与全局位置表逐值相等；全局表也不得残留不属于任何结构的孤儿位置。
    //   同一身份只有一处权威形状，避免"结构里写一套、全局表里另写一套"（对侧为空 = 该侧尚未提供，见段首口径）。
    if (!positionsCopy.isEmpty()) {
      for (ClassStructure structure : structuresCopy.values()) {
        for (Map.Entry<ClassPositionId, ProductionRole> entry : structure.positions().entrySet()) {
          ProductionRole flat = positionsCopy.get(entry.getKey());
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
      for (ProductionRole position : positionsCopy.values()) {
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
    Map<HouseholdId, HouseholdClassMembership> classMembershipsCopy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, HouseholdClassMembership> classMembershipEntry :
        classStandings.entrySet()) {
      if (classMembershipEntry.getKey() == null || classMembershipEntry.getValue() == null) {
        throw new IllegalArgumentException(
            "classStandings 的键与值都不得为 null: " + classMembershipEntry.getKey());
      }
      HouseholdClassMembership classMembership = classMembershipEntry.getValue();
      if (!classMembershipEntry.getKey().equals(classMembership.householdId())) {
        throw new IllegalArgumentException(
            "classStandings 的键必须与 ClassStanding.householdId 一致：键="
                + classMembershipEntry.getKey()
                + "，行内 householdId="
                + classMembership.householdId());
      }
      if (!householdEconomiesCopy.isEmpty()
          && !householdEconomiesCopy.containsKey(classMembershipEntry.getKey())) {
        throw new IllegalArgumentException(
            "classStandings 的家户必须是已存在的家户（S1 起身份与视图分离）：" + classMembershipEntry.getKey());
      }
      if (!positionsCopy.isEmpty()) {
        if (!positionsCopy.containsKey(classMembership.currentPositionId())) {
          throw new IllegalArgumentException(
              "ClassStanding.currentPositionId 必须是已存在的阶层位置：家户="
                  + classMembershipEntry.getKey()
                  + "，当前位置="
                  + classMembership.currentPositionId());
        }
        if (!positionsCopy.containsKey(classMembership.originalPositionId())) {
          throw new IllegalArgumentException(
              "ClassStanding.originalPositionId 必须是已存在的阶层位置：家户="
                  + classMembershipEntry.getKey()
                  + "，原所属="
                  + classMembership.originalPositionId());
        }
        for (ClassPositionId retained : classMembership.retainedShares().keySet()) {
          if (!positionsCopy.containsKey(retained)) {
            throw new IllegalArgumentException(
                "ClassStanding.retainedShares 的键必须是已存在的阶层位置：家户="
                    + classMembershipEntry.getKey()
                    + "，保留位置="
                    + retained);
          }
        }
        // ★★ P2-B：追加参与的生产位置（多生产方式）必须逐个是已存在的位置 —— 组织阶段按它们建
        //   生产组织/unit，悬空引用会让"可参与"变成组织期的静默跳过。
        for (ClassPositionId participating : classMembership.participatingPositionIds()) {
          if (!positionsCopy.containsKey(participating)) {
            throw new IllegalArgumentException(
                "ClassStanding.participatingPositionIds 必须是已存在的阶层位置：家户="
                    + classMembershipEntry.getKey()
                    + "，参与位置="
                    + participating);
          }
        }
      }
      classMembershipsCopy.put(classMembershipEntry.getKey(), classMembership);
    }
    classStandings = Collections.unmodifiableMap(classMembershipsCopy); // ★ 冻在赋值处
    // ── E2 第 21/22 个组件：生产组织 / 生产资料规则 ───────────────────────────────────
    //   ★ 旧档缺键 ⇒ 空表（上面已归一）；两张表为空时本段整体 no-op，旧结算路径逐值不变。
    //   ★ 引用完整性按"对侧是否已提供"分段生效（与 E1 四条同款），保证 with* 能逐组件构造。
    Map<ProductionOrganizationId, ProductionEnterprise> enterprisesCopy = new LinkedHashMap<>();
    for (Map.Entry<ProductionOrganizationId, ProductionEnterprise> entry :
        productionOrganizations.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "productionOrganizations 的键与值都不得为 null: " + entry.getKey());
      }
      ProductionEnterprise enterprise = entry.getValue();
      if (!entry.getKey().equals(enterprise.id())) {
        throw new IllegalArgumentException(
            "productionOrganizations 的键必须与 ProductionEnterprise.id 一致：键="
                + entry.getKey()
                + "，行内 id="
                + enterprise.id());
      }
      // ★ B5（裁定十八）：原"生产组织的 modeId 必须已存在"一维已删（本域看不见定义）⇒
      //   `EconomyReferenceValidation.validateModeAssociations`（读 production 当刻权威）。
      if (!positionsCopy.isEmpty() && !positionsCopy.containsKey(enterprise.classPositionId())) {
        throw new IllegalArgumentException(
            "生产组织的 classPositionId 必须是已存在的阶层位置：组织="
                + entry.getKey()
                + "，位置="
                + enterprise.classPositionId());
      }
      // ★ B2b：原"组织指名的 unit 必须已存在、且 organizer == unit.operator"两条守卫已移到跨片校验面
      //   （{@code EconomyReferenceValidation.validateUnitCrossReferences}）——它们要读 production 的实例投影。
      for (AssetShareId assetSource : enterprise.assetSources()) {
        if (!assetShares.isEmpty() && !assetShares.containsKey(assetSource)) {
          throw new IllegalArgumentException(
              "生产组织使用的资产份额必须已存在：组织=" + entry.getKey() + "，份额=" + assetSource);
        }
        if (!assetShares.isEmpty()) {
          OwnershipStake organizedShare = assetShares.get(assetSource);
          if (organizedShare != null && !organizedShare.operator().equals(enterprise.organizer())) {
            throw new IllegalArgumentException(
                "生产组织使用的 OwnershipStake 必须由 organizer 经营（operator 一致）：组织="
                    + entry.getKey()
                    + "，份额="
                    + assetSource
                    + "，份额 operator="
                    + organizedShare.operator());
          }
        }
      }
      for (HouseholdId laborSource : enterprise.laborSources()) {
        if (!householdEconomiesCopy.isEmpty() && !householdEconomiesCopy.containsKey(laborSource)) {
          throw new IllegalArgumentException(
              "生产组织的劳动来源家户必须已存在：组织=" + entry.getKey() + "，家户=" + laborSource);
        }
      }
      enterprisesCopy.put(entry.getKey(), enterprise);
    }
    productionOrganizations = Collections.unmodifiableMap(enterprisesCopy); // ★ 冻在赋值处
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
      // ★ B5（裁定十八）：原"生产资料规则的 modeId 必须已存在"一维已删（本域看不见定义）⇒
      //   `EconomyReferenceValidation.validateModeAssociations`（读 production 当刻权威）。
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
      // ★★ P2-C §13.7：按 GOV 单位派生的政府记录必须把国库钉在该单位的政府家户上
      //   （id = gov-unit-<unitId> ⇒ 家户 id = hh-gov-<unitId>）。这条不变量在状态构造期判死：
      //   任何"登记了单位政府却指向别的家户/别的 actor"的数据都进不了世界，也不靠"先到者胜"兜底。
      GovernmentIds.unitRefOf(government.id())
          .ifPresent(
              unitRef -> {
                if (government.treasury().kind() != ActorKind.HOUSEHOLD) {
                  throw new IllegalArgumentException(
                      "单位政府 "
                          + government.id()
                          + " 的国库必须是该 GOV 单位的政府家户账户（HOUSEHOLD）："
                          + government.treasury());
                }
                HouseholdId expected = GovernmentHouseholds.of(unitRef);
                HouseholdId actual = HouseholdActors.householdOf(government.treasury());
                if (!expected.equals(actual)) {
                  throw new IllegalArgumentException(
                      "单位政府 "
                          + government.id()
                          + " 的国库家户必须是 "
                          + expected
                          + "（govUnitId="
                          + unitRef
                          + "），实际="
                          + actual);
                }
              });
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
    // ── A1（2026-10-08）货币词表的两个新组件：币种表 + 工具表 ─────────────────────────────────
    //   ★★ 归一方向（旧档兼容）：**整个词表缺席（空表）⇒ 旧世界默认**（silver + silver-specie），
    //      于是"没有声明过词表的世界"读出来与 A1 之前逐值相同（旧 dump 的 currencyDefs=[{silver,3}] 不变）。
    //      但**只给币种不给工具**是合法的显式状态（工具表空 = 这个世界只有计价单位、还没有具体的钱），
    //      此时不补 silver-specie —— 那会成为一张"币种不在表里的工具"。
    boolean legacyVocabulary = currencies == null || currencies.isEmpty();
    Map<CurrencyId, CurrencyDef> currenciesCopy = new LinkedHashMap<>();
    if (legacyVocabulary) {
      for (CurrencyDef def : MoneyVocabulary.legacyCurrencyDefs()) {
        currenciesCopy.put(def.currencyId(), def);
      }
    } else {
      for (Map.Entry<CurrencyId, CurrencyDef> entry : currencies.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException("currencies 的键与值都不得为 null: " + entry.getKey());
        }
        if (!entry.getKey().equals(entry.getValue().currencyId())) {
          throw new IllegalArgumentException(
              "currencies 的键必须与 CurrencyDef.currencyId() 一致（键 = 身份，不许两处拼币种名）：键="
                  + entry.getKey()
                  + "，行内 id="
                  + entry.getValue().id());
        }
        currenciesCopy.put(entry.getKey(), entry.getValue());
      }
    }
    // ★★ 跨表守卫：政府声称能发行的币种必须在币种表里有定义（说不出"这是什么钱"就不许发行它）。
    //   与上面"发行记录的 governmentId 必须已存在"同一条口径（引用完整性 fail-closed）。
    for (Government government : governmentsCopy.values()) {
      for (CurrencyId currency : government.issuable()) {
        if (!currenciesCopy.containsKey(currency)) {
          throw new IllegalArgumentException(
              "政府 "
                  + government.id()
                  + " 声称发行的币种在币种表里没有定义："
                  + currency
                  + "（币种表="
                  + currencyIdsOf(currenciesCopy)
                  + "；先 economy.DefineCurrency 或把该币种写进创世词表）");
        }
      }
    }
    currencies = Collections.unmodifiableMap(currenciesCopy); // ★ 冻在赋值处
    Map<InstrumentId, MoneyInstrument> instrumentsCopy = new LinkedHashMap<>();
    boolean legacyInstruments = moneyInstruments == null || moneyInstruments.isEmpty();
    if (legacyInstruments && legacyVocabulary) {
      for (MoneyInstrument instrument : MoneyVocabulary.legacyInstruments()) {
        instrumentsCopy.put(instrument.id(), instrument);
      }
    } else if (!legacyInstruments) {
      for (Map.Entry<InstrumentId, MoneyInstrument> entry : moneyInstruments.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException("moneyInstruments 的键与值都不得为 null: " + entry.getKey());
        }
        if (!entry.getKey().equals(entry.getValue().id())) {
          throw new IllegalArgumentException(
              "moneyInstruments 的键必须与 MoneyInstrument.id() 一致：键="
                  + entry.getKey()
                  + "，行内 id="
                  + entry.getValue().id());
        }
        if (!currenciesCopy.containsKey(entry.getValue().currency())) {
          throw new IllegalArgumentException(
              "货币工具 "
                  + entry.getKey().value()
                  + " 的币种在币种表里没有定义："
                  + entry.getValue().currency()
                  + "（币种表="
                  + currencyIdsOf(currenciesCopy)
                  + "）");
        }
        instrumentsCopy.put(entry.getKey(), entry.getValue());
      }
    }
    moneyInstruments = Collections.unmodifiableMap(instrumentsCopy); // ★ 冻在赋值处
    // ── B2（2026-10-08）市场区的持久状态（约束设计书 §4.2/§4.3；不变量 I22）──────────────────────
    //   ★★ 归一方向（旧档兼容）：**旧档缺该组件键 ⇒ 空表**（上面已归一），空表 = 沿用现行"城市 + tier 半径"派生
    //      ⇒ small-world / corridor / v17levant 逐值不变（市场区表非空才改走持久区）。
    //   ★★ 四条守卫（全部 fail-closed；前三条判本表自身，第四条判跨表引用）：
    //      ① 键 == 值内 zoneId（键即身份，不许两处拼区名）；
    //      ② 一个 hex 至多属于一个区（I22 的"单一权威"在**状态层**判死：重叠的区表会让"这一格按谁的钱报价"
    //         有两处互相矛盾的记录，任何读口都无法判谁对）；
    //      ③ 法定币必须已在世界词表里（说不出"这是什么钱"就不许把它定成某区法定币）；
    //      ④ 区级官方汇率的 base/quote 必须已在世界词表里（同 A2a 的 currency-not-defined 口径）。
    //   ★★ 2026-10-09 C 批删掉的第五条（用户 §1.6「肯定不管」；设计书 §4.3）：原第 ⑤ 条要求"发行政府必须已登记、
    //      且它的 issuable 必须含该区法定币"。区只记"这一片用哪种钱"，**不记**"谁发得出"（G1/G4）：立区是**法律事实**，
    //      发不出就没人能换、后果自负。⇒ 本条与命令面的 gov-not-registered / currency-not-issuable 一并退役。
    //      ★ "谁管这种钱"改由 `Government.issuable` **反查**回答（{@code
    // MarketZoneBook.possibleIssuersOf}，I-M8），
    //      不在这里再存一份发行关系（第二张表 = 第二权威）。
    //   ★ 成员格不得同时在两个区（②）用**规范序**（zoneId 升序）遍历，保证"哪一个区先声明"这个报错文本可复现。
    if (marketZones == null) {
      marketZones = Map.of(); // 旧档缺该键 ⇒ 空表（见上面的归一方向）
    }
    Map<MarketZoneId, MarketZone> marketZonesCopy = new LinkedHashMap<>();
    Map<HexCoord, MarketZoneId> hexOwner = new LinkedHashMap<>();
    List<MarketZone> zonesInCanonicalOrder = new ArrayList<>(marketZones.size());
    zonesInCanonicalOrder.addAll(marketZones.values());
    zonesInCanonicalOrder.sort(Comparator.comparing(zone -> zone.zoneId().value()));
    for (MarketZone zone : zonesInCanonicalOrder) {
      if (zone == null) {
        throw new IllegalArgumentException("marketZones 不得含 null");
      }
      MarketZoneId zoneId = zone.zoneId();
      if (!marketZones.containsKey(zoneId)) {
        throw new IllegalArgumentException(
            "marketZones 的键必须与 MarketZone.zoneId 一致（键 = 身份，不许两处拼区名）：值内 zoneId="
                + zoneId.value()
                + "，表内键="
                + marketZones.keySet());
      }
      for (HexCoord hex : zone.hexes()) {
        MarketZoneId owner = hexOwner.putIfAbsent(hex, zoneId);
        if (owner != null) {
          throw new IllegalArgumentException(
              "一个 hex 至多属于一个市场区（I22 单一权威）：hex="
                  + hex
                  + " 同时在 zone="
                  + owner.value()
                  + " 与 zone="
                  + zoneId.value()
                  + "（先把该格从其中一个区划出去：economy.ReassignZoneHexes）");
        }
      }
      if (!currenciesCopy.containsKey(zone.legalTender())) {
        throw new IllegalArgumentException(
            "市场区 "
                + zoneId.value()
                + " 的法定币在币种表里没有定义："
                + zone.legalTender()
                + "（币种表="
                + currencyIdsOf(currenciesCopy)
                + "；先 economy.DefineCurrency 或把该币种写进创世词表）");
      }
      for (OfficialRate rate : zone.officialRates().values()) {
        if (!currenciesCopy.containsKey(rate.base())) {
          throw new IllegalArgumentException(
              "市场区 "
                  + zoneId.value()
                  + " 的官方汇率标的币在币种表里没有定义："
                  + rate.base()
                  + "（币种表="
                  + currencyIdsOf(currenciesCopy)
                  + "）");
        }
        if (!currenciesCopy.containsKey(rate.quote())) {
          throw new IllegalArgumentException(
              "市场区 "
                  + zoneId.value()
                  + " 的官方汇率计价币在币种表里没有定义："
                  + rate.quote()
                  + "（币种表="
                  + currencyIdsOf(currenciesCopy)
                  + "）");
        }
      }
      marketZonesCopy.put(zoneId, zone);
    }
    marketZones = Collections.unmodifiableMap(marketZonesCopy); // ★ 冻在赋值处
    // ── ★★ W1：两张市场表之间的跨表守卫（"一个 hex 属于哪个市场"只许有一个答案）────────────────────────
    //   ① 键在区表里的价表条目：计价币必须逐值等于本区法定币（★ 双权威合并：法定币是唯一权威，
    //      价表这一栏是它的投影，漂开即拒）；成员格必须为空（成员格权威是该区，I22）；
    //   ② 任何价表条目的成员格都不得已被某个区覆盖（区成员格的市场由**该区**的条目给定）——
    //      否则"这一格的价"会有两张表同时声称拥有它；
    //   ③ 持久形态下、键不是区的条目 = **派生市场**（今天 MarketTopology 兜底单格区的对应物）：
    //      它的成员格就是它的 hexes（必须非空），不得与任何区成员格重叠（由 ② 判死）；
    //   ④ 派生形态（区表为空）下所有条目都是派生市场 ⇒ hexes 必须非空
    //      （"有市场的格"没有别的来源）。
    //   ★★ 2026-10-11 W1d（控制方裁定）：**"每个持久区必须有价表条目"这条守卫已删除** —— 按设计书 §4.2
    //      N-4 的口径，"缺表 = 该区不交易任何商品"，与"表里没有这一行 = 不交易该商品"是同一条语义。
    //      ★ 为什么必须删：守卫在 compact 构造器里 ⇒ 每次 with*() 都重跑它 ⇒ "区 + 它的价表必须成对变化"
    //      的命令路径（划界/并区/改计价币）在**中间态**被自己的守卫拒掉，`economy.DefineMarketZone` 恒失败
    //      （W1c 账本 §四-2 的实测证据链）。缺表是可表达、可读出的合法状态（读点一律 fail-closed 到"不交易"），
    //      不再需要"一次构造里同时给表"。原子写口见 {@link #withMarketZonesAndTables}。
    for (Map.Entry<MarketZoneId, MarketTable> entry : marketTables.entrySet()) {
      MarketZoneId tableId = entry.getKey();
      MarketTable table = entry.getValue();
      MarketZone zone = marketZones.get(tableId);
      if (zone != null) {
        if (!table.numeraire().equals(zone.legalTender())) {
          throw new IllegalArgumentException(
              "市场 "
                  + tableId.value()
                  + " 的价表计价币与区法定币不一致（双权威未合并）：价表="
                  + table.numeraire().value()
                  + "，法定币="
                  + zone.legalTender().value());
        }
        if (!table.hexes().isEmpty()) {
          throw new IllegalArgumentException(
              "持久形态下价表的成员格必须为空（成员格权威 = MarketZone.hexes，I22）：市场="
                  + tableId.value()
                  + "，价表成员格数="
                  + table.hexes().size());
        }
      } else if (table.hexes().isEmpty()) {
        throw new IllegalArgumentException(
            "派生市场（键不在市场区表里）必须有成员格（它是\"有市场的格\"的唯一来源）：市场=" + tableId.value());
      }
      for (HexCoord hex : table.hexes()) {
        MarketZoneId owner = hexOwner.get(hex);
        if (owner != null) {
          throw new IllegalArgumentException(
              "价表的成员格已被市场区覆盖（该格的价只能由该区的条目给）：市场="
                  + tableId.value()
                  + "，hex="
                  + hex
                  + "，区="
                  + owner.value());
        }
      }
    }
    // ★★ 2026-10-11 W1d：这里原有"每个持久区必须有价表条目"的循环（W1 引入）—— **已删**（见上面的口径注）。
    //   缺表的区是合法状态：`MarketTables.at`/`MarketIndex.at`/`MarketTopology` 一律把它读成"不交易"，
    //   不抛、不 NPE、不造默认价；`MarketTables.allMarketHexes` 也不把它的成员格算进"有市场的格"。
    // ── 第 38 个组件：商品基础运费表（约束设计书 §4.1 修正版；不变量 I-F1/I-F3）──────────────────────
    //   ★ 守卫只有一条：**值域 ≥ 0**（0 = 明确的"该商品免基础费"，不是"说不出价"；单位运费仍被 max(1,…) 抬到 1）。
    //     键域（必须是词表里的商品）在 GM 命令边界判 —— 状态层不反查词表，旧档/夹具因此能带任意商品键而不炸。
    //   ★ 逐键复制 + 冻在赋值处：与其余 Map 组件同款（访问器返回冻结副本，调用方改不动）。
    Map<CommodityId, Long> commodityFreightCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : commodityFreightBaseMilli.entrySet()) {
      CommodityId commodity = entry.getKey();
      Long baseMilli = entry.getValue();
      if (commodity == null || baseMilli == null) {
        throw new IllegalArgumentException("commodityFreightBaseMilli 的键与值都不得为 null: " + commodity);
      }
      if (baseMilli < 0L) {
        throw new IllegalArgumentException(
            COMMODITY_FREIGHT_CONTRACT_PREFIX
                + "基础运费必须 ≥ 0（0 = 明确的'该商品免基础费'，不是'说不出价'）: 商品="
                + commodity.value()
                + "，基础运费="
                + baseMilli);
      }
      commodityFreightCopy.put(commodity, baseMilli);
    }
    commodityFreightBaseMilli = Collections.unmodifiableMap(commodityFreightCopy); // ★ 冻在赋值处

    // ── R1（第 39 个组件）：**政府市场授权表**的形状守卫 + 冻结（约束设计书 §4.5 G8 / 不变量 I-P6）────────
    //   ★ 三条守卫（全部 fail-closed，不静默归一）：
    //     ① 键 == 值内 id、键与值都不得为 null（与其余 Map 组件同款）；
    //     ② 授权主体政府必须已登记 —— 否则这条授权没有可执行的主体，"留一条永不生效的行"正是本仓最反对的形态；
    //     ③ 该政府的国库必须是**家户**（账户主体只有家户：挂单账户恒是 hh-gov-<govUnitId>）。
    //   ★ 值域（量 > 0 / 限价 ≥ 1 / 到期 ≥ 起始 / 成交量 ∈ [0, 量]）由 GovernmentMarketMandate 构造期判死。
    //   ★ 逐键复制 + 冻在赋值处：与其余 Map 组件同款（访问器返回冻结副本，调用方改不动）。
    Map<MarketMandateId, GovernmentMarketMandate> govMarketMandatesCopy = new LinkedHashMap<>();
    for (Map.Entry<MarketMandateId, GovernmentMarketMandate> entry : govMarketMandates.entrySet()) {
      MarketMandateId mandateId = entry.getKey();
      GovernmentMarketMandate mandate = entry.getValue();
      if (mandateId == null || mandate == null) {
        throw new IllegalArgumentException("govMarketMandates 的键与值都不得为 null: " + mandateId);
      }
      if (!mandateId.equals(mandate.id())) {
        throw new IllegalArgumentException(
            "govMarketMandates 的键必须与 GovernmentMarketMandate.id 一致：键="
                + mandateId
                + "，行内 id="
                + mandate.id());
      }
      Government government = governmentsCopy.get(mandate.government());
      if (government == null) {
        throw new IllegalArgumentException(
            "govMarketMandates 的授权主体政府未登记（授权没有可执行的主体）: "
                + mandateId
                + " government="
                + mandate.government().value());
      }
      if (government.treasury().kind() != ActorKind.HOUSEHOLD) {
        throw new IllegalArgumentException(
            "govMarketMandates 的授权主体国库必须是家户（账户主体只有家户）: "
                + mandateId
                + " government="
                + mandate.government().value()
                + " treasury="
                + government.treasury());
      }
      govMarketMandatesCopy.put(mandateId, mandate);
    }
    govMarketMandates = Collections.unmodifiableMap(govMarketMandatesCopy); // ★ 冻在赋值处
    // ── ★★ W1（第 40 个组件）：市场轮统计簿 ────────────────────────────────────────────────
    //   ★ 只判 null 与结构 + 归一成**规范序**（迭代序 = 内容的纯函数，I7）；**不**判"键必须在 marketTables 里"
    //     —— 市场被撤之后它的历史统计仍是历史事实（见 record 头的组件注释）。
    if (marketRoundStats == null) {
      marketRoundStats = Map.of();
    }
    for (Map.Entry<MarketZoneId, MarketRoundStats> entry : marketRoundStats.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("marketRoundStats 的键与值都不得为 null: " + entry.getKey());
      }
    }
    marketRoundStats = MarketRoundStats.frozen(marketRoundStats); // ★ 规范序 + 冻在赋值处
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
      if (!ownershipStakesCopy.isEmpty()
          && !ownershipStakesCopy.containsKey(pledge.assetShareId())) {
        throw new IllegalArgumentException(
            "质押指名的资产份额必须已存在：质押=" + entry.getKey() + "，份额=" + pledge.assetShareId());
      }
      // ★ B5（裁定十八）：原"质押指名的生产方式必须已存在"一维已删（本域看不见定义）⇒
      //   `EconomyReferenceValidation.validateModeAssociations`（读 production 当刻权威）。
      if (pledge.status() == Pledge.Status.ACTIVE) {
        activePledgedByShare.merge(pledge.assetShareId(), pledge.quantity(), Math::addExact);
      }
      pledgesCopy.put(entry.getKey(), pledge);
    }
    for (Map.Entry<AssetShareId, Long> entry : activePledgedByShare.entrySet()) {
      OwnershipStake share = ownershipStakesCopy.get(entry.getKey());
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
      if (!householdEconomiesCopy.isEmpty()) {
        for (HouseholdId household : signal.households()) {
          if (!householdEconomiesCopy.containsKey(household)) {
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
    //   ★ 引用完整性按“对侧已提供”分段（与 E1/E2/E5 同款）：productionOrganizations 为空 = 该侧尚未提供
    //     ⇒ 只判结构；非空才判组织存在与 fromMode 一致性。★ B5（裁定十八）：原"from/to mode 存在性"那一维
    //     已删（本域看不见定义）⇒ `EconomyReferenceValidation.validateModeAssociations`。
    //     ★ “同一组织至多一条 PENDING”在构造期判死 ——
    //     它是模式变迁命令幂等与“一条 revision 只应用一次”的地基（重复 PENDING 会让同一次切换被两次结算）。
    Map<ModeTransitionId, ModeTransition> modeTransitionsCopy = new LinkedHashMap<>();
    Set<ProductionOrganizationId> pendingEnterprises = new LinkedHashSet<>();
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
        ProductionEnterprise enterprise = productionOrganizations.get(transition.organizationId());
        if (enterprise != null && !enterprise.modeId().equals(transition.fromModeId())) {
          throw new IllegalArgumentException(
              "模式变迁的 fromModeId 必须等于组织当前的 modeId（同一件事不许两处拼写）：变迁="
                  + entry.getKey()
                  + "，fromMode="
                  + transition.fromModeId()
                  + "，组织 mode="
                  + enterprise.modeId());
        }
      }
      // ★ B5（裁定十八）：原"变迁的 from/to mode 必须已存在"两维已删（本域看不见定义）⇒
      //   `EconomyReferenceValidation.validateModeAssociations`（读 production 当刻权威）。
      if (transition.status() == ModeTransition.Status.PENDING
          && !pendingEnterprises.add(transition.organizationId())) {
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
      if (!householdEconomiesCopy.isEmpty()
          && !householdEconomiesCopy.containsKey(share.householdId())) {
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
    // ── P4a 第 31 个组件：周期家户扣增规则（键 == 值内 id；规则自身构造期已判字段不变量）──────────
    //   ★ 空表 = 还没有任何显式规则 ⇒ 日循环到此完全 no-op，旧档逐值行为不变。
    Map<PeriodicHouseholdAdjustmentId, HouseholdPeriodicAdjustment> periodicAdjustmentsCopy =
        new LinkedHashMap<>();
    for (Map.Entry<PeriodicHouseholdAdjustmentId, HouseholdPeriodicAdjustment> entry :
        periodicAdjustments.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("periodicAdjustments 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().id())) {
        throw new IllegalArgumentException(
            "periodicAdjustments 的键必须与 HouseholdPeriodicAdjustment.id 一致：键="
                + entry.getKey()
                + "，行内 id="
                + entry.getValue().id());
      }
      periodicAdjustmentsCopy.put(entry.getKey(), entry.getValue());
    }
    periodicAdjustments = Collections.unmodifiableMap(periodicAdjustmentsCopy); // ★ 冻在赋值处

    // ── Z1 第 32 个组件：产品产出数量覆盖表 —— ★★ B2b 起**本组件已从本类型删除** ──────────────
    //   GM 产出数值覆盖的唯一权威在 production（{@code ProductionData.outputQuantityOverrides()}，经
    //   {@code ProductionRuntime#outputQuantityOverrides()} 只读投影）；预计与实际共用它
    //   （{@code ProductionRuntimeEngine.effectiveOutputPerUnit}，I6）。
    //   ★ 原构造期守卫（键/值非 null、数量 ∈ [0, {@link #MAX_OUTPUT_QUANTITY}]）由 {@code ProductionData}
    //     自己的构造期判（非 null、非负）；"产业存在 / 商品在该方案配方产出键里"由
    //     {@code EconomyReferenceValidation.validateOutputQuantityOverrides} 在候选提交前判（读 production
    // 投影）。

    // ── Z1 第 33 个组件：生产效率累计与余数表 —— ★★ B2b 起**本组件已从本类型删除** ──────────────
    //   效率累计与三段精度余数的唯一权威在 production（{@code ProductionData.efficiency()}，经

    // ── ★★★ D-032 C2（D2 §5.3）：第 32 个组件的形状守卫 + 冻结 ────────────────────────────────
    //   ★ 本组件只做**不依赖 production 定义**的校验（键 == 值内 unit、null）；角色存在性/份额 owner/
    //     资格/权重规则等跨切片判据在 `EconomyReferenceValidation#validateProductionRosters`。
    //   ★ 逐键复制 + 冻在赋值处：与其余 Map 组件同款（访问器返回冻结副本，调用方改不动）。
    if (productionRosters == null) {
      productionRosters = Map.of();
    }
    Map<ProductionUnitId, ProductionRoster> productionRostersCopy = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, ProductionRoster> entry : productionRosters.entrySet()) {
      ProductionUnitId rosterUnit = entry.getKey();
      ProductionRoster roster = entry.getValue();
      if (rosterUnit == null || roster == null) {
        throw new IllegalArgumentException("productionRosters 的键与值都不得为 null: " + rosterUnit);
      }
      if (!rosterUnit.equals(roster.unit())) {
        throw new IllegalArgumentException(
            "productionRosters 的键必须与 ProductionRoster.unit 一致（D2-I1）：键="
                + rosterUnit.value()
                + "，行内 unit="
                + roster.unit().value());
      }
      productionRostersCopy.put(rosterUnit, roster);
    }
    productionRosters = Collections.unmodifiableMap(productionRostersCopy); // ★ 冻在赋值处

    // ── ★★★ D-032 W2a：第 33 个组件（候选级工资默认）形状守卫 + 冻结 ────────────────────────────
    //   ★ 本组件只判本域形状（键 == 值内 (plan, role, marketZone)、null、quote ≥ 0、target > 0）；
    //     角色存在性/`MONEY_WAGE`/市场区存在性由 EconomyReferenceValidation 读 production/economy 权威判。
    if (productionWageDefaults == null) {
      productionWageDefaults = Map.of();
    }
    Map<WageDefaultKey, ProductionWageDefault> wageDefaultsCopy = new LinkedHashMap<>();
    for (Map.Entry<WageDefaultKey, ProductionWageDefault> entry :
        productionWageDefaults.entrySet()) {
      WageDefaultKey key = entry.getKey();
      ProductionWageDefault value = entry.getValue();
      if (key == null || value == null) {
        throw new IllegalArgumentException("productionWageDefaults 的键与值都不得为 null: " + key);
      }
      if (!key.equals(value.key())) {
        throw new IllegalArgumentException(
            "wage-default-key-mismatch: productionWageDefaults 的键必须等于值内 (plan, role, marketZone)：键="
                + key
                + "，值内="
                + value.key());
      }
      wageDefaultsCopy.put(key, value);
    }
    productionWageDefaults = Collections.unmodifiableMap(wageDefaultsCopy); // ★ 冻在赋值处

    // ── ★★★ D-032 W2a：第 34 个组件（执行期工资岗位）形状守卫 + 冻结 ────────────────────────────
    //   ★ 键 == 值内 (unit, role)、null、target/quote/streak/观测值域；币种 legalTender 与角色支付类型由
    //     EconomyReferenceValidation/命令层判（跨切片）。
    if (productionWagePostings == null) {
      productionWagePostings = Map.of();
    }
    Map<WagePostingKey, ProductionWagePosting> wagePostingsCopy = new LinkedHashMap<>();
    for (Map.Entry<WagePostingKey, ProductionWagePosting> entry :
        productionWagePostings.entrySet()) {
      WagePostingKey key = entry.getKey();
      ProductionWagePosting value = entry.getValue();
      if (key == null || value == null) {
        throw new IllegalArgumentException("productionWagePostings 的键与值都不得为 null: " + key);
      }
      if (!key.equals(value.key())) {
        throw new IllegalArgumentException(
            "wage-posting-key-mismatch: productionWagePostings 的键必须等于值内 (unit, role)：键="
                + key
                + "，值内="
                + value.key());
      }
      wagePostingsCopy.put(key, value);
    }
    productionWagePostings = Collections.unmodifiableMap(wagePostingsCopy); // ★ 冻在赋值处

    // ── ★★★ D-032 W2b：第 35 个组件（逐户工资进位余数）形状守卫 + 冻结 ─────────────────────────
    //   ★ 键 == 值内身份由 WageCarryKey 自身保证；本处只判 null 与值域 0..999（坏 carry 是契约故障）。
    if (wageCarryRemainders == null) {
      wageCarryRemainders = Map.of();
    }
    Map<WageCarryKey, Long> wageCarryCopy = new LinkedHashMap<>();
    for (Map.Entry<WageCarryKey, Long> entry : wageCarryRemainders.entrySet()) {
      WageCarryKey key = entry.getKey();
      Long carry = entry.getValue();
      if (key == null || carry == null) {
        throw new IllegalArgumentException("wageCarryRemainders 的键与值都不得为 null: " + key);
      }
      if (carry < 0L || carry > 999L) {
        throw new IllegalArgumentException(
            "wage-carry-out-of-range: wageCarryRemainders 的值必须在 0..999: 键="
                + key
                + "，carry="
                + carry);
      }
      wageCarryCopy.put(key, carry);
    }
    wageCarryRemainders = Collections.unmodifiableMap(wageCarryCopy); // ★ 冻在赋值处
  }

  /**
   * ★★ R4-E2：一个格在 economy 侧是否"可定位" —— 该格至少有一行家户，或一张市场。
   *
   * <p>★ <b>为什么市场也算</b>：{@code economy.SetMarketPrice} 可以先给一个尚无家户/产业的格建市场（命令只要求格存在），其后落在该格的 HEX
   * 需求不应因为"还没有家户行"被判成坏状态 —— 需求路径会在订单生成时按当时人口摊到 0 户（合法）。
   *
   * <p>★★ <b>D-032 B4（§13.14.2 裁定十五）/ B5（§13.15.4 裁定二十）</b>：产业那一维（"该格登记过产业定义"）已随 economy 的定义副本删除 ——
   * 它现在由 state-aware 守卫 {@code EconomyReferenceValidation.validateDemandHexes} 读 production
   * 当刻定义权威判： <b>落点 = {@code EconomyReferenceValidation.java} 里 {@code definitions.industries()}
   * 的那一支</b>（与家户行 / 市场两支合成同一个三元判据）。 本方法只判本域（家户行 / 市场）。
   *
   * <p>★ <b>边界（裁定二十明文要求写明，如实记）</b>：{@code EconomyCodec} <b>单独 decode</b>（不经候选态校验面）时， "HEX
   * 需求指名的格只有产业、没有家户/市场"这一形态<b>不再被拦</b>；该维只在候选态校验面补齐（注册点 {@code Shell.java:1199}， 含创世态 {@code
   * CoreSimos.bootstrapGenesisInternal:399-402}）。⇒ 落库前仍然 fail-closed，V10 往返由测试 owner 覆盖。
   */
  private static boolean hexRegistered(
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<MarketZoneId, MarketTable> marketTables,
      Map<MarketZoneId, MarketZone> marketZones,
      HexCoord hex) {
    // ★★★ D-032 B4（§13.14.2 裁定十五）：产业维（"该格登记过产业定义"）已不在本域可查（定义副本已删）⇒
    //   本方法只判 economy 侧两维；那一维由 state-aware 守卫 `EconomyReferenceValidation.validateDemandHexes` 补齐。
    for (HouseholdEconomy householdEconomy : householdEconomies.values()) {
      if (householdEconomy.view().hex().equals(hex)) {
        return true;
      }
    }
    // ★★ W1：价表按**区**存之后，"这一格有市场"= 这一格在任一张价表的成员格里（派生形态），
    //   或属于某个市场区（持久形态 —— 区成员格都有本区价表，见 MarketTable 的类注）。
    for (MarketTable table : marketTables.values()) {
      if (table.hexes().contains(hex)) {
        return true;
      }
    }
    if (marketZones != null) {
      for (MarketZone zone : marketZones.values()) {
        if (zone.hexes().contains(hex)) {
          return true;
        }
      }
    }
    return false;
  }

  // ★★★ D-032 B4（§13.14.2 裁定十五）：原先的 `requireIndustryRegistered(industries, view, what)`
  //   （flows 行的"该格登记过产业"构造期守卫）**已删除** —— 判据本身没删，它平移到了 state-aware 守卫
  //   `EconomyReferenceValidation.validateFlowsOnIndustryHex`（读 production 当刻定义权威）。
  //   构造期没有定义副本可查 ⇒ 留在那里的只会是一条永远看不见定义的死判据。

  /**
   * ★★ <b>2026-10-09 选项 A 的读口：一个家户作为债务人的全部合同 id（规范序 = {@link DebtContractId} 规范串升序）</b>。
   *
   * <p>★★ <b>它逐值等于改前 {@code HouseholdEconomy.debts()} 的返回值</b>（同一份合同表 + 同一套对账规则）：引用表按 {@code
   * classes} 键序、一户之内按合同 id 升序建立，故本方法按下标顺序收集即可，不需要再排序。
   *
   * <p>★ <b>2026-10-10（B4：规范序）：这条"不需要再排序"的前提现在被构造期真正保证</b> —— 对账器 {@code
   * DebtReferenceReconciler.reconcile} 的 no-op 判据是<b>含顺序</b>的比较（改前用 {@code Map.equals}，顺序不同也判"一致" ⇒
   * 非规范序被原样放行）。故引用表的迭代序 = 内容的纯函数，<b>不随 advance 的分段方式变</b>。
   *
   * <p>★ <b>为什么是一个方法而不是让调用方自己扫表</b>：引用表的键是复合键（{@code 家户@合同}），"取某户的引用"是它唯一的读法；
   * 散在各处的过滤会把"键的形状"变成多处拼写点（下一个改键的人会漏改其中一处）。读口不抛：该户没有引用 ⇒ 空表（与改前"行里没有 debts ⇒ 空 list"逐值相同）。
   *
   * @param household 家户稳定身份；不得为 null
   * @return 保序不可变清单（可能为空；绝不返回 null）
   */
  public List<DebtContractId> debtsOf(HouseholdId household) {
    Objects.requireNonNull(household, "household 不得为 null");
    List<DebtContractId> ids = new ArrayList<>();
    for (HouseholdDebtReference reference : householdDebtRefs.keySet()) {
      if (reference.household().equals(household)) {
        ids.add(reference.contract());
      }
    }
    return Collections.unmodifiableList(ids);
  }

  /** 一个组件一个 with（照 {@code LedgerData} 的形制）。 */
  public EconomyData withMeta(Optional<EconomyMeta> value) {
    return new EconomyData(
        value,
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
        pledges,
        liquidationPolicies,
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
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  /**
   * ★★ <b>本切片已占用的格键</b>（规范 {@code <q>_<r>}；<b>唯一拼写点</b>）= 家户行的居住格。
   *
   * <p>★ <b>为什么它在这里</b>：这条判据同时服务三条路径 —— ① 追加播种的"格已被占用"守卫（{@code economy.Seed}）；② {@code
   * economy.UpsertGovUnit} 的格守卫；③ <b>D-032 B4 起</b>组合根的 {@code
   * ProductionConsumerPort#isHexEconomicallyActive}（"某格是否已有已激活的经济状态"的 economy 半边）。 它是"economy
   * 侧现状"的纯派生读，故挂在状态类型上、由使用者共用，而不是在别处各抄一份。
   *
   * <p>★★ <b>权威边界（B2b/B4）</b>：本方法只认 <b>economy 自己的家户行</b>。生产实例不在这里 —— 实例的唯一权威在 production，组合根把"本方法
   * ∪ production 落在此格的实例"拼成完整的"格已激活"判据（旧 economy {@code units}/定义副本两维都已删）。
   */
  public Set<String> occupiedHexKeys() {
    Set<String> hexes = new LinkedHashSet<>();
    for (HouseholdEconomy householdEconomy : classes.values()) {
      hexes.add(
          IndustryHexKeys.hexKey(
              householdEconomy.view().hex().q(), householdEconomy.view().hex().r()));
    }
    return hexes;
  }

  /** 一个组件一个 with（照 {@code LedgerData} 的形制）。 */
  public EconomyData withHouseholdEconomies(Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    return new EconomyData(
        meta,
        householdEconomies,
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
        pledges,
        liquidationPolicies,
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
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  /**
   * ★★ E4a：债务合同表（第 4 个组件，替换旧的 {@code debts} 槽）—— 键 = {@link DebtContractId}， 值 = {@link
   * DebtContract}。其余 30 个组件原样带过（全表共 31 个组件）。
   */
  public EconomyData withDebtContracts(Map<DebtContractId, DebtContract> value) {
    return new EconomyData(
        meta,
        classes,
        value,
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
        pledges,
        liquidationPolicies,
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
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  /** 一个组件一个 with（照 {@code LedgerData} 的形制）。 */
  public EconomyData withFlows(Map<HouseholdId, FlowRow> value) {
    return new EconomyData(
        meta,
        classes,
        debtContracts,
        value,
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
        pledges,
        liquidationPolicies,
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
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  public EconomyData withLaborCommitments(
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments) {
    return new EconomyData(
        meta,
        classes,
        debtContracts,
        flows,
        laborCommitments,
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
        pledges,
        liquidationPolicies,
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
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  /** 一个组件一个 with（T2：生产关系表）；其余 30 个组件原样带过（全表共 31 个组件）。 */
  public EconomyData withRelations(Map<ProductionUnitId, ProductionRules> value) {
    return new EconomyData(
        meta,
        classes,
        debtContracts,
        flows,
        allocations,
        value,
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
        pledges,
        liquidationPolicies,
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
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  /**
   * 一个组件一个 with（H4：市场表）；其余 30 个组件原样带过（全表共 31 个组件）。
   *
   * <p>★ <b>它是"GM 定价格"的唯一写入口</b>（铁律 2：所有修改最终表示为 Command → ChangeSet → Revision）——
   * 本批还没有"设价"命令，故它现在只被载荷（创世播种）与用例用到；命令留待 GM 参数目录落地。
   */
  /**
   * ★★ <b>W1：市场轮统计簿的唯一写入形态</b>（每个市场轮结束时由 {@code EconomySettlement} 调一次）。
   *
   * <p>★ 手写 {@code new EconomyData(…, productionRosters)} 会在下一次新增组件时静默丢掉某个组件（本仓最贵的那类 bug，铁律 5 的由来）
   * ⇒ 所有写入都必须走本方法。
   */
  public EconomyData withMarketRoundStats(Map<MarketZoneId, MarketRoundStats> value) {
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
        pledges,
        liquidationPolicies,
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
        value,
        productionRosters,
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  /**
   * ★★ <b>W1：价表的唯一写入形态</b>（键 = 市场 id；每区一张）。
   *
   * <p>★ 手写 {@code new EconomyData(…, productionRosters)} 会在下一次新增组件时静默丢掉某个组件（本仓最贵的那类 bug，铁律 5 的由来）
   * ⇒ 所有只改价表的写入都必须走本方法。
   *
   * <p>★★ <b>什么时候<b>不能</b>单用它</b>（2026-10-11 W1d）：当这次变更同时要动<b>区表</b>（新建区 / 撤区 / 改法定币）时，
   * 中间态会被构造期的跨表守卫拒掉（"区在、价表不在"或"价表计价币 ≠ 区法定币"）—— 那种"区 + 它的价表成对变化"的场景走 {@link
   * #withMarketZonesAndTables}。★ 反过来说：<b>只改价表</b>（改价、改某些行的价目）时中间态自洽，本方法就是正确入口。
   */
  public EconomyData withMarketTables(Map<MarketZoneId, MarketTable> value) {
    return new EconomyData(
        meta,
        classes,
        debtContracts,
        flows,
        allocations,
        relations,
        value,
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
        pledges,
        liquidationPolicies,
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
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  /**
   * ★★ <b>第 10 个组件（M2.4）：在途批次表</b>；其余 30 个组件原样带过（全表共 31 个组件）。
   *
   * <p>★ 与 {@link #withMarketTables} 同款：它是"跨 tick 状态"的唯一写入口（在日循环的到货销账与发运建账里被调用）， 不是 GM 命令面。
   */
  public EconomyData withShipments(Map<ShipmentId, ShipmentBatch> value) {
    return new EconomyData(
        meta,
        classes,
        debtContracts,
        flows,
        allocations,
        relations,
        marketTables,
        value,
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
        pledges,
        liquidationPolicies,
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
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  public EconomyData withOwnershipStakes(Map<AssetShareId, OwnershipStake> value) {
    return new EconomyData(
        meta,
        classes,
        debtContracts,
        flows,
        allocations,
        relations,
        marketTables,
        shipments,
        value,
        operatorConditions,
        demands,
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
        periodicAdjustments,
        currencies,
        moneyInstruments,
        marketZones,
        householdDebtRefs,
        commodityFreightBaseMilli,
        govMarketMandates,
        marketRoundStats,
        productionRosters,
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  /** 一个组件一个 with（S3.2：经营者状态表）；其余 30 个组件原样带过（全表共 31 个组件）。 */
  public EconomyData withOperatorConditions(Map<ProductionUnitId, OperatorCondition> value) {
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
        value,
        demands,
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
        periodicAdjustments,
        currencies,
        moneyInstruments,
        marketZones,
        householdDebtRefs,
        commodityFreightBaseMilli,
        govMarketMandates,
        marketRoundStats,
        productionRosters,
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  // ★★ B2b：`withProcesses` 已删（units 组件退役）。实例的唯一权威在 production，写它一律经契约口
  //   `createInstance` / `removeInstance` / `reoperateInstance`（见 EconomyEntrySettlement /
  // EconomyEnterpriseSettlement /
  //   EconomyModeTransitionSettlement / ModeMigrationSettlement）。

  /** ★★ R4-E2：需求账本（第 15 个组件）；其余 30 个组件原样带过（全表共 31 个组件）（GM 命令的唯一写入口）。 */
  public EconomyData withHouseholdDemands(Map<DemandId, HouseholdDemand> householdDemands) {
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
        householdDemands,
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
        periodicAdjustments,
        currencies,
        moneyInstruments,
        marketZones,
        householdDebtRefs,
        commodityFreightBaseMilli,
        govMarketMandates,
        marketRoundStats,
        productionRosters,
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  // ★★★ B5（§13.15.2 裁定十八）：`withModes(Map<ProductionModeId, ProductionModeDefinitionView>)`
  //   （第 17 个组件的写口）**整方法删除** —— 组件本身已从本类型删除，生产方式定义的唯一权威与唯一写面
  //   都在 production（`ProductionData.withModes`，经 production 命令面
  //   `production.UpsertProductionMode` / `production.DeactivateProductionMode`）。

  /** ★★ E1：阶层结构表（第 18 个组件）；其余 30 个组件原样带过（全表共 31 个组件）。 */
  public EconomyData withClassStructures(Map<ClassStructureId, ClassStructure> value) {
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
        periodicAdjustments,
        currencies,
        moneyInstruments,
        marketZones,
        householdDebtRefs,
        commodityFreightBaseMilli,
        govMarketMandates,
        marketRoundStats,
        productionRosters,
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  /** ★★ E1：阶层位置表（第 19 个组件）；其余 30 个组件原样带过（全表共 31 个组件）。 */
  public EconomyData withProductionRoles(Map<ClassPositionId, ProductionRole> value) {
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
        periodicAdjustments,
        currencies,
        moneyInstruments,
        marketZones,
        householdDebtRefs,
        commodityFreightBaseMilli,
        govMarketMandates,
        marketRoundStats,
        productionRosters,
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  /**
   * ★★ <b>E1/P7：阶层结构与全局阶层位置表的成对写口</b>（第 18/19 两个组件一次落值）；其余 30 个组件原样带过（全表共 31 个组件）。
   *
   * <p>★ <b>为什么必须成对</b>：{@link ClassStructure#positions()} 与全局 {@code classPositions} 有"逐值相等 +
   * 每条位置至少属于一个结构"的双向守卫，而现有 {@code withClassStructures}/{@code withProductionRoles} 各自只改一个组件 ——
   * 单独改任一侧都会让中间态过不了守卫（"结构里写一套、全局表里另写一套"）。GM 编辑（{@code economy.GmAdjust} 的
   * upsertClassStructure/upsertClassPosition）需要同时 upsert 结构与全局位置，故这里提供唯一的成对落值口；
   * <b>不是第二套状态</b>：两个参数就是那两个既有组件，最终仍走同一个 canonical 构造器与全部守卫。
   */
  public EconomyData withClassStructuresAndPositions(
      Map<ClassStructureId, ClassStructure> structures,
      Map<ClassPositionId, ProductionRole> positions) {
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
        periodicAdjustments,
        currencies,
        moneyInstruments,
        marketZones,
        householdDebtRefs,
        commodityFreightBaseMilli,
        govMarketMandates,
        marketRoundStats,
        productionRosters,
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  /** ★★ E1：家户阶层归属表（第 20 个组件）；其余 30 个组件原样带过（全表共 31 个组件）。 */
  public EconomyData withClassMemberships(
      Map<HouseholdId, HouseholdClassMembership> classMemberships) {
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
        classMemberships,
        productionOrganizations,
        assetRules,
        governments,
        moneyIssuances,
        pledges,
        liquidationPolicies,
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
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  /** ★★ E2：生产组织表（第 21 个组件）；其余 30 个组件原样带过（全表共 31 个组件）。 */
  public EconomyData withProductionEnterprises(
      Map<ProductionOrganizationId, ProductionEnterprise> value) {
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
        value,
        assetRules,
        governments,
        moneyIssuances,
        pledges,
        liquidationPolicies,
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
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  /** ★★ E2：生产资料规则表（第 22 个组件）；其余 30 个组件原样带过（全表共 31 个组件）。 */
  public EconomyData withAssetRules(Map<AssetRuleId, AssetRule> value) {
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
        value,
        governments,
        moneyIssuances,
        pledges,
        liquidationPolicies,
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
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  /** ★★ E3：政府表（第 23 个组件）；其余 30 个组件原样带过（全表共 31 个组件）。 */
  public EconomyData withGovernments(Map<GovernmentId, Government> value) {
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
        value,
        moneyIssuances,
        pledges,
        liquidationPolicies,
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
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  /** ★★ E3：货币发行审计表（第 24 个组件）；其余 30 个组件原样带过（全表共 31 个组件）。 */
  public EconomyData withMoneyIssuances(Map<MoneyIssuanceId, MoneyIssuanceRecord> value) {
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
        value,
        pledges,
        liquidationPolicies,
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
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  /**
   * ★★ E4a：质押表（第 25 个组件，追加在末尾）；其余 30 个组件原样带过（全表共 31 个组件）。
   *
   * <p>★ {@code pledges} 为空 = 没有质押；本方法只让调用方逐组件构造，跨表守卫 （Σ活跃质押 ≤ share.quantity）仍由规范构造器按“对侧已提供”分段判。
   */
  public EconomyData withPledges(Map<PledgeId, Pledge> value) {
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
        value,
        liquidationPolicies,
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
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  /**
   * ★★ E5a：清算政策表（第 26 个组件）；其余 30 个组件原样带过（全表共 31 个组件）。
   *
   * <p>★ 键 == 值内 {@code ruleId}；{@code assetRules} 非空时被引用规则必须存在 —— 两条守卫都由规范构造器 fail-closed。 空表 =
   * 没有清算制度参数，旧行为逐值不变。
   */
  public EconomyData withLiquidationPolicies(Map<AssetRuleId, LiquidationPolicy> value) {
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
        pledges,
        value,
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
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  /**
   * ★★ E5a：hex 危机信号表（第 27 个组件，追加在末尾）；其余 30 个组件原样带过（全表共 31 个组件）。
   *
   * <p>★ 键 == 值内 id == {@code CrisisSignalId.idOf(hex, kind)}；同 hex 同 kind 覆盖即更新（不追加历史）。 空表 =
   * 没有信号；E5a 不产生任何信号。
   */
  public EconomyData withCrisisSignals(Map<CrisisSignalId, HexCrisisSignal> value) {
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
        pledges,
        liquidationPolicies,
        value,
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
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  /**
   * ★★ E6a：模式变迁表（第 28 个组件）；其余 30 个组件原样带过（全表共 31 个组件）。
   *
   * <p>★ 键 == 值内 id == {@code ModeTransitionId.idOf(organizationId, toModeId,
   * effectiveDay)}；同一组织至多一条 PENDING。空表 = 没有模式变迁请求，旧行为逐值不变。
   */
  public EconomyData withModeTransitions(Map<ModeTransitionId, ModeTransition> value) {
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
        pledges,
        liquidationPolicies,
        crisisSignals,
        value,
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
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  /**
   * ★★ E6a：阶层保留份额表（第 29 个组件，追加在末尾）；其余 30 个组件原样带过（全表共 31 个组件）。
   *
   * <p>★ 键 == 值内 id == {@code ClassShareId.idOf(transitionId, householdId, classPositionId)}；同一
   * {@code (transitionId, householdId)} 的 Σ sharePerMille 必须 = 1000（规范构造器逐组判）。空表 = 没有份额记录。
   */
  public EconomyData withClassShares(Map<ClassShareId, ClassShare> value) {
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
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        value,
        periodicAdjustments,
        currencies,
        moneyInstruments,
        marketZones,
        householdDebtRefs,
        commodityFreightBaseMilli,
        govMarketMandates,
        marketRoundStats,
        productionRosters,
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  /**
   * ★★ P4a：周期家户扣增规则表（第 31 个组件，追加在末尾）；其余 30 个组件原样带过（全表共 31 个组件）。
   *
   * <p>键 = {@link PeriodicHouseholdAdjustmentId}，且必须等于值内 {@link HouseholdPeriodicAdjustment#id()}。
   * 规则只描述"每周期请求扣多少"，不落账户；是否到期与部分支付由 app 的 executor 按绝对世界日现算。
   */
  public EconomyData withPeriodicAdjustments(
      Map<PeriodicHouseholdAdjustmentId, HouseholdPeriodicAdjustment> value) {
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
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        value,
        currencies,
        moneyInstruments,
        marketZones,
        householdDebtRefs,
        commodityFreightBaseMilli,
        govMarketMandates,
        marketRoundStats,
        productionRosters,
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  // ★★ B2b：`withOutputQuantityOverrides` 已删（GM 产出覆盖的唯一权威在 production）。

  // ★★ B2b：`withProductionEfficiency` 已删（效率累计与余数的唯一权威在 production）。

  /** {@link ProductionUnitId#idOf} 的固定前缀（唯一拼写点；用来识别"这看起来是一个 unit id"）。 */
  private static final String UNIT_ID_PREFIX = "unit-";

  // ★★ B.2b 的两个 hasLegacyProductionBits(...) 判据已随 D-032 A 批删除（原因见 compact 构造器里那段注释）：
  //   它们读的 5 个兼容位已不在 {@link IndustryDefinitionView} 上，判据在本类型上无法表达。

  /**
   * ★★ <b>A1：币种表</b>（货币词表的身份一半）；其余组件原样带过。
   *
   * <p>键 = {@link CurrencyId}，且必须等于值内 {@link CurrencyDef#currencyId()}（键即身份，不许两处拼币种名）。 空表 ⇒
   * 构造期归一到旧世界默认（{@code silver}）——见类注；改名（只换 {@code displayName}）走它，<b>不动任何账</b>（I16）。
   */
  public EconomyData withCurrencies(Map<CurrencyId, CurrencyDef> value) {
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
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        periodicAdjustments,
        value,
        moneyInstruments,
        marketZones,
        householdDebtRefs,
        commodityFreightBaseMilli,
        govMarketMandates,
        marketRoundStats,
        productionRosters,
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  /**
   * ★★ <b>A1：货币工具表</b>（货币词表的工具一半）；其余组件原样带过。
   *
   * <p>键 = {@link InstrumentId}，且必须等于值内 {@link MoneyInstrument#id()}；每张工具的币种必须在 {@code currencies}
   * 里有定义（跨表守卫在构造期判）。空表 ⇒ 只在"整个词表缺席"时归一到 {@code silver-specie}。
   */
  public EconomyData withMoneyInstruments(Map<InstrumentId, MoneyInstrument> value) {
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
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        periodicAdjustments,
        currencies,
        value,
        marketZones,
        householdDebtRefs,
        commodityFreightBaseMilli,
        govMarketMandates,
        marketRoundStats,
        productionRosters,
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  /**
   * ★★ <b>B2：市场区表</b>（约束设计书 §4.2 / I22）；其余组件原样带过。
   *
   * <p>键 = {@link MarketZoneId}，且必须等于值内 {@link MarketZone#zoneId()}（键即身份，不许两处拼区名）。<b>空表</b> ⇒
   * 市场区退回"城市 + tier 半径"派生（既有世界的默认值，逐值不变）；<b>非空</b> ⇒ 成员格由本表唯一给定。
   *
   * <p>★★ <b>它是"划界/退让/覆盖/合并"的唯一写入形态</b>：手写 {@code new EconomyData(…, productionRosters)}
   * 会在下一次新增组件时静默丢掉某个 组件（本仓最贵的那类 bug）；三个区命令都走它。跨表守卫（hex 不重叠、法定币在词表里、发行政府登记且 issuable 含法定币）
   * 由构造期判，命令层只是把它们提前成具名拒因。
   */
  public EconomyData withMarketZones(Map<MarketZoneId, MarketZone> value) {
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
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        periodicAdjustments,
        currencies,
        moneyInstruments,
        value,
        householdDebtRefs,
        commodityFreightBaseMilli,
        govMarketMandates,
        marketRoundStats,
        productionRosters,
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  /**
   * ★★ <b>W1d（2026-10-11）：市场区表与价表（第 36/9 两个组件）的成对写口</b>；其余 38 个组件原样带过（全表共 40 个组件）。
   *
   * <p>★★ <b>为什么必须成对</b>：两条跨表守卫把这两张表锁在一起 —— ① 键 ∈ 区表的价表条目，其 {@code numeraire} 必须逐值等于该区 {@code
   * legalTender}；② 该条目的 {@code hexes} 必须为空、且不得覆盖已属某区的格。两条都在 {@code EconomyData} 的 <b>compact
   * 构造器</b>里，而<b>每一次</b> {@code with*()} 都会重新构造并重跑它们 ⇒ "新建区 + 给它一张表" / "撤区 + 摘掉它的表" / "改区法定币 +
   * 改价表计价币"这三种变更，若拆成两个单组件 wither， <b>没有任何合法中间态</b>：
   *
   * <ul>
   *   <li>{@code withMarketZones(新区)} 先调 ⇒ 该区的表还没进 ⇒ 被读成"区没有价表"（W1d 前是硬拒；W1d 起缺表合法， 但下一个 wither
   *       前的中间态仍然不是目标态，且若同时还要摘掉旧条目就出现了瞬时不一致）；
   *   <li>{@code withMarketTables(新表)} 先调 ⇒ 该键还不是区 ⇒ 被读成<b>派生市场</b> ⇒ 撞"派生市场必须有成员格"；
   *   <li>改计价币时拆开 ⇒ 中间态是"新币的价表 + 旧法定币的区" ⇒ 撞"价表计价币必须等于该区法定币"。
   * </ul>
   *
   * <p>⇒ 与 {@link #withClassStructuresAndPositions}、{@code EconomyClearRegionHandler} 末尾的"classes +
   * memberships 同一次规范构造"<b>同款</b>：把两个互相约束的组件一次改变。★ <b>它不是第二套状态</b>：两个参数就是那两个既有组件， 最终仍走同一个 canonical
   * 构造器与全部守卫（含上面那两条）—— 只是让"目标态"而不是"中间态"接受检查。
   *
   * <p>★ 调用方（本批三个）：{@code economy.DefineMarketZone}（立区 + 它的表）、{@code economy.MergeMarketZones} （撤源区
   * + 摘它的表）、{@code economy.SetMarketNumeraire}（改法定币 + 改价表计价币）。
   *
   * @param zones 新的市场区表（键 = 区 id；空表 = 派生形态）
   * @param tables 新的价表（键 = 市场 id；含全部派生市场条目与全部持久区的条目）
   */
  public EconomyData withMarketZonesAndTables(
      Map<MarketZoneId, MarketZone> zones, Map<MarketZoneId, MarketTable> tables) {
    return new EconomyData(
        meta,
        classes,
        debtContracts,
        flows,
        allocations,
        relations,
        tables,
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
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        periodicAdjustments,
        currencies,
        moneyInstruments,
        zones,
        householdDebtRefs,
        commodityFreightBaseMilli,
        govMarketMandates,
        marketRoundStats,
        productionRosters,
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  /**
   * ★★ <b>第 38 个组件：商品基础运费表</b>（约束设计书 §4.1 修正版）；其余组件原样带过。
   *
   * <p>键 = {@link CommodityId}，值 = 该商品的<b>基础运费</b>（毫计价货币 / 商品单位 / 程）。<b>空表</b> ⇒ 每个商品落到 {@link
   * CommodityFreightBase#legacyMilli(CommodityId)}（现行硬编码分档 ⇒ 未设表的世界逐值不变）；<b>键缺</b> ⇒ 同上（{@link
   * #commodityFreightBaseMilliOf}）。
   *
   * <p>★★ <b>它是本组件的唯一写入形态</b>：手写 {@code new EconomyData(…, productionRosters)}
   * 会在下一次新增组件时静默丢掉某个组件（本仓最贵的那类 bug）；GM 命令 {@code economy.SetCommodityFreight} 走它。★ 值域（{@code ≥
   * 0}）由构造期具名判死；键域（必须是词表里的商品） 由命令层判。
   */
  public EconomyData withCommodityFreightBaseMilli(Map<CommodityId, Long> value) {
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
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        periodicAdjustments,
        currencies,
        moneyInstruments,
        marketZones,
        householdDebtRefs,
        value,
        govMarketMandates,
        marketRoundStats,
        productionRosters,
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  /**
   * ★★ <b>R1（第 39 个组件）：政府市场授权表</b>（约束设计书 §4.5 G8 / 不变量 I-P6）；其余组件原样带过。
   *
   * <p>键 = {@link MarketMandateId}（必须等于值内 {@link GovernmentMarketMandate#id()}），值 = 一次「明确授权下单」
   * （谁/商品/方向/量/限价/到期）。<b>空表</b> ⇒ 国库户在商品市场上零订单（与 Z7b 的"退出商品市场"在行为上逐值相同）； 授权主体必须是已登记的政府、且其国库必须是家户 ——
   * 由 compact 构造器具名判死。
   *
   * <p>★★ <b>它是本组件的唯一写入形态</b>：手写 {@code new EconomyData(…, productionRosters)}
   * 会在下一次新增组件时静默丢掉某个组件（本仓最贵的那类 bug）；两条 GM 命令（{@code economy.AuthorizeGovernmentMarketOrder} /
   * {@code economy.CancelGovernmentMarketOrder}）与日结算的成交回写走它。
   */
  public EconomyData withGovMarketMandates(Map<MarketMandateId, GovernmentMarketMandate> value) {
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
        pledges,
        liquidationPolicies,
        crisisSignals,
        modeTransitions,
        classShares,
        periodicAdjustments,
        currencies,
        moneyInstruments,
        marketZones,
        householdDebtRefs,
        commodityFreightBaseMilli,
        value,
        marketRoundStats,
        productionRosters,
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  /** 币种表里全部 id 的可读清单（拒因/诊断用；保序）。 */
  private static List<String> currencyIdsOf(Map<CurrencyId, CurrencyDef> table) {
    List<String> ids = new ArrayList<>(table.size());
    for (CurrencyId currency : table.keySet()) {
      ids.add(currency.value());
    }
    return ids;
  }

  /**
   * ★★ S1：把 {@link Payee.ToCohort} 一对一归一到 {@link Payee.ToHousehold}。
   *
   * <p>判据：<b>该 view 恰有一个家户</b>（旧档的既有事实）⇒ 迁移；view 有歧义（S3 才允许）⇒ 保留旧变体交由 S3
   * 的视图选择器解释。归一化是构造期的纯函数、幂等（归一后的规则不再含 ToCohort）。
   *
   * <p>★ <b>S3 审计</b>：索引每次都用**当前** {@code classes} 行集合重建，不缓存历史 view。默认关系在第一次构造时就已归一为 {@code
   * ToHousehold(稳定身份)} ⇒ 之后的阶层写回只改 {@code HouseholdEconomy.view}，不会让这些规则改指到别的家户；仍保留的 {@code
   * ToCohort} 是旧档/多义视图的兼容窄口，按当前行集合解释。
   */
  private static Map<ProductionUnitId, ProductionRules> normalizePayees(
      Map<ProductionUnitId, ProductionRules> raw,
      Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    Map<CohortKey, HouseholdId> unique = new LinkedHashMap<>();
    Set<CohortKey> ambiguous = new LinkedHashSet<>();
    for (HouseholdEconomy householdEconomy : householdEconomies.values()) {
      if (unique.putIfAbsent(householdEconomy.view(), householdEconomy.id()) != null) {
        ambiguous.add(householdEconomy.view());
      }
    }
    for (CohortKey view : ambiguous) {
      unique.remove(view);
    }
    Map<ProductionUnitId, ProductionRules> normalized = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, ProductionRules> entry : raw.entrySet()) {
      ProductionRules relation = entry.getValue();
      List<CompensationRule> rules = new ArrayList<>(relation.rules().size());
      boolean changed = false;
      for (CompensationRule rule : relation.rules()) {
        if (rule.recipient() instanceof Payee.ToCohort toCohort) {
          HouseholdId household = unique.get(toCohort.cohort());
          if (household != null) {
            rules.add(
                new CompensationRule(
                    rule.type(),
                    new Payee.ToHousehold(household),
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
              ? new ProductionRules(
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

  // ── ★★★ D-032 C2（D2 §5.3）：名册组件的逐组件写口（不绕过 ChangeSet 的落盘路径）───────────────

  /** 整份替换一张名册表（GM 命令 / 创世 / 自动名册共用；其余 31 个组件原样带过）。 */
  public EconomyData withProductionRosters(Map<ProductionUnitId, ProductionRoster> value) {
    Objects.requireNonNull(value, "value（清除全部名册请用空表）");
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
        pledges,
        liquidationPolicies,
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
        value,
        productionWageDefaults,
        productionWagePostings,
        wageCarryRemainders);
  }

  /** 写入/替换一个 unit 的名册（键 == 值内 unit 由构造期再判一次）。 */
  public EconomyData withProductionRoster(ProductionRoster roster) {
    Objects.requireNonNull(roster, "roster");
    LinkedHashMap<ProductionUnitId, ProductionRoster> next = new LinkedHashMap<>(productionRosters);
    next.put(roster.unit(), roster);
    return withProductionRosters(next);
  }

  /** 清除一个 unit 的名册；键不存在 ⇒ 逐值不变（返回同值的新实例）。 */
  public EconomyData withoutProductionRoster(ProductionUnitId unit) {
    Objects.requireNonNull(unit, "unit");
    if (!productionRosters.containsKey(unit)) {
      return this;
    }
    LinkedHashMap<ProductionUnitId, ProductionRoster> next = new LinkedHashMap<>(productionRosters);
    next.remove(unit);
    return withProductionRosters(next);
  }

  // ── ★★★ D-032 W2a：第 33/34 个组件的逐组件写口 ──────────────────────────────────────────────

  /** 整份替换候选级工资默认表；空表 = 清空。 */
  public EconomyData withProductionWageDefaults(Map<WageDefaultKey, ProductionWageDefault> value) {
    Objects.requireNonNull(value, "value（清除全部工资默认请用空表）");
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
        pledges,
        liquidationPolicies,
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
        value,
        productionWagePostings,
        wageCarryRemainders);
  }

  /** 整份替换执行期工资岗位表；空表 = 清空。 */
  public EconomyData withProductionWagePostings(Map<WagePostingKey, ProductionWagePosting> value) {
    Objects.requireNonNull(value, "value（清除全部工资岗位请用空表）");
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
        pledges,
        liquidationPolicies,
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
        productionWageDefaults,
        value,
        wageCarryRemainders);
  }

  // ── ★★★ D-032 W2b：第 35 个组件的逐组件写口 ──────────────────────────────────────────────

  /** 整份替换逐户工资进位余数表；空表 = 全部为 0。 */
  public EconomyData withWageCarryRemainders(Map<WageCarryKey, Long> value) {
    Objects.requireNonNull(value, "value（清除全部工资进位请用空表）");
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
        pledges,
        liquidationPolicies,
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
        productionWageDefaults,
        productionWagePostings,
        value);
  }

  /** 写入/替换一条 carry（0 的条目仍保留；清空用整表写口）。 */
  public EconomyData withWageCarry(WageCarryKey key, long carry) {
    Objects.requireNonNull(key, "key");
    LinkedHashMap<WageCarryKey, Long> next = new LinkedHashMap<>(wageCarryRemainders);
    next.put(key, carry);
    return withWageCarryRemainders(next);
  }
}
