package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.ProductionInputDrawSettlement.drawOneProductionProcessInputs;
import static io.mosire.simos.economy.time.ProductionInputDrawSettlement.supplierAccountsOf;
import static io.mosire.simos.economy.time.SettlementLogSupport.TRACE;
import static io.mosire.simos.economy.time.SettlementLogSupport.logCapacityDeductionDetail;
import static io.mosire.simos.economy.time.SettlementMath.stockOf;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.economy.ProportionalSplit;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 由 EconomySettlement 按职责机械抽取；包内实现，不构成跨模块 API。 */
final class ProductionInputSettlement {

  // ── 现扣周期投入（周期的第一天）────────────────────────────────────────────────────────

  /**
   * ★★ **现扣周期投入步**（v2 spec §3.2/§3.3；**H3 起"谁出料"由 relation 明说**，裁定 C3）：**周期的第一天**（{@code
   * progressDays == 0}）把 {@link ProductionRecipe#inputPerUnit()}（农业 = 种子，织机 = 纤维，作坊 = 纤维 + 铁）
   * 从<b>关系指名的那一个主体的账</b>上划给该产业，并把实际划到的量**按商品**累加进 {@link
   * IndustryDefinitionView#cycleInputUsedMilli()}。
   *
   * <pre>
   * 供方       = relation.inputSupplier（缺 relation ⇒ 退回缺省 = operator；四档默认 = operator，见 RegimeRelations）
   * usableScale = min( 产能那一路 , ⌊可供量_j ÷ inputPerUnit[j]⌋ …每种投入一路 )     // "不把料倒进空转的产业"
   * need_j     = usableScale × inputPerUnit[j]                                      // ★ 整个产业的周期需求 —— 没有"人口占比"
   * drawn_j    = min( 供方余额_j , need_j )                                         // ★ 扣不动就扣光余额（不凭空造）
   * </pre>
   *
   * <p>★★ <b>改前的口径与它实测得到的病（H3 要修的就是这个）</b>：改前按 {@code rowSharesOf}（<b>该产业各行的人口占比 +
   * 逐行向下取整</b>）把需求摊给"供给该产业的家户"，每行再各扣各的。后果实测得到：① 分摊比例是<b>算</b>出来的 —— 没有哪一处 "说"过谁出料；② 逐行向下取整把需求切碎 ⇒
   * <b>取不满</b>：小夹具 6 座作坊只开 <b>4</b> 座、50 台织机只开 <b>48</b> 台； 真档年末剩 <b>24,001,080</b>
   * 毫纤维（旧口径"被织机取光"不再成立）。H3 起：**谁出料写在数据里** （{@link
   * ProductionRules#inputSupplier()}），需求是<b>整个产业</b>的，逐户只做一次"按人口占比 + 最大余数法"的分派 （Σ分派 ==
   * 需求，**不再逐户丢弃余数**）。
   *
   * <p>★★ <b>"从该主体自己的账出"的两处实现边界（如实记）</b>：
   *
   * <ol>
   *   <li>★ <b>供方是家户</b>（{@code ToCohort}；或本世界现存的 {@code HOUSEHOLD} 家户 actor —— 唯一拼写点 {@code
   *       HouseholdActors}）：就取它<b>那一本账</b>（会话工作副本，裁定 K1），需求 = <b>整份</b>，缸里不够就扣光 ⇒ 收获日投入那一路自然缩。 ★
   *       这就是"<b>佃农穷 ⇒ 地荒</b>"：tenant 档的 operator 就是佃农家户 ⇒ 它的缸空 ⇒ 规模 0，且**没有"同层"可补** （单一主体 ⇒
   *       ③（二）那道补齐无事可做）；
   *   <li>★★ <b>供方是聚合主体</b>（{@code ESTATE} / {@code WORKSHOP} / 产业型 {@code HOUSEHOLD}：id 是产业 id，如
   *       {@code farm@0_0}）：<b>economy 看不见它的账</b> —— 那本账住在 actor 切片的 {@code HouseholdInventory} 上（键
   *       {@code (actor, location)}），而会话工作副本按 {@code HouseholdId} 索引、只有家户账进得来（铁律 3：economy 不认识
   *       actor 切片；S1 起家户账经 {@code HouseholdActors.of} 派生 actor 后落进 AccountSession）。⇒ 由
   *       <b>该产业名下的家户账代理它</b>（"这个主体的缸" = 它名下那些家户的缸，与"谁供给这个产业"同一条唯一事实 {@link
   *       SettlementIndex#householdsOf}）。★ 这<b>不是</b>"按人口猜<b>谁</b>出料"（谁是供方已经由 relation 说了），而是"指名的主体
   *       <b>内部</b>怎么摊"的管道口径：先按人口占比问一遍（负担按人头摊），再由**同层还有货的账**补齐 ⇒ 只要这个主体**整体**出得起，
   *       需求就<b>一分不少</b>地筹齐（改前那种"逐行取整 ⇒ 取不满"不再发生）；
   * </ol>
   *
   * <p>★★ <b>H3 删掉的那条通道：同格取材（R4 的 T0）—— 为什么删</b>：改前 {@code transferIntraHexInputs} 会把"同格富余的
   * 纤维"搬给缺料的家户（R4 用它把田里的纤维送到城里的作坊）。H3 把它<b>整块删掉</b>，理由两条，都是实测得到的：
   *
   * <ol>
   *   <li>★ <b>它按 rowSharesOf 分摊</b>（"该行想要多少"），而那正是本批要消灭的口径 —— 留着它，本批的改动就只改了一半；
   *   <li>★★ <b>它会跨主体抢料（实测）</b>：把"第一层不够就从同格别人那里补"接进本步之后，真档出现 —— 织机（农村家户的账， 自己缸里 18,600,000）按"整个产业的
   *       740 台"去取料 ⇒ 缺口 3,600,000 ⇒ **把城里作坊自己的 2,100,000 纤维取走** ⇒ 作坊缺纤维 ⇒ 规模 0 ⇒ **工具产量 0**（{@code
   *       EconomyRealScaleClothTest} 实测：{@code heldByOperator(CRAFT, SMALL_TOOL)} 由 169,750 掉到
   *       <b>0</b>）。⇒ "谁出料"已经由 relation 说了，取料就<b>只从那个主体的账上取</b>；跨主体的余缺调剂是**市场** 的活（H4 的同格池），不是本步的活。
   * </ol>
   *
   * <p>★ <b>删掉它之后 R4 那条判据仍然成立</b>（有实测）：织机吃的纤维来自**它自己名下那些家户**（农村四行），而农田的纤维副产 经关系规则<b>正落在那些行上</b> ⇒
   * 每个周期都拿得到新料，{@code weavingContinuesEveryCycleBecauseTheFieldsFeedTheLooms} 照旧绿（那正是 R4 的 T0
   * 要表达的事，只是通道从"跨行搬运"变成了"同一本账"）。
   *
   * <p>★★ <b>"只取用得上的量"</b>：{@code usableScale} 的上限里含**除本商品之外**每种投入的可供量（改前是逐行的 {@code
   * rowUsageScale}，H3 起按产业算一次）—— 真档里城市作坊缺铁时，纤维<b>搬过去也是白扔</b>（会在现扣步被当投入扣掉而产出为 0， 织机反而拿不到料）。★
   * <b>劳动那一路刻意不算进来</b>：周期第一天 {@code cycleLaborMilli} 还是 0，把它算进来会让**所有**产业的 上限都是 0（取材永不发生）；劳动瓶颈在周期末的
   * {@link ProductionEfficiencyBook} 里照旧生效。
   *
   * <p>★★ <b>落账（P2-A §13.3：双方都是家户账）</b>—— 见 {@link #recordInputDraw}：
   *
   * <pre>
   * 供方家户 ≠ 经营者家户（"地主出种"那类）⇒ 一条 INPUT_REQUISITION 转移（供方家户 → 经营者家户），
   *   由**唯一** applier（{@link #applyTransferToHouseholds}）落到副本上（供方 −、经营者 +），随后**在经营者账上消费掉**
   *   这一笔（{@code consumed} 记在经营者：它是这批料的消费者）⇒ 净额：供方 −drawn、经营者 0；
   * 其余情形（供方家户 == 经营者家户；或经营者解析不到家户 —— 聚合主体）⇒ **不产生转移**：投入是"被生产直接吃掉"的，
   *   按 H2 的既有口径记账（供方 −余额、{@code consumed} 记在供方、{@code ledger.addInput}）。
   * </pre>
   *
   * <p>★★ <b>H6-lite：同格中间品的争用 ⇒ 一个池子 + 按需求比例配给（改前是"先到先得"）</b>。★ 它修的是一个**实测到**的病 （不是推测）：真档一格 14,806
   * 人 + 一座城（1,777 人）里，作坊第 2 个周期起**永久停工**，而原因**不是**"织机把纤维抢走"（H5 台账里那句是错的， 见下），而是 <b>作坊按 relation
   * 指名的那本账（= 它的经营者，由城镇家户代理）从创世那一份起**再也没有进项**</b> —— 纤维是农田的副产， 经关系规则落在**农村**家户的账上（ {@code
   * EconomyRealScaleClothTest#probeFibreAccountsPerCycle} 的实测：城镇缸 2,100,000 → <b>0</b>、农村缸逐周期
   * 18,600,000 → 18,042,000 → …，作坊的布入账 130,926 → <b>0</b>、<b>0</b>）。而织机与作坊**在同一格**、要的是**同一种商品** ⇒
   * 谁先跑谁先拿（{@code industries} 的插入序 = farm → weave → craft）。
   *
   * <pre>
   * ① 调查（只读）：逐产业算出"它能取的那些账"（relation 名下的**家户账**，聚合主体由劳动家户代理）与逐商品的可供量
   * ② 争用     ：★ **同一格里 ≥2 个产业要同一种投入** ⇒ 这一种投入在本格是一个**池子**
   *              池 = 这些需求方各自的账之**并集**（关系仍是"谁能进池"的权威：池里只有被 relation 指名过的账）
   *              逐需求方的可供量 := **整个池子**（各家户账之和）                 （⇒ 规模可以按池子算）
   * ③ 配给     ：D = Σ need、P = 池内余额；D &gt; P ⇒ quota = 按 need 比例（最大余数法，**Σquota == P**）；D ≤ P ⇒ quota = need
   * ④ 落账     ：逐产业、逐商品，需求上限 = quota（无争用 ⇒ 就是 need），取用次序见下（自己名下先、池里别人名下后）
   * </pre>
   *
   * <p>★ <b>为什么"池"只含被 relation 指名过的账</b>：这是 H3 那条"谁出料写在数据里"的**下限守住** —— 拓宽的只是**同一池子内的取用次序**，
   * 不是"谁出料"；且**只有争用才开池**（一格只有一个需求方时，逐值 = 改前）。
   *
   * <p>★ <b>它保住了的两条既有机构</b>：① <b>缸空 ⇒ 地荒</b>（tenant 档的种子只从佃农家户那一本账出；种子只有农业要 ⇒ **从不争用** ⇒ 从不进池）；②
   * <b>无争用 ⇒ 逐值不变</b>（今天的全部夹具与判据都在这一档里）。
   *
   * <p>★ <b>本轮明确不做的（如实记）</b>：中间品**仍不走市场**（没有价格、没有货款流转 —— 市场只管口粮/布的缺口），
   * 故这条池子配给是"跨主体余缺调剂"的**权宜口径**；正解是让经营者参与市场（H4 的市场今天只有家户参与）。★ 边界：**只有城市的格** （无农村人口 ⇒ 没有织机 ⇒
   * 纤维无人争用）里的作坊**照旧第 2 周期停工** —— 它那一格没有任何一条 relation 把乡下的纤维指给它。
   *
   * <p>★ {@code population == 0} 的家户**不分摊**（份额恒 0 ⇒ 与"它不出工"同义 —— 与改前的门槛逐字一致），但**可以是供方** （relation
   * 指名它，或它缸里有货要兜底同格时）：<b>出料不需要人口</b>。
   *
   * <p>★ **扣掉的量并入当日 {@code consumed}**：投入是**本期的消费**（spec §二 把"留种的计量"列在"数"里），记进去才能保住 §6.1
   * 的守恒式（否则配了投入的世界上那条等式不成立 ⇒ 端到端的守恒用例会变成假绿）。
   *
   * <p>★ <b>为什么不在这里扣"每日原料"</b>：{@code dailyInputPerUnit} 是**每日**口径，本轮仍是零读取点（spec §3.3
   * 明说两个字段并存、语义各自清楚），不在本步范围。
   *
   * <p>★★★ <b>S-11（v3.7，2026-10-11 用户第七批裁定）的落点就在第一遍</b>：{@code InputPlan.capacityScale}
   * 不再是"计划规模"本身， 而是 <b>计划规模 × 投入满足率</b>（最短缺那一项投入的 Leontief 满足率，逐户按手头库存现算）—— 详见 {@link
   * #inputSatisfactionPerMille}。满足率为 0 的 unit 与"本格没有产能"同支（不进池、不要料、不产出）。 ★ 口粮缺口**不**走这条（劳动与人口是
   * Social 的唯一权威，W3 另报）。
   *
   * <p>★ <b>W2-fix3</b>：返回"产能被投入短缺扣掉"的日志读数（{@link CapacityDeductionSummary}；只服务 §一.9 的 INFO 汇总，
   * 不进任何算式）。★ 本方法的调用者是**分区 worker**（一个分区一次调用），故它只把读数交回去、<b>不</b>发射 INFO —— 由协调器把全部分区（+
   * 无格那一批）合并后**一次**发射，否则分区数会把"一条 INFO"变成"每分区一条"。
   */
  static CapacityDeductionSummary drawCycleInputs(
      LinkedHashMap<ProductionUnitId, ProductionProcess> units,
      Map<IndustryId, IndustryDefinitionView> industries,
      LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
      SettlementIndex index,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      LinkedHashMap<HouseholdId, Map<CommodityId, Long>> consumedGoods,
      Map<ActorRef, HouseholdId> householdOfActor,
      ProductionLedger.Accumulator ledger,
      long day) {
    // ── 第一遍：调查（**只读**：不改任何账）────────────────────────────────────────────
    List<InputPlan> plans =
        surveyInputDemands(
            units,
            industries,
            householdEconomies,
            relations,
            operatorConditions,
            index,
            householdGoods,
            householdOfActor);
    // ── 第二遍：同格争用 ⇒ 开池 + 按需求比例配给（H6-lite；无争用 ⇒ 一个字段都不动）────────
    rationContestedInputs(plans, householdGoods);
    // ── 第二遍b：★★★ S-11 定规模 —— 实际产能 = 计划产能 × 投入满足率（最短缺那一项投入）。
    //   位置必须在开池**之后**：池子把 available 换成"取料口径"的可供量，S-11 的"拿不到"必须按同一份量算
    //   （否则"自己账上没料、同格池子里有"的 unit 会被误判成产能 0 ⇒ 把 H6-lite 那条实测修好的通道堵死）。
    //   ★★ W2-fix3：逐 unit 明细下 DEBUG；INFO 只发**一条** —— 读数在这里累计、由调用方（协调器线程）在
    //   全部分区合并后发射（本方法是一次分区调用，自己发射就会变成"每分区一条 INFO"）。
    CapacityDeductionSummary deductions = new CapacityDeductionSummary(day);
    for (InputPlan plan : plans) {
      applyInputSatisfaction(plan, day, deductions);
    }
    // ── 第三遍：逐 unit 落账（需求上限 = 配给额；无争用 ⇒ 就是它自己算出来的 need）──────────
    for (InputPlan plan : plans) {
      drawOneProductionProcessInputs(
          plan,
          units,
          householdEconomies,
          householdGoods,
          householdMoney,
          consumedGoods,
          householdOfActor,
          ledger);
    }
    return deductions;
  }

  /**
   * ★★ <b>一个 unit 本周期的投入计划</b>（H6-lite 的三遍式里"调查"的产物）。
   *
   * <p>★ <b>为什么要有它</b>：H6-lite 的配给要**先看清全部需求方**才谈得上"按需求比例" —— 逐 unit 就地算就地扣的旧写法
   * 天然是"先到先得"。故把"算"与"扣"分成两遍：本类只在第一遍被读写，第二遍改 {@link #available}（争用的商品换成池子） 与 {@link #quota}，第三遍才动账。
   *
   * <p>★ 字段的可变性是**有意的、且被限制在三处**：{@code available} 只被 {@link #rationContestedInputs} 覆盖， {@code
   * quota}/{@code sharedKeys} 只由它填；★ S-11 起 {@code capacityScale} 只被 {@link
   * #applyInputSatisfaction} 覆盖 ⇒ 第三遍是纯读。
   */
  static final class InputPlan {

    /** unit id（唯一键）。 */
    final ProductionUnitId id;

    /** unit 本体（**第一遍的那一份**：第三遍只读它的 operator / cycleInputUsedMilli 并写回新状态）。 */
    final ProductionProcess unit;

    /** 本格（unit.industry 解析出来的那一格；"同格争用"的分组键）。★ 产业 id 里没有格键 ⇒ null（它不属于任何一格 ⇒ 不参与争用）。 */
    final HexCoord hex;

    /** 逐单位规模要的商品（= {@code industry.inputPerUnit()}；只读）。 */
    final Map<CommodityId, Long> perScale;

    /** 产业模板（S-11 的 INFO 日志要报它；只读）。 */
    final IndustryDefinitionView industry;

    /** ★★ <b>计划规模</b>（= {@code ProductionProcessBook.plannedCapacityScaleOf}；只读）。 */
    final long plannedScale;

    /**
     * ★★★ S-11（v3.7）：<b>实际产能</b> = 计划规模 × 投入满足率（{@link #inputSatisfactionPerMille}，最短缺那一项投入）。 ★
     * 可变、且**唯一写点**是 {@link #applyInputSatisfaction}（H6-lite 第二遍之后：必须等池子把 available 换成取料口径）。
     */
    long capacityScale;

    /** relation 名下的家户账（保序去重；取料的第二层与"补齐"层）。 */
    final List<HouseholdId> ownKeys;

    /** ② 逐商品可供量：{@link #ownKeys} 的家户账（争用的商品在第二遍被换成池子）。 */
    final Map<CommodityId, Long> available;

    /** ★ 只有**争用**的商品才进表：本 unit 该商品的取料上限（第二遍填）。 */
    final Map<CommodityId, Long> quota = new LinkedHashMap<>();

    /** ★ 只有**争用**的商品才进表：这一种料的池子（= 各需求方 {@link #ownKeys} 的并集）。 */
    final Map<CommodityId, List<HouseholdId>> sharedKeys = new LinkedHashMap<>();

    private InputPlan(
        ProductionUnitId id,
        ProductionProcess unit,
        HexCoord hex,
        IndustryDefinitionView industry,
        Map<CommodityId, Long> perScale,
        long plannedScale,
        List<HouseholdId> ownKeys,
        Map<CommodityId, Long> available) {
      this.id = id;
      this.unit = unit;
      this.hex = hex;
      this.industry = industry;
      this.perScale = perScale;
      this.plannedScale = plannedScale;
      this.capacityScale = plannedScale; // 先记计划规模；applyInputSatisfaction 折算成实际产能
      this.ownKeys = ownKeys;
      this.available = available;
    }

    /**
     * 本 unit 在 {@code commodity} 上的取料上限：争用的商品 = 配给额，其余 = 规模折出来的需求。 ★ S-11（v3.7）起再对**实际产能**折出的需求取
     * min：配给额是按**计划规模**算的，而 S-11 可能把这一轮的产能 调低（拿不到料）⇒ 取料不得越过"实际产能需要的量"（旧代码里 quota ≤ need 恒成立，这一层 min
     * 是恒等的）。
     */
    long limitOf(CommodityId commodity, long usableScale) {
      long need = usableScale * perScale.getOrDefault(commodity, 0L);
      Long ration = quota.get(commodity);
      return ration != null ? Math.min(ration, need) : need;
    }
  }

  /**
   * ★★ <b>调查：逐 unit 算出它本周期"想开多大、要什么料、能从哪些账取"</b>（H6-lite 第一遍；**只读**）。
   *
   * <pre>
   * available_j = (供方自己的账在副本里 ? 它在 j 上的余额 : 0) + Σ_{k ∈ ownKeys} 库存_k,j
   * </pre>
   *
   * <p>★★★ <b>S-11（v3.7，2026-10-11 用户第七批裁定）：供不应求才扣产能 —— 生产阶段的落点是 {@link
   * #applyInputSatisfaction}</b>（本方法只记计划规模；折算在 H6-lite
   * 的池子成形之后）。用户原话：「如果市场供不应求了，单个家户拿不到足够的生产资料/口粮，这时候才算开始扣产能」。本批只做<b>生产资料那一支</b>：
   *
   * <pre>
   * 计划规模 S0 = plannedCapacityScaleOf(unit, industry, index, condition)        （= 本格产能 × 状态机计划系数）
   * 计划需求 q_j = S0 × inputPerUnit_j                                            （固定配方 / Leontief）
   * 实际到手 h_j = Σ_{k ∈ ownKeys} 库存_k,j                                       （★ 家户手头库存**现算**，不新增状态）
   * 逐项满足率 r_j = q_j ≤ 0 ? 1000 : min(1000, ⌊h_j × 1000 ÷ q_j⌋)
   * 投入满足率 r   = min_j r_j                                                     （★ 最短缺那一项，不取加权平均）
   * 实际产能 S     = ⌊S0 × r ÷ 1000⌋                                               （= 计划产能 × 满足率）
   * </pre>
   *
   * <p>★ <b>"拿不到"= 物理短缺，不是穷</b>：本算式只读<b>库存</b>，一分钱都不看 —— 债务式清算下"没钱也能买" （未付价款记债，见 {@code
   * MarketSettlement.executeTrade}），买不到只可能是没货/运力不足/口岸拦下。
   *
   * <p>★ <b>口粮缺口不进这条</b>（S-11 第②半）：劳动与人口是 Social 的唯一权威，本方法既不改人口也不改可提供劳动； 口粮缺口由 W3 的通道报社会。
   *
   * <p>★ <b>谁是"需求方"</b>：周期第一天、配了投入、且**实际产能 &gt; 0**（{@code capacityScale > 0}）的 unit。★ 产能为 0 的 unit
   * **不进池也不参与争用** —— 它一根料都不会要（旧口径在 {@code usableScale <= 0} 那一支被跳过，读数逐值相同）。
   */
  static List<InputPlan> surveyInputDemands(
      LinkedHashMap<ProductionUnitId, ProductionProcess> units,
      Map<IndustryId, IndustryDefinitionView> industries,
      LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<ProductionUnitId, OperatorCondition> operatorConditions,
      SettlementIndex index,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<ActorRef, HouseholdId> householdOfActor) {
    List<InputPlan> plans = new ArrayList<>();
    for (ProductionUnitId id : new ArrayList<>(units.keySet())) {
      ProductionProcess unit = units.get(id);
      IndustryDefinitionView industry = industries.get(unit.industry());
      if (industry == null) {
        throw new IllegalStateException("生产单元指名的产业模板不存在（状态已被改坏）: " + unit);
      }
      if (index.assetRejectionOf(id).isPresent()) {
        continue; // ★★★ D4：预算具名拒绝的 unit 在执行前不扣任何周期投入（本日不半推进）。
      }
      if (unit.progressDays() != 0L) {
        continue; // 只有周期的第一天扣投入
      }
      Map<CommodityId, Long> perScale = industry.inputPerUnit();
      if (perScale.isEmpty()) {
        continue; // ★ 空表与"缺键"同义：不扣、不缩规模 ⇒ 未配投入的 unit 行为与 V2 一字不差
      }
      long plannedScale =
          ProductionProcessBook.plannedCapacityScaleOf(
              unit, industry, index, operatorConditions.get(id));
      if (plannedScale <= 0L) {
        continue; // 本格没有产能 / 已缩到 0 ⇒ 它不产出、也不要料（"地荒着"）
      }
      // ★★ H3：**谁出料由 relation 说**。缺 relation（合法状态）⇒ 退回缺省 = operator。
      ProductionRules relation = relations.get(id);
      Payee supplier =
          relation == null ? new Payee.ToActor(unit.operator()) : relation.inputSupplier();
      List<HouseholdId> ownKeys =
          supplierAccountsOf(supplier, unit, industry, householdEconomies, householdOfActor, index);
      // ① 可供量（逐商品）= relation 名下的家户账（聚合主体已由 supplierAccountsOf 解析到 unit 名下的家户账）。
      Map<CommodityId, Long> available = new LinkedHashMap<>();
      for (CommodityId commodity : perScale.keySet()) {
        long sum = 0L;
        for (HouseholdId key : ownKeys) {
          sum += stockOf(householdGoods, key, commodity);
        }
        available.put(commodity, sum);
      }
      // ② ★★★ S-11：这里先记**计划规模**；"实际产能 = 计划产能 × 投入满足率"在第二遍之后统一折算
      //   （{@link #applyInputSatisfaction}）—— 必须等 H6-lite 的池子把 available 换成"取料口径"之后再算，
      //   否则"自己账上一根料都没有、但同格池子里有"的 unit 会被误判成产能 0（那正是 H6-lite 要修的实测病）。
      plans.add(
          new InputPlan(
              id,
              unit,
              // ★ 拿不到格键 ⇒ null（**不抛**：改前这条路径也不需要格，{@code householdKeysOf} 会给空表 ⇒ 逐值同旧），
              //   它因此不属于任何一格、也就不参与"同格争用"。
              IndustryHexKeys.hexKeyOf(unit.industry()).map(HexCoord::parse).orElse(null),
              industry,
              perScale,
              plannedScale,
              ownKeys,
              available));
    }
    return plans;
  }

  /**
   * ★★★ <b>S-11（v3.7，2026-10-11 用户第七批裁定）：定规模 —— 实际产能 = 计划产能 × 投入满足率</b> （H6-lite 第二遍之后、落账之前；逐 plan
   * 一次）。
   *
   * <p>它读的 {@code available} 是<b>取料口径</b>的可供量（争用的商品在第二遍已被换成池子）—— 与第三遍实际取料、 与 {@link #usableScaleOf}
   * 用的是同一份量，三处不可能漂开。★ 它同时把"拿不到多少"记进 {@link CapacityDeductionSummary}（逐 unit 明细下 DEBUG， INFO
   * 一条轮汇总由协调器发射）。
   */
  static void applyInputSatisfaction(
      InputPlan plan, long day, CapacityDeductionSummary deductions) {
    long satisfactionPerMille =
        inputSatisfactionPerMille(plan.perScale, plan.available, plan.plannedScale);
    long actualScale = plan.plannedScale * satisfactionPerMille / 1_000L;
    plan.capacityScale = actualScale;
    if (satisfactionPerMille < 1_000L && (TRACE.isInfoEnabled() || TRACE.isDebugEnabled())) {
      // ★★ W2-fix3（§一.9）：扣减发生 ⇒ ① 逐 unit 明细下 DEBUG（PRODUCTION_CAPACITY_DEDUCTED_DETAIL）；
      //   ② 累进"本轮汇总"，轮末由协调器发一条 INFO（PRODUCTION_CAPACITY_DEDUCTED）。
      //   ★ 两个级别都关 ⇒ 连"最短缺那一项"都不复算（结算路径一个字段都不多算；它纯属日志读数，不进任何算式）。
      CommodityId scarcest = scarcestInputOf(plan.perScale, plan.available, plan.plannedScale);
      deductions.record(plan, scarcest, satisfactionPerMille, actualScale);
      logCapacityDeductionDetail(day, plan, scarcest, satisfactionPerMille, actualScale);
    }
  }

  /**
   * ★★★ <b>S-11（v3.7，2026-10-11 用户第七批裁定）：投入品满足率 —— "最短缺那一项投入"的 Leontief 口径</b>。
   *
   * <pre>
   * 计划需求 q_j = plannedScale × inputPerUnit_j        （固定配方；逐商品一条）
   * 逐项满足率 r_j = q_j ≤ 0 ? 1000 : min(1000, ⌊hand_j × 1000 ÷ q_j⌋)
   * 投入满足率 r   = min_j r_j                          （★ 最短缺那一项；**不取加权平均**）
   * </pre>
   *
   * <p>★ 语义（用户原话「单个家户拿不到足够的生产资料…这时候才算开始扣产能」）：<b>拿不到 = 物理短缺</b>。本算式只看 <b>库存</b>，不看钱 ——
   * 债务式清算下"没钱也能买"（未付价款记债），所以"买不到"只能是没货/运力不足/口岸拦下。
   *
   * <p>★ 缺省中性：没有任何一项投入（{@code inputPerUnit} 为空、或每单位需求 ≤ 0）⇒ 返回 1000 ⇒ 实际产能 = 计划产能 （与改前逐值相同）。
   *
   * <p>★ 与 {@link #usableScaleOf} 的分工：本方法把"<b>产能</b>那一路"从计划规模折成实际产能， {@code usableScaleOf}
   * 仍把"可供量那一路"（含 H6-lite 的池子）取 min —— 两者读<b>同一份</b> {@code available}（第二遍之后的取料口径）， 因此缺省中性（料够 ⇒ 满足率
   * 1000）与"缸空 ⇒ 地荒"两支逐值不变，差异只到<b>千分比取整</b>这一级。 ★ <b>刻意不按"本户自己的账"算</b>：relation 名下的账在争用时会被 H6-lite
   * 换成同格池子（同格作坊+织机的纤维之争）， 按自家账算会把"自己账上没料、池子里有"的 unit 判成产能 0 ⇒ 堵死那条已实测修好的通道（见 {@link
   * #drawCycleInputs} 的注）。 ★ 也不按"家户合并算"：Leontief 的固定比例是<b>每条生产方式（unit）</b>的口径，把一户名下多个 unit
   * 的投入并成一份最小 满足率会让一条产线的缺料去砍另一条产线（本批不做，记为待裁定）。
   *
   * @param perScale 逐商品的单位规模需求（{@code industry.inputPerUnit()}；只读）
   * @param hand 逐商品的实际到手量（= {@code available}：relation 指名账上的库存合计）
   * @param plannedScale 计划规模（≥ 0）
   * @return 满足率（0..1000 的千分比）
   */
  static long inputSatisfactionPerMille(
      Map<CommodityId, Long> perScale, Map<CommodityId, Long> hand, long plannedScale) {
    if (plannedScale <= 0L) {
      return 0L;
    }
    long satisfaction = 1_000L;
    for (Map.Entry<CommodityId, Long> entry : perScale.entrySet()) {
      Long per = entry.getValue();
      if (per == null || per <= 0L) {
        continue; // 每单位需求 ≤ 0 ⇒ 这一路不构成约束（与 usableScaleOf / 收获日公式同口径）
      }
      long required = plannedScale * per; // 计划的需求量（毫商品）
      if (required <= 0L) {
        continue;
      }
      long onHand = hand.getOrDefault(entry.getKey(), 0L);
      long rate = onHand >= required ? 1_000L : Math.max(0L, onHand) * 1_000L / required;
      satisfaction = Math.min(satisfaction, Math.min(1_000L, rate));
    }
    return satisfaction;
  }

  /**
   * ★★ W2-fix3：<b>最短缺那一项投入</b>（与 {@link #inputSatisfactionPerMille} 同一判据；只为日志复算一次，不改算式）。
   *
   * <p>★ 它是"扣产能"两条日志的共同输入：轮汇总的商品分布计数（{@link CapacityDeductionSummary}）与逐 unit DEBUG 明细的 {@code
   * scarcestInput/scarcestOnHand/scarcestRequired} 三个字段。
   *
   * @return 最短缺的商品（没有任何一项构成约束 ⇒ null）
   */
  static CommodityId scarcestInputOf(
      Map<CommodityId, Long> perScale, Map<CommodityId, Long> hand, long plannedScale) {
    CommodityId scarcest = null;
    long scarcestPerMille = 1_000L;
    for (Map.Entry<CommodityId, Long> entry : perScale.entrySet()) {
      Long per = entry.getValue();
      if (per == null || per <= 0L) {
        continue;
      }
      long required = plannedScale * per;
      if (required <= 0L) {
        continue;
      }
      long onHand = hand.getOrDefault(entry.getKey(), 0L);
      long rate = onHand >= required ? 1_000L : Math.max(0L, onHand) * 1_000L / required;
      rate = Math.min(1_000L, rate);
      if (rate < scarcestPerMille) {
        scarcestPerMille = rate;
        scarcest = entry.getKey();
      }
    }
    return scarcest;
  }

  /**
   * ★★ W2-fix3（§一.9）：<b>本轮"产能被投入短缺扣掉"的汇总账 —— INFO 只发一条</b>。
   *
   * <p>改前 {@code PRODUCTION_CAPACITY_DEDUCTED} 是<b>逐 unit 一条 INFO</b>（真档一轮可能上百行）。现在：
   *
   * <pre>
   * 逐 unit 明细 → DEBUG  PRODUCTION_CAPACITY_DEDUCTED_DETAIL（字段见 {@link #logCapacityDeductionDetail}）
   * 轮汇总       → INFO   PRODUCTION_CAPACITY_DEDUCTED（事件名保留；语义从"逐 unit"变成"轮汇总"）
   *                      day / units（被扣 unit 数）/ households（被扣家户数，去重）/ totalPlannedScale /
   *                      totalActualScale / totalDeductedScale / maxDeductedPerMille（最大扣减比例，千分比）/
   *                      scarcestInputs（"最短缺投入"的商品分布计数）/ why
   * </pre>
   *
   * <p>★ <b>它只是日志读数</b>：{@code capacityScale} 早已由 {@link #applyInputSatisfaction}
   * 定完，本类不参与任何算式、不改任何状态。 ★ 每份实例只被<b>一个</b>分区 worker（或协调器单线程那一段）触碰，分区之间在协调器上经 {@link #merge} 合并 ⇒
   * 没有跨线程共享的可变状态。
   *
   * <p>★ <b>为什么必须合并后再发</b>：{@link #drawCycleInputs} 是分区 worker（一次调用 = 一个分区）⇒ 就地发射会变成"每分区一条
   * INFO"；合并由协调器按分区计划序做 ⇒ 行数恒为 1，且 {@code scarcestInputs} 用 {@link TreeMap}（商品 id 升序）⇒ 字段值与线程调度、
   * 合并次序都无关。
   */
  static final class CapacityDeductionSummary {

    /** 本轮的 day（全部分区同一天）。 */
    private final long day;

    /** 被扣的 unit 数。 */
    private int units;

    /** 被扣的家户（去重；只读它的 size = "几户被扣"）。 */
    private final Set<HouseholdId> households = new LinkedHashSet<>();

    /** 计划规模合计（与 {@link InputPlan#plannedScale} 同单位）。 */
    private long totalPlannedScale;

    /** 实际产能合计（与 {@link InputPlan#capacityScale} 同单位）。 */
    private long totalActualScale;

    /** 最大扣减比例（千分比 = 1000 − 最低满足率）。 */
    private long maxDeductedPerMille;

    /** "最短缺投入"的商品分布计数（{@link TreeMap} ⇒ 按商品 id 升序，跨分区合并次序不影响渲染）。 */
    private final Map<String, Integer> scarcestInputs = new TreeMap<>();

    CapacityDeductionSummary(long day) {
      this.day = day;
    }

    /**
     * 记一个<b>真被扣了产能</b>的 unit（只被 {@link #applyInputSatisfaction} 在满足率 &lt; 1000‰ 时调用）。
     *
     * <p>★ 用基本型 {@code +=} 而不是 {@code Math.addExact}：这是日志读数，<b>任何情况下都不许抛</b>（日志失败不得影响结算）。
     */
    private void record(
        InputPlan plan, CommodityId scarcest, long satisfactionPerMille, long actualScale) {
      units++;
      households.addAll(plan.ownKeys);
      totalPlannedScale += plan.plannedScale;
      totalActualScale += actualScale;
      maxDeductedPerMille = Math.max(maxDeductedPerMille, 1_000L - satisfactionPerMille);
      scarcestInputs.merge(scarcest == null ? "none" : scarcest.value(), 1, Integer::sum);
    }

    /** ★ 分区账合并（协调器按分区计划序调用；各分区同一天）。 */
    void merge(CapacityDeductionSummary other) {
      units += other.units;
      households.addAll(other.households);
      totalPlannedScale += other.totalPlannedScale;
      totalActualScale += other.totalActualScale;
      maxDeductedPerMille = Math.max(maxDeductedPerMille, other.maxDeductedPerMille);
      for (Map.Entry<String, Integer> entry : other.scarcestInputs.entrySet()) {
        scarcestInputs.merge(entry.getKey(), entry.getValue(), Integer::sum);
      }
    }

    /** ★ §一.9：轮末<b>一条 INFO 汇总</b>；本轮一次扣减都没发生（或 INFO 关着）⇒ 一条都不发（与改前"只在真扣减时发射"同口径）。 */
    void logSummary() {
      if (units == 0 || !TRACE.isInfoEnabled()) {
        return;
      }
      EventLog.channel(TRACE)
          .info(
              LogEvent.of(
                  "PRODUCTION_CAPACITY_DEDUCTED",
                  EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
                  "day",
                  day,
                  "units",
                  units,
                  "households",
                  households.size(),
                  "totalPlannedScale",
                  totalPlannedScale,
                  "totalActualScale",
                  totalActualScale,
                  "totalDeductedScale",
                  totalPlannedScale - totalActualScale,
                  "maxDeductedPerMille",
                  maxDeductedPerMille,
                  "scarcestInputs",
                  scarcestInputs,
                  "why",
                  "S-11：实际产能 = 计划产能 × 最短缺投入的满足率（拿不到 = 物理短缺，非因穷）；逐 unit 明细见 PRODUCTION_CAPACITY_DEDUCTED_DETAIL"));
    }
  }

  /**
   * ★★ <b>同格争用 ⇒ 开池 + 按需求比例配给</b>（H6-lite 第二遍；见 {@link #drawCycleInputs} 的方法注释）。
   *
   * <pre>
   * 争用 = 同一格里 ≥2 个需求方要同一种投入（逐商品判；只有本周期真开工的 unit 算需求方）
   * 池   = 这些需求方 ownKeys 的**并集**（保序去重：需求方序 × 各自的 ownKeys 序）  ← relation 决定谁能进池
   * 每家的可供量_j := 池内余额（各家户账之和）⇒ 规模可以按"本格这一种料的总量"算
   * D = Σ need_j、P = 池内余额；D > P ⇒ quota = 按 need 比例（最大余数法，Σquota == P）；否则 quota = need_j
   * </pre>
   *
   * <p>★ <b>无争用 ⇒ 一个字段都不动</b>（{@code quota}/{@code sharedKeys} 都留在空表里）⇒ 单需求方的世界的全部读数逐值不变。
   */
  static void rationContestedInputs(
      List<InputPlan> plans, Map<HouseholdId, Map<CommodityId, Long>> householdGoods) {
    // ① 归集：格 → 商品 → 需求方（保序；键是格 + 商品，值是该商品在本格的全部需求方）。
    Map<HexCoord, Map<CommodityId, List<InputPlan>>> byHex = new LinkedHashMap<>();
    for (InputPlan plan : plans) {
      if (plan.hex == null || plan.ownKeys.isEmpty()) {
        continue; // ★ 产业 id 里没有格键、或 relation **一本账都没指名**（没有人供给它）⇒ 它不进池、也不从池里取
      }
      Map<CommodityId, List<InputPlan>> byCommodity =
          byHex.computeIfAbsent(plan.hex, key -> new LinkedHashMap<>());
      for (Map.Entry<CommodityId, Long> entry : plan.perScale.entrySet()) {
        if (entry.getValue() <= 0L) {
          continue; // 每单位需求为 0 ⇒ 它**不是**这一种料的需求方（同收获日公式的口径）
        }
        byCommodity.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).add(plan);
      }
    }
    // ② 逐（格，商品）判争用 ⇒ 开池 + 配给。
    for (Map<CommodityId, List<InputPlan>> byCommodity : byHex.values()) {
      for (Map.Entry<CommodityId, List<InputPlan>> entry : byCommodity.entrySet()) {
        CommodityId commodity = entry.getKey();
        List<InputPlan> demanders = entry.getValue();
        if (demanders.size() < 2) {
          continue; // ★ 只有一个需求方 ⇒ 逐值 = 改前（"缸空 ⇒ 地荒"那条机构也在这一档里）
        }
        List<HouseholdId> pool = new ArrayList<>();
        for (InputPlan plan : demanders) {
          for (HouseholdId key : plan.ownKeys) {
            if (!pool.contains(key)) {
              pool.add(key);
            }
          }
        }
        long poolStock = 0L;
        for (HouseholdId key : pool) {
          poolStock += stockOf(householdGoods, key, commodity);
        }
        // 逐需求方：可供量换成池子 ⇒ 规模按池子算；同时记下池子（第三遍取料要用）。
        for (InputPlan plan : demanders) {
          plan.available.put(commodity, poolStock);
          plan.sharedKeys.put(commodity, pool);
        }
        // 需求 = 规模 × 每单位用量，而"规模"与第三遍同一条算式（产能那一路 × 每种投入的可供量那一路，取小）。
        long[] needs = new long[demanders.size()];
        long totalNeed = 0L;
        for (int i = 0; i < demanders.size(); i++) {
          InputPlan plan = demanders.get(i);
          needs[i] = usableScaleOf(plan) * plan.perScale.getOrDefault(commodity, 0L);
          totalNeed += needs[i];
        }
        if (totalNeed <= 0L) {
          continue; // 谁都用不上这一种料（池子空、或每单位需求为 0）⇒ 不必配给
        }
        long[] quotas =
            totalNeed > poolStock
                ? ProportionalSplit.byDenominator(poolStock, needs, totalNeed)
                : needs;
        for (int i = 0; i < demanders.size(); i++) {
          demanders.get(i).quota.put(commodity, quotas[i]);
        }
      }
    }
  }

  /**
   * 规模 = <b>min(产能那一路, 逐投入的可供量那一路)</b>（= 改前 {@code rowUsageScale} 的 unit 版）。
   *
   * <p>★★ S-11（v3.7）起"产能那一路" = {@link InputPlan#capacityScale} = **计划规模 × 投入满足率**（见 {@link
   * #inputSatisfactionPerMille}）；"可供量那一路"照旧按 {@code available} 算（争用的商品在第二遍被换成池子）。
   *
   * <p>★★ <b>它是唯一拼写点</b>：第二遍"这一家想要多少"与第三遍"这一家实际取多少"必须是**同一把尺**。
   */
  static long usableScaleOf(InputPlan plan) {
    long usable = plan.capacityScale;
    for (Map.Entry<CommodityId, Long> per : plan.perScale.entrySet()) {
      if (per.getValue() <= 0L) {
        continue; // 每单位需求为 0 ⇒ 这一路不构成约束（同收获日公式的口径）
      }
      usable = Math.min(usable, plan.available.getOrDefault(per.getKey(), 0L) / per.getValue());
    }
    return usable;
  }

  /** ★★ 索引口径的产能规模（R4-B.3a-perf；算式仍由 {@link ProductionProcessBook} 唯一拼写点给出）。 */
  static long capacityScaleOf(
      ProductionProcess unit, IndustryDefinitionView industry, SettlementIndex index) {
    return ProductionProcessBook.capacityScaleOf(unit, industry, index);
  }

  /** ★★ 索引口径的计划规模（R4-B.3a-perf；产能规模查入口索引，计划系数算式不变）。 */
  static long plannedCapacityScaleOf(
      ProductionProcess unit,
      IndustryDefinitionView industry,
      SettlementIndex index,
      OperatorCondition condition) {
    return ProductionProcessBook.plannedCapacityScaleOf(unit, industry, index, condition);
  }

  /** ★★ 索引口径的“投入没凑齐”判据（R4-B.3a-perf；算式与旧签名逐字相同，只是计划规模查索引）。 */
  static boolean inputShortfallOf(
      ProductionProcess unit,
      IndustryDefinitionView industry,
      SettlementIndex index,
      OperatorCondition condition) {
    long planned = ProductionProcessBook.plannedCapacityScaleOf(unit, industry, index, condition);
    if (planned <= 0L) {
      return false;
    }
    for (Map.Entry<CommodityId, Long> entry : industry.inputPerUnit().entrySet()) {
      if (entry.getValue() <= 0L) {
        continue;
      }
      long required = entry.getValue() * planned;
      long used = unit.cycleInputUsedMilli().getOrDefault(entry.getKey(), 0L);
      if (used < required) {
        return true;
      }
    }
    return false;
  }
}
