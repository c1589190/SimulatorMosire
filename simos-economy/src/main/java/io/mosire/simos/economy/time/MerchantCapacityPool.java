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
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
 * 报价     每个提供者有一个**自报价（限价）**：{@link CapacityQuote#askPerMilleOf}（成本维 × 规模维 + 具名固定"上门"附加费；
 *          表见 {@link CapacityQuoteBook}）。买方按**限价升序**买（价格优先）⇒ 同一 lane 上各户各有各的价。
 * 同价序   限价相同 ⇒ 沿用 M-A1 的 canonical 序（市场议价权占比‰ 降序 → 家户 id 升序）——"价格管买方的选择、
 *          议价权管稀缺时的分配"（Q-25），**不另设特权队列**。
 * 需求口径 报价口径下运力按**数量 × 距离**（{@link CapacityDemand#workPerGoodPerMille}，毫商品·程）扣减预算；
 *          缺省口径（无报价）仍按毫商品 1:1（M-A1 逐值不变）。
 * 缺省中性 无报价（{@link CapacityQuoteBook#empty()}）⇒ 序与价都退回 M-A1（I-C2），**不是**兼容位而是缺省语义中性。
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
 *       不足一趟 ⇒ **该条跑商不成立**（不放行 = 不成交、不铸 CARRIER_FEE、不烧工具；具名归因见 {@link MerchantHaul} 的 reason 常量）
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
 * 结论          判据 = {@code 当刻可用量 ≥ 一趟}（{@link MerchantHaul#affordsRun}）；{@link Entry#releasedToolMilli()}
 *              **不再参与判据**，只作日志自解释（{@code MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT.releasedMilli}）
 * 单调性        新判据 ≥ 旧判据（逐点，减项非负）⇒ 只会**减少**拦、不会新增拦；fail-closed 方向不变（真不够 ⇒ 拦）
 * 归因副作用    "拦 ⇔ 可用量 &lt; 一趟" ⇒ 归因只剩 {@code tool-short}（真缺货）与 {@code tool-frozen}（被冻结占住）
 *              两支（{@link MerchantHaul#blockedReason} 二选一）；第三档归因（预算档）在**生产路径与 4/5 参旧路径上都结构上不可达**
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
 */
public final class MerchantCapacityPool {

  /** 运力池日志（market 分类：它的生命周期就是市场轮）。 */
  private static final org.slf4j.Logger LOG = EconomyLog.market();

  /** 工具商品（V-22：跑商消耗走**商品账**，不动 {@code AssetKind.TOOL} 产权份额）—— 唯一拼写点。 */
  public static final CommodityId TOOL_COMMODITY = MerchantHaul.TOOL_COMMODITY;

  /** 空池（没有跑商家户的世界 / worker 本地副本）。 */
  private static final MerchantCapacityPool EMPTY =
      new MerchantCapacityPool(Map.of(), Map.of(), Map.of(), 0L, false, 0L, null, null);

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

  /** 是否成市（报价口径；见 {@link CapacityQuoteBook}）。{@code false} ⇒ 缺省口径（M-A1 逐值不变）。 */
  private final boolean priced;

  /** 本轮所有提供者的最高限价（‰；报价口径下 "计划单位运费" 的上界）。 */
  private final long maxAskPerMille;

  /**
   * ★★ <b>G3-fix-2：工具维的<b>活视图</b>（商品账）</b>—— 只有带冻结的重载（生产装配点）才非空；{@link #select}
   * 在承运选择点按它取当刻现货（时点见类注）。
   *
   * <p>{@code null} = 本池没有活视图（4/5 参重载：夹具 / 纯状态读者）⇒ 门槛只看本轮预算余量，与改前逐值相同。
   */
  private final Map<HouseholdId, Map<CommodityId, Long>> liveGoods;

  /** ★★ <b>G3-fix-2：工具维的<b>活视图</b>（冻结账）</b>—— 与 {@link #liveGoods} 成对（同一时点、同一口径）。 */
  private final Map<HouseholdId, Map<CommodityId, Long>> liveFrozenGoods;

  private MerchantCapacityPool(
      Map<HexCoord, List<Entry>> byHex,
      Map<HexCoord, Long> totalCapacityByHex,
      Map<HouseholdId, Entry> byHousehold,
      long householdCount,
      boolean priced,
      long maxAskPerMille,
      Map<HouseholdId, Map<CommodityId, Long>> liveGoods,
      Map<HouseholdId, Map<CommodityId, Long>> liveFrozenGoods) {
    this.byHex = byHex;
    this.totalCapacityByHex = totalCapacityByHex;
    this.byHousehold = byHousehold;
    this.householdCount = householdCount;
    this.priced = priced;
    this.maxAskPerMille = maxAskPerMille;
    this.liveGoods = liveGoods;
    this.liveFrozenGoods = liveFrozenGoods;
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
   * <p>夹具 / 纯状态读者 / 旧调用方走这一条；生产路径走带 {@code frozenGoods} 的 6 参重载（它会带活视图进池）。
   */
  public static MerchantCapacityPool of(
      Map<HouseholdId, HouseholdClassMembership> classStandings,
      Map<ClassPositionId, ProductionRole> classPositions,
      Map<HouseholdId, HouseholdEconomy> rows,
      Map<HouseholdId, Map<CommodityId, Long>> goods) {
    return assemble(
        classStandings,
        classPositions,
        rows,
        goods,
        Map.of(),
        CapacityQuoteBook.empty(),
        null,
        null);
  }

  /**
   * ★★ <b>装配一轮的运力池（唯一入口；每轮重算，不累积）</b>。
   *
   * <p>成员判据（J-A）：{@code classStandings} 里该家户的 {@code effectivePositionIds()} 含任一 {@code
   * classPositions} 中 {@code modeId == merchant} 的位置。运力算式见 {@link MerchantCapacity}；限价见 {@link
   * CapacityQuoteBook#askPerMilleOf}（报价口径）。
   *
   * <p>★ <b>本重载 = 无冻结概念</b>（工具可投入量取**装配点的存量**；夹具 / 纯状态读者 / 旧调用方）：它逐字委托 {@link
   * #assemble}，减项传空表且**不带活视图** ⇒ 与改前**逐值相同**（I-C2 缺省语义中性）。生产路径必须用带冻结的那一条 ——否则"同轮把 tool
   * 全挂进卖单"的户会被误判成有工具（2026-10-10 G3-fix-1 修的就是这个；G3-fix-2 又把读取时点挪到 承运选择点，见类注）。
   *
   * @param classStandings 家户 → 阶层归属（主业 = current、副业 = participating）
   * @param classPositions 位置 → 生产方式（判"是不是跑商"）
   * @param rows 家户行（劳动投入 + 所在格）
   * @param goods 会话商品账（读 {@code tool} 可投入量；没有商品账时传空表 ⇒ 工具项 = 0，见 J-2 下界）
   * @param quotes 本轮的运力报价表（{@link CapacityQuoteBook#empty()} = 无报价 ⇒ 缺省口径；逐轮瞬态）
   */
  public static MerchantCapacityPool of(
      Map<HouseholdId, HouseholdClassMembership> classStandings,
      Map<ClassPositionId, ProductionRole> classPositions,
      Map<HouseholdId, HouseholdEconomy> rows,
      Map<HouseholdId, Map<CommodityId, Long>> goods,
      CapacityQuoteBook quotes) {
    return assemble(classStandings, classPositions, rows, goods, Map.of(), quotes, null, null);
  }

  /**
   * ★★ <b>生产装配点（唯一带"当刻可用量"口径的入口）</b>（2026-10-10 G3-fix-1 + G3-fix-2 + G3-leftovers +
   * G3-fix-3）：{@code tool} 的判据量 = {@code 当刻可用量}（见类注与 {@link #select}；G3-fix-3 删掉了"− 本轮已放行 ×
   * 每趟门槛"这一重复扣减），与提交侧的实扣判据（{@code EconomySettlement.consumeForLoss}：可用量 &lt; 一趟 ⇒
   * 一点也不烧）**同口径、同活表**。
   *
   * <p>★★ <b>为什么必须同口径（实测缺陷，两次）</b>：① 旧实现只读**存量** ⇒ 一个把 tool 全部挂进本轮卖单（冻结 12,000）的户在池里仍显示 {@code
   * toolRemainingMilli=12000}，{@code select} 于是放行该次承运、成交成立、{@code CARRIER_FEE} 照铸；到 {@code
   * settleHaulRuns} 实扣时才发现 {@code available=0} ⇒ "货走了、运费收了、工具没扣"。 ② G3-fix-1
   * 改成读可用量，但读的是**装配点**（市场轮之前）的冻结表 —— 本轮卖单冻结在市场轮内才落表 ⇒ 减项恒为 0，真实世界**一格没变**（G3b 复验 3,589 条逐值未变）。⇒
   * 本重载把 {@code goods}/{@code frozenGoods} <b>作为活视图带进池</b>，由 {@link #select} 在**承运选择点**现读（时点口径见类注）。
   *
   * <p>★ <b>缺省中性</b>：没有活视图的 4/5 参重载（夹具 / 纯状态读者 / 旧调用方）逐值退回改前；本重载的 {@code frozenGoods}
   * 为空表只是"没有冻结概念"，判据仍按当刻活视图推导（这正是 G3-leftovers 修的那条：不再拿装配时点的过期镜像当真值）。
   *
   * @param frozenGoods 会话冻结商品账（{@code max(0, 现货 − 冻结)} 的减项）；没有冻结概念时传 {@code Map.of()}
   */
  public static MerchantCapacityPool of(
      Map<HouseholdId, HouseholdClassMembership> classStandings,
      Map<ClassPositionId, ProductionRole> classPositions,
      Map<HouseholdId, HouseholdEconomy> rows,
      Map<HouseholdId, Map<CommodityId, Long>> goods,
      Map<HouseholdId, Map<CommodityId, Long>> frozenGoods,
      CapacityQuoteBook quotes) {
    return assemble(
        classStandings, classPositions, rows, goods, frozenGoods, quotes, goods, frozenGoods);
  }

  /**
   * <b>装配体（唯一拼写点）</b>：{@code liveGoods}/{@code liveFrozenGoods} = {@link #select} 在承运选择点现读的活视图
   * （{@code null} ⇒ 本池没有活视图，门槛只看本轮预算余量 = 4/5 参旧路径的逐值行为）。
   */
  private static MerchantCapacityPool assemble(
      Map<HouseholdId, HouseholdClassMembership> classStandings,
      Map<ClassPositionId, ProductionRole> classPositions,
      Map<HouseholdId, HouseholdEconomy> rows,
      Map<HouseholdId, Map<CommodityId, Long>> goods,
      Map<HouseholdId, Map<CommodityId, Long>> frozenGoods,
      CapacityQuoteBook quotes,
      Map<HouseholdId, Map<CommodityId, Long>> liveGoods,
      Map<HouseholdId, Map<CommodityId, Long>> liveFrozenGoods) {
    Objects.requireNonNull(classStandings, "classStandings");
    Objects.requireNonNull(classPositions, "classPositions");
    Objects.requireNonNull(rows, "rows");
    Objects.requireNonNull(goods, "goods");
    Objects.requireNonNull(frozenGoods, "frozenGoods");
    Objects.requireNonNull(quotes, "quotes");
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
      // ★★ G3-fix-1：工具可投入量 = **可用量**（存量 − 冻结，下夹 0）—— 与提交侧 `consumeForLoss` 同一判据。
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
      long askPerMille = quotes.askPerMilleOf(capacity);
      maxAsk = Math.max(maxAsk, askPerMille);
      HexCoord hex = capacity.hex();
      // ★★ M-C：纯商号（H-2：**主业** ∈ merchant.*）/ 顺便跑商（merchant 只在副业）—— 免运费与利润算式的分流依据；
      //   判据的唯一拼写点在 {@link MerchantIdentity}。★ 工具预算（H-5 的门槛维）= 装配时点该户的 tool 商品存量，
      //   每次跑商扣 {@link MerchantHaul#TOOL_MILLI_PER_HAUL}（一次性消耗、不返还）。
      boolean pureMerchant = MerchantIdentity.isPureMerchant(standing, classPositions);
      pool.computeIfAbsent(hex, ignored -> new ArrayList<>())
          // ★★ G3-leftovers：`posted`（有没有挂运力单/自报价）在**装配点取一次**存进条目 —— 逐户读数的那条
          //   DEBUG 事件（{@link #logHouseholdAssembly}）在 tick 面的调用方发射（那里才有 `day`），
          //   此时报价表已不在本池手里（它只是装配入参）⇒ 读数必须与装配同源，不能事后从别处重取。
          .add(new Entry(capacity, askPerMille, pureMerchant, quotes.hasPosted(household)));
      totals.merge(hex, capacity.capacityMilli(), Math::addExact);
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
      if (quotes.isPriced()) {
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
            quotes.isPriced(),
            maxAsk,
            liveGoods,
            liveFrozenGoods);
    // ★★ G3-leftovers：本池的逐户**装配**读数（事件 {@code MERCHANT_CAPACITY_HOUSEHOLD}）不在这里发射 ——
    //   本方法拿不到世界日（{@link #of} 的入参里没有 tick），而该事件按 §一.9 必须带 `day` 才能与同日的
    //   {@code MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT} / {@code MARKET_*} 逐户按日对齐。发射点 = tick 面的装配调用方
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

    /**
     * ★★ <b>G3-leftovers：装配点的"有没有挂运力单/自报价"读数</b>（{@code CapacityQuoteBook.hasPosted}）——
     * 报价表是本池的装配入参、不入池，而逐户读数事件由 tick 面的调用方发射（见 {@link #logHouseholdAssembly}）⇒ 在这里取一次存住。只进日志，不改任何判据。
     */
    private final boolean posted;

    private long remainingWorkMilli;
    private long allocatedGoodsMilli;
    private long allocatedWorkMilli;
    private long allocationCount;
    private long sharePerMille;

    /**
     * ★★ M-C + G3-leftovers：**装配时点**的工具预算镜像（毫工具；= 装配时点该户 {@code tool} 可用量）—— 每次跑商扣 {@link
     * MerchantHaul#TOOL_MILLI_PER_HAUL}（一次性消耗、不返还，H-1）。
     *
     * <p>★ 用途随批次收窄：① 4/5 参旧路径（没有活视图）的门槛判据 —— 逐值退回改前；② G3-leftovers 起作「本轮已放行」的计数载体： {@link
     * #releasedToolMilli()} = 装配镜像 − 剩余。生产路径的判据量**不再取它，也不取那个减项**（G3-fix-3：判据 = 当刻可用量）， 见 {@link
     * #select}。
     *
     * <p>★ 轮内该户工具**上升**时它可以为负（已放行的趟数超过装配时点可用量）—— 这是真实读数，不是错值。
     */
    private long remainingToolMilli;

    /** 本轮因**缺工具**未成立的跑商次数（具名归因的计数；读数/日志用）。 */
    private long toolBlockedRuns;

    /** ★★ G3-fix-2：上面那个总数按**政策归因**拆开（各自由实际发生的次数决定；读数/日志用）。 */
    private long toolFrozenBlockedRuns;

    /**
     * ★★ G3-fix-2：{@link #toolFrozenBlockedRuns} 的**另一支**（归因只剩两支，见 {@link
     * MerchantHaul#blockedReason}）。
     */
    private long toolShortBlockedRuns;

    /** ★★ G3-fix-2：**首次**被拦下时的具名归因与读数（给轮末的逐户 DEBUG 行；不改任何判据）。 */
    private String toolBlockedReason;

    private long toolBlockedStockMilli;
    private long toolBlockedFrozenMilli;
    private long toolBlockedAvailableMilli;

    /** ★★ G3-leftovers / G3-fix-3：首次被拦下时的"本轮已放行 × 每趟门槛"读数 —— **只进日志**，G3-fix-3 起不参与判据。 */
    private long toolBlockedReleasedMilli;

    /** ★ 本轮的运费实收（按币分列）—— **每轮算出的读数**，只进日志/读口，不落状态（冻结项 6 的"不落状态"）。 */
    private final Map<CurrencyId, Long> earnedByCurrency = new LinkedHashMap<>();

    private Entry(
        MerchantCapacity capacity, long askPerMille, boolean pureMerchant, boolean posted) {
      this.capacity = capacity;
      this.askPerMille = askPerMille;
      this.pureMerchant = pureMerchant;
      this.posted = posted;
      this.remainingWorkMilli = capacity.capacityMilli();
      this.remainingToolMilli = capacity.toolMilli();
    }

    /**
     * ★★ <b>G3-leftovers：本轮**已放行**的趟数 × 每趟门槛</b>（毫工具）= 装配镜像 − 剩余 —— **只作日志自解释与计数** （"放行过几趟"）。★
     * G3-fix-3 起它**不再是判据量的减项**：活视图已含本轮燃烧，减它 = 同一笔扣两次（见类注与 {@link #select}）。
     */
    private long releasedToolMilli() {
      return Math.max(0L, capacity.toolMilli() - remainingToolMilli);
    }

    /**
     * ★★ <b>G3-fix-2：记一次"被工具门槛拦下"</b>（在承运选择点，判据量不足一趟 ⇒ 该条跑商不成立）—— 只累加读数与首次样本，不改任何判据、不写账户。
     *
     * @param reason {@link MerchantHaul} 的两个具名归因之一
     * @param stockMilli 当刻现货（{@code -1} = 本池没有活视图，旧路径）
     * @param releasedMilli ★ G3-leftovers：当刻的"本轮已放行 × 每趟门槛"读数（**只进日志**；G3-fix-3 起不参与判据）
     */
    private void recordToolBlock(
        String reason, long stockMilli, long frozenMilli, long availableMilli, long releasedMilli) {
      toolBlockedRuns++;
      // ★ 归因只有两支（判据 = 当刻可用量本身 ⇒ "被拦"必有真缺口：真缺货 / 被冻结占住）；
      //   唯一拼写点是 MerchantHaul.blockedReason ⇒ 这里的 else 就是 tool-short。
      if (MerchantHaul.TOOL_FROZEN_REASON.equals(reason)) {
        toolFrozenBlockedRuns++;
      } else {
        toolShortBlockedRuns++;
      }
      if (toolBlockedReason == null) {
        toolBlockedReason = reason;
        toolBlockedStockMilli = stockMilli;
        toolBlockedFrozenMilli = frozenMilli;
        toolBlockedAvailableMilli = availableMilli;
        toolBlockedReleasedMilli = releasedMilli;
      }
    }
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
   * ★★ <b>M-C：本轮的**跑商家户**（= 池成员，判据 {@link MerchantIdentity#selectsMerchant}）</b>——
   * 商号利润读数的**范围**（没有跑商家户 ⇒ 空集 ⇒ 读数不产出，缺省语义中性 I-C2）。
   *
   * <p>★ 返回的是 {@link #byHousehold} 的键集（构造期已冻结、不可改）；顺序由调用方自己规范（本仓 I7）。
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
   * <p>★★ <b>G3-fix-2 + G3-leftovers + G3-fix-3：工具门槛的判据量在<b>本方法内、逐条承运被判定的那一刻</b>现取</b>（时点与理由见类注）：
   * {@code 判据量 = 当刻可用量}（G3-fix-3 起不再减"本轮已放行 × 每趟门槛" —— 活视图已含本轮燃烧，减它是重复扣减）； 不足一趟 ⇒
   * 该条不产生（不成交、不铸运费、不烧工具）， 具名归因 {@code tool-frozen} / {@code tool-short} 二选一（{@link
   * MerchantHaul#blockedReason}；第三档归因自 G3-fix-3 起不可达、已随其常量与计数删除）。 ★ 没有活视图的 4/5
   * 参旧路径只判本轮预算余量（逐值退回改前）。
   *
   * @param from 发货格（运力池所在的格）
   * @param to 收货格（判半径）
   * @param quantityMilli 本笔请求承运量（毫商品）
   * @param workPerGoodPerMille 本 lane 的运力耗用（‰；{@link CapacityDemand}。缺省口径传 1000 = 1:1）
   */
  public CarrierAllocation select(
      HexCoord from, HexCoord to, long quantityMilli, long workPerGoodPerMille) {
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
    long toolBlockedRuns = 0L;
    long toolFrozenBlockedRuns = 0L;
    long toolShortBlockedRuns = 0L;
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
      // ★★ M-C：跑商门槛 = 工具（H-D/H-5）。手里的工具不够一趟 ⇒ **该次跑商不成立**（具名归因，绝不静默跳过）：
      //   该条承运不产生，需求转成"运力未获服务"（K-4/Q-27：不成交、不成债、不计价）。
      //   ★ 判据只看**工具够不够一趟**，与 lane 长短/批量无关 —— 它是**门槛**，不是按量计的费。
      // ★★ G3-fix-2：判据量在**承运选择点**现取 —— 见下面的 liveToolStockMilli/FrozenMilli（时点与算式写在类注）。
      // ★★ G3-leftovers：减项曾 = "本轮已放行 × 每趟门槛"（不再取装配时点的过期镜像）。
      // ★★ G3-fix-3：**该减项已删** —— 活视图已含本轮燃烧（同一张活表 + 实扣在两次 select 之间落账，证据见类注）
      //   ⇒ 旧式 = 同一笔扣两次（B 世界 25 趟假拦）。判据 = **当刻可用量本身**。
      long toolStockMilli = liveToolStockMilli(item.capacity.household());
      long toolFrozenMilli = liveToolFrozenMilli(item.capacity.household());
      long toolAvailableMilli =
          toolStockMilli < 0L ? -1L : Math.max(0L, toolStockMilli - toolFrozenMilli);
      long toolCheckMilli =
          toolAvailableMilli < 0L
              ? item.remainingToolMilli // 无活视图（4/5 参旧路径）⇒ 只判本轮预算，逐值退回改前
              // 生产路径：判据量 = 当刻可用量（G3-fix-3；releasedMilli 只进日志，不参与判据）。
              : toolAvailableMilli;
      if (!MerchantHaul.affordsRun(toolCheckMilli)) {
        // ★ 归因二选一（唯一拼写点 = MerchantHaul.blockedReason）：真缺货 / 被冻结占住。
        //   第三档（预算档）自 G3-fix-3 起不可达（判据量 = 可用量本身 < 一趟），已随其常量与计数删除。
        String toolReason =
            MerchantHaul.blockedReason(toolStockMilli, MerchantHaul.TOOL_MILLI_PER_HAUL);
        item.recordToolBlock(
            toolReason,
            toolStockMilli,
            toolFrozenMilli,
            toolAvailableMilli,
            item.releasedToolMilli());
        if (MerchantHaul.TOOL_FROZEN_REASON.equals(toolReason)) {
          toolFrozenBlockedRuns++;
        } else {
          toolShortBlockedRuns++;
        }
        toolBlockedRuns++;
        continue;
      }
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
      item.remainingToolMilli -= MerchantHaul.TOOL_MILLI_PER_HAUL; // 一次性消耗（H-1）
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
              MerchantHaul.TOOL_MILLI_PER_HAUL,
              take,
              consumed));
    }
    long allocated = quantityMilli - demandLeft;
    if (LOG.isDebugEnabled() && demandLeft > 0L) {
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
                  "unallocatedMilli",
                  demandLeft,
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
                  "toolBlockedRuns",
                  toolBlockedRuns,
                  // ★★ G3-fix-2：把"缺工具"这一条按**政策归因**拆开（tool-frozen / tool-short 两支）。
                  //   ★ 第三档（预算档）自 G3-fix-3 起不可达（判据 = 可用量本身，拦 ⇔ 可用量 < 一趟）⇒ 2026-10-10 清理已删字段。
                  "toolFrozenBlockedRuns",
                  toolFrozenBlockedRuns,
                  "toolShortBlockedRuns",
                  toolShortBlockedRuns,
                  "toolMilliPerHaul",
                  MerchantHaul.TOOL_MILLI_PER_HAUL,
                  "priced",
                  priced,
                  "reason",
                  laneBlockedReason(
                      pool,
                      unreachableWork,
                      subUnitWork,
                      toolBlockedRuns,
                      toolFrozenBlockedRuns,
                      toolShortBlockedRuns)));
    }
    return new CarrierAllocation(choices, quantityMilli, demandLeft, priced);
  }

  /**
   * ★★ <b>G3-fix-2：一个家户的<b>当刻现货</b></b>（承运选择点现读活表）。返回 {@code -1} = <b>本池没有活视图</b> （4/5 参旧路径：夹具 /
   * 纯状态读者）⇒ 调用方只判本轮预算余量（与改前逐值相同）。
   */
  private long liveToolStockMilli(HouseholdId household) {
    if (liveGoods == null) {
      return -1L; // "不可知"（不是 0：0 会被误读成"真缺货"）
    }
    return liveGoods.getOrDefault(household, Map.of()).getOrDefault(TOOL_COMMODITY, 0L);
  }

  /** ★★ <b>G3-fix-2：一个家户的<b>当刻冻结量</b></b>（同一张活表、同一时点；没有活视图 ⇒ 0 = 无冻结概念）。 */
  private long liveToolFrozenMilli(HouseholdId household) {
    if (liveFrozenGoods == null) {
      return 0L;
    }
    return liveFrozenGoods.getOrDefault(household, Map.of()).getOrDefault(TOOL_COMMODITY, 0L);
  }

  /**
   * ★ <b>这一笔为什么没走完</b>（具名归因，按固定次序拼接：缺工具 / 半径外 / 亚单位残余 / 运力耗尽 / 本格没有跑商家户）—— 只在 DEBUG
   * 行里出现，判据本身不改任何行为。★ 多个原因同时成立时**全部列出**（不挑一个代表性说法）； ★★ G3-fix-2 起"缺工具"按政策归因拆开 （{@code tool-frozen}
   * / {@code tool-short} 两支，各自由实际发生的次数决定；第三档归因自 G3-fix-3 起不可达、已删）。
   */
  private static String laneBlockedReason(
      List<Entry> pool,
      long unreachableWork,
      long subUnitWork,
      long toolBlockedRuns,
      long toolFrozenBlockedRuns,
      long toolShortBlockedRuns) {
    if (pool.isEmpty()) {
      return "no-merchant-household-in-shipping-hex";
    }
    List<String> reasons = new ArrayList<>(3);
    if (toolBlockedRuns > 0L) {
      // ★ T-fix/G3-fix-2：唯一拼写点在 MerchantHaul（此处不再写第二遍字面量）
      if (toolFrozenBlockedRuns > 0L) {
        reasons.add(MerchantHaul.TOOL_FROZEN_REASON);
      }
      if (toolShortBlockedRuns > 0L) {
        reasons.add(MerchantHaul.TOOL_SHORT_REASON);
      }
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

  /** 该户本轮的工具预算还剩多少（毫工具；读数/日志用；不在池里 ⇒ 0）。 */
  public long remainingToolMilliOf(HouseholdId household) {
    Entry entry = byHousehold.get(household);
    return entry == null ? 0L : entry.remainingToolMilli;
  }

  /** 本轮因缺工具未成立的跑商次数（全部家户之和；INFO 汇总用）。 */
  public long toolBlockedRuns() {
    long total = 0L;
    for (List<Entry> entries : byHex.values()) {
      for (Entry item : entries) {
        total = Math.addExact(total, item.toolBlockedRuns);
      }
    }
    return total;
  }

  /**
   * ★★ G3-fix-2：本轮被工具门槛拦下的次数按政策归因拆分（{@code tool-frozen} = 下标 0、{@code tool-short} = 1）。 ★ 第三档（预算档）自
   * G3-fix-3 起恒不可达（判据 = 当刻可用量本身 ⇒ 拦下必有真缺口）⇒ 2026-10-10 清理已删该档（含其下标）。
   */
  private long[] toolBlockedByReason() {
    long frozen = 0L;
    long shortRuns = 0L;
    for (List<Entry> entries : byHex.values()) {
      for (Entry item : entries) {
        frozen = Math.addExact(frozen, item.toolFrozenBlockedRuns);
        shortRuns = Math.addExact(shortRuns, item.toolShortBlockedRuns);
      }
    }
    return new long[] {frozen, shortRuns};
  }

  /**
   * ★★ <b>G3-fix-2：逐户"本轮为什么没跑成"</b>（DEBUG；只在**有**被拦下的户上打，一轮一行/户）—— 这是"门槛不成立 ⇒ 该笔不成立"的**具名证据**（带
   * {@code day}：可与同日的 {@code CARRIER_FEE_PAID} 逐户对照； 生产路径上不该出现"这户今天被 tool-frozen 拦过、却又收过运费"的组合）。
   */
  private void logToolBlocks(long day) {
    if (!LOG.isDebugEnabled()) {
      return;
    }
    for (Map.Entry<HexCoord, List<Entry>> hex : byHex.entrySet()) {
      for (Entry item : hex.getValue()) {
        if (item.toolBlockedRuns <= 0L) {
          continue;
        }
        EventLog.channel(LOG)
            .debug(
                LogEvent.of(
                    "MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    day,
                    "household",
                    item.capacity.household().value(),
                    "hex",
                    item.capacity.hex(),
                    "blockedRuns",
                    item.toolBlockedRuns,
                    "frozenBlockedRuns",
                    item.toolFrozenBlockedRuns,
                    "shortBlockedRuns",
                    item.toolShortBlockedRuns,
                    "reason",
                    item.toolBlockedReason,
                    "stockMilli",
                    item.toolBlockedStockMilli,
                    "frozenMilli",
                    item.toolBlockedFrozenMilli,
                    "availableMilli",
                    item.toolBlockedAvailableMilli,
                    "neededMilli",
                    MerchantHaul.TOOL_MILLI_PER_HAUL,
                    // ★★ G3-leftovers 加字段 / G3-fix-3 改语义：**本轮已放行 × 每趟门槛**（首次被拦那一刻的读数）——
                    //   ★ 它**不参与 G3-fix-3 起的判据**，只回答"这一轮到这户为止已经放行过几趟"（同一行里
                    //   availableMilli / neededMilli 才是判据的两个操作数：拦 ⇔ availableMilli < neededMilli，
                    //   而"为什么可用量少了"仍由它 + stockMilli/frozenMilli 解释）。
                    "releasedMilli",
                    item.toolBlockedReleasedMilli));
      }
    }
  }

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
   * 面提供；同款先例 = {@code EconomySettlement} 装配后紧接着发的 {@code CAPACITY_QUOTE_BOOK}。 ★
   * <b>发射时点与改前逐值一致</b>：仍然是"装配完立刻发"（早于市场轮），因此 {@code toolRemainingMilli} / {@code runsAffordable}
   * 读到的是**装配时点**的预算镜像（= {@code toolMilli}），与改前那一行逐值相同。
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
                    "posted",
                    item.posted,
                    "pureMerchant",
                    item.pureMerchant,
                    "toolRemainingMilli",
                    item.remainingToolMilli,
                    "runsAffordable",
                    MerchantHaul.runsAffordable(item.remainingToolMilli),
                    "priced",
                    priced));
      }
    }
  }

  /** ★ 一轮结束后的逐格分配汇总（INFO = 每格运力池与分配汇总；§一.9）。 */
  public void logRoundSummary(long day) {
    // ★★ G3-fix-2：门槛归因的逐户证据（DEBUG；与 INFO 汇总的开关无关 —— 没有活视图 / 没有被拦下的户 ⇒ 一行不打）。
    logToolBlocks(day);
    if (!LOG.isInfoEnabled()) {
      return;
    }
    long usedGoodsTotal = 0L;
    long usedWorkTotal = 0L;
    long allocationsTotal = 0L;
    long toolBlockedTotal = 0L;
    long remainingToolTotal = 0L;
    long[] blockedByReason = toolBlockedByReason();
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
      for (Entry item : entry.getValue()) {
        toolBlockedTotal = Math.addExact(toolBlockedTotal, item.toolBlockedRuns);
        remainingToolTotal = Math.addExact(remainingToolTotal, item.remainingToolMilli);
      }
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
                // ★★ M-C（§一.9：INFO = 门槛与利润汇总的门槛面）：本轮因缺工具未成立的跑商次数 + 池里剩余工具。
                "toolBlockedRuns",
                toolBlockedTotal,
                // ★★ G3-fix-2：上面那个总数按政策归因拆开（tool-frozen / tool-short；判据的唯一拼写点在
                // MerchantHaul）。★ 第三档（预算档）自 G3-fix-3 起不可达 ⇒ 2026-10-10 清理已删该字段。
                "toolFrozenBlockedRuns",
                blockedByReason[0],
                "toolShortBlockedRuns",
                blockedByReason[1],
                "toolMilliRemaining",
                remainingToolTotal,
                "toolMilliPerHaul",
                MerchantHaul.TOOL_MILLI_PER_HAUL));
  }

  /**
   * ★ <b>一条分配结果（承运条目 = 一次跑商）</b>。
   *
   * @param pureMerchant 该提供者是不是**纯商号**（H-2：主业 ∈ merchant.*）—— 免运费（H-A）与利润算式的分流依据
   * @param toolMilli 本次跑商**要烧掉**的工具（毫工具；H-D/H-1 的一次性消耗，成交时从商品账扣）
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
      long toolMilli,
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
      if (toolMilli < 0L) {
        throw new IllegalArgumentException("CarrierChoice.toolMilli 不得为负: " + toolMilli);
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
