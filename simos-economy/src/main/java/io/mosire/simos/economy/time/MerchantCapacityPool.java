package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTables;
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
 *          上，钱由**市场牌价**（{@code MarketTable.prices[haul]}）定，本项只作"从最低价起买"的**选择序**与缺省口径的价。
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
 * <p>★★★ <b>2026-10-10 A3 残留修复（本批；F-1 / F-3 / F-2）</b>—— 服务成市格的三处口径收口（其余格一个判据都不动）：
 *
 * <pre>
 * F-1 进池/提供者判据  服务成市的格：判据 = **持有可交付的 {@code haul} 服务货**（{@code max(0, 现货 − 冻结) > 0}）
 *                      ★ 旧判据"劳动 + 工具 > 0"是**已退役的门槛家族残留**：劳动/工具为 0 但手上有服务货的户
 *                        结构上卖不出服务，与设计书 §3.2「运力 = 该产业本周期可产出的服务量」不一致。
 *                      其余格：判据一字不变（{@code capacityMilli > 0}）⇒ 无牌价的世界逐值退回改前（I-H3）。
 * F-3 tier / 限价序    服务成市格：{@code tier} 与半径由**服务货可用量**派生（{@link MerchantCapacity#ofService}），
 *                      限价 = 承运成本(tier) + 上门附加费；序仍是**价格优先**（{@code askPerMille} 升序）→ 同价按
 *                      M-A1 的 canonical 序（议价权降序 → 家户 id 升序）——**不另设特权队列**（设计书 §3.3）。
 * F-2 归因可分辨        {@link LogisticsBlock}：把"没有服务卖"（业务拒绝，INFO）与"运力不足"（运行期读数）分开，
 *                      随 {@link CarrierAllocation#logisticsBlock()} 返回、并在逐轮 INFO 汇总的 {@code blockedByReason} 分档计数。
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
 * day                         ★★ W7-fix3：本轮的世界日 —— **只在知道世界日时写**（本池由 tick 面装配才有；
 *                             夹具 / 纯状态读者装配的池不带此栏，见 {@link #NO_WORLD_DAY}），字段名与同族
 *                             {@code MERCHANT_CAPACITY_POOL} / {@code MERCHANT_CAPACITY_POOL_HEX} /
 *                             {@code MERCHANT_CAPACITY_HOUSEHOLD} **同拼写、同位置（打头）**
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
 * 自报价簿 CapacityQuote/CapacityQuoteBook            市场牌价 MarketTable.prices[haul]（服务成交的钱腿）
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
          + ",market-haul-price(MarketTable.prices[haul])"
          + ",industry-declared-input-necessaryInputsOf(standard-reserve)";

  /** 运力池日志（market 分类：它的生命周期就是市场轮）。 */
  private static final org.slf4j.Logger LOG = EconomyLog.market();

  /**
   * ★ <b>A3：{@link MerchantCapacity} 作业层的**工具维**键</b>（会话商品账口径；V-22：读的是商品账，**不动** {@code
   * AssetKind.SMALL_TOOL} 产权份额）。
   *
   * <p>★ A3 起它<b>不再是门槛</b>（旧 {@code MerchantHaul.TOOL_COMMODITY} 的那套"每趟烧 1,000 毫工具"已删）：
   * 这里读它只为把运力规模（tier / share 的派生依据）算出来。⇒ 收成 {@code private}（不再是对外拼写点）。
   */
  private static final CommodityId TOOL_COMMODITY =
      new CommodityId(EconomyVocabulary.TOOL_COMMODITY_ID);

  /**
   * ★ <b>"本池不是由 tick 面装配的"的哨兵</b>（夹具 / 纯状态读者走不带世界日的 {@code of} 重载；{@link #EMPTY} 同）。
   *
   * <p>★ D4 纪律（与 {@code EconomySettlement} 的同名哨兵同一口径）：<b>绝不拿假日凑数</b> —— {@code day < 0} ⇒ {@link
   * #select} 的 {@code MERCHANT_CAPACITY_LANE_TRUNCATED} **不带 {@code day} 字段**（字段缺席，而不是写一个假日期）。
   */
  private static final long NO_WORLD_DAY = -1L;

  /**
   * 空池（没有跑商家户的世界 / worker 本地副本）。★ 无世界日（{@link #NO_WORLD_DAY}：它由 {@link #empty()}
   * 给并行只读副本，从不承载某一轮的身份）。
   */
  private static final MerchantCapacityPool EMPTY =
      new MerchantCapacityPool(
          Map.of(),
          Map.of(),
          Map.of(),
          List.of(),
          0L,
          false,
          0L,
          Set.of(),
          new LinkedHashMap<>(),
          NO_WORLD_DAY);

  /**
   * ★★ <b>F-2（2026-10-10）：本类"为什么这条路没走完"归因词表的两个**非**运力支</b>。
   *
   * <pre>
   * NO_MERCHANT_HOUSEHOLD_REASON  具名归因：全池没有"选了跑商的家户"（连卖家都没有）
   * NO_SERVICE_GOODS_REASON       具名归因：服务成市格里**有**跑商家户，但一件可交付服务货都没有（等下一轮产出）
   *                               ★ 它就是"承运方手上拿不出货"这件事的**货侧**名字（{@link
   *                                 HaulService#NO_DELIVERABLE_SERVICE_GOODS_REASON}）—— 见下条的合并说明。
   * </pre>
   *
   * <p>★ 这两支与 {@code out-of-derived-radius} / {@code sub-unit-residual} / {@code
   * capacity-exhausted} 一起由 {@link #laneBlockedReason} 拼成**同一份** fixed-order 词表；
   * 它们只进读数（INFO/DEBUG/TRACE）， <b>不是任何判据</b>（§一.9 的日志纪律）。★ 字面量同制（小写连字符）， 是逐轮 INFO 汇总 {@code
   * serviceGoodsAbsentHouseholds} 栏的键，也是读数面按名分档的依据。
   *
   * <p>★★ <b>W7-fix（2026-10-11）：两个拼写合并成一个</b>（§一.11：不留"看起来在记、其实永远不被读"的常量）。 W7 曾把它们拆成两串（{@code
   * merchant-household-holds-no-service-goods} vs {@code
   * no-deliverable-haul-service-goods}）以区分"没有提供商"与"有提供商但没货"；实测（AGENTS §四 回代码核）：
   *
   * <pre>
   * ① 真正需要分开的那一刀 = 全池没有提供商（{@link #NO_MERCHANT_HOUSEHOLD_REASON}）vs 有提供商但没货
   *    —— 这两支**本来就不同值**，合并货侧两串**不会**把它们混起来；
   * ② 货侧那两串说的是**同一件事**（承运方手上可交付服务货为 0），只是一个从"家户账"看、一个从"承运方"看；
   * ③ W7 的 {@code HaulService.NO_DELIVERABLE_SERVICE_GOODS_REASON} 在 main 里**零发射点**（只有 javadoc
   *    与测试引用）⇒ 要么接发射点、要么删；本批按"两串本是一件事"合并：**唯一拼写点留在 `HaulService`**，
   *    本常量（唯一真发射点，装配期 {@code serviceAbsent} 计数）改为**引用**它 ⇒ 该常量不再悬空，也不新增读数语义。
   * </pre>
   */
  public static final String NO_MERCHANT_HOUSEHOLD_REASON = "no-merchant-household-in-shipping-hex";

  /**
   * 见 {@link #NO_MERCHANT_HOUSEHOLD_REASON}：有跑商家户、但手上没有可交付的服务货。
   *
   * <p>★★ W7-fix：值 = {@link HaulService#NO_DELIVERABLE_SERVICE_GOODS_REASON}（**唯一拼写点**在货侧那一边）——
   * 本常量只保留"池侧读它"的这个入口名，两个拼写不再各写一遍字面量。
   */
  public static final String NO_SERVICE_GOODS_REASON =
      HaulService.NO_DELIVERABLE_SERVICE_GOODS_REASON;

  /**
   * ★★★ <b>W7（2026-10-11）：缺省归因 —— 池里没有任何"手上有可交付服务货"的承运方</b>（全池口径）。
   *
   * <p>★★ <b>为什么与 {@link HaulService#NO_DELIVERABLE_SERVICE_GOODS_REASON}
   * 必须是<b>两个</b>不同的拼写</b>（AGENTS §四 的结构性歧义）： 改前两者同值（{@code
   * no-haul-service-in-shipping-hex}），于是日志里那一个字面量**同时**被读成"这一格没有牌价" （❌
   * 按代码不成立：有牌价才算"服务成市格"）、"这一格没有承运方"、"这一格没有服务货"三种互不相同的事 —— 调查因此把归因读反过一次（运力调查报告 §2.5 的 A2 条）。
   *
   * <pre>
   * {@link HaulService#NO_DELIVERABLE_SERVICE_GOODS_REASON}   货侧：**承运方手上没有可交付的运输服务货**（等下一轮产出）
   * 本常量（= {@link LogisticsBlock#NO_CARRIER_WITH_SERVICE}） 池侧：**全池没有一个能覆盖本 lane 且手上有服务货的承运方**
   * </pre>
   */
  public static final String NO_CARRIER_WITH_SERVICE_REASON = "no-carrier-with-deliverable-service";

  /** ★★ <b>A2：走"运输服务货"口径的格集</b>（空集 = 缺省口径，逐值退回改前）。只作日志归因（"为什么这一格买不到运力"）。 */
  private final Set<HexCoord> haulServiceHexes;

  /**
   * ★★ <b>F-2（2026-10-10）：本轮的"装配期缺货计数"（**瞬态、每轮一份、只进日志**）</b>—— 键 = 具名词表，值 = 该事实
   * 在本轮出现的**家户数**。当前唯一在册的键是 {@link #NO_SERVICE_GOODS_REASON}（服务成市格里"选了跑商、但手上可交付服务货 为 0"的家户数）。
   *
   * <p>★ 它<b>不是</b>状态组件：不落盘、不进 codec / 变更集、不参与任何判据、不改变任何返回值； 每轮装配时新建（与池同生命周期）。 理由是 §一.9
   * 要求"业务拒绝在公开读数面可分辨"——把"没有服务卖"（本表 + 运行期 {@code MARKET} 的 {@code
   * logisticsDetailQuantities}）与"没有承运人"（{@link #NO_MERCHANT_HOUSEHOLD_REASON}）分开，读者不必开 DEBUG。
   */
  private final Map<String, Long> serviceAbsentTally;

  /** 逐格池（键序 = hex 的规范序；值按分配序、不可变）。★ W7 起它只服务**逐格读数**与"本格有没有承运方"的查询。 */
  private final Map<HexCoord, List<Entry>> byHex;

  /**
   * ★★★ <b>W7（2026-10-11）：全局分配序 —— <b>本池唯一的分配次序</b></b>（用户裁定：运力提供**全市场区共享**，跨区/跨币亦可被雇）。
   *
   * <pre>
   * 序 = 限价升序（报价口径：买方从**最低价**起买，M0d 原文"向市场上最低价的运力提供商买运力"）
   *       → 同价按**全池**议价权占比‰ 降序 → 家户 id 升序（I7：内容的纯函数）
   *
   * 与逐格序（{@link #byHex}）的关系：只含一个非空格的池上，两者**逐项相同** ⇒ 改前改后逐值可比。
   * </pre>
   *
   * <p>★★ <b>为什么必须有它</b>：改前 {@link #select} 只从 {@code byHex.get(发货格)} 里挑 ⇒ 发货格没有承运方（或没有服务货）时， 该
   * lane **一个候选都看不到**，哪怕隔壁格的商号手上正握着一堆卖不掉的运输服务（实测：全世界 19/37 格的运力预算恒 0， 而需求每格都在产生）。W7 起候选集 = 全池。
   */
  private final List<Entry> globalOrder;

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
   * ★★ <b>W7-fix3（2026-10-11）：本轮的世界日</b>—— {@code select} 的 {@code
   * MERCHANT_CAPACITY_LANE_TRUNCATED} 一行里 {@code day} 字段的来源；由 tick 面的装配调用方给（{@code
   * EconomySettlement} 的装配点），与同一轮 {@code MarketSettlement.MarketRound} 的 {@code day}
   * <b>同源同值</b>（同一个 tick 局部量）。
   *
   * <p>★ <b>为什么由装配点给、而不是 {@code select} 的入参</b>：池本身就是"一轮一份"的对象（同 {@code priced} / {@code
   * haulServiceHexes}），日属于<b>轮的身份</b>；放进装配点 ⇒ 本轮所有 lane 必然读到同一个日，不存在"某一笔 传错日"的形态。★
   * 它<b>只被日志读</b>：不进任何判据、不改任何数值、不落状态。
   */
  private final long day;

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
      List<Entry> globalOrder,
      long householdCount,
      boolean priced,
      long maxAskPerMille,
      Set<HexCoord> haulServiceHexes,
      Map<String, Long> serviceAbsentTally,
      long day) {
    this.byHex = byHex;
    this.totalCapacityByHex = totalCapacityByHex;
    this.byHousehold = byHousehold;
    this.globalOrder = globalOrder;
    this.householdCount = householdCount;
    this.priced = priced;
    this.maxAskPerMille = maxAskPerMille;
    this.day = day;
    // ★★ F-2（2026-10-10）：缺货计数的**构造期防御性拷贝**（保序 + 不可变；I7 禁 Map.copyOf）。
    this.serviceAbsentTally = Collections.unmodifiableMap(new LinkedHashMap<>(serviceAbsentTally));
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
    return assemble(
        classStandings, classPositions, rows, goods, Map.of(), false, null, NO_WORLD_DAY);
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
    return assemble(
        classStandings, classPositions, rows, goods, Map.of(), priced, null, NO_WORLD_DAY);
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
    return assemble(
        classStandings, classPositions, rows, goods, frozenGoods, priced, null, NO_WORLD_DAY);
  }

  /**
   * ★★ <b>A2（2026-10-10）：服务商品口径下的运力池</b>—— 供给来自跑商家户手上的**运输服务货**（A1 起 {@code trade@hex}
   * 的产出入既有货物账），不再由"劳动投入 + 工具存量"派生。
   *
   * <pre>
   * 服务成市的格（haulServiceHexes 含该格）⇒ 该户本轮运力预算 = max(0, haul 现货 − haul 冻结)   ← 服务商品账，I-H2
   *                                        ★ 进池**不再要求**"劳动 + 工具 > 0"（F-1）；tier/半径由服务货规模派生（F-3）
   * 其余格                                ⇒ 逐值退回 {@link MerchantCapacity} 的既有算式（劳动 + 工具），进池判据同改前
   * </pre>
   *
   * <p>★★ <b>为什么必须按格分流</b>：服务市场的开关是 {@code MarketTable.hasPrice(haul)}（唯一拼写点 = {@link
   * HaulService#pricedAt}）—— 没给运输服务定价的格一个判据都不变 ⇒ <b>缺省中性（I-H3）逐表达式成立</b>， 而不是靠"算出来恰好相等"。★
   * 已定过价的格则必须由**真货**兜底：服务不能凭空造，卖出多少就得有多少货（I-H2）。
   *
   * <p>★★ <b>A3（2026-10-10）：工具门槛已删</b>——服务口径下"能不能跑"只由**手上有没有服务货**决定（I-H2）， 工具不再是跑商的市场轮门槛：它由 {@code
   * trade} 产业声明的周期投入经**标准生产管线**消耗 （{@code Industry.cycleInputPerUnit} → 现扣 + 挂单保留），见设计书 §3.2 与 §5
   * I-H6。
   *
   * <p>★★★ <b>F-1/F-3（2026-10-10 A3 残留修复）</b>：服务成市格的**进池判据**从"劳动 + 工具 &gt; 0"（已退役的门槛家族残留）
   * 换成"选了跑商即进池、运力 = 手上的服务货"；{@code tier}/{@code serviceRadiusHex}/{@code askPerMille} 随之由 **服务货规模 +
   * 牌价**派生（见 {@link MerchantCapacity#ofService} 与 {@link #askPerMilleOf}）， <b>序仍是"价格优先 → 同价按
   * canonical 序"，不另设特权队列</b>（设计书 §3.3、Q-25）。
   *
   * @param haulServiceHexes <b>服务成市</b>的格（{@link HaulService#pricedAt} 为真的那些格）；{@code null} = 全都不是
   *     ★ 本重载不带世界日 ⇒ 池的 {@code day} = {@link #NO_WORLD_DAY}（夹具 / 纯状态读者的读数口径；生产路径走下面带 {@code day} 的
   *     8 参重载）。
   */
  public static MerchantCapacityPool of(
      Map<HouseholdId, HouseholdClassMembership> classStandings,
      Map<ClassPositionId, ProductionRole> classPositions,
      Map<HouseholdId, HouseholdEconomy> rows,
      Map<HouseholdId, Map<CommodityId, Long>> goods,
      Map<HouseholdId, Map<CommodityId, Long>> frozenGoods,
      boolean priced,
      Set<HexCoord> haulServiceHexes) {
    return of(
        classStandings,
        classPositions,
        rows,
        goods,
        frozenGoods,
        priced,
        haulServiceHexes,
        NO_WORLD_DAY);
  }

  /**
   * ★★ <b>W7-fix3（2026-10-11）：带世界日的装配入口</b>（生产路径 = tick 面走这一条）—— 与上一重载<b>逐值同一条装配</b>， 只多一个"轮的身份"读数：
   *
   * <pre>
   * day = 本轮的世界日 ⇒ {@code select} 的 MERCHANT_CAPACITY_LANE_TRUNCATED 一行带 {@code day} 字段
   *       （与同族 MERCHANT_CAPACITY_POOL / _POOL_HEX / _HOUSEHOLD **同拼写**，可逐日对齐/按窗口拆）
   * </pre>
   *
   * <p>★ 为什么需要它：改前该事件<b>没有</b> {@code day}（读数只能给整轮合计、无法按窗口拆；同族其它三条都由调用方带着日发）。 日属于"一轮的身份"，故从装配点注入 ——
   * 见 {@link #day} 字段注。
   *
   * @param day 世界日（tick 面给；{@code < 0} = 无世界日 ⇒ 该事件不带 {@code day} 字段，见 {@link #NO_WORLD_DAY}）
   */
  public static MerchantCapacityPool of(
      Map<HouseholdId, HouseholdClassMembership> classStandings,
      Map<ClassPositionId, ProductionRole> classPositions,
      Map<HouseholdId, HouseholdEconomy> rows,
      Map<HouseholdId, Map<CommodityId, Long>> goods,
      Map<HouseholdId, Map<CommodityId, Long>> frozenGoods,
      boolean priced,
      Set<HexCoord> haulServiceHexes,
      long day) {
    return assemble(
        classStandings, classPositions, rows, goods, frozenGoods, priced, haulServiceHexes, day);
  }

  /** <b>装配体（唯一拼写点）</b>。 */
  private static MerchantCapacityPool assemble(
      Map<HouseholdId, HouseholdClassMembership> classStandings,
      Map<ClassPositionId, ProductionRole> classPositions,
      Map<HouseholdId, HouseholdEconomy> rows,
      Map<HouseholdId, Map<CommodityId, Long>> goods,
      Map<HouseholdId, Map<CommodityId, Long>> frozenGoods,
      boolean priced,
      Set<HexCoord> haulServiceHexes,
      long day) {
    Objects.requireNonNull(classStandings, "classStandings");
    Objects.requireNonNull(classPositions, "classPositions");
    Objects.requireNonNull(rows, "rows");
    Objects.requireNonNull(goods, "goods");
    Objects.requireNonNull(frozenGoods, "frozenGoods");
    List<HouseholdId> households = new ArrayList<>(rows.keySet());
    households.sort(Comparator.comparing(HouseholdId::value));
    Map<HexCoord, List<Entry>> pool = new LinkedHashMap<>();
    Map<HexCoord, Long> totals = new LinkedHashMap<>();
    // ★★ F-2：装配期的缺货计数（**局部**，装配完交给池；见字段注：瞬态、只进日志、不是状态组件）。
    Map<String, Long> serviceAbsent = new LinkedHashMap<>();
    long counted = 0L;
    long maxAsk = 0L;
    for (HouseholdId household : households) {
      HouseholdEconomy row = rows.get(household);
      HouseholdClassMembership standing = classStandings.get(household);
      if (row == null || !MerchantIdentity.selectsMerchant(standing, classPositions)) {
        continue;
      }
      HexCoord hex0 = row.view().hex();
      // ★★ A3（2026-10-10）：工具维 = {@link MerchantCapacity} 的**作业层**可投入量（装配点的存量 − 冻结）。
      //   它**不再是任何门槛**（旧 "每趟烧 1,000 毫工具" 的门槛已随 MerchantHaul 删除）：工具由 trade 产业声明的
      //   周期投入经标准生产管线消耗（设计书 §3.2 / I-H6）⇒ 这里读它只为把"运力规模"（tier/share 的派生依据）
      //   算出来，改它不改任何一次承运的成败。
      long toolStockMilli =
          goods.getOrDefault(household, Map.of()).getOrDefault(TOOL_COMMODITY, 0L);
      long toolFrozenMilli =
          frozenGoods.getOrDefault(household, Map.of()).getOrDefault(TOOL_COMMODITY, 0L);
      long toolMilli = Math.max(0L, toolStockMilli - toolFrozenMilli);
      // ★★ A2：服务成市的格 ⇒ 本轮运力预算 = 该户手上的**运输服务货**（max(0, 现货 − 冻结)）——
      //   卖出多少服务就得有多少货（I-H2），"服务不能凭空造"由此结构性成立；其余格逐值退回劳动+工具算式。
      //   ★ 单位锚（A1）：1 商品单位 haul = 1,000 毫服务 = 1,000 毫商品·程 ⇒ 这里不需要第二次换算。
      boolean servicePriced = haulServiceHexes != null && haulServiceHexes.contains(hex0);
      long serviceAvailableMilli =
          Math.max(
              0L,
              goods.getOrDefault(household, Map.of()).getOrDefault(HaulService.HAUL_COMMODITY, 0L)
                  - frozenGoods
                      .getOrDefault(household, Map.of())
                      .getOrDefault(HaulService.HAUL_COMMODITY, 0L));
      // ★★★ F-1（2026-10-10，A3 残留修复）：**进池/提供者判据改成与"有服务货"一致**。
      //
      //   服务成市的格：**进池不再要求"劳动 + 工具 > 0"**（那是已退役的门槛家族残留）——该户只要"选了跑商"
      //     就进池，它的运力预算 = **手上可交付的服务货**（{@code max(0, 现货 − 冻结)}，I-H2）。
      //     ⇒ 一个劳动/工具为 0 但持有服务货的户**结构上不再卖不出服务**（设计书 §3.2「运力 = 该产业本周期
      //       可产出的服务量」）。★ 预算为 0 的条目留在池里但**分不到任何量**（{@code select} 只看预算），
      //       这样"本格**有**跑商家户、但没有服务货"这条事实在读数面仍然看得见（{@code householdCountAt} /
      //       {@code MERCHANT_CAPACITY_HOUSEHOLD}）—— 它是 F-2 "没有服务卖" 与 "没有承运人" 分辨的依据。
      //   其余格（无牌价）：判据**一字不变**（{@code capacityMilli > 0}）⇒ 逐值退回改前（I-H3 缺省中性）。
      //   ★ 分格分流的开关仍是 HaulService.pricedAt（唯一拼写点）——不是"算出来恰好相等"。
      MerchantCapacity capacity =
          servicePriced
              // ★★ F-3：服务成市格的 tier / 半径由**服务货可用量**派生（{@link MerchantCapacity#ofService}），
              //   不再由劳动 + 工具派生；labor/tool 两栏降为读数（见该方法注）。
              ? MerchantCapacity.ofService(
                  household,
                  hex0,
                  serviceAvailableMilli,
                  row.participationAdjustedLaborMilli(),
                  toolMilli)
              : MerchantCapacity.of(
                  household, hex0, row.participationAdjustedLaborMilli(), toolMilli);
      if (capacity.capacityMilli() <= 0L && !servicePriced) {
        continue; // 0 运力 = 不进池（无牌价格：逐字等于改前；"有这家户"与"它提供运力"是两件事）
      }
      if (servicePriced && serviceAvailableMilli <= 0L) {
        // F-2 具名归因：**有提供商、但没有货**（与"没有提供商"分开计数；只进读数，不改判据）。
        serviceAbsent.merge(NO_SERVICE_GOODS_REASON, 1L, Math::addExact);
      }
      long askPerMille = askPerMilleOf(capacity, priced);
      maxAsk = Math.max(maxAsk, askPerMille);
      HexCoord hex = capacity.hex();
      // ★★ M-C：纯商号（H-2：**主业** ∈ merchant.*）/ 顺便跑商（merchant 只在副业）—— 免运费与利润算式的分流依据；
      //   判据的唯一拼写点在 {@link MerchantIdentity}。
      boolean pureMerchant = MerchantIdentity.isPureMerchant(standing, classPositions);
      long workBudgetMilli = servicePriced ? serviceAvailableMilli : capacity.capacityMilli();
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
    // ★★★ W7（2026-10-11）：议价权占比的分母改成**全池总量**（用户裁定：运力池全市场区共享）。
    //   改前分母 = 本格总量 ⇒ "占比"只在本格内有意义；全局共享之后分配序是**一份**全局序，
    //   分母必须与那个序的作用域一致（否则"占比 1000‰"的脚夫会排在"占比 100‰"的富商前面，与规模无关，
    //   是改前逐格语境的残留）。★ 只有一个非空格的世界里，两种分母**逐值相同** ⇒ 既有世界逐值可比。
    long poolTotalMilli = 0L;
    for (long value : totals.values()) {
      poolTotalMilli = Math.addExact(poolTotalMilli, value);
    }
    List<Entry> allEntries = new ArrayList<>((int) Math.min(counted, 4096L));
    for (Map.Entry<HexCoord, List<Entry>> entry : pool.entrySet()) {
      List<Entry> entries = entry.getValue();
      for (Entry item : entries) {
        item.sharePerMille = item.capacity.sharePerMilleOf(poolTotalMilli);
      }
      // ★★ 逐格序（保留）：分配序 = 议价权降序 → 家户 id 升序；报价口径前面加限价升序。
      //   它现在只服务逐格读数（"这一格的承运方怎么排"）与既有夹具/测试的可比性。
      Comparator<Entry> order =
          Comparator.comparingLong((Entry item) -> -item.sharePerMille)
              .thenComparing(item -> item.capacity.household().value());
      if (priced) {
        order = Comparator.comparingLong((Entry item) -> item.askPerMille).thenComparing(order);
      }
      entries.sort(order);
      frozen.put(entry.getKey(), Collections.unmodifiableList(entries));
      frozenTotals.put(entry.getKey(), totals.getOrDefault(entry.getKey(), 0L));
      allEntries.addAll(entries);
      for (Entry item : entries) {
        frozenByHousehold.put(item.capacity.household(), item);
      }
    }
    // ★★★ W7：**全局分配序**（唯一分配次序）—— 同一把比较器，作用域 = 全池。
    Comparator<Entry> globalComparator =
        Comparator.comparingLong((Entry item) -> -item.sharePerMille)
            .thenComparing(item -> item.capacity.household().value());
    if (priced) {
      globalComparator =
          Comparator.comparingLong((Entry item) -> item.askPerMille)
              .thenComparing(globalComparator);
    }
    allEntries.sort(globalComparator);
    List<Entry> globalOrder = Collections.unmodifiableList(allEntries);
    MerchantCapacityPool built =
        new MerchantCapacityPool(
            Collections.unmodifiableMap(frozen),
            Collections.unmodifiableMap(frozenTotals),
            Collections.unmodifiableMap(frozenByHousehold),
            globalOrder,
            counted,
            priced,
            maxAsk,
            haulServiceHexes,
            serviceAbsent,
            day);
    // ★★ G3-leftovers：本池的逐户**装配**读数（事件 {@code MERCHANT_CAPACITY_HOUSEHOLD}）**不在这里发射** ——
    //   发射点仍是 tick 面的装配调用方（{@code EconomySettlement} 紧随本装配之后，与 {@code
    //   CAPACITY_QUOTE_BOOK} 同款先例）：{@link #logHouseholdAssembly(long)}。★ W7-fix3 起本方法**拿到了**世界日
    //   （第 8 参）⇒ 原注释"本方法拿不到世界日"已不成立；但那条事件的发射点**不动**（读数一条不多、一条不少）：
    //   日在本类只有两个落点 —— 装配点注入的 {@link #day}（供 {@link #select} 的 lane 读数）与
    //   {@link #logRoundSummary} / {@link #logHouseholdAssembly} 的入参。
    return built;
  }

  /**
   * ★★ <b>F-2：本轮的装配期缺货计数（保序、不可变；没有 ⇒ 空表）</b>—— 供逐轮 INFO 汇总读。
   *
   * <p>★ 保序 = {@code LinkedHashMap} + {@code Collections.unmodifiableMap}（I7 确定性；禁 {@code
   * Map.copyOf}）。 它是**只读视图**：键的插入序由首次出现序决定（装配按家户 id 升序遍历 ⇒ 内容的纯函数）。
   */
  public Map<String, Long> serviceAbsentTally() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(serviceAbsentTally));
  }

  /**
   * 纯状态派生查询（工具维取 0 ⇒ 运力下界，J-2）：该格有没有"选了跑商且有运力"的家户。
   *
   * <p>★★ <b>B2：入参是"当日视图"（不是段首 {@code EconomyData}）</b> —— 本方法同时读阶层归属、家户位置与劳动，
   * 三类都<b>逐日会变</b>；只换一半会拼出"当刻归属 + 段首位置"的混合日视图（接口类注明令禁止）。
   *
   * <p>★★ <b>W7</b>：判据改走 {@link #providerCapacityMilli}（= 决策层与结算层的**同一把尺**，见那个方法的类注）。
   */
  public static boolean hasCapacityAt(EconomyDayView base, HexCoord hex) {
    return hexCapacityMilli(base, hex, Map.of()) > 0L;
  }

  /**
   * ★★ <b>M-D：该格运力总量（纯函数；与 {@link #of} 装配同一条算式）</b>—— 逐户 {@link #providerCapacityMilli} 后求和。
   *
   * @param goods 会话商品账（读 {@code tool} 与 {@code haul} 存量）；没有商品账 ⇒ 传空表（工具项 = 0 ⇒ **运力下界**，J-2）
   */
  public static long hexCapacityMilli(
      EconomyDayView base, HexCoord hex, Map<HouseholdId, Map<CommodityId, Long>> goods) {
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
   * ★★★ <b>W7（2026-10-11）："一个户能交付多少运力"的**唯一派生量** —— 决策层与结算层读同一份</b>。
   *
   * <p>★★ <b>用户派单口径（逐字）</b>：「把 {@code ExpectedProfitBook.merchantProspect} 的判据与结算层（承运/议价权）的判据
   * <b>统一到同一口径</b> —— 以能真交付服务的那一侧为准（或两侧都读同一份派生量）」。
   *
   * <pre>
   * 服务成市的格（{@link HaulService#pricedAt}）：capacity = max(服务货可用量, 劳动 + 工具能产出的量)
   *                                                ↑ 结算层口径（I-H2：手上真的有多少服务可卖）
   *                                                             ↑ 决策层口径（本周期能产出多少服务）
   * 其余格：                                    capacity = 劳动 + 工具能产出的量（逐值退回改前）
   * </pre>
   *
   * <p>★★ <b>为什么取<b>较大者</b>（而不是只取服务货）—— 这是"两把尺"里那处误判的正面修复</b>：
   *
   * <ul>
   *   <li><b>只取服务货 ⇒ 先有鸡还是先有蛋</b>：一间刚开张（或刚迁到新格）的商号<b>本周期还没有任何服务货</b>， 若决策层据此判它"不能跑商"，它就永远当不上主业 ⇒
   *       永远不产出服务 ⇒ 路自己堵死；
   *   <li><b>只取劳动 + 工具 ⇒ 报告实测的那处误判</b>：一个<b>手上有服务货、但劳动/工具为 0</b> 的户（{@code 0_2} 正是） 被判 {@code
   *       no-bargaining-power}，<b>永远当不上主业跑商，而它其实能卖服务</b>（那批服务货随时可交付）；
   *   <li><b>取较大者 = 两条交付路径的并集</b>：现在就能交付的（服务货）与本周期能产出的（劳动 + 工具）都算数 ⇒
   *       上面两种误判<b>同时消失</b>，且判据只有一个名字、一处拼写。
   * </ul>
   *
   * <p>★ <b>方向上单调（不收紧任何既有准入）</b>：服务成市的格里本式 ≥ 改前的 {@code MerchantCapacity.of(labor, tool)} ⇒
   * 决策层只会接受**更多**候选（多出来的每一个仍要过 {@code ExpectedProfitBook} 的诚实预估与"承运资产自备"约束）； 非服务成市的格逐值不变。★
   * 结算层**不受本式影响**：{@link #of} 装配的运力预算仍是**手上的服务货** （I-H2：卖多少就得有多少货，服务不能凭空造）。
   *
   * @param goods 会话商品账（读 {@code tool} 与 {@code haul}）；纯状态读者传 {@code Map.of()} ⇒ 两项都取 0 = 下界（J-2）
   */
  public static long providerCapacityMilli(
      EconomyDayView base,
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
    Map<CommodityId, Long> account = goods.getOrDefault(household, Map.of());
    long toolMilli = account.getOrDefault(TOOL_COMMODITY, 0L);
    long productive =
        MerchantCapacity.capacityMilliOf(row.participationAdjustedLaborMilli(), toolMilli);
    if (!HaulService.pricedAt(MarketTables.atOrNull(base, hex))) {
      return productive; // 服务不成市 ⇒ 逐值退回改前（缺省中性）
    }
    long serviceAvailable = Math.max(0L, account.getOrDefault(HaulService.HAUL_COMMODITY, 0L));
    return Math.max(serviceAvailable, productive);
  }

  /**
   * ★★ <b>M-D：一个家户在本格的运力（纯函数；不是池成员 ⇒ 0）</b>—— 成员判据与装配同源（{@link
   * MerchantIdentity#selectsMerchant}），算式同源（{@link #providerCapacityMilli}，W7 起 = 两把尺合一的那一份）。
   */
  private static long memberCapacityMilli(
      EconomyDayView base,
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
    return providerCapacityMilli(base, household, hex, goods);
  }

  /**
   * ★★ <b>M-D / §11.4 G-1：一个家户作为运力提供者的**市场议价权**（占比‰）</b>—— 排序输入之一（计划 §7 Q-19： 与 {@code
   * MerchantCapacityPool} 的分配序**同一口径、同一拼写点**，不得两套）。
   *
   * <pre>
   * 该户**已是池成员**（{@code selectsMerchant} 且住在本格）⇒ share = 该户可交付运力 × 1000 ÷ 全池可交付运力
   * 其余（本格候选新进入者 / 邻格准备迁入的候选）        ⇒ share = 该户可交付运力 × 1000 ÷ (全池 + 该户)
   * </pre>
   *
   * <p>★★ <b>W7（2026-10-11）：分母从"本格运力总量"改成"全池可交付运力"</b> —— 与 {@link #assemble} 里 {@code
   * Entry.sharePerMille} 的分母<b>同源</b>（运力池全市场区共享，分配序是一份全局序）。只有一个非空格的世界里两者逐值相同。 ★
   * 原来的"邻格候选必须按新进入者算（分母加自己）"那条理由（"运力池挂在发货格"）已被用户裁定取代，但**算法保留**：
   * 它不是本格语境的残留，而是"**我进去之后占多少**"的正确口径（新进入者不在当前池里）。
   *
   * <p>★ 除法与占比口径的唯一拼写点 = {@link MerchantCapacity#sharePerMilleOf}（本方法只负责"分母是哪一个"）。 ★ 纯状态读者（没有商品账）传
   * {@code Map.of()} ⇒ 工具/服务两项都取 0 ⇒ 占比是**下界**（J-2 的具名偏差，方向 fail-closed）。
   */
  public static long sharePerMilleAsProviderAt(
      EconomyDayView base,
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
    long capacity = providerCapacityMilli(base, household, hex, goods);
    if (capacity <= 0L) {
      return 0L; // 既没有可交付的服务货、也产不出运力 ⇒ 占比 0（不猜）
    }
    boolean residentMember =
        row.view().hex().equals(hex)
            && MerchantIdentity.selectsMerchant(
                base.classStandings().get(household), base.classPositions());
    long total = poolCapacityMilli(base, goods);
    if (!residentMember) {
      // 本格候选新进入者 / 邻格准备迁入的候选：把自己算进分母（"我进去之后占多少"）
      total = Math.addExact(total, capacity);
    }
    return MerchantCapacity.sharePerMilleOf(capacity, total);
  }

  /**
   * ★★ <b>W7：全池可交付运力（纯函数；{@link #sharePerMilleAsProviderAt} 与逐格分母的共同来源）</b>—— 逐户按**它自己住的格**取 {@link
   * #providerCapacityMilli} 后求和（家的位置 = {@code HouseholdEconomy.view().hex()}）。
   *
   * <p>★ 键序 = 家户 id 规范串升序（I7：内容的纯函数）。
   */
  public static long poolCapacityMilli(
      EconomyDayView base, Map<HouseholdId, Map<CommodityId, Long>> goods) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(goods, "goods");
    long total = 0L;
    List<HouseholdId> households = new ArrayList<>(base.classStandings().keySet());
    households.sort(Comparator.comparing(HouseholdId::value));
    for (HouseholdId household : households) {
      HouseholdEconomy row = base.classes().get(household);
      if (row == null) {
        continue;
      }
      total = Math.addExact(total, memberCapacityMilli(base, household, row.view().hex(), goods));
    }
    return total;
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
      // ★★ F-1（2026-10-10）：服务成市格的 budget 与 capacity 是**同一个操作数**（服务货可用量）——
      //   这正是"进池判据 = 有服务货"的算术落点；labor/tool 两栏在服务口径下只作读数。
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
   * ★★★ <b>A5（2026-10-10，T-H3 的读数来源）：本轮**逐户运费实收**（= 服务成交的钱腿收款人侧）</b>—— 按币分列。
   *
   * <pre>
   * 键 = 家户 id（**家户 id 升序**，确定性 I7）；值 = {币种（规范串升序） → 毫}
   * 只含本轮真的收到钱的户（零额/没成交的户不落键）⇒ 空表 = 本轮没有服务成交（不是 0）
   * </pre>
   *
   * <p>★★ <b>它解决什么</b>：服务不进订单簿（需求由其他商品的购买派生），所以 {@code MarketReport.fills()} 里 <b>结构上永远没有</b>服务成交 ⇒
   * 跑商产业的 {@code OperatorCondition.cycleRevenueByCurrency} 恒空、 {@code lastCycleNetMilli} 只剩成本一项（A4
   * 账本 §5-3 实测：{@code trade@0_*} 恒 {@code {} } / net −104，误导）。
   * 本条读数是"服务收入确实体现为该产业经营者的收益"的**唯一来源**：{@code MarketSettlement} 把它随 {@code MarketOutcome} 交出，{@code
   * OperatorSettlement} 按收款家户归入它名下产出运输服务的那个 unit。
   *
   * <p>★ <b>它是读数、不是账</b>：钱腿本身只有一条（{@code MARKET_TRADE}，买方 → 承运家户），{@link #recordFee} 只是把同一条腿按户归集一次
   * ⇒ 与 {@code EnterpriseProfitBook} 读同一批转移腿，<b>不构成第二本账</b>（I-H5 不双记）。 保序 = {@code LinkedHashMap} +
   * {@code Collections.unmodifiableMap}（I7：禁 {@code Map.copyOf}）。
   */
  public Map<HouseholdId, Map<CurrencyId, Long>> freightEarnedByHousehold() {
    List<HouseholdId> households = new ArrayList<>(byHousehold.keySet());
    households.sort(Comparator.comparing(HouseholdId::value));
    Map<HouseholdId, Map<CurrencyId, Long>> ordered = new LinkedHashMap<>();
    for (HouseholdId household : households) {
      Entry entry = byHousehold.get(household);
      Map<CurrencyId, Long> earned = new LinkedHashMap<>();
      List<CurrencyId> currencies = new ArrayList<>(entry.earnedByCurrency.keySet());
      currencies.sort(Comparator.comparing(CurrencyId::value));
      for (CurrencyId currency : currencies) {
        long amountMilli = entry.earnedByCurrency.getOrDefault(currency, 0L);
        if (amountMilli != 0L) {
          earned.put(currency, amountMilli);
        }
      }
      if (!earned.isEmpty()) {
        ordered.put(household, Collections.unmodifiableMap(earned));
      }
    }
    return Collections.unmodifiableMap(ordered);
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
   * <p>★ 读数（日志）的差别只有两个：① {@code MERCHANT_CAPACITY_LANE_TRUNCATED} 的 {@code
   * requestKeyTracked=false}、 {@code unallocatedObservationIndex} 恒 1（每次调用自己成一份，因为这里没有"哪一次请求"的身份）；②
   * 若本池是走**不带 世界日**的 {@code of} 重载装配的（夹具 / 纯状态读者），该事件**不带 {@code day} 字段**（{@link #NO_WORLD_DAY}；
   * 生产路径的池带日 ⇒ 照常打头输出）。<b>算法与判据与 5 参重载完全同一条路径</b>（见那个重载的说明），返回值逐值相同。
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
    // ★★★ F1（W7-fix，2026-10-11）：归因的判据必须读**全池**（{@code globalOrder}）—— 候选集自 W7 起就是全池，
    //   用发货格的池切片会把"有承运方但都在半径外"误报成 "no-merchant-household-in-shipping-hex"（测试批
    //   {@code HaulGlobalPoolW7Test} 去掉 {@code emptyLocalMerchant} 即最小复现）。★ 纯读数面：
    //   choices / unallocatedMilli / 任何数值一字不动。★ 逐格运力读数也不经过池切片（它读
    //   {@code totalCapacityByHex}，见下面 DEBUG 行的 {@code hexTotalCapacityMilli}）。
    //   ★★★ W7-fix3（2026-10-11）：改前这里还留着 W7 改写后的**死存储**
    //     `List<Entry> pool = byHex.getOrDefault(from, List.of());`（此后一次也没被读 ⇒ SpotBugs
    //     DLS_DEAD_LOCAL_STORE）—— 已删。★ 删它不动任何数值：`byHex.getOrDefault` 是纯读（无副作用、`byHex`
    //     由构造器保证非 null），本方法此后不再从 {@code byHex} 取任何切片。
    List<Entry> candidatePool = globalOrder;
    List<CarrierChoice> choices = new ArrayList<>();
    long demandLeft = quantityMilli;
    long unreachableWork = 0L;
    long subUnitWork = 0L;
    // ★★★ W7：口径不一致（服务成市 / 未成市）而被跳过的条目数 —— 只作读数（不是"够不着"，见下方口径闸）。
    long differentMarketKindCount = 0L;
    // ★★★ W7（2026-10-11）：候选集 = **全池**（用户裁定：运力提供全市场区共享，跨区/跨币亦可被雇）。
    //   改前是 `byHex.getOrDefault(from, ...)`（只认发货格的池）⇒ 发货格没有承运方/没有服务货时本 lane 一个候选都看不到。
    //   ★ 物理约束仍在（fail-closed，只可能变严不变松）：承运方必须**跑得到**这条 lane —— 行程 = 自家格 → 发货格 → 收货格，
    //     总里程 ≤ 它的派生服务半径（{@link MerchantCapacity#serviceRadiusHex()}）。承运方就在发货格时，
    //     该式**逐值退化**为既有的 `hex.distanceTo(to) <= radius`（改前语义）⇒ 既有世界逐值可比。
    for (Entry item : globalOrder) {
      if (demandLeft <= 0L) {
        break;
      }
      if (!item.capacity.servesTrip(from, to)) {
        unreachableWork = Math.addExact(unreachableWork, item.remainingWorkMilli);
        continue;
      }
      // ★★★ W7（2026-10-11）：**口径必须与这条 lane 一致** —— 服务成市的 lane（运费走"服务商品成交"那条腿、
      //   货腿在 {@code deliverHaulService} 里按**手上的服务货**实扣）只能由**服务货口径**的承运方承接；
      //   未定价的 lane（运费走既有 CARRIER_FEE 腿、运力由"劳动 + 工具"派生）只能由未定价格的承运方承接。
      //   ★ 为什么必须有这一道：全局共享之后，两类承运方**进了同一个候选集** —— 若让一个"劳动 + 工具"口径的
      //     承运方去接一条服务成市的 lane，{@code deliverHaulService} 会在它账上找不到 {@code haul} 货
      //     ⇒ 具名 ERROR {@code HAUL_SERVICE_DELIVERY_FAULT} + 整笔成交作废（改前这种组合结构上到不了，
      //     因为候选只来自发货格、口径必然相同）。
      //   ★ 缺省中性：{@code haulServiceHexes} 为空（未定价世界 / 夹具）时两个判据恒等 ⇒ 逐值退回改前。
      if (haulServiceHexes.contains(from) != haulServiceHexes.contains(item.capacity.hex())) {
        differentMarketKindCount++;
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
    // ★★ F-2（2026-10-10）：本笔未获服务的具名归因档（**一次算、两处读** —— DEBUG 行与返回值读的是同一份）。
    //   ★★★ W7（2026-10-11）："没有可交付服务"的判据从**逐格**（发货格的预算合计 ≤ 0）改成**全池**
    //     （{@code totalCapacityMilli <= 0}：装配时全池一件可交付服务货都没有）—— 因为池现在全局共享，
    //     发货格预算为 0 已经**不再**意味着"买不到运力"（这正是本批要修的那件事）。★ 装配后被本轮别的 lane 吃光
    //     走的是 ⑤ capacity-exhausted（既有口径不动）。
    boolean serviceSupplyEmpty = totalCapacityMilli <= 0L;
    LogisticsBlock logisticsBlock =
        logisticsBlockOf(candidatePool, unreachableWork, subUnitWork, serviceSupplyEmpty);
    // ★★ 2026-10-10：真有未服务量时先记一笔"观察"（无条件记 —— 与日志档位无关，否则 INFO 汇总会在只开 INFO 时假报 0），
    //   再按档位发 DEBUG 行。★ 记账结果**只被下面那几个字段读**：不改 demandLeft、不改 choices、不改返回值。
    if (demandLeft > 0L) {
      LaneUnservedObservationBook.Observation observation =
          unservedObservations.observe(requestKey, demandLeft);
      if (LOG.isDebugEnabled()) {
        // ★★ W7-fix3（2026-10-11）：本事件补 `day`（**打头**，字段名与同族 {@code MERCHANT_CAPACITY_POOL} /
        //   {@code MERCHANT_CAPACITY_POOL_HEX} / {@code MERCHANT_CAPACITY_HOUSEHOLD} 同拼写、同位置）——
        //   改前它没有日 ⇒ 读数只能给整轮合计、无法按窗口（日）拆。★ D4 纪律：**只在知道世界日时写**
        //   （{@link #day} ≥ 0 = 池由 tick 面装配）；夹具 / 纯状态读者（{@link #NO_WORLD_DAY}）不带此栏，
        //   绝不用假日凑数 —— 与 {@code EconomySettlement.logEmptiedHouseholdDebtRetained} 同一守卫形态。
        //   ★ 级别、发射条件（{@code demandLeft > 0} 且 DEBUG 开）与其余字段一字未改。
        List<Object> fields = new ArrayList<>(46);
        if (day >= 0L) {
          fields.add("day");
          fields.add(day);
        }
        fields.add("from");
        fields.add(from);
        fields.add("to");
        fields.add(to);
        fields.add("requestedMilli");
        fields.add(quantityMilli);
        fields.add("allocatedMilli");
        fields.add(allocated);
        // ★★ 口径（类注同文）：这是**本次观察**的原始值 —— **不可**逐行相加当真实量（同一请求会被反复观察）。
        fields.add("unallocatedMilli");
        fields.add(demandLeft);
        // ★★ 净额（每份未服务量只算一次）：**对全部行求和 = 真实量**（可当真实量用）。
        fields.add("unallocatedNetMilli");
        fields.add(observation.netMilli());
        // ★★ 该请求的第几次观察（1 起）：≥2 ⇒ 本行与前面某行是**同一份**量（重复倍数的下界读数）。
        fields.add("unallocatedObservationIndex");
        fields.add(observation.observationIndex());
        // ★★ 有没有请求身份：false = 4/5 参旧路径（夹具/纯状态读者）⇒ 本次调用自己成一份。
        fields.add("requestKeyTracked");
        fields.add(observation.requestKeyTracked());
        fields.add("workPerGoodPerMille");
        fields.add(workPerGoodPerMille);
        fields.add("requestedWorkMilli");
        fields.add(CapacityDemand.workMilliOf(quantityMilli, workPerGoodPerMille));
        fields.add("allocatedWorkMilli");
        fields.add(CapacityDemand.workMilliOf(allocated, workPerGoodPerMille));
        fields.add("hexTotalCapacityMilli");
        fields.add(totalCapacityByHex.getOrDefault(from, 0L));
        fields.add("outOfRadiusWorkMilli");
        fields.add(unreachableWork);
        fields.add("subUnitResidualWorkMilli");
        fields.add(subUnitWork);
        fields.add("differentMarketKindCarriers");
        fields.add(differentMarketKindCount);
        fields.add("priced");
        fields.add(priced);
        fields.add("reason");
        fields.add(
            laneBlockedReason(candidatePool, unreachableWork, subUnitWork, serviceSupplyEmpty));
        // ★★ F-2（2026-10-10）：**可分辨的归因档**（与上面 reason 同一组操作数算出的单一支）——
        //   业务拒绝（缺提供商 / 缺服务货）的档位在 INFO 汇总里也出现，不必开 DEBUG。
        fields.add("logisticsDetail");
        fields.add(logisticsBlock.detail());
        fields.add("businessRejection");
        fields.add(logisticsBlock.businessRejection());
        EventLog.channel(LOG)
            .debug(
                LogEvent.of(
                    "MERCHANT_CAPACITY_LANE_TRUNCATED",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    fields.toArray()));
      }
    }
    // ★★ F-2：**具名归因随分配结果一起返回**（"没有服务卖" / "运力不足" / …）—— 读数面据此分档，
    //   不再让两件事在公开读数里同为 LOGISTICS_CAPACITY。★ 分配全成功 ⇒ UNSPECIFIED（不硬塞一个假原因）。
    return new CarrierAllocation(choices, quantityMilli, demandLeft, priced, logisticsBlock);
  }

  /**
   * ★ <b>这一笔为什么没走完</b>（具名归因，按固定次序拼接：本格没有跑商家户 / 本格没有服务货 / 半径外 / 亚单位残余 / 运力耗尽）—— 只在 DEBUG
   * 行里出现，判据本身不改任何行为。★ 多个原因同时成立时**全部列出**（不挑一个代表性说法）。
   *
   * <p>★★ <b>A3（2026-10-10）</b>：原来还有两支"缺工具"归因（{@code tool-frozen} / {@code tool-short}）—— 门槛已删 （见
   * {@link #select}），归因随之退役。
   *
   * <p>★★ <b>F-2（2026-10-10）</b>：本方法与 {@link #logisticsBlockOf} 读**同一组**操作数、各司其职 —— 本方法给"全部同时成立的
   * 原因"（DEBUG 行的诊断串），后者给**可分辨的那一支**（见该方法的优先级表）。
   */
  private static String laneBlockedReason(
      List<Entry> candidatePool,
      long unreachableWork,
      long subUnitWork,
      boolean serviceSupplyEmpty) {
    LogisticsBlock block =
        logisticsBlockOf(candidatePool, unreachableWork, subUnitWork, serviceSupplyEmpty);
    if (block == LogisticsBlock.NO_MERCHANT_HOUSEHOLD
        || block == LogisticsBlock.NO_CARRIER_WITH_SERVICE) {
      return block.detail();
    }
    List<String> reasons = new ArrayList<>(4);
    if (unreachableWork > 0L) {
      reasons.add(LogisticsBlock.OUT_OF_RADIUS.detail());
    }
    if (subUnitWork > 0L) {
      reasons.add(LogisticsBlock.SUB_UNIT_RESIDUAL.detail());
    }
    if (block == LogisticsBlock.CAPACITY_EXHAUSTED) {
      reasons.add(LogisticsBlock.CAPACITY_EXHAUSTED.detail());
    }
    if (reasons.isEmpty()) {
      reasons.add(LogisticsBlock.UNSPECIFIED.detail());
    }
    return String.join("+", reasons);
  }

  /**
   * ★★ <b>F-2（2026-10-10）：可分辨的归因档</b>—— 键 = 本类词表的唯一拼写点，{@link #logisticsBlockOf} 的唯一取值域。★
   * 它是**读数**：不进任何判据、不改任何返回值、不落状态。
   *
   * <p>★ {@link #UNSPECIFIED} = "这一笔确实没走完，但没有一支具名原因成立"（不硬塞一个假原因，也不静默）。 {@link
   * #NO_MERCHANT_HOUSEHOLD} / {@link #NO_CARRIER_WITH_SERVICE} 是**业务拒绝**（§一.9：INFO 档可读），
   * 其余三支是**运行期读数**（DEBUG/TRACE 档）。
   */
  public enum LogisticsBlock {
    /** 全池没有"选了跑商的家户"（连卖家都没有）。 */
    NO_MERCHANT_HOUSEHOLD(NO_MERCHANT_HOUSEHOLD_REASON),
    /**
     * ★★★ W7（2026-10-11）：<b>全池没有一个手上有可交付服务货的承运方</b>（跑商家户都还没产出 / 已卖光 / 够不着）—— 与 {@link
     * HaulService#NO_DELIVERABLE_SERVICE_GOODS_REASON}（货侧：承运方手上没有服务货）**分开拼写**：改前两者同值 {@code
     * no-haul-service-in-shipping-hex}，一个词被读成三件不同的事（见 {@link #NO_CARRIER_WITH_SERVICE_REASON}）。
     */
    NO_CARRIER_WITH_SERVICE(NO_CARRIER_WITH_SERVICE_REASON),
    /** 运力不够（服务/规模都还在，但这一笔吃不下）。 */
    CAPACITY_EXHAUSTED("capacity-exhausted"),
    /** lane 超出提供者的派生服务半径（W7 起口径 = 总行程：自家格 → 发货格 → 收货格）。 */
    OUT_OF_RADIUS("out-of-derived-radius"),
    /** 剩余运力不足一个整商品单位（亚单位残余）。 */
    SUB_UNIT_RESIDUAL("sub-unit-residual"),
    /** 没走完但没有具名原因可归（兜底，不静默）。 */
    UNSPECIFIED("logistics-capacity-unattributed");

    private final String detail;

    LogisticsBlock(String detail) {
      this.detail = detail;
    }

    /**
     * 具名字面量（**小写连字符**，与 {@code HaulService.NO_DELIVERABLE_SERVICE_GOODS_REASON} 同制）：进读数/日志的唯一拼写。
     */
    public String detail() {
      return detail;
    }

    /**
     * ★ 这一档是不是**业务拒绝**（§一.9 的分级：业务拒绝 = INFO 档可读）。
     *
     * <p>★ 只有这两档是"世界缺东西"（缺提供商 / 缺货）⇒ 读者不必开 DEBUG 就能分开它们； 其余三档是运行期读数（"分配用完了"/"够不着"），照旧只在 DEBUG/TRACE
     * 出现。
     */
    public boolean businessRejection() {
      return this == NO_MERCHANT_HOUSEHOLD || this == NO_CARRIER_WITH_SERVICE;
    }
  }

  /**
   * ★★ <b>F-2（2026-10-10）：把"这一笔为什么没走完"收敛成**一个可分辨的档**</b>（与 {@link #laneBlockedReason} 同一组操作数）——
   * 这就是"没有服务卖"与"运力不足"在公开读数面分开的落点。
   *
   * <pre>
   * 次序（先命中先返回；次序本身是口径的一部分）：
   * ① 全池为空                                      ⇒ NO_MERCHANT_HOUSEHOLD（业务拒绝）
   * ② 装配时全池一件可交付服务货都没有（预算合计 ≤ 0）⇒ NO_CARRIER_WITH_SERVICE（业务拒绝）
   * ③ 有预算但全部够不着（总行程超半径）               ⇒ OUT_OF_RADIUS
   * ④ 有够得着的预算但不足一个整商品单位              ⇒ SUB_UNIT_RESIDUAL
   * ⑤ 其余（预算被本轮别的 lane 吃光/被扣到 0）        ⇒ CAPACITY_EXHAUSTED
   * ⑥ 都不成立                                       ⇒ UNSPECIFIED（兜底，不静默）
   * </pre>
   *
   * <p>★ <b>为什么②优先于③④⑤</b>：②说的是"全世界根本没有服务可买"（等下一轮产出），③④⑤说的是"有运力但给不了这一笔" （退单/等运力/改路线）——
   * 两者的处置完全不同，这正是 F-2 要分开的那一刀。★ ①与②也分开（缺提供商 vs 缺货）。
   *
   * <p>★★ <b>W7 的口径收窄（与全局共享配套）</b>：②的判据从"**本格**预算 ≤ 0"改成"**全池**装配时预算 ≤ 0" —— 全局共享之后"本格预算
   * 0"不再是拒绝理由（那正是本批修掉的那件事）；仍为 0 的只剩"全世界都没有可交付服务"。
   *
   * <p>★★★ <b>F1（W7-fix，2026-10-11）：①的判据 = <b>全池候选集</b>（{@code globalOrder}），不是"发货格的池切片"</b>。
   *
   * <pre>
   * 改前（错）  发货格没有跑商家户 ⇒ 报 no-merchant-household-in-shipping-hex —— 而全池**有**货、只是都超半径
   * 改后（对）  全池为空才报 NO_MERCHANT_HOUSEHOLD；全池非空而全部够不着 ⇒ ③ OUT_OF_RADIUS
   * </pre>
   *
   * ★ <b>数值面一字不动</b>：本方法只产出 {@link CarrierAllocation#logisticsBlock()}（读数）， {@code choices} /
   * {@code unallocatedMilli} / 任何扣减都不读它。★ 与候选集同源（{@code select} 的候选 = 全池） ⇒ 归因与"谁真的被考虑过"不可能再漂开。★
   * 缺省中性：单格世界里"全池" = "本格"，逐值退回改前。
   */
  private static LogisticsBlock logisticsBlockOf(
      List<Entry> candidatePool,
      long unreachableWork,
      long subUnitWork,
      boolean serviceSupplyEmpty) {
    if (candidatePool.isEmpty()) {
      return LogisticsBlock.NO_MERCHANT_HOUSEHOLD;
    }
    if (serviceSupplyEmpty) {
      return LogisticsBlock.NO_CARRIER_WITH_SERVICE;
    }
    if (unreachableWork > 0L) {
      return LogisticsBlock.OUT_OF_RADIUS;
    }
    if (subUnitWork > 0L) {
      return LogisticsBlock.SUB_UNIT_RESIDUAL;
    }
    // ★ 走到这里 = 池非空、有服务预算、也没被吃光 —— 却仍然没分完 ⇒ 本笔没吃到的那份预算是"被本轮别的 lane
    //   吃光/扣到亚单位"（remainingWork 或 subUnitWork 已覆盖前者，本条只剩兜底）：具名 capacity-exhausted 是
    //   唯一不猜的说法（不许硬塞"没有服务卖"）。
    return LogisticsBlock.CAPACITY_EXHAUSTED;
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
   * ★★ <b>W7：全池"手上有可交付服务货"的家户数</b>（= 装配时运力预算 &gt; 0 的条目数）—— 全局共享之后， "有多少格能运"不再是有意义的读数（任意格的商号可服务任意
   * lane），"**有多少户手上有货**"才是。
   *
   * <p>★ 与 {@link #householdCount()}（选了跑商的家户数，含预算为 0 的壳户）配对读：前者是"能卖的"，后者是"在册的"。
   */
  public long householdsWithCapacity() {
    long count = 0L;
    for (Entry item : globalOrder) {
      if (item.capacity.capacityMilli() > 0L) {
        count++;
      }
    }
    return count;
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
                // ★★★ W7（2026-10-11）：**池的作用域**（用户裁定：运力提供全市场区共享）—— 这一栏是"为什么某一格的
                //   货能被别格的商号运走"的公开读数面；assignOrder = 全局序（限价升序 → 全池议价权降序 → 家户 id 升序）。
                "poolScope",
                "global-shared",
                "assignOrder",
                priced ? "ask-asc,pool-share-desc,household-asc" : "pool-share-desc,household-asc",
                "householdsWithDeliverableService",
                householdsWithCapacity(),
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
                unservedObservations.maxObservations(),
                // ★★ F-2（2026-10-10）：**F-2 归因的公开读数面** —— 业务拒绝（缺提供商 / 缺服务货）在这里
                //   按**具名键**分档计数（INFO = "这一轮发生了什么"），读者不必开 DEBUG 就能把"没有服务卖"
                //   与"运力不足"分开；运行期三支（半径/亚单位/耗尽）由 MERCHANT_CAPACITY_LANE_TRUNCATED 的
                //   logisticsDetail 与逐格竞争读数给出。★ 没有拒绝 ⇒ 空表（缺省中性：这一栏不新增任何噪声）。
                "serviceGoodsAbsentHouseholds",
                serviceAbsentTally()));
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
   * <p>★★ <b>F-2（2026-10-10）</b>：{@link #logisticsBlock} 是"这笔为什么没走完"的**具名归因档** ——
   * 调用方/读数面据此把"没有服务卖"（业务拒绝，INFO 可读）与"运力不足"分开；全部分配成功 ⇒ {@link
   * LogisticsBlock#UNSPECIFIED}（不硬塞一个假原因）。★ 它<b>不是</b>第二套判据：值是 {@link #select} 里 现算的同一份读数，{@code
   * choices}/{@code unallocatedMilli} 一字不受它影响。
   *
   * @param priced 本次分配是否走报价口径（决定运费怎么收：报价口径逐条按各自限价，缺省口径沿用 M-A1 的按量比例分摊）
   * @param logisticsBlock 本笔未获服务的具名归因（业务拒绝 → INFO；运行期三支 → DEBUG/TRACE）
   */
  public record CarrierAllocation(
      List<CarrierChoice> choices,
      long requestedMilli,
      long unallocatedMilli,
      boolean priced,
      LogisticsBlock logisticsBlock) {

    /** 4 参兼容入口（夹具/纯读者）：归因档取 {@link LogisticsBlock#UNSPECIFIED}（"没有可归的原因"）。 */
    public CarrierAllocation(
        List<CarrierChoice> choices, long requestedMilli, long unallocatedMilli, boolean priced) {
      this(choices, requestedMilli, unallocatedMilli, priced, LogisticsBlock.UNSPECIFIED);
    }

    public CarrierAllocation {
      Objects.requireNonNull(choices, "choices");
      Objects.requireNonNull(logisticsBlock, "logisticsBlock");
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

    /** ★ F-2：本笔的归因是不是**业务拒绝**（§一.9：INFO 档可读）—— 调用方据此选日志档位，不自己判词。 */
    public boolean businessRejection() {
      return unallocatedMilli > 0L && logisticsBlock.businessRejection();
    }
  }
}
