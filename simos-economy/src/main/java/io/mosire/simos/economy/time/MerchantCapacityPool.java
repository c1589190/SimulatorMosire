package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MerchantPolicy;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>M-A1/M-A2：逐 hex 运力池（派生量，一轮一份、瞬态、不落状态）</b>。
 *
 * <p>★★ <b>它取代了什么</b>：旧 {@code MerchantSettlement.CarrierPool}（"货主挑商号"：按服务半径 + 有效到货费率序 挑 {@code
 * MerchantFirm}，容量就地扣、周期末把 {@code lastFee/upkeep/profit} 写回持久组件）。用户 2026-10-10 裁定 （设计书 §11/§14）：商号
 * = <b>提供运力的家户</b>；运力是<b>每轮算出来的派生量</b>；商号行（{@code MerchantFirm} / {@code
 * EconomyData.merchantFirms}）整体退役。
 *
 * <p>★★ <b>M-A2 新口径（本批冻结）</b>：
 *
 * <pre>
 * 报价     每个提供者的**限价**是一个**纯派生量**（{@link #askPerMilleOf}：派生承运成本 × 规模档 + 具名固定"上门"附加费）——
 *          ★★ A3 起**没有"自报价簿"**（{@code CapacityQuote}/{@code CapacityQuoteBook} 已整族退役）：服务成市的 lane
 *          上，钱由**市场牌价**（{@code Market.prices[haul]}）定，本项只作"从最低价起买"的**选择序**与缺省口径的价。
 * 同价序   限价相同 ⇒ 沿用 M-A1 的 canonical 序（市场议价权占比‰ 降序 → 家户 id 升序）——"价格管买方的选择、
 *          议价权管稀缺时的分配"（Q-25），**不另设特权队列**。
 * 需求口径 报价口径下运力按**数量 × 距离**（{@link CapacityDemand#workPerGoodPerMille}，毫商品·程）扣减预算；
 *          缺省口径（无报价）仍按毫商品 1:1（M-A1 逐值不变）。
 * 缺省中性 {@code priced = false}（夹具 / 纯状态读者）⇒ 序与价都退回 M-A1（I-C2），**不是**兼容位而是缺省语义中性。
 * </pre>
 *
 * <p>★★ <b>冻结口径（逐条落点）</b>：
 *
 * <pre>
 * J-A 「选择跑商」本身就是提供运力 ⇒ 成员判据 = effectivePositionIds() 里含 modeId == merchant 的位置
 * J-C 运力池 = 逐 hex 汇总该格"选了跑商"的家户的运力（{@link MerchantCapacity}）
 * ③  该格运力总量 ⇒ 该格商品的硬上限（本类 select 的 Σ 承接量 ≤ 该格运力预算）
 * ④  分配顺序 = 限价升序（报价口径）→ 市场议价权（占比‰ 降序 → 家户 id 升序；I7 确定性）
 * G-2 运力池挂**发货格**（select 的 from）
 * </pre>
 *
 * <p>★★★ <b>A3 阅读提示（2026-10-10）：下面这三段（G3-fix-2 / G3-leftovers / G3-fix-3）是**工具门槛的演进史**
 * ——门槛族已整族退役（见类末 A3 注与 {@code MerchantHaul} 的删除）⇒ 这三段**只作留痕**，不是现行判据。现行的
 * "本条能不能成立"只有一条判据：该条目还有没有服务货（{@code remainingWorkMilli &gt; 0}，I-H2），见 {@link #select}。
 *
 * <p>★★ <b>G3-fix-2（2026-10-10）：工具门槛读"当刻可用量"（时点 = 承运选择点，不是装配点）</b>—— G3-fix-1
 * 把门槛判据从<b>存量</b>改成<b>可用量</b>，但读的是<b>池装配点</b>的冻结表，而本轮的卖单冻结由 {@code MarketSettlement.commitFreezes}
 * 在<b>市场轮内</b>才落表 ⇒ 真实世界里减项恒为 {@code 0}（G3b 复验： {@code MERCHANT_HAUL_TOOL_SHORT_AT_COMMIT} 3,589
 * 条逐值未变、928 组同户同日"SHORT 具名 + {@code toolBurnedMilli=0}"、 其中 708 组连 {@code CARRIER_FEE_PAID} 一起成立 ——
 * 门槛不成立却照运、运费照铸）。本批把可用量的读取挪到选择点：
 *
 * <pre>
 * 时点  {@link #select} 内、逐条承运条目被判定的那一刻 —— 市场轮内、{@code commitFreezes} 之后，
 *       与提交侧 {@code EconomySettlement.consumeForLoss} 读的是**同一张活表**（{@code householdFrozenGoods}）
 * 算式  判据量 = **当刻可用量**（G3-fix-3：减项"本轮已放行 × 每趟门槛"已删 —— 活视图已含本轮燃烧，见下）；
 *       不足一趟 ⇒ **该条跑商不成立**（不放行 = 不成交、不铸 CARRIER_FEE、不烧工具；具名归因见当时的 {@code MerchantHaul} reason 常量（A3 已删该族，见类末 A3 注））
 * 缺省  无冻结概念（4/5 参旧路径，没有活视图）⇒ 判据量 ≡ 本轮预算余量 ⇒ 与改前**逐值相同**（I-C2）
 * </pre>
 *
 * <p>★★ <b>G3-leftovers（2026-10-10）：判据量的"预算"改成<b>当刻推导</b>（不再取装配时点的过期镜像）</b>—— G3-fix-2 的 {@code
 * min(本轮预算余量, 当刻可用量)} 两个操作数取自<b>不同时点</b>："本轮预算余量"是**装配时点**的 {@code max(0, 现货 − 冻结)} 镜像（{@link #of}
 * 的 6 参重载 → Entry 构造，此后每放行一趟扣 1,000），而"当刻可用量"是选择点的活视图。轮内该户工具 <b>上升</b>（买工具 / 产业投入）时，门槛就拿**旧的低值**拦人
 * —— A 世界实测：按该旧低值拦下 29 行 / Σ 5,824 趟（最小样本 {@code day=153}：{@code stock=1610 available=1610
 * needed=1000 预算余量=925}），归因全部落在当时的**第三档**（预算档；该档已随 G3-fix-3 删除，见下）。本批把判据量换成 {@code max(0, 当刻可用量 −
 * 本轮已放行 × 每趟门槛)}：底数永远是当刻 活视图，减项 = 本轮自己已放行的趟数（{@link Entry#releasedToolMilli()}，由装配镜像 − 剩余推出）⇒
 * <b>预算随放行实时递减</b>。 ★ 该减项**已被 G3-fix-3 删除**（它就是下面那条"同一笔扣两次"）；本段保留作判据演进的历史叙述。
 *
 * <p>★ <b>方向仍 fail-closed（只过严不过宽）</b>，三档逐值可查：① 轮内该户工具<b>不动</b> ⇒ 当刻可用量 = 装配可用量 ⇒ {@code max(0, 可用量
 * − 已放行)} ≡ 装配镜像剩余 = 旧式 {@code min(剩余, 可用量)} —— <b>逐值相同</b>；② 工具<b>下降</b>（或冻结上升）⇒ 新判据 ≤ 旧判据（更严）；③
 * 只有工具<b>上升</b>时才放松 —— 那正是本次要修的偏差（被拦趟数减少、跨格成交回升）。已放行的减项若与提交侧 已落账的实扣<em>重复</em>，也只是让判据更严，绝不放松。 ★★
 * <b>G3-fix-3 更正</b>：末句"重复也只是更严，绝不放松"的推理**站不住** —— 过严不是安全侧（门槛是硬门槛，不是按量计的费）： 它把同一笔燃烧扣两次，在 B 世界造成 25
 * 趟**假拦**（见下）。
 *
 * <p>★★ <b>G3-fix-3（2026-10-10）：删掉判据量里的"本轮已放行 × 每趟门槛" —— 活视图已含本轮的燃烧，再减一次就是同一笔扣两次</b>。 两条结构直证 +
 * 一条实测直证：
 *
 * <pre>
 * ① 同一张活表  {@code liveGoods} = 装配入口传进来的 {@code householdGoods}，与实扣 {@code EconomySettlement.consumeForLoss}
 *              写的是**同一个 Map 实例**（{@code setStock} 就地 put ⇒ 池持有的引用当刻可见）
 * ② 落账次序    实扣在同一次 {@code executeTrade} 的 {@link #select} **之后**（{@code MarketSettlement.settleHaulRuns}）、
 *              下一笔 lane 的 select **之前** ⇒ 后一次 select 读到的可用量**已经**扣掉了前一趟的燃烧
 * ③ 实测直证    A 世界同户同日：旧行 {@code stock=1879} → 新行 {@code stock=879}，差**恰 1000**（= 一趟门槛）
 *              —— 不是"活视图没变"，是"真烧了一趟之后活视图少了 1000"
 * 后果（G3d 复验）B 世界 25 趟假拦：{@code day=213 stock=1465 frozen=0 available=1465 needed=1000 released=2000}
 *              ⇒ 旧式 {@code 1465 − 2000 < 1000} 被拦，而**两种成文口径都放行**（活视图 {@code 1465 ≥ 1000}；
 *              或回到装配量 {@code 1465 + 2000 = 3465}，第 3 趟只用到 3,000）
 * 结论          判据 = {@code 当刻可用量 ≥ 一趟}（旧 {@code MerchantHaul.affordsRun}）；旧 {@code Entry.releasedToolMilli()}
 *              **不再参与判据**，只作日志自解释（{@code MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT.releasedMilli}）
 * 单调性        新判据 ≥ 旧判据（逐点，减项非负）⇒ 只会**减少**拦、不会新增拦；fail-closed 方向不变（真不够 ⇒ 拦）
 * 归因副作用    "拦 ⇔ 可用量 &lt; 一趟" ⇒ 归因只剩 {@code tool-short}（真缺货）与 {@code tool-frozen}（被冻结占住）
 *              两支（旧 {@code MerchantHaul.blockedReason} 二选一）；第三档归因（预算档）在**生产路径与 4/5 参旧路径上都结构上不可达**
 *              ⇒ 2026-10-10 清理：连同它的 reason 常量、只服务它的计数（逐户 + 汇总计数 + 逐户日志字段）与
 *                 "本轮装配镜像余量"那个逐行日志字段一并删除（判据一字未动）
 * </pre>
 *
 * <p>★ <b>"当刻"是哪一个时点（口径写清）</b>：{@code select} 只被 {@code MarketSettlement.executeTrade} 在
 * <b>协调器</b>上逐条 lane 调用（有运力池的世界整个区内撮合都退回串行，见 {@code matchWithinRegions}），且 {@code select}
 * 自身<b>不写账户</b>（烧工具在它之后的 {@code settleHaulRuns}）⇒ 一次 {@code select} 内所有条目读到的都是同一个账户状态；两次 {@code
 * select} 之间账户可能已变（上一笔的烧工具已落账）， 那正是"每一条承运都按它被判定的那一刻"的严格口径。
 *
 * <p>★★ <b>一轮一份、不累积</b>：每次装配都从当刻状态重算（{@code 不落状态、不储存、不转卖}）。{@code select} 就地扣 剩余运力 ⇒
 * 本类<b>只允许协调器单线程使用</b>；并行 worker 副本拿 {@link #empty()}（与旧 CarrierPool 同款约定）。
 *
 * <p>★★ <b>缺省中性（冻结项 6）</b>：无报价（家户未挂运力单）⇒ 分配口径与价格逐值退回 M-A1 ⇒ 世界里没有"选了跑商的家户"时 {@link #hasCapacityAt}
 * 恒 false ⇒ 跨格（跨区 + 同区跨格）路线在候选生成处就<b>不建</b>、具名 {@code LOGISTICS_CAPACITY}；<b>同 hex
 * 成交一字不动</b>（它不走运力）。
 *
 * <p>★★ <b>2026-10-10：池侧"未服务量"读数的去重（★ 只动读数与说明，判据/成交/价格/运力分配一字未动）</b>—— 事件 {@code
 * MERCHANT_CAPACITY_LANE_TRUNCATED} 原来只报<b>本次观察</b>的 {@code unallocatedMilli}，而<b>同一次请求</b>（同一车道键 +
 * 同一对买卖槽）会在多个运力窗口（{@code MARKET_MAX_TRANSPORT_ROUNDS = 4}）与多次试配上被反复观察：反复看到的是<b>同一份</b>没运走的量 ⇒
 * 逐行相加会把它算 2~36 遍（把同一车道上量相同的<b>不同</b>请求合并看，最高 72 次）。G3f 实测 A 世界 103 天：Σ 逐行 = 真实量的 3.0~3.45
 * 倍。现在每行另外报三个字段，<b>口径写死在这里</b>：
 *
 * <pre>
 * unallocatedMilli            本次观察的原始值 —— ❌ **不可**逐行相加当真实量（同一请求会重复）
 * unallocatedNetMilli         本次净额       —— ✅ **可**当真实量：对全部行求和 = 每份未服务量只算一次的合计
 * unallocatedObservationIndex 该请求的第几次观察（1 起）—— ≥2 ⇒ 本行与前面某行是**同一份**量
 * requestKeyTracked          有没有请求身份（false = 4/5 参旧路径：本次调用自己成一份）
 * </pre>
 *
 * ⇒ 读"真实未承运量"只认 {@code Σ unallocatedNetMilli}（每日合计见 {@link #logRoundSummary} 的 {@code
 * unallocatedRawMilli} / {@code unallocatedNetMilli} 两栏的对照）。 请求身份 = {@link
 * LaneUnservedBook#pairKey}（与 D-1b 槽侧认领<b>同键同拼写</b>，由 {@code MarketSettlement.executeTrade} 拼好传入）；
 * 去重状态只活在 {@link LaneUnservedObservationBook} 里、只被上述日志字段读，<b>不参与任何判据</b>（与 D-1b 那份 <b>改</b>价格输入的
 * {@link LaneUnservedBook} 各自独立）。★ 缺省中性：没有截断 ⇒ 本事件一行不打、上簿一次不记 ⇒ 字段不出现、数值一字不变。
 *
 * <p>★★ <b>A3（2026-10-10）：跑商的"平行机器"退役收口（设计书 §3.4）</b> —— 本类保留为 <b>"派生需求 →
 * 服务货分配"的现算视图</b>，不再是跑商私有的门槛/定价/利润机器。
 *
 * <pre>
 * 已删（族）                                          取代者（标准管线的一环）
 * 工具门槛（每趟 ≥1,000 毫工具 + 烧工具 + 两种归因）    trade 产业的 cycleInputPerUnit（现扣 + 挂单保留）
 * 自报价簿 CapacityQuote/CapacityQuoteBook            市场牌价 Market.prices[haul]（服务成交的钱腿）
 * 平行利润读数 MerchantProfitBook                     EnterpriseProfitBook（MARKET_TRADE 收入腿）
 * §16 预留特例（necessaryInputsOf 下夹一趟）           产业声明投入的**标准**保留循环（I-H6）
 * </pre>
 *
 * <p>★ 保留（并与 A2 同口径）：① 供给 = 跑商家户手上的 {@code haul} 服务货（I-H2，卖多少就扣多少）； ② 分配序 = 限价升序 → 市场议价权降序 → 家户 id
 * 升序（I7 确定性，§3.3 的"同价按既有 canonical 序"）。 本类只做"**按格**把一份派生需求分配给持有服务货的家户"，不做门槛、不定价、不算利润。
 */
public final class MerchantCapacityPool {

  /**
   * ★★ <b>A3（2026-10-10）：已退役的"平行机器"族（§一.9 的 INFO 具名行：哪族退役了）</b>。
   *
   * <p>★ 单一拼写点：{@link #logRoundSummary} 把它打进 {@code MERCHANT_CAPACITY_POOL} 这条 INFO 的 {@code
   * retiredFamilies} 栏（与 {@link #RETIRED_PARALLEL_MACHINE_REPLACEMENTS} 配对读）。
   */
  public static final String RETIRED_PARALLEL_MACHINE_FAMILIES =
      "merchant-haul-tool-threshold(per-haul-tool-burn+tool-frozen/tool-short)"
          + ",merchant-profit-book(parallel-profit-readout)"
          + ",capacity-quote-book(per-household-self-quote)"
          + ",merchant-haul-tool-reserve(necessaryInputsOf-special-case)";

  /** ★★ <b>A3：上面每一族被<em>谁</em>取代</b>（与 {@link #RETIRED_PARALLEL_MACHINE_FAMILIES} 同序配对）。 */
  public static final String RETIRED_PARALLEL_MACHINE_REPLACEMENTS =
      "industry-cycle-input(trade.cycleInputPerUnit={CATTLE:{tool:100}})"
          + ",enterprise-profit-book(MARKET_TRADE-revenue-leg)"
          + ",market-haul-price(Market.prices[haul])"
          + ",industry-declared-input-necessaryInputsOf(standard-reserve)";

  /** 运力池日志（market 分类：它的生命周期就是市场轮）。 */
  private static final org.slf4j.Logger LOG = EconomyLog.market();

  /**
   * ★ <b>A3：{@link MerchantCapacity} 作业层的**工具维**键</b>（会话商品账口径；V-22：读的是商品账，**不动** {@code
   * AssetKind.TOOL} 产权份额）。
   *
   * <p>★ A3 起它<b>不再是门槛</b>（旧 {@code MerchantHaul.TOOL_COMMODITY} 的那套"每趟烧 1,000 毫工具"已删）：
   * 这里读它只为把运力规模（tier / share 的派生依据）算出来。⇒ 收成 {@code private}（不再是对外拼写点）。
   */
  private static final CommodityId TOOL_COMMODITY =
      new CommodityId(EconomyVocabulary.TOOL_COMMODITY_ID);

  /** 空池（没有跑商家户的世界 / worker 本地副本）。 */
  private static final MerchantCapacityPool EMPTY =
      new MerchantCapacityPool(Map.of(), Map.of(), Map.of(), 0L, false, 0L, Set.of());

  /** ★★ <b>A2：走"运输服务货"口径的格集</b>（空集 = 缺省口径，逐值退回改前）。只作日志归因（"为什么这一格买不到运力"）。 */
  private final Set<HexCoord> haulServiceHexes;

  /** 逐格池（键序 = hex 的规范序；值按分配序、不可变）。 */
  private final Map<HexCoord, List<Entry>> byHex;

  /** 逐格运力预算（毫商品·程＝报价口径 / 毫商品＝缺省口径；与 {@link #byHex} 同键集）。 */
  private final Map<HexCoord, Long> totalCapacityByHex;

  /** 有运力的家户数（INFO 汇总用）。 */
  private final long householdCount;

  /** 本轮的运力预算总额（毫商品·程＝报价口径 / 毫商品＝缺省口径；INFO 汇总用）。 */
  private final long totalCapacityMilli;

  /** 家户 → 它的池条目（{@link #recordFee} 用；键域 = 上面所有条目的家户）。 */
  private final Map<HouseholdId, Entry> byHousehold;

  /** 是否成市（报价口径）。{@code false} ⇒ 缺省口径（M-A1 逐值不变）。★ A3 起由调用方显式给（不再由报价表推出）。 */
  private final boolean priced;

  /** 本轮所有提供者的最高限价（‰；报价口径下 "计划单位运费" 的上界）。 */
  private final long maxAskPerMille;

  /**
   * ★★ <b>2026-10-10：池侧"未服务量观察"的净额簿</b>（{@link LaneUnservedObservationBook}）—— <b>只服务日志读数</b>：
   * 同一请求被反复观察时只净记一次，供 {@link #select} 的 DEBUG 字段与 {@link #logRoundSummary} 的日合计读。 ★
   * 它<b>不参与任何判据</b>、不改 {@link CarrierAllocation} 的任何数值；逐轮瞬态（本池本身一轮一份）。
   */
  private final LaneUnservedObservationBook unservedObservations =
      new LaneUnservedObservationBook();

  private MerchantCapacityPool(
      Map<HexCoord, List<Entry>> byHex,
      Map<HexCoord, Long> totalCapacityByHex,
      Map<HouseholdId, Entry> byHousehold,
      long householdCount,
      boolean priced,
      long maxAskPerMille,
      Set<HexCoord> haulServiceHexes) {
    this.byHex = byHex;
    this.totalCapacityByHex = totalCapacityByHex;
    this.byHousehold = byHousehold;
    this.householdCount = householdCount;
    this.priced = priced;
    this.maxAskPerMille = maxAskPerMille;
    this.haulServiceHexes =
        haulServiceHexes == null
            ? Set.of()
            : Collections.unmodifiableSet(new LinkedHashSet<>(haulServiceHexes));
    long sum = 0L;
    for (long value : totalCapacityByHex.values()) {
      sum = Math.addExact(sum, value);
    }
    this.totalCapacityMilli = sum;
  }

  public static MerchantCapacityPool empty() {
    return EMPTY;
  }

  /**
   * ★★ <b>装配一轮的运力池（缺省口径：无报价）</b>—— 价格与分配序逐值退回 M-A1（I-C2）。
   *
   * <p>夹具 / 纯状态读者 / 旧调用方走这一条；生产路径走带 {@code haulServiceHexes} 的 7 参重载。
   */
  public static MerchantCapacityPool of(
      Map<HouseholdId, HouseholdClassMembership> classStandings,
      Map<ClassPositionId, ProductionRole> classPositions,
      Map<HouseholdId, HouseholdEconomy> rows,
      Map<HouseholdId, Map<CommodityId, Long>> goods) {
    return assemble(classStandings, classPositions, rows, goods, Map.of(), false, null);
  }

  /**
   * ★★ <b>装配一轮的运力池（唯一入口；每轮重算，不累积）</b>。
   *
   * <p>成员判据（J-A）：{@code classStandings} 里该家户的 {@code effectivePositionIds()} 含任一 {@code
   * classPositions} 中 {@code modeId == merchant} 的位置。运力算式见 {@link MerchantCapacity}；限价见 {@link
   * {@link #askPerMilleOf}（报价口径）。
   *
   * <p>★ <b>本重载 = 无冻结概念</b>（工具可投入量取**装配点的存量**；夹具 / 纯状态读者 / 旧调用方）⇒ 与改前**逐值相同**（I-C2 缺省语义中性）。★ 它只影响
   * {@code MerchantCapacity}（作业层）的"工具可投入量"这一项读数，**不再**是任何门槛的判据（A3 起工具由产业周期投入消耗，见类注）。
   *
   * @param classStandings 家户 → 阶层归属（主业 = current、副业 = participating）
   * @param classPositions 位置 → 生产方式（判"是不是跑商"）
   * @param rows 家户行（劳动投入 + 所在格）
   * @param goods 会话商品账（读 {@code tool} 存量 ⇒ {@link MerchantCapacity} 的工具项；没有商品账时传空表 ⇒ 工具项 = 0，见 J-2
   *     下界）
   * @param priced 本轮是否走报价口径（{@code true} = 生产路径：限价层参与分配序、运力按"数量 × 距离"扣；
   *     {@code false} = 夹具 / 纯状态读者 ⇒ 缺省口径，逐值退回 M-A1）
   */
  public static MerchantCapacityPool of(
      Map<HouseholdId, HouseholdClassMembership> classStandings,
      Map<ClassPositionId, ProductionRole> classPositions,
      Map<HouseholdId, HouseholdEconomy> rows,
      Map<HouseholdId, Map<CommodityId, Long>> goods,
      boolean priced) {
    return assemble(classStandings, classPositions, rows, goods, Map.of(), priced, null);
  }

  /**
   * ★★ <b>带冻结账的运力池（无服务口径）</b>：{@code frozenGoods} 只用于 ① {@link MerchantCapacity} 的工具可投入量（{@code
   * max(0, 现货 − 冻结)}）与 ② 服务成市格的 {@code haul} 可用量（本重载 {@code haulServiceHexes = null} ⇒ 服务口径关闭）。
   *
   * <p>★ <b>A3（2026-10-10）</b>：本重载此前还带"工具维活视图"（{@code select} 里的当刻门槛判据）——**那个门槛已随 {@code
   * MerchantHaul} 一并删除**（工具消耗单套化到产业周期投入），故活视图两参已删。
   *
   * @param frozenGoods 会话冻结商品账；没有冻结概念时传 {@code Map.of()}
   * @param priced 见 5 参重载
   */
  public static MerchantCapacityPool of(
      Map<HouseholdId, HouseholdClassMembership> classStandings,
      Map<ClassPositionId, ProductionRole> classPositions,
      Map<HouseholdId, HouseholdEconomy> rows,
      Map<HouseholdId, Map<CommodityId, Long>> goods,
      Map<HouseholdId, Map<CommodityId, Long>> frozenGoods,
      boolean priced) {
    return assemble(classStandings, classPositions, rows, goods, frozenGoods, priced, null);
  }

  /**
   * ★★ <b>A2（2026-10-10）：服务商品口径下的运力池</b>—— 供给来自跑商家户手上的**运输服务货**（A1 起 {@code trade@hex}
   * 的产出入既有货物账），不再由"劳动投入 + 工具存量"派生。
   *
   * <pre>
   * 服务成市的格（haulServiceHexes 含该格）⇒ 该户本轮运力预算 = max(0, haul 现货 − haul 冻结)   ← 服务商品账，I-H2
   * 其余格                                ⇒ 逐值退回 {@link MerchantCapacity} 的既有算式（劳动 + 工具）
   * </pre>
   *
   * <p>★★ <b>为什么必须按格分流</b>：服务市场的开关是 {@code Market.hasPrice(haul)}（唯一拼写点 = {@link
   * HaulService#pricedAt}）—— 没给运输服务定价的格一个判据都不变 ⇒ <b>缺省中性（I-H3）逐表达式成立</b>， 而不是靠"算出来恰好相等"。★
   * 已定过价的格则必须由**真货**兜底：服务不能凭空造，卖出多少就得有多少货（I-H2）。
   *
   * <p>★★ <b>A3（2026-10-10）：工具门槛已删</b>——服务口径下"能不能跑"只由**手上有没有服务货**决定（I-H2）， 工具不再是跑商的市场轮门槛：它由 {@code
   * trade} 产业声明的周期投入经**标准生产管线**消耗 （{@code Industry.cycleInputPerUnit} → 现扣 + 挂单保留），见设计书 §3.2 与 §5
   * I-H6。
   *
   * @param haulServiceHexes <b>服务成市</b>的格（{@link HaulService#pricedAt} 为真的那些格）；{@code null} = 全都不是
   */
  public static MerchantCapacityPool of(
      Map<HouseholdId, HouseholdClassMembership> classStandings,
      Map<ClassPositionId, ProductionRole> classPositions,
      Map<HouseholdId, HouseholdEconomy> rows,
      Map<HouseholdId, Map<CommodityId, Long>> goods,
      Map<HouseholdId, Map<CommodityId, Long>> frozenGoods,
      boolean priced,
      Set<HexCoord> haulServiceHexes) {
    return assemble(
        classStandings, classPositions, rows, goods, frozenGoods, priced, haulServiceHexes);
  }

  /** <b>装配体（唯一拼写点）</b>。 */
  private static MerchantCapacityPool assemble(
      Map<HouseholdId, HouseholdClassMembership> classStandings,
      Map<ClassPositionId, ProductionRole> classPositions,
      Map<HouseholdId, HouseholdEconomy> rows,
      Map<HouseholdId, Map<CommodityId, Long>> goods,
      Map<HouseholdId, Map<CommodityId, Long>> frozenGoods,
      boolean priced,
      Set<HexCoord> haulServiceHexes) {
    Objects.requireNonNull(classStandings, "classStandings");
    Objects.requireNonNull(classPositions, "classPositions");
    Objects.requireNonNull(rows, "rows");
    Objects.requireNonNull(goods, "goods");
    Objects.requireNonNull(frozenGoods, "frozenGoods");
    List<HouseholdId> households = new ArrayList<>(rows.keySet());
    households.sort(Comparator.comparing(HouseholdId::value));
    Map<HexCoord, List<Entry>> pool = new LinkedHashMap<>();
    Map<HexCoord, Long> totals = new LinkedHashMap<>();
    long counted = 0L;
    long maxAsk = 0L;
    for (HouseholdId household : households) {
      HouseholdEconomy row = rows.get(household);
      HouseholdClassMembership standing = classStandings.get(household);
      if (row == null || !MerchantIdentity.selectsMerchant(standing, classPositions)) {
        continue;
      }
      // ★★ A3（2026-10-10）：工具维 = {@link MerchantCapacity} 的**作业层**可投入量（装配点的存量 − 冻结）。
      //   它**不再是任何门槛**（旧 "每趟烧 1,000 毫工具" 的门槛已随 MerchantHaul 删除）：工具由 trade 产业声明的
      //   周期投入经标准生产管线消耗（设计书 §3.2 / I-H6）⇒ 这里读它只为把"运力规模"（tier/share 的派生依据）
      //   算出来，改它不改任何一次承运的成败。
      long toolStockMilli =
          goods.getOrDefault(household, Map.of()).getOrDefault(TOOL_COMMODITY, 0L);
      long toolFrozenMilli =
          frozenGoods.getOrDefault(household, Map.of()).getOrDefault(TOOL_COMMODITY, 0L);
      long toolMilli = Math.max(0L, toolStockMilli - toolFrozenMilli);
      MerchantCapacity capacity =
          MerchantCapacity.of(
              household, row.view().hex(), row.participationAdjustedLaborMilli(), toolMilli);
      if (capacity.capacityMilli() <= 0L) {
        continue; // 0 运力 = 不进池（"有这家户"与"它提供运力"是两件事）
      }
      long askPerMille = askPerMilleOf(capacity, priced);
      maxAsk = Math.max(maxAsk, askPerMille);
      HexCoord hex = capacity.hex();
      // ★★ M-C：纯商号（H-2：**主业** ∈ merchant.*）/ 顺便跑商（merchant 只在副业）—— 免运费与利润算式的分流依据；
      //   判据的唯一拼写点在 {@link MerchantIdentity}。
      boolean pureMerchant = MerchantIdentity.isPureMerchant(standing, classPositions);
      // ★★ A2：服务成市的格 ⇒ 本轮运力预算 = 该户手上的**运输服务货**（max(0, 现货 − 冻结)）——
      //   卖出多少服务就得有多少货（I-H2），"服务不能凭空造"由此结构性成立；其余格逐值退回劳动+工具算式。
      //   ★ 单位锚（A1）：1 商品单位 haul = 1,000 毫服务 = 1,000 毫商品·程 ⇒ 这里不需要第二次换算。
      long workBudgetMilli =
          haulServiceHexes != null && haulServiceHexes.contains(hex)
              ? Math.max(
                  0L,
                  goods
                          .getOrDefault(household, Map.of())
                          .getOrDefault(HaulService.HAUL_COMMODITY, 0L)
                      - frozenGoods
                          .getOrDefault(household, Map.of())
                          .getOrDefault(HaulService.HAUL_COMMODITY, 0L))
              : capacity.capacityMilli();
      // ★★ A3：`posted`（有没有逐户挂运力单）随自报价簿退役 —— 现在**不存在**逐户覆写，"限价"是条目的纯派生量
      //   （{@link #askPerMilleOf}）⇒ 该读数栏恒为假、已从条目与日志里删除。
      pool.computeIfAbsent(hex, ignored -> new ArrayList<>())
          .add(new Entry(capacity, askPerMille, pureMerchant, workBudgetMilli));
      totals.merge(hex, workBudgetMilli, Math::addExact);
      counted++;
    }
    LinkedHashMap<HexCoord, List<Entry>> frozen = new LinkedHashMap<>();
    LinkedHashMap<HexCoord, Long> frozenTotals = new LinkedHashMap<>();
    LinkedHashMap<HouseholdId, Entry> frozenByHousehold = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, List<Entry>> entry : pool.entrySet()) {
      long total = totals.getOrDefault(entry.getKey(), 0L);
      List<Entry> entries = entry.getValue();
      for (Entry item : entries) {
        item.sharePerMille = item.capacity.sharePerMilleOf(total);
      }
      // ★★ 池内序 = 分配序（I7：内容的纯函数，不读哈希/插入序）：
      //   报价口径 ⇒ 限价升序（价格优先）→ 议价权降序 → 家户 id 升序（同价按 M-A1 的 canonical 序）
      //   缺省口径 ⇒ 议价权降序 → 家户 id 升序（逐字等于 M-A1）
      Comparator<Entry> order =
          Comparator.comparingLong((Entry item) -> -item.sharePerMille)
              .thenComparing(item -> item.capacity.household().value());
      if (priced) {
        order = Comparator.comparingLong((Entry item) -> item.askPerMille).thenComparing(order);
      }
      entries.sort(order);
      frozen.put(entry.getKey(), Collections.unmodifiableList(entries));
      frozenTotals.put(entry.getKey(), total);
      for (Entry item : entries) {
        frozenByHousehold.put(item.capacity.household(), item);
      }
    }
    MerchantCapacityPool built =
        new MerchantCapacityPool(
            Collections.unmodifiableMap(frozen),
            Collections.unmodifiableMap(frozenTotals),
            Collections.unmodifiableMap(frozenByHousehold),
            counted,
            priced,
            maxAsk,
            haulServiceHexes);
    // ★★ G3-leftovers：本池的逐户**装配**读数（事件 {@code MERCHANT_CAPACITY_HOUSEHOLD}）不在这里发射 ——
    //   本方法拿不到世界日（{@link #of} 的入参里没有 tick），而该事件按 §一.9 必须带 `day` 才能与同日的
    //   {@code MARKET_*} 逐户按日对齐。发射点 = tick 面的装配调用方
    //   （{@code EconomySettlement}，与 {@code CAPACITY_QUOTE_BOOK} 同款先例）：{@link
    // #logHouseholdAssembly(long)}。
    return built;
  }

  /** 纯状态派生查询（工具维取 0 ⇒ 运力下界，J-2）：该格有没有"选了跑商且有运力"的家户。 */
  public static boolean hasCapacityAt(EconomyData base, HexCoord hex) {
    return hexCapacityMilli(base, hex, Map.of()) > 0L;
  }

  /**
   * ★★ <b>M-D：该格运力总量（纯函数；与 {@link #of} 装配同一条算式）</b>—— 逐户 {@link MerchantCapacity#of}（劳动投入 +
   * 工具存量）后求和。
   *
   * @param goods 会话商品账（读 {@code tool} 存量）；没有商品账 ⇒ 传空表（工具项 = 0 ⇒ **运力下界**，J-2）
   */
  public static long hexCapacityMilli(
      EconomyData base, HexCoord hex, Map<HouseholdId, Map<CommodityId, Long>> goods) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(goods, "goods");
    long total = 0L;
    List<HouseholdId> households = new ArrayList<>(base.classStandings().keySet());
    households.sort(Comparator.comparing(HouseholdId::value));
    for (HouseholdId household : households) {
      long capacity = memberCapacityMilli(base, household, hex, goods);
      total = Math.addExact(total, capacity);
    }
    return total;
  }

  /**
   * ★★ <b>M-D：一个家户在本格的运力（纯函数；不是池成员 ⇒ 0）</b>—— 成员判据与装配同源（{@link
   * MerchantIdentity#selectsMerchant}），算式同源（{@link MerchantCapacity#of}）。
   */
  private static long memberCapacityMilli(
      EconomyData base,
      HouseholdId household,
      HexCoord hex,
      Map<HouseholdId, Map<CommodityId, Long>> goods) {
    HouseholdClassMembership standing = base.classStandings().get(household);
    if (!MerchantIdentity.selectsMerchant(standing, base.classPositions())) {
      return 0L;
    }
    HouseholdEconomy row = base.classes().get(household);
    if (row == null || !row.view().hex().equals(hex)) {
      return 0L;
    }
    long toolMilli = goods.getOrDefault(household, Map.of()).getOrDefault(TOOL_COMMODITY, 0L);
    return MerchantCapacity.of(household, hex, row.participationAdjustedLaborMilli(), toolMilli)
        .capacityMilli();
  }

  /**
   * ★★ <b>M-D / §11.4 G-1：一个家户在该格作为运力提供者的**市场议价权**（占比‰）</b>—— 排序输入之一（计划 §7 Q-19： 与 {@code
   * MerchantCapacityPool} 的分配序**同一口径、同一拼写点**，不得两套）。
   *
   * <pre>
   * 该户**就住在本格且已是池成员**（{@code selectsMerchant}）⇒ share = 该户运力 × 1000 ÷ 本格运力总量
   * 其余（本格候选新进入者 / 邻格准备迁入的候选）        ⇒ share = 该户运力 × 1000 ÷ (本格运力总量 + 该户运力)
   * </pre>
   *
   * <p>★★ <b>为什么邻格候选不返回 0</b>：运力池挂在**发货格**（G-2）且成员判据是"住在那格"；一个住在 A 格、 考虑把跑商当主业并**迁到** B 格的家户，迁到 B
   * 之后就在 B 的池里 —— 因此 B 格的议价权必须按"**新进入者**" 算（分母加上它自己），不能用"它现在不在 B"判成 0（那会把跨格进入跑商的路整条堵死）。
   *
   * <p>★ 除法与占比口径的唯一拼写点 = {@link MerchantCapacity#sharePerMilleOf}（本方法只负责"分母是哪一个"）。 ★ 纯状态读者（没有商品账）传
   * {@code Map.of()} ⇒ 工具项 0 ⇒ 占比是**下界**（J-2 的具名偏差，方向 fail-closed）。
   */
  public static long sharePerMilleAsProviderAt(
      EconomyData base,
      HouseholdId household,
      HexCoord hex,
      Map<HouseholdId, Map<CommodityId, Long>> goods) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(goods, "goods");
    HouseholdEconomy row = base.classes().get(household);
    if (row == null) {
      return 0L;
    }
    long toolMilli = goods.getOrDefault(household, Map.of()).getOrDefault(TOOL_COMMODITY, 0L);
    MerchantCapacity capacity =
        MerchantCapacity.of(household, hex, row.participationAdjustedLaborMilli(), toolMilli);
    if (capacity.capacityMilli() <= 0L) {
      return 0L; // 劳动 + 工具都是 0 ⇒ 没有运力可提供（占比 0，不猜）
    }
    boolean residentMember =
        row.view().hex().equals(hex)
            && MerchantIdentity.selectsMerchant(
                base.classStandings().get(household), base.classPositions());
    long total = hexCapacityMilli(base, hex, goods);
    if (!residentMember) {
      // 本格候选新进入者 / 邻格准备迁入的候选：把自己算进分母（"我进去之后占多少"）
      total = Math.addExact(total, capacity.capacityMilli());
    }
    return capacity.sharePerMilleOf(total);
  }

  /** 池里一条家户运力（可变剩余量；只允许协调器单线程触碰）。 */
  private static final class Entry {
    private final MerchantCapacity capacity;
    private final long askPerMille;
    private final boolean pureMerchant;

    private long remainingWorkMilli;
    private long allocatedGoodsMilli;
    private long allocatedWorkMilli;
    private long allocationCount;
    private long sharePerMille;

    /** ★ 本轮的运费实收（按币分列）—— **每轮算出的读数**，只进日志/读口，不落状态（冻结项 6 的"不落状态"）。 */
    private final Map<CurrencyId, Long> earnedByCurrency = new LinkedHashMap<>();

    private Entry(
        MerchantCapacity capacity, long askPerMille, boolean pureMerchant, long workBudgetMilli) {
      this.capacity = capacity;
      this.askPerMille = askPerMille;
      this.pureMerchant = pureMerchant;
      // ★★ A2：本轮运力预算的**唯一产生点** —— 缺省口径 = {@link MerchantCapacity#capacityMilli()}（劳动 + 工具，
      //   逐值不变）；服务成市的格 = 该户手上的运输服务货（{@code assemble} 现算，I-H2）。
      this.remainingWorkMilli = workBudgetMilli;
    }
  }

  /**
   * ★★ <b>A3（2026-10-10）："运力服务含上门"这一腿的具名固定附加费（‰）</b>：<b>25</b>。
   *
   * <p>★ <b>从旧 {@code CapacityQuote.GET_READY_SURCHARGE_PER_MILLE} 原值搬来</b>（自报价簿退役，这一档口径保留）：
   * 与既有承运成本的一档步长同值（{@link MarketSettlement#MARKET_FREIGHT_CARRIER_COST_PER_MILLE_PER_TIER_STEP} =
   * 25‰/档）——"一趟上门"按一个档次的运营成本计价，是粗估、可由 GM 后续调；<b>只改这一个常量就改上门腿</b>。 ★ <b>深度
   * 1</b>：它只加一次，不参与递归（用户原话「注意"运力"过来同样需要运力」的终止规则）；也不随 lane 距离变化。
   */
  public static final long GET_READY_SURCHARGE_PER_MILLE = 25L;

  /**
   * ★★ <b>A3（2026-10-10）：一个提供者的限价（‰）—— 纯派生、唯一拼写点</b>（旧 {@link CapacityQuote} 的两级入口 {@code
   * askPerMilleOf} / {@code selfQuotedPerMilleOf} 已随自报价簿退役，算式原值保留在此）。
   *
   * <pre>
   * 报价口径（priced = true，生产路径）   限价 = {@link MarketSettlement#carrierCostPerMille}(tier) + {@link #GET_READY_SURCHARGE_PER_MILLE}
   * 缺省口径（priced = false，夹具/读者）限价 = {@link MarketSettlement#carrierCostPerMille}(tier)   ← 逐值等于 M-A1 的派生承运成本
   * </pre>
   *
   * <p>★ 为什么缺省口径**不加**上门附加费：缺省口径下这不是"买运力"（没有服务市场），而是 M-A1 的按议价权分摊 —— 加一笔新费用会让"无报价 ⇒ 逐值不变"当场不成立。
   */
  static long askPerMilleOf(MerchantCapacity capacity, boolean priced) {
    Objects.requireNonNull(capacity, "capacity");
    long cost = MarketSettlement.carrierCostPerMille(capacity.tier());
    return priced ? Math.addExact(cost, GET_READY_SURCHARGE_PER_MILLE) : cost;
  }

  public boolean isEmpty() {
    return byHex.isEmpty();
  }

  /** 本轮是否成市（报价口径：家户挂了运力单/自报价）。{@code false} ⇒ 缺省口径（M-A1 逐值不变）。 */
  public boolean isPriced() {
    return priced;
  }

  /** 本轮所有提供者的最高限价（‰）；空池/无报价 ⇒ 0。用作"计划单位运费"的上界。 */
  public long maxAskPerMille() {
    return maxAskPerMille;
  }

  /** 这一格有没有运力（静态：装配时的运力 > 0；被本轮别处吃光不影响本判据 —— 那由 select/executeTrade 判）。 */
  public boolean hasCapacityAt(HexCoord hex) {
    return byHex.containsKey(hex);
  }

  /** 这一格的运力总量（毫商品·程＝报价口径 / 毫商品＝缺省口径；没有 ⇒ 0）。 */
  public long totalCapacityAt(HexCoord hex) {
    return totalCapacityByHex.getOrDefault(hex, 0L);
  }

  /** 这一格的提供者家户数（INFO 汇总用；没有 ⇒ 0）。 */
  public long householdCountAt(HexCoord hex) {
    return byHex.getOrDefault(hex, List.of()).size();
  }

  /** 这一格**剩下**的运力（毫商品·程＝报价口径 / 毫商品＝缺省口径；分配后就地减少）。 */
  public long remainingCapacityAt(HexCoord hex) {
    long remaining = 0L;
    for (Entry item : byHex.getOrDefault(hex, List.of())) {
      remaining = Math.addExact(remaining, item.remainingWorkMilli);
    }
    return remaining;
  }

  /** 有运力的家户数（INFO 汇总用）。 */
  public long householdCount() {
    return householdCount;
  }

  /**
   * ★★ <b>本轮的**跑商家户**（= 池成员，判据 {@link MerchantIdentity#selectsMerchant}）</b>。
   *
   * <p>★ A3 起唯一的生产调用点（"商号利润读数的范围"）已随 {@code MerchantProfitBook} 退役 ⇒ 现在只剩夹具/读口的
   * 键集查询用途（验收测试用它断言"谁进池了"）。★ 返回的是 {@link #byHousehold} 的键集（构造期已冻结、不可改）； 顺序由调用方自己规范（本仓 I7）。
   */
  public java.util.Set<HouseholdId> householdIds() {
    return byHousehold.keySet();
  }

  /** 有运力的格数（INFO 汇总用）。 */
  public int hexCount() {
    return byHex.size();
  }

  /** 本轮的运力总预算（毫商品·程＝报价口径 / 毫商品＝缺省口径；INFO 汇总用）。 */
  public long totalCapacityMilli() {
    return totalCapacityMilli;
  }

  /**
   * ★★ <b>本 lane 的运力耗用系数（‰）</b>——报价口径按"数量 × 距离"（{@link CapacityDemand}，毫商品·程）， 缺省口径按 M-A1 的 1:1（毫商品
   * ⇒ 逐值不变）。
   */
  public long workPerGoodPerMilleOf(long commodityBaseMilli, long ratePerMille) {
    if (!priced) {
      return CapacityDemand.GOODS_ONLY_WORK_PER_GOOD_PER_MILLE;
    }
    return CapacityDemand.workPerGoodPerMille(commodityBaseMilli, ratePerMille);
  }

  /**
   * ★★ <b>按买方选择键分配一条 lane 的运力</b>（缺省口径 1:1；等价于 M-A1 的旧签名）。
   *
   * @param from 发货格（运力池所在的格）
   * @param to 收货格（判半径）
   * @param quantityMilli 本笔请求承运量（毫商品）
   */
  public CarrierAllocation select(HexCoord from, HexCoord to, long quantityMilli) {
    return select(from, to, quantityMilli, CapacityDemand.GOODS_ONLY_WORK_PER_GOOD_PER_MILLE);
  }

  /**
   * ★★ <b>无请求身份的分配入口</b>（等价于 5 参重载传 {@code requestKey = null}）—— 夹具 / 纯状态读者 / 旧调用方走这一条。
   *
   * <p>★ 读数的差别只有一个：{@code MERCHANT_CAPACITY_LANE_TRUNCATED} 的 {@code requestKeyTracked=false}、
   * {@code unallocatedObservationIndex} 恒 1（每次调用自己成一份，因为这里没有"哪一次请求"的身份）。<b>算法与判据与 5
   * 参重载完全同一条路径</b>（见那个重载的说明），返回值逐值相同。
   *
   * @param from 发货格（运力池所在的格）
   * @param to 收货格（判半径）
   * @param quantityMilli 本笔请求承运量（毫商品）
   * @param workPerGoodPerMille 本 lane 的运力耗用（‰；{@link CapacityDemand}。缺省口径传 1000 = 1:1）
   */
  public CarrierAllocation select(
      HexCoord from, HexCoord to, long quantityMilli, long workPerGoodPerMille) {
    return select(from, to, quantityMilli, workPerGoodPerMille, null);
  }

  /**
   * ★★ <b>按买方选择键（限价）分配一条 lane 的运力</b>（K-C/K-2/Q-25/Q-27）。
   *
   * <pre>
   * 候选 = 发货格 from 的池 ∩（剩余运力预算 > 0）∩（lane 长度 ≤ 该家户派生半径）
   * 序   = 限价升序（报价口径；买方从**最低价**起买）→ 同价按市场议价权（占比‰ 降序 → 家户 id 升序）
   * 分配 = 逐条 min(剩余需求, 该户剩余预算还能承接的商品量)，就地扣减（报价口径按"数量 × 距离"扣）
   * 不变量 Σ choices.quantityMilli + unallocated == requested，且 Σ 扣减 ≤ 该格运力预算（A3）
   * </pre>
   *
   * <p>★ <b>排"限价"而不是排"容量"</b>：这正是用户裁定的"按市场上最低价的运力提供商买运力"；同价时才由市场议价权 （稀缺分配键）决定先后 ——
   * 两条各管一层（Q-25），<b>不另设特权队列</b>。
   *
   * <p>★ <b>供给不足（K-4/Q-27）</b>：没分到的部分（{@code unallocatedMilli}）由调用方收缩成交量 ⇒ <b>不成交、不成债、
   * 不计价</b>；被挡下的部分不提价不压价（V-20 的截断剔除）。本类不静默丢。
   *
   * <p>★★ <b>A3（2026-10-10）：本条能否成立只看服务货</b> —— 判据 = 该条目的 {@code remainingWorkMilli &gt; 0} （服务成市格 =
   * 手上的 {@code haul} 服务货，I-H2）。旧"工具门槛"（每趟 ≥ 1,000 毫工具，{@code tool-frozen} / {@code tool-short}
   * 两支归因）已随 {@code MerchantHaul} 整体退役：工具由 {@code trade} 产业的周期投入经标准生产管线 消耗（设计书 §3.2 / I-H6）⇒
   * 它不再是市场轮的准入判据。
   *
   * <p>★★ <b>2026-10-10 池侧读数去重（只动读数）</b>：{@code demandLeft > 0} 时本方法记一笔"未服务量观察"，并另发 {@code
   * unallocatedNetMilli} / {@code unallocatedObservationIndex} / {@code requestKeyTracked} 三个字段 ——
   * <b>口径写在类注里</b>（"Σ 原始读数不可当真实量、Σ 净额可以"）。★ 去重只读 {@code requestKey}、只写本类的观察簿， 不改本方法的任何返回值。
   *
   * @param from 发货格（运力池所在的格）
   * @param to 收货格（判半径）
   * @param quantityMilli 本笔请求承运量（毫商品）
   * @param workPerGoodPerMille 本 lane 的运力耗用（‰；{@link CapacityDemand}。缺省口径传 1000 = 1:1）
   * @param requestKey <b>请求身份</b>（{@link LaneUnservedBook#pairKey}：车道键 + 买槽序 &gt; 卖槽序）—— 只服务上面那三个
   *     日志字段的去重；{@code null} = 无身份（夹具 / 纯状态读者）⇒ 本次调用自己成一份
   */
  public CarrierAllocation select(
      HexCoord from, HexCoord to, long quantityMilli, long workPerGoodPerMille, String requestKey) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    if (quantityMilli < 0L) {
      throw new IllegalArgumentException("承运需求不得为负: " + quantityMilli);
    }
    if (workPerGoodPerMille <= 0L) {
      throw new IllegalArgumentException("运力耗用系数必须为正: " + workPerGoodPerMille);
    }
    List<Entry> pool = byHex.getOrDefault(from, List.of());
    List<CarrierChoice> choices = new ArrayList<>();
    long demandLeft = quantityMilli;
    long unreachableWork = 0L;
    long subUnitWork = 0L;
    for (Entry item : pool) {
      if (demandLeft <= 0L) {
        break;
      }
      if (!item.capacity.servesLane(to)) {
        unreachableWork = Math.addExact(unreachableWork, item.remainingWorkMilli);
        continue;
      }
      if (item.remainingWorkMilli <= 0L) {
        continue;
      }
      // ★★ A3（2026-10-10）：**这里原本还有一道"工具门槛"（每趟 ≥ 1,000 毫工具，缺则本条不成立）——已删**。
      //   理由（设计书 §3.2 / §3.4 / I-H6）：跑商的手工门槛被"跑商是一种生产方式"取代 ——
      //   工具由 {@code trade} 产业声明的**周期投入**（{@code cycleInputPerUnit = {CATTLE:{tool:100}}}）
      //   经标准生产管线现扣、并经标准挂单保留预留（{@code necessaryInputsOf} 的 {@code industry.inputPerUnit()} 循环）；
      //   市场轮里"每趟烧 1,000 毫工具"那一套（{@code MerchantHaul}）已整体退役。
      //   ⇒ 本条能否成立只看**手上有没有服务货**（{@code remainingWorkMilli}，I-H2），不再看工具存量。
      long maxGoods = CapacityDemand.maxGoodsFor(item.remainingWorkMilli, workPerGoodPerMille);
      if (maxGoods <= 0L) {
        // 剩余预算不足一个整商品单位（报价口径下"数量 × 距离"放大了耗用 ⇒ 这一步会真的发生）：
        // 它没有被吃掉，只是这一笔用不上；具名计数，不静默。
        subUnitWork = Math.addExact(subUnitWork, item.remainingWorkMilli);
        continue;
      }
      long take = Math.min(demandLeft, maxGoods);
      long consumed = CapacityDemand.workConsumedBy(take, workPerGoodPerMille);
      item.remainingWorkMilli -= consumed;
      item.allocatedGoodsMilli = Math.addExact(item.allocatedGoodsMilli, take);
      item.allocatedWorkMilli = Math.addExact(item.allocatedWorkMilli, consumed);
      item.allocationCount++;
      demandLeft -= take;
      choices.add(
          new CarrierChoice(
              item.capacity.carrier(),
              item.capacity.household(),
              item.capacity.hex(),
              item.capacity.tier(),
              item.askPerMille,
              item.pureMerchant,
              take,
              consumed));
    }
    long allocated = quantityMilli - demandLeft;
    // ★★ 2026-10-10：真有未服务量时先记一笔"观察"（无条件记 —— 与日志档位无关，否则 INFO 汇总会在只开 INFO 时假报 0），
    //   再按档位发 DEBUG 行。★ 记账结果**只被下面那几个字段读**：不改 demandLeft、不改 choices、不改返回值。
    if (demandLeft > 0L) {
      LaneUnservedObservationBook.Observation observation =
          unservedObservations.observe(requestKey, demandLeft);
      if (LOG.isDebugEnabled()) {
        EventLog.channel(LOG)
            .debug(
                LogEvent.of(
                    "MERCHANT_CAPACITY_LANE_TRUNCATED",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "from",
                    from,
                    "to",
                    to,
                    "requestedMilli",
                    quantityMilli,
                    "allocatedMilli",
                    allocated,
                    // ★★ 口径（类注同文）：这是**本次观察**的原始值 —— **不可**逐行相加当真实量（同一请求会被反复观察）。
                    "unallocatedMilli",
                    demandLeft,
                    // ★★ 净额（每份未服务量只算一次）：**对全部行求和 = 真实量**（可当真实量用）。
                    "unallocatedNetMilli",
                    observation.netMilli(),
                    // ★★ 该请求的第几次观察（1 起）：≥2 ⇒ 本行与前面某行是**同一份**量（重复倍数的下界读数）。
                    "unallocatedObservationIndex",
                    observation.observationIndex(),
                    // ★★ 有没有请求身份：false = 4/5 参旧路径（夹具/纯状态读者）⇒ 本次调用自己成一份。
                    "requestKeyTracked",
                    observation.requestKeyTracked(),
                    "workPerGoodPerMille",
                    workPerGoodPerMille,
                    "requestedWorkMilli",
                    CapacityDemand.workMilliOf(quantityMilli, workPerGoodPerMille),
                    "allocatedWorkMilli",
                    CapacityDemand.workMilliOf(allocated, workPerGoodPerMille),
                    "hexTotalCapacityMilli",
                    totalCapacityByHex.getOrDefault(from, 0L),
                    "outOfRadiusWorkMilli",
                    unreachableWork,
                    "subUnitResidualWorkMilli",
                    subUnitWork,
                    "priced",
                    priced,
                    "reason",
                    laneBlockedReason(
                        pool,
                        unreachableWork,
                        subUnitWork,
                        // ★★ A2：这一格是"服务货口径"却一件服务货都没有 ⇒ 具名归因（"为什么买不到"的第一现场）。
                        haulServiceHexes.contains(from)
                            && totalCapacityByHex.getOrDefault(from, 0L) <= 0L)));
      }
    }
    return new CarrierAllocation(choices, quantityMilli, demandLeft, priced);
  }

  /**
   * ★ <b>这一笔为什么没走完</b>（具名归因，按固定次序拼接：本格没有跑商家户 / 本格没有服务货 / 半径外 / 亚单位残余 / 运力耗尽）—— 只在 DEBUG
   * 行里出现，判据本身不改任何行为。★ 多个原因同时成立时**全部列出**（不挑一个代表性说法）。
   *
   * <p>★★ <b>A3（2026-10-10）</b>：原来还有两支"缺工具"归因（{@code tool-frozen} / {@code tool-short}）—— 门槛已删 （见
   * {@link #select}），归因随之退役。
   */
  private static String laneBlockedReason(
      List<Entry> pool, long unreachableWork, long subUnitWork, boolean serviceSupplyEmpty) {
    if (pool.isEmpty()) {
      return "no-merchant-household-in-shipping-hex";
    }
    List<String> reasons = new ArrayList<>(4);
    // ★★ A2：服务口径下"这一格一件服务货都没有"= 最典型的买不到（跑商家户还没产出/已卖光）⇒ 具名（唯一拼写点 = HaulService）。
    if (serviceSupplyEmpty) {
      reasons.add(HaulService.NO_SERVICE_SUPPLY_REASON);
    }
    if (unreachableWork > 0L) {
      reasons.add("out-of-derived-radius");
    }
    if (subUnitWork > 0L) {
      reasons.add("sub-unit-residual");
    }
    if (reasons.isEmpty()) {
      reasons.add("capacity-exhausted");
    }
    return String.join("+", reasons);
  }

  /**
   * ★★ <b>M-C：把一条承运条目的耗用折算成承运家户的**劳动投入**（毫小时）</b>—— 利润读数的"劳动力成本"维。
   *
   * <p>运力 = 劳动项 + 工具项（{@link MerchantCapacity}）⇒ 本条的劳动份额 = {@code 耗用 × 劳动 ÷ 运力}
   * （向上取整：宁可多算一分劳动成本，不静默少算）。查不到该户 ⇒ 0（不猜）。
   */
  public long laborHoursOf(HouseholdId household, long consumedWorkMilli) {
    Entry entry = byHousehold.get(household);
    if (entry == null || consumedWorkMilli <= 0L || entry.capacity.capacityMilli() <= 0L) {
      return 0L;
    }
    long product = Math.multiplyExact(consumedWorkMilli, entry.capacity.laborMilli());
    return (product + entry.capacity.capacityMilli() - 1L) / entry.capacity.capacityMilli();
  }

  /**
   * ★★ <b>A3（2026-10-10）：本类的"工具门槛"整族已退役</b> —— 原 {@code toolBlockedRuns()} / {@code
   * toolBlockedByReason()} / {@code logToolBlocks(day)}（事件 {@code
   * MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT}） 与 {@link Entry} 上的全部工具计数一并删除。
   *
   * <p>理由：门槛的判据（"每趟 ≥ 1,000 毫工具"）与它的实扣（{@code MarketSettlement.settleHaulRuns} 的烧工具）都随 {@code
   * MerchantHaul} 退役；工具改由 {@code trade} 产业的**周期投入**经标准生产管线消耗（设计书 §3.2 / I-H6） ⇒ "这一格为什么没走完"只剩服务货 /
   * 半径 / 亚单位残余三支，见 {@link #laneBlockedReason}。
   */

  /**
   * ★★ <b>记一条本轮的运费实收</b>（唯一致谢点：{@code MarketSettlement} 铸 {@code CARRIER_FEE} 时调用）。
   *
   * <p>它是"利润 = 每轮算出的读数"在本批的**可读部分**：只累计运费实收（按币分列），进日志/读口，<b>不落状态</b>。 完整的利润算式（含纯商号/顺便分流与三层税）属
   * M-C（见实现账本 D-3）。
   */
  public void recordFee(HouseholdId household, CurrencyId currency, long amountMilli) {
    Entry entry = byHousehold.get(household);
    if (entry == null || amountMilli <= 0L) {
      return;
    }
    entry.earnedByCurrency.merge(currency, amountMilli, Math::addExact);
  }

  /**
   * ★★ <b>G3-leftovers（2026-10-10）：本轮的逐户<b>装配</b>读数（DEBUG；事件 {@code
   * MERCHANT_CAPACITY_HOUSEHOLD}）</b>—— 一行 = 池里一个家户条目，字段与同族 {@code MERCHANT_CAPACITY_POOL_HEX} /
   * {@code MERCHANT_CAPACITY_POOL} 同形： <b>{@code day} 打头</b>（本事件此前**没有** {@code day} ⇒ 读数无法按日对齐，是本链
   * M-A1 引入的缺口；理由与先例见 {@link #assemble} 末尾的注释）。
   *
   * <p>★ <b>它为什么在装配点由调用方发、而不是池内自发</b>：本类没有世界日的概念（{@link #of} 的入参里没有 tick），而 {@code day} 由 tick
   * 面提供；同款先例 = {@code EconomySettlement} 装配后紧接着发的 {@code CAPACITY_QUOTE_BOOK}。
   *
   * <p>★★ <b>A3（2026-10-10）</b>：原来还有 {@code toolRemainingMilli} / {@code runsAffordable} 两栏（旧"每趟烧
   * 1,000 毫工具"的门槛镜像）—— 门槛退役，两栏已删；{@code toolMilli} 保留，它现在是 {@link MerchantCapacity}
   * 作业层的**可投入量输入**（装配点的存量 − 冻结），不再是任何门槛。
   *
   * <p>★ 只读、只打日志：不写状态、不改任何判据、不影响结算（§一.9 的日志纪律）。
   *
   * @param day 世界日（由 tick 面的调用方传入；见 {@code EconomySettlement} 的装配点）
   */
  public void logHouseholdAssembly(long day) {
    if (!LOG.isDebugEnabled()) {
      return;
    }
    for (Map.Entry<HexCoord, List<Entry>> entry : byHex.entrySet()) {
      long total = totalCapacityByHex.getOrDefault(entry.getKey(), 0L);
      for (Entry item : entry.getValue()) {
        EventLog.channel(LOG)
            .debug(
                LogEvent.of(
                    "MERCHANT_CAPACITY_HOUSEHOLD",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    day,
                    "hex",
                    entry.getKey(),
                    "household",
                    item.capacity.household().value(),
                    "laborMilli",
                    item.capacity.laborMilli(),
                    "toolMilli",
                    item.capacity.toolMilli(),
                    "capacityMilli",
                    item.capacity.capacityMilli(),
                    "hexTotalMilli",
                    total,
                    "sharePerMille",
                    item.sharePerMille,
                    "tier",
                    item.capacity.tier().name(),
                    "serviceRadiusHex",
                    item.capacity.serviceRadiusHex(),
                    "askPerMille",
                    item.askPerMille,
                    "pureMerchant",
                    item.pureMerchant,
                    "priced",
                    priced));
      }
    }
  }

  /**
   * ★ 一轮结束后的逐格分配汇总（INFO = 每格运力池与分配汇总；§一.9）。
   *
   * <p>★★ <b>2026-10-10：{@code MERCHANT_CAPACITY_POOL} 另报本轮"因运力未获服务"的钱/量对照</b>（口径写在同一行里）： {@code
   * unallocatedRawMilli} = Σ 逐行原始读数（同一请求被反复观察 ⇒ <b>不可</b>当真实量）、{@code unallocatedNetMilli} = Σ
   * 净额（每份只算一次 ⇒ <b>可</b>当真实量）；另附 {@code unallocatedObservations}（行数）/ {@code
   * unallocatedRequests}（请求数）/ {@code unallocatedMaxObservations}（单个请求最高被观察几次）三栏， 让"放大倍数"当场可算。★
   * 与日志档位无关：观察簿无条件记账 ⇒ 只开 INFO 时这一行也是真值（不是假 0）。
   */
  public void logRoundSummary(long day) {
    if (!LOG.isInfoEnabled()) {
      return;
    }
    long usedGoodsTotal = 0L;
    long usedWorkTotal = 0L;
    long allocationsTotal = 0L;
    for (Map.Entry<HexCoord, List<Entry>> entry : byHex.entrySet()) {
      long usedGoods = 0L;
      long usedWork = 0L;
      long allocations = 0L;
      long askMin = Long.MAX_VALUE;
      long askMax = 0L;
      Map<CurrencyId, Long> earned = new LinkedHashMap<>();
      for (Entry item : entry.getValue()) {
        usedGoods = Math.addExact(usedGoods, item.allocatedGoodsMilli);
        usedWork = Math.addExact(usedWork, item.allocatedWorkMilli);
        allocations = Math.addExact(allocations, item.allocationCount);
        askMin = Math.min(askMin, item.askPerMille);
        askMax = Math.max(askMax, item.askPerMille);
        for (Map.Entry<CurrencyId, Long> leg : item.earnedByCurrency.entrySet()) {
          earned.merge(leg.getKey(), leg.getValue(), Math::addExact);
        }
      }
      usedGoodsTotal = Math.addExact(usedGoodsTotal, usedGoods);
      usedWorkTotal = Math.addExact(usedWorkTotal, usedWork);
      allocationsTotal = Math.addExact(allocationsTotal, allocations);
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "MERCHANT_CAPACITY_POOL_HEX",
                  EconomyLogSource.ECONOMY_ORGANIZATION,
                  "day",
                  day,
                  "hex",
                  entry.getKey(),
                  "households",
                  entry.getValue().size(),
                  "capacityMilli",
                  totalCapacityByHex.getOrDefault(entry.getKey(), 0L),
                  "allocatedMilli",
                  usedGoods,
                  "allocatedWorkMilli",
                  usedWork,
                  "allocations",
                  allocations,
                  "askMinPerMille",
                  askMin == Long.MAX_VALUE ? 0L : askMin,
                  "askMaxPerMille",
                  askMax,
                  "freightEarnedByCurrency",
                  earned));
    }
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "MERCHANT_CAPACITY_POOL",
                EconomyLogSource.ECONOMY_ORGANIZATION,
                "day",
                day,
                "hexes",
                byHex.size(),
                "households",
                householdCount,
                "capacityMilli",
                totalCapacityMilli,
                "allocatedMilli",
                usedGoodsTotal,
                "allocatedWorkMilli",
                usedWorkTotal,
                "allocations",
                allocationsTotal,
                "priced",
                priced,
                "maxAskPerMille",
                maxAskPerMille,
                // ★★ A3（2026-10-10）：退役族**唯一**的 INFO 具名行 —— 哪族退役了、被谁取代（§一.9）。
                //   与逐格/逐户的 INFO 同源同步（每轮各一行），不新增状态、不改任何判据。
                "retiredFamilies",
                RETIRED_PARALLEL_MACHINE_FAMILIES,
                "replacedBy",
                RETIRED_PARALLEL_MACHINE_REPLACEMENTS,
                // ★★ 2026-10-10：本轮"因运力未获服务"的读数**净额 vs 原始**（口径与算法见类注与
                //   LaneUnservedObservationBook）——
                //   unallocatedRawMilli = Σ 逐行 unallocatedMilli（同一请求会被反复观察）⇒ ❌ 不可当真实量；
                //   unallocatedNetMilli = Σ 每份只算一次                 ⇒ ✅ 可当真实量（读这一栏）。
                //   后三栏 = 重复结构（行数 / 请求数 / 单个请求的最高观察次数），让"放大倍数"当场可算。
                "unallocatedRawMilli",
                unservedObservations.rawTotalMilli(),
                "unallocatedNetMilli",
                unservedObservations.netTotalMilli(),
                "unallocatedObservations",
                unservedObservations.observationRows(),
                "unallocatedRequests",
                unservedObservations.requestCount(),
                "unallocatedMaxObservations",
                unservedObservations.maxObservations()));
  }

  /**
   * ★ <b>一条分配结果（承运条目 = 一次跑商）</b>。
   *
   * @param pureMerchant 该提供者是不是**纯商号**（H-2：主业 ∈ merchant.*）—— 免运费（H-A）与利润算式的分流依据
   * @param askPerMille 该提供者的成交限价（‰；报价口径 = 自报价 + 上门附加费；缺省口径 = M-A1 派生承运成本）
   * @param quantityMilli 本条实际承运的商品量（毫商品）
   * @param consumedWorkMilli 本条的运力耗用（毫商品·程＝报价口径 / 毫商品＝缺省口径）
   */
  public record CarrierChoice(
      ActorRef carrier,
      HouseholdId household,
      HexCoord hex,
      MerchantPolicy.MerchantTier tier,
      long askPerMille,
      boolean pureMerchant,
      long quantityMilli,
      long consumedWorkMilli) {

    public CarrierChoice {
      Objects.requireNonNull(carrier, "carrier");
      Objects.requireNonNull(household, "household");
      Objects.requireNonNull(hex, "hex");
      Objects.requireNonNull(tier, "tier");
      if (askPerMille < 0L) {
        throw new IllegalArgumentException("CarrierChoice.askPerMille 不得为负: " + askPerMille);
      }
      if (quantityMilli <= 0L) {
        throw new IllegalArgumentException("CarrierChoice.quantityMilli 必须为正: " + quantityMilli);
      }
      if (consumedWorkMilli <= 0L) {
        throw new IllegalArgumentException(
            "CarrierChoice.consumedWorkMilli 必须为正: " + consumedWorkMilli);
      }
    }
  }

  /**
   * ★★ <b>一次 select 的完整结果</b>：分给了哪些提供者、各多少、以及没分出去的剩余需求。
   *
   * <p>不变量：{@code Σ choices.quantityMilli + unallocatedMilli == requestedMilli}（A3 退化为"分配总量 ≤
   * 运力总量"）。{@code unallocatedMilli} 必须由调用方显式处理（记 {@code LOGISTICS_CAPACITY} 与"被运力截断量"）， 本类不静默丢。
   *
   * @param priced 本次分配是否走报价口径（决定运费怎么收：报价口径逐条按各自限价，缺省口径沿用 M-A1 的按量比例分摊）
   */
  public record CarrierAllocation(
      List<CarrierChoice> choices, long requestedMilli, long unallocatedMilli, boolean priced) {

    public CarrierAllocation {
      Objects.requireNonNull(choices, "choices");
      choices = List.copyOf(choices);
      if (requestedMilli < 0L || unallocatedMilli < 0L || unallocatedMilli > requestedMilli) {
        throw new IllegalArgumentException(
            "CarrierAllocation 的 requested/unallocated 非法: requested="
                + requestedMilli
                + " unallocated="
                + unallocatedMilli);
      }
      long allocated = 0L;
      for (CarrierChoice choice : choices) {
        allocated = Math.addExact(allocated, choice.quantityMilli());
      }
      if (allocated != requestedMilli - unallocatedMilli) {
        throw new IllegalArgumentException(
            "CarrierAllocation 不守恒: allocated="
                + allocated
                + " requested="
                + requestedMilli
                + " unallocated="
                + unallocatedMilli);
      }
    }

    /** 已分配总量（毫商品）= {@code requestedMilli − unallocatedMilli}。 */
    public long allocatedMilli() {
      return requestedMilli - unallocatedMilli;
    }

    /** 一条都没分出去（该格没有跑商家户 / 半径外 / 运力耗尽）。 */
    public boolean isEmpty() {
      return choices.isEmpty();
    }
  }
}
