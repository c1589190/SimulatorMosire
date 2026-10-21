package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MerchantPolicy;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.model.TransportCapacityBatch;
import io.mosire.simos.economy.model.TransportCapacityBatchKey;
import io.mosire.simos.economy.model.TransportRoundPlan;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
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

  /**
   * ★★ <b>A3（2026-10-10）："运力服务含上门"这一腿的具名固定附加费（‰）</b>：<b>25</b>。
   *
   * <p>★ <b>从旧 {@code CapacityQuote.GET_READY_SURCHARGE_PER_MILLE} 原值搬来</b>（自报价簿退役，这一档口径保留）：
   * 与既有承运成本的一档步长同值（{@link MarketSettlement#MARKET_FREIGHT_CARRIER_COST_PER_MILLE_PER_TIER_STEP} =
   * 25‰/档）——"一趟上门"按一个档次的运营成本计价，是粗估、可由 GM 后续调；<b>只改这一个常量就改上门腿</b>。 ★ <b>深度
   * 1</b>：它只加一次，不参与递归（用户原话「注意"运力"过来同样需要运力」的终止规则）；也不随 lane 距离变化。
   */
  public static final long GET_READY_SURCHARGE_PER_MILLE = 25L;

  private static final MerchantCapacityPool EMPTY =
      new MerchantCapacityPool(MerchantCapacityCore.emptyCore());

  private final MerchantCapacityCore core;

  private MerchantCapacityPool(MerchantCapacityCore core) {
    this.core = core;
  }

  /** 空池（没有跑商家户的世界 / worker 本地副本）。 */
  public static MerchantCapacityPool empty() {
    return EMPTY;
  }

  /**
   * ★★★ <b>D-032 C7b2：活动运输池唯一装配入口</b> —— 只从当前 marketRoundId 的 C6 批次构造。
   *
   * <p>不再读旧 merchant 角色/haul 服务货/劳动+工具；不一致批次/空 plan 一律安全退化，plan 与批次如何校验由 {@link
   * MerchantCapacityCore#assembleTransportPlan} 承担。
   */
  public static MerchantCapacityPool ofTransportPlan(
      TransportRoundPlan plan, Map<TransportCapacityBatchKey, TransportCapacityBatch> batches) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(batches, "batches");
    return new MerchantCapacityPool(MerchantCapacityCore.assembleTransportPlan(plan, batches));
  }

  public static MerchantCapacityPool of(
      Map<HouseholdId, HouseholdClassMembership> classStandings,
      Map<ClassPositionId, ProductionRole> classPositions,
      Map<HouseholdId, HouseholdEconomy> rows,
      Map<HouseholdId, Map<CommodityId, Long>> goods) {
    return new MerchantCapacityPool(
        MerchantCapacityCore.assemble(
            classStandings,
            classPositions,
            rows,
            goods,
            Map.of(),
            false,
            null,
            MerchantCapacityCore.NO_WORLD_DAY));
  }

  public static MerchantCapacityPool of(
      Map<HouseholdId, HouseholdClassMembership> classStandings,
      Map<ClassPositionId, ProductionRole> classPositions,
      Map<HouseholdId, HouseholdEconomy> rows,
      Map<HouseholdId, Map<CommodityId, Long>> goods,
      boolean priced) {
    return new MerchantCapacityPool(
        MerchantCapacityCore.assemble(
            classStandings,
            classPositions,
            rows,
            goods,
            Map.of(),
            priced,
            null,
            MerchantCapacityCore.NO_WORLD_DAY));
  }

  public static MerchantCapacityPool of(
      Map<HouseholdId, HouseholdClassMembership> classStandings,
      Map<ClassPositionId, ProductionRole> classPositions,
      Map<HouseholdId, HouseholdEconomy> rows,
      Map<HouseholdId, Map<CommodityId, Long>> goods,
      Map<HouseholdId, Map<CommodityId, Long>> frozenGoods,
      boolean priced) {
    return new MerchantCapacityPool(
        MerchantCapacityCore.assemble(
            classStandings,
            classPositions,
            rows,
            goods,
            frozenGoods,
            priced,
            null,
            MerchantCapacityCore.NO_WORLD_DAY));
  }

  public static MerchantCapacityPool of(
      Map<HouseholdId, HouseholdClassMembership> classStandings,
      Map<ClassPositionId, ProductionRole> classPositions,
      Map<HouseholdId, HouseholdEconomy> rows,
      Map<HouseholdId, Map<CommodityId, Long>> goods,
      Map<HouseholdId, Map<CommodityId, Long>> frozenGoods,
      boolean priced,
      Set<HexCoord> haulServiceHexes) {
    return new MerchantCapacityPool(
        MerchantCapacityCore.assemble(
            classStandings,
            classPositions,
            rows,
            goods,
            frozenGoods,
            priced,
            haulServiceHexes,
            MerchantCapacityCore.NO_WORLD_DAY));
  }

  public static MerchantCapacityPool of(
      Map<HouseholdId, HouseholdClassMembership> classStandings,
      Map<ClassPositionId, ProductionRole> classPositions,
      Map<HouseholdId, HouseholdEconomy> rows,
      Map<HouseholdId, Map<CommodityId, Long>> goods,
      Map<HouseholdId, Map<CommodityId, Long>> frozenGoods,
      boolean priced,
      Set<HexCoord> haulServiceHexes,
      long day) {
    return new MerchantCapacityPool(
        MerchantCapacityCore.assemble(
            classStandings,
            classPositions,
            rows,
            goods,
            frozenGoods,
            priced,
            haulServiceHexes,
            day));
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

  public static boolean hasCapacityAt(EconomyDayView base, HexCoord hex) {
    return MerchantCapacityMath.hasCapacityAt(base, hex);
  }

  public static long hexCapacityMilli(
      EconomyDayView base, HexCoord hex, Map<HouseholdId, Map<CommodityId, Long>> goods) {
    return MerchantCapacityMath.hexCapacityMilli(base, hex, goods);
  }

  public static long providerCapacityMilli(
      EconomyDayView base,
      HouseholdId household,
      HexCoord hex,
      Map<HouseholdId, Map<CommodityId, Long>> goods) {
    return MerchantCapacityMath.providerCapacityMilli(base, household, hex, goods);
  }

  public static long sharePerMilleAsProviderAt(
      EconomyDayView base,
      HouseholdId household,
      HexCoord hex,
      Map<HouseholdId, Map<CommodityId, Long>> goods) {
    return MerchantCapacityMath.sharePerMilleAsProviderAt(base, household, hex, goods);
  }

  public static long poolCapacityMilli(
      EconomyDayView base, Map<HouseholdId, Map<CommodityId, Long>> goods) {
    return MerchantCapacityMath.poolCapacityMilli(base, goods);
  }

  static long askPerMilleOf(MerchantCapacity capacity, boolean priced) {
    return MerchantCapacityMath.askPerMilleOf(capacity, priced);
  }

  public Map<String, Long> serviceAbsentTally() {
    return core.serviceAbsentTally();
  }

  public boolean isEmpty() {
    return core.isEmpty();
  }

  public boolean isPriced() {
    return core.isPriced();
  }

  public long maxAskPerMille() {
    return core.maxAskPerMille();
  }

  public boolean hasCapacityAt(HexCoord hex) {
    return core.hasCapacityAt(hex);
  }

  public long totalCapacityAt(HexCoord hex) {
    return core.totalCapacityAt(hex);
  }

  public long householdCountAt(HexCoord hex) {
    return core.householdCountAt(hex);
  }

  public long remainingCapacityAt(HexCoord hex) {
    return core.remainingCapacityAt(hex);
  }

  public long householdCount() {
    return core.householdCount();
  }

  public Map<HouseholdId, Map<CurrencyId, Long>> freightEarnedByHousehold() {
    return core.freightEarnedByHousehold();
  }

  public java.util.Set<HouseholdId> householdIds() {
    return core.householdIds();
  }

  public int hexCount() {
    return core.hexCount();
  }

  public long totalCapacityMilli() {
    return core.totalCapacityMilli();
  }

  public long workPerGoodPerMilleOf(long commodityBaseMilli, long ratePerMille) {
    return core.workPerGoodPerMilleOf(commodityBaseMilli, ratePerMille);
  }

  public CarrierAllocation select(HexCoord from, HexCoord to, long quantityMilli) {
    return core.select(from, to, quantityMilli);
  }

  public CarrierAllocation select(
      HexCoord from, HexCoord to, long quantityMilli, long workPerGoodPerMille) {
    return core.select(from, to, quantityMilli, workPerGoodPerMille);
  }

  public CarrierAllocation select(
      HexCoord from, HexCoord to, long quantityMilli, long workPerGoodPerMille, String requestKey) {
    return core.select(from, to, quantityMilli, workPerGoodPerMille, requestKey);
  }

  public long laborHoursOf(HouseholdId household, long consumedWorkMilli) {
    return core.laborHoursOf(household, consumedWorkMilli);
  }

  public void recordFee(HouseholdId household, CurrencyId currency, long amountMilli) {
    core.recordFee(household, currency, amountMilli);
  }

  public void logHouseholdAssembly(long day) {
    core.logHouseholdAssembly(day);
  }

  public long householdsWithCapacity() {
    return core.householdsWithCapacity();
  }

  public void logRoundSummary(long day) {
    core.logRoundSummary(day);
  }
}
