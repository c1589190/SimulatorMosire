package io.mosire.simos.app.world;

import static io.mosire.simos.app.world.RuralLaborSeeder.fiberPerWorkshopMilli;
import static io.mosire.simos.app.world.RuralLaborSeeder.toolPerWorkshopMilli;
import static io.mosire.simos.app.world.SeederPayloadSupport.actorNode;
import static io.mosire.simos.app.world.SeederPayloadSupport.orderedTable;
import static io.mosire.simos.app.world.SeederPayloadSupport.relationNode;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.MerchantTradeTemplate;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.production.contract.RolePayment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 产业模板（农业/家庭纺织/作坊/贸易）与 plan/roles 构造。 */
class IndustrySeeder extends WorldBalanceSeeder {

  /**
   * 农业（**恒有**，§十）：制度 = **领主自营庄园**（{@link #REGIME_FEUDAL}）；★★ **H0.3 起产能 = 本格可耕地**， 不再按人口切进四行。
   *
   * <p>★ 本注原写"制度 = 封建租佃" —— 那是**同词两义**：租佃（佃农家户）是另立的 {@code tenant} 档，而本方法写进载荷的是 {@link
   * #REGIME_FEUDAL}（**领主自营庄园**）。它原与同文件里 {@code REGIME_FEUDAL} 常量注**直接矛盾**， 已在 S1 阶段 3 D8 就地改对（同 D7
   * 的口径）。
   *
   * <p>★★ **H0.2：它名下不再有四行** —— 那一格的四行农村家户搬到 entry 级（{@link #ruralCohort}），
   * 因为"这一格有多少地"是**产业**的事、"这一格的人有多少粮/多少活"是**家户**的事（K2/K3 的分工）。
   */
  static IndustryPlan agriculture(HexCoord hex, long landMilliMu) {
    return industry(
        FARM,
        IndustryHexKeys.id(FARM, hex.q(), hex.r()).value(),
        "农业",
        REGIME_FEUDAL,
        // ★★ **K3：产能从"行"搬到"产业"** —— 本格农业的产能总量 = 本格可耕地（千分亩）。
        //   旧版是"按各行人口切成四份、每行写一份 LAND"（Σ 才等于格土地）；现在总量只有一处真相，
        //   而"地归谁"由产权读口回答，不再与经济结算抢同一个字段。
        //   ★ 值**允许 0**（沙漠/山地/海洋格：可耕地 0）—— 0 是合法产能（"这格没有地"），不是缺键。
        Map.of("LAND", landMilliMu),
        orderedTable("meansWeightPerMille", 700, "laborWeightPerMille", 300),
        // ★★ **V7 配方**：规模单位 = **亩**（每 1 亩要 1,000 千分亩，故 {@code 规模 == 产能的亩数}）；
        //   每一亩需要 {@link #LABOR_MILLI_PER_MU} 千分劳动；每亩产 {@link #GRAIN_OUTPUT_PER_MU} 粮 + {@link
        // #FIBER_OUTPUT_PER_MU} 单位纤维
        //   （**田里同时出粮与纤维** —— 纤维是副产物，故不需要新的种植流程）；每亩下种 8 粮。
        Map.of("LAND", MILLI_MU_PER_MU),
        LABOR_MILLI_PER_MU,
        orderedTable(COMMODITY_GRAIN, GRAIN_OUTPUT_PER_MU, COMMODITY_FIBER, FIBER_OUTPUT_PER_MU),
        Map.of("LAND", Map.of(COMMODITY_GRAIN, SEED_MILLI_PER_MU)));
  }

  /**
   * ★★ **农村家庭纺织**（R3 的 T4；每个有农村人口的格一个）：配方 {@code FIBER + LABOR + SMALL_TOOL → CLOTH}（spec §四
   * 的压力测试），制度 = {@link #REGIME_HOUSEHOLD}，收劳动的主体是 {@code ActorKind.HOUSEHOLD} 家户。
   *
   * <p>★★ **H0.2：它不再有四行**（旧版那四行人口恒 0、劳动恒 0，是"同一批人的第二本账"）。旧版那四行上的东西**逐项去处**：
   *
   * <ul>
   *   <li>**织机**（旧：四行各持一份 {@code meansOfProduction.SMALL_TOOL}）⇒ 并成**总数**写进本产业的 {@code capacity}
   *       （{@link #RURAL_CAPITA_PER_LOOM} 人一台）。理由：织机是**产能**（"单位规模 = 1 台织机"，见 {@code
   *       capacityPerUnit}），K3 把它从家户账上收归产业；旧版"Σ 四行 = 本格织机数"这条守恒， 现在由**一个数**直接成立；
   *   <li>**纤维**（旧：四行各持一份 {@code goods.fiber}）⇒ 并入**农村四行**（见 {@link #ruralCohort}）。
   *       理由：那是**农村池的活**（同一批人农闲织布），家户账只能记在自家户名下 —— 记在"纺织"名下等于 给同一批人开第二本账；
   *   <li>**人口与劳动**（旧：恒 0）⇒ 本来就住在农村四行里，**一个数都不动**。农闲织布是同一批人的第二份活， 支撑它的是劳动配额那 100‰（{@link
   *       #WEAVE_SHARE_PER_MILLE}），不是第二份人口。
   * </ul>
   *
   * <p>★★ **纤维从哪来**（**留白，不是遗漏**）：创世给这四行各一份**纤维**（= 本格农业**一个周期**的纤维副产，见 {@link #fiberStockMilli}）。★
   * 把它从"田里"搬到"织机上"是**跨行的实物转移**，正是 spec §六 V8（统一转移）的活， 而 brief 明说"**不建议本轮做跨行实物转移**" ⇒
   * 本轮织机吃的是这份**明标为"估计来源"**的创世库存； 农业自己产的那份照常累积在农业行里（读口看得见）。★ **后果如实记**：一个周期之后织机没有原料 ⇒ 停工，等 V8
   * 把田里的纤维送过来。
   *
   * <p>★★ <b>它不是市场主体</b>（2026-10-23 裁定 B）：产出/库存已按劳动落各成员家户账，市场参与者只在成员家户这一层；{@code
   * MarketSettlement.participantsFor} 对集体 unit 静默跳过（只留默认关闭的 TRACE），不把它登记/警告为市场主体。
   */
  static IndustryPlan householdWeaving(HexCoord hex, long looms) {
    return industry(
        WEAVE,
        IndustryHexKeys.id(WEAVE, hex.q(), hex.r()).value(),
        "家庭纺织",
        REGIME_HOUSEHOLD,
        // ★★ 产能 = 本格**织机总数**（旧版四行各一份、Σ 才是总数）。★ 值允许 0（农村人口 < 20 的格一台也没有）。
        Map.of("SMALL_TOOL", looms),
        // 分配：家户自给 ⇒ 劳动权重为主（没有土地可摊；织机按户头摊）。
        orderedTable("meansWeightPerMille", 300, "laborWeightPerMille", 700),
        Map.of("SMALL_TOOL", 1L),
        LABOR_MILLI_PER_LOOM,
        Map.of(COMMODITY_CLOTH, CLOTH_PER_LOOM_PER_CYCLE),
        Map.of(
            "SMALL_TOOL",
            Map.of(COMMODITY_FIBER, CLOTH_PER_LOOM_PER_CYCLE * FIBER_MILLI_PER_CLOTH)));
  }

  /**
   * ★★ **城市作坊**（T5；城市格追加，§十）：制度 = 手工业；★★ **H0.3 起产能 = 本格作坊总座数**。
   *
   * <p>★★ **配方 = {@code FIBER + IRON + LABOR + WORKSHOP → CLOTH + TOOL}**（两条变换合在一座作坊里；"消耗 IRON"
   * 这种话正是 R3 换型要表达的东西）—— 它同时证明两件事（spec §六 给 T5 定的目的）：**非 LAND 生产成立**、**城市能产出自己的产品**。 ★
   * 城乡交换（布换粮）不在本轮（那要 R4 的 V8）。
   *
   * <p>★★ **H0.2：它也不再有四行**。旧版那四行上的东西**逐项去处**：
   *
   * <ul>
   *   <li>**作坊**（旧：四行各持一份 {@code meansOfProduction.WORKSHOP}）⇒ 并成**总数**写进本产业的 {@code capacity}
   *       （{@link #URBAN_CAPITA_PER_WORKSHOP} 人一座）；
   *   <li>**原料库存：纤维 + 铁**（旧：四行各按"该行作坊数 × 一座作坊一个周期的用量"持有）⇒ 并入**城镇四行** （见 {@link
   *       #urbanCohort}）。理由同纺织：这是**城镇池的活**，只能记在城镇家户名下；
   *   <li>**人口与劳动** ⇒ 搬到城镇四行，**逐值不变**（旧版就在 craft 行上，不在 weave 行上）。
   * </ul>
   *
   * <p>★★ **原料从哪来**：创世给这四行各一份**初始库存**（纤维 + 铁，均明标"估计来源"）—— 与家庭纺织同一处置，理由见它的注释。
   */
  static IndustryPlan handicraft(HexCoord hex, long workshops) {
    return industry(
        CRAFT,
        IndustryHexKeys.id(CRAFT, hex.q(), hex.r()).value(),
        "手工业",
        REGIME_HANDICRAFT,
        // ★★ 产能 = 本格**作坊总座数**（旧版四行各一份、Σ 才是总数）。★ 值允许 0。
        Map.of("WORKSHOP", workshops),
        orderedTable("meansWeightPerMille", 400, "laborWeightPerMille", 600),
        Map.of("WORKSHOP", 1L),
        LABOR_MILLI_PER_WORKSHOP,
        orderedTable(
            COMMODITY_CLOTH,
            CLOTH_PER_WORKSHOP_PER_CYCLE,
            COMMODITY_TOOL,
            TOOL_PER_WORKSHOP_PER_CYCLE),
        // ★★ H5 ④：投入 = **纤维 + 工具**（改前是纤维 + 铁）—— 工具是作坊**自己的产品**（净产为正）
        //   ⇒ 存量可再生，"创世一箱铁 → 用完永久停工"这个外生断点消失。逐条算式见
        //   {@link #TOOL_MILLI_PER_WORKSHOP_CYCLE}。
        Map.of(
            "WORKSHOP",
            orderedTable(
                COMMODITY_FIBER, fiberPerWorkshopMilli(),
                COMMODITY_TOOL, toolPerWorkshopMilli())));
  }

  /**
   * ★★ <b>城市贸易/承运产业（P11.7/D-024）</b>：每个有城镇人口的格追加一个 {@code trade@hex} 模板。
   *
   * <pre>
   * regime          = merchant（{@link RegimeOperators#MERCHANT}；BY_REGIME 不登记它，故经营主体显式给）
   * capacity        = {SMALL_TOOL: MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI}  // ★ W7-fix：作业资本 = 工具（12,000 毫）
   * capacityPerUnit = {SMALL_TOOL: MerchantTradeTemplate.TOOL_PER_UNIT}        // ★ W7-fix2：**120** 毫工具作业资本 / 1 单位规模（存量；≠ 周期投入 100，不要求相等）
   * outputPerUnit   = {haul: HAUL_PER_TRADE_UNIT_CYCLE}       // ★ A1：产出**运输服务**（改前是空表 ⇒ 收获当场 return）
   * cycleInputPerUnit = {tool: {tool: TOOL_MILLI_PER_TRADE_UNIT_CYCLE}}  // ★ A3：跑商工具消耗的**唯一**一套（产业周期投入）
   * laborPerUnit    = LABOR_MILLI_PER_TRADE_UNIT（1000）      // 劳动约束（LaborQueueBook 读它）
   * 分配模板         = meansWeightPerMille = TRADE_SPLIT_MEANS_PER_MILLE(700) / laborWeightPerMille = 300
   * cycleDays       = CYCLE_DAYS
   * 经营者           = 该格 merchant principal 家户 actor
   * </pre>
   *
   * <p>★★ <b>A1（2026-10-10）为什么给 {@code outputPerUnit} 这一行</b>：改前它是**空表** ⇒ {@code
   * EconomySettlement.harvest} 的 {@code netByCommodity.isEmpty() ⇒ return} 让 trade 的收获**当场返回** ——
   * 收入腿不进标准管线的任何一步；同时 {@code LaborQueueBook.offer} 为它落 {@code NO_RECIPE_OUTPUT}（maxAbsorbable = 0）。
   * 这正是用户 2026-10-10 原话「我觉得更该排查的是为什么跑商作为生产方式会和其他类型的生产隔离开来」指的那处**结构性隔离** （只读调查账本
   * S-3/S-11）。给了产出之后：trade 走**同一条**净产入账/关系结算路径，劳动约束由 {@code laborPerUnit} 参与排队。
   *
   * <p>★★ <b>A1 当时的缺省中性（设计书 §5 I-H3）——★ 已被 A2 取代，此处只留痕</b>：A1 时运输服务**没有牌价** （{@code
   * MARKET_PRICES_FACTORY} 不含它）⇒ 按既有 {@code MarketTable.prices} 口径「缺价 ⇒ 不交易」： {@code
   * MarketDemandBook} 算出的可寻址需求恒 0 ⇒ 家户不挂运输服务卖单、没有对手方 ⇒ **不产生任何运输服务交易**； {@code LaborQueueBook}
   * 的估值也因缺价恒为 0 （{@code outputPriced=false} + 净收益 ≤ 0）⇒ trade **照旧不入队**、劳动分配逐值不变。
   *
   * <p>★★★ <b>A2（2026-10-10）更正：牌价已经在表里了 —— 上面那段不再描述现状</b>。{@code MARKET_PRICES_FACTORY} 自 A2 起**含**
   * {@code haul}（出厂价 {@code MARKET_PRICE_HAUL = 4} 毫/商品单位；见 {@link #factoryPrices()} 的末位追加） ⇒
   * 缺省世界里运输服务**是成市的**：{@code trade} 真的产出服务、跨格货单真的派生服务需求并成交（A2 派生需求、 A3 并回标准管线、A5 周期作废）。仍然成立的是那条既有
   * {@code MarketTable.prices} 口径本身（无牌价格 ⇒ 不交易、逐值退回 M-A1），只是现在只有"未定价 / 被明确设成 0 以外无法删价"的世界才落在那一支；A4
   * 实测真实 world 里"无牌价世界" **构造不出**（GM 无删价路径、{@code commodityIds} 读词表）——见 A4 账本 §Q6。
   *
   * <p>★★★ <b>W7-fix（2026-10-11，用户裁定 v3.28 §7）：{@code trade} 的作业资本从<b>牲口</b>换成<b>工具</b></b>。
   * 用户原话「**能不能先把牲口要求取消掉啊？目前是在跑测试，算作工具需求得了**」⇒ 跑商投入口径 = <b>劳动 + 工具</b> （J-B 的既有算式「运力 = f(劳动投入,
   * 工具消耗)」），创世那一份"运力资产"随之落到**工具**：
   *
   * <pre>
   * capacity        = {SMALL_TOOL: MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI}(12,000)  ← 与同一家户已拿到的启动工具**同一个数**
   * capacityPerUnit = {SMALL_TOOL: MerchantTradeTemplate.TOOL_PER_UNIT}(120)          ← 作业资本那一维（**存量**：每 1 单位规模压住的毫工具；★ ≠ 下一行的周期投入 100，**不是同一个量、不要求相等**）
   * cycleInputPerUnit = {tool: {tool: TOOL_MILLI_PER_TRADE_UNIT_CYCLE}}(100)     ← 值侧一字未改，只换分路键
   * </pre>
   *
   * ★ <b>不新增任何商品量</b>：这里只多一条**产权份额**登记（把商号本金主家户已经拿到的那一份工具同时登记为该产业的 作业资本）；家户的工具**商品账**一个字未变。★ 它取代了
   * W7 的 {@code MERCHANT_CATTLE_PER_CITY}（常量与用法一并删除， 不留第二个拼写点）—— 原因见 {@link
   * #MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI} 与模板类的 W7-fix 节。
   *
   * <p>★ {@code capacity} 会在 {@code ownershipStakesOf(plan, mainCapacity)} 出口物化成 {@code owner =
   * operator} 的 {@code OWNED} **SMALL_TOOL** 份额（trade 没有家户副 unit 的配额 ⇒ 不拆分、整额给商号本金主）。
   */
  static IndustryPlan trade(HexCoord hex, ActorRef merchantPrincipal) {
    IndustryPlan plan =
        industry(
            TRADE,
            IndustryHexKeys.id(TRADE, hex.q(), hex.r()).value(),
            "贸易",
            RegimeOperators.MERCHANT,
            Map.of(AssetKind.SMALL_TOOL.name(), MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI),
            // 分配模板（旧口径的 split 规则）：承运以运力资产为主、劳动占三成（理由与档位见 TRADE_SPLIT_MEANS_PER_MILLE）。
            orderedTable(
                "meansWeightPerMille",
                TRADE_SPLIT_MEANS_PER_MILLE,
                "laborWeightPerMille",
                TRADE_SPLIT_LABOR_PER_MILLE),
            Map.of(AssetKind.SMALL_TOOL.name(), MerchantTradeTemplate.TOOL_PER_UNIT),
            LABOR_MILLI_PER_TRADE_UNIT,
            // ★★ A1：**产出 = 运输服务**（商品单位/规模·周期；具名常量与两条独立标定见 HAUL_PER_TRADE_UNIT_CYCLE）。
            //   表只有一项 ⇒ 单键 Map.of 不存在迭代序抖动（本仓"保序"纪律针对多项表；与下面工具投入表同款）。
            Map.of(COMMODITY_HAUL, HAUL_PER_TRADE_UNIT_CYCLE),
            // ★★ M-D 附修 + **A3 单套化**：**周期投入 = 工具**（改前是空表 ⇒ 跑商家户不会自发买工具）。
            //   ★★ A3（设计书 §3.2）：这是跑商工具消耗的**唯一**一套口径 —— 市场轮里"每趟烧 1,000 毫工具"
            //   （旧 MerchantHaul.TOOL_MILLI_PER_HAUL）已删；工具改由本条产业投入经**标准生产管线**现扣
            //   （{@code drawCycleInputs}）、并经**标准挂单保留**预留（{@code necessaryInputsOf} 的
            //   {@code industry.inputPerUnit()} 循环，I-H6）⇒ 跑商与农业/手工业同一条路。
            //   具名常量与"为什么是 100"见 {@link #TOOL_MILLI_PER_TRADE_UNIT_CYCLE}（由
            //   {@link #HAUL_TOOL_PER_MILLE_OF_SERVICE} 派生，与旧趟耗逐值可对上）。
            //   ★★ W7-fix：分路键随作业资本一起从 CATTLE 改成 tool（与手工业
            //   {@code cycleInputPerUnit = {WORKSHOP: {...}}} 同形；{@code
            // IndustryDefinitionView.inputPerUnit()} =
            // 各层之和，
            //   全仓没有按资产分支读它的现扣/收获路径 ⇒ 挂哪一层的值侧都逐值进同一个读口）。
            //   ★ 值侧口径 = "毫工具 / 规模单位·周期"（与手工业同口径）；表只有一项 ⇒ 用单键 {@code Map.of}
            //     不存在迭代序抖动（本仓"保序"纪律针对多项表）。
            Map.of(
                AssetKind.SMALL_TOOL.name(),
                Map.of(COMMODITY_TOOL, TOOL_MILLI_PER_TRADE_UNIT_CYCLE)),
            merchantPrincipal);
    // ★★ <b>兼容读口必须的两个键（如实记，见实现报告）</b>：{@code EconomyPayloads.industrySpec} 对**每个**
    //   industry 节点都会先算 {@code legacyOperator}（无 operator 键 ⇒ 调 {@code
    // RegimeOperators.defaultOperator}），
    //   而 merchant 不在 {@code RegimeOperators.BY_REGIME} 的登记表里（Agent A 的既定形状）⇒ 不给 operator 键，
    //   整个 production-runtime 载荷在解析期就 fail-closed。故 trade 节点显式带：
    //   · {@code operator} = 商号本金主（把那条默认推导短路）；
    //   · {@code relation} = 最小自留关系（merchant 不在 {@code RegimeRelations} 的默认模板表里；这条关系同时供
    //     解析器为下面"由 legacy 合成"的 trade unit 建关系）。
    //   代价：trade 的 unit 由 {@code EconomyPayloads} 的 legacy 合成路径按 {@code (产业, operator)} 的同一公式建出
    //   （id/operator/modeKey 与本类 plan 逐值同式），因此 seeder **不**再为 trade 显式发 units[] 条目（否则解析器判
    //   "同一 (产业, 经营者) 两处拼写"）；作业资本（SMALL_TOOL）份额仍走 assetShares 出口。
    plan.payload().put("operator", actorNode(merchantPrincipal));
    plan.payload().put("relation", relationNode(tradeRelation(plan)));
    return plan;
  }

  /**
   * ★★ <b>trade 的最小自留生产关系</b>：{@code merchant} 不在 {@code RegimeRelations} 的四档默认表里，载荷若无显式 {@code
   * relation} 会在解析期 fail-closed（{@code EconomyPayloads} 的 defaultRelation 分支）⇒ seeder 必须显式给一条。
   *
   * <p>空 {@code rules} = 产出全归 {@link ProductionRules#residualOwner()}（这与"缺 relation 走最小自留"逐值同效）；
   * {@code trade} 的产出（★ A1 起 = **运输服务**）因此全部留在 operator —— 即该格商号本金主家户（{@code creditOutput} 的单一主体）。
   * 劳动来源按其本金主/经营者自营记 {@link LaborSource#SELF}。投入供方就是 operator（其 {@code cycleInputPerUnit} 的工具由
   * operator 自己出，这一栏只是形状）。
   */
  private static ProductionRules tradeRelation(IndustryPlan plan) {
    ActorRef operator = plan.operator();
    return new ProductionRules(
        plan.unitId(),
        operator,
        new Payee.ToActor(operator),
        List.of(),
        operator,
        LaborSource.SELF);
  }

  /**
   * 一个产业对象（与 §3.1 {@code IndustryDefinitionView} 逐字段对应；**R3 起含 V7 的四个配方分量**；**H0.3 起含产能总量**）。
   *
   * <p>{@code dailyInputPerUnit}/{@code dailyLaborPerUnit} 置 0：§十 没给这两项的依据（那是 R3a 的事），**不臆造**；
   * {@code cycleDays} = {@link #CYCLE_DAYS}。★★ R3B.2 起返回 {@link IndustryPlan}：模板载荷 + 经营者 + 产能， unit
   * 与资产份额从它派生（模板本身不再带 operator/capacity/progress/cycleState）。
   *
   * <p>★★ **它名下没有 {@code classes}**（H0.2）：阶层行按 {@code (格, 居住类型, 阶层)} 挂在 entry 级 —— 一个产业的 {@code
   * slots} 只说"这个制度允许哪些角色"，不再说"这些行归它"。
   *
   * @param capacity 本格该产业的**产能总量**（§3.1 的 {@code capacity}；旧版住在 {@code
   *     HouseholdEconomy.meansOfProduction}）
   * @param capacityPerUnit 每 1 单位规模需要多少生产资料（农业 = 1 亩；织机/作坊 = 1 台/座）
   * @param laborPerUnit 每 1 单位规模需要多少劳动（千分劳动）
   * @param outputPerUnit 每 1 单位规模的产出（商品单位）
   * @param cycleInputPerUnit 每 1 单位规模每周期消耗的商品（毫单位；按生产资料种类归类）
   */
  private static IndustryPlan industry(
      String kind,
      String id,
      String name,
      String regime,
      Map<String, Object> capacity,
      Map<String, Object> split,
      Map<String, Object> capacityPerUnit,
      long laborPerUnit,
      Map<String, Object> outputPerUnit,
      Map<String, Object> cycleInputPerUnit) {
    return industry(
        kind,
        id,
        name,
        regime,
        capacity,
        split,
        capacityPerUnit,
        laborPerUnit,
        outputPerUnit,
        cycleInputPerUnit,
        RegimeOperators.defaultOperator(new RegimeId(regime), new IndustryId(id)));
  }

  /**
   * 上一条的**显式经营主体**重载：服务 {@code RegimeOperators.BY_REGIME} 不登记的制度（当前 = merchant 的 {@code
   * trade}，它的经营主体必须由载荷显式给出）。载荷/单位/份额的构造与上一条逐字相同。
   */
  private static IndustryPlan industry(
      String kind,
      String id,
      String name,
      String regime,
      Map<String, Object> capacity,
      Map<String, Object> split,
      Map<String, Object> capacityPerUnit,
      long laborPerUnit,
      Map<String, Object> outputPerUnit,
      Map<String, Object> cycleInputPerUnit,
      ActorRef operator) {
    if (operator == null) {
      throw new IllegalArgumentException("industry 的 operator 不得为 null（缺省请用不带 operator 的重载）");
    }
    List<Map<String, Object>> slots = new ArrayList<>(CLASS_IDS.length);
    for (int i = 0; i < CLASS_IDS.length; i++) {
      Map<String, Object> slot = new LinkedHashMap<>();
      slot.put("id", CLASS_IDS[i]);
      slot.put("name", CLASS_NAMES[i]);
      slot.put("laborParticipationPerMille", CLASS_LABOR_PER_MILLE[i]);
      slots.add(slot);
    }
    Map<String, Object> allocation = new LinkedHashMap<>(split);
    allocation.put("@class", "split");
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", id);
    payload.put("name", name);
    payload.put("regime", regime);
    payload.put("cycleDays", CYCLE_DAYS);
    // ★★ R3（V7）：配方的两个新分量 —— "每 1 单位规模需要多少生产资料 / 多少劳动"。
    payload.put("capacityPerUnit", capacityPerUnit);
    payload.put("laborPerUnit", laborPerUnit);
    payload.put("dailyInputPerUnit", Map.of());
    payload.put("dailyLaborPerUnit", 0);
    payload.put("outputPerUnit", outputPerUnit);
    // ★★ 一次性投入（v2 spec §3.3：**周期第一天**现扣的原料）：值侧带商品维度（R3 换型）。
    payload.put("cycleInputPerUnit", cycleInputPerUnit);
    payload.put("allocation", allocation);
    payload.put("slots", slots);
    // ★★★ D-032 C3（D2 §5.2/§5.5）：产业定义必须携带角色 —— 单户方案的缺省形态 = C0/D1 的
    //   {@link ProductionRoleDefinition#defaultOperator()}（id=operator、share=1000、PRODUCT_SHARE）。
    //   ★ 多角色方案必须显式 roles（本播种器当前没有多角色方案；若将来出现，见
    //   {@link #requireRostersForMultiRoleUnits} 的 seed-roster-missing 守卫）。
    payload.put("roles", singleOperatorRoleNodes());
    // ★★★ D-032 W5-E：方案必须显式声明资产权利接受面 —— production 缺键 = 空集 ⇒ 采用侧
    //   {@code asset-right-kind-missing}，所有生产日都会停。本播种器按 kind 的实际份额给出（唯一映射点见
    //   {@link #seedAcceptedRightKinds(String)}）。
    payload.put("acceptedRightKinds", seedAcceptedRightKinds(kind));
    // ★★ R3B.2：**模板不再带 operator/capacity/progress/cycleState** —— 这些是 unit/份额的事实：
    //   经营者 = RegimeOperators 的默认（与 operatorSeed 同源）；进度/劳动/投入由 unitOf(plan) 显式发出；
    //   产能总量由 ownershipStakesOf(plan) 整额 OWNED 物化。这样新载荷不会触发"旧形状"兼容路径。
    Map<String, Long> capacityLongs = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : capacity.entrySet()) {
      if (!(entry.getValue() instanceof Number number)) {
        throw new IllegalStateException("capacity 的值必须是整数: " + entry);
      }
      capacityLongs.put(entry.getKey(), number.longValue());
    }
    // ★★ R4-B.3a：capacityPerUnit 也留一份**定点整数**形态进 plan —— 副 unit 的"至少一份"判据
    //   （每资产至少 capacityPerUnit）读它；载荷那一份仍原样发出（形状不变）。
    Map<String, Long> capacityPerUnitLongs = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : capacityPerUnit.entrySet()) {
      if (!(entry.getValue() instanceof Number number)) {
        throw new IllegalStateException("capacityPerUnit 的值必须是整数: " + entry);
      }
      capacityPerUnitLongs.put(entry.getKey(), number.longValue());
    }
    return new IndustryPlan(
        kind, id, regime, operator, capacityLongs, capacityPerUnitLongs, payload);
  }

  /**
   * ★★★ <b>D-032 W5-E：seed 方案的产业 kind → 它实际物化的份额权利性质</b>（唯一映射点）。
   *
   * <p>★ <b>按实播份额精确列出</b>（不是"能多接受就多写"）：farm / craft 的主 unit {@code capacity} 物化为 {@code OWNED}、家户副
   * unit 物化为 {@code TENANCY}；weave / trade 只物化 {@code OWNED}。新增 seed 产业 kind 时必须在这里 显式给映射 —— 未知
   * kind 直接 {@link IllegalStateException}，绝不静默落成空集。
   */
  private static List<String> seedAcceptedRightKinds(String kind) {
    return switch (kind) {
      case FARM, CRAFT -> SEED_ACCEPTED_RIGHT_KINDS_OWNED_OR_TENANCY;
      case WEAVE, TRADE -> SEED_ACCEPTED_RIGHT_KINDS_OWNED;
      default ->
          throw new IllegalStateException(
              "seed 产业 kind="
                  + kind
                  + " 没有声明 acceptedRightKinds（production 缺键会落成空集，"
                  + "采用侧将具名拒 asset-right-kind-missing）");
    };
  }

  /**
   * ★★★ <b>D-032 C3（D1 §2.2 / D2 §5.2）：单户方案的默认角色节点（唯一的 wire 拼写点）</b>。
   *
   * <p>值的来源是 production 的 {@link ProductionRoleDefinition#defaultOperator()}（id={@code operator}、
   * share=1000‰、{@code intraRoleSplit=equal}、{@code standardLaborMilliPerCycle=0}、{@code
   * payment=product_share}） —— 单户/单经营者方案的缺省形态。★ 多角色方案**必须显式给 roles**，不得用本方法。
   *
   * <p>本方法同时服务 {@code economy.Seed} 的产业节点与 {@code production.Seed} 的产业节点：两边读的字段名与 {@code
   * ProductionDefinitionPayloads.role} 逐字对应（不在这里发明第二套字段名）。
   */
  public static List<Map<String, Object>> singleOperatorRoleNodes() {
    ProductionRoleDefinition role = ProductionRoleDefinition.defaultOperator();
    if (!(role.payment() instanceof RolePayment.ProductShare)) {
      throw new IllegalStateException(
          "单户默认角色的支付形态必须是 PRODUCT_SHARE（production 契约已变？）: " + role.id().value());
    }
    Map<String, Object> node = new LinkedHashMap<>();
    node.put("id", role.id().value());
    node.put("name", role.name());
    node.put("sharePerMille", role.sharePerMille());
    node.put("intraRoleSplit", role.intraRoleSplit().key());
    node.put("standardLaborMilliPerCycle", role.standardLaborMilliPerCycle());
    node.put("payment", "product_share");
    return List.of(Collections.unmodifiableMap(node));
  }

  /** 同一格内按产业 id 找 plan（三产业固定集合；找不到 = 该产业本格不存在）。 */
  static IndustryPlan planOf(List<IndustryPlan> plans, IndustryId id) {
    for (IndustryPlan plan : plans) {
      if (plan.id().equals(id.value())) {
        return plan;
      }
    }
    throw new IllegalStateException("本格没有产业 plan: " + id);
  }
}
