package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MembershipId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.id.UseRightId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.api.relation.Basis;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.api.relation.Weight;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.Membership;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.economy.model.UseRight;
import io.mosire.simos.map.hex.HexCoord;
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
 *                "participationPerMille":950,"money":0,"debts":[],
 *                "naturalNeeds":{"grain":37350},"effectiveDemand":{}}],
 *    "laborSupply":[{"group":"rural:0_0:MALE:1","period":1,"grossLaborMilli":261000,
 *                    "servedLaborMilli":0,"committedLaborMilli":0}],
 *    "allocations":[{"id":"alloc-0-farm@0_0","group":"rural:0_0:MALE:1",
 *                    "actor":{"kind":"ESTATE","id":"farm@0_0"},"activity":"farm",
 *                    "laborMilli":261000,"period":1}]}],
 *  "markets":{"0_0":{"numeraire":"silver","prices":{"grain":1,"cloth":5}}}}
 * }</pre>
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
 * 侧只在**会话工作副本**（{@code EconomyDayStepper} 的入参）里读它。★ 与 K3 的 {@code meansOfProduction} 同款理由：
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
 * <p>★ **{@code debts} 本轮只接受空数组**（§十 明确"不做债务"）：给出非空债务 ⇒ 拒，免得落下一批指向空债务表的悬空引用。
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
    String mapId = requireText(payload, "mapId");
    String rulesVersion = requireText(payload, "rulesVersion");
    JsonNode entries = requireArray(payload, "entries");
    if (entries.isEmpty()) {
      throw new IllegalArgumentException("entries 不得为空");
    }
    Map<IndustryId, Industry> industries = new LinkedHashMap<>();
    // ★★ H0：家户行是**entry 级**的（键 = (格, 居住类型, 阶层)），不再嵌在产业节点里 —— 见类注 ①。
    Map<HouseholdId, ClassRow> classes = new LinkedHashMap<>();
    // ★ R2 的两张新表：**逐格**声明（格是命令目标与权限的粒度：一条命令动的是这些格）。
    Map<PeopleLotId, LaborSupply> laborSupply = new LinkedHashMap<>();
    Map<LaborAllocationId, LaborAllocation> allocations = new LinkedHashMap<>();
    // ★ T2 的第 8 个组件：与 industries **同键**（关系的身份 = 它结算的那个产业）⇒ 逐产业一条，见下面的循环。
    Map<IndustryId, ProductionRelation> relations = new LinkedHashMap<>();
    // ★ H4 的第 9 个组件：顶层 `markets`（键 = 格串），见类注的第五处形状变化。
    Map<HexCoord, Market> markets = markets(payload, entries);
    // ★ S1 的两个新组件：可选的逐格声明；缺省 ⇒ 空表（由 EconomyData 的迁移器补齐成员份额；
    //   使用权则是"没有登记就没有权利" —— 不凭产能替谁发明权利，见 UseRight 的类注）。
    Map<MembershipId, Membership> memberships = new LinkedHashMap<>();
    Map<UseRightId, UseRight> useRights = new LinkedHashMap<>();
    Map<String, Long> useRightSequences = new LinkedHashMap<>();
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
      Map<IndustryId, Set<ResidenceKind>> residencesByIndustry = residencesOf(entryAllocations);
      for (JsonNode node : requireArray(entry, "industries")) {
        Industry industry = industry(node);
        IndustryId id = industry.id();
        // ★★ R0：**entry 格 ↔ industry.id 格必须一致**（见 requireIndustryHexMatchesEntry）——
        //   entry 只划"这条命令动哪些格"，而产业按自己的 id 格参与结算 ⇒ 不一致时命令目标与真实
        //   写入面错位。旧版从这里旁路进去（R0 的 S0.1）。
        requireIndustryHexMatchesEntry(hex, id);
        if (industries.putIfAbsent(id, industry) != null) {
          throw new IllegalArgumentException("同一份载荷里产业 id 重复: " + id);
        }
        // ★ 关系与产业**同键**（上面刚判过重复）⇒ 此处不必再判一次（判重只会是一段走不到的代码）。
        relations.put(
            id, relation(node, industry, residencesByIndustry.getOrDefault(id, Set.of())));
      }
      // ★★ **H0：该格的家户行（entry 级）** —— 每行显式带 {@code residence}，键 = (格, 居住类型, 阶层)。
      for (JsonNode row : optionalArray(entry, "classes")) {
        ClassRow classRow = classRow(hex, row);
        if (classes.putIfAbsent(classRow.id(), classRow) != null) {
          throw new IllegalArgumentException("同一份载荷里家户行重复: " + classRow.id());
        }
      }
      // ★ R2：该格各批次的劳动供给（可支配劳动的上限）—— 缺省 ⇒ 空表（与 classes 同款）。
      //   ★ 空表**不是静默兜底**：没有供给记录的批次就不可能有配额（EconomyData 的构造期守卫按月判），
      //     而没有配额的产业当日劳动为 0 —— 那是新口径的直接后果（劳动是**分配**来的），不是"忘了算"。
      for (JsonNode node : optionalArray(entry, "laborSupply")) {
        LaborSupply supply = laborSupply(node);
        if (laborSupply.putIfAbsent(supply.group(), supply) != null) {
          throw new IllegalArgumentException("同一份载荷里劳动供给重复: " + supply.group());
        }
      }
      // ★ R2：该格各批次的劳动配额（谁把多少劳动给了谁）—— ★ H0 起**已在上面先解析**（推关系的居住类型要它）。
      for (LaborAllocation allocation : entryAllocations) {
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
      // ★ S1：该格的使用权（可选；id 由载荷内确定性序号给出）。
      for (JsonNode node : optionalArray(entry, "useRights")) {
        IndustryId activity = IndustryId.parse(requireText(node, "activity"));
        JsonNode holderNode = optionalObject(node, "holder");
        if (holderNode == null) {
          throw new IllegalArgumentException("使用权的字段 holder 必须是对象: " + node);
        }
        ActorRef holder = actorRef(holderNode);
        AssetKind asset;
        try {
          asset = AssetKind.valueOf(requireText(node, "asset"));
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException("使用权的 asset 不是生产资料种类: " + node, e);
        }
        long quantity = requireLong(node, "quantity");
        UseRight.RightKind kind;
        try {
          kind = UseRight.RightKind.valueOf(requireText(node, "kind"));
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException("使用权的 kind 不是 OWNED/TENANCY/COMMUNAL: " + node, e);
        }
        String sequenceKey = activity + "|" + asset + "|" + holder + "|" + kind;
        long sequence = useRightSequences.getOrDefault(sequenceKey, 0L);
        useRightSequences.put(sequenceKey, sequence + 1L);
        UseRight useRight =
            new UseRight(
                UseRight.idOf(activity, asset, holder, kind, sequence),
                activity,
                holder,
                asset,
                quantity,
                kind);
        if (useRights.putIfAbsent(useRight.id(), useRight) != null) {
          throw new IllegalArgumentException("同一份载荷里使用权重复: " + useRight.id());
        }
      }
    }
    EconomyMeta meta =
        new EconomyMeta(mapId, at.tick(), OptionalLong.empty(), rulesVersion, Optional.empty());
    return new EconomyData(
        Optional.of(meta),
        industries,
        classes,
        Map.of(),
        Map.of(),
        laborSupply,
        allocations,
        relations,
        markets,
        // ★ M2.4：创世载荷没有在途（播种出来的世界货物都在账上；在途由市场发运产生）。
        Map.of(),
        memberships,
        useRights,
        // ★ S3 预留的第 13 个组件：创世载荷暂不声明经营者状态（空表 = 尚未登记任何状态机状态）。
        Map.of());
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

  // ── 生产关系（T2；计划 R3/R8）────────────────────────────────────────────────────────

  /**
   * 一个产业的 {@code relation}（可选键，见类注）：缺 ⇒ 按 {@code regime} 推导；给了 ⇒ 逐值采纳 + {@code operator} 一致性判死。
   *
   * <p>★ 推导时的 {@code operator} 取**产业的那个**（{@code industry.operator()}）而不是再调一次 {@link
   * RegimeOperators#defaultOperator}：缺 {@code operator} 键时两者同值，而**显式给了 operator** 时只有
   * 前者自洽（否则那条合法的载荷会被 R3 守卫自相矛盾地拒掉）。
   *
   * <p>★★ <b>H0：推导还要一个"这批家户住哪种居住类型"</b>（cohort 键的居住维）—— 它的**唯一来源是同一条 entry 的配额表** （{@link
   * ResidenceKind#ofLot}），由调用方算好传进来（本方法看不见整条 entry）。★ <b>显式给了 {@code relation} 时它不参与</b>：
   * 那时受方是载荷逐字写出的 cohort 串（自带居住段），不推导、也不校验（"载荷说什么就是什么"）。
   */
  private static ProductionRelation relation(
      JsonNode node, Industry industry, Set<ResidenceKind> residences) {
    JsonNode relationNode = optionalObject(node, "relation");
    if (relationNode == null) {
      return RegimeRelations.defaultRelation(
          industry.regime(), industry.id(), industry.operator(), residences);
    }
    JsonNode operatorNode = optionalObject(relationNode, "operator");
    ActorRef operator = operatorNode == null ? industry.operator() : actorRef(operatorNode);
    // ★★ 载荷边缘的一致性判据（R3 的第 2 条；状态层还有同一条守卫 —— 两层都在是**有意**的，见
    //   EconomySeedHandlerTest#rejectsARelationWhoseOperatorDisagreesWithTheIndustry 的类注：
    //   那里钉的是**本层**的消息，否则删掉本层不会红）。
    if (!operator.equals(industry.operator())) {
      throw new IllegalArgumentException(
          "relation.operator 必须与产业的 operator 一致（同一件事不许两处拼写）：关系="
              + operator
              + "，产业="
              + industry.operator()
              + "（产业 "
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
        industry.id(), operator, inputSupplier, rules, residualOwner, laborSource);
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
  private static Recipient recipient(JsonNode node, String what) {
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
  private static CompensationRule compensationRule(JsonNode node) {
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
            : HouseholdId.pendingLegacy(id.value()),
        actorRef(actor),
        requireText(node, "activity"),
        requireLong(node, "laborMilli"),
        requireLong(node, "period"));
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

  private static Industry industry(JsonNode node) {
    IndustryId id = IndustryId.parse(requireText(node, "id"));
    String name = requireText(node, "name");
    RegimeId regime = RegimeId.parse(requireText(node, "regime"));
    // ★★ S1 阶段 3：经营主体。**缺键 ⇒ 按 regime 推导**（裁定 R1/R2；与 progressDays / cycleLaborMilli
    //   的缺省同一处口径）。★ 推导**只在这一层**发生 —— Industry 收了 null 是抛，不是补（裁定 D1）。
    //   ★ `"operator":null` 与缺键在 Jackson 里**不可分**（optionalObject 把 isNull() 与缺键一并收成 null）
    //   ⇒ 两者都走推导。这不是漏判：与 progressDays / cycleLaborMilli **逐字相同**。形状给错（字符串 /
    //   数组）照旧**抛**（optionalObject 的那条拒因）。
    JsonNode operatorNode = optionalObject(node, "operator");
    ActorRef operatorRef =
        operatorNode == null ? RegimeOperators.defaultOperator(regime, id) : actorRef(operatorNode);
    long cycleDays = requireLong(node, "cycleDays");
    long progressDays = optionalLong(node, "progressDays", 0L);
    // ★ R3（V7）：配方的两个新分量 —— "每 1 单位规模需要多少生产资料 / 多少劳动"。
    //   ★ capacityPerUnit **必填**（它是"单位规模"的锚，没有它规模无上界）；缺键 ⇒ 空表 ⇒ 由 Industry 的构造期守卫拒。
    Map<AssetKind, Long> capacityPerUnit =
        assetMap(optionalObject(node, "capacityPerUnit"), "capacityPerUnit");
    // ★★ **H0/K3：本格该产业的产能总量**（{@code {"LAND":3100000}}）。缺键 ⇒ 空表 ⇒ 规模 0（= 本格没有产能，
    //   真档里沙漠格的 LAND = 0、人口不足一厂的格 TOOL = 0 正是这一形态）。★ 逐值允许 0；键必须是 capacityPerUnit
    //   的键的子集（否则那个数永远不会被 scaleOf 读 = 死数据）—— 那条守卫在 Industry 的构造期。
    Map<AssetKind, Long> capacityTotal = assetMap(optionalObject(node, "capacity"), "capacity");
    long laborPerUnit = optionalLong(node, "laborPerUnit", 0L);
    Map<AssetKind, Map<CommodityId, Long>> dailyInput =
        assetCommodityMap(optionalObject(node, "dailyInputPerUnit"), "dailyInputPerUnit");
    long dailyLabor = optionalLong(node, "dailyLaborPerUnit", 0L);
    Map<CommodityId, Long> output =
        commodityMap(optionalObject(node, "outputPerUnit"), "outputPerUnit");
    // ★ v2 spec §3.3：每单位生产资料**每周期一次性**投入（农业 = 每亩需种，单位毫粮/亩）。
    //   ★ R3 换型：值侧带上商品维度（{"LAND":{"grain":8000}}）⇒ 表达得了"消耗 IRON"。
    Map<AssetKind, Map<CommodityId, Long>> cycleInput =
        assetCommodityMap(optionalObject(node, "cycleInputPerUnit"), "cycleInputPerUnit");
    // ★ 本周期实际扣到的投入（**按商品**的累加器）；缺键 ⇒ 空表。负值由 Industry 的构造期守卫拒。
    Map<CommodityId, Long> cycleInputUsed =
        commodityMap(optionalObject(node, "cycleInputUsedMilli"), "cycleInputUsedMilli");
    // ★ R3a：周期累计实际劳动（缺键 ⇒ 0，旧载荷兼容：生成器不写它时按"新周期、尚未投入"）。
    long cycleLabor = optionalLong(node, "cycleLaborMilli", 0L);
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
    // ★ v2 spec §八.4：v1 的周期结算只实现了 AllocationRule.Split（小农/封建租佃/手工业）。
    //   WageFirst（资本主义工业）是后续增量 —— 必须在**播种期**拒，而不是等某个收获日才炸：
    //   那种异常会穿出协调器的 simulateWorld，让整条 AdvanceTime revision 失败。
    //   ★ T4 起 harvest **不再读** AllocationRule（分配改由 relation 规则承担）⇒ 它不再抛那个异常；
    //     本守卫因此是这条口径**唯一**的落点（比之前更要紧，不是更不要紧）。
    //   ★ 为什么拒在这里而不是 Industry 构造期：构造期拒会让 WageFirst 这个**状态形状**
    //     （spec §五 的第四种制度）变得不可表达，连带 EconomyCodecTest 的 wage_first 多态
    //     往返夹具无法构造 ⇒ 丢一条 JSON 分支的覆盖。播种期拒已堵住命令路径，且不砍覆盖。
    if (!(rule instanceof AllocationRule.Split)) {
      throw new IllegalArgumentException("v1 的周期分配只支持 AllocationRule.Split（v2 spec §八.4）：" + rule);
    }
    return new Industry(
        id,
        name,
        regime,
        cycleDays,
        progressDays,
        capacityPerUnit,
        capacityTotal,
        dailyInput,
        dailyLabor,
        laborPerUnit,
        output,
        cycleInput,
        slots,
        rule,
        cycleLabor,
        cycleInputUsed,
        operatorRef);
  }

  /**
   * ★★ <b>一条家户行</b>（H0：键 = 该 entry 的格 + 行上显式声明的 {@code residence} + {@code slot}）： {@code
   * {residence, slot, population, laborMilli, participationPerMille, money, debts, naturalNeeds,
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
   *       EconomyDayStepper} 的入参）。★ 播种那一份要**搬**过去（app 的 {@code HouseholdSeeder}）， 静默忽略它 =
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
              + "economy 侧只在 EconomyDayStepper 的会话工作副本里读它 —— 播种时请把它搬进该家户 actor 的账户）："
              + node.get("goods"));
    }
    long money = optionalLong(node, "money", 0L);
    long cycleNaturalNeedMilli = optionalLong(node, "cycleNaturalNeedMilli", 0L);
    List<DebtId> debts = new ArrayList<>();
    for (JsonNode debt : optionalArray(node, "debts")) {
      // ★ §十：本轮"不做债务"⇒ 只接受空数组（拒绝非空，免得落下一批指向空债务表的悬空引用）。
      throw new IllegalArgumentException("本轮不支持债务（debts 只接受空数组）: " + debt);
    }
    Map<CommodityId, Long> needs =
        commodityMap(optionalObject(node, "naturalNeeds"), "naturalNeeds");
    Map<CommodityId, Long> demand =
        commodityMap(optionalObject(node, "effectiveDemand"), "effectiveDemand");
    HouseholdId householdId =
        node.hasNonNull("householdId")
            ? HouseholdId.parse(requireText(node, "householdId"))
            : HouseholdId.ofSeed(hex, residence, slot);
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

  /**
   * ★★ <b>产业 → 供给它的那些批次的居住类型集合</b>（{@link RegimeRelations#defaultRelation} 的第四参；见类注末段）。
   *
   * <p>★ <b>两个来源都是既有的唯一拼写点</b>：产业段 = {@code allocation.actor().id()}（构造期守卫判死"产业型主体必须指名已存在的产业"）、
   * 居住类型 = {@link ResidenceKind#ofLot}（批次前缀的唯一拼写点）。本方法**不新增任何约定**。
   *
   * <p>★ 一条配额都没有的产业**不出现在表里**（调用方按空集处理 ⇒ 不产生 cohort 规则）。
   */
  private static Map<IndustryId, Set<ResidenceKind>> residencesOf(
      List<LaborAllocation> allocations) {
    Map<IndustryId, Set<ResidenceKind>> byIndustry = new LinkedHashMap<>();
    for (LaborAllocation allocation : allocations) {
      byIndustry
          .computeIfAbsent(
              new IndustryId(allocation.actor().id()), ignored -> new LinkedHashSet<>())
          .add(ResidenceKind.ofLot(allocation.group()));
    }
    return byIndustry;
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

  private static long optionalLong(JsonNode node, String field, long fallback) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return fallback;
    }
    return requireIntegral(value, field);
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
