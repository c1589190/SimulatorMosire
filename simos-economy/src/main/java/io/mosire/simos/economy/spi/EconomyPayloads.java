package io.mosire.simos.economy.spi;

import static io.mosire.simos.economy.spi.EconomyDebtPayloads.crisisSignals;
import static io.mosire.simos.economy.spi.EconomyDebtPayloads.debtContracts;
import static io.mosire.simos.economy.spi.EconomyHouseholdPayloads.allocation;
import static io.mosire.simos.economy.spi.EconomyHouseholdPayloads.canonicalAllocationActivity;
import static io.mosire.simos.economy.spi.EconomyHouseholdPayloads.householdEconomy;
import static io.mosire.simos.economy.spi.EconomyHouseholdPayloads.productionRosters;
import static io.mosire.simos.economy.spi.EconomyHouseholdPayloads.resolveAllocationUnits;
import static io.mosire.simos.economy.spi.EconomyHouseholdPayloads.resolveProductionRole;
import static io.mosire.simos.economy.spi.EconomyMarketPayloads.markets;
import static io.mosire.simos.economy.spi.EconomyMarketPayloads.productionWageAnchors;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.actorRef;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.optionalArray;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.optionalObject;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.requireArray;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.requireEntryObject;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.requireInt;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.requireText;
import static io.mosire.simos.economy.spi.EconomyPolicyPayloads.classShares;
import static io.mosire.simos.economy.spi.EconomyPolicyPayloads.governments;
import static io.mosire.simos.economy.spi.EconomyPolicyPayloads.modeTransitions;
import static io.mosire.simos.economy.spi.EconomyPolicyPayloads.moneyIssuances;
import static io.mosire.simos.economy.spi.EconomyPolicyPayloads.parseAssetRules;
import static io.mosire.simos.economy.spi.EconomyPolicyPayloads.parseClassMemberships;
import static io.mosire.simos.economy.spi.EconomyPolicyPayloads.parseClassStructures;
import static io.mosire.simos.economy.spi.EconomyPolicyPayloads.parseProductionRoles;
import static io.mosire.simos.economy.spi.EconomyPolicyPayloads.relation;
import static io.mosire.simos.economy.spi.EconomyProductionPayloads.addOwnershipStake;
import static io.mosire.simos.economy.spi.EconomyProductionPayloads.planNodeOf;
import static io.mosire.simos.economy.spi.EconomyProductionPayloads.requireIndustryHexMatchesEntry;
import static io.mosire.simos.economy.spi.EconomyProductionPayloads.unit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.AssetRuleId;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ClassShareId;
import io.mosire.simos.economy.api.id.ClassStructureId;
import io.mosire.simos.economy.api.id.CrisisSignalId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ModeTransitionId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.AssetRule;
import io.mosire.simos.economy.model.ClassShare;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.ModeTransition;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.model.ProductionWageAnchor;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.economy.model.RentRule;
import io.mosire.simos.economy.model.TransferRule;
import io.mosire.simos.economy.model.WageAnchorKey;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * {@code economy.Seed} 命令的载荷解析助手（与 {@code SocialPayloads} / {@code MapPayloads} 同制）。
 *
 * <p>★ **载荷形态是本模块的私事**（C26）：Core 只转交 {@code payloadJson} 字节串。形态如下（**与 §3 的 record 字段一一对应**）：
 *
 * <pre>{@code
 * {"mapId":"Map1","rulesVersion":"aggregate-v1","entries":[
 *   {"q":0,"r":0,"plans":[
 *     {"id":"farm@0_0","name":"农业","regime":"feudal","cycleDays":120,
 *      "capacityPerUnit":{"LAND":1000},"laborPerUnit":143,
 *      "dailyInputPerUnit":{},"dailyLaborPerUnit":0,"outputPerUnit":{"grain":67,"fiber":12},
 *      "cycleInputPerUnit":{"LAND":{"grain":8000}},
 *      "allocation":{"@class":"split","meansWeightPerMille":700,"laborWeightPerMille":300},
 *      "slots":[{"id":"poor_peasant","name":"贫农","laborParticipationPerMille":950}]}],
 *    "classes":[{"residence":"rural","slot":"poor_peasant","population":450,"laborMilli":261000,
 *                "participationPerMille":950,"money":0,
 *                "naturalNeeds":{"grain":37350},"effectiveDemand":{}}],
 *    "laborSupply":[{"group":"rural:0_0:MALE:1","period":1,"grossLaborMilli":261000,
 *                    "servedLaborMilli":0,"committedLaborMilli":0}],
 *    "allocations":[{"id":"alloc-0-farm@0_0","group":"rural:0_0:MALE:1",
 *                    "actor":{"kind":"ESTATE","id":"farm@0_0"},"activity":"farm",
 *                    "laborMilli":261000,"period":1}]}],
 *  "markets":{"0_0":{"numeraire":"silver","prices":{"grain":1,"cloth":5}}}}
 * }</pre>
 *
 * <p>★★★ <b>B5（§13.15.3 裁定十九）</b>：上例的 `plans` 键原名 `industries`，且节点自带一整套产业定义 +
 * 旧形状实例字段（`operator`/`capacity`/`progressDays`/`cycleLaborMilli`/`cycleInputUsedMilli`）。B5 起：
 *
 * <ol>
 *   <li>产业定义<b>不进 economy 载荷</b> —— 唯一权威在 production 的 `production.Seed` `industries[]`
 *       （`ProductionDefinitionLookup`）；
 *   <li>旧形状实例字段<b>不再解析</b>（合成 unit / 整额 OWNED 份额那条路径已删；旧档 fail-closed 是 Alpha 允许的）；
 *   <li>`plans[]` 只剩"该格有哪些方案节点"（每个节点是 unit 节点的宿主与 `relation` 的来源）； `units[]` 里逐项自带
 *       `industry`/`operator`/`modeKey`，产业模板从 production 当刻定义权威取。
 * </ol>
 *
 * <p>★★ <b>R3B.2 的新形状（上面的样例是旧形状留痕）</b>：{@code industries[]} 只留模板（无 {@code operator/capacity/
 * progressDays/cycleLaborMilli/cycleInputUsedMilli}）；{@code units[]} 显式给生产单元（{@code id?/industry/
 * operator?/modeKey?/progressDays?/cycleLaborMilli?/cycleInputUsedMilli?}）；{@code
 * allocations[].activity} = unit id、{@code actor} = unit.operator。★ 上面的旧形状**仍可读**：industry
 * 的旧实例字段**不再解析**（B5 / §13.15.3 裁定十九：economy 载荷不再自带产业定义，旧形状合成路径已删）。
 *
 * <p>★★ <b>H0（2026-09-27，裁定 K2/K3 + R-N1-A）的三处形状变化 —— 三条都是"编译绿、运行红"的坑，逐条写清</b>：
 *
 * <ol>
 *   <li>★★ <b>{@code classes} 从"产业节点内"搬到 <b>entry 级</b>，且每行多一个 {@code residence}</b>：行 = 家户 =
 *       {@code (格, 居住类型, 阶层)}（K2）⇒ 它<b>不再属于某个产业</b>（农村家户同时供给农业与家庭纺织）。{@code q}/{@code r} 取自
 *       entry；{@code residence} 走 {@link ResidenceKind#parse}（词表外即抛）。★ <b>为什么必须显式给</b>：改前"行属于哪个产业"
 *       隐含了居住类型（{@code farm}/{@code weave} = 农村、{@code craft} = 城镇），H0 之后那层隐含没有了 —— 缺键就<b>抛</b>
 *       （不许按产业种类猜：{@code weave} 的家户就是农村那四行，猜出来的第二份约定会与配额表漂开）；
 *   <li>★★ <b>{@code meansOfProduction} 键<b>不再接受</b></b>（K3）：产能搬到 {@code
 *       IndustryDefinitionView.capacity}。★ 留着不读 = "看起来在记、其实被静默丢掉"（真档会变成"全格没有产能 ⇒ 绝收"而无人察觉）⇒
 *       <b>给了即抛</b>，消息点名新键；
 *   <li>★★ <b>{@code IndustryDefinitionView} 多一个 {@code capacity}（本格该产业的产能总量）</b>：缺键 ⇒ 空表 ⇒ 规模 0（=
 *       本格没有产能， 真档里沙漠格的 {@code LAND = 0}、人口不足一厂的格 {@code SMALL_TOOL = 0} 正是这一形态）。★ <b>逐值允许 0</b> （与
 *       {@code capacityPerUnit} 的"必须 > 0"性质不同）。
 * </ol>
 *
 * <p>★★ <b>H1（2026-09-27，裁定 D3-C/K1）的第四处形状变化：{@code classes[].goods} 键<b>不再接受</b></b> —— 家户的商品库存住在
 * actor 切片的 {@code HouseholdInventory}（键 {@code (HouseholdActors.of(cohort), cohort.hex())}），
 * economy 侧只在**会话工作副本**（{@code 旧日推进器（R3a 已删除）} 的入参）里读它。★ 与 K3 的 {@code meansOfProduction} 同款理由：
 * 载荷里留着它而解析器静默忽略 = 创世库存凭空消失（真档表现为第 1 天全员断粮，而载荷看起来完全正常）⇒ <b>给了即抛</b>。 ★ <b>播种那一份要搬</b>：app 的 {@code
 * HouseholdSeeder} 把它写进该家户 actor 的账户，**不再**写进行载荷。
 *
 * <p>★ <b>默认关系的居住维从哪来</b>：{@code relation} 缺键时按 {@code regime} 推，而 cohort 受方要带居住类型 —— 本类从**同一条
 * entry 的 {@code allocations}** 推（{@link ResidenceKind#ofLot}，批次前缀的唯一拼写点）： {@code 产业 →
 * 供给它的那些批次的居住类型集合}。⇒ {@code allocations} 先解析、再推 relation。★ 这个集合**多于一种即抛** （见 {@code
 * RegimeRelations}：非劳动加权的规则会重复计费）；空集合 ⇒ 该产业不产生 cohort 规则。
 *
 * <p>★★ **R3（V7）的两处形状变化**（spec §五）：
 *
 * <ul>
 *   <li>新增 {@code capacityPerUnit}（每 1 单位规模需要多少生产资料）与 {@code laborPerUnit}（每 1 单位规模需要多少劳动） —— 它们与
 *       {@code inputPerUnit}（由 {@code cycleInputPerUnit} 合计而来，**不进载荷**）和 {@code outputPerUnit} 一起构成
 *       {@code IndustryDefinitionView.recipe()} 的四个分量；
 *   <li>两个投入表的**值侧带上商品维度**：{@code "cycleInputPerUnit":{"LAND":8000}} ⇒ {@code
 *       "cycleInputPerUnit":{"LAND":{"grain":8000}}}（"消耗 IRON"这种话原来表达不了）； {@code
 *       "cycleSeedUsedMilli":0} ⇒ {@code "cycleInputUsedMilli":{}}（按商品的累加器）。
 * </ul>
 *
 * <p>★★ <b>H4 的第五处形状变化：顶层的 {@code markets}（第 9 个组件的载荷键）</b> —— <b>可选键</b>，形状 {@code
 * {"<q>_<r>":{"numeraire":"<币种>","prices":{"<商品>":<单价>}}, …}}：
 *
 * <ul>
 *   <li>★ <b>为什么是顶层、键 = 格</b>：{@code EconomyData.markets} 就是 {@code Map<MarketZoneId, MarketTable>}
 *       ⇒ 载荷与状态**同形**（一份数据一处拼写）。★ 键走 {@link HexCoord#parse}（{@code "0_0"} 是格串的唯一拼写点， 调用方不自己拼）；★ 缺键
 *       / 缺格 = 该格没有市场（合法状态：真档创世只给有经济 entry 的格发市场）；
 *   <li>★★ <b>每一格市场必须在 {@code entries} 里出现</b>（fail-closed）：命令的作用域是 entry 的格集 （{@code
 *       EconomySeedHandler.targetPaths} 报的就是它）—— 一个落在 entry 之外的格会在<b>权限/目标</b>面上
 *       成为"没人申报的写"，故此处当场拒（不静默种下一个谁也管不到的格）；
 *   <li>★ <b>{@code numeraire} 必填</b>（每格恰一种计价货币，裁定 M1-A）；{@code prices} 可缺（⇒ 空表 = 这一格什么 都还没定价）。★
 *       单价**逐值 &gt; 0**、量纲 = <b>毫计价货币 / 商品单位</b>（1 商品单位 = 1000 毫单位）—— 两条守卫都在 {@link MarketTable}
 *       的构造期与类注里，本层只做形状（同"数值语义交给领域类型"的既有口径）；
 *   <li>★ <b>本批没有"设价"命令</b>：价格只能由创世载荷给（GM 调价要等参数目录/命令落地，见 {@code EconomyData.withMarkets}）。
 * </ul>
 *
 * <p>★ **旧档兼容不在本轮范围**（spec §十.4 的裁定："旧档：重建也没关系"）：随包的 {@code worlds/v17levant.json} **不含 economy
 * 切片**（只有 map/social/unit），故这两处形状变化不影响它能否打开。
 *
 * <p>★ **S1 阶段 3：{@code operator} 是可选键**（上面的样例里就带着它）。**缺键 ⇒ 按 {@code regime} 推导** （{@link
 * RegimeOperators#defaultOperator}，裁定 R1）—— 这是**载荷边缘**的推导点：{@code IndustryDefinitionView} 收了 {@code
 * null} 是**抛**，不是补（裁定 D1）。★ 与 {@code allocations[].actor} 的口径**刻意不同**：那里缺键是**拒**、 这里是**推导**，不许合并。
 *
 * <p>★★ <b>S1 阶段 4+5 Task 2：{@code relation} 是可选键</b>（产业对象内的第二个推导点，形态与 {@code operator} 同款）。<b>缺键 ⇒
 * 按 {@code regime} 推导</b>（{@link RegimeRelations#defaultRelation}，计划 R3/R8）； <b>给了 ⇒
 * 逐值采纳</b>，但两个一致性判据在命令边界判死：
 *
 * <pre>{@code
 * "relation":{
 *   "operator":{"kind":"ESTATE","id":"farm@0_0"},        // 可选；缺 ⇒ 取产业的那个（于是必然一致）
 *   "inputSupplier":{"cohort":"0_0|rural|landlord"},     // 可选（H3/C3）；缺 ⇒ 取 operator（四档默认同值）
 *   "residualOwner":{"kind":"ESTATE","id":"farm@0_0"},   // 可选；缺 ⇒ 取 operator（自留是缺省）
 *   "rules":[{"type":"OUTPUT_SHARE","recipient":{"cohort":"0_0|landlord"},
 *             "pool":"GROSS_OUTPUT","weight":"NONE","ratePerMille":300,"fixedAmount":0,
 *             "commodity":"grain","priority":10}]}                  // rules 可选（缺 ⇒ 空表 = 全归 residualOwner）
 * }</pre>
 *
 * <ul>
 *   <li>★ <b>{@code activity} <b>不是</b>载荷键</b>：关系的身份 = 它所在的**那个产业**（铁律 1 —— 同一件事不许 两处拼写）。故这里只会造出
 *       {@code activity == 本产业 id} 的关系；
 *   <li>★★ <b>{@code operator} 给了就必须与产业的 {@code operator} 逐值相等</b>，否则**抛**（同一件事的两处拼写
 *       不一致时，没有哪一处能判谁对）；<b>缺省取产业的那个</b>—— ★ 不是"再调一次 {@code RegimeOperators.defaultOperator}"：两者在缺
 *       {@code operator} 键时同值，而在**显式给了 operator** 时 只有前者自洽（否则"显式主体 + 缺 relation"会自相矛盾地被拒，I3.1
 *       的用例正是那个形态）；
 *   <li>★★ <b>H3：{@code inputSupplier} 是可选键</b>（"这些投入由谁出"，裁定 C3）—— 形状与 {@code recipient}
 *       逐字同款（{@code {actor:{kind,id}}} 或 {@code {cohort:"<CohortKey 规范串>"}}，恰给其一）； <b>缺键 ⇒ 取 {@code
 *       operator}</b>（= 四档默认，见 {@code RegimeRelations.defaultInputSupplier}）。 ★
 *       <b>缺省不在本层另写一遍</b>：交给 {@code ProductionRules} 的构造期缺省（旧档兼容的那一处边缘）； ★
 *       它**不参与**上面那条一致性判据（供方与经营者**可以**是两个主体 —— 那正是"地主出种"要表达的形态）；
 *   <li>★ <b>受方</b>：{@code recipient} 恰给 {@code actor}（{@code {kind,id}}）或 {@code cohort} （{@code
 *       CohortKey} 的**规范串**，如 {@code "0_0|landlord"}）之一 —— 两个都没给 / 两个都给了 ⇒ 抛；
 *   <li>★★ <b>H2：{@code pool} × {@code weight} 是新档（裁定 D5-B），{@code basis} 是旧档</b> —— 本层是
 *       <b>旧档兼容的那一处边缘</b>：给了 {@code basis} ⇒ 经 {@code Basis.pool()} / {@code Basis.weight()} 翻译
 *       （旧五档的逐档映射表在 {@code Basis} 的类注里）；给了 {@code pool}（{@code weight} 缺省 {@code NONE}）⇒ 直接用。 ★
 *       <b>两个都给了、或都没给 ⇒ 抛</b>（不猜：同一件事的两处拼写不一致时，没有哪一处能判谁对）；
 *   <li>★ <b>{@code commodity}</b>：**缺键 ⇒ 货币档**（{@code Optional.empty()}）、给了 ⇒ 实物档；两个方向都由 {@link
 *       CompensationRule} 的构造期守卫兜底（本层不重复实现那条规则）；
 *   <li>★★ <b>{@code currency}</b>（H2 的币种位）：实物档**不得给**；货币档给了就用、<b>缺键取出厂货币</b> （{@link
 *       RegimeRelations#DEFAULT_CURRENCY} 是唯一拼写点）—— 旧档的货币规则没有这个键，而"旧档读不回来"不是兼容，是事故 （真档播种会当场抛）；
 *   <li>★ <b>{@code ratePerMille} / {@code fixedAmount} / {@code priority} 三个整数必填</b>（不在本层造缺省值：
 *       一条规则的率/额/次序被静默补成 0，读起来是"合法的数据"，实际是"漏写了一个键"）；
 *   <li>★ <b>空 {@code rules} 合法</b>（= 全部自留，裁定 E9 的等价路径）。
 * </ul>
 *
 * <p>★ **坏载荷一律以 {@link IllegalArgumentException} 面世**（带可读中文原因）：形状/类型不对在本层判，**数值语义**（人口/土地/劳动 ≥
 * 0、槽位必须在该产业的 {@code slots} 里、{@code progressDays ≤ cycleDays}）交给 §3 的领域类型与 {@link EconomyData}
 * 构造期守卫——**不重复实现**，一处真相。
 *
 * <p>★ <b>E4c：创世载荷可解析非空初始债务</b>：顶层 {@code debtContracts} 键可缺席（= 空表）， 给了就按对象数组解析（结构与条款在载荷层判形状，id
 * 派生/引用完整性在 {@link EconomyData} 构造期判）。 ★★ <b>配套责任</b>：economy <b>不自动搬 actor 库存</b>；声明初始债务的 seed 必须在
 * app 的 actor.Seed 协调器里给 debtor/creditor 备好对应库存/货币/权利，否则就是凭空种出的无对价债权名册。 旧类行 {@code debts}
 * 键仍可读（它只是派生引用；进入构造期后由合同表权威重建）。
 *
 * <p>★ <b>E5a：创世载荷可解析可选初始危机信号</b>：顶层 {@code crisisSignals} 键可缺席（= 空表）； 结构与取值范围在载荷层判，键身份/家户引用完整性在
 * {@link EconomyData} 构造期判。
 *
 * <p>★ <b>P10.1 的顶层可选 {@code merchantFirms} 数组已于 2026-10-10 M-A1 退役</b>（用户裁定："旧设计和数据类型直接
 * 重建不用留"）：运力改为派生量（{@code MerchantCapacityPool}），创世不再声明任何商号行。既有顶层键的语义一字不动。
 */
final class EconomyPayloads {

  private EconomyPayloads() {}

  /** 解析载荷文本：非 JSON、或不是 JSON 对象 ⇒ 抛。 */
  static JsonNode parse(String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    JsonNode payload;
    try {
      payload = EconomyPayloadSupport.mapper().readTree(payloadJson);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("payload 不是合法 JSON: " + e.getOriginalMessage(), e);
    }
    if (payload == null || !payload.isObject()) {
      throw new IllegalArgumentException("payload 必须是 JSON 对象: " + payloadJson);
    }
    // ★★ 2026-10-09：class-first 线格式已退役 —— economy.Seed 只接受 production-runtime（政府内置）载荷；
    //   顶层 classFirst 键存在即 fail-closed，不把它静默读成"没有生产结构的空世界"。
    if (payload.has("classFirst")) {
      throw new IllegalArgumentException(
          "class-first 线格式已退役：economy.Seed 只接受 production-runtime（政府内置）载荷，"
              + "顶层 classFirst 键不再受支持");
    }
    return payload;
  }

  /**
   * 载荷里逐格的可寻址路径（{@code <q>_<r>}，{@link io.mosire.simos.util.spi.ResourcePaths#economy(int, int)}
   * 的形态） ——{@code CommandTargets} 用它，**不建完整记录**（目标声明只关心"动谁"）。
   */
  static List<String> entryHexKeys(JsonNode payload) {
    JsonNode entries = requireArray(payload, "entries");
    if (entries.isEmpty()) {
      throw new IllegalArgumentException("entries 不得为空");
    }
    List<String> keys = new ArrayList<>(entries.size());
    for (JsonNode entry : entries) {
      requireEntryObject(entry);
      keys.add(requireInt(entry, "q") + "_" + requireInt(entry, "r"));
    }
    return List.copyOf(keys);
  }

  /**
   * 把载荷整份 materialize 成 {@link EconomyData}（含激活元信息）。
   *
   * @param at 世界当前时刻（{@code activatedDay} 取它的 {@link SimosTimestamp#tick()}）
   * @param definitions ★★★ B5（§13.15.3 裁定十九）：production 当刻定义权威 —— 载荷 `industries[]` 键**已退役**
   *     （economy 侧不再组装、也不再解析它），产业模板（id/regime/cycleDays…）一律从这份权威取；某个 unit 指名的 产业不在这里 ⇒
   *     **具名拒**（不存在"economy 载荷自带一份定义"的第二权威）。
   * @param productionInstances ★★ B2b：production 的当刻实例投影（`production.Seed` 已先落账）—— 载荷里的 `units[]`
   *     现在只用来声明**逐 unit 的 relation 与配额归一**，实例本体归 production； 每个载荷 unit
   *     必须能在投影里找到（plan/operator/modeKey 逐值一致），否则**具名拒** （不存在"economy 单方面声明一个实例"的第二权威）。
   */
  static EconomyData toData(
      JsonNode payload,
      SimosTimestamp at,
      io.mosire.simos.production.contract.ProductionDefinitionLookup definitions,
      Map<ProductionUnitId, ProductionProcess> productionInstances,
      Map<MarketZoneId, MarketZone> knownMarketZones) {
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(knownMarketZones, "knownMarketZones（工资锚的区/法定币校验用）");
    Objects.requireNonNull(
        definitions, "definitions（B5：载荷不再带 industries[]，产业定义权威在 production，必须显式注入）");
    Objects.requireNonNull(productionInstances, "productionInstances（B2b：实例权威的只读投影，必须显式注入）");
    // ★★ E4c：`debtContracts` 从"只接受空数组"放开为可解析；结构与引用完整性走 EconomyData 构造期守卫
    //   （id 四元组派生、debtor/creditor 行存在、HouseholdEconomy.debts 引用存在）。
    String mapId = requireText(payload, "mapId");
    String rulesVersion = requireText(payload, "rulesVersion");
    JsonNode entries = requireArray(payload, "entries");
    if (entries.isEmpty()) {
      throw new IllegalArgumentException("entries 不得为空");
    }
    // ★★★ B9（§14.18.3 纪律）：本表的值来自 production 当刻定义权威，写入点**唯一** —— 逐 unit 解析定义时
    //   `industries.putIfAbsent(...)`；读者 = "逐 unit 落 relation"那一段的存在性守卫与取模板。改本表时
    //   必须同时核它的读者（B5 曾删掉旧填表循环而留下读空表的守卫 ⇒ 关系表被静默丢掉）。
    Map<IndustryId, IndustryDefinitionView> industries = new LinkedHashMap<>();
    // ★★ R3B.2：第 14 个组件（生产单元）—— 新载荷显式给 units[]，旧载荷由 industry 的旧实例字段合成。
    Map<ProductionUnitId, ProductionProcess> units = new LinkedHashMap<>();
    // ★★ H0：家户行是**entry 级**的（键 = (格, 居住类型, 阶层)），不再嵌在产业节点里 —— 见类注 ①。
    Map<HouseholdId, HouseholdEconomy> householdEconomies = new LinkedHashMap<>();
    // ★ R2 的两张新表：**逐格**声明（格是命令目标与权限的粒度：一条命令动的是这些格）。
    Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments = new LinkedHashMap<>();
    // ★ T2 的第 8 个组件：R3B.2 起键 = ProductionUnitId（关系挂在 unit 上）。
    Map<ProductionUnitId, ProductionRules> relations = new LinkedHashMap<>();
    // ★ H4 的第 9 个组件：顶层 `markets`（键 = 格串），见类注的第五处形状变化。
    Map<MarketZoneId, MarketTable> markets = markets(payload, entries);
    // ★★ P1：E1/E2 的可选地基键（缺键 ⇒ 空表；旧载荷逐值不变）。解析只做形状/词表，
    //   键身份、结构↔位置闭环、standing 家户/位置引用等由 EconomyData 构造期守卫 fail-closed。
    //   ★★★ B5（§13.15.2 裁定十八）：顶层可选 `modes` 键**退役**（原 {@code parseModes} 已删）——
    //     生产方式定义的唯一权威在 production，economy 侧不再有定义副本、也不再有它的播种写腿
    //     （定义经 `production.Seed` 的 `modes[]` 落账）。
    Map<ClassStructureId, ClassStructure> classStructures = parseClassStructures(payload);
    Map<ClassPositionId, ProductionRole> classPositions = parseProductionRoles(payload);
    Map<HouseholdId, HouseholdClassMembership> classMemberships = parseClassMemberships(payload);
    Map<AssetRuleId, AssetRule> assetRules = parseAssetRules(payload);
    // ★★ E3 的第 23/24 个组件：顶层可选 `governments` / `moneyIssuances`（缺键 ⇒ 空表；旧载荷逐值不变）。
    Map<GovernmentId, Government> governments = governments(payload);
    Map<MoneyIssuanceId, MoneyIssuanceRecord> moneyIssuances =
        moneyIssuances(payload, at, governments);
    // ★ S1 的资产份额组件：可选的逐格声明；缺省 ⇒ 空表（"没有登记就没有份额" —— 不凭产能替谁发明权利，
    //   见 OwnershipStake 的类注）。★ P2-A A3：成员份额不再是经济状态组件（唯一权威 = Social 的 Household.members），
    //   载荷里即使带 `memberships` 键也不再解析（旧世界重建）。
    Map<AssetShareId, OwnershipStake> assetShares = new LinkedHashMap<>();
    Map<String, Long> ownershipStakeSequences = new LinkedHashMap<>();
    // ★★★ D-032 C2（D2 §5.5）：顶层 productionRosters 名册表。C2 只做 economy 侧接收/形状校验；
    //   角色/资格/份额 owner 的跨切片判据由 EconomyReferenceValidation#validateProductionRosters 在候选态判。
    Map<ProductionUnitId, ProductionRoster> productionRosters =
        productionRosters(payload, definitions, productionInstances);
    // ★★★ D-032 P4c3：U20 工资锚是唯一工资报价数组；空/缺省 = 空表。
    Map<WageAnchorKey, ProductionWageAnchor> wageAnchors =
        productionWageAnchors(payload, definitions, knownMarketZones);
    for (JsonNode entry : entries) {
      requireEntryObject(entry);
      int q = requireInt(entry, "q");
      int r = requireInt(entry, "r");
      HexCoord hex = new HexCoord(q, r);
      // ★★ **H0：配额先解析**（下面推默认关系的居住类型要用它 —— 那是"这批人住哪种居住类型"的唯一来源）。
      List<EconomyHouseholdPayloads.RawAllocation> entryLaborCommitments = new ArrayList<>();
      for (JsonNode node : optionalArray(entry, "allocations")) {
        entryLaborCommitments.add(allocation(node));
      }
      // ★★★ B5（§13.15.3 裁定十九）：原 `entry.industries[]`（产业定义节点）**已退役** —— economy 载荷不再
      //   自带产业定义（旧形状的 operator/capacity/progress/cycle* 合成路径一并删除）。本键只剩"该格的方案
      //   （产业/unit）落点"这一件事：每个节点仍是 unit 节点的宿主与 `relation` 的来源，
      //   产业模板一律从 production 当刻定义权威取（`definitions`）。
      List<JsonNode> planNodes = new ArrayList<>();
      for (JsonNode node : requireArray(entry, "plans")) {
        // ★★ R0：**entry 格 ↔ 节点自带产业 id 的格必须一致**（见 requireIndustryHexMatchesEntry）。
        requireIndustryHexMatchesEntry(hex, IndustryId.parse(requireText(node, "id")));
        planNodes.add(node);
      }
      // ★★ R3B.1：该格的实物资产份额（可选）。新键 = assetShares、旧键 = useRights；先解析，
      //   因为旧载荷的 capacity→整额 OWNED 物化要判"这一产业是不是已经有显式份额"。
      boolean hasNewShares = entry.has("assetShares");
      boolean hasLegacyShares = entry.has("useRights");
      if (hasNewShares && hasLegacyShares) {
        throw new IllegalArgumentException(
            "同一 entry 不得同时给 assetShares 与 useRights（R3B.1 起新键是 assetShares，旧键按一对一代际翻译）: " + entry);
      }
      for (JsonNode node : optionalArray(entry, "assetShares")) {
        addOwnershipStake(assetShares, ownershipStakeSequences, node, false);
      }
      for (JsonNode node : optionalArray(entry, "useRights")) {
        addOwnershipStake(assetShares, ownershipStakeSequences, node, true);
      }
      Map<String, JsonNode> unitNodesById = new LinkedHashMap<>();
      for (JsonNode node : optionalArray(entry, "units")) {
        JsonNode idNode = node.get("id");
        String idText = idNode != null && idNode.isTextual() ? idNode.asText() : null;
        if (idText == null) {
          // ★ id 缺省 = ProductionUnitId.idOf(industry, operator)（确定性工厂是契约层的唯一拼写点）。
          IndustryId industryId = IndustryId.parse(requireText(node, "industry"));
          // ★★★ B5（§13.15.3 裁定十九）：定义从 production 当刻权威取（载荷不再自带定义）。
          IndustryDefinitionView template =
              definitions
                  .industry(industryId)
                  .orElseThrow(
                      () ->
                          new IllegalArgumentException(
                              "unit 指名的产业不在 production 当刻定义权威里（economy 载荷不再自带产业定义）: "
                                  + node
                                  + " industry="
                                  + industryId));
          JsonNode operatorNode = optionalObject(node, "operator");
          ActorRef operator =
              operatorNode == null
                  ? RegimeOperators.defaultOperator(template.regime(), industryId)
                  : actorRef(operatorNode);
          idText = ProductionUnitId.idOf(industryId, operator).value();
        }
        if (unitNodesById.putIfAbsent(idText, node) != null) {
          throw new IllegalArgumentException("同一份载荷里 unit id 重复: " + idText);
        }
      }
      // ★★★ B5（§13.15.3 裁定十九）：原"旧形状 industry 节点（带 operator/capacity/progressDays/
      //   cycleLaborMilli/cycleInputUsedMilli）⇒ 合成默认 unit + 整额 OWNED 份额"这一整段**已删除**
      //   —— economy 载荷不再自带产业定义，旧形状路径一并退役（Alpha 不兼容旧档：旧档 fail-closed，
      //   不许留兼容位）。实例本体一律由 `production.Seed` 的 `instances[]` 落账。
      // 逐 unit 落表 + 解析它自己的 relation（unit 节点可选给 relation；旧载荷/模板在 industry 节点上）。
      Map<ProductionUnitId, JsonNode> relationNodesByUnit = new LinkedHashMap<>();
      List<ProductionUnitId> entryUnitIds = new ArrayList<>();
      for (Map.Entry<String, JsonNode> unitEntry : unitNodesById.entrySet()) {
        JsonNode unitNode = unitEntry.getValue();
        IndustryId industryId = IndustryId.parse(requireText(unitNode, "industry"));
        // ★★★ B5（§13.15.3 裁定十九）：产业模板从 **production 当刻定义权威**取（载荷不再自带定义）。
        IndustryDefinitionView industry =
            definitions
                .industry(industryId)
                .orElseThrow(
                    () ->
                        new IllegalArgumentException(
                            "unit 指名的产业不在 production 当刻定义权威里（economy 载荷不再自带产业定义）: unit="
                                + unitEntry.getKey()
                                + " industry="
                                + industryId));
        // ★★★ B9（§14.18.2 裁定三十六 / §14.18.3 纪律）：本行是 `industries` 的**唯一写入者**。B5 退役载荷
        //   `entry.industries[]` 时删掉了它的填表循环，`industries` 一度"只 new 从不 put" ⇒ `:511` 的存在性
        //   守卫恒假、逐 unit 落 relation 整段成死代码（I14 回归）。写入内容 = production 当刻定义权威里
        //   **已具名核过存在性**的那一条；`:511` 的守卫保留（它仍是真实的 unit→方案存在性核对）。
        industries.putIfAbsent(industry.id(), industry);
        ActorRef operator =
            optionalObject(unitNode, "operator") == null
                ? RegimeOperators.defaultOperator(industry.regime(), industryId)
                : actorRef(optionalObject(unitNode, "operator"));
        ProductionProcess unit = unit(unitNode, industry, operator);
        if (units.putIfAbsent(unit.id(), unit) != null) {
          throw new IllegalArgumentException("同一份载荷里 unit 重复: " + unit.id());
        }
        // ★★ B2b：载荷 unit 必须与 production 的实例**逐值一致**（instance 本体在 production）。
        ProductionProcess authoritative = productionInstances.get(unit.id());
        if (authoritative == null) {
          throw new IllegalArgumentException(
              "载荷声明了 production 里不存在的实例（实例的唯一权威在 production；请把它放进 production.Seed 的"
                  + " instances[]）: unit="
                  + unit.id().value());
        }
        if (!authoritative.plan().equals(unit.industry())
            || !authoritative.operator().equals(unit.operator())
            || !authoritative.modeKey().equals(unit.modeKey())) {
          throw new IllegalArgumentException(
              "载荷 unit 与 production 实例不一致（同一件事两处拼写）: unit="
                  + unit.id().value()
                  + " 载荷 plan/operator/modeKey="
                  + unit.industry().value()
                  + "/"
                  + unit.operator()
                  + "/"
                  + unit.modeKey()
                  + " production="
                  + authoritative.plan().value()
                  + "/"
                  + authoritative.operator()
                  + "/"
                  + authoritative.modeKey());
        }
        JsonNode relationNode = optionalObject(unitNode, "relation");
        if (relationNode == null) {
          // ★★ B5（裁定十九）：`relation` 的宿主仍是该格的方案节点（`plans[]`；trade 的关系就发在这里）。
          JsonNode planNode = planNodeOf(planNodes, industryId);
          if (planNode != null) {
            relationNode = optionalObject(planNode, "relation");
          }
        }
        relationNodesByUnit.put(
            unit.id(),
            relationNode == null ? EconomyPayloadSupport.mapper().nullNode() : relationNode);
        entryUnitIds.add(unit.id());
      }
      // ★ 配额 activity → unit：新载荷必须直接给 unit id；旧载荷按 actor.id() 当产业串找唯一 unit 改写。
      Map<String, List<ProductionUnitId>> unitsByIndustry = new LinkedHashMap<>();
      for (ProductionProcess unit : units.values()) {
        unitsByIndustry
            .computeIfAbsent(unit.industry().value(), ignored -> new ArrayList<>())
            .add(unit.id());
      }
      List<HouseholdLaborCommitment> canonicalLaborCommitments =
          new ArrayList<>(entryLaborCommitments.size());
      for (EconomyHouseholdPayloads.RawAllocation rawAllocation : entryLaborCommitments) {
        ProductionRoleId resolvedRole =
            resolveProductionRole(
                rawAllocation, productionInstances, productionRosters, definitions);
        boolean productionUnit =
            productionInstances.containsKey(new ProductionUnitId(rawAllocation.activity()));
        canonicalLaborCommitments.add(
            canonicalAllocationActivity(
                rawAllocation.withRole(resolvedRole, productionUnit),
                unitsByIndustry,
                units,
                entry));
      }
      // ★ 默认关系的居住类型来源 = 供给该 unit 的批次前缀（ResidenceKind.ofLot）。
      Map<ProductionUnitId, Set<ResidenceKind>> residencesByUnit = new LinkedHashMap<>();
      for (HouseholdLaborCommitment laborCommitment : canonicalLaborCommitments) {
        List<ProductionUnitId> resolved =
            resolveAllocationUnits(laborCommitment, unitsByIndustry, units.keySet());
        for (ProductionUnitId unitId : resolved) {
          residencesByUnit
              .computeIfAbsent(unitId, ignored -> new LinkedHashSet<>())
              .add(ResidenceKind.ofLot(laborCommitment.group()));
        }
      }
      for (ProductionUnitId unitId : entryUnitIds) {
        ProductionProcess unit = units.get(unitId);
        if (unit == null || !industries.containsKey(unit.industry())) {
          continue;
        }
        IndustryDefinitionView industry = industries.get(unit.industry());
        JsonNode relationNode = relationNodesByUnit.get(unitId);
        ProductionRules relation =
            relationNode == null || relationNode.isNull()
                ? RegimeRelations.defaultRelation(
                    industry.regime(),
                    unitId,
                    industry.id(),
                    unit.operator(),
                    residencesByUnit.getOrDefault(unitId, Set.of()))
                : relation(
                    relationNode,
                    industry,
                    unitId,
                    unit.operator(),
                    residencesByUnit.getOrDefault(unitId, Set.of()));
        if (relations.putIfAbsent(unitId, relation) != null) {
          throw new IllegalArgumentException("同一份载荷里 unit 关系重复: " + unitId);
        }
      }
      // ★★ **H0：该格的家户行（entry 级）** —— 每行显式带 {@code residence}，键 = (格, 居住类型, 阶层)。
      for (JsonNode row : optionalArray(entry, "classes")) {
        HouseholdEconomy householdEconomy = householdEconomy(hex, row);
        if (householdEconomies.putIfAbsent(householdEconomy.id(), householdEconomy) != null) {
          throw new IllegalArgumentException("同一份载荷里家户行重复: " + householdEconomy.id());
        }
      }
      // ★ R2：该格各批次的劳动供给（可支配劳动的上限）—— 缺省 ⇒ 空表（与 classes 同款）。
      for (HouseholdLaborCommitment laborCommitment : canonicalLaborCommitments) {
        if (laborCommitments.putIfAbsent(laborCommitment.id(), laborCommitment) != null) {
          throw new IllegalArgumentException("同一份载荷里劳动分配重复: " + laborCommitment.id());
        }
      }
    }
    EconomyMeta meta =
        new EconomyMeta(mapId, at.tick(), OptionalLong.empty(), rulesVersion, Optional.empty());
    // ★★ E4c：非空初始债务可以解析（结构/条款/引用校验交给 EconomyData 构造期守卫；不在载荷层重复实现）。
    //   ★ 初始债务的"配套库存/货币/权利"不在这里搬：economy 不自动改 actor 账 —— app 协调器必须在 actor.Seed
    //     里给 debtor/creditor 备好相应余额（见 EconomyPayloads 类注与 EconomySeeder 的说明）。
    Map<DebtContractId, DebtContract> debtContracts = debtContracts(payload);
    // ★★ E5a：可选初始 hex 危机信号（缺键 ⇒ 空表；结构在本层判，引用完整性走 EconomyData 构造期守卫）。
    Map<CrisisSignalId, HexCrisisSignal> crisisSignals = crisisSignals(payload);
    // ★★ E6a：可选初始模式变迁 / 阶层保留份额（缺键 ⇒ 空表；id 确定性派生、分组 Σ=1000 与引用完整性走构造期守卫）。
    Map<ModeTransitionId, ModeTransition> modeTransitions = modeTransitions(payload);
    Map<ClassShareId, ClassShare> classShares = classShares(payload);
    // ★★★ D-032 B4/B5/B9（§13.14.2 裁定十五 / §13.15.3 裁定十九 / §14.18.2 裁定三十六）：载荷**不再读**
    //   `entry.industries[]`（B5 退役该键），定义权威唯一在 production。⇒ 上面的 `industries` 是**本方法内的
    //   局部索引**：逐个 unit 从 `definitions.industry(id)` 取到**已具名核过存在性**的那条后 `putIfAbsent` 填入
    //   （B9 补写者），只服务"逐 unit 落 relation"那一段，**不是**第二份持久权威、也不进 `EconomyData`。
    //   跨表引用（unit→方案）另由落库前的 state-aware 守卫读 production 当刻定义判（EconomyReferenceValidation）。
    return new EconomyData(
        Optional.of(meta),
        householdEconomies,
        debtContracts,
        Map.of(),
        laborCommitments,
        relations,
        markets,
        // ★ M2.4：创世载荷没有在途（播种出来的世界货物都在账上；在途由市场发运产生）。
        Map.of(),
        assetShares,
        // ★ S3 预留的第 13 个组件：创世载荷暂不声明经营者状态（空表 = 尚未登记任何状态机状态）。
        Map.of(),
        // ★★ R4-E2：创世载荷不声明 GM 需求（空表 = 由 economy.AddDemand 注入）。
        //   ★★ B2c（裁定八）：候选预设表已从 EconomyData 删除 ⇒ 原来那一格（candidates）随之删除。
        Map.of(),
        // ★★ P1：E1 的三张地基表从载荷可选键解析（缺键 ⇒ 空表 = 旧路径继续跑）。
        //   ★★★ B5（裁定十八）：生产方式定义 `modes` 那一格**已删** —— 定义权威唯一在 production。
        classStructures,
        classPositions,
        classMemberships,
        // ★★ E2：生产组织**不由创世载荷声明** —— 必须留给 EconomyEnterpriseSettlement 的自动组织阶段
        //   在 modes 非空后生成（手种 = 第二真相）；生产资料规则则由 P1 的 assetRules 键显式给出。
        Map.of(),
        assetRules,
        // ★★ E3：政府 / 货币发行审计（创世载荷可选声明；缺键 ⇒ 空表 = 零登记、无发行）。
        governments,
        moneyIssuances,
        // ★★ E5a：危机信号（可选；键身份/引用完整性由 EconomyData 构造期守卫判）。
        crisisSignals,
        // ★★ E6a：模式变迁 / 阶层保留份额（可选；id 派生、分组 Σ=1000 与引用完整性由构造期守卫判）。
        modeTransitions,
        classShares,
        // ★★ B2b：Z1 的周期规则/产出覆盖/效率表 / A1 的词表 / B2 的市场区 / 选项 A 的引用表 /
        //   商品基础运费 / R1 的政府授权 / W1 的市场轮统计 —— 创世载荷都不声明它们（空表 = 该维"还没有"），
        //   构造期各自归一到中性/派生形态（与改动前逐值相同）。
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        productionRosters,
        Map.of(),
        // ★★★ D-032 D4-Ib1：创世载荷不声明资产占用/库存预留（空表 = 尚未授予/预留）。
        Map.of(),
        Map.of(),
        // ★★★ D-032 P4c3：U20 工资锚（唯一报价权威；空/缺省 = 空表）。
        wageAnchors);
  }

  static ProductionRole productionRole(JsonNode node, String defaultModeId) {
    return EconomyPolicyPayloads.productionRole(node, defaultModeId);
  }

  static RentRule rentRule(JsonNode node) {
    return EconomyPolicyPayloads.rentRule(node);
  }

  static TransferRule transferRule(JsonNode node) {
    return EconomyPolicyPayloads.transferRule(node);
  }

  static Map<ClassPositionId, Long> productionRoleShareMap(JsonNode object, String field) {
    return EconomyPolicyPayloads.productionRoleShareMap(object, field);
  }

  static Map<String, String> stringMap(JsonNode object, String field) {
    return EconomyPolicyPayloads.stringMap(object, field);
  }

  static Payee recipient(JsonNode node, String what) {
    return EconomyPolicyPayloads.recipient(node, what);
  }

  static CompensationRule compensationRule(JsonNode node) {
    return EconomyPolicyPayloads.compensationRule(node);
  }

  static IndustryDefinitionView parseIndustryNode(JsonNode node) {
    return EconomyProductionPayloads.parseIndustryNode(node);
  }
}
