package io.mosire.simos.economy;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.actor.api.actor.ActorKind;
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
import io.mosire.simos.economy.model.EconomyPolicyKey;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdDebtReference;
import io.mosire.simos.economy.model.HouseholdDemand;
import io.mosire.simos.economy.model.HouseholdEconomy;
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
import io.mosire.simos.economy.model.WageAnchorKey;
import io.mosire.simos.economy.model.WageCarryKey;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
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
 * <p>★ **组件与 {@link io.mosire.simos.economy.change.EconomyChangeSet} 一一对应**（铁律 5）：
 * ★★★ D-032 P4c3 删除旧两张工资表，只保留 {@code productionWageAnchors}（U20 工资锚唯一报价权威）。
 * 新增状态组件必须同时进变更集，由 {@code EconomyRoundTripTest} 的反射枚举把守。
 *
 * <p>★★ **E1–E6 追加组件清单**（逐阶段；数字是本记录全表的组件序号/个数，E6b/E6c 零新状态组件）：
 *
 * <ul>
 *   <li>E1（第 18–20 个，3 个）：{@code classStructures} / {@code classPositions} / {@code
 *       classStandings}（★ 第 17 个 {@code modes} 已由 B5 删除，见下）；
 *   <li>E2（第 21–22 个，2 个）：{@code productionOrganizations} / {@code assetRules}；
 *   <li>E3（第 23–24 个，2 个）：{@code governments} / {@code moneyIssuances}；
 *   <li>E4/E5 的相关组件已随 P2d 删除；
 *   <li>E5 的 {@code crisisSignals} 保留；
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
 * <p>★ **三十三张表都保序不可变**：{@code LinkedHashMap} + {@code Collections.unmodifiableMap}，**绝不用 {@code
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
 * 也没有它的写口；原"结构/位置/组织/规则/变迁 → mode 存在性"五维守卫的落点是候选态守卫 {@code
 * EconomyReferenceValidation#validateModeAssociations}，反向"mode → 结构存在性"落点是 {@code
 * validateModeStructures}（两者都在 {@code EconomyProductionReferenceValidator} 里，注册点 {@code
 * Shell.java:1199}）。
 *
 * <p>★★ **E2 追加第 21–22 个组件**（{@code productionOrganizations} / {@code assetRules}）：前者是"生产方式 + 阶层结构
 * + 劳动 + 资产"之间的桥（{@code ProductionEnterprise}），由日结算的自动组织阶段 upsert；后者是 mode 下每种生产资料的 租佃/转移规则（{@code
 * AssetRule}）。★ 两张表为空时自动组织阶段整体 no-op，旧路径逐值不变；非空时的引用完整性同样按 "对侧是否已提供"分段生效（unit / ownershipStake /
 * productionRole / mode 存在性）。
 *
 * <p>★★ **E3 追加第 23–24 个组件**（{@code governments} / {@code moneyIssuances}）：政府表建立"谁是哪个币种的发行主体"，
 * 发行审计表记录 INITIAL_ENDOWMENT / FISCAL_ISSUE / WITHDRAWAL。两表为空时旧结算路径逐值不变（零登记 ⇒ 付方余额不足照旧
 * fail-closed）；旧档缺这两个键 ⇒ 空表。
 *
 * <p>★★ **E4a：第 4 个组件从旧 {@code debts} 槽替换为 {@code debtContracts}**：债务唯一权威表 = {@code
 * Map<DebtContractId, DebtContract>}；同一 {@code (debtor, creditor, unit, terms)} 跨周期同一条合同，不新开条；旧
 * {@code debt-cN-...} 的周期聚合条由 {@code EconomyCodec} 的旧档迁移按四元组合并（principal 用 {@code Math.addExact}
 * 求和）。
 *
 * <p>★★ **E5a 保留 {@code crisisSignals}**：按 {@link CrisisSignalId} 键 hex 危机信号（键 == 由 {@code (hex,
 * kind)} 确定性派生；同 hex 同 kind 覆盖即更新）。
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
    // ── ★★★ D-032 W2b（D3 §2.4/§2.6）：逐 (unit, role, worker) 工资进位余数（0..999）──
    Map<WageCarryKey, Long> wageCarryRemainders,
    // ── ★★★ D-032 D4-Ib1（D4 §4.3/§8.2）：第 36/37 个组件 —— 资产占用授予与商品库存预留 ─────────
    //   空表合法（= 没有占用/预留）；键 == 值内 id。两张表是唯一权威，必须同批进 ChangeSet/codec。
    Map<AssetOccupancyGrantId, AssetOccupancyGrant> assetOccupancies,
    Map<InventoryReservationId, InventoryReservation> inventoryReservations,
    // ── ★★★ D-032 P4c3：U20 工资锚（唯一工资报价权威）───────────────────────────────────────
    //   键 = (marketZone, industry, role)；值 = 锚。空表 = 没有锚（缺锚 ≠ 0）；显式命令 / seed 才写。
    Map<WageAnchorKey, ProductionWageAnchor> productionWageAnchors)
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

  /** W10：政策读口（委托 EconomyDataQueries；语义见原 Javadoc）。 */
  @Override
  public long economyPolicyOf(EconomyPolicyKey key) {
    return EconomyDataQueries.economyPolicyOf(meta, key);
  }

  /** W10：该政策键是否被显式配置过（委托 EconomyDataQueries）。 */
  @Override
  public boolean economyPolicyConfigured(EconomyPolicyKey key) {
    return EconomyDataQueries.economyPolicyConfigured(meta, key);
  }

  /** 商品基础运费读口（委托 EconomyDataQueries；缺键 ⇒ 具名缺省）。 */
  public long commodityFreightBaseMilliOf(CommodityId commodity) {
    return EconomyDataQueries.commodityFreightBaseMilliOf(commodityFreightBaseMilli, commodity);
  }

  /** GOV 单位对应的政府记录（委托 EconomyDataQueries；未登记 ⇒ fail-closed）。 */
  public Government governmentOf(String govUnitId) {
    return EconomyDataQueries.governmentOf(governments, govUnitId);
  }

  /** GOV 单位对应的政府记录（委托 EconomyDataQueries；未登记 ⇒ 中性缺省）。 */
  public Government governmentOrDefault(String govUnitId) {
    return EconomyDataQueries.governmentOrDefault(governments, govUnitId);
  }

  /**
   * 往返用例的起点：未激活 + 33 张空表（34 个组件 = meta + 33 张表）。
   *
   * <p>★★ B2b（§13.11.2 裁定六）：{@code units} / {@code outputQuantityOverrides} / {@code
   * productionEfficiency} 三个组件已从本类型删除；★★ B2c（§13.12.2 裁定八）：第四个 {@code candidates} 同批删净（唯一权威在
   * production）⇒ 四个「旧组件面便捷构造器」 一并删除：本类型只剩 canonical 构造器与 {@code with*}，不再有「取空表兜底」的第二条构造口径。
   */
  public static EconomyData empty() {
    return EconomyDataTransforms.empty();
  }

  public EconomyData {
    if (meta == null) {
      meta = Optional.empty();
    }
    classes = EconomyDataInvariants.emptyIfNull(classes);
    debtContracts = EconomyDataInvariants.emptyIfNull(debtContracts);
    flows = EconomyDataInvariants.emptyIfNull(flows);
    allocations = EconomyDataInvariants.emptyIfNull(allocations);
    relations = EconomyDataInvariants.emptyIfNull(relations);
    marketTables = EconomyDataInvariants.emptyIfNull(marketTables);
    shipments = EconomyDataInvariants.emptyIfNull(shipments);
    assetShares = EconomyDataInvariants.emptyIfNull(assetShares);
    operatorConditions = EconomyDataInvariants.emptyIfNull(operatorConditions);
    demands = EconomyDataInvariants.emptyIfNull(demands);
    classStructures = EconomyDataInvariants.emptyIfNull(classStructures);
    classPositions = EconomyDataInvariants.emptyIfNull(classPositions);
    classStandings = EconomyDataInvariants.emptyIfNull(classStandings);
    productionOrganizations = EconomyDataInvariants.emptyIfNull(productionOrganizations);
    assetRules = EconomyDataInvariants.emptyIfNull(assetRules);
    governments = EconomyDataInvariants.emptyIfNull(governments);
    moneyIssuances = EconomyDataInvariants.emptyIfNull(moneyIssuances);
    crisisSignals = EconomyDataInvariants.emptyIfNull(crisisSignals);
    modeTransitions = EconomyDataInvariants.emptyIfNull(modeTransitions);
    classShares = EconomyDataInvariants.emptyIfNull(classShares);
    periodicAdjustments = EconomyDataInvariants.emptyIfNull(periodicAdjustments);
    currencies = EconomyDataInvariants.emptyIfNull(currencies);
    moneyInstruments = EconomyDataInvariants.emptyIfNull(moneyInstruments);
    marketZones = EconomyDataInvariants.emptyIfNull(marketZones);
    householdDebtRefs = EconomyDataInvariants.emptyIfNull(householdDebtRefs);
    commodityFreightBaseMilli = EconomyDataInvariants.emptyIfNull(commodityFreightBaseMilli);
    govMarketMandates = EconomyDataInvariants.emptyIfNull(govMarketMandates);
    marketRoundStats = EconomyDataInvariants.emptyIfNull(marketRoundStats);
    productionRosters = EconomyDataInvariants.emptyIfNull(productionRosters);
    wageCarryRemainders = EconomyDataInvariants.emptyIfNull(wageCarryRemainders);
    assetOccupancies = EconomyDataInvariants.emptyIfNull(assetOccupancies);
    inventoryReservations = EconomyDataInvariants.emptyIfNull(inventoryReservations);
    productionWageAnchors = EconomyDataInvariants.emptyIfNull(productionWageAnchors);

    // ★ 旧词表判定必须在 currencies 归一到 legacy defs 之前取；与改前 compact 构造器同一时点。
    boolean legacyVocabulary = currencies.isEmpty();

    classes = EconomyDataInvariants.validateClasses(classes);
    debtContracts = EconomyDataInvariants.validateDebtContracts(debtContracts);
    householdDebtRefs =
        EconomyDataInvariants.reconcileDebtReferences(debtContracts, classes, householdDebtRefs);
    flows = EconomyDataInvariants.validateFlows(flows, classes);
    allocations = EconomyDataInvariants.validateAllocations(allocations, classes);
    assetShares = EconomyDataInvariants.validateAssetShares(assetShares);
    relations = EconomyDataInvariants.validateRelations(relations, classes);
    marketTables = EconomyDataInvariants.validateMarketTables(marketTables);
    shipments = EconomyDataInvariants.validateShipments(shipments);
    operatorConditions = EconomyDataInvariants.validateOperatorConditions(operatorConditions);
    demands = EconomyDataInvariants.validateDemands(demands, classes, marketTables, marketZones);

    classStructures = EconomyDataStructureInvariants.validateClassStructures(classStructures);
    classPositions =
        EconomyDataStructureInvariants.validateClassPositions(classPositions, classStructures);
    classStandings =
        EconomyDataStructureInvariants.validateClassStandings(
            classStandings, classes, classPositions);
    productionOrganizations =
        EconomyDataStructureInvariants.validateProductionOrganizations(
            productionOrganizations, classPositions, assetShares, classes);
    assetRules = EconomyDataStructureInvariants.validateAssetRules(assetRules);
    governments = EconomyDataStructureInvariants.validateGovernments(governments);
    moneyIssuances =
        EconomyDataStructureInvariants.validateMoneyIssuances(moneyIssuances, governments);
    currencies = EconomyDataStructureInvariants.validateCurrencies(currencies, governments);
    moneyInstruments =
        EconomyDataStructureInvariants.validateMoneyInstruments(
            moneyInstruments, currencies, legacyVocabulary);
    marketZones = EconomyDataStructureInvariants.validateMarketZones(marketZones, currencies);
    EconomyDataStructureInvariants.validateMarketTablesAgainstZones(marketTables, marketZones);
    commodityFreightBaseMilli =
        EconomyDataInvariants.validateCommodityFreight(commodityFreightBaseMilli);
    govMarketMandates =
        EconomyDataStructureInvariants.validateGovMarketMandates(govMarketMandates, governments);
    marketRoundStats = EconomyDataStructureInvariants.validateMarketRoundStats(marketRoundStats);
    crisisSignals = EconomyDataProductionInvariants.validateCrisisSignals(crisisSignals, classes);
    modeTransitions =
        EconomyDataProductionInvariants.validateModeTransitions(
            modeTransitions, productionOrganizations);
    classShares =
        EconomyDataProductionInvariants.validateClassShares(
            classShares, modeTransitions, classes, classPositions);
    periodicAdjustments =
        EconomyDataProductionInvariants.validatePeriodicAdjustments(periodicAdjustments);
    productionRosters =
        EconomyDataProductionInvariants.validateProductionRosters(productionRosters);
    wageCarryRemainders =
        EconomyDataProductionInvariants.validateWageCarryRemainders(wageCarryRemainders);
    assetOccupancies = EconomyDataProductionInvariants.validateAssetOccupancies(assetOccupancies);
    inventoryReservations =
        EconomyDataProductionInvariants.validateInventoryReservations(inventoryReservations);
    productionWageAnchors =
        EconomyDataProductionInvariants.validateProductionWageAnchors(
            productionWageAnchors, marketZones);
  }

  /** {@link ProductionUnitId#idOf} 的固定前缀（唯一拼写点；用来识别"这看起来是一个 unit id"）。 */
  private static final String UNIT_ID_PREFIX = "unit-";

  /** 2026-10-09 选项 A 的读口：一个家户作为债务人的全部合同 id。 */
  public List<DebtContractId> debtsOf(HouseholdId household) {
    return EconomyDataQueries.debtsOf(householdDebtRefs, household);
  }

  /** 本切片已占用的格键（规范 q_r；只认家户行）。 */
  public Set<String> occupiedHexKeys() {
    return EconomyDataQueries.occupiedHexKeys(classes);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withMeta(Optional<EconomyMeta> value) {
    return EconomyDataTransforms.withMeta(this, value);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withHouseholdEconomies(Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    return EconomyDataTransforms.withHouseholdEconomies(this, householdEconomies);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withDebtContracts(Map<DebtContractId, DebtContract> value) {
    return EconomyDataTransforms.withDebtContracts(this, value);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withFlows(Map<HouseholdId, FlowRow> value) {
    return EconomyDataTransforms.withFlows(this, value);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withLaborCommitments(
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments) {
    return EconomyDataTransforms.withLaborCommitments(this, laborCommitments);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withRelations(Map<ProductionUnitId, ProductionRules> value) {
    return EconomyDataTransforms.withRelations(this, value);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withMarketRoundStats(Map<MarketZoneId, MarketRoundStats> value) {
    return EconomyDataTransforms.withMarketRoundStats(this, value);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withMarketTables(Map<MarketZoneId, MarketTable> value) {
    return EconomyDataTransforms.withMarketTables(this, value);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withShipments(Map<ShipmentId, ShipmentBatch> value) {
    return EconomyDataTransforms.withShipments(this, value);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withOwnershipStakes(Map<AssetShareId, OwnershipStake> value) {
    return EconomyDataTransforms.withOwnershipStakes(this, value);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withOperatorConditions(Map<ProductionUnitId, OperatorCondition> value) {
    return EconomyDataTransforms.withOperatorConditions(this, value);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withHouseholdDemands(Map<DemandId, HouseholdDemand> householdDemands) {
    return EconomyDataTransforms.withHouseholdDemands(this, householdDemands);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withClassStructures(Map<ClassStructureId, ClassStructure> value) {
    return EconomyDataTransforms.withClassStructures(this, value);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withProductionRoles(Map<ClassPositionId, ProductionRole> value) {
    return EconomyDataTransforms.withProductionRoles(this, value);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withClassStructuresAndPositions(
      Map<ClassStructureId, ClassStructure> structures,
      Map<ClassPositionId, ProductionRole> positions) {
    return EconomyDataTransforms.withClassStructuresAndPositions(this, structures, positions);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withClassMemberships(
      Map<HouseholdId, HouseholdClassMembership> classMemberships) {
    return EconomyDataTransforms.withClassMemberships(this, classMemberships);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withProductionEnterprises(
      Map<ProductionOrganizationId, ProductionEnterprise> value) {
    return EconomyDataTransforms.withProductionEnterprises(this, value);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withAssetRules(Map<AssetRuleId, AssetRule> value) {
    return EconomyDataTransforms.withAssetRules(this, value);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withGovernments(Map<GovernmentId, Government> value) {
    return EconomyDataTransforms.withGovernments(this, value);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withMoneyIssuances(Map<MoneyIssuanceId, MoneyIssuanceRecord> value) {
    return EconomyDataTransforms.withMoneyIssuances(this, value);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withCrisisSignals(Map<CrisisSignalId, HexCrisisSignal> value) {
    return EconomyDataTransforms.withCrisisSignals(this, value);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withModeTransitions(Map<ModeTransitionId, ModeTransition> value) {
    return EconomyDataTransforms.withModeTransitions(this, value);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withClassShares(Map<ClassShareId, ClassShare> value) {
    return EconomyDataTransforms.withClassShares(this, value);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withPeriodicAdjustments(
      Map<PeriodicHouseholdAdjustmentId, HouseholdPeriodicAdjustment> value) {
    return EconomyDataTransforms.withPeriodicAdjustments(this, value);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withCurrencies(Map<CurrencyId, CurrencyDef> value) {
    return EconomyDataTransforms.withCurrencies(this, value);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withMoneyInstruments(Map<InstrumentId, MoneyInstrument> value) {
    return EconomyDataTransforms.withMoneyInstruments(this, value);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withMarketZones(Map<MarketZoneId, MarketZone> value) {
    return EconomyDataTransforms.withMarketZones(this, value);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withMarketZonesAndTables(
      Map<MarketZoneId, MarketZone> zones, Map<MarketZoneId, MarketTable> tables) {
    return EconomyDataTransforms.withMarketZonesAndTables(this, zones, tables);
  }

  /**
   * ★★★ D-032 P4fix2：区表 + 价表 + 工资锚表<b>同一次候选态构造</b>（原子写口）。
   *
   * <p>{@code economy.DefineMarketZone} 在摘走来源派生市场表的格/整表时，必须把该来源表下的工资锚从 {@code sourceId} 重键到新持久区
   * {@code zoneId}；三张表分开落会造出"锚指向已删表 / 尚未进区表的区"的中间态， 被 {@link EconomyData} 构造期跨表守卫（{@code
   * wage-anchor-market-zone-missing}）拒掉。
   */
  public EconomyData withMarketZonesTablesAndWageAnchors(
      Map<MarketZoneId, MarketZone> zones,
      Map<MarketZoneId, MarketTable> tables,
      Map<WageAnchorKey, ProductionWageAnchor> wageAnchors) {
    return EconomyDataTransforms.withMarketZonesTablesAndWageAnchors(
        this, zones, tables, wageAnchors);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withCommodityFreightBaseMilli(Map<CommodityId, Long> value) {
    return EconomyDataTransforms.withCommodityFreightBaseMilli(this, value);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withGovMarketMandates(Map<MarketMandateId, GovernmentMarketMandate> value) {
    return EconomyDataTransforms.withGovMarketMandates(this, value);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withProductionRosters(Map<ProductionUnitId, ProductionRoster> value) {
    return EconomyDataTransforms.withProductionRosters(this, value);
  }

  /** ★★★ D-032 P4b1：U20 工资锚表的写口（空表 = 清除；缺锚 ≠ 0）。 */
  public EconomyData withProductionWageAnchors(
      Map<WageAnchorKey, ProductionWageAnchor> value) {
    return EconomyDataTransforms.withProductionWageAnchors(this, value);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withWageCarryRemainders(Map<WageCarryKey, Long> value) {
    return EconomyDataTransforms.withWageCarryRemainders(this, value);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withAssetOccupancies(Map<AssetOccupancyGrantId, AssetOccupancyGrant> value) {
    return EconomyDataTransforms.withAssetOccupancies(this, value);
  }

  /** 一个组件一个 with（委托 EconomyDataTransforms；行为不变）。 */
  public EconomyData withInventoryReservations(
      Map<InventoryReservationId, InventoryReservation> value) {
    return EconomyDataTransforms.withInventoryReservations(this, value);
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

  /** 写入/替换一条 carry（0 的条目仍保留；清空用整表写口）。 */
  public EconomyData withWageCarry(WageCarryKey key, long carry) {
    Objects.requireNonNull(key, "key");
    LinkedHashMap<WageCarryKey, Long> next = new LinkedHashMap<>(wageCarryRemainders);
    next.put(key, carry);
    return withWageCarryRemainders(next);
  }

  /** 写入/替换一条资产占用（键 == 值内 id 由构造期再判一次）。 */
  public EconomyData withAssetOccupancy(AssetOccupancyGrant grant) {
    Objects.requireNonNull(grant, "grant");
    LinkedHashMap<AssetOccupancyGrantId, AssetOccupancyGrant> next =
        new LinkedHashMap<>(assetOccupancies);
    next.put(grant.id(), grant);
    return withAssetOccupancies(next);
  }

  /** 删除一条资产占用；键不存在 ⇒ 逐值不变。 */
  public EconomyData withoutAssetOccupancy(AssetOccupancyGrantId id) {
    Objects.requireNonNull(id, "id");
    if (!assetOccupancies.containsKey(id)) {
      return this;
    }
    LinkedHashMap<AssetOccupancyGrantId, AssetOccupancyGrant> next =
        new LinkedHashMap<>(assetOccupancies);
    next.remove(id);
    return withAssetOccupancies(next);
  }

  /** 写入/替换一条预留（键 == 值内 id 由构造期再判一次）。 */
  public EconomyData withInventoryReservation(InventoryReservation reservation) {
    Objects.requireNonNull(reservation, "reservation");
    LinkedHashMap<InventoryReservationId, InventoryReservation> next =
        new LinkedHashMap<>(inventoryReservations);
    next.put(reservation.id(), reservation);
    return withInventoryReservations(next);
  }

  /** 删除一条预留；键不存在 ⇒ 逐值不变。 */
  public EconomyData withoutInventoryReservation(InventoryReservationId id) {
    Objects.requireNonNull(id, "id");
    if (!inventoryReservations.containsKey(id)) {
      return this;
    }
    LinkedHashMap<InventoryReservationId, InventoryReservation> next =
        new LinkedHashMap<>(inventoryReservations);
    next.remove(id);
    return withInventoryReservations(next);
  }
}
