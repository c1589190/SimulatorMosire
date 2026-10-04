package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.debt.DefaultRemedy;
import io.mosire.simos.economy.api.debt.InterestTiming;
import io.mosire.simos.economy.api.debt.MonetaryConversion;
import io.mosire.simos.economy.api.debt.RepaymentRule;
import io.mosire.simos.economy.api.id.AssetRuleId;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ClassShareId;
import io.mosire.simos.economy.api.id.ClassStructureId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CrisisSignalId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MembershipId;
import io.mosire.simos.economy.api.id.ModeTransitionId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import io.mosire.simos.economy.api.id.PledgeId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.api.money.MoneyIssuanceKind;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.api.relation.Basis;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.api.relation.Weight;
import io.mosire.simos.economy.classfirst.ClassFirstState;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.AssetRule;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassPosition;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassShare;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.ClassStanding;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.LiquidationPolicy;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.Membership;
import io.mosire.simos.economy.model.MerchantFirm;
import io.mosire.simos.economy.model.MerchantPolicy;
import io.mosire.simos.economy.model.ModeTransition;
import io.mosire.simos.economy.model.Pledge;
import io.mosire.simos.economy.model.ProductionMode;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.economy.model.RentRule;
import io.mosire.simos.economy.model.TransferRule;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.util.json.SimosObjectMapper;
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
 *   {"q":0,"r":0,"industries":[
 *     {"id":"farm@0_0","name":"农业","regime":"feudal",
 *      "operator":{"kind":"ESTATE","id":"farm@0_0"},"cycleDays":120,"progressDays":0,
 *      "capacityPerUnit":{"LAND":1000},"capacity":{"LAND":3100000},"laborPerUnit":143,
 *      "dailyInputPerUnit":{},"dailyLaborPerUnit":0,"outputPerUnit":{"grain":67,"fiber":12},
 *      "cycleInputPerUnit":{"LAND":{"grain":8000}},"cycleInputUsedMilli":{},
 *      "cycleLaborMilli":0,
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
 * <p>★★ <b>R3B.2 的新形状（上面的样例是旧形状留痕）</b>：{@code industries[]} 只留模板（无 {@code operator/capacity/
 * progressDays/cycleLaborMilli/cycleInputUsedMilli}）；{@code units[]} 显式给生产单元（{@code id?/industry/
 * operator?/modeKey?/progressDays?/cycleLaborMilli?/cycleInputUsedMilli?}）；{@code
 * allocations[].activity} = unit id、{@code actor} = unit.operator。★ 上面的旧形状**仍可读**：industry
 * 的旧实例字段先合成一条默认 unit + 整额 OWNED 份额，再构造模板（见 {@code EconomyCodec} 与 {@code industrySpec}）。
 *
 * <p>★★ <b>H0（2026-09-27，裁定 K2/K3 + R-N1-A）的三处形状变化 —— 三条都是"编译绿、运行红"的坑，逐条写清</b>：
 *
 * <ol>
 *   <li>★★ <b>{@code classes} 从"产业节点内"搬到 <b>entry 级</b>，且每行多一个 {@code residence}</b>：行 = 家户 =
 *       {@code (格, 居住类型, 阶层)}（K2）⇒ 它<b>不再属于某个产业</b>（农村家户同时供给农业与家庭纺织）。{@code q}/{@code r} 取自
 *       entry；{@code residence} 走 {@link ResidenceKind#parse}（词表外即抛）。★ <b>为什么必须显式给</b>：改前"行属于哪个产业"
 *       隐含了居住类型（{@code farm}/{@code weave} = 农村、{@code craft} = 城镇），H0 之后那层隐含没有了 —— 缺键就<b>抛</b>
 *       （不许按产业种类猜：{@code weave} 的家户就是农村那四行，猜出来的第二份约定会与配额表漂开）；
 *   <li>★★ <b>{@code meansOfProduction} 键<b>不再接受</b></b>（K3）：产能搬到 {@code Industry.capacity}。★ 留着不读
 *       = "看起来在记、其实被静默丢掉"（真档会变成"全格没有产能 ⇒ 绝收"而无人察觉）⇒ <b>给了即抛</b>，消息点名新键；
 *   <li>★★ <b>{@code Industry} 多一个 {@code capacity}（本格该产业的产能总量）</b>：缺键 ⇒ 空表 ⇒ 规模 0（= 本格没有产能，
 *       真档里沙漠格的 {@code LAND = 0}、人口不足一厂的格 {@code TOOL = 0} 正是这一形态）。★ <b>逐值允许 0</b> （与 {@code
 *       capacityPerUnit} 的"必须 > 0"性质不同）。
 * </ol>
 *
 * <p>★★ <b>H1（2026-09-27，裁定 D3-C/K1）的第四处形状变化：{@code classes[].goods} 键<b>不再接受</b></b> —— 家户的商品库存住在
 * actor 切片的 {@code GoodsAccount}（键 {@code (HouseholdActors.of(cohort), cohort.hex())}）， economy
 * 侧只在**会话工作副本**（{@code 旧日推进器（R3a 已删除）} 的入参）里读它。★ 与 K3 的 {@code meansOfProduction} 同款理由：
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
 *       {@code Industry.recipe()} 的四个分量；
 *   <li>两个投入表的**值侧带上商品维度**：{@code "cycleInputPerUnit":{"LAND":8000}} ⇒ {@code
 *       "cycleInputPerUnit":{"LAND":{"grain":8000}}}（"消耗 IRON"这种话原来表达不了）； {@code
 *       "cycleSeedUsedMilli":0} ⇒ {@code "cycleInputUsedMilli":{}}（按商品的累加器）。
 * </ul>
 *
 * <p>★★ <b>H4 的第五处形状变化：顶层的 {@code markets}（第 9 个组件的载荷键）</b> —— <b>可选键</b>，形状 {@code
 * {"<q>_<r>":{"numeraire":"<币种>","prices":{"<商品>":<单价>}}, …}}：
 *
 * <ul>
 *   <li>★ <b>为什么是顶层、键 = 格</b>：{@code EconomyData.markets} 就是 {@code Map<HexCoord, Market>} ⇒
 *       载荷与状态**同形**（一份数据一处拼写）。★ 键走 {@link HexCoord#parse}（{@code "0_0"} 是格串的唯一拼写点， 调用方不自己拼）；★ 缺键 /
 *       缺格 = 该格没有市场（合法状态：真档创世只给有经济 entry 的格发市场）；
 *   <li>★★ <b>每一格市场必须在 {@code entries} 里出现</b>（fail-closed）：命令的作用域是 entry 的格集 （{@code
 *       EconomySeedHandler.targetPaths} 报的就是它）—— 一个落在 entry 之外的格会在<b>权限/目标</b>面上
 *       成为"没人申报的写"，故此处当场拒（不静默种下一个谁也管不到的格）；
 *   <li>★ <b>{@code numeraire} 必填</b>（每格恰一种计价货币，裁定 M1-A）；{@code prices} 可缺（⇒ 空表 = 这一格什么 都还没定价）。★
 *       单价**逐值 &gt; 0**、量纲 = <b>毫计价货币 / 商品单位</b>（1 商品单位 = 1000 毫单位）—— 两条守卫都在 {@link Market}
 *       的构造期与类注里，本层只做形状（同"数值语义交给领域类型"的既有口径）；
 *   <li>★ <b>本批没有"设价"命令</b>：价格只能由创世载荷给（GM 调价要等参数目录/命令落地，见 {@code EconomyData.withMarkets}）。
 * </ul>
 *
 * <p>★ **旧档兼容不在本轮范围**（spec §十.4 的裁定："旧档：重建也没关系"）：随包的 {@code worlds/v17levant.json} **不含 economy
 * 切片**（只有 map/social/unit），故这两处形状变化不影响它能否打开。
 *
 * <p>★ **S1 阶段 3：{@code operator} 是可选键**（上面的样例里就带着它）。**缺键 ⇒ 按 {@code regime} 推导** （{@link
 * RegimeOperators#defaultOperator}，裁定 R1）—— 这是**载荷边缘**的推导点：{@code Industry} 收了 {@code null}
 * 是**抛**，不是补（裁定 D1）。★ 与 {@code allocations[].actor} 的口径**刻意不同**：那里缺键是**拒**、 这里是**推导**，不许合并。
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
 *       <b>缺省不在本层另写一遍</b>：交给 {@code ProductionRelation} 的构造期缺省（旧档兼容的那一处边缘）； ★
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
 * <p>★ <b>E4c：创世载荷可解析非空初始债务/质押</b>：顶层 {@code debtContracts} / {@code pledges} 键可缺席（= 空表），
 * 给了就按对象数组解析（结构与条款在载荷层判形状，id 派生/引用完整性在 {@link EconomyData} 构造期判）。 ★★ <b>配套责任</b>：economy <b>不自动搬
 * actor 库存</b>；声明初始债务的 seed 必须在 app 的 actor.Seed 协调器里给 debtor/creditor
 * 备好对应库存/货币/权利，否则就是凭空种出的无对价债权名册。 旧类行 {@code debts} 键仍可读（它只是派生引用；进入构造期后由合同表权威重建）。
 *
 * <p>★ <b>E5a：创世载荷可解析可选初始清算政策/危机信号</b>：顶层 {@code liquidationPolicies} / {@code crisisSignals}
 * 键可缺席（= 空表）；结构与取值范围在载荷层判，键身份/规则与家户引用完整性在 {@link EconomyData} 构造期判。两者为空时旧载荷逐值不变；E5a 不产生任何信号。
 *
 * <p>★ <b>P10.1：创世载荷可解析可选 {@code merchantFirms} 数组</b>（缺键 ⇒ 空表）：每项与 {@link MerchantFirm}
 * 字段对齐，{@code tier}/格走现有词表/格串解析器，数值范围交给 {@code MerchantFirm} 构造期守卫。既有 30 个顶层键的语义一字不动。
 */
final class EconomyPayloads {

  /** 本类唯一的一台 mapper（共享基座出厂配置；{@code AllocationRule} 的多态注解跟着类型走，不依赖 mixin）。 */
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private EconomyPayloads() {}

  /** 解析载荷文本：非 JSON、或不是 JSON 对象 ⇒ 抛。 */
  static JsonNode parse(String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    JsonNode payload;
    try {
      payload = MAPPER.readTree(payloadJson);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("payload 不是合法 JSON: " + e.getOriginalMessage(), e);
    }
    if (payload == null || !payload.isObject()) {
      throw new IllegalArgumentException("payload 必须是 JSON 对象: " + payloadJson);
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
   */
  static EconomyData toData(JsonNode payload, SimosTimestamp at) {
    Objects.requireNonNull(at, "at");
    // ★★ E4c：`debtContracts` / `pledges` 从"只接受空数组"放开为可解析；结构与引用完整性走 EconomyData 构造期守卫
    //   （id 四元组派生、debtor/creditor 行存在、ClassRow.debts 引用存在、质押引用与 Σ活跃质押上界）。
    String mapId = requireText(payload, "mapId");
    String rulesVersion = requireText(payload, "rulesVersion");
    JsonNode entries = requireArray(payload, "entries");
    if (entries.isEmpty()) {
      throw new IllegalArgumentException("entries 不得为空");
    }
    Map<IndustryId, Industry> industries = new LinkedHashMap<>();
    // ★★ R3B.2：第 14 个组件（生产单元）—— 新载荷显式给 units[]，旧载荷由 industry 的旧实例字段合成。
    Map<ProductionUnitId, ProductionUnit> units = new LinkedHashMap<>();
    // ★★ H0：家户行是**entry 级**的（键 = (格, 居住类型, 阶层)），不再嵌在产业节点里 —— 见类注 ①。
    Map<HouseholdId, ClassRow> classes = new LinkedHashMap<>();
    // ★ R2 的两张新表：**逐格**声明（格是命令目标与权限的粒度：一条命令动的是这些格）。
    Map<PeopleLotId, LaborSupply> laborSupply = new LinkedHashMap<>();
    Map<LaborAllocationId, LaborAllocation> allocations = new LinkedHashMap<>();
    // ★ T2 的第 8 个组件：R3B.2 起键 = ProductionUnitId（关系挂在 unit 上）。
    Map<ProductionUnitId, ProductionRelation> relations = new LinkedHashMap<>();
    // ★ H4 的第 9 个组件：顶层 `markets`（键 = 格串），见类注的第五处形状变化。
    Map<HexCoord, Market> markets = markets(payload, entries);
    // ★★ P1：E1/E2 的六个可选地基键（缺键 ⇒ 空表；旧载荷逐值不变）。解析只做形状/词表，
    //   键身份、结构↔位置闭环、standing 家户/位置引用等由 EconomyData 构造期守卫 fail-closed。
    Map<ProductionModeId, ProductionMode> modes = parseModes(payload);
    Map<ClassStructureId, ClassStructure> classStructures = parseClassStructures(payload);
    Map<ClassPositionId, ClassPosition> classPositions = parseClassPositions(payload);
    Map<HouseholdId, ClassStanding> classStandings = parseClassStandings(payload);
    Map<AssetRuleId, AssetRule> assetRules = parseAssetRules(payload);
    // ★★ E3 的第 23/24 个组件：顶层可选 `governments` / `moneyIssuances`（缺键 ⇒ 空表；旧载荷逐值不变）。
    Map<GovernmentId, Government> governments = governments(payload);
    Map<MoneyIssuanceId, MoneyIssuanceRecord> moneyIssuances =
        moneyIssuances(payload, at, governments);
    // ★ S1 的两个新组件：可选的逐格声明；缺省 ⇒ 空表（由 EconomyData 的迁移器补齐成员份额；
    //   资产份额则是"没有登记就没有份额" —— 不凭产能替谁发明权利，见 AssetShare 的类注）。
    Map<MembershipId, Membership> memberships = new LinkedHashMap<>();
    Map<AssetShareId, AssetShare> assetShares = new LinkedHashMap<>();
    Map<String, Long> assetShareSequences = new LinkedHashMap<>();
    for (JsonNode entry : entries) {
      requireEntryObject(entry);
      int q = requireInt(entry, "q");
      int r = requireInt(entry, "r");
      HexCoord hex = new HexCoord(q, r);
      // ★★ **H0：配额先解析**（下面推默认关系的居住类型要用它 —— 那是"这批人住哪种居住类型"的唯一来源）。
      List<LaborAllocation> entryAllocations = new ArrayList<>();
      for (JsonNode node : optionalArray(entry, "allocations")) {
        entryAllocations.add(allocation(node));
      }
      // ★★ R3B.2：产业模板 + 旧实例字段（旧载荷的 operator/capacity/progress/cycle）分两路读。
      List<IndustrySpec> specs = new ArrayList<>();
      for (JsonNode node : requireArray(entry, "industries")) {
        IndustrySpec spec = industrySpec(node);
        // ★★ R0：**entry 格 ↔ industry.id 格必须一致**（见 requireIndustryHexMatchesEntry）。
        requireIndustryHexMatchesEntry(hex, spec.template().id());
        if (industries.putIfAbsent(spec.template().id(), spec.template()) != null) {
          throw new IllegalArgumentException("同一份载荷里产业 id 重复: " + spec.template().id());
        }
        specs.add(spec);
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
        addAssetShare(assetShares, assetShareSequences, node, false);
      }
      for (JsonNode node : optionalArray(entry, "useRights")) {
        addAssetShare(assetShares, assetShareSequences, node, true);
      }
      // 旧载荷：旧 Industry 的实例字段（operator/capacity/progress/cycle*）⇒ 合成默认 unit + 整额 OWNED 份额。
      Map<String, JsonNode> unitNodesById = new LinkedHashMap<>();
      for (JsonNode node : optionalArray(entry, "units")) {
        JsonNode idNode = node.get("id");
        String idText = idNode != null && idNode.isTextual() ? idNode.asText() : null;
        if (idText == null) {
          // ★ id 缺省 = ProductionUnitId.idOf(industry, operator)（确定性工厂是契约层的唯一拼写点）。
          IndustryId industryId = IndustryId.parse(requireText(node, "industry"));
          Industry template = industries.get(industryId);
          if (template == null) {
            throw new IllegalArgumentException(
                "unit 指名的产业不存在（同一份载荷内）: " + node + " industry=" + industryId);
          }
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
      for (IndustrySpec spec : specs) {
        if (!spec.legacy()) {
          continue;
        }
        ActorRef operator = spec.legacyOperator();
        ProductionUnitId unitId = ProductionUnitId.idOf(spec.template().id(), operator);
        if (unitNodesById.containsKey(unitId.value())) {
          throw new IllegalArgumentException(
              "旧形状 industry（带 operator/capacity/progress）与显式 units[] 同时给同一 (产业, 经营者) ⇒ "
                  + "同一件事两处拼写，拒绝："
                  + unitId);
        }
        // 只有"这一产业已有实物份额"时才建 unit（旧档 capacity 全 0 时没有份额 ⇒ 旧行为规模恒 0，不造假 unit）。
        if (!hasShareForIndustry(assetShares, spec.template().id())) {
          synthesizeOwnedShares(
              assetShares, assetShareSequences, spec.template().id(), operator, spec.capacity());
        }
        if (hasShareForIndustry(assetShares, spec.template().id())) {
          JsonNode operatorNode =
              MAPPER
                  .createObjectNode()
                  .put("kind", operator.kind().name())
                  .put("id", operator.id());
          ObjectNode unitNode = MAPPER.createObjectNode();
          unitNode.put("id", unitId.value());
          unitNode.put("industry", spec.template().id().value());
          unitNode.set("operator", operatorNode);
          unitNode.put("modeKey", spec.template().id().value());
          unitNode.put("progressDays", spec.progressDays());
          unitNode.put("cycleLaborMilli", spec.cycleLaborMilli());
          ObjectNode used = MAPPER.createObjectNode();
          for (Map.Entry<CommodityId, Long> usedEntry : spec.cycleInputUsedMilli().entrySet()) {
            used.put(usedEntry.getKey().value(), usedEntry.getValue());
          }
          unitNode.set("cycleInputUsedMilli", used);
          unitNodesById.put(unitId.value(), unitNode);
        }
      }
      // 逐 unit 落表 + 解析它自己的 relation（unit 节点可选给 relation；旧载荷/模板在 industry 节点上）。
      Map<ProductionUnitId, JsonNode> relationNodesByUnit = new LinkedHashMap<>();
      List<ProductionUnitId> entryUnitIds = new ArrayList<>();
      for (Map.Entry<String, JsonNode> unitEntry : unitNodesById.entrySet()) {
        JsonNode unitNode = unitEntry.getValue();
        IndustryId industryId = IndustryId.parse(requireText(unitNode, "industry"));
        Industry industry = industries.get(industryId);
        if (industry == null) {
          throw new IllegalArgumentException(
              "unit 指名的产业不存在（同一份载荷内）: unit=" + unitEntry.getKey() + " industry=" + industryId);
        }
        IndustrySpec spec = specOf(specs, industryId);
        ActorRef operator =
            optionalObject(unitNode, "operator") == null
                ? RegimeOperators.defaultOperator(industry.regime(), industryId)
                : actorRef(optionalObject(unitNode, "operator"));
        ProductionUnit unit = unit(unitNode, industry, operator);
        if (units.putIfAbsent(unit.id(), unit) != null) {
          throw new IllegalArgumentException("同一份载荷里 unit 重复: " + unit.id());
        }
        JsonNode relationNode = optionalObject(unitNode, "relation");
        if (relationNode == null && spec != null) {
          relationNode = optionalObject(spec.node(), "relation");
        }
        relationNodesByUnit.put(unit.id(), relationNode == null ? MAPPER.nullNode() : relationNode);
        entryUnitIds.add(unit.id());
      }
      // ★ 配额 activity → unit：新载荷必须直接给 unit id；旧载荷按 actor.id() 当产业串找唯一 unit 改写。
      Map<String, List<ProductionUnitId>> unitsByIndustry = new LinkedHashMap<>();
      for (ProductionUnit unit : units.values()) {
        unitsByIndustry
            .computeIfAbsent(unit.industry().value(), ignored -> new ArrayList<>())
            .add(unit.id());
      }
      List<LaborAllocation> canonicalAllocations = new ArrayList<>(entryAllocations.size());
      for (LaborAllocation allocation : entryAllocations) {
        canonicalAllocations.add(
            canonicalAllocationActivity(allocation, unitsByIndustry, units, entry));
      }
      // ★ 默认关系的居住类型来源 = 供给该 unit 的批次前缀（ResidenceKind.ofLot）。
      Map<ProductionUnitId, Set<ResidenceKind>> residencesByUnit = new LinkedHashMap<>();
      for (LaborAllocation allocation : canonicalAllocations) {
        List<ProductionUnitId> resolved =
            resolveAllocationUnits(allocation, unitsByIndustry, units.keySet());
        for (ProductionUnitId unitId : resolved) {
          residencesByUnit
              .computeIfAbsent(unitId, ignored -> new LinkedHashSet<>())
              .add(ResidenceKind.ofLot(allocation.group()));
        }
      }
      for (ProductionUnitId unitId : entryUnitIds) {
        ProductionUnit unit = units.get(unitId);
        if (unit == null || !industries.containsKey(unit.industry())) {
          continue;
        }
        Industry industry = industries.get(unit.industry());
        JsonNode relationNode = relationNodesByUnit.get(unitId);
        ProductionRelation relation =
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
        ClassRow classRow = classRow(hex, row);
        if (classes.putIfAbsent(classRow.id(), classRow) != null) {
          throw new IllegalArgumentException("同一份载荷里家户行重复: " + classRow.id());
        }
      }
      // ★ R2：该格各批次的劳动供给（可支配劳动的上限）—— 缺省 ⇒ 空表（与 classes 同款）。
      for (JsonNode node : optionalArray(entry, "laborSupply")) {
        LaborSupply supply = laborSupply(node);
        if (laborSupply.putIfAbsent(supply.group(), supply) != null) {
          throw new IllegalArgumentException("同一份载荷里劳动供给重复: " + supply.group());
        }
      }
      for (LaborAllocation allocation : canonicalAllocations) {
        if (allocations.putIfAbsent(allocation.id(), allocation) != null) {
          throw new IllegalArgumentException("同一份载荷里劳动分配重复: " + allocation.id());
        }
      }
      // ★ S1：该格的成员份额（可选；键 = (lot, household) 的确定性 id）。
      for (JsonNode node : optionalArray(entry, "memberships")) {
        PeopleLotId lot = PeopleLotId.parse(requireText(node, "lot"));
        HouseholdId household = HouseholdId.parse(requireText(node, "household"));
        Membership membership =
            new Membership(
                Membership.idOf(lot, household), lot, household, requireLong(node, "count"));
        if (memberships.putIfAbsent(membership.id(), membership) != null) {
          throw new IllegalArgumentException("同一份载荷里成员份额重复: " + membership.id());
        }
      }
    }
    EconomyMeta meta =
        new EconomyMeta(mapId, at.tick(), OptionalLong.empty(), rulesVersion, Optional.empty());
    // ★★ E4c：非空初始债务/质押可以解析（结构/条款/引用校验交给 EconomyData 构造期守卫；不在载荷层重复实现）。
    //   ★ 初始债务的"配套库存/货币/权利"不在这里搬：economy 不自动改 actor 账 —— app 协调器必须在 actor.Seed
    //     里给 debtor/creditor 备好相应余额（见 EconomyPayloads 类注与 EconomySeeder 的说明）。
    Map<DebtContractId, DebtContract> debtContracts = debtContracts(payload);
    Map<PledgeId, Pledge> pledges = pledges(payload);
    // ★★ E5a：可选初始清算政策 / hex 危机信号（缺键 ⇒ 空表；结构在本层判，引用完整性走 EconomyData 构造期守卫）。
    Map<AssetRuleId, LiquidationPolicy> liquidationPolicies = liquidationPolicies(payload);
    Map<CrisisSignalId, HexCrisisSignal> crisisSignals = crisisSignals(payload);
    // ★★ E6a：可选初始模式变迁 / 阶层保留份额（缺键 ⇒ 空表；id 确定性派生、分组 Σ=1000 与引用完整性走构造期守卫）。
    Map<ModeTransitionId, ModeTransition> modeTransitions = modeTransitions(payload);
    Map<ClassShareId, ClassShare> classShares = classShares(payload);
    // ★★ R1：顶层可选 classFirst 持久状态（缺键 ⇒ 空态；形状/引用完整性由 ClassFirstState 构造期与绑定层判）。
    ClassFirstState classFirst = classFirst(payload);
    // ★★ P10.1：顶层可选 merchantFirms 数组（缺键 ⇒ 空表；组织侧不由本载荷声明 ⇒ 构造期只判键身份，
    //   引用完整性留给组织侧真正提供时的下一次构造）。
    Map<ProductionOrganizationId, MerchantFirm> merchantFirms = parseMerchantFirms(payload);
    return new EconomyData(
        Optional.of(meta),
        industries,
        classes,
        debtContracts,
        Map.of(),
        laborSupply,
        allocations,
        relations,
        markets,
        // ★ M2.4：创世载荷没有在途（播种出来的世界货物都在账上；在途由市场发运产生）。
        Map.of(),
        memberships,
        assetShares,
        // ★ S3 预留的第 13 个组件：创世载荷暂不声明经营者状态（空表 = 尚未登记任何状态机状态）。
        Map.of(),
        units,
        // ★★ R4-E2：创世载荷不声明 GM 需求/候选预设（空表 = 由 economy.AddDemand/RegisterCandidate 注入）。
        Map.of(),
        Map.of(),
        // ★★ P1：E1 的四张地基表从载荷可选键解析（缺键 ⇒ 空表 = 旧路径继续跑）。
        modes,
        classStructures,
        classPositions,
        classStandings,
        // ★★ E2：生产组织**不由创世载荷声明** —— 必须留给 EconomyOrganizationSettlement 的自动组织阶段
        //   在 modes 非空后生成（手种 = 第二真相）；生产资料规则则由 P1 的 assetRules 键显式给出。
        Map.of(),
        assetRules,
        // ★★ E3：政府 / 货币发行审计（创世载荷可选声明；缺键 ⇒ 空表 = 零登记、无发行）。
        governments,
        moneyIssuances,
        // ★★ E4c：质押表（可选；引用/数量上界由 EconomyData 构造期守卫按"对侧已提供"分段判）。
        pledges,
        // ★★ E5a：清算政策 / 危机信号（可选；键身份/引用完整性由 EconomyData 构造期守卫判）。
        liquidationPolicies,
        crisisSignals,
        // ★★ E6a：模式变迁 / 阶层保留份额（可选；id 派生、分组 Σ=1000 与引用完整性由构造期守卫判）。
        modeTransitions,
        classShares,
        classFirst,
        merchantFirms);
  }

  /**
   * ★★ <b>顶层 {@code markets}：格 → 市场</b>（H4；可选键，见类注的第五处形状变化）。
   *
   * <pre>
   * "markets":{"0_0":{"numeraire":"silver","prices":{"grain":1,"cloth":5}}, "0_1":{…}}
   * </pre>
   *
   * <p>★ <b>三条 fail-closed</b>：键不是合法格串 ⇒ 抛（{@link HexCoord#parse}）；值不是对象 ⇒ 抛； ★★ <b>市场所在的格必须在
   * {@code entries} 里</b> ⇒ 否则抛（命令作用域是 entry 的格集，见类注）。
   *
   * @param payload 整份载荷（本方法自己读它的 {@code markets} 键）
   * @param entries 已经校验过的 {@code entries} 数组（用来判"市场落在 entry 之外"）
   */
  private static Map<HexCoord, Market> markets(JsonNode payload, JsonNode entries) {
    Map<HexCoord, Market> markets = new LinkedHashMap<>();
    JsonNode node = optionalObject(payload, "markets");
    if (node == null) {
      return markets; // 缺键 ⇒ 世界上一个市场都没有（合法状态）
    }
    Set<HexCoord> entryHexes = new LinkedHashSet<>();
    for (JsonNode entry : entries) {
      entryHexes.add(new HexCoord(requireInt(entry, "q"), requireInt(entry, "r")));
    }
    node.fields()
        .forEachRemaining(
            field -> {
              HexCoord hex;
              try {
                hex = HexCoord.parse(field.getKey());
              } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                    "markets 的键必须是格串（<q>_<r>，见 HexCoord）: " + field.getKey(), e);
              }
              if (!field.getValue().isObject()) {
                throw new IllegalArgumentException(
                    "markets."
                        + field.getKey()
                        + " 必须是对象 {numeraire, prices}: "
                        + field.getValue());
              }
              if (!entryHexes.contains(hex)) {
                throw new IllegalArgumentException(
                    "markets 指名的格 "
                        + field.getKey()
                        + " 不在 entries 里（命令的作用域是 entry 的格集，"
                        + "落在它之外的市场没有人申报 ⇒ 拒绝，不静默种下一个谁也管不到的格）");
              }
              if (markets.putIfAbsent(hex, market(field.getValue())) != null) {
                throw new IllegalArgumentException("markets 里格 " + field.getKey() + " 重复");
              }
            });
    return markets;
  }

  /**
   * ★★ <b>一格的市场</b>（{@code {"numeraire":"<币种>","prices":{"<商品>":<单价>}}}）。
   *
   * <p>★ <b>两个键的口径</b>：{@code numeraire} 必填（每格恰一种计价货币）；{@code prices} 可缺（⇒ 空表）； 单价**逐值 &gt; 0** 由
   * {@link Market} 的构造期守卫判死（本层不重复实现那条规则，只做形状）。
   */
  private static Market market(JsonNode node) {
    CurrencyId numeraire = CurrencyId.parse(requireText(node, "numeraire"));
    Map<CommodityId, Long> prices = commodityMap(optionalObject(node, "prices"), "prices");
    return new Market(numeraire, prices);
  }

  // ── P1：E1/E2 完整经济地基的可选载荷（缺键 ⇒ 空表；词表/范围守卫复用领域构造期）────────────

  /**
   * ★★ <b>顶层可选 {@code modes} 数组</b>（缺键 ⇒ 空表）。每项： {@code
   * {"id","name","version","classStructureId"}}。非法值由 {@link ProductionMode} 构造期守卫判， 同一份载荷里 id 重复 ⇒
   * 抛。
   */
  private static Map<ProductionModeId, ProductionMode> parseModes(JsonNode payload) {
    Map<ProductionModeId, ProductionMode> modes = new LinkedHashMap<>();
    for (JsonNode node : optionalArray(payload, "modes")) {
      if (!node.isObject()) {
        throw new IllegalArgumentException("modes 的每项必须是对象: " + node);
      }
      ProductionModeId id = ProductionModeId.parse(requireText(node, "id"));
      ProductionMode mode =
          new ProductionMode(
              id,
              requireText(node, "name"),
              requireInt(node, "version"),
              ClassStructureId.parse(requireText(node, "classStructureId")));
      if (modes.putIfAbsent(id, mode) != null) {
        throw new IllegalArgumentException("同一份载荷里 mode id 重复: " + id);
      }
    }
    return modes;
  }

  /**
   * ★★ <b>顶层可选 {@code classStructures} 数组</b>（缺键 ⇒ 空表）。每项： {@code
   * {"id","modeId","positions":[...],"defaultSharesPerMille":{...}}}； {@code positions}
   * 非空/重复键由构造期守卫与解析层判死，{@code defaultSharesPerMille} 可缺省 ⇒ 空表。
   */
  private static Map<ClassStructureId, ClassStructure> parseClassStructures(JsonNode payload) {
    Map<ClassStructureId, ClassStructure> structures = new LinkedHashMap<>();
    for (JsonNode node : optionalArray(payload, "classStructures")) {
      if (!node.isObject()) {
        throw new IllegalArgumentException("classStructures 的每项必须是对象: " + node);
      }
      ClassStructureId id = ClassStructureId.parse(requireText(node, "id"));
      ProductionModeId modeId = ProductionModeId.parse(requireText(node, "modeId"));
      Map<ClassPositionId, ClassPosition> positions = new LinkedHashMap<>();
      for (JsonNode positionNode : requireArray(node, "positions")) {
        ClassPosition position = classPosition(positionNode);
        if (positions.putIfAbsent(position.id(), position) != null) {
          throw new IllegalArgumentException(
              "同一份 classStructure 里 position id 重复: " + position.id());
        }
      }
      Map<ClassPositionId, Long> shares =
          classPositionShareMap(
              optionalObject(node, "defaultSharesPerMille"),
              "classStructures[].defaultSharesPerMille");
      ClassStructure structure = new ClassStructure(id, modeId, positions, shares);
      if (structures.putIfAbsent(id, structure) != null) {
        throw new IllegalArgumentException("同一份载荷里 classStructure id 重复: " + id);
      }
    }
    return structures;
  }

  /**
   * ★★ <b>顶层可选 {@code classPositions} 数组</b>（缺键 ⇒ 空表）。形状与 classStructures 内嵌的位置逐字相同；
   * 两条路径都构造同值对象，随后由 {@link EconomyData} 判"结构内位置 == 全局位置表"。
   */
  private static Map<ClassPositionId, ClassPosition> parseClassPositions(JsonNode payload) {
    Map<ClassPositionId, ClassPosition> positions = new LinkedHashMap<>();
    for (JsonNode node : optionalArray(payload, "classPositions")) {
      if (!node.isObject()) {
        throw new IllegalArgumentException("classPositions 的每项必须是对象: " + node);
      }
      ClassPosition position = classPosition(node);
      if (positions.putIfAbsent(position.id(), position) != null) {
        throw new IllegalArgumentException("同一份载荷里 classPosition id 重复: " + position.id());
      }
    }
    return positions;
  }

  /** 一个阶层位置节点：id/modeId/name + 三个结构维词表 + 可选 ruleExtensions（缺键 ⇒ 空表）。 */
  private static ClassPosition classPosition(JsonNode node) {
    return classPosition(node, null);
  }

  /**
   * 一个阶层位置节点（同 {@link #classPosition(JsonNode)}），{@code modeId} 可缺省为 {@code defaultModeId}。
   *
   * <p>★ P7 起 {@code economy.GmAdjust} 的两个 class 结构编辑 kind 复用本解析：{@code upsertClassStructure}
   * 的每个位置在 {@code classStructure.modeId} 缺省时取结构自身 modeId（给了则逐值参与后续一致性判据）；播种路径仍以 {@code null} 调本方法 ⇒
   * {@code modeId} 必填的旧行为逐字不变。
   */
  static ClassPosition classPosition(JsonNode node, String defaultModeId) {
    ClassPositionId id = ClassPositionId.parse(requireText(node, "id"));
    ProductionModeId modeId =
        node.hasNonNull("modeId")
            ? ProductionModeId.parse(requireText(node, "modeId"))
            : ProductionModeId.parse(
                defaultModeId == null ? requireText(node, "modeId") : defaultModeId);
    ClassPosition.RelationToMeans relationToMeans =
        enumValue(
            ClassPosition.RelationToMeans.class,
            requireText(node, "relationToMeans"),
            "classPositions[].relationToMeans");
    ClassPosition.LaborRole laborRole =
        enumValue(
            ClassPosition.LaborRole.class,
            requireText(node, "laborRole"),
            "classPositions[].laborRole");
    ClassPosition.SurplusRole surplusRole =
        enumValue(
            ClassPosition.SurplusRole.class,
            requireText(node, "surplusRole"),
            "classPositions[].surplusRole");
    Map<String, String> extensions =
        stringMap(optionalObject(node, "ruleExtensions"), "classPositions[].ruleExtensions");
    return new ClassPosition(
        id, modeId, requireText(node, "name"), relationToMeans, laborRole, surplusRole, extensions);
  }

  /**
   * ★★ <b>顶层可选 {@code classStandings} 数组</b>（缺键 ⇒ 空表）。每项： {@code
   * {"householdId","originalPositionId","currentPositionId","retainedShares"?,
   * "consecutiveDebtStressCycles"?,"lastTransitionDay"?,"reason"?}}；数值可缺省，reason 缺省空串。
   * 引用完整性（家户存在、位置存在）由 {@link EconomyData} 构造期守卫判。
   */
  private static Map<HouseholdId, ClassStanding> parseClassStandings(JsonNode payload) {
    Map<HouseholdId, ClassStanding> standings = new LinkedHashMap<>();
    for (JsonNode node : optionalArray(payload, "classStandings")) {
      if (!node.isObject()) {
        throw new IllegalArgumentException("classStandings 的每项必须是对象: " + node);
      }
      HouseholdId householdId = HouseholdId.parse(requireText(node, "householdId"));
      ClassPositionId original = ClassPositionId.parse(requireText(node, "originalPositionId"));
      ClassPositionId current = ClassPositionId.parse(requireText(node, "currentPositionId"));
      Map<ClassPositionId, Long> retainedShares =
          classPositionShareMap(
              optionalObject(node, "retainedShares"), "classStandings[].retainedShares");
      long consecutiveDebtStressCycles = optionalLong(node, "consecutiveDebtStressCycles", 0L);
      long lastTransitionDay = optionalLong(node, "lastTransitionDay", 0L);
      String reason = optionalText(node, "reason").orElse("");
      ClassStanding standing =
          new ClassStanding(
              householdId,
              original,
              current,
              retainedShares,
              consecutiveDebtStressCycles,
              lastTransitionDay,
              reason);
      if (standings.putIfAbsent(householdId, standing) != null) {
        throw new IllegalArgumentException("同一份载荷里 classStanding 家户重复: " + householdId);
      }
    }
    return standings;
  }

  /**
   * ★★ <b>顶层可选 {@code assetRules} 数组</b>（缺键 ⇒ 空表）。每项： {@code
   * {"modeId","assetKind","isCoreMeans","pledgeable","liquidationPriority",
   * "rentRule"?,"transferRule"}}；id 由 {@code AssetRuleId.idOf(modeId, assetKind)} 派生，若显式给了 {@code
   * id} 必须与派生值一致。
   */
  private static Map<AssetRuleId, AssetRule> parseAssetRules(JsonNode payload) {
    Map<AssetRuleId, AssetRule> rules = new LinkedHashMap<>();
    for (JsonNode node : optionalArray(payload, "assetRules")) {
      if (!node.isObject()) {
        throw new IllegalArgumentException("assetRules 的每项必须是对象: " + node);
      }
      ProductionModeId modeId = ProductionModeId.parse(requireText(node, "modeId"));
      AssetKind assetKind =
          enumValue(AssetKind.class, requireText(node, "assetKind"), "assetRules[].assetKind");
      boolean isCoreMeans = requireBoolean(node, "isCoreMeans");
      boolean pledgeable = requireBoolean(node, "pledgeable");
      int liquidationPriority = requireInt(node, "liquidationPriority");
      Optional<RentRule> rentRule =
          node.hasNonNull("rentRule")
              ? Optional.of(rentRule(requireObject(node, "rentRule")))
              : Optional.empty();
      TransferRule transferRule = transferRule(requireObject(node, "transferRule"));
      AssetRuleId derived = AssetRuleId.idOf(modeId, assetKind);
      if (node.hasNonNull("id")) {
        AssetRuleId declared = AssetRuleId.parse(requireText(node, "id"));
        if (!declared.equals(derived)) {
          throw new IllegalArgumentException(
              "assetRules[].id 必须与 (modeId, assetKind) 派生值一致（不许手写第二份身份）：声明="
                  + declared
                  + " 派生="
                  + derived);
        }
      }
      AssetRule rule =
          new AssetRule(
              derived,
              modeId,
              assetKind,
              isCoreMeans,
              pledgeable,
              liquidationPriority,
              rentRule,
              transferRule);
      if (rules.putIfAbsent(derived, rule) != null) {
        throw new IllegalArgumentException("同一份载荷里 assetRule id 重复: " + derived);
      }
    }
    return rules;
  }

  /** 一条租金模板节点：type/priority/legs；leg 的 kind/rate/fixed 与商品/币种二选一由构造期守卫判。 */
  static RentRule rentRule(JsonNode node) {
    RentRule.RentType type = RentRule.RentType.parse(requireText(node, "type"));
    int priority = requireInt(node, "priority");
    List<RentRule.RentLeg> legs = new ArrayList<>();
    for (JsonNode legNode : requireArray(node, "legs")) {
      if (!legNode.isObject()) {
        throw new IllegalArgumentException("assetRules[].rentRule.legs 的每项必须是对象: " + legNode);
      }
      RentRule.RentType kind = RentRule.RentType.parse(requireText(legNode, "kind"));
      int ratePerMille = optionalInt(legNode, "ratePerMille", 0);
      long fixedAmount = optionalLong(legNode, "fixedAmount", 0L);
      Optional<CommodityId> commodity = optionalText(legNode, "commodity").map(CommodityId::parse);
      Optional<CurrencyId> currency = optionalText(legNode, "currency").map(CurrencyId::parse);
      legs.add(new RentRule.RentLeg(kind, ratePerMille, fixedAmount, commodity, currency));
    }
    return new RentRule(type, priority, legs);
  }

  /** 一条转移规则节点（三个布尔都必填；构造期无额外不变量，形状即语义）。 */
  static TransferRule transferRule(JsonNode node) {
    return new TransferRule(
        requireBoolean(node, "transferable"),
        requireBoolean(node, "requiresOwnerConsent"),
        requireBoolean(node, "allowSublease"));
  }

  /** 位置 id → 千分比/数量表（用于 classStructures.defaultSharesPerMille 与 classStandings.retainedShares）。 */
  static Map<ClassPositionId, Long> classPositionShareMap(JsonNode object, String field) {
    Map<ClassPositionId, Long> out = new LinkedHashMap<>();
    if (object == null) {
      return out;
    }
    object
        .fields()
        .forEachRemaining(
            entry ->
                out.put(
                    ClassPositionId.parse(entry.getKey()),
                    requireIntegral(entry.getValue(), field + "." + entry.getKey())));
    return out;
  }

  /** 字符串表（用于 {@code ClassPosition.ruleExtensions}）；值非文本 ⇒ 抛。 */
  static Map<String, String> stringMap(JsonNode object, String field) {
    Map<String, String> out = new LinkedHashMap<>();
    if (object == null) {
      return out;
    }
    object
        .fields()
        .forEachRemaining(
            entry -> {
              if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException(field + " 的键不得为空白: " + entry.getKey());
              }
              JsonNode value = entry.getValue();
              if (value == null || !value.isTextual()) {
                throw new IllegalArgumentException(
                    field + "." + entry.getKey() + " 必须是字符串: " + value);
              }
              out.put(entry.getKey(), value.asText());
            });
    return out;
  }

  // ── 生产关系（T2；计划 R3/R8）────────────────────────────────────────────────────────

  /**
   * ★★ <b>一个 unit 的 {@code relation}</b>（可选键）：缺 ⇒ 按 {@code regime} 推导；给了 ⇒ 逐值采纳 + {@code operator}
   * 与 unit.operator 的一致性判死（R3B.2 起关系挂 unit）。
   *
   * <p>★★ <b>H0：推导还要一个"这批家户住哪种居住类型"</b>（cohort 键的居住维）—— 它的**唯一来源是同一条 entry 的配额表** （{@link
   * ResidenceKind#ofLot}），由调用方算好传进来（本方法看不见整条 entry）。★ <b>显式给了 {@code relation} 时它不参与</b>：
   * 那时受方是载荷逐字写出的 cohort 串（自带居住段），不推导、也不校验（"载荷说什么就是什么"）。
   *
   * @param activity 关系挂的那个 unit（键 = 它，铁律 1）
   * @param operator 该 unit 的经营者（显式 relation 里的 operator 必须与它逐值相等）
   */
  private static ProductionRelation relation(
      JsonNode relationNode,
      Industry industry,
      ProductionUnitId activity,
      ActorRef operator,
      Set<ResidenceKind> residences) {
    if (relationNode == null || relationNode.isNull()) {
      return RegimeRelations.defaultRelation(
          industry.regime(), activity, industry.id(), operator, residences);
    }
    JsonNode operatorNode = optionalObject(relationNode, "operator");
    ActorRef relationOperator = operatorNode == null ? operator : actorRef(operatorNode);
    // ★★ 载荷边缘的一致性判据（R3 的第 2 条；状态层还有同一条守卫 —— 两层都在是**有意**的）。
    if (!relationOperator.equals(operator)) {
      throw new IllegalArgumentException(
          "relation.operator 必须与 unit.operator 一致（同一件事不许两处拼写）：关系="
              + relationOperator
              + "，unit="
              + operator
              + "（unit "
              + activity
              + "，产业 "
              + industry.id()
              + "）");
    }
    JsonNode residualNode = optionalObject(relationNode, "residualOwner");
    ActorRef residualOwner = residualNode == null ? operator : actorRef(residualNode);
    // ★★ **H3（裁定 C3）：投入由谁出** —— 可选键，形状与补偿规则的 recipient 逐字同款（`{actor:{kind,id}}` 或
    //   `{cohort:"0_0|rural|landlord"}`，恰给其一）。★ **缺键 ⇒ 取 operator**（= 四档默认，见 RegimeRelations）：
    //   这是旧档兼容的那一处边缘 —— H3 之前的 relation JSON 没有这个键，而"旧档读不回来"不是兼容，是事故。
    //   ★ 缺省**不在这里另写一遍值**：交给 ProductionRelation 的构造期缺省（null ⇒ ToActor(operator)），
    //     一处拼写点（本层只解析"给了什么"，不发明"没给时是什么"）。
    JsonNode inputSupplierNode = optionalObject(relationNode, "inputSupplier");
    Recipient inputSupplier =
        inputSupplierNode == null ? null : recipient(inputSupplierNode, "inputSupplier");
    List<CompensationRule> rules = new ArrayList<>();
    for (JsonNode rule : optionalArray(relationNode, "rules")) {
      rules.add(compensationRule(rule));
    }
    // ★ S1：劳动来源（可选键；缺省留给 ProductionRelation 的构造期兜底 SELF，与旧档口径一致）。
    LaborSource laborSource =
        relationNode.hasNonNull("laborSource")
            ? LaborSource.parse(requireText(relationNode, "laborSource"))
            : null;
    return new ProductionRelation(
        activity, operator, inputSupplier, rules, residualOwner, laborSource);
  }

  /**
   * ★★ <b>一个受方</b>（{@code {actor:{kind,id}}} 恰给其一，或 {@code {cohort:"<CohortKey 规范串>"}}）—— 补偿规则的
   * {@code recipient} 与关系的 {@code inputSupplier}（H3）<b>共用本方法</b>。
   *
   * <p>★ <b>抽出来的是形状与拒因，不是文案</b>：两个调用点的消息里都带字段名（{@code what}），于是"哪个键写歪了"一眼可见；
   * 少了这一步，同一套"恰其一"的规则就会在第二处再写一遍（本仓明令禁止的第二拼写点）。
   *
   * @param node 受方节点（非 null；调用方已确认它是对象）
   * @param what 字段名（进错误消息；如 {@code "recipient"} / {@code "inputSupplier"}）
   */
  static Recipient recipient(JsonNode node, String what) {
    JsonNode actorNode = optionalObject(node, "actor");
    boolean hasCohort = node.hasNonNull("cohort");
    boolean hasHousehold = node.hasNonNull("household");
    int given = (actorNode != null ? 1 : 0) + (hasCohort ? 1 : 0) + (hasHousehold ? 1 : 0);
    if (given != 1) {
      throw new IllegalArgumentException(
          what + " 必须恰给 actor / household / cohort 之一（给 " + given + " 个）: " + node);
    }
    if (actorNode != null) {
      return new Recipient.ToActor(actorRef(actorNode));
    }
    if (hasHousehold) {
      return new Recipient.ToHousehold(HouseholdId.parse(requireText(node, "household")));
    }
    return new Recipient.ToCohort(CohortKey.parse(requireText(node, "cohort")));
  }

  /**
   * 一条补偿规则：{@code {type, recipient:{actor|cohort}, pool, weight?, basis?, ratePerMille,
   * fixedAmount, commodity?, currency?, priority}}。
   *
   * <p>★ <b>受方"恰其一"</b>：两个变体都没给 / 都给了 ⇒ 抛（契约里它是**类型事实**，载荷这一层负责把它喂对）。 ★ <b>{@code commodity} 缺键 =
   * 货币档</b>（空 {@code Optional}）—— 与 {@link CompensationRule} 的二选一守卫
   * 同源，本层不重复判它（违反了那条守卫会由契约自己抛，消息更准）。
   *
   * <p>★★ <b>H2：{@code pool} × {@code weight} 与旧档的 {@code basis} 都收</b>（详见类注）—— 这是"旧档不许当场抛"
   * 那条纪律的落点：真档的关系载荷全是 {@code basis}，少了这条翻译，整个真档播不出来。
   */
  static CompensationRule compensationRule(JsonNode node) {
    JsonNode recipientNode = optionalObject(node, "recipient");
    if (recipientNode == null) {
      throw new IllegalArgumentException("补偿规则的字段 recipient 必须是对象: " + node);
    }
    // ★ H3 起受方的解析与关系的 inputSupplier **共用同一处**（见 recipient）：同一套"恰其一"的规则只有一个拼写点。
    Recipient recipient = recipient(recipientNode, "recipient");
    RuleType type = RuleType.parse(requireText(node, "type"));
    return new CompensationRule(
        type,
        recipient,
        poolOf(node),
        weightOf(node),
        requireInt(node, "ratePerMille"),
        requireLong(node, "fixedAmount"),
        optionalText(node, "commodity").map(CommodityId::parse),
        currencyOf(node, type),
        requireInt(node, "priority"));
  }

  /**
   * ★★ <b>池：新档 {@code pool}、旧档 {@code basis}（H2 之前的关系载荷全是这一形状）</b>—— 旧字面量经 {@link Basis#pool()}
   * 翻译（映射表在 {@code Basis} 的类注里）。
   *
   * <p>★ <b>两个键都没给 ⇒ 抛</b>（缺一个必填字段是坏载荷，不是缺省）；★ <b>两个都给了 ⇒ 也抛</b>（不猜：同一件事的两处拼写不一致时， 没有哪一处能判谁对 —— 同
   * {@code operator} 那条一致性强判的口径）。
   */
  private static Pool poolOf(JsonNode node) {
    Optional<String> pool = optionalText(node, "pool");
    Optional<String> basis = optionalText(node, "basis");
    if (pool.isPresent() && basis.isPresent()) {
      throw new IllegalArgumentException(
          "关系规则不得同时给 pool 与 basis（H2 起 pool+weight 是新档、basis 是旧档，两者只能给一个）: " + node);
    }
    if (pool.isPresent()) {
      return Pool.parse(pool.get());
    }
    if (basis.isPresent()) {
      return Basis.parse(basis.get()).pool();
    }
    throw new IllegalArgumentException("关系规则缺 pool（H2 起的必填键；旧档写 basis）: " + node);
  }

  /** ★★ <b>权重：新档 {@code weight}（缺省 {@code NONE}）、旧档 {@code basis}</b>—— 同 {@link #poolOf} 的口径。 */
  private static Weight weightOf(JsonNode node) {
    Optional<String> weight = optionalText(node, "weight");
    Optional<String> basis = optionalText(node, "basis");
    if (weight.isPresent() && basis.isPresent()) {
      throw new IllegalArgumentException(
          "关系规则不得同时给 weight 与 basis（H2 起 pool+weight 是新档、basis 是旧档，两者只能给一个）: " + node);
    }
    if (weight.isPresent()) {
      return Weight.parse(weight.get());
    }
    if (basis.isPresent()) {
      return Basis.parse(basis.get()).weight();
    }
    // ★ 给了 pool 而没给 weight ⇒ NONE（"不分"是绝大多数规则的那一档；旧档走不到这里，它在上面就返回了）。
    return Weight.NONE;
  }

  /**
   * ★★ <b>币种（H2 的币种位）</b>：实物档不得给、货币档必须有 —— 货币档缺键时取<b>出厂货币</b> （{@link
   * RegimeRelations#DEFAULT_CURRENCY} 是唯一拼写点）。
   *
   * <p>★ <b>为什么缺键是"取出厂值"而不是"抛"</b>：旧档的货币规则<b>没有这个键</b>（币种位是 H2 才有的）， 而"1000 毫钱"在旧档里本来就没有说是哪种钱 ⇒
   * 翻译成出厂货币是**旧档兼容**，不是猜（真值随 S2 的货币口径定）。 ★ 实物档给了币种 ⇒ 抛（那是坏数据：实物不是钱）。
   */
  private static Optional<CurrencyId> currencyOf(JsonNode node, RuleType type) {
    Optional<CurrencyId> currency = optionalText(node, "currency").map(CurrencyId::parse);
    if (!type.money()) {
      if (currency.isPresent()) {
        throw new IllegalArgumentException(
            "实物规则不得带 currency（"
                + type
                + "）：currency="
                + currency.get()
                + " —— 实物档恒空，币种只对货币档合法: "
                + node);
      }
      return Optional.empty();
    }
    return currency.isPresent() ? currency : Optional.of(RegimeRelations.DEFAULT_CURRENCY);
  }

  // ── 劳动供给 / 劳动分配（R2，设计稿 §四）──────────────────────────────────────────────

  /**
   * 一份劳动供给：{@code {group, period, grossLaborMilli, servedLaborMilli?, committedLaborMilli?}}。
   *
   * <p>★ **毛额由载荷给**（不由本层从人口算）：人口与年龄性别住在 social 的 {@code PopulationGroup}，economy 编译期不认识它 （设计稿
   * §二/§八.1）—— 算出毛额的是 {@code EconomySeeder}（它以 R1 已落地的年龄×性别系数表折算）。
   */
  private static LaborSupply laborSupply(JsonNode node) {
    return new LaborSupply(
        PeopleLotId.parse(requireText(node, "group")),
        requireLong(node, "period"),
        requireLong(node, "grossLaborMilli"),
        // ★ 两项扣除缺省 0（本轮的形态）：它们**不是可有可无**的字段，只是值为 0 ——
        //   LaborSupply 照减（见其类注），故载荷给非 0 时逐值生效。
        optionalLong(node, "servedLaborMilli", 0L),
        optionalLong(node, "committedLaborMilli", 0L));
  }

  /**
   * ★★ <b>R3B.2：配额 activity → unit</b>。新载荷必须直接给 unit id；旧载荷给活动标签（{@code farm}）或旧产业串 ⇒ 按 {@code
   * actor.id()} 当产业串找**唯一** unit 改写；多个候选抛（新载荷必须写 unit id，不猜）；解析不到 ⇒ 原样 （自由家户劳动，不喂任何生产，旧档同义）。
   */
  private static LaborAllocation canonicalAllocationActivity(
      LaborAllocation allocation,
      Map<String, List<ProductionUnitId>> unitsByIndustry,
      Map<ProductionUnitId, ProductionUnit> units,
      JsonNode entry) {
    ProductionUnitId byActivity = new ProductionUnitId(allocation.activity());
    ProductionUnit direct = units.get(byActivity);
    if (direct != null) {
      // ★ activity 已是 unit id：actor 必须是该 unit 的 operator（守卫要求两者一致）——旧载荷里 actor 可能是
      //   产业 id（默认经营者同 id 时本就相等），这里按 unit.operator 对齐。
      return direct.operator().equals(allocation.actor())
          ? allocation
          : new LaborAllocation(
              allocation.id(),
              allocation.group(),
              allocation.household(),
              direct.operator(),
              allocation.activity(),
              allocation.laborMilli(),
              allocation.period());
    }
    if (allocation.activity().startsWith("unit-")) {
      throw new IllegalArgumentException(
          "劳动配额的 activity 看起来是 unit id 但该 unit 不在载荷里（悬空引用；自由家户劳动请用非 unit 的活动词）："
              + allocation
              + "，entry="
              + entry);
    }
    List<ProductionUnitId> candidates =
        unitsByIndustry.getOrDefault(allocation.actor().id(), List.of());
    if (candidates.isEmpty()) {
      candidates = unitsByIndustry.getOrDefault(allocation.activity(), List.of());
    }
    if (candidates.size() == 1) {
      ProductionUnit resolved = units.get(candidates.get(0));
      return new LaborAllocation(
          allocation.id(),
          allocation.group(),
          allocation.household(),
          resolved == null ? allocation.actor() : resolved.operator(),
          candidates.get(0).value(),
          allocation.laborMilli(),
          allocation.period());
    }
    if (candidates.size() > 1) {
      throw new IllegalArgumentException(
          "劳动配额的 activity 不是 unit id，而 actor 指名的产业有多条 unit ⇒ 无法确定是哪一条（请在载荷里写 unit id）："
              + allocation
              + "，候选="
              + candidates
              + "，entry="
              + entry);
    }
    return allocation;
  }

  /** 一条配额供给的 unit 集合（已对齐 activity；解析不到 ⇒ 空表）。 */
  private static List<ProductionUnitId> resolveAllocationUnits(
      LaborAllocation allocation,
      Map<String, List<ProductionUnitId>> unitsByIndustry,
      Set<ProductionUnitId> unitIds) {
    ProductionUnitId byActivity = new ProductionUnitId(allocation.activity());
    if (unitIds.contains(byActivity)) {
      return List.of(byActivity);
    }
    if (allocation.activity().startsWith("unit-")) {
      return List.of(); // 悬空 unit 引用：不解析；由构造期守卫 fail-closed
    }
    List<ProductionUnitId> candidates =
        unitsByIndustry.getOrDefault(allocation.actor().id(), List.of());
    if (candidates.isEmpty()) {
      candidates = unitsByIndustry.getOrDefault(allocation.activity(), List.of());
    }
    return candidates.size() == 1 ? List.of(candidates.get(0)) : List.of();
  }

  /**
   * 一条劳动配额：{@code {id, group, actor:{kind,id}, activity, laborMilli, period}}。
   *
   * <p>★ {@code actor.kind} 走 {@link ActorKind#parse} 的**词表**（词表外的种类即抛并列出合法值）； {@code actor.id}
   * 与产业的对应关系由 {@code EconomyData} 的构造期守卫判死（不在这里重复实现）。
   */
  private static LaborAllocation allocation(JsonNode node) {
    JsonNode actor = optionalObject(node, "actor");
    if (actor == null) {
      throw new IllegalArgumentException("劳动分配的字段 actor 必须是对象: " + node);
    }
    LaborAllocationId id = LaborAllocationId.parse(requireText(node, "id"));
    return new LaborAllocation(
        id,
        PeopleLotId.parse(requireText(node, "group")),
        // ★ S1：新载荷可以显式给 household；旧载荷没有该键 ⇒ pending 占位，由 EconomyData 构造期的
        //   LegacyHouseholdMigration 按"产业格 + 居住类型"的行人口拆到真实家户（与旧档同一条规则）。
        node.hasNonNull("household")
            ? HouseholdId.parse(requireText(node, "household"))
            : HouseholdIds.pendingLegacy(id.value()),
        actorRef(actor),
        requireText(node, "activity"),
        requireLong(node, "laborMilli"),
        requireLong(node, "period"));
  }

  // ── 实物资产份额（R3B.1）────────────────────────────────────────────────────────────

  /**
   * ★★ <b>一条实物资产份额载荷 → {@link AssetShare}</b>。
   *
   * <p>★ <b>新形状</b>：{@code {industry, owner:{kind,id}, operator:{kind,id}, asset, quantity, kind}}
   * —— {@code owner} 与 {@code operator} 是两件事，允许不等（租佃/委托）。
   *
   * <p>★ <b>旧形状（{@code useRights} 数组）</b>：{@code {activity, holder, asset, quantity, kind}} ⇒ 一对一翻译
   * {@code holder ⇒ owner=operator}、{@code activity ⇒ industry}；不拆地主/佃户/多 unit（R3B.1 边界）。
   *
   * <p>★ <b>id 一律不信任载荷、由确定性序号生成</b>（{@link AssetShare#idOf}）：同一份载荷重放得到同一批 id， 禁止随机数/时间戳；旧档里已落盘的
   * {@code use-…} id 不走本方法（那条路在 {@code EconomyCodec} 里原样保留）。
   */
  private static void addAssetShare(
      Map<AssetShareId, AssetShare> out,
      Map<String, Long> sequences,
      JsonNode node,
      boolean legacy) {
    IndustryId industry;
    ActorRef owner;
    ActorRef operator;
    if (legacy) {
      industry = IndustryId.parse(requireText(node, "activity"));
      JsonNode holderNode = optionalObject(node, "holder");
      if (holderNode == null) {
        throw new IllegalArgumentException("旧使用权（useRights）的字段 holder 必须是对象: " + node);
      }
      owner = actorRef(holderNode);
      operator = owner;
    } else {
      industry = IndustryId.parse(requireText(node, "industry"));
      JsonNode ownerNode = optionalObject(node, "owner");
      if (ownerNode == null) {
        throw new IllegalArgumentException("资产份额的字段 owner 必须是对象: " + node);
      }
      JsonNode operatorNode = optionalObject(node, "operator");
      if (operatorNode == null) {
        throw new IllegalArgumentException("资产份额的字段 operator 必须是对象: " + node);
      }
      owner = actorRef(ownerNode);
      operator = actorRef(operatorNode);
    }
    AssetKind asset;
    try {
      asset = AssetKind.valueOf(requireText(node, "asset"));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("资产份额的 asset 不是生产资料种类: " + node, e);
    }
    long quantity = requireLong(node, "quantity");
    AssetShare.RightKind kind;
    try {
      kind = AssetShare.RightKind.valueOf(requireText(node, "kind"));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("资产份额的 kind 不是 OWNED/TENANCY/COMMUNAL: " + node, e);
    }
    String sequenceKey = industry + "|" + asset + "|" + owner + "|" + operator + "|" + kind;
    long sequence = sequences.getOrDefault(sequenceKey, 0L);
    sequences.put(sequenceKey, sequence + 1L);
    AssetShare share =
        new AssetShare(
            AssetShare.idOf(industry, asset, owner, operator, kind, sequence),
            industry,
            asset,
            owner,
            operator,
            quantity,
            kind);
    if (out.putIfAbsent(share.id(), share) != null) {
      throw new IllegalArgumentException("同一份载荷里资产份额重复: " + share.id());
    }
  }

  // ── 产业 / 阶层行 ────────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>R0（S0.1）：entry 的格必须与 {@code industry.id} 里携带的格逐字一致</b>。
   *
   * <p>★★ <b>为什么必须在这里判死</b>：{@code entries[]} 是命令的<b>作用域</b>（{@code CommandTargets} 逐格判越权）， 而产业按
   * {@code id} 里的格参与结算/读口。两者不一致时，一条只被授权动 {@code (0,0)} 的播种命令会把 {@code farm@9_9} 的产业写进世界 ——
   * 命令目标与真实写入面错位（这正是 R0 要堵的旁路）。
   *
   * <p>★ <b>id 不带格键也拒</b>：那种 id 无法与任何 entry 对账（{@link IndustryHexKeys#hexKeyOf} 返回空），
   * 放行等于把这条守卫留成"只要不写 {@code @} 就能绕过"。★ 旧档（非播种载荷）不受影响：它们由 {@code EconomyCodec} 读入，不经本方法。
   */
  private static void requireIndustryHexMatchesEntry(HexCoord entryHex, IndustryId id) {
    String entryKey = IndustryHexKeys.hexKey(entryHex.q(), entryHex.r());
    Optional<String> industryKey = IndustryHexKeys.hexKeyOf(id);
    if (industryKey.isEmpty()) {
      throw new IllegalArgumentException(
          "产业 id 必须携带格键（<kind>@<q>_<r>）才能与它所在的 entry 对账: industry.id="
              + id
              + "，entry 格="
              + entryKey);
    }
    if (!industryKey.get().equals(entryKey)) {
      throw new IllegalArgumentException(
          "产业 id 的格与它所在的 entry 格不一致（拒绝该播种载荷）: industry.id="
              + id
              + " ⇒ 格 "
              + industryKey.get()
              + "；entry=(q="
              + entryHex.q()
              + ",r="
              + entryHex.r()
              + ") ⇒ 格 "
              + entryKey);
    }
  }

  /**
   * ★★ <b>R3B.2 的 Industry 载荷 = 纯技术模板</b>：只读 {@code id/name/regime/cycleDays/capacityPerUnit/
   * dailyInputPerUnit/dailyLaborPerUnit/laborPerUnit/outputPerUnit/cycleInputPerUnit/allocation/slots}。
   *
   * <p>★ 旧载荷的实例字段（{@code
   * operator/capacity/progressDays/cycleLaborMilli/cycleInputUsedMilli}）**不在这里读**： 它们由 {@link
   * #industrySpec} 另存，用于合成默认 unit + 整额 OWNED 份额（见类注）。
   */
  private static Industry industry(JsonNode node) {
    IndustryId id = IndustryId.parse(requireText(node, "id"));
    String name = requireText(node, "name");
    RegimeId regime = RegimeId.parse(requireText(node, "regime"));
    long cycleDays = requireLong(node, "cycleDays");
    // ★ R3（V7）：配方的两个新分量 —— "每 1 单位规模需要多少生产资料 / 多少劳动"。
    Map<AssetKind, Long> capacityPerUnit =
        assetMap(optionalObject(node, "capacityPerUnit"), "capacityPerUnit");
    long laborPerUnit = optionalLong(node, "laborPerUnit", 0L);
    Map<AssetKind, Map<CommodityId, Long>> dailyInput =
        assetCommodityMap(optionalObject(node, "dailyInputPerUnit"), "dailyInputPerUnit");
    long dailyLabor = optionalLong(node, "dailyLaborPerUnit", 0L);
    Map<CommodityId, Long> output =
        commodityMap(optionalObject(node, "outputPerUnit"), "outputPerUnit");
    Map<AssetKind, Map<CommodityId, Long>> cycleInput =
        assetCommodityMap(optionalObject(node, "cycleInputPerUnit"), "cycleInputPerUnit");
    List<ClassSlot> slots = new ArrayList<>();
    for (JsonNode slot : requireArray(node, "slots")) {
      slots.add(
          new ClassSlot(
              SocialClassId.parse(requireText(slot, "id")),
              requireText(slot, "name"),
              requireInt(slot, "laborParticipationPerMille")));
    }
    JsonNode allocation = node.get("allocation");
    if (allocation == null || !allocation.isObject()) {
      throw new IllegalArgumentException("字段 allocation 必须是对象: " + node);
    }
    AllocationRule rule;
    try {
      rule = MAPPER.convertValue(allocation, AllocationRule.class);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("allocation 形状不对（见 AllocationRule）: " + allocation, e);
    }
    // ★ v2 spec §八.4：v1 的周期结算只实现了 AllocationRule.Split（WageFirst 在播种期拒）。
    if (!(rule instanceof AllocationRule.Split)) {
      throw new IllegalArgumentException("v1 的周期分配只支持 AllocationRule.Split（v2 spec §八.4）：" + rule);
    }
    return new Industry(
        id,
        name,
        regime,
        cycleDays,
        capacityPerUnit,
        dailyInput,
        dailyLabor,
        laborPerUnit,
        output,
        cycleInput,
        slots,
        rule);
  }

  /** ★★ 旧载荷的 Industry 节点：模板 + 旧实例字段快照（合成默认 unit/份额用；新载荷 legacy=false）。 */
  private record IndustrySpec(
      Industry template,
      JsonNode node,
      boolean legacy,
      ActorRef legacyOperator,
      long progressDays,
      long cycleLaborMilli,
      Map<CommodityId, Long> cycleInputUsedMilli,
      Map<AssetKind, Long> capacity) {}

  /** 读一个 industry 节点：模板走 {@link #industry}，旧实例字段另存；判据 = 四个旧键任一出现。 */
  private static IndustrySpec industrySpec(JsonNode node) {
    Industry template = industry(node);
    boolean legacy =
        node.has("operator")
            || node.has("capacity")
            || node.has("progressDays")
            || node.has("cycleLaborMilli")
            || node.has("cycleInputUsedMilli");
    JsonNode operatorNode = optionalObject(node, "operator");
    ActorRef legacyOperator =
        operatorNode == null
            ? RegimeOperators.defaultOperator(template.regime(), template.id())
            : actorRef(operatorNode);
    long progressDays = optionalLong(node, "progressDays", 0L);
    long cycleLabor = optionalLong(node, "cycleLaborMilli", 0L);
    Map<CommodityId, Long> cycleInputUsed =
        commodityMap(optionalObject(node, "cycleInputUsedMilli"), "cycleInputUsedMilli");
    Map<AssetKind, Long> capacity = assetMap(optionalObject(node, "capacity"), "capacity");
    return new IndustrySpec(
        template, node, legacy, legacyOperator, progressDays, cycleLabor, cycleInputUsed, capacity);
  }

  /** 同一 entry 内按产业 id 找 spec（新载荷 unit 引用产业模板）。 */
  private static IndustrySpec specOf(List<IndustrySpec> specs, IndustryId id) {
    for (IndustrySpec spec : specs) {
      if (spec.template().id().equals(id)) {
        return spec;
      }
    }
    return null;
  }

  /**
   * ★★ <b>R3B.2：一个 unit 载荷节点 → {@link ProductionUnit}</b>：{@code {id?, industry, operator?,
   * modeKey?, progressDays?, cycleLaborMilli?, cycleInputUsedMilli?}}。 {@code id} 缺省 = {@link
   * ProductionUnitId#idOf}；{@code operator} 缺省 = 该产业 regime 的默认经营者； {@code modeKey} 缺省 = {@code
   * industry.id().value()}（旧档口径）。
   */
  private static ProductionUnit unit(JsonNode node, Industry industry, ActorRef operator) {
    String idText = optionalText(node, "id").orElse(null);
    ProductionUnitId id =
        idText == null
            ? ProductionUnitId.idOf(industry.id(), operator)
            : ProductionUnitId.parse(idText);
    String modeKey = optionalText(node, "modeKey").orElse(industry.id().value());
    long progressDays = optionalLong(node, "progressDays", 0L);
    long cycleLaborMilli = optionalLong(node, "cycleLaborMilli", 0L);
    Map<CommodityId, Long> cycleInputUsed =
        commodityMap(optionalObject(node, "cycleInputUsedMilli"), "cycleInputUsedMilli");
    return new ProductionUnit(
        id, industry.id(), operator, modeKey, progressDays, cycleLaborMilli, cycleInputUsed);
  }

  /** 某个产业在 {@code assetShares} 表里是否已有份额行（值内 industry 命中）。 */
  private static boolean hasShareForIndustry(
      Map<AssetShareId, AssetShare> assetShares, IndustryId industry) {
    for (AssetShare share : assetShares.values()) {
      if (share.industry().equals(industry)) {
        return true;
      }
    }
    return false;
  }

  /** 旧载荷的 capacity 表 ⇒ 逐项整额 OWNED 份额（含 0 值；capacity 空则退回 capacityPerUnit 的键、数量 0）。 */
  private static void synthesizeOwnedShares(
      Map<AssetShareId, AssetShare> out,
      Map<String, Long> sequences,
      IndustryId industry,
      ActorRef operator,
      Map<AssetKind, Long> capacity) {
    // ★ capacity 空（老载荷 capacity 键缺失）⇒ 不登记任何份额 ⇒ 调用方不造 unit；规模恒 0 与旧档逐值等价。
    for (Map.Entry<AssetKind, Long> entry : capacity.entrySet()) {
      ObjectNode share = MAPPER.createObjectNode();
      share.put("industry", industry.value());
      ObjectNode operatorNode = MAPPER.createObjectNode();
      operatorNode.put("kind", operator.kind().name());
      operatorNode.put("id", operator.id());
      share.set("owner", operatorNode);
      share.set("operator", operatorNode.deepCopy());
      share.put("asset", entry.getKey().name());
      share.put("quantity", entry.getValue());
      share.put("kind", AssetShare.RightKind.OWNED.name());
      addAssetShare(out, sequences, share, false);
    }
  }

  /**
   * ★★ <b>一条家户行</b>（H0：键 = 该 entry 的格 + 行上显式声明的 {@code residence} + {@code slot}）： {@code
   * {residence, slot, population, laborMilli, participationPerMille, money, naturalNeeds,
   * effectiveDemand, cycleNaturalNeedMilli?}}。
   *
   * <p>★★ <b>三条 fail-closed（{@code goods} 那条是 H1 新增的）</b>：
   *
   * <ul>
   *   <li>{@code residence} <b>必填</b>（{@link ResidenceKind#parse}，词表外即抛）：居住维是家户身份的一维，而"行属于哪个产业"
   *       那层隐含（{@code farm}/{@code weave} = 农村）H0 之后没有了 ⇒ 按产业种类猜出来的第二份约定会与配额表漂开（见类注 ①）；
   *   <li>{@code meansOfProduction} <b>给了即抛</b>（K3）：产能搬到 {@code Industry.capacity} —— 静默忽略它 =
   *       "看起来在记、其实被丢掉"（真档表现为全格绝收而账面看不出是谁弄丢的）；
   *   <li>★★ {@code goods} <b>给了即抛</b>（H1；裁定 D3-C/K1）：家户的商品库存住在 actor 切片的 {@code GoodsAccount}（键
   *       {@code (HouseholdActors.of(cohort), cohort.hex())}），economy 侧只在**会话工作副本**里读它 （{@code
   *       旧日推进器（R3a 已删除）} 的入参）。★ 播种那一份要**搬**过去（app 的 {@code HouseholdSeeder}）， 静默忽略它 =
   *       创世库存凭空消失（真档表现为第 1 天全员断粮，而载荷看起来完全正常）。
   * </ul>
   *
   * <p>★★ <b>M2.7 的 {@code cycleNaturalNeedMilli} 是可选键</b>（旧档缺键 ⇒ 0，照本类 {@code money} 的同款先例）：
   * 它是**结算逐日累加的读数**（本周期累计自然口粮需要），创世载荷通常不写它；旧载荷读成 0 = "还没开始累计"，不是"没有需要"。
   */
  private static ClassRow classRow(HexCoord hex, JsonNode node) {
    ResidenceKind residence = ResidenceKind.parse(requireText(node, "residence"));
    SocialClassId slot = SocialClassId.parse(requireText(node, "slot"));
    long population = requireLong(node, "population");
    long laborMilli = requireLong(node, "laborMilli");
    int participation = requireInt(node, "participationPerMille");
    if (node.hasNonNull("meansOfProduction")) {
      throw new IllegalArgumentException(
          "家户行不再有 meansOfProduction 键（K3：产能已搬到产业的 capacity 键，见 Industry.capacity）："
              + node.get("meansOfProduction"));
    }
    if (node.hasNonNull("goods")) {
      throw new IllegalArgumentException(
          "家户行不再有 goods 键（H1/K1：商品库存住在 actor 切片的 GoodsAccount 上，"
              + "economy 侧只在 旧日推进器（R3a 已删除） 的会话工作副本里读它 —— 播种时请把它搬进该家户 actor 的账户）："
              + node.get("goods"));
    }
    long money = optionalLong(node, "money", 0L);
    long cycleNaturalNeedMilli = optionalLong(node, "cycleNaturalNeedMilli", 0L);
    List<DebtContractId> debts = new ArrayList<>();
    for (JsonNode debt : optionalArray(node, "debts")) {
      if (!debt.isTextual() || debt.asText().isBlank()) {
        throw new IllegalArgumentException("classes[].debts 的每项必须是非空 DebtContractId 字符串: " + debt);
      }
      // ★★ E4c：允许非空引用；它是**派生索引**（进入 EconomyData 后由 DebtReferenceReconciler 以合同表为权威重建）。
      //   指不到合同/端点不存在仍会在构造期具名抛，不会留下悬空引用。
      debts.add(DebtContractId.parse(debt.asText()));
    }
    Map<CommodityId, Long> needs =
        commodityMap(optionalObject(node, "naturalNeeds"), "naturalNeeds");
    Map<CommodityId, Long> demand =
        commodityMap(optionalObject(node, "effectiveDemand"), "effectiveDemand");
    HouseholdId householdId =
        node.hasNonNull("householdId")
            ? HouseholdId.parse(requireText(node, "householdId"))
            : HouseholdIds.ofSeed(hex, residence, slot);
    return new ClassRow(
        householdId,
        // ★ 这是创世载荷声明的 view（slot 可含 S3 新阶层）；运行期只由 HouseholdClassRule 改写 ClassRow.view，
        //   不回写载荷、也不反过来从 view 推身份（householdId 缺失时才由 ofSeed 生成稳定身份）。
        new CohortKey(hex, residence, slot),
        population,
        laborMilli,
        participation,
        money,
        debts,
        needs,
        demand,
        cycleNaturalNeedMilli);
  }

  /** 键是 {@link AssetKind} 名的定点整数表（值非整/键不认识 ⇒ 抛）。 */
  private static Map<AssetKind, Long> assetMap(JsonNode object, String field) {
    Map<AssetKind, Long> out = new LinkedHashMap<>();
    if (object == null) {
      return out;
    }
    object
        .fields()
        .forEachRemaining(
            entry -> {
              AssetKind kind;
              try {
                kind = AssetKind.valueOf(entry.getKey());
              } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                    "字段 " + field + " 的键不是生产资料种类: " + entry.getKey());
              }
              out.put(kind, requireIntegral(entry.getValue(), field + "." + entry.getKey()));
            });
    return out;
  }

  /**
   * 键是 {@link AssetKind} 名、**值本身又是商品表的表**（R3 换型的那两张投入表）：{@code {"LAND":{"grain":8000}}}。
   *
   * <p>★ 三条拒因各自给出可读的中文原因：键不认识生产资料种类、值不是对象、内层值不是整数 —— 都由本层判， 数值语义（≥ 0）交给 {@code Industry}
   * 的构造期守卫（一处真相）。
   */
  private static Map<AssetKind, Map<CommodityId, Long>> assetCommodityMap(
      JsonNode object, String field) {
    Map<AssetKind, Map<CommodityId, Long>> out = new LinkedHashMap<>();
    if (object == null) {
      return out;
    }
    object
        .fields()
        .forEachRemaining(
            entry -> {
              AssetKind kind;
              try {
                kind = AssetKind.valueOf(entry.getKey());
              } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                    "字段 " + field + " 的键不是生产资料种类: " + entry.getKey());
              }
              if (!entry.getValue().isObject()) {
                throw new IllegalArgumentException(
                    "字段 " + field + "." + entry.getKey() + " 必须是商品表（对象）: " + entry.getValue());
              }
              out.put(kind, commodityMap(entry.getValue(), field + "." + entry.getKey()));
            });
    return out;
  }

  /** 键是商品 id 的定点整数表（值非整 ⇒ 抛）。 */
  private static Map<CommodityId, Long> commodityMap(JsonNode object, String field) {
    Map<CommodityId, Long> out = new LinkedHashMap<>();
    if (object == null) {
      return out;
    }
    object
        .fields()
        .forEachRemaining(
            entry ->
                out.put(
                    CommodityId.parse(entry.getKey()),
                    requireIntegral(entry.getValue(), field + "." + entry.getKey())));
    return out;
  }

  // ── E3：政府与货币发行审计的载荷解析 ─────────────────────────────────────────────────

  // ── E4c：债务合同与质押的载荷解析 ───────────────────────────────────────────────────

  /**
   * ★★ <b>E4c：顶层可选 {@code debtContracts} 数组（放开非空）</b>。
   *
   * <pre>
   * {"debtContracts":[
   *   {"debtor":"h-0_0-rural-poor_peasant","creditor":"h-0_0-rural-landlord",
   *    "unit":{"kind":"commodity","commodity":"grain"},
   *    "terms":{"interestRatePerMillePerCycle":20,"interestTiming":"AFTER_REPAYMENT_ON_CLOSE",
   *             "repaymentRule":"AVAILABLE_SURPLUS_SHARE","monetaryConversion":"NOT_ALLOWED",
   *             "defaultRemedy":"MARK_DEFAULTED"},
   *    "principal":1000,"openedDay":0,"lastInterestDay":null,"dueCycle":1,"status":"NORMAL"}]}
   * </pre>
   *
   * <ul>
   *   <li>{@code id} 可省：由 {@code (debtor, creditor, unit, terms)} 经 {@link DebtContractId#idOf} 派生；
   *       给了就必须与派生值逐字相同（否则构造期守卫拒绝，不许手写第二份身份）；
   *   <li>★★ {@code terms} <b>必填</b>（不是"省略 ⇒ legacyDefault"）：种子必须把 {@code
   *       interestRatePerMillePerCycle / interestTiming / repaymentRule / monetaryConversion /
   *       defaultRemedy} 五个键给全 —— 缺键 fail-closed，不把漏写静默补成默认档（资本化路径的 `legacyDefault` 只在结算层显式使用，并由
   *       {@code termsSource} 标注）；
   *   <li>{@code openedDay} 缺省 = 0；{@code lastInterestDay}/{@code dueCycle} 缺省 = 空；{@code status}
   *       缺省 = NORMAL；
   *   <li>单位：{@code commodity} ⇒ {@code {"kind":"commodity","commodity":"grain"}}；货币 ⇒ {@code
   *       {"kind":"money","currency":"silver"}}（恰其一，由单位类型表达）。
   * </ul>
   *
   * <p>★★ <b>结构/引用/条款校验不在这里重复实现</b>：{@link DebtContract} 与 {@code EconomyData} 的构造期守卫会判 id
   * 派生、debtor/creditor 行存在、{@code ClassRow.debts} 引用存在。
   *
   * <p>★★ <b>初始债务的配套责任（必须写清）</b>：本方法只把债权记进 economy 状态；它<b>不搬任何 actor 库存/货币/权利</b>。 如果 seed 声明"H1 欠
   * H2 1000 粮"，app 侧的 actor.Seed 协调器必须已经在 H1/H2 的账户里备好对应的真实粮/钱 （否则这条债没有对价，是凭空造出的债权名册）。economy 不自动搬
   * actor 库存，也不为演示凭空补配套。
   */
  private static Map<DebtContractId, DebtContract> debtContracts(JsonNode payload) {
    Map<DebtContractId, DebtContract> contracts = new LinkedHashMap<>();
    for (JsonNode node : optionalArray(payload, "debtContracts")) {
      if (!node.isObject()) {
        throw new IllegalArgumentException("debtContracts 的每项必须是对象: " + node);
      }
      HouseholdId debtor = HouseholdId.parse(requireText(node, "debtor"));
      HouseholdId creditor = HouseholdId.parse(requireText(node, "creditor"));
      DebtUnit unit = debtUnit(requireObject(node, "unit"));
      DebtTerms terms = debtTerms(requireObject(node, "terms"));
      long principal = requireLong(node, "principal");
      long openedDay = optionalLong(node, "openedDay", 0L);
      OptionalLong lastInterestDay = optionalOptionalLong(node, "lastInterestDay");
      OptionalLong dueCycle = optionalOptionalLong(node, "dueCycle");
      DebtStatus status =
          node.hasNonNull("status") ? debtStatus(requireText(node, "status")) : DebtStatus.NORMAL;
      DebtContractId derived = DebtContractId.idOf(debtor, creditor, unit, terms);
      if (node.hasNonNull("id")) {
        DebtContractId declared = DebtContractId.parse(requireText(node, "id"));
        if (!declared.equals(derived)) {
          throw new IllegalArgumentException(
              "debtContracts[].id 必须与四元组派生值一致（不许手写第二份身份）：声明=" + declared + " 派生=" + derived);
        }
      }
      DebtContract contract =
          new DebtContract(
              derived,
              debtor,
              creditor,
              unit,
              terms,
              principal,
              openedDay,
              lastInterestDay,
              dueCycle,
              status);
      if (contracts.putIfAbsent(derived, contract) != null) {
        throw new IllegalArgumentException("同一份载荷里债务合同 id 重复: " + derived);
      }
    }
    return contracts;
  }

  /** ★ E4c：债务单位节点（{@code kind=commodity|money}；另一维必须给且只给一个）。 */
  private static DebtUnit debtUnit(JsonNode node) {
    String kind = requireText(node, "kind");
    return switch (kind) {
      case "commodity" -> {
        if (node.hasNonNull("currency")) {
          throw new IllegalArgumentException(
              "debtContracts[].unit.kind=commodity 不得同时给 currency（空要省略）: " + node);
        }
        yield DebtUnit.commodity(CommodityId.parse(requireText(node, "commodity")));
      }
      case "money" -> {
        if (node.hasNonNull("commodity")) {
          throw new IllegalArgumentException(
              "debtContracts[].unit.kind=money 不得同时给 commodity（空要省略）: " + node);
        }
        yield DebtUnit.money(CurrencyId.parse(requireText(node, "currency")));
      }
      default ->
          throw new IllegalArgumentException(
              "debtContracts[].unit.kind 必须是 commodity 或 money: " + kind);
    };
  }

  /** ★ E4c：条款节点（五维必填；{@code dueCycle}/{@code dueDay} 可空；缺键 fail-closed，不静默补 0）。 */
  private static DebtTerms debtTerms(JsonNode node) {
    int rate = requireInt(node, "interestRatePerMillePerCycle");
    InterestTiming timing =
        enumValue(InterestTiming.class, requireText(node, "interestTiming"), "interestTiming");
    RepaymentRule repayment =
        enumValue(RepaymentRule.class, requireText(node, "repaymentRule"), "repaymentRule");
    MonetaryConversion conversion =
        enumValue(
            MonetaryConversion.class,
            requireText(node, "monetaryConversion"),
            "monetaryConversion");
    DefaultRemedy remedy =
        enumValue(DefaultRemedy.class, requireText(node, "defaultRemedy"), "defaultRemedy");
    OptionalLong dueCycle = optionalOptionalLong(node, "dueCycle");
    OptionalLong dueDay = optionalOptionalLong(node, "dueDay");
    return new DebtTerms(rate, timing, repayment, conversion, remedy, dueCycle, dueDay);
  }

  /** ★ E4c：状态词（词表外具名抛）。 */
  private static DebtStatus debtStatus(String text) {
    return enumValue(DebtStatus.class, text, "status");
  }

  /** ★ E4c：顶层可选 {@code pledges} 数组；引用与 Σ活跃质押上界由 EconomyData 构造期守卫判。 */
  private static Map<PledgeId, Pledge> pledges(JsonNode payload) {
    Map<PledgeId, Pledge> pledges = new LinkedHashMap<>();
    for (JsonNode node : optionalArray(payload, "pledges")) {
      if (!node.isObject()) {
        throw new IllegalArgumentException("pledges 的每项必须是对象: " + node);
      }
      PledgeId id = PledgeId.parse(requireText(node, "id"));
      DebtContractId debtContractId = DebtContractId.parse(requireText(node, "debtContractId"));
      AssetShareId assetShareId = AssetShareId.parse(requireText(node, "assetShareId"));
      long quantity = requireLong(node, "quantity");
      ProductionModeId modeId = ProductionModeId.parse(requireText(node, "modeId"));
      int priority = requireInt(node, "priority");
      Pledge.Status status =
          enumValue(Pledge.Status.class, requireText(node, "status"), "pledges[].status");
      Pledge pledge =
          new Pledge(id, debtContractId, assetShareId, quantity, modeId, priority, status);
      if (pledges.putIfAbsent(id, pledge) != null) {
        throw new IllegalArgumentException("同一份载荷里质押 id 重复: " + id);
      }
    }
    return pledges;
  }

  /**
   * ★★ E5a：顶层可选 {@code liquidationPolicies} 数组（缺键 ⇒ 空表）。每项：
   *
   * <pre>{@code
   * {"ruleId":"asset-rule-<mode>-<ASSET>","maxLiquidatePerMille":1000,"protectedReserve":0,
   *  "priceSource":"POLICY","policyValuePerUnitMilli":0,"recipientRule":"CREDITOR_FIRST"}
   * }</pre>
   *
   * <p>★ 取值范围由 {@link LiquidationPolicy} 构造期判死；{@code ruleId} 是否指向已存在的 {@code AssetRule} 由 {@code
   * EconomyData} 构造期守卫按"对侧已提供"分段判。同一份载荷里 {@code ruleId} 重复 ⇒ 抛（政策不是信号，不做覆盖）。
   */
  private static Map<AssetRuleId, LiquidationPolicy> liquidationPolicies(JsonNode payload) {
    Map<AssetRuleId, LiquidationPolicy> policies = new LinkedHashMap<>();
    for (JsonNode node : optionalArray(payload, "liquidationPolicies")) {
      if (!node.isObject()) {
        throw new IllegalArgumentException("liquidationPolicies 的每项必须是对象: " + node);
      }
      AssetRuleId ruleId = AssetRuleId.parse(requireText(node, "ruleId"));
      int maxLiquidatePerMille = requireInt(node, "maxLiquidatePerMille");
      long protectedReserve = requireLong(node, "protectedReserve");
      LiquidationPolicy.PriceSource priceSource =
          enumValue(
              LiquidationPolicy.PriceSource.class,
              requireText(node, "priceSource"),
              "liquidationPolicies[].priceSource");
      long policyValuePerUnitMilli = requireLong(node, "policyValuePerUnitMilli");
      LiquidationPolicy.RecipientRule recipientRule =
          enumValue(
              LiquidationPolicy.RecipientRule.class,
              requireText(node, "recipientRule"),
              "liquidationPolicies[].recipientRule");
      LiquidationPolicy policy =
          new LiquidationPolicy(
              ruleId,
              maxLiquidatePerMille,
              protectedReserve,
              priceSource,
              policyValuePerUnitMilli,
              recipientRule);
      if (policies.putIfAbsent(ruleId, policy) != null) {
        throw new IllegalArgumentException("同一份载荷里清算政策的 ruleId 重复: " + ruleId);
      }
    }
    return policies;
  }

  /**
   * ★★ E5a：顶层可选 {@code crisisSignals} 数组（缺键 ⇒ 空表）。每项：
   *
   * <pre>{@code
   * {"hex":"0_0","kind":"FOOD","severity":1,"day":120,
   *  "evidence":{"grainGap":-3},"households":["hh-…"],"classes":["poor_peasant"],"reason":"…"}
   * }</pre>
   *
   * <p>★ {@code id} 是可选键：给了必须与 {@link CrisisSignalId#idOf(HexCoord, String)} 一致（不一致 ⇒ 抛）； 不给就由
   * {@code (hex, kind)} 派生。★ {@code evidence} 的值为原始触发量（<b>可为负</b>，见 {@code HexCrisisSignal}
   * 类注）；{@code households}/{@code classes}/{@code evidence} 缺键 ⇒ 空表。 ★★ <b>同 hex 同 kind
   * 只保留最新一条</b>： 数组中后出现的项直接覆盖先前的项（覆盖即更新；本层不追加、不报重复）。
   */
  private static Map<CrisisSignalId, HexCrisisSignal> crisisSignals(JsonNode payload) {
    Map<CrisisSignalId, HexCrisisSignal> signals = new LinkedHashMap<>();
    for (JsonNode node : optionalArray(payload, "crisisSignals")) {
      if (!node.isObject()) {
        throw new IllegalArgumentException("crisisSignals 的每项必须是对象: " + node);
      }
      HexCoord hex = HexCoord.parse(requireText(node, "hex"));
      HexCrisisSignal.Kind kind =
          enumValue(HexCrisisSignal.Kind.class, requireText(node, "kind"), "crisisSignals[].kind");
      CrisisSignalId derived = CrisisSignalId.idOf(hex, kind.name());
      optionalText(node, "id")
          .ifPresent(
              id -> {
                if (!derived.equals(CrisisSignalId.parse(id))) {
                  throw new IllegalArgumentException(
                      "crisisSignals[].id 必须与 (hex, kind) 派生值一致: id=" + id + "，派生=" + derived);
                }
              });
      int severity = requireInt(node, "severity");
      long day = requireLong(node, "day");
      Map<String, Long> evidence = evidence(node);
      List<HouseholdId> households = new ArrayList<>();
      for (JsonNode household : optionalArray(node, "households")) {
        if (!household.isTextual() || household.asText().isBlank()) {
          throw new IllegalArgumentException("crisisSignals[].households 的每项必须是非空字符串: " + node);
        }
        households.add(HouseholdId.parse(household.asText()));
      }
      List<SocialClassId> classes = new ArrayList<>();
      for (JsonNode socialClass : optionalArray(node, "classes")) {
        if (!socialClass.isTextual() || socialClass.asText().isBlank()) {
          throw new IllegalArgumentException("crisisSignals[].classes 的每项必须是非空字符串: " + node);
        }
        classes.add(SocialClassId.parse(socialClass.asText()));
      }
      HexCrisisSignal signal =
          new HexCrisisSignal(
              derived,
              hex,
              kind,
              severity,
              day,
              evidence,
              List.copyOf(households),
              List.copyOf(classes),
              requireText(node, "reason"));
      // ★ 同 hex 同 kind 只保留最新一条：后出现者覆盖先出现者（覆盖即更新，见方法注释与 EconomyData 守卫）。
      signals.put(derived, signal);
    }
    return signals;
  }

  /**
   * ★★ E6a：顶层可选 {@code modeTransitions} 数组（缺键 ⇒ 空表；旧载荷逐值不变）。每项：
   *
   * <pre>{@code
   * {"organizationId":"org-…","fromModeId":"…","toModeId":"…","retainOriginalPerMille":400,
   *  "requestedDay":120,"effectiveDay":121,"status":"PENDING","reason":"…","id":"mt-…"?}
   * }</pre>
   *
   * <p>★ {@code id} 是可选键：给了必须与 {@link ModeTransitionId#idOf(ProductionOrganizationId,
   * ProductionModeId, long)} 一致（不一致 ⇒ 抛）；不给就派生。★ 同一数组里派生 id 重复 ⇒ 抛（同一请求不得写两条）。 组织/mode 引用与"同一组织至多一条
   * PENDING"由 {@code EconomyData} 构造期守卫判。
   */
  private static Map<ModeTransitionId, ModeTransition> modeTransitions(JsonNode payload) {
    Map<ModeTransitionId, ModeTransition> transitions = new LinkedHashMap<>();
    for (JsonNode node : optionalArray(payload, "modeTransitions")) {
      if (!node.isObject()) {
        throw new IllegalArgumentException("modeTransitions 的每项必须是对象: " + node);
      }
      ProductionOrganizationId organizationId =
          ProductionOrganizationId.parse(requireText(node, "organizationId"));
      ProductionModeId fromModeId = ProductionModeId.parse(requireText(node, "fromModeId"));
      ProductionModeId toModeId = ProductionModeId.parse(requireText(node, "toModeId"));
      int retainOriginalPerMille = requireInt(node, "retainOriginalPerMille");
      long requestedDay = requireLong(node, "requestedDay");
      long effectiveDay = requireLong(node, "effectiveDay");
      ModeTransition.Status status =
          enumValue(
              ModeTransition.Status.class, requireText(node, "status"), "modeTransitions[].status");
      String reason = optionalText(node, "reason").orElse("");
      ModeTransitionId derived = ModeTransitionId.idOf(organizationId, toModeId, effectiveDay);
      optionalText(node, "id")
          .ifPresent(
              id -> {
                if (!derived.equals(ModeTransitionId.parse(id))) {
                  throw new IllegalArgumentException(
                      "modeTransitions[].id 必须与 (organizationId, toModeId, effectiveDay) 派生值一致: id="
                          + id
                          + "，派生="
                          + derived);
                }
              });
      ModeTransition transition =
          new ModeTransition(
              derived,
              organizationId,
              fromModeId,
              toModeId,
              retainOriginalPerMille,
              requestedDay,
              effectiveDay,
              status,
              reason);
      if (transitions.putIfAbsent(derived, transition) != null) {
        throw new IllegalArgumentException("同一份载荷里模式变迁 id 重复: " + derived);
      }
    }
    return transitions;
  }

  /**
   * ★★ E6a：顶层可选 {@code classShares} 数组（缺键 ⇒ 空表）。每项：
   *
   * <pre>{@code
   * {"transitionId":"mt-…","householdId":"hh-…","classPositionId":"…","sharePerMille":400,"id":"cs-…"?}
   * }</pre>
   *
   * <p>★ {@code id} 是可选键：给了必须与 {@link ClassShareId#idOf(ModeTransitionId, HouseholdId,
   * ClassPositionId)} 一致（不一致 ⇒ 抛）；不给就派生。同一 {@code (transitionId, householdId)} 的 Σ =
   * 1000‰、变迁/家户/位置引用完整性由 {@code EconomyData} 构造期守卫判。
   */
  private static Map<ClassShareId, ClassShare> classShares(JsonNode payload) {
    Map<ClassShareId, ClassShare> shares = new LinkedHashMap<>();
    for (JsonNode node : optionalArray(payload, "classShares")) {
      if (!node.isObject()) {
        throw new IllegalArgumentException("classShares 的每项必须是对象: " + node);
      }
      ModeTransitionId transitionId = ModeTransitionId.parse(requireText(node, "transitionId"));
      HouseholdId householdId = HouseholdId.parse(requireText(node, "householdId"));
      ClassPositionId classPositionId = ClassPositionId.parse(requireText(node, "classPositionId"));
      long sharePerMille = requireLong(node, "sharePerMille");
      ClassShareId derived = ClassShareId.idOf(transitionId, householdId, classPositionId);
      optionalText(node, "id")
          .ifPresent(
              id -> {
                if (!derived.equals(ClassShareId.parse(id))) {
                  throw new IllegalArgumentException(
                      "classShares[].id 必须与 (transitionId, householdId, classPositionId) 派生值一致: id="
                          + id
                          + "，派生="
                          + derived);
                }
              });
      ClassShare share =
          new ClassShare(derived, transitionId, householdId, classPositionId, sharePerMille);
      if (shares.putIfAbsent(derived, share) != null) {
        throw new IllegalArgumentException("同一份载荷里阶层保留份额 id 重复: " + derived);
      }
    }
    return shares;
  }

  /** ★ E5a：{@code crisisSignals[].evidence} —— 可选对象；键非空白、值为 long（可为负）。缺键 ⇒ 空表。 */
  private static Map<String, Long> evidence(JsonNode node) {
    JsonNode evidence = optionalObject(node, "evidence");
    Map<String, Long> values = new LinkedHashMap<>();
    if (evidence == null) {
      return values;
    }
    evidence
        .fields()
        .forEachRemaining(
            field -> {
              if (field.getKey() == null || field.getKey().isBlank()) {
                throw new IllegalArgumentException("crisisSignals[].evidence 的键不得空白: " + evidence);
              }
              values.put(
                  field.getKey(), requireIntegral(field.getValue(), "evidence." + field.getKey()));
            });
    return values;
  }

  /** ★ E4c：可空整数键（缺席/null ⇒ 空；给了非整数 ⇒ 具名抛）。 */
  private static OptionalLong optionalOptionalLong(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return OptionalLong.empty();
    }
    if (!value.isIntegralNumber()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是整数或 null: " + value);
    }
    return OptionalLong.of(value.asLong());
  }

  /** ★ E4c：枚举词表解析（未知值 ⇒ 列出合法值，不 silent fallback）。 */
  private static <E extends Enum<E>> E enumValue(Class<E> type, String text, String field) {
    try {
      return Enum.valueOf(type, text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "未知的 "
              + field
              + ": "
              + text
              + "；合法值: "
              + java.util.Arrays.toString(type.getEnumConstants()),
          e);
    }
  }

  /**
   * 顶层可选 {@code governments}：{@code [{id,nationRef,treasury:{kind,id},issuable:[币种…],seignioragePerCycle?,debtIssuePerCycle?}]}。
   *
   * <p>缺键 ⇒ 空表（旧载荷没有政府 ⇒ 零登记，旧 fail-closed 行为逐字不变）；一个币种只能有一个发行主体由 {@code EconomyData} 的构造期守卫判死。
   */
  private static Map<GovernmentId, Government> governments(JsonNode payload) {
    Map<GovernmentId, Government> governments = new LinkedHashMap<>();
    for (JsonNode node : optionalArray(payload, "governments")) {
      if (!node.isObject()) {
        throw new IllegalArgumentException("governments 的每项必须是对象: " + node);
      }
      GovernmentId id = GovernmentId.parse(requireText(node, "id"));
      String nationRef = requireText(node, "nationRef");
      JsonNode treasuryNode = requireObject(node, "treasury");
      ActorRef treasury = actorRef(treasuryNode);
      Set<CurrencyId> issuable = new LinkedHashSet<>();
      for (JsonNode currencyNode : optionalArray(node, "issuable")) {
        if (!currencyNode.isTextual() || currencyNode.asText().isBlank()) {
          throw new IllegalArgumentException("governments[].issuable 的每项必须是非空币种字符串: " + node);
        }
        issuable.add(CurrencyId.parse(currencyNode.asText()));
      }
      long seignioragePerCycle = optionalLong(node, "seignioragePerCycle", 0L);
      if (seignioragePerCycle < 0L) {
        throw new IllegalArgumentException(
            "governments[].seignioragePerCycle 不得为负: " + seignioragePerCycle);
      }
      long debtIssuePerCycle = optionalLong(node, "debtIssuePerCycle", 0L);
      if (debtIssuePerCycle < 0L) {
        throw new IllegalArgumentException(
            "governments[].debtIssuePerCycle 不得为负: " + debtIssuePerCycle);
      }
      Government government =
          new Government(id, nationRef, treasury, issuable, seignioragePerCycle, debtIssuePerCycle);
      if (governments.putIfAbsent(id, government) != null) {
        throw new IllegalArgumentException("同一份载荷里政府 id 重复: " + id);
      }
    }
    return governments;
  }

  /**
   * 顶层可选 {@code moneyIssuances}：{@code
   * [{id,governmentId,day?,period?,currency,amount,kind,reason}]}。
   *
   * <p>缺 {@code day} ⇒ 取命令锚点 {@code at.tick()}；缺 {@code period} ⇒ 1（创世周期，与 {@code
   * EconomySeeder.FIRST_PERIOD} 同值，但载荷边缘不复用 app 常量）；缺键 ⇒ 空表。
   */
  private static Map<MoneyIssuanceId, MoneyIssuanceRecord> moneyIssuances(
      JsonNode payload, SimosTimestamp at, Map<GovernmentId, Government> governments) {
    Map<MoneyIssuanceId, MoneyIssuanceRecord> records = new LinkedHashMap<>();
    for (JsonNode node : optionalArray(payload, "moneyIssuances")) {
      if (!node.isObject()) {
        throw new IllegalArgumentException("moneyIssuances 的每项必须是对象: " + node);
      }
      MoneyIssuanceId id = MoneyIssuanceId.parse(requireText(node, "id"));
      GovernmentId governmentId = GovernmentId.parse(requireText(node, "governmentId"));
      if (!governments.containsKey(governmentId)) {
        throw new IllegalArgumentException(
            "发行记录指名的政府不存在（同一份载荷内）：记录=" + id + "，governmentId=" + governmentId);
      }
      long day = optionalLong(node, "day", at.tick());
      long period = optionalLong(node, "period", 1L);
      CurrencyId currency = CurrencyId.parse(requireText(node, "currency"));
      long amount = requireLong(node, "amount");
      MoneyIssuanceKind kind = issuanceKind(requireText(node, "kind"));
      String reason = requireText(node, "reason");
      MoneyIssuanceRecord record =
          new MoneyIssuanceRecord(id, governmentId, day, period, currency, amount, kind, reason);
      if (records.putIfAbsent(id, record) != null) {
        throw new IllegalArgumentException("同一份载荷里发行记录 id 重复: " + id);
      }
    }
    return records;
  }

  private static MoneyIssuanceKind issuanceKind(String text) {
    try {
      return MoneyIssuanceKind.valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "未知的 MoneyIssuanceKind: "
              + text
              + "；合法值: "
              + java.util.Arrays.toString(MoneyIssuanceKind.values()),
          e);
    }
  }

  private static JsonNode requireObject(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是对象: " + node);
    }
    return value;
  }

  // ── 形状助手 ─────────────────────────────────────────────────────────────────────────

  /**
   * {@code {"kind","id"}}：一个主体引用（{@code allocations[].actor} 与 {@code industries[].operator}
   * 共用**同一个**形状与同一套解析）。
   *
   * <p>★ {@code kind} 走 {@link ActorKind#parse} 的**词表**（词表外的种类即抛并列出合法值）、{@code id} 不得为空白 ——
   * 两句拒因的文案一字不改（消息是契约），故抽的是**解析**、不是文案。
   */
  private static ActorRef actorRef(JsonNode node) {
    return new ActorRef(ActorKind.parse(requireText(node, "kind")), requireText(node, "id"));
  }

  private static void requireEntryObject(JsonNode entry) {
    if (entry == null || !entry.isObject()) {
      throw new IllegalArgumentException("字段 entries 的元素必须是 {q,r,industries} 对象: " + entry);
    }
  }

  private static String requireText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是非空字符串: " + node);
    }
    return value.asText();
  }

  private static long requireLong(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return requireIntegral(value, field);
  }

  private static int requireInt(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是整数: " + node);
    }
    return value.asInt();
  }

  /** 布尔键（三个字段必填）：缺键 / null / 非布尔 ⇒ 抛（不把漏写静默补成 false）。 */
  private static boolean requireBoolean(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isBoolean()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是布尔值: " + node);
    }
    return value.asBoolean();
  }

  private static long optionalLong(JsonNode node, String field, long fallback) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return fallback;
    }
    return requireIntegral(value, field);
  }

  /** 可选布尔键（缺键 / JSON {@code null} ⇒ fallback；给了非布尔 ⇒ 具名抛）。 */
  private static boolean optionalBoolean(JsonNode node, String field, boolean fallback) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return fallback;
    }
    if (!value.isBoolean()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是布尔值: " + node);
    }
    return value.asBoolean();
  }

  private static int optionalInt(JsonNode node, String field, int fallback) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return fallback;
    }
    if (!value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是整数: " + node);
    }
    return value.asInt();
  }

  /**
   * 可选的文本键：缺键 / JSON {@code null} ⇒ {@link Optional#empty()}（"空"的唯二形态）。
   *
   * <p>★ <b>给了但形状不对（非文本 / 空白）⇒ 抛</b>：与 {@link #optionalObject} 同款 —— "可选"说的是**可以不给**，
   * 不是"给了什么都收"。静默把它当"没给"会让一个写错的键看起来像合法的货币规则。
   */
  private static Optional<String> optionalText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return Optional.empty();
    }
    if (!value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是非空字符串: " + node);
    }
    return Optional.of(value.asText());
  }

  private static long requireIntegral(JsonNode value, String field) {
    if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是整数");
    }
    return value.asLong();
  }

  private static JsonNode requireArray(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是数组: " + node);
    }
    return value;
  }

  private static Iterable<JsonNode> optionalArray(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return List.of();
    }
    if (!value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是数组: " + node);
    }
    return value;
  }

  /**
   * ★★ R1：顶层可选 {@code classFirst} 持久状态。缺键 ⇒ {@link ClassFirstState#empty()}（旧 payload 逐值不变）；
   * 给了但形状不对 ⇒ 交给同一条 Jackson 绑定路径 fail-closed（{@link EconomyCodec#deserializeClassFirstState}）。
   */
  private static ClassFirstState classFirst(JsonNode payload) {
    JsonNode node = optionalObject(payload, "classFirst");
    if (node == null) {
      return ClassFirstState.empty();
    }
    return EconomyCodec.deserializeClassFirstState(node);
  }

  /**
   * ★★ P10.1：顶层可选 {@code merchantFirms} 数组（缺键 ⇒ 空表）。
   *
   * <pre>
   * "merchantFirms":[{"organizationId":"org-…","tier":"PORTER","homeHex":"0_0",
   *                   "capacityPerRound":100,"capacityUsedThisRound":0,"serviceRadiusHex":2,
   *                   "ruralTradeCostPenaltyPerMille":0,"lastFeeEarnedMilli":0,
   *                   "lastUpkeepMilli":0,"lastProfitMilli":0}]
   * </pre>
   *
   * <p>★ 每项字段与 {@link MerchantFirm} 逐一对齐；{@code tier} 走枚举词表、{@code homeHex} 走 {@link
   * HexCoord#parse}。{@code serviceRadiusHex} 缺键 ⇒ 按 tier 的具名默认（2/4/8；架构 §3.2 "具名默认，GM 可调"），
   * 显式给了就以显式值为准。{@code homeIsCity} 是 §3.2 的字段但不在 P10.1 载荷必填清单里：缺键 = {@code true} （"true
   * 暂定只允许城市商号"），显式给了就按布尔值收（坏类型具名抛）。数值范围（容量 &gt; 0、used ≥ 0、penalty ∈ [0,100]、金额非负）由 {@link
   * MerchantFirm} 构造期守卫 fail-closed；同 {@code organizationId} 重复 ⇒ 抛。
   */
  private static Map<ProductionOrganizationId, MerchantFirm> parseMerchantFirms(JsonNode payload) {
    Map<ProductionOrganizationId, MerchantFirm> firms = new LinkedHashMap<>();
    for (JsonNode node : optionalArray(payload, "merchantFirms")) {
      if (!node.isObject()) {
        throw new IllegalArgumentException("merchantFirms 的每项必须是对象: " + node);
      }
      ProductionOrganizationId organizationId =
          ProductionOrganizationId.parse(requireText(node, "organizationId"));
      MerchantPolicy.MerchantTier tier =
          enumValue(
              MerchantPolicy.MerchantTier.class, requireText(node, "tier"), "merchantFirms[].tier");
      HexCoord homeHex = HexCoord.parse(requireText(node, "homeHex"));
      boolean homeIsCity = optionalBoolean(node, "homeIsCity", true);
      MerchantFirm firm =
          new MerchantFirm(
              organizationId,
              tier,
              homeHex,
              homeIsCity,
              requireLong(node, "capacityPerRound"),
              requireLong(node, "capacityUsedThisRound"),
              optionalLong(node, "serviceRadiusHex", MerchantFirm.defaultServiceRadiusHex(tier)),
              requireLong(node, "ruralTradeCostPenaltyPerMille"),
              requireLong(node, "lastFeeEarnedMilli"),
              requireLong(node, "lastUpkeepMilli"),
              requireLong(node, "lastProfitMilli"));
      if (firms.putIfAbsent(organizationId, firm) != null) {
        throw new IllegalArgumentException("同一份载荷里商号 organizationId 重复: " + organizationId);
      }
    }
    return firms;
  }

  private static JsonNode optionalObject(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是对象: " + node);
    }
    return value;
  }
}
