package io.mosire.simos.app.world;

import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.HouseholdLaborTimeTable;
import io.mosire.simos.economy.model.IndustryClassSlots;
import io.mosire.simos.economy.model.MerchantPolicy;
import io.mosire.simos.economy.model.MerchantTradeTemplate;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.social.provisioning.SocialProvisioning;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.economy.ProportionalSplit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 创世世界的平衡参数与纯换算。
 *
 * <p>本类是 {@link EconomySeeder} 的参数基类：所有 {@code EconomySeeder.CONST} 仍按原名可读，但声明集中到这里，
 * 让门面只剩创世入口与载荷装配。拆分只移动声明/方法，不改任何数值与初始化文本顺序。
 */
class WorldBalanceSeeder {

  static final org.slf4j.Logger LOG = io.mosire.simos.app.AppLog.shell();
  public static final String PRODUCTION_RUNTIME_SEED_REASON =
      "production-runtime:seedClassStanding";

  /** 农业产业种类标签（{@link IndustryHexKeys} 的前缀）。 */
  public static final String FARM = "farm";

  /** 手工业产业种类标签（城市格追加，§十）。 */
  public static final String CRAFT = "craft";

  /** ★★ **农村家庭纺织**的产业种类标签（R3 的 T4；每个**农村**格一个）。 */
  public static final String WEAVE = "weave";

  /** ★★ <b>城市贸易/承运产业标签</b>（P11.7/D-024；每个有城镇人口的格一个）：制度 = {@link RegimeOperators#MERCHANT}。 */
  public static final String TRADE = "trade";

  /**
   * ★★★ <b>D-032 W5-E：创世方案显式声明的资产权利接受面</b>（production 的 {@code
   * IndustryDefinitionView.acceptedRightKinds}）。
   *
   * <p>★★ <b>为什么必须显式写</b>：production 解析器对缺键按<b>空集</b>解析（B2c 冻结语义）—— 空集 = 不接受任何资产权利 ⇒ 采用算法在 {@code
   * asset-right-kind-missing} 处具名拒绝，所有生产日都会停。因此每个 seed 方案都必须按它实际物化的份额 kind 列出接受面：主 unit 的 {@code
   * capacity} 恒 {@code OWNED}；农业/手工业的家户副 unit 是 {@code TENANCY}；家庭纺织的家户副 unit 是 {@code
   * OWNED}；铸币与官署只物化 {@code OWNED}。
   *
   * <p>★ <b>唯一拼写点</b>：两个常量覆盖全部 seed 方案，四处 payload 构造点只引用它们，避免逐点漂移。值 = wire 词表名 （{@link
   * AssetRightKind#name()}），不是枚举对象。
   */
  public static final List<String> SEED_ACCEPTED_RIGHT_KINDS_OWNED =
      List.of(AssetRightKind.OWNED.name());

  /** 见 {@link #SEED_ACCEPTED_RIGHT_KINDS_OWNED}：同时接受自营与租佃份额的方案（farm / craft）。 */
  public static final List<String> SEED_ACCEPTED_RIGHT_KINDS_OWNED_OR_TENANCY =
      List.of(AssetRightKind.OWNED.name(), AssetRightKind.TENANCY.name());

  /** ★★ 每 1 单位贸易规模的劳动（千分劳动/日）：{@code 1000} = 1 个标准劳动（与作坊口径同量级）。 */
  public static final long LABOR_MILLI_PER_TRADE_UNIT = MerchantTradeTemplate.LABOR_MILLI_PER_UNIT;

  /**
   * ★★ <b>A1（2026-10-10）：每 1 单位贸易规模每周期产出的**运输服务**（商品单位 / 规模·周期）：{@code 1}</b> —— {@code trade@hex}
   * 的 {@code outputPerUnit = {haul: 本值}}（设计书 §3.2）。
   *
   * <p>★★ <b>量纲（★ 改数之前先读这一行）</b>：{@code IndustryDefinitionView.outputPerUnit} 的值侧恒是**商品单位** （{@code
   * EconomySettlement.harvest}：{@code 规模 × 值 × MILLI_PER_COMMODITY_UNIT} = 毫单位），<b>不是</b>毫单位 —— 与
   * {@code cycleInputPerUnit}（毫单位/规模）**刻意不同**（见 {@code IndustryDefinitionView} 类注的"量纲陷阱"）。 ⇒ 本常量 1
   * = <b>1,000 毫运输服务 = 1,000 毫商品·程</b>；"毫商品·程"是 {@code CapacityDemand}/{@code MerchantCapacity}
   * 的既有**运力**口径 ⇒ **不新造量纲**（用户 2026-10-10 口径「运力就是每一笔长途商品需要额外支付的……」，设计书 §3.3）。
   *
   * <p>★★ <b>取值理由（两条独立口径给出同一个数；★ W7-fix 起两处量级已随"作业资本 = 工具"重算，见下）</b>：
   *
   * <pre>
   * ① 与**旧的每城运力口径同量级**（W7 口径，留痕）：100 规模 × 1,000 毫 = **100,000 毫运输服务 / 城·周期**
   *    = MerchantPolicy.CITY_CAPACITY_CEILING（= 旧创世常量 MERCHANT_CAPACITY_PER_CITY = 100,000，M-A1 类注引的同一个数）。
   *    ★★ W7-fix：规模锚不再是"100 头牲口"而是"该户的作业资本（工具）"⇒ 规模由
   *       min(劳动那一路, 投入满足率那一路) 决定：12,000 毫工具 ÷ 100 毫/规模·周期 = **120 单位**
   *       （工具禀赋恰好是一周期工具投入 ⇒ 与①同量级，读数需实跑复核）。
   * ② 与**本产业自己已声明的工具投入配套**：cycleInputPerUnit = 100 毫工具/规模·周期；
   *    ★★ A3 起该工具量**只由产业投入这一套**表达（{@link #HAUL_TOOL_PER_MILLE_OF_SERVICE} = 100‰ = 每 1,000 毫服务
   *    耗 100 毫工具）⇒ 它等价于旧口径的 **10 趟/周期**（10 趟 × 一趟 1,000 毫工具）
   *    ⇒ 10 趟 × (一趟承运 10 商品单位 = 10,000 毫商品) = **100,000 毫运输服务/周期**（与①逐值相同）。
   * </pre>
   *
   * <p>★ <b>为什么一趟按 10 商品单位算</b>：旧 {@code MerchantHaul} 类注的既有标定（现由 {@link #GOODS_UNITS_PER_HAUL} =
   * 10 承载）——"一趟的规模锚 = 承运 10 商品单位 （10,000 毫商品 = 一个人一个周期的口粮量）"；而 {@code CapacityDemand} 的**缺省耗用**是 1
   * 毫商品 = 1 运力单位（1000‰） ⇒ 一趟的服务量 = 10,000 毫商品·程。两条既有标定在这里恰好闭合，故本常量不是新拍的数。
   *
   * <p>★ <b>毛产与净产</b>：本值是**毛产**；入账前的 3% 损耗走既有口径 （{@code EconomySettlement.FEED_PER_MILLE} = 0 +
   * {@code DEPRECIATION_PER_MILLE} = 30）⇒ 家户账实收 = 97%。
   *
   * <p>★★ <b>A3 已做（2026-10-10）：工具消耗单套化</b>——{@code cycleInputPerUnit} 的 100/规模·周期 与旧 {@code
   * MerchantHaul.TOOL_MILLI_PER_HAUL} 的 1,000/趟**两套并存**已收敛为一套（前者），详见 {@link
   * #HAUL_TOOL_PER_MILLE_OF_SERVICE}。
   */
  /// ★★ W7：值改由 {@link MerchantTradeTemplate#HAUL_PER_UNIT_CYCLE} 提供（唯一拼写点；值 = 1，一字未改）。
  public static final long HAUL_PER_TRADE_UNIT_CYCLE = MerchantTradeTemplate.HAUL_PER_UNIT_CYCLE;

  /**
   * ★★★ <b>A3（2026-10-10）：跑商的工具消耗 —— <b>全仓唯一一套口径</b>：每 1 毫运输服务消耗 {@code 100} 毫工具（‰）</b>。
   *
   * <p>★★ <b>用户原话（2026-10-10，逐字，设计书 §1）</b>：「运力作为特殊商品不可储存转卖，只能计算后在生产环节统一兑现、
   * 自动算收益」／「跑商不是生产方式吗？难到家户不会给预估生产方式预留生产资料吗？」
   *
   * <p>★★ <b>为什么是这一套（设计书 §3.2 的冻结契约）</b>：A1/A2 期间跑商的工具消耗**并存两套**—— ① 产业周期投入 {@code
   * cycleInputPerUnit = {tool:{tool:100}}}（= 每 1 规模单位·周期 100 毫工具， 见 {@link
   * #TOOL_MILLI_PER_TRADE_UNIT_CYCLE}；★ W7-fix 前该分路键是 CATTLE）；② 市场轮里"每趟跑商烧 1,000 毫工具" （旧 {@code
   * MerchantHaul.TOOL_MILLI_PER_HAUL}）。两套同时扣工具就是**双扣**（设计书 v1.1 的批次边界因此把 "单套化"归 A3）。A3 按设计书 §3.2
   * 取<b>产业投入</b>那一套：它走**标准生产管线**（ {@code IndustryDefinitionView.cycleInputPerUnit} → {@code
   * IndustryDefinitionView.inputPerUnit()} → 现扣 + 挂单保留 + 补货需求， 与农业留种、作坊吃工具**同一条路**）⇒
   * 工具不再是跑商私有的市场轮门槛，而是普通的生产资料（I-H6）。
   *
   * <p>★★ <b>本值不是新拍的数（与旧趟耗逐值可对上）</b>：
   *
   * <pre>
   * 一趟的既有规模锚 = 承运 10 商品单位 = 10,000 毫商品 = 10,000 毫服务（{@code CapacityDemand} 缺省耗用 1 毫商品 = 1 毫服务）
   * 旧趟耗          = 1,000 毫工具 / 趟
   * ⇒ 工具占服务 = 1,000 ÷ 10,000 = **100‰**（= 本常量）
   * ⇒ 每 1 规模单位·周期产出 HAUL_PER_TRADE_UNIT_CYCLE(1 商品单位 = 1,000 毫服务) ⇒ 工具投入 = 1,000 × 100‰ = 100 毫工具
   * 两条既有标定在这里**恰好闭合**（A1 已把这条闭合写进 HAUL_PER_TRADE_UNIT_CYCLE 的类注）⇒ 单套化**不改**任何标定值。
   * </pre>
   *
   * <p>★ 它是"工具维"的**唯一**拼写点：{@link #TOOL_MILLI_PER_TRADE_UNIT_CYCLE} 与 {@link
   * #MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI} 都由它派生（改它 = 改整个跑商的工具强度）。
   */
  public static final long HAUL_TOOL_PER_MILLE_OF_SERVICE = 100L;

  /**
   * ★★ <b>每 1 单位贸易规模每周期消耗的工具（毫工具 / 规模·周期）：{@code 100}</b>—— 跑商家户的 <b>工具补货路径</b>，也是 A3
   * 起跑商工具消耗的**唯一**落点（产业周期投入）。
   *
   * <pre>
   * 为什么工具需求必须走这一条**周期投入**：
   *   家户的购买需求只有两条来源 —— ① Social 的自然需求（{@code naturalNeeds}，只含口粮一类生活消费品）
   *   ② **生产投入**（{@code IndustryDefinitionView.cycleInputPerUnit}）。{@code trade} 改前两者都没有 ⇒ 跑商家户**不会自发买工具**
   *   ⇒ 创世那 12 趟烧完即停。★ 本常量让工具需求走**既有生产投入需求路径**（{@code MarketDemandBook} 汇总 →
   *   家户挂买单 → 成交补货），**不改 Social 的自然需求权威（I-C6）、不给家户造第二本需求**。
   *
   * ★★ A3 起它由 {@link #HAUL_TOOL_PER_MILLE_OF_SERVICE} 派生（**不是**第二个手写的数）：
   *   本值 = HAUL_PER_TRADE_UNIT_CYCLE（1 商品单位 = MILLI_PER_COMMODITY_UNIT 毫服务/规模·周期）
   *        × HAUL_TOOL_PER_MILLE_OF_SERVICE ÷ 1000 = 1 × 1,000 × 100 ÷ 1000 = **100 毫工具/规模·周期**。
   *
   * 量级（供核对；{@code cycleInputPerUnit} 的值侧是"每 1 单位规模"每周期，实际需求 = 值 × 规模）：
   *   ★★ W7-fix2（2026-10-11，**当下的口径**）：规模锚 = {@code trade.capacityPerUnit = {SMALL_TOOL:
   *   MerchantTradeTemplate.TOOL_PER_UNIT}}（**120**）与播种的作业资本（{@code capacity = {SMALL_TOOL:
   *   MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI}} = 12,000 毫工具）⇒ 规模上界 = ⌊12,000 ÷ 120⌋ = **100 单位**；
   *   **真正**的规模 = min(劳动那一路, 投入满足率那一路)，而每 1 单位规模·周期要 100 毫工具
   *   ⇒ 100 单位规模每周期工具投入 = 100 × 100 = **10,000 毫工具 = 10 商品单位**；创世禀赋 12,000 毫工具
   *   （= 12 商品单位）**留 2,000 毫工具余量**（= 2 商品单位），与手工业一座作坊每周期毛产
   *   {@link #TOOL_PER_WORKSHOP_PER_CYCLE} 件 = 5,000 毫工具同一量级。
   *   ★★★ <b>锚与周期投入**不是同一个量、不要求相等**</b>：锚 = 120 毫工具 / 1 单位规模 = 每 1 单位规模
   *   **压住**的作业资本（**存量** / 资本占用，见 {@code MerchantTradeTemplate.TOOL_PER_UNIT}）；周期投入 =
   *   100 毫工具 / 规模·周期 = 每 1 单位规模每周期**烧掉**的（**流量**，见
   *   {@link MerchantTradeTemplate#TOOL_MILLI_PER_UNIT_CYCLE}）。⇒ W7-fix 那条"12,000 毫恰好是一个周期
   *   要烧掉的量"的自洽性**已被刻意放弃**（锚取值改为保住 100 单位规模，代价如实记在
   *   {@code MerchantTradeTemplate.TOOL_PER_UNIT} 的类注里）。
   * </pre>
   *
   * <p>★ <b>A3 起"每周期"如何落到"每次承运"（口径说明，不是第二套数）</b>：产业按规模投入工具、按规模产出运输服务； 服务卖出多少由市场决定（不可储存、成交即消耗 —— 见
   * {@code HaulService} 类注）。因此工具消耗**不再按趟计**， 而是与产出同口径地按规模·周期计 ——
   * 这正是"跑商是一种生产方式"的字面含义（投入在产业侧，收益在市场侧）。
   */
  /// ★★ W7（2026-10-11）：**值改由 {@link MerchantTradeTemplate#TOOL_MILLI_PER_UNIT_CYCLE} 提供**（唯一拼写点；
  //  值逐值不变 = 12,000 毫工具，原算式 12 趟 × 10 商品单位 × 1,000 毫 × 100‰ ÷ 1000 见那个常量的类注）。
  //  ★★ W7-fix：它同时是"作业资本那一维"的参照（模板 {@code capacityPerUnit = {SMALL_TOOL: TOOL_PER_UNIT}}）。
  public static final long TOOL_MILLI_PER_TRADE_UNIT_CYCLE =
      MerchantTradeTemplate.TOOL_MILLI_PER_UNIT_CYCLE;

  /**
   * ★★ <b>A1：{@code trade@hex} 分配模板里**生产资料**那一侧的权重（‰）</b>：{@code 700}。
   *
   * <p>★ <b>为什么从 1000 改成 700</b>：改前该产业的分配模板是 {@code meansWeightPerMille=1000 /
   * laborWeightPerMille=0} —— 劳动权重 0 意味着"劳动不参与分配"，与"跑商是一种生产方式（要人干）"自相矛盾（用户 2026-10-10
   * 原话「跑商不是生产方式吗？」）。 改后劳动权重 = {@link #TRADE_SPLIT_LABOR_PER_MILLE}（两权重之和恒 = 1,000，由 {@code
   * AllocationRule.Split} 构造期判）。
   *
   * <p>★ <b>为什么取 700/300 而不是别的比例</b>：与**农业**（{@code agriculture(...)}：700/300）同档 —— 两者都是
   * **资本（生产资料）主导**的产业（农业 = 土地、承运 = 畜力/运力资产）；劳动主导的两档是纺织（300/700）与作坊（400/600）。 ⇒ 沿用既有档位、不新造第二套比例。
   *
   * <p>★★ <b>如实记的口径边界</b>：{@code AllocationRule.Split} 在本仓是**旧口径**（见 {@code EconomySettlement} 类注
   * "旧口径的 {@code AllocationRule.Split}"）—— 当前生产结算走 relation（{@code ProductionRules}），不读这两个权重 ⇒
   * <b>本改动不改任何数值</b>，它是"产业模板与它声明的制度自相一致"的形状修正。★ 跑商劳动的**真实约束**在 {@code
   * IndustryDefinitionView.laborPerUnit}（= {@link #LABOR_MILLI_PER_TRADE_UNIT} = 1,000
   * 千分劳动/规模，**改前就已 &gt; 0**， 由 {@code LaborQueueBook.offer} 读）—— 设计书 §3.2 把"laborWeightPerMille =
   * 0"读成"不施加劳动约束"是把上述两个字段混了， 已具名记进账本（§4 D-A1-2），本批不因此改 {@code laborPerUnit}（它本来就非 0）。
   */
  /// ★★ W7：值改由 {@link MerchantTradeTemplate#SPLIT_MEANS_PER_MILLE} 提供（唯一拼写点；值 = 700，一字未改）。
  public static final int TRADE_SPLIT_MEANS_PER_MILLE = MerchantTradeTemplate.SPLIT_MEANS_PER_MILLE;

  /**
   * ★★ <b>A1：{@code trade@hex} 分配模板里**劳动**那一侧的权重（‰）</b>：{@code 300}（理由见 {@link
   * #TRADE_SPLIT_MEANS_PER_MILLE}）。
   */
  /// ★★ W7：值改由 {@link MerchantTradeTemplate#SPLIT_LABOR_PER_MILLE} 提供（唯一拼写点；值 = 300，一字未改）。
  public static final int TRADE_SPLIT_LABOR_PER_MILLE = MerchantTradeTemplate.SPLIT_LABOR_PER_MILLE;

  /** ★★ 创世商号的层级（GM 默认）：{@link MerchantPolicy.MerchantTier#PORTER}（三档里最小的一档）。 */
  public static final MerchantPolicy.MerchantTier MERCHANT_TIER =
      MerchantPolicy.MerchantTier.PORTER;

  /**
   * 农业制度：**领主自营庄园**（spec §六 的 {@code feudal} 档）。★ 本注原写"封建租佃"——**同词两义**：租佃（佃农家户）是另立的 {@code tenant}
   * 档（S1 阶段 3 D7）。
   */
  public static final String REGIME_FEUDAL = "feudal";

  /** 手工业制度（§十"制度"行）。 */
  public static final String REGIME_HANDICRAFT = "handicraft";

  /** ★★ **家户自给**制度（R3 的 T4）：农村家庭纺织的制度标签 —— 它**不是**庄园也不是作坊，是"同一批人农闲织布" （spec §四 的压力测试），故单列一档。 */
  public static final String REGIME_HOUSEHOLD = "household";

  /// ★★ W7：值改由 {@link MerchantTradeTemplate#CYCLE_DAYS} 提供（唯一拼写点；值 = 120，一字未改）——
  //  按需创建的 {@code trade} 必须与创世那一份同周期。
  public static final int CYCLE_DAYS = MerchantTradeTemplate.CYCLE_DAYS;

  /**
   * ★★ **创世配额的发放周期**（R2，设计稿 §四）：{@code 1}。
   *
   * <p>★ **为什么是常量而不是"世界当前周期"**：{@code EconomyMeta} 的 {@code lastClosedCycle} 在创世为空 ⇒ 正在进行的周期恒为
   * {@code 0 + 1 = 1}；而且**配额与供给必须同期**（{@code EconomyData} 的构造期守卫按月判），两者由同一条载荷给出 ⇒
   * 无论哪一国先播、隔多久播，这个数都自洽。★ 本轮配额**常设**（跨周期不变），"按周期重发配额"是后续轮次的命令。
   */
  public static final long FIRST_PERIOD = 1L;

  /**
   * 每格土地基准（亩）：**量纲标定值**（v2 spec §10.3 定案 A，由 1,000 改来）。
   *
   * <p>★ 依据（★ V5 后的账：口粮口径改为"每人每 120 天 10 粮"的累计差分，残差不丢）：真档每格 14,806 人 × 10,000 毫粮/周期 = 148,060
   * 粮/格/周期需粮；**原 67 粮/亩口径**：毛产 3,100 × 67 = 207,700，扣折旧 3% ⇒ 净 201,469，再减**播种日**扣的种子 24,800 ⇒ 可用
   * 176,669 ⇒ 自给率 **119.3%**。★★ <b>2026-10-20 用户历史农业基准的简单预设改动（{@link
   * #GRAIN_OUTPUT_PER_MU}=48、{@link #SEED_MILLI_PER_MU}=12,000）</b>：毛产 3,100 × 48 = 148,800，扣折旧 3%
   * ⇒ 144,336，再减种子 3,100 × 12 = 37,200 ⇒ 可用 **107,136** ⇒ 自给率 **72.4%**。★ 上一版 34 粮/亩的 52.3% 计算见 git
   * 历史。★ 格面积是**纯经济假设** （{@code HexCell} 只存 height），与地图无关。
   */
  public static final long MU_PER_HEX = 3_100L;

  /** 千分亩/亩（土地的量纲是千分亩，§7）。 */
  static final long MILLI_MU_PER_MU = 1000L;

  /**
   * 满可耕地的产能档（= {@code TerrainCatalog} 里平原的 {@code food}）：{@link #arablePerMilleOf} 的分母。
   *
   * <p>★ 有一条用例把它钉到 map（`fullArableFoodMatchesTheCatalogPlain`）：map 改了平原产能，这里就要红。
   */
  static final int FOOD_AT_FULL_ARABLE = 3;

  /**
   * 农业每亩毛产（粮）：**量纲标定值**（v2 spec §10.3 定案 A，由 7 改来）。
   *
   * <p>★★ <b>2026-10-20 用户历史农业基准的简单预设改动</b>：每亩毛产 **48 粮**（旧值 34；用户基准为每亩年毛产 48 kg，本轮仍按现有 120
   * 天周期计算，不把周期改成 360 天）。67 → 34 的历史标定过程见 v2 spec §10.3 与 git 历史，不在本文件回写。
   */
  public static final long GRAIN_OUTPUT_PER_MU = 48L;

  /**
   * ★★ **每亩需种**（**毫粮/亩** = 12 粮/亩）：真档播种器写进 {@code cycleInputPerUnit[LAND]} 的值（v2 spec §3.3； 用户
   * 2026-09-25 裁定「现定」，计划 2 的「修订与新增」）。
   *
   * <p>★★ <b>2026-10-20 用户历史农业基准的简单预设改动</b>：每亩播种 **12,000 毫粮**（旧值 8,000；用户基准为每亩播种 12 kg，本轮仍按现有 120
   * 天周期计算）。与标定值对照：满种 = 3,100 亩 × 12 粮/亩 = **37,200 粮/格/周期**，对毛产 3,100 × {@link
   * #GRAIN_OUTPUT_PER_MU} = **148,800 粮/格/周期** 之比 = **1:4**。★ 该比值低于前现代留种率约 1:6 ~ 1:11
   * 的下界——这是刻意的（要造清偿压力），留种率校准点见 {@code 2026-10-02-open-bugs}。
   *
   * <p>★★ **口径（别混量纲）**：这是**毫粮/亩**（与 {@code outputPerUnit} 的「粮/亩」、结算里按**亩**算的口径同侧）； {@code
   * IndustryDefinitionView.capacity} 的 {@code LAND} 是**千分亩**——播种步先 {@code / 1000} 换成亩再乘（差 1000 倍）。
   *
   * <p>★ 它让真档**第一次真的读到第三路瓶颈**（v2 spec §三 的 `seedCapMu`）：此前 {@code cycleInputPerUnit} 是空 map ⇒
   * 播种步一字不扣 ⇒ 真档行为与 V2 逐值一致，但三路瓶颈在 799 格真实世界里**看不见**。
   */
  public static final long SEED_MILLI_PER_MU = 12_000L;

  // ── R3（V7 通用生产）的配方参数：三张配方（农业 / 家庭纺织 / 城市作坊）──────────────────

  /**
   * ★★ **每亩需要的劳动**（千分劳动）：{@code 143} = {@code ⌈1000 ÷ 7⌉}（{@code 旧结算引擎（R3a 已删除）.LAND_MU_PER_LABOR}
   * = 7）。
   *
   * <p>★★ **为什么是倒数、为什么取整**：V2 的口径是"1 标准劳动（1000 千分劳动）经营 7 亩"（乘法），而 {@code
   * ProductionRecipe.laborPerUnit} 的口径是"每 1 单位规模需要多少劳动"（除法），两者互为倒数，而 {@code 1000 ÷ 7 = 142.857}
   * 不是整数 ⇒ 取**向上取整 143**（比旧口径**略紧**：每 7 亩要 1001 千分劳动而不是 1000）。 ★ **后果只落在"劳动是瓶颈"的格上**：真档的分母是土地（劳动可经营
   * 51,646 亩 ≫ 3,100 亩），故真档收获一分不动。
   */
  /** ★ R3a：一名劳动的耕地当量（亩/人）；旧 {@code 旧结算引擎（R3a 已删除）.LAND_MU_PER_LABOR} 的同值搬移。 */
  public static final long LAND_MU_PER_LABOR = 7L;

  /**
   * ★★ <b>每亩需要的劳动（千分劳动）</b>：{@code ⌈1000 ÷ LAND_MU_PER_LABOR(=7)⌉ = 143}。
   *
   * <p>★ R3a：旧 {@code 旧结算引擎（R3a 已删除）.LABOR_MILLI_PER_MU} 常量已随旧结算引擎删除，这里按同一条算式就地 保留（唯一拼写点仍在 {@link
   * #LAND_MU_PER_LABOR}）；逐值 = 143，农业配方不变。
   */
  public static final long LABOR_MILLI_PER_MU =
      (1000L + LAND_MU_PER_LABOR - 1L) / LAND_MU_PER_LABOR;

  /**
   * ★★ **农田的纤维副产**（单位纤维/亩）：**6**。
   *
   * <p>★ **依据**：亚麻/秸秆是粮食作物的副产物，不占单独的地（spec §四 的 T4："**纤维内生于土地**（不凭空造）"）。 6 单位/亩 是与"每亩 {@link
   * #GRAIN_OUTPUT_PER_MU} 粮"（2026-10-20 预设为
   * 48）同量级的**拍出来的假设**（与前现代亩产的秸秆/纤维量级相符），它的作用是让"田里**同时**出粮与纤维"在真档里看得见。
   *
   * <p>★ 改它 = 改产出结构 ⇒ 记入 productionRuntimeRulesVersion()。
   */
  public static final long FIBER_OUTPUT_PER_MU = 6L;

  /**
   * ★★ **每多少农村人有一台织机**（创世场景参数）：**20**。
   *
   * <p>★ 依据：前现代农村"男耕女织"——织布是**家户副业**，一台织机服务一户（一户约 5 口，而参加纺织的是其中一部分人）； 取 20
   * 是为了让**织机、纺织劳动、纤维**三路瓶颈落在同一量级（见 {@code EconomyRealScaleClothTest} 的实测）， 从而"最紧约束"这条判据在真档里真的被走到。
   */
  public static final long RURAL_CAPITA_PER_LOOM = 20L;

  /** ★ 每台织机每周期需要的劳动（千分劳动）：**1000**（= 1 个标准劳动，全职织布）。 */
  public static final long LABOR_MILLI_PER_LOOM = 1_000L;

  /** ★ 每台织机每周期产布（匹）：**30**（= 每 4 天 1 匹，前现代手工织机的量级）。 */
  public static final long CLOTH_PER_LOOM_PER_CYCLE = 30L;

  /**
   * ★★ **每多少城市人有一座作坊**（创世场景参数）：**50**。
   *
   * <p>★ 依据：城市手工业是**专业化**生产（与农村副业不同），一座作坊（含工具、场地、匠人）服务约 50 人。
   */
  public static final long URBAN_CAPITA_PER_WORKSHOP = 50L;

  /** ★ 每座作坊每周期需要的劳动（千分劳动）：**1000**（= 1 个标准劳动）。 */
  public static final long LABOR_MILLI_PER_WORKSHOP = 1_000L;

  /** ★ 每座作坊每周期产布（匹）：**60**（专业化生产 ⇒ 约农村织机的两倍）。 */
  public static final long CLOTH_PER_WORKSHOP_PER_CYCLE = 60L;

  /** ★ 每座作坊每周期产工具（件）：**5**。 */
  public static final long TOOL_PER_WORKSHOP_PER_CYCLE = 5L;

  /** ★★ **每匹布需要多少纤维**（毫纤维/匹）：**1,000**（= 1 单位）。织机与作坊共用同一条口径（同一门手艺）。 */
  public static final long FIBER_MILLI_PER_CLOTH = 1_000L;

  /** ★★ **每件工具需要多少铁**（毫铁/件）：**2,000**（= 2 单位）。★ H5 起**没有配方读它**（见下）。 */
  public static final long IRON_MILLI_PER_TOOL = 2_000L;

  /**
   * ★★ <b>一座作坊每周期消耗的工具（毫工具 / 座·周期）：2,000（= 2 件）</b>—— H5 ④ 的出厂参数（GM 可调）。
   *
   * <pre>
   * 一座作坊一个周期的工具账（单位：毫工具；{@code 1 件 = 1000 毫}）：
   *   毛产   = TOOL_PER_WORKSHOP_PER_CYCLE(5 件) × 1000                    = 5,000
   *   损耗   = 毛产 × (FEED 0‰ + DEPRECIATION 30‰)                          =   150
   *   净产   = 5,000 − 150                                                  = 4,850
   *   投入   = TOOL_MILLI_PER_WORKSHOP_CYCLE(2 件) × 1000                   = 2,000
   *   ★ 净产出 = 4,850 − 2,000 = **+2,850 毫工具 / 座·周期 ≥ 0**            ⇒ 工具存量**可再生**
   * </pre>
   *
   * <p>★★ <b>改前是什么样</b>（H5 ④ 要消灭的那个外生断点）：作坊的投入是<b>铁</b>（{@code TOOL_PER_WORKSHOP_PER_CYCLE ×
   * IRON_MILLI_PER_TOOL = 10,000 毫铁/座·周期}），而**本仓没有任何冶炼流程** ⇒ 铁只能来自创世给的那一箱 ⇒ 用完（实测第 2
   * 个周期）作坊**永久停工**、工具产量归 0（{@code EconomyRealScaleClothTest} 实测：第 240 天作坊布产 =
   * 0）。改成"工具自产自用"之后，作坊吃的是**它自己产出的工具**（净产为正）⇒ 只要它开得起来，就一直开得下去。
   *
   * <p>★ <b>铁的去处</b>（用户裁定"铁留作留位"）：它仍是词表里的商品、仍由创世给城镇家户一份库存、仍在读口的商品清单里 （{@link
   * EconomyVocabulary#allCommodityIds()}）—— <b>但有话直说：本批没有任何配方读它</b>（留位，不是"在用"）。 ★ 取 0 ⇒
   * 作坊不再吃工具（工具只增不减）；取 ≥ 4,850 ⇒ 净产出为负（工具存量**净消耗** ⇒ 又变回"用完停工"， 只是把铁换成了工具）。
   */
  public static final long TOOL_MILLI_PER_WORKSHOP_CYCLE = 2_000L;

  /**
   * ★ <b>一趟跑商的既有规模锚（商品单位 / 趟）：{@code 10}</b> —— 只作上面两个具名常量的推导因子与文档口径， **不再有任何运行期判据读它**（A3
   * 起"趟"不是计价/门槛单位：工具按产业周期投入、服务按商品成交）。
   *
   * <p>★ 由来（旧 {@code MerchantHaul} 类注的标定，逐字保留）：一趟承运 10 商品单位 = 10,000 毫商品 = 一个人一个周期的口粮量。
   */
  public static final long GOODS_UNITS_PER_HAUL = 10L;

  /**
   * ★★ <b>M-C：跑商家户的创世启动工具（毫工具 / 户）：12 × 一趟跑商的工具量</b>。
   *
   * <p>★★ <b>为什么创世必须给这一份</b>（用户 2026-10-10 裁定：「跑商这个产业的准入门槛要高，单次跑商需要花费大量 tool」；设计书 §12
   * H-D/H-5）：而创世把工具只给**作坊主** 家户（见 {@link #toolPerWorkshopMilli()}），"选了跑商"的家户（城镇 {@code
   * rich_peasant} → {@code merchant.self_employed}、{@code landlord} → {@code
   * merchant.principal}）手里**一件工具都没有** ⇒ 不补这一份，跨格贸易从第 1 天起就整体走不动（那不是"门槛高"，是"门槛封死"）。
   *
   * <p>★★ <b>A3（2026-10-10）：改成由 {@link #HAUL_TOOL_PER_MILLE_OF_SERVICE} 这一套派生</b>（旧写法是 {@code 12L *
   * MerchantHaul.TOOL_MILLI_PER_HAUL}，而那套"趟耗"已按设计书 §3.2 删除）。逐值**不变**（仍是 12,000 毫工具 = 12 商品单位）：
   *
   * <pre>
   * 12 趟 × 一趟 10 商品单位 × 1,000 毫服务/商品单位 × 100‰（HAUL_TOOL_PER_MILLE_OF_SERVICE）÷ 1000 = 12,000 毫工具
   * </pre>
   *
   * <p>★ <b>为什么是 12 趟</b>：一个市场月（每 5 天一轮 × 12 ≈ 60 天）的量级 —— 够观察到 "跑商要烧工具、烧完就得再买"这条链路，同时明确它是**外生初值**。
   *
   * <p>★★ <b>已知边界（如实记）</b>：工具补货路径 = 该格 {@code trade} 产业的周期投入（{@link
   * #TOOL_MILLI_PER_TRADE_UNIT_CYCLE}），经营者恰是**一个**家户（该格 merchant principal）⇒ 补货只覆盖"经营 trade
   * 的家户"；同格其它跑商家户（如 {@code merchant.self_employed}）没有 trade unit ⇒ 它们的工具补给要靠排序表把它们选去当 trade
   * 经营者，或另开"跑商家户按剩余运力生成工具购买意图"那一档（A3 未做，见实现账本"未完成项"）。
   */
  public static final long MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI =
      12L
          * GOODS_UNITS_PER_HAUL
          * EconomyVocabulary.MILLI_PER_COMMODITY_UNIT
          * HAUL_TOOL_PER_MILLE_OF_SERVICE
          / 1_000L;

  /** 织造的产业活动标签（{@code HouseholdLaborCommitment.activity}）。 */
  public static final String ACTIVITY_WEAVE = WEAVE;

  /**
   * ★★★ <b>D-032 C9：商队运输的劳动活动标签</b>（{@code HouseholdLaborCommitment.activity}）。
   *
   * <p>它是 {@link #ACTIVITY_SEX_WEIGHT_PER_MILLE} 的键；<b>不得复用 {@link #FARM}</b> 冒充运输劳动（否则读口/审计无法区分
   * 农场与运输，且性别权重会悄悄沿用农业）。运输配额的 activity 列仍按既有口径写 unit id，本标签只作性别权重选择键。
   */
  public static final String ACTIVITY_TRANSPORT = "transport";

  /**
   * ★★ **各劳动活动按性别的默认配置权重**（‰，创世 preset）——**"男耕女织"的落点**（spec §四 原文："**性别与年龄影响各类劳动活动的 默认配置权重**"）。
   *
   * <pre>
   * 农业（男耕）：男 600 / 女 400      // 偏向男性
   * 纺织（女织）：男 200 / 女 800      // 偏向女性
   * 作坊（城市手工业）：男 500 / 女 500 // 两性均等（专业化生产，没有那份乡土分工）
   * </pre>
   *
   * ★★ **它不是"女 = 纺织"的硬编码**：权重只是一张**可按活动覆盖的默认表**，配额仍是**按批次的毛劳动加权**切出来的 （{@code 权重 = 毛劳动 ×
   * 该性别在本活动的权重}）⇒ 男人照样进纺织、女人照样种地，只是**默认配置**偏一侧。 ★ 与 {@code AGE_LABOR_COEF_BY_SEX} 同为"创世 preset"：R4
   * 的季节性（农忙 ⇒ 农业劳动需求升 ⇒ 家庭纺织降）会重配它。
   */
  static final Map<String, Map<Sex, Integer>> ACTIVITY_SEX_WEIGHT_PER_MILLE = activitySexWeights();

  /**
   * ★ <b>活动 → 性别权重表</b>（保序不可变；照本类"绝不用 {@code Map.of} 拼多键表"的既有纪律： {@code Map.of} 的 ≥2
   * 键形态迭代序不是内容的纯函数）。
   *
   * <p>★★ W5 追加 {@link #MINT}（与作坊同档 500/500：铸币是体力 + 手艺的混合活）。
   */
  private static Map<String, Map<Sex, Integer>> activitySexWeights() {
    Map<String, Map<Sex, Integer>> weights = new LinkedHashMap<>();
    weights.put(FARM, Map.of(Sex.MALE, 600, Sex.FEMALE, 400));
    weights.put(WEAVE, Map.of(Sex.MALE, 200, Sex.FEMALE, 800));
    weights.put(CRAFT, Map.of(Sex.MALE, 500, Sex.FEMALE, 500));
    weights.put(MINT, Map.of(Sex.MALE, 500, Sex.FEMALE, 500));
    // ★ D-032 C9：运输劳动是独立活动标签，性别权重 600/400（与农业同档但**不复用 FARM 键**，
    //   这样读口/审计能区分"农场劳动"与"商队运输劳动"）。
    weights.put(ACTIVITY_TRANSPORT, Map.of(Sex.MALE, 600, Sex.FEMALE, 400));
    return Collections.unmodifiableMap(weights);
  }

  /**
   * ★★★ <b>W5-fix3（2026-10-11）：城镇池的日劳动里分给铸币的份额（‰）：{@code 30}</b> —— 铸币劳动从**作坊配额发完后的剩余预算**里取。
   *
   * <p>★★ <b>为什么从 10 改成 30（实测读数，不是估算）</b>：配额 = {@code min(池日劳动 × 本值 ÷ 1000, 作坊发完后的剩余预算)}，
   * 而"能不能产出"由规模公式的第二段决定：{@code laborScale = ⌊周期平均日劳动 ÷ laborPerUnit⌋}（见 {@code
   * ProductionEfficiencyBook.evaluateHarvest}）。小世界首都（0,0）实测：
   *
   * <pre>
   * 池日劳动（参与率折扣后）      = 349,160 毫劳动/日
   * 改前 10‰：请求 3,491/日 ⇒ 实际落表 3,147/日   &lt; 单位规模需求 8,000 ⇒ laborScale = 0 ⇒ 规模 0 ⇒ 铸币 0 毫
   * 改后 30‰：请求 10,474/日 ⇒ 实际落表 9,440/日  ≥ 单位规模需求 8,000 ⇒ laborScale = 1 ⇒ 规模 = min(劳动 1, 产能 1, 投入 1) = 1
   *                                                                      ⇒ 产出 = 1 × MINT_MILLI_PER_FURNACE_CYCLE = 500 毫/周期
   * </pre>
   *
   * <p>★★ <b>为什么是"提份额"而不是"缩单位规模"</b>（W5-fix3 的选择理由，任务书约束 ②）：份额只改**铸币向池子要多少**， 单位规模的三个出厂率（劳动 {@link
   * #MINT_LABOR_MILLI_PER_FURNACE} / 工具 {@link #MINT_TOOL_MILLI_PER_FURNACE} / 产出 {@link
   * #MINT_MILLI_PER_FURNACE_CYCLE}）一个字节都不动 ⇒ "毫/毫劳动"（0.0625）与"工具/毫劳动"（0.25）两条比率**逐值同改前** （约束 ②
   * 的最强形态：既不让铸币变暴利，也不让工具变免费）。缩单位规模则要同时等比改三个常量、并把"1 座铸炉"的物理含义改掉。
   *
   * <p>★★ <b>它不抢作坊的劳动</b>：铸币拿的是 {@code appendAllocation} 走完之后**剩下的**那份预算（同一张预算表多做一次读取） ⇒
   * 作坊的配额逐值不变（实测：改前改后 {@code unit-craft@0_0} 的日配额都是 313,023 毫劳动）。
   *
   * <p>★ <b>为什么是 30 而不是刚好够的 ≈26‰</b>：{@code laborBudget} 不给"时间预算为 0"的批次发配额，而 {@code
   * appendAllocation} 按性别权重把总量摊到**池里每个批次**上 —— 摊到零预算批次的那一份被预算封顶**静默削掉**。小世界实测削掉 12 个批次：8
   * 个老龄批次（{@code …MALE/FEMALE:s0-2 … s3-2}，老龄时间预算 = 0）+ 4 个流民批次（{@code …MALE/FEMALE:d-1 /
   * d-2}，D-023：流民没有工作），合计 1,034/10,474 ≈ 9.9%。 留 ~18% 余量让"一个单位规模"在削掉之后仍然成立。
   *
   * <p>★ <b>三个旋钮各管一段</b>（都是**标定项**，W6 首查 / 用户拍板）：本值 = 铸币的劳动投入规模；{@link
   * #MINT_MILLI_PER_FURNACE_CYCLE} = 产出率（毫/规模·周期）；{@link #MINT_FURNACES_PER_CAPITA} = 铸炉数（产能上限）。
   */
  public static final int MINT_LABOR_SHARE_PER_MILLE = 30;

  /**
   * ★★ **农村日劳动里分给家庭纺织的份额**（‰）：**100**。
   *
   * <p>★★ **这一条是本轮最容易踩的坑**（brief 点名，与 V3 的"播种器留白"同款）：若创世把农村批次 **1000‰ 全给农业**，
   * 家庭纺织就是"**有配额、没活干**"的惰性状态 ⇒ 报表里看不见布。故创世**必须**给农村批次一条非零的纺织配额 （判据：真档推一年后 {@code CLOTH} 库存 &gt; 0
   * 且该配额非零）。
   *
   * <p>★ 依据：农闲时家户投入织布的时间占全年劳动的一成左右；它是**判断结果**（spec §十一）⇒ 具名常量、可按活动调。 ★ **R4 的季节性会调它**（农忙 ⇒ 农业劳动需求升
   * ⇒ 家庭纺织降）。
   */
  public static final int WEAVE_SHARE_PER_MILLE = 100;

  /**
   * ★★★ <b>D-032 C9：operator 自有可用劳动中切给商队运输的份额（‰）：{@code 100}</b>。
   *
   * <p>该份额从**同一份** landed/urban 劳动预算里切出（农场/作坊总量等额减少），不新增第二份劳动。实际值再取 {@code
   * max(CaravanTransportTemplate.LABOR_MILLI_PER_SCALE_UNIT, operatorAvailable × 本值 ÷ 1000)}，并受
   * operator 可用劳动与来源池可切余额双重封顶；不足最小规模 ⇒ 具名跳过，不伪造 0 劳动。
   */
  public static final int TRANSPORT_LABOR_SHARE_PER_MILLE = 100;

  /**
   * ★★ <b>R4-B.3a：每格产业容量与劳动拆给家户副 unit 的份额（‰）：{@code 300}</b>。
   *
   * <p>★★ <b>拆法（唯一常量、唯一算法）</b>：对每个产业的每条 {@code (industry, asset)} 容量与每条指向主 unit 的劳动配额， 副 unit 合计拿
   * {@code ⌊总量 × 本值 ÷ 1000⌋}，主 unit 拿剩余 —— 于是 {@code Σ 份额 == 旧 capacity}、 {@code Σ 配额 ==
   * 旧配额}（只拆不加，见 {@link #splitIndustry}）。★ 家户自用/佃耕/家户纺织的份额都在这 300‰ 之内， <b>不额外造地、不额外造劳动</b>。
   *
   * <p>★ <b>为什么是 300</b>：出厂判断值（judgement，不是从数据推的）：庄园自营占七成、佃耕/匠户合计三成， 既让同一格出现"两个主体各记各的进度/产出账"，又不把主
   * unit 压到看不见规模。★ GM 旋钮：改它 = 改新世界初态 （不影响旧档迁移路径）。
   */
  public static final int SECONDARY_PER_MILLE = 300;

  /**
   * ★★ <b>R4-B.3a：佃租/匠户分成率（‰）：{@code 300}</b>。
   *
   * <p>佃农 unit 的旧规则 = {@code OUTPUT_SHARE × GROSS_OUTPUT × grain × 本值‰ × 受方=地主家户}；匠户 unit 的同型规则以
   * {@code cloth} 付给作坊主。★ 它是<b>旧 {@link CompensationRule} 的出厂参数</b>，与 {@code RegimeRelations} 的
   * {@code FEUDAL_RENT_PER_MILLE}（庄园地租 300‰）同量级但各自独立。★★ D-032 C3 起它先经 {@code legacyRuleRoleNodes}
   * 审计/映射；映射成功则旧 rules 清空、由 plan-level 角色承接；不可表示时创世具名失败。
   */
  public static final int TENANT_RENT_PER_MILLE = 300;

  /**
   * 初始阶层比例（‰）：§十"初始阶层比例"行 贫农 450 / 中农 350 / 富农 150 / 地主 50。
   *
   * <p>★ **包内可见**（不是 {@code public}）：数组是可变对象，公开分享等于对外开一个改参数的后门（SpotBugs MS_PKGPROTECT
   * 实测报过）；它只服务本类与同包用例。
   */
  static final int[] CLASS_SHARE_PER_MILLE = {450, 350, 150, 50};

  /** 阶层槽位 id（与 {@link #CLASS_SHARE_PER_MILLE} 同序；包内可见的理由见上）。 */
  /**
   * ★★ **阶层的规范值**（S1 阶段 1：改用**全局词表** {@link SocialClassId} 的值 —— 从 {@code peasant}/{@code
   * middle}/{@code rich} 换成 {@code poor_peasant}/{@code middle_peasant}/{@code rich_peasant}）。
   *
   * <p>★ **顺序必须与 {@link #CLASS_LABOR_PER_MILLE} 一一对齐**（贫农 → 中农 → 富农 → 地主）。
   *
   * <p>★ 保持 {@code String[]}（而非换成 `List&lt;SocialClassId&gt;`）：消费点（`.length`、`CLASS_IDS[i]`、 塞进
   * JSON 的 `slot.put("id", …)`）全部按字符串用 —— 换类型要动 15 处而**收益为零**， 词表校验已经在 {@code new
   * SocialClassId(...)} 那一处生效。
   */
  /// ★★ W7（2026-10-11）：**表项改由 {@link IndustryClassSlots} 提供**（唯一拼写点上移到 economy）—— 理由是 W7 起
  //  {@code trade} 也能在迁移时按需创建，而那条写路径在 economy 内部（看不到 app 的常量）⇒ 槽位表只能有一份。
  //  名字与类型一字不变，**逐值不变**（贫农/中农/富农/地主）。
  static final String[] CLASS_IDS = IndustryClassSlots.IDS.toArray(new String[0]);

  /** 阶层槽位展示名（与 {@link #CLASS_SHARE_PER_MILLE} 同序；包内可见的理由见上）。 */
  static final String[] CLASS_NAMES = IndustryClassSlots.NAMES.toArray(new String[0]);

  // ── D-032 RC2b：农村二档池（landed_peasant / landless_laborer）──────────────────────────────────
  //   ★ 旧 CLASS_* 四档只保留给城市池/城市产业；农村池按本组数组切人、发劳动、落家户。
  //   ★ 顺序：有地农民在前（LAND 权重全给 index 0），无地农民在后。

  /** 农村家户阶层 id（与 {@link #RURAL_CLASS_SHARE_PER_MILLE} 同序）：有地农民 / 无地农民。 */
  static final String[] RURAL_CLASS_IDS = {
    SocialClassId.LANDED_PEASANT.value(), SocialClassId.LANDLESS_LABORER.value()
  };

  /** 农村家户阶层展示名（与 {@link #RURAL_CLASS_IDS} 同序）。 */
  static final String[] RURAL_CLASS_NAMES = {"有地农民", "无地农民"};

  /**
   * 农村池阶层比例（‰）：800 / 200 ⇒ 每 hex 200 农村人口切成 160 有地 / 40 无地（Σ == 1000‰）。
   *
   * <p>★ <b>旧 {@link #CLASS_SHARE_PER_MILLE} 不动</b>：城市池仍按 450/350/150/50 切四档。
   */
  static final int[] RURAL_CLASS_SHARE_PER_MILLE = {800, 200};

  /** 农村池阶层劳动投入率（‰）：有地/无地均为 950（RC2b 冻结口径）。 */
  static final int[] RURAL_CLASS_LABOR_PER_MILLE = {950, 950};

  /** 农村池初始口粮天数：有地 120 天 / 无地 30 天（与 {@link #RURAL_CLASS_IDS} 同序）。 */
  static final int[] RURAL_RATION_DAYS = {120, 30};

  /** 农村池按阶层份额加权的参与率（‰）= Σ(份额 × 劳动率) ÷ 1000；二档均 950 ⇒ 950。 */
  static final int RURAL_CLASS_WEIGHTED_LABOR_PER_MILLE = ruralClassWeightedParticipationPerMille();

  private static int ruralClassWeightedParticipationPerMille() {
    long weighted = 0L;
    for (int i = 0; i < RURAL_CLASS_SHARE_PER_MILLE.length; i++) {
      weighted += (long) RURAL_CLASS_SHARE_PER_MILLE[i] * RURAL_CLASS_LABOR_PER_MILLE[i];
    }
    return (int) (weighted / 1000L);
  }

  /** 该居住类型的阶层 id 表（农村二档 / 城市旧四档）—— 分流唯一入口。 */
  static String[] classIdsOf(ResidenceKind residence) {
    return residence == ResidenceKind.RURAL ? RURAL_CLASS_IDS : CLASS_IDS;
  }

  /** 该居住类型的阶层份额表（农村 800/200 / 城市 450/350/150/50）。 */
  static int[] classSharesOf(ResidenceKind residence) {
    return residence == ResidenceKind.RURAL ? RURAL_CLASS_SHARE_PER_MILLE : CLASS_SHARE_PER_MILLE;
  }

  /** 该居住类型的阶层劳动投入率表（农村 950/950 / 城市 950/900/750/100）。 */
  static int[] classLaborOf(ResidenceKind residence) {
    return residence == ResidenceKind.RURAL ? RURAL_CLASS_LABOR_PER_MILLE : CLASS_LABOR_PER_MILLE;
  }

  /** 该居住类型的阶层加权参与率（‰）。 */
  static int classWeightedLaborOf(ResidenceKind residence) {
    return residence == ResidenceKind.RURAL
        ? RURAL_CLASS_WEIGHTED_LABOR_PER_MILLE
        : CLASS_WEIGHTED_LABOR_PER_MILLE;
  }

  /** 该居住类型的初始流民比例（‰）：RC2b 起所有池均为 0（不生成 displaced 户）；居住类型仅作非空校验。 */
  static int displacedSeedOf(ResidenceKind residence) {
    Objects.requireNonNull(residence, "residence");
    return 0;
  }

  /**
   * ★★ <b>创世初始流民比例（‰）：{@code 0}（RC2b 起所有池不生成 displaced 户）。</b>
   *
   * <p>RC2b 起本常量为 {@code 0}：新世界**不再切出 displaced 家户**（农村无地农民是常规阶层户）。保留常量供手搭夹具/旧口径 引用；所有居住类型的读取口
   * {@link #displacedSeedOf(ResidenceKind)} 同样恒为 {@code 0}。 ★ 改它 = 改新世界初态，记入
   * productionRuntimeRulesVersion() 的口径。
   */
  public static final int DISPLACED_SEED_PER_MILLE = 0;

  /**
   * ★★ <b>流民家户的阶层槽位字面量</b>：{@link SocialClassId#LANDLESS_LABORER} 的规范值（{@code
   * "landless_laborer"}）—— 它是 {@link SocialClassId} 封闭词表里与"无地流民"唯一对齐的一档。
   *
   * <p>★★ <b>为什么不是字面量 {@code "displaced"}</b>（如实记偏差）：{@code HouseholdEconomy} 的 {@code slot}/view
   * 的阶层段由 {@code EconomyPayloads.householdEconomy} 经 {@link SocialClassId#parse} 解析，而该词表**不含**
   * {@code displaced}（本批文件所有权也不含 {@code SocialClassId}）⇒ 线格式只能用一个词表内阶层。流民的**生产方式**仍是 {@link
   * DefaultProductionModes#DISPLACED}，由 {@code productionRuntimeClassMemberships} 按本槽位映射到 {@code
   * displaced.laborer} 位置；家户 id 的 {@code roleSuffix="displaced"} 保留"流民户"的显式痕迹。
   */
  static final String DISPLACED_SLOT = SocialClassId.LANDLESS_LABORER.value();

  /**
   * ★★ **初始粮食储备的按阶层天数表**（版本化参数，2026-09-25 用户点名）：键 = 阶层槽位，值 = **每人几天的口粮**。
   *
   * <p>★★ **为什么要按阶层差异化**：旧口径给全世界每一行都配同一份 60 天口粮 ⇒ 同格里**谁都没有余粮**（人人都恰好吃到自己那份），
   * 于是同格借粮链**空转**、缺粮**没有任何后果**。贫农最薄（30 天）、地主最厚（250 天）后，地主/富农手里天然有可贷的余粮， 贫农先见底 ⇒ 同格借贷与（{@code
   * 旧结算引擎（R3a 已删除）} 的）饿死惩罚才有落点。
   *
   * <p>★ 改这张表 = 改初始资源分布 ⇒ 记入 productionRuntimeRulesVersion() 的口径（版本化，不写死在公式里）。
   */
  public static final Map<SocialClassId, Integer> INITIAL_RATION_DAYS_BY_CLASS =
      Map.of(
          new SocialClassId(CLASS_IDS[0]), 30, // 贫农：最薄（先见底 ⇒ 缺口/借粮/饿死都从它起）
          new SocialClassId(CLASS_IDS[1]), 60, // 中农：与旧口径同（60 天）
          new SocialClassId(CLASS_IDS[2]), 120, // 富农：有余粮可贷
          new SocialClassId(CLASS_IDS[3]), 250, // 地主：最厚（同格主要债权人）
          new SocialClassId(RURAL_CLASS_IDS[0]), RURAL_RATION_DAYS[0], // 有地农民：120 天
          new SocialClassId(RURAL_CLASS_IDS[1]), RURAL_RATION_DAYS[1]); // 无地农民：30 天

  /**
   * ★★ <b>初始粮食储备倍数</b>（2026-10-09 压力验证批引入；2026-10-23 用户裁定对半砍）。
   *
   * <p>由来：首日播种会按可用的家户粮食扣种子（种子需求约 5.08 亿毫粮，见 small-world 42,358 亩 × 12 粮/亩）， 而 {@link
   * #INITIAL_RATION_DAYS_BY_CLASS} 的原始储备只有约 2,167 万毫粮 ⇒ 首日几乎全被当种子扣掉，第一个收获日前 没有口粮，压力累积到
   * 1000+。倍数把各阶层储备粮放大，验证“只要留下口粮、压力是否会退、出生是否会动”。
   *
   * <p>★★ <b>2026-10-23 用户裁定：30 → 15（对半）</b>——配合 {@link #GRAIN_OUTPUT_PER_MU} 的历史下调（67 →
   * 34；2026-10-20 预设为 48）， 让清偿能力不足、债务跨周期累积。★ 它仍是"验证参数"性质：验证完成后应改为正式参数表/世界生成参数或回退。
   */
  public static final int GENESIS_GRAIN_RESERVE_MULTIPLIER = 15;

  /** 槽位劳动投入率上限（‰）：{@code ClassSlot} 的既定口径（贫农 950 / 中农 900 / 富农 750 / 地主 100）。 */
  /// ★★ W7：表项改由 {@link IndustryClassSlots#LABOR_PER_MILLE} 提供（唯一拼写点；值逐值不变 950/900/750/100）。
  static final int[] CLASS_LABOR_PER_MILLE = IndustryClassSlots.laborPerMille();

  /**
   * {@link #CLASS_IDS} / {@link #CLASS_SHARE_PER_MILLE} 里**地主**的槽位序号（= 3）。
   *
   * <p>★ R4-B.3a 的佃耕 unit 要指名"该格农村 landlord 家户"（owner / 租金受方）—— 这个下标不在别处再写数字 3。
   */
  static final int LANDLORD_SLOT_INDEX = 3;

  /**
   * {@link #CLASS_IDS} 里**作坊主**的槽位序号（= 1，{@code middle_peasant}）。
   *
   * <p>★ P2-A §13.3：城市作坊不再是账户主体 —— 主 unit 的经营者 = 该格城镇中农家户（ {@code productionRuntimePositionId} 的
   * {@code handicraft_workshop.workshop_owner} 档），它的工具周转料与工资周转金 都记在这一户的账上。
   */
  static final int WORKSHOP_OWNER_SLOT_INDEX = 1;

  /**
   * ★★ <b>M1.8：阶层加权的参与率（‰）</b> —— {@code Σ(阶层份额 × 该阶层参与率) ÷ 1000}。
   *
   * <pre>
   * (450×950 + 350×900 + 150×750 + 50×100) ÷ 1000 = 860 000 ÷ 1000 = 860‰
   * </pre>
   *
   * <p>★★ <b>它为什么存在、以及它为什么不是"第二次折扣"</b>：配额是**按批次**发的，而一个批次（性别 × 年龄）里混着四个阶层 ——
   * 批次的"可用劳动"只能按**池的阶层构成**取加权参与率。于是同一份毛劳动在两条投影上各折算<b>一次</b>： 逐行 = {@link
   * HouseholdEconomy#participationAdjustedLaborMilli()}（阶层自己的参与率），逐批次 = 本常量；两者在池上的总量相等（同一批人、同一套份额）。
   * ★ <b>严禁</b>任何调用点在乘过本常量之后又去乘一次逐阶层的参与率（或反过来）—— 那会把同一份劳动折算两遍（M1.8 的靶子）。
   *
   * <p>★ <b>它是派生量、不是第二个参数表</b>：公式只读 {@link #CLASS_SHARE_PER_MILLE} 与 {@link #CLASS_LABOR_PER_MILLE}
   * —— 改那两张表，本值跟着变，不可能漂开。
   */
  static final int CLASS_WEIGHTED_LABOR_PER_MILLE = classWeightedParticipationPerMille();

  private static int classWeightedParticipationPerMille() {
    long weighted = 0L;
    for (int i = 0; i < CLASS_SHARE_PER_MILLE.length; i++) {
      weighted += (long) CLASS_SHARE_PER_MILLE[i] * CLASS_LABOR_PER_MILLE[i];
    }
    return (int) (weighted / 1000L);
  }

  static final int[] AGE_SHARE_PER_MILLE = {350, 550, 100};

  /** 各年龄档的劳动系数（‰，与 {@link #AGE_SHARE_PER_MILLE} 同序）：0 / 1000 / 300。 */
  static final int[] AGE_LABOR_COEF_PER_MILLE = {0, 1000, 300};

  /**
   * ★★ **年龄档 × 性别的劳动系数表**（‰）：外层键 = 性别，值 = 与 {@link #AGE_SHARE_PER_MILLE} 同序的档内系数。
   *
   * <p>★ **默认两性同表**（本轮判据只是"性别**进入了**折算"，不是"男女系数不同"——"男耕女织"的具体数值属 R2，
   * 到那时才按观察调这张表）。表**可按性别覆盖**正是那个旋钮：`payload(…, 表)` 的包内可见重载收它，单测据此证明 "性别真的参与折算"（把女性系数改成 0 ⇒ 劳动逐值减半）。
   */
  static final Map<Sex, int[]> AGE_LABOR_COEF_BY_SEX =
      Map.of(Sex.MALE, AGE_LABOR_COEF_PER_MILLE, Sex.FEMALE, AGE_LABOR_COEF_PER_MILLE);

  /**
   * ★★ <b>P2-A §13.4 / C8：家户每 tick 劳动时间预算的系数表</b>：默认 = 未成年 4h / 成年男 16h / 成年女 8h / 老年 0。
   *
   * <p>★★ <b>Z1b 收口</b>：本表**从社会侧唯一权威派生**（{@link SocialProvisioning#defaults()} 的全局劳动默认行 + {@link
   * SocialProvisioning#standardLaborMilliHoursPerTick()}），不再把 {@code
   * HouseholdLaborTimeTable.DEFAULT}（legacy economy-api 常量）当第二真相。逐值不变：CHILD 4,000 / ADULT_MALE
   * 16,000 / ADULT_FEMALE 8,000 / ELDER 0（毫小时/人/tick）⇒ 创世逐值零影响。单位毫小时；{@code
   * HouseholdEconomy.laborMilli} 是它在经济侧的投影（每 tick 重算）。
   */
  public static final HouseholdLaborTimeTable HOUSEHOLD_LABOR_TIME_TABLE =
      authoritativeHouseholdLaborTimeTable();

  /**
   * ★★ <b>C8：从社会侧权威默认表派生</b>（Z1b）。四个值全部读 {@link SocialProvisioning#defaults()} 的**全局默认劳动行** —— 其中
   * {@code ADULT/MALE} 走具名的 {@link SocialProvisioning#standardLaborMilliHoursPerTick()}（标准岗位劳动定额
   * 的唯一权威），其余三档走同表公开读口。缺行 / 无性别档的 CHILD、ELDER 男女不一致 ⇒ 具名 fail-closed（不猜、不用 legacy 常量兜底）。
   */
  private static HouseholdLaborTimeTable authoritativeHouseholdLaborTimeTable() {
    SocialProvisioning authority = SocialProvisioning.defaults();
    long childMale = authoritativeLaborMilli(authority, AgeBracket.CHILD, Sex.MALE);
    long childFemale = authoritativeLaborMilli(authority, AgeBracket.CHILD, Sex.FEMALE);
    if (childMale != childFemale) {
      throw new IllegalStateException(
          "社会劳动权威默认表的 CHILD 男女系数不一致，无法派生无性别档的 HouseholdLaborTimeTable: "
              + childMale
              + " vs "
              + childFemale);
    }
    long elderMale = authoritativeLaborMilli(authority, AgeBracket.ELDER, Sex.MALE);
    long elderFemale = authoritativeLaborMilli(authority, AgeBracket.ELDER, Sex.FEMALE);
    if (elderMale != elderFemale) {
      throw new IllegalStateException(
          "社会劳动权威默认表的 ELDER 男女系数不一致，无法派生无性别档的 HouseholdLaborTimeTable: "
              + elderMale
              + " vs "
              + elderFemale);
    }
    return new HouseholdLaborTimeTable(
        childMale,
        authority.standardLaborMilliHoursPerTick(),
        authoritativeLaborMilli(authority, AgeBracket.ADULT, Sex.FEMALE),
        elderMale);
  }

  /** 权威默认表的全局劳动行（不回落家户覆盖）；缺行 ⇒ 具名 fail-closed（拒绝臆造）。 */
  private static long authoritativeLaborMilli(
      SocialProvisioning authority, AgeBracket bracket, Sex sex) {
    return authority
        .globalLabor(bracket, sex)
        .orElseThrow(() -> new IllegalStateException("社会劳动权威默认表缺劳动行: " + bracket + "/" + sex))
        .milliHoursPerTick();
  }

  // ── 商品 id：唯一拼写点都在 {@link EconomyVocabulary}（v2 spec §六；R3 起商品不止粮）──────────

  public static final String COMMODITY_GRAIN = EconomyVocabulary.GRAIN_COMMODITY_ID;

  /** 布（R3；家庭纺织与城市作坊的产出）。 */
  public static final String COMMODITY_CLOTH = EconomyVocabulary.CLOTH_COMMODITY_ID;

  /** 纤维（R3；农田副产，也是织机/作坊的原料）。 */
  public static final String COMMODITY_FIBER = EconomyVocabulary.FIBER_COMMODITY_ID;

  /** 工具（R3；作坊的产出）。 */
  public static final String COMMODITY_TOOL = EconomyVocabulary.TOOL_COMMODITY_ID;

  /** 铁（R3；作坊的原料）。 */
  public static final String COMMODITY_IRON = EconomyVocabulary.IRON_COMMODITY_ID;

  /** ★★ 运输服务（A1，2026-10-10；旧 {@code trade@hex} 的产出；C9 起新世界不再 seed 该活动）。 */
  public static final String COMMODITY_HAUL = EconomyVocabulary.HAUL_COMMODITY_ID;

  /** ★★ D-032 C9：运输马匹商品 id（唯一拼写点仍是 {@link EconomyVocabulary#TRANSPORT_HORSE_COMMODITY_ID}）。 */
  public static final String COMMODITY_TRANSPORT_HORSE =
      EconomyVocabulary.TRANSPORT_HORSE_COMMODITY_ID;

  /**
   * ★★★ <b>D-032 C9：每个既有 OWNER 家户的创世运输马匹库存</b>（毫商品单位）。
   *
   * <p>用户冻结口径：「运输马匹的测试世界存量必须要大」「每个 hex 的既有 OWNER 家户持有大量运输马匹，并提供可出售余量」。 取 {@code 1_000_000} 毫 =
   * <b>1,000 匹马/户</b>：32hex 测试世界每 hex 至少一户 OWNER ⇒ 单是创世存量就有 3.2
   * 万匹马，远大于现有市场轮运力需求；马匹不冻结、不扣减，故库存同时就是可出售余量的上限。
   *
   * <p>★ 这是**唯一数量入口**：main seeder 与 C10 test owner 都从本常量（经 {@code EconomySeeder} 的同名别名）读取， 不在
   * fixture 或别处另写第二个数。
   */
  public static final long TRANSPORT_HORSE_OWNER_STOCK_MILLI = 1_000_000L;

  // ── H4：同格市场（固定价、每格单一计价货币）与创世货币禀赋 ──────────────────────────────

  /**
   * ★★ <b>出厂计价货币</b>（H4；裁定 M1-A"每格市场只有一个记账单位"）：{@code silver}。
   *
   * <p>★★ <b>唯一拼写点是 {@link RegimeRelations#DEFAULT_CURRENCY}</b>（H2 的币种位）—— 本类**不写第二份** {@code
   * "silver"} 字面量：同一件事两处拼写，改名那天必然漂开（而"钱是哪种"漂开不会有任何编译错误）。★ <b>M1.1 补记</b>：字面量本身已 搬进世界级货币词表 {@code
   * MoneyVocabulary}（{@code DEFAULT_CURRENCY} 引用它）⇒ 全仓 {@code src/main} 里 {@code "silver"} 恰一处，由
   * {@code EconomyVocabularyGuardTest} 的源扫描钉住。
   */
  public static final CurrencyId MARKET_NUMERAIRE = RegimeRelations.DEFAULT_CURRENCY;

  /**
   * ★★ <b>粮价 = 1</b>：整张表的**基准商品**（口粮口径的实物；M1-A 的计价实物）。
   *
   * <p>★ 依据：粮是本世界唯一"人人要吃、天天要吃"的商品 ⇒ 取它作价值基准，其余商品按"生产一个单位要多少劳动/投入" 与之相比（见下面逐条）。
   */
  public static final long MARKET_PRICE_GRAIN = 1L;

  /**
   * ★★ <b>布价 = 5</b>（1 匹布 = 5 粮的等价 = 5 毫银）。
   *
   * <p>★ 依据（按"生产一个单位要多少劳动/投入"）：一匹布 = <b>1 单位纤维</b>（{@link #FIBER_MILLI_PER_CLOTH}）+ 约 <b>33.3
   * 千分劳动</b>（{@link #LABOR_MILLI_PER_LOOM} 1000 ÷ {@link #CLOTH_PER_LOOM_PER_CYCLE} 30）。 取 5 = 原料 1
   * + 加工 4 —— "加工增值约为原料的四倍"，与"1 匹布换 5 单位粮"的前现代量级相符。 ★ 城市作坊的同一条配方每匹摊到的劳动减半、而多耗 1/6 单位铁（{@link
   * #CLOTH_PER_WORKSHOP_PER_CYCLE}） ⇒ 两条技术路线在同一挂牌价下的毛利率不同：**固定价不看成本**是本批的已知简化 （动态价格 P' =
   * P(1+k(D−S)/(D+S)) 留给价格那一轮）。
   */
  public static final long MARKET_PRICE_CLOTH = 5L;

  /**
   * ★★ <b>纤维价 = 1</b>（与粮同价）。
   *
   * <p>★ 依据：纤维是**农田的副产**——同一亩地、同一份劳动同时出 {@link #GRAIN_OUTPUT_PER_MU} 粮（2026-10-20 预设为 48）与 6 单位纤维
   * （{@link #FIBER_OUTPUT_PER_MU}）⇒ 它没有独立的"多花一份工"的成本，出厂按**同价**是保守取值（不给副产折价、 也不溢价）。★ 折价（例如
   * 1/2）同样可辩护；这是 GM 旋钮。
   */
  public static final long MARKET_PRICE_FIBER = 1L;

  /**
   * ★★ <b>铁价 = 10</b>（1 单位铁 = 10 粮的等价）：<b>依据最弱的一条，如实记</b>。
   *
   * <p>★★ 本轮**没有冶炼流程**（{@link #COMMODITY_IRON} 只来自创世给的初始库存，K6 的"留位"商品）⇒ 铁**没有生产成本可推**。
   * 按"它比农副产（纤维）贵一个量级、与工具同量级"给一个判断值；★ 采矿/冶炼接入那一轮必须重定（并且届时它才有 "按劳动推"的依据）。
   */
  public static final long MARKET_PRICE_IRON = 10L;

  /**
   * ★★ <b>工具价 = 20</b>：**由配方内生的比值**，不是另拍的数。
   *
   * <p>★ 依据：作坊每件工具耗 2 单位铁（{@link #IRON_MILLI_PER_TOOL} 2000 毫铁 = 2 单位）⇒ {@code 2 × 铁价 =
   * 20}。工具的加工劳动**已经计在布的售价里**（作坊是"布 + 工具"的联合生产，laborPerUnit 只有一份）⇒ 工具挂牌价恰等于它的铁含量价值，不重复计价。
   */
  public static final long MARKET_PRICE_TOOL = 20L;

  /**
   * ★★ <b>A2：运输服务（{@code haul}）的出厂牌价 = 4</b>（毫银 / 商品单位服务；1 商品单位服务 = 1,000 毫运输服务 = 1,000 毫商品·程，量纲见
   * {@link #HAUL_PER_TRADE_UNIT_CYCLE}）。
   *
   * <p>★★ <b>用户原话（2026-10-10，设计书 §1）</b>：「运输服务我不是说算商品吗，只是这个商品的需求需要额外通过其他已有商品的购买来
   * 计算，要我选的话我肯定选A」＋「按照市场上最低价的运力提供商买运力」。
   *
   * <pre>
   * ① 成本下界（本产业自己的配方，逐值可核）：
   *      每 1 规模单位·周期 ⇒ 投入 {@link #TOOL_MILLI_PER_TRADE_UNIT_CYCLE}(100 毫工具) 、产出 {@link #HAUL_PER_TRADE_UNIT_CYCLE}(1 商品单位服务)
   *      工具出厂价 {@link #MARKET_PRICE_TOOL}(20) ⇒ 单位服务成本 = 100 × 20 ÷ 1000 = **2 毫银 / 商品单位服务**
   *      ⇒ 牌价 = 2 恰好**不亏不赚**（劳动没有回报）。
   * ② 既有运费口径（改前的单位运费，逐式可核）：
   *      freightUnitMilli(基础费 1, 路线费率 60‰, 承运成本 25‰) = max(1, ⌈1 × 1060 × 1025 ÷ 10^6⌉) = **2 毫银 / 商品单位**
   *      ⇒ 改前买方为一单位货付的运费，折到"服务单价"上正是 ① 的 2 —— 两条独立口径在 2 处重合，
   *        说明本行**不是新拍的数**（服务量 = 1,060 毫服务/商品单位货，见 {@code CapacityDemand}）。
   * ③ 为什么取 4 而不是 2：{@code laborPerUnit = }{@link #LABOR_MILLI_PER_TRADE_UNIT} > 0 ⇒ 跑商是**要人干的产业**，
   *      它的劳动必须由家户的劳动队列按预期净收益发放（{@code LaborQueueBook}）。而队列的判据是
   *        net = 卖出价(bid) − 投入成本 = ⌊牌价 × 990‰⌋ − 2 ，且要求 net ≥ 1 毫银/规模
   *      ⇒ 牌价 = 2 ⇒ net = −1（不排队）；牌价 = 3 ⇒ ⌊2.97⌋ = 2 ⇒ net = 0（仍不排队）；牌价 = 4 ⇒ ⌊3.96⌋ = 3 ⇒ net = 1 ✔
   *      ⇒ **4 是能让跑商家户真的去跑商的最小整数牌价**（取整档位见 {@code MarketTable.BID_PER_MILLE}）。
   * ④ 与用户"按最低价的运力提供商买运力"的关系：牌价是**市场价**（所有提供者同价）⇒ 买方的择优落在既有的
   *      "限价升序 → 议价权降序 → 家户 id 升序"队列上（同价按 canonical 序），不另设特权队列。
   * </pre>
   *
   * <p>★★ <b>这是一行 GM 可调的数据，不是写死的行为</b>（与上面五条同一条纪律）：改它 = 改世界的运输服务价格。 ★ 敏感性已算清并记账：≤ 3 ⇒ 跑商拿不到劳动配额 ⇒
   * <b>一件服务也产不出来</b>（跨格货单因此买不到运力而不成交）； 值越高 ⇒ 卖方利润越厚、买方运费越贵。真实世界的落点由 A4 用两套 world 复验。
   */
  public static final long MARKET_PRICE_HAUL = 4L;

  /**
   * ★★★ <b>D-032 C9：运输马匹的市场牌价 = 100 毫银 / 商品单位</b>（{@code MarketTable.prices} 的口径：毫银 / 商品单位， 1 匹马 =
   * 1,000 毫商品 ⇒ 1 匹马 = 100 毫银）。
   *
   * <p>★ 这是本责任区**唯一拼写点**；{@code factoryPrices()} 必须包含该键，缺键时创世/运输 seed 具名失败， 不允许在承运组织已存在而马匹无价时静默退回“0
   * 价/无价不交易”。它是 GM 可调工程默认，不是用户标定。
   */
  public static final long MARKET_PRICE_TRANSPORT_HORSE = 100L;

  /**
   * ★★ <b>出厂价格表（保序：粮 → 布 → 纤维 → 工具 → 铁）</b>—— 逐格市场的 {@code prices}，键 = 商品 id。
   *
   * <p>★★ <b>量纲（与 {@code MarketTable} 的契约逐字一致）：{@code 价格 = 毫计价货币 / 商品单位}</b>（1 商品单位 = 1000 最小计量单位，见
   * {@link EconomyVocabulary#MILLI_PER_COMMODITY_UNIT}）⇒ economy 侧的结算是 {@code 货款(毫银) = 数量(毫商品) × 价格
   * ÷ 1000}。★ 于是 {@link #MARKET_PRICE_GRAIN}（= 1）读作 <b>1 粮 = 1 毫银</b>（10,000 毫粮 ↔ 10 毫银 =
   * 一个周期的口粮）——"每单位商品的毫银数"是本表的唯一读法， 本类**不另立一套量纲**（价格量纲的唯一拼写点在 {@code MarketTable} 的类注）。
   *
   * <p>★★ <b>整张表都是 GM 可调的数据，不是写死的行为</b>（信条十二）：本类只给**出厂值**，改价 = 改这一行 （或将来迁进 V7
   * 参数目录）；**没有任何代码分支依赖某个具体价格**——价格只被写进载荷、由 economy 侧的同格市场池读。
   *
   * <p>★★ <b>为什么没有木</b>（{@code EconomyVocabulary.WOOD_COMMODITY_ID}）：本轮**无配方、无播种、无库存** ⇒
   * 挂一个没有生产者、也没有持有者的牌价只是噪声（与 K6 对 {@code IRON} 的要求"留位必须有读者"同一条纪律的 反面：**不留没有读者的价**）。木材接入那一轮再加一行。
   *
   * <p>★ <b>键序 = 商品 id 的声明序</b>（{@link #COMMODITY_GRAIN} / {@link #COMMODITY_CLOTH} / {@link
   * #COMMODITY_FIBER} / {@link #COMMODITY_TOOL} / {@link #COMMODITY_IRON}）—— {@code Map.of} 的
   * 迭代序不是内容的纯函数 ⇒ 用 {@code LinkedHashMap} 包一层再冻结（载荷字节因此可复现）。
   */
  public static final Map<CommodityId, Long> MARKET_PRICES_FACTORY = factoryPrices();

  /**
   * ★ <b>单区派生市场的规范 id</b>：{@code "single-region"} —— 与改动前 {@code MarketTopology.singleRegion} 的节点
   * id 逐字相同（"同状态 ⇒ 同 id"：派生拓扑给出的区 id 就是价表的键）。
   */
  public static final String SINGLE_REGION_MARKET_ID = "single-region";

  /**
   * ★★ <b>创世货币禀赋的缓冲系数（‰）</b>：{@code 1200} —— 每人 <b>1.2 个周期</b>的口粮等价。
   *
   * <p>★★ <b>为什么必须 ≥ 1000‰</b>（brief 的硬要求："必须够买一个周期的口粮，否则市场形同虚设"）：市场的有效需求 = 有购买力的缺口；若禀赋
   * <b>一个周期</b>的口粮等价，家户在第 1 天就买不起自己那份口粮 ⇒ 市场开张即死。
   *
   * <p>★ <b>为什么多那 200‰</b>：挂牌价是固定价、成交还受"供 &lt; 求 ⇒ 按需求比例配给"约束，且家户一年还要添一身 衣裳（每人每年 1 匹布 = 5 粮的等价）⇒
   * 留两成周转余量，别让禀赋卡在"恰好够粮"的边界上。★ 它是 GM 旋钮。
   */
  public static final int GENESIS_MONEY_BUFFER_PER_MILLE = 1_200;

  /**
   * ★★ E3：创世发行/审计主体的稳定 id（唯一拼写点）。当前市场单一计价货币 {@code silver}。
   *
   * <p>★ P2-C 后的两种形态：给了政府家户的 demo 世界里它是"国库 = 家户账户"的发行政府；普通 nation seed 里它是 {@link
   * #genesisAuditGovernment()}（不持户、issuable 空集、旋钮 0），只为 INITIAL_ENDOWMENT 提供归属，不声称任何家户。
   * 单位政府（中央/地方）另用 {@code gov-unit-<unitId>} 的派生 id，不再共用这一把 key。
   */
  public static final GovernmentId GENESIS_GOVERNMENT_ID = new GovernmentId("world-silver");

  /** 世界级政府在 {@code nationRef} 里的引用（当前不是任何真实国家 id，故用保留字面量）。 */
  public static final String GENESIS_GOVERNMENT_NATION_REF = "world";

  /**
   * ★★★ <b>W5（2026-10-11）：铸币政策旋钮的出厂值</b>（毫 / 产业周期）—— 写进 {@link Government#mintScalePerCycle()}。
   *
   * <p>★★ <b>它取代了两个旧常量</b>（{@code GOVERNMENT_SEIGNIORAGE_PER_CYCLE_MILLI} = 2,000 与 {@code
   * GOVERNMENT_DEBT_ISSUE_PER_CYCLE_MILLI} = 5,000，双轨）：按 §一.11，旧的两个字段与两个常量<b>直接删</b>。
   * 语义也换了：改前那是"周期开始<b>直接加进国库</b>的钱"（旁路），现在是"本周期铸币的<b>产出上限</b>"—— 钱必须由铸币产业<b>生产出来</b>（{@code
   * IndustryDefinitionView.moneyOutputPerUnit}）并受 {@code Government.mintScalePerCycle} 上限约束（★
   * W5-fix5：上限就是这个旋钮本身）。
   *
   * <p>★ 取 {@code 2_000} 是与旧铸币量同量级（毫计价货币：2 银/周期）的<b>保号标定</b>，不是重新标定； 真正的产出还取决于铸币 unit
   * 的规模与产出率，故实测值会小于本上限。
   */
  public static final long GOVERNMENT_MINT_SCALE_PER_CYCLE_MILLI = 2_000L;

  /**
   * 出厂价表（{@link #MARKET_PRICES_FACTORY} 的构造）—— **保序**：粮 → 布 → 纤维 → 工具 → 铁 → 运输服务 → 运输马匹。
   *
   * <p>★ 为什么不直接 {@code Map.of(...)}：它的迭代序不是内容的纯函数 ⇒ 载荷字节会抖（本仓对"可复现"的既定口径）。 ★★ A2（2026-10-10）：{@code
   * haul} **追加在末尾**（既有五项的相对序一字不动）—— 加它是本批"运输服务成为可交易商品" 的落点（设计书 §3.1"进价格表？进"）；它同时是"服务成市"的开关（{@code
   * HaulService.pricedAt}）。
   */
  static Map<CommodityId, Long> factoryPrices() {
    Map<CommodityId, Long> prices = new LinkedHashMap<>();
    prices.put(new CommodityId(COMMODITY_GRAIN), MARKET_PRICE_GRAIN);
    prices.put(new CommodityId(COMMODITY_CLOTH), MARKET_PRICE_CLOTH);
    prices.put(new CommodityId(COMMODITY_FIBER), MARKET_PRICE_FIBER);
    prices.put(new CommodityId(COMMODITY_TOOL), MARKET_PRICE_TOOL);
    prices.put(new CommodityId(COMMODITY_IRON), MARKET_PRICE_IRON);
    // ★★ A2：运输服务（末位追加，既有五项的相对序不变）。
    prices.put(new CommodityId(EconomyVocabulary.HAUL_COMMODITY_ID), MARKET_PRICE_HAUL);
    // ★★★ D-032 C9：运输马匹是普通可交易商品，必须有价；追加在末尾，既有项相对序不变。
    if (MARKET_PRICE_TRANSPORT_HORSE <= 0L) {
      throw new IllegalStateException(
          "transport-horse-price-invalid: MARKET_PRICE_TRANSPORT_HORSE="
              + MARKET_PRICE_TRANSPORT_HORSE);
    }
    prices.put(new CommodityId(COMMODITY_TRANSPORT_HORSE), MARKET_PRICE_TRANSPORT_HORSE);
    return Collections.unmodifiableMap(prices);
  }

  static final CalendarClock GENESIS_CLOCK = CalendarClock.julianDefault();

  /**
   * 把 {@code total} 按 **千分比例表** 切成同长子表（§十 的"初始阶层比例"）。
   *
   * <p>★★ **分母恒为 1000‰，绝不用 Σ比例 归一化**：{@code 450/350/150/50} 之和恰为 1000，故正常情形下 {@code Σ 结果 ==
   * total}（余数 ∈ [0, n-1]，按**最大余数法**分派：余数 {@code total × 比例 mod 1000} 大者先得、同余数**按下标序**，见 {@link
   * ProportionalSplit}）。而**比例表被人改坏**（例如地主 50 → 100 而没重分）时 分母仍是 1000 ⇒ {@code Σ 结果 ≠ total} ——
   * 静默归一化会把这个错误**伪装成"一切正常"**（本仓最忌的那一族）， "Σ 行人口 == 格人口"那条守恒断言因此有判别力。
   *
   * <p>★ **不变式**：{@code Σ结果 == total} 当且仅当 {@code Σ比例 == 1000}（或残差为 0 的平凡情形）。
   */
  public static long[] splitByShares(long total, int[] sharesPerMille) {
    long[] weights = new long[sharesPerMille.length];
    for (int i = 0; i < sharesPerMille.length; i++) {
      if (sharesPerMille[i] < 0) {
        throw new IllegalArgumentException("splitByShares 的比例不得为负: " + sharesPerMille[i]);
      }
      weights[i] = sharesPerMille[i];
    }
    return split(total, weights, 1000L);
  }

  /**
   * 把 {@code total} 按**任意非负权重**成比例切成同长子表，**Σ 结果恰为 {@code total}**（残差按最大余数法分派）。
   *
   * <p>★ 与 {@link #splitByShares} 的分工：这里的分母是**权重之和**（权重不是千分数，如"按各行人口分土地"）； 权重全为 0（或 {@code total ==
   * 0}）时整份记在第一项（农业人口为 0 的格，土地不丢也不做除零）。
   *
   * <p>★★ **与 {@code 旧结算引擎（R3a 已删除）.allocate} 是同一个函数**（都走 {@link ProportionalSplit#byDenominator}
   * 且分母同为 Σ权重）⇒ "分配口径统一"是**可执行的事实**，由 {@code EconomyAllocationConsistencyTest} 跨模块逐值对拍。
   */
  public static long[] splitProportional(long total, long[] weights) {
    return split(total, weights, -1L);
  }

  /**
   * 两个切分函数的共同实现（{@code denominator < 0} ⇒ 用 **Σ权重** 作分母，否则用给定分母）。
   *
   * <p>★ 校验留在两个公开入口（它们的异常消息是既有契约的一部分）；本函数只做"分母选择"。
   */
  private static long[] split(long total, long[] weights, long denominator) {
    if (total < 0) {
      throw new IllegalArgumentException("切分的 total 不得为负: " + total);
    }
    long weightSum = 0L;
    for (long weight : weights) {
      if (weight < 0) {
        throw new IllegalArgumentException("切分的权重不得为负: " + weight);
      }
      weightSum += weight;
    }
    if (weights.length == 0 && total != 0L) {
      throw new IllegalArgumentException("切分没有可承载的槽位，但 total = " + total);
    }
    return ProportionalSplit.byDenominator(
        total, weights, denominator < 0L ? weightSum : denominator);
  }

  /**
   * ★★ <b>铸币产业的 kind</b>（{@code <kind>@<q>_<r>} 的 kind 段；唯一拼写点）。
   *
   * <p>★ 用 {@code mint} 而不是复用某个既有 kind：铸币是**一等生产方式**（有配方、有产能锚、有产出）， 复用一个不相关的 kind
   * 会让"这个产业到底是干什么的"在读口处不可判（判据 ⑤：kind 要具名）。
   */
  public static final String MINT = "mint";

  /** 铸币产业的制度名（本批不新增 {@code ProductionModeDefinitionView}/阶层结构：它<b>不建户、不参与阶层流动</b>）。 */
  public static final String REGIME_MINT = "government_mint";

  /**
   * ★★ <b>每座铸炉的产能锚</b>（{@link #MINT_FURNACES_PER_CAPITA} × 首都人口，最少 {@code 1} 座）。
   *
   * <p>★ "单位规模"必须有一个生产资料锚（{@code capacityPerUnit} 不得为空 —— 否则规模无上界、产出随"可用"无限涨）： 铸币锚在 {@code
   * WORKSHOP}（铸炉/作坊）上，与手工业同一种资产，故不需要新造 {@code AssetKind}。
   */
  public static final long MINT_FURNACES_PER_CAPITA = 1L;

  /**
   * ★★ <b>每 1 座铸炉每周期的劳动需求</b>（千分劳动；{@code 8_000} = 8 人·周期）—— 铸币<b>吃劳动</b>那一半。
   *
   * <p>★ 量纲与 {@code IndustryDefinitionView.laborPerUnit} 同侧（千分劳动/规模），与既有的 {@code
   * LABOR_MILLI_PER_LOOM}/{@code LABOR_MILLI_PER_WORKSHOP} 同制。
   *
   * <p>★★ <b>W5-fix3 量纲澄清（只改注释，不改值）</b>：它在规模公式里比较的对象是**周期平均日劳动** {@code avgLaborMilli = 本周期累计劳动 ÷
   * cycleDays}（{@code ProductionEfficiencyBook.evaluateHarvest} 的 ② 劳动链）⇒ 运算上读作
   * <b>毫劳动/日·单位规模</b>（{@code laborScale = ⌊avgLaborMilli ÷ 本值⌋}），<b>不是</b>"一个周期总共 8,000"。
   * 故"一个单位规模"= 每天 8,000 毫劳动（= {@code SocialProvisioning} 出厂默认里成年女性一天的 8,000 毫小时定额）， 一个 120 天的周期共吃
   * 8,000 × 120 = 960,000 毫劳动。★ 本值本身是**标定项**：改它必须同步等比改 {@link #MINT_TOOL_MILLI_PER_FURNACE} 与
   * {@link #MINT_MILLI_PER_FURNACE_CYCLE}，否则"毫/劳动"比率会漂（W5-fix3 选择改 {@link
   * #MINT_LABOR_SHARE_PER_MILLE} 而不是改它，正是为了不动这三条出厂率）。
   */
  public static final long MINT_LABOR_MILLI_PER_FURNACE = 8_000L;

  /**
   * ★★ <b>每 1 座铸炉每周期的工具投入</b>（毫工具；{@code 2_000} = 2 件工具）。
   *
   * <p>★★ <b>为什么是工具而不是金银</b>（如实记的实现口径）：本世界没有贵金属商品（词表里只有粮/纤维/布/铁/工具/ 运输服务，见 {@code
   * EconomyVocabulary}）⇒"铸币消耗金属"这条在**当前词表下表达不出来**。本批按用户原话 "消耗劳动 +
   * 工具/金属等投入"取**工具**（铸范/模具/器具）：它是既有商品、可被真实买、可被真实消耗， 且它让铸币**有投入约束**（不是"有劳动就能无限印"）。★
   * 将来加入贵金属商品时，改这一张投入表即可（形状不变）。
   */
  public static final long MINT_TOOL_MILLI_PER_FURNACE = 2_000L;

  /**
   * ★★ <b>每 1 座铸炉每周期的产出率（毫货币 / 规模）</b> —— {@code IndustryDefinitionView.moneyOutputPerUnit} 的出厂值。
   *
   * <p>★ 取 {@code 500} 毫/座·周期：在"首都 1 座铸炉"的最小世界里，产出上限 = 500 毫，而政策旋钮 {@link
   * #GOVERNMENT_MINT_SCALE_PER_CYCLE_MILLI} = 2,000 毫 ⇒ 实测能看出"产出的实际约束来自规模与投入，不是旋钮"。 ★ 它是**标定项**（列
   * W6 首查）：改这一个常量即可调整铸币曲线。
   */
  public static final long MINT_MILLI_PER_FURNACE_CYCLE = 500L;

  /** 铸币的创世投入够跑几个周期（见 {@link #seedGovernmentMint} 第 ③ 步）。 */
  public static final int MINT_SEED_CYCLES = 4;
}
