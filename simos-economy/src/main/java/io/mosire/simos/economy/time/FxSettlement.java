package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.fx.FxFill;
import io.mosire.simos.economy.api.fx.FxRejectReason;
import io.mosire.simos.economy.api.fx.FxVenue;
import io.mosire.simos.economy.api.fx.OfficialRate;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;

/**
 * ★★ <b>外汇撮合（阶段 2-A2a；约束设计书 §3.2/§3.4）</b>：把 FX 委托纳入<b>既有的市场轮</b>，与商品撮合共用同一处落账口。
 *
 * <pre>
 * ① 报价：逐窗口（GOV）算 bidP/askP 与两侧容量（三项约束 + 国库付得起；见 {@link GovFxWindow}）
 * ② 建簿：<b>窗口挂单先</b>（政府是这个市场上的挂牌方），再逐格逐户的<b>民间簿</b>家户单
 *        ★★ P-T5 起家户单<b>不再寄生官方报价</b>（口岸设计书 §19.3 / §20.2 的 F-1~F-4）：
 *          看手里有哪几种币 → 逐币算"付清自己的生活消费品需求要付多少"（{@link HouseholdPurchasingPower}）
 *          → 付得最少的那种 = 购买力最强 → <b>最弱先换</b>（同强度按币种 id 升序），<b>挂单全部</b>，
 *            限价 = 同一份价格口径（F-4；不用家户估值表）   —— 缺价的币算不出购买力 ⇒ 不换（不猜）
 * ③ 撮合：价格-时间优先、可吃深度 —— 买单按限价降序、卖单按限价升序，逐个交叉配对；
 *         成交量 = min(双方剩余, 买方付得起, 卖方拿得出)，成交价 = 两限价的中间价（{@link FxPricing}）
 * ④ 落账：每笔 = <b>两条腿</b>，逐条经唯一写口 {@code EconomySettlement.applyTransfer}（铁律 2 不被绕过）
 * </pre>
 *
 * <p>★★ <b>P-T5：民间簿（家户自报价；口岸设计书 §18.3 C-1 / §19 / §20）</b>——本条之前 {@code pairs}（币对报价）的
 * <b>唯一来源是政府窗口</b>，没有窗口就一条家户单都不生成（"政府不愿开通道就民间自愿"等于零）。本批起：
 *
 * <ul>
 *   <li><b>不依赖窗口</b>：窗口仍是"强力信用主体"之一（其挂单照旧进簿），但<b>不是</b>家户挂单的前提；
 *   <li><b>时点不变</b>：FX 轮仍在商品撮合与信用<b>之后</b>（F-5；= 用户"挂完生产需求后"）；
 *   <li><b>币对定向</b>：有窗口簿的币对就并入那个定向（家户按该定向挂买/卖单，能直接与窗口撮合）；没有窗口簿的币对 新建<b>民间簿</b>，定向 = 币种 id 升序（base =
 *       小 id）—— 纯函数，不依赖任何迭代序（I7）；
 *   <li><b>量 = 全部可花额</b>（F-3：不扣生活/生产储备；"能换多少换多少"）；允许部分成交，未成交部分下一轮重新挂 （订单逐轮瞬态，本类不存任何跨轮状态）；
 *   <li><b>撮合序一个字不改</b>：价格优先、同价按既有 canonical 序（{@code Order#ownerRank}）—— 用户 2026-10-10 确认
 *       "走的是市场自然议价"，<b>不另设特权队列</b>（§20.3 甲条）。
 * </ul>
 *
 * <p>★ <b>缺省中性（I-C2）</b>：单币世界（市场计价币与家户持币只有一种）在计划<b>之前</b>就早退，一个数都不动；多币世界但没有 可比的价（空 naturalNeeds /
 * 法定区缺价）⇒ 不挂单 ⇒ 同样逐值不变。
 *
 * <p>★★ <b>本类不是第二个 applier</b>：它只算"谁给谁多少"，动账一律 {@code applyTransfer}；因此逐币种守恒（I20）不是
 * 本类的性质，而是"只有一条写路径"的性质 —— 本类一个账户 {@code put} 都不写。
 *
 * <p>★★ <b>同币对多份报价（2026-10-09 A 批；设计书 §4.4 / G3）</b>：同一币对的生效报价可以有多条（多个 GOV 各挂各的价）， <b>全部并存</b> ——
 * 每个窗口各进簿一张单，按 {@code limitPerMille} 价格优先撮合（{@link #matchBook}）。多份【不同】报价按 {@code
 * FX_PAIR_MULTIPLE_QUOTES} 具名记录（INFO，永不静默）；多份【相同】报价不刷日志（§一.9）。★ <b>不得按区收窄撮合域</b>：
 * 家户自己看价、直接买，不存在"只能跟本区成交"（该问法已作废，设计书 §1.3/§2.2）。
 *
 * <p>★ <b>P-T5 起"最优摘要"（{@code PairRates}）不再当家户的锚</b>：家户的限价只从 F-1/F-4 的购买力口径来（见上），摘要只服务
 * 窗口簿的建簿与多份报价的日志。
 *
 * <p>★★ <b>为什么放在市场轮里、而不是另开一条日结算支</b>：① 家户能花的钱是"商品市场撮合之后"的余额（同一份账户表）； ② 冻结/信用/未成交归因都在这一轮里；③
 * 官方汇率与实际汇率的对照必须落在同一个世界日上（F4）。
 *
 * <p>★★ <b>家户规则为什么<b>不</b>看窗口当轮的容量</b>：容量是"政府这一轮做不做"，而家户的购买力/限价来自<b>价格表 + 自己的生活
 * 消费品需求</b>（F-1/F-4，与任何官方报价无关）。若让家户规则依赖窗口容量，窗口一停（储备见底/停做）整个外汇市场就消失 —— 这正是本批要修掉的
 * C-1：<b>官方汇率只在窗口有量时才拉动市场价</b>，窗口停了，市场还在，价格由家户自己按价格表定。
 *
 * <p>★ <b>本轮的边界（如实记）</b>：逐轮瞬态、不落盘；FX 成交不进 {@code ProductionLedger.transfers}（那是生产腿的账）， 只进本轮 {@link
 * FxRoundResult} 与日志 ——"最近 N 笔"因此是<b>逐轮</b>读数（跨轮滚动窗口需要持久化，与 I17 冲突，见 {@code FxMarketRate} 的类注）。
 */
final class FxSettlement {

  /**
   * ★ <b>最小手</b>（base 最小单位）：1000 = 1 个币种单位（A 阶段两币种 {@code scale = 3}）。
   *
   * <p>★ 它是<b>GM 可调默认值</b>：没有它，逐毫的尘埃成交会把"实际汇率"这个加权均价搅浑，也给日志刷量。
   *
   * <p>★ <b>P-T5 删掉了哪些旧口径（一并退役，不保留）</b>：{@code FX_SELL_DISTRESS_PER_MILLE = 100}（"外币完全花不出去 ⇒
   * 在政府买价下再让 10%"）与"有盈余才买外币"（{@code spendableQuote − reserveMoney > 0}、按官方 {@code ask} 算量）两条旧家户规则
   * —— 用户 2026-10-10 的 F-3/F-4 是"挂单全部 + 限价按 F-1 的价格口径给"，不再有折价、也不再有 储备扣减（旧设计直接删，见 AGENTS §一.11）。
   */
  static final long FX_MIN_LOT_BASE_MILLI = 1_000L;

  /**
   * ★ <b>实际汇率的采样窗口</b>（笔）：{@code 120} = 设计书 §9-4 建议的"一个产业周期的成交数"（本仓产业周期默认 120 天）。A 阶段逐轮瞬态 ⇒ 实际取
   * {@code min(120, 本轮成交数)}。
   */
  static final int FX_RATE_WINDOW_FILLS = 120;

  /** 外汇日志（fx 分类）；逐笔明细在 TRACE，窗口停做与具名拒在 INFO（§一.9）。 */
  private static final org.slf4j.Logger FX = EconomyLog.fx();

  private FxSettlement() {}

  /**
   * ★★ <b>跑一轮外汇撮合</b>（由 {@code MarketSettlement.clearOncePerCycle} 在商品撮合与信用之后调用）。
   *
   * <p>★★ <b>P-T5：本方法不再以"有没有官方汇率"为前提</b>（C-1）。旧口径（没有窗口 ⇒ 整段跳过）让"政府不愿开通道
   * 就民间自愿"等于零；现在只要<b>有市场</b>就跑：窗口侧照旧（{@code input.windows()} 空 ⇒ 没有窗口单），家户侧由 {@link
   * #planHouseholdOrders} 按 F-1 购买力自报价建<b>民间簿</b>。★ 单币世界在计划之前早退 ⇒ 仍逐值退回 A2a 之前。
   *
   * @return 本轮的外汇读数（没有市场 ⇒ {@link FxRoundResult#none()}；有市场但无事发生 ⇒ 空读数，与 {@code none()} 逐值等价）
   */
  static FxRoundResult match(
      MarketSettlement.MarketRound round, Map<HexCoord, Market> markets, MarketTopology topology) {
    FxRoundInput input = round.fx();
    if (input == null || markets.isEmpty()) {
      return FxRoundResult.none();
    }
    List<FxRoundResult.NamedRejection> rejections = new ArrayList<>();
    List<WindowState> windows = new ArrayList<>();
    Map<String, PairRates> pairs = new TreeMap<>();
    // ★★ §4.4（G3）：同一币对的<b>多份报价全部并存</b>——不再 putIfAbsent"先到先得"。
    //   两个用途各一张表：① pairs = 该币对的<b>最优摘要</b>（窗口簿建簿 + 多份报价日志）；② quotesByPair = 逐条留名。
    //   ★ P-T5：家户单已不读 pairs（它的限价来自 F-1 的价格口径）——摘要不再当家户的锚。
    Map<String, List<PairQuote>> quotesByPair = new TreeMap<>();
    for (FxRoundInput.Window spec : input.windows()) {
      OfficialRate rate = spec.rate();
      String key = pairKey(rate.base(), rate.quote());
      quotesByPair
          .computeIfAbsent(key, ignored -> new ArrayList<>())
          .add(new PairQuote(spec.governmentId(), rate));
      pairs.merge(
          key,
          new PairRates(rate.base(), rate.quote(), rate.buyPerMille(), rate.sellPerMille()),
          PairRates::best);
      HouseholdId treasury = round.householdOfActor().get(spec.treasury());
      if (treasury == null) {
        // 国库 actor 不是已登记家户 ⇒ 做不到"政府不许凭空持币"（账户主体只有家户）⇒ 窗口整段不参与，具名。
        rejectOnce(
            round.day(),
            rejections,
            new FxRoundResult.NamedRejection(
                FxRejectReason.WINDOW_INACTIVE,
                spec.treasury(),
                rate.base(),
                rate.quote(),
                0L,
                0L,
                "国库 actor 不是已登记家户（账户主体只有家户）⇒ 窗口不参与"),
            "FX_REJECTED",
            true);
        logWindowEvent(
            round.day(),
            "FX_WINDOW_INACTIVE",
            FxRejectReason.WINDOW_INACTIVE,
            spec.governmentId(),
            rate,
            0L,
            "treasury-not-a-household");
        continue;
      }
      long reserve = MarketSettlement.spendableMoneyOf(round, treasury, rate.base());
      long quoteSpendable = MarketSettlement.spendableMoneyOf(round, treasury, rate.quote());
      GovFxWindow.Quote quote =
          GovFxWindow.quote(
              spec.governmentId(), rate, reserve, spec.reserveCapBaseMilli(), quoteSpendable);
      windows.add(new WindowState(spec, treasury, quote, treasuryHex(round, treasury)));
      if (quote.buyBlocked() != null) {
        logWindowEvent(
            round.day(),
            "FX_WINDOW_BUY_BLOCKED",
            quote.buyBlocked(),
            spec.governmentId(),
            rate,
            reserve,
            "reserve="
                + reserve
                + " cap="
                + GovFxWindow.reserveCapLabel(spec.reserveCapBaseMilli())
                + " spendableQuote="
                + quoteSpendable);
        rejectOnce(
            round.day(),
            rejections,
            new FxRoundResult.NamedRejection(
                quote.buyBlocked(),
                spec.treasury(),
                rate.base(),
                rate.quote(),
                0L,
                0L,
                "政府买入侧停做（储备 "
                    + reserve
                    + " / 上限 "
                    + GovFxWindow.reserveCapLabel(spec.reserveCapBaseMilli())
                    + " / 可付 "
                    + quoteSpendable
                    + "）"),
            "FX_REJECTED",
            true);
      }
      if (quote.sellBlocked() != null) {
        logWindowEvent(
            round.day(),
            "FX_WINDOW_SELL_BLOCKED",
            quote.sellBlocked(),
            spec.governmentId(),
            rate,
            reserve,
            "reserve=" + reserve);
        rejectOnce(
            round.day(),
            rejections,
            new FxRoundResult.NamedRejection(
                quote.sellBlocked(),
                spec.treasury(),
                rate.base(),
                rate.quote(),
                0L,
                0L,
                "政府卖出侧停做（储备 " + reserve + "，不许卖空）"),
            "FX_REJECTED",
            true);
      }
    }

    // ── ①.5 报价冲突（★ §4.4 / §一.9）：同一币对多份【不同】报价 ⇒ INFO 具名（谁/各什么价/谁最优）；
    //         多份【相同】报价 ⇒ 不刷日志（否则噪声）。永不静默（这是 F9"静默丢弃"的正面替代）。
    logMultipleQuotes(round.day(), quotesByPair, pairs);

    // ── ② 建簿：窗口簿（每个"有官方汇率的币对"一本；窗口停做不影响市场的存在）────────────────────
    Map<String, List<Order>> books = new LinkedHashMap<>();
    for (Map.Entry<String, PairRates> entry : pairs.entrySet()) {
      books.put(entry.getKey(), new ArrayList<>());
    }
    for (WindowState window : windows) {
      String key = pairKey(window.quote.base(), window.quote.quote());
      List<Order> book = books.computeIfAbsent(key, ignored -> new ArrayList<>());
      if (window.quote.canBuy()) {
        book.add(
            Order.window(
                window,
                true,
                window.quote.bidPerMille(),
                window.quote.buyCapacityBaseMilli(),
                window.hex));
      }
      if (window.quote.canSell()) {
        book.add(
            Order.window(
                window,
                false,
                window.quote.askPerMille(),
                window.quote.sellCapacityBaseMilli(),
                window.hex));
      }
    }
    // ── ②b ★★ P-T5：民间簿（家户自报价；不依赖窗口报价，窗口只是其中一方）─────────────────────
    HouseholdPlan plan = planHouseholdOrders(round, markets, topology, books);

    // ── ③ 逐币对撮合（价格优先 + canonical 时间序；吃深度）─────────────────────────────
    List<FxFill> fills = new ArrayList<>();
    for (Map.Entry<String, List<Order>> entry : books.entrySet()) {
      matchBook(round, entry.getValue(), fills, rejections);
    }

    // ── ④ 未成交的具名归因（不许静默留残量）──────────────────────────────────────────
    attributeResidue(round.day(), books, rejections);
    return finish(round, windows, plan, fills, rejections);
  }

  // ── ★★ P-T5：民间簿的家户单（口径 = F-1/F-2/F-3/F-4，唯一拼写点见 HouseholdPurchasingPower）────

  /**
   * ★ <b>本轮家户侧的量（只进日志，不进任何状态）</b>。
   *
   * @param households 挂出至少一条单的家户数（INFO 的"参与户数"）
   * @param orders 家户单总数
   * @param pairs 家户单覆盖的币对数（= 被家户单触及的簿数）
   */
  private record HouseholdPlan(int households, int orders, int pairs) {

    static HouseholdPlan empty() {
      return new HouseholdPlan(0, 0, 0);
    }
  }

  /**
   * ★★ <b>民间簿的挂单计划（P-T5 的核心）</b>：逐格逐户，看手里有哪几种币 → 按 {@link HouseholdPurchasingPower} 算
   * "付清自己的生活消费品需求要付多少" → <b>最弱先换</b>（同强度按币种 id 升序）→ 挂单把弱币换成<b>最强</b>的那种。
   *
   * <pre>
   * ① 早退：世界只有一种币（市场计价币 ∪ 家户持币）⇒ 一个数都不动（I-C2 / 单币世界）
   * ② 逐户：持币（可花额 &gt; 0）不足两种 ⇒ 无可比；naturalNeeds 为空 ⇒ 没有"付清需求"这件事 ⇒ 都不挂
   * ③ 逐币：价表 = 该币法定区锚格的市场（自己的区优先）；篮子缺价 ⇒ 该币算不出购买力 ⇒ 该币不参与（不猜）
   * ④ 可比币 &lt; 2 ⇒ 不挂（DEBUG 具名"为什么没换"）
   * ⑤ 最强 = 强度全序首项（cost 升序、币种 id 升序）；其余逐个换成它，顺序 = cost 降序、币种 id 升序
   * ⑥ 量 = 该弱币<b>全部可花额</b>（F-3：不扣生活/生产储备）；限价 = F-4（同一份价格口径）
   * ⑦ 定向：有窗口簿的币对并入窗口的定向（能直接与政府撮合）；没有则新建民间簿，定向 = 币种 id 升序
   * </pre>
   *
   * <p>★ <b>F-2 的"最弱先换"落在哪</b>：换汇顺序 = {@code weaker} 的排序（cost 降序、币种 id 升序）。★ 撮合本身仍按 "价格优先、同价按
   * canonical 序"（{@code Order#ownerRank}）——两者不冲突：前者是"这一户先挂哪种币的单"，后者是 "簿上谁先成交"（用户 2026-10-10
   * 确认：走市场自然议价，不设特权队列）。
   *
   * <p>★ <b>为什么"并入窗口定向"</b>：簿是逐定向一本（{@code base|quote}），买/卖两侧都在同一本里交叉。家户要卖弱币买强币 —— 若窗口簿正是 {@code
   * 弱|强}，它就是一张卖单（与窗口的买盘撮合）；若窗口簿是 {@code 强|弱}，它就是一张买单（与窗口的卖盘撮合）。 两种都落在<b>同一本簿</b>里，不需要（也不许）另设特权队列。
   *
   * <p>★ <b>为什么没有窗口也要能挂</b>：C-1 —— 民间簿的报价只从"价格表 + 自己的生活消费品需求"来，与官方报价无关；窗口只是这个 市场上的一个卖方/买方。
   *
   * <p>★ <b>确定性（I7 / I-C5）</b>：遍历序 = hex (q,r) 升序 → 行序 → 币种 id 升序；排序键全是内容的纯函数；新建簿的定向由 币种 id
   * 决定。同一份世界状态两跑 ⇒ 同一批单、同一个次序。
   */
  private static HouseholdPlan planHouseholdOrders(
      MarketSettlement.MarketRound round,
      Map<HexCoord, Market> markets,
      MarketTopology topology,
      Map<String, List<Order>> books) {
    if (!hasMultipleCurrencies(round, markets)) {
      // ★ 单币世界：连价表都不建（I-C2 的落点 —— 旧世界逐值不变，且不为此多花一分算力）。
      return HouseholdPlan.empty();
    }
    Map<CurrencyId, HouseholdPurchasingPower.ZoneTable> zoneTables =
        HouseholdPurchasingPower.zoneTables(markets, topology);
    Map<String, List<HouseholdId>> rowsByHex =
        EconomySettlement.rowsByHex(round.householdEconomies());
    List<HexCoord> hexes = new ArrayList<>(markets.keySet());
    hexes.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
    Set<HouseholdId> participants = new LinkedHashSet<>();
    Set<String> touchedPairs = new LinkedHashSet<>();
    int orders = 0;
    int powerUnavailable = 0;
    int belowMinLot = 0;
    int unquotable = 0;
    for (HexCoord hex : hexes) {
      List<HouseholdId> rows =
          rowsByHex.getOrDefault(IndustryHexKeys.hexKey(hex.q(), hex.r()), List.of());
      MarketRegion ownRegion = topology.contains(hex) ? topology.regionOf(hex) : null;
      String regionId = ownRegion == null ? "" : ownRegion.node().nodeId();
      for (HouseholdId household : rows) {
        if (household == null
            || round.marketExcludedHouseholds().contains(household)
            // ★★ R1：国库户也不生成**家户外汇单** —— 改前它在 Z7b 排除集里（同一个集合兼着这两件事），
            //   本轮把商品市场参与与自动下单拆开后，这里必须显式保住原来那一半：国库户的外汇行为
            //   只走 GOV 外汇窗口（FxRoundInput 的 window），不额外挂家户单。
            || round.govMandates().isAuthorizationOnly(household)) {
          continue;
        }
        HouseholdEconomy row = round.householdEconomies().get(household);
        if (row == null || row.naturalNeeds().isEmpty()) {
          continue; // 没有"生活消费品需求"⇒ 没有"付清需求要付多少"⇒ 不挂（缺省中性的一半）
        }
        List<CurrencyId> held = heldCurrencies(round, household);
        if (held.size() < 2) {
          continue; // 手里只有一种币 ⇒ 没有"弱换强"可言
        }
        // ① F-1：逐币算"付清自己的生活消费品需求要付多少"（缺价 ⇒ 该币算不出 ⇒ 不参与比较、也不换）。
        Map<CurrencyId, Long> power = new LinkedHashMap<>();
        List<CurrencyId> unquotableCurrencies = new ArrayList<>();
        for (CurrencyId currency : held) {
          HouseholdPurchasingPower.ZoneTable table =
              HouseholdPurchasingPower.tableFor(currency, ownRegion, zoneTables, markets);
          OptionalLong cost =
              table == null
                  ? OptionalLong.empty()
                  : HouseholdPurchasingPower.needCostMilli(row, table);
          if (cost.isPresent()) {
            power.put(currency, cost.getAsLong());
          } else {
            unquotableCurrencies.add(currency);
          }
        }
        if (power.size() < 2) {
          powerUnavailable++;
          logHouseholdPower(
              round.day(),
              hex,
              household,
              power,
              unquotableCurrencies,
              null,
              0,
              "no-comparable-purchasing-power（可比币不足两种：缺价 / 篮子为空）");
          continue;
        }
        // ② 最强 = 强度全序首项（cost 升序、币种 id 升序）—— 同强度时 id 小的更强（确定性 I7）。
        Comparator<CurrencyId> strength = HouseholdPurchasingPower.strengthOrder(power);
        CurrencyId target = null;
        for (CurrencyId currency : power.keySet()) {
          if (target == null || strength.compare(currency, target) < 0) {
            target = currency;
          }
        }
        // ③ 其余币（都不是最强的）→ 逐个换成最强币；顺序 = 最弱先换、同强度按币种 id 升序（F-2）。
        List<CurrencyId> weaker = new ArrayList<>(power.keySet());
        weaker.remove(target);
        weaker.sort(HouseholdPurchasingPower.weakestFirstOrder(power));
        List<CurrencyId> planned = new ArrayList<>();
        for (CurrencyId weak : weaker) {
          String touched =
              placeHouseholdOrder(round, household, hex, regionId, weak, target, power, books);
          if (touched == null) {
            if (MarketSettlement.spendableMoneyOf(round, household, weak) < FX_MIN_LOT_BASE_MILLI) {
              belowMinLot++;
            } else {
              unquotable++;
            }
            continue;
          }
          planned.add(weak);
          touchedPairs.add(touched);
          orders++;
        }
        String reason =
            planned.isEmpty() ? "no-order-placed（低于最小手 / 给不出限价）" : "converted-to-strongest";
        if (!planned.isEmpty()) {
          participants.add(household);
        }
        logHouseholdPower(
            round.day(),
            hex,
            household,
            power,
            unquotableCurrencies,
            target,
            planned.size(),
            reason);
      }
    }
    if (orders > 0 || powerUnavailable > 0) {
      EventLog.channel(FX)
          .debug(
              LogEvent.of(
                  "FX_ORDER_PLAN",
                  EconomyLogSource.ECONOMY_FX,
                  "day",
                  round.day(),
                  "households",
                  participants.size(),
                  "orders",
                  orders,
                  "pairs",
                  touchedPairs.size(),
                  "noComparablePower",
                  powerUnavailable,
                  "belowMinLot",
                  belowMinLot,
                  "unquotable",
                  unquotable,
                  "books",
                  books.size()));
    }
    for (List<Order> book : books.values()) {
      book.sort(Order.HOUSEHOLD_ORDER);
    }
    return new HouseholdPlan(participants.size(), orders, touchedPairs.size());
  }

  /**
   * ★ <b>本户"手里有哪几种币"（F-1 的比较集）</b>：可花额 &gt; 0 的币种，按币种 id 升序（canonical 序，I7）。
   *
   * <p>★ 口径写清楚：冻结中的钱在本段<b>不</b>算（FX 在信用之后、商品冻结已释放，正常路径下二者相等；但读"可花额"与全仓 商品面的口径逐字同源，不另造第二种"我有多钱"）。
   */
  private static List<CurrencyId> heldCurrencies(
      MarketSettlement.MarketRound round, HouseholdId household) {
    List<CurrencyId> held = new ArrayList<>();
    for (CurrencyId currency : round.moneyOf(household).keySet()) {
      if (currency != null && MarketSettlement.spendableMoneyOf(round, household, currency) > 0L) {
        held.add(currency);
      }
    }
    held.sort(Comparator.comparing(CurrencyId::value));
    return held;
  }

  /**
   * ★★ <b>给"把弱币换成最强币"挂一条单</b>（返回落进哪本簿；{@code null} = 没挂）。
   *
   * <pre>
   * 定向（base, quote）= 窗口簿已有的那个定向（弱|强 ⇒ 家户卖 base；强|弱 ⇒ 家户买 base）
   *                    两者都没有 ⇒ 新建民间簿，定向 = 币种 id 升序（base = 小 id）
   * 限价  = F-4：⌊1000 × cost(quote) ÷ cost(base)⌋（同一份 F-1 价格口径）
   * 量    = 卖 base ⇒ 该弱币的全部可花额（F-3）
   *         买 base ⇒ ⌊该弱币可花额 × 1000 ÷ 限价⌋（"这点钱按我的限价能买多少 base"，能换多少换多少）
   * </pre>
   *
   * <p>★ <b>fail-closed 的两处</b>：限价说不出（0）⇒ 不挂；量低于 {@link #FX_MIN_LOT_BASE_MILLI} ⇒ 不挂（挂上去也撮不动，
   * 只会给日志与读数刷灰）。两处都由调用方计入 DEBUG 的具名原因。
   */
  private static String placeHouseholdOrder(
      MarketSettlement.MarketRound round,
      HouseholdId household,
      HexCoord hex,
      String regionId,
      CurrencyId weak,
      CurrencyId target,
      Map<CurrencyId, Long> power,
      Map<String, List<Order>> books) {
    long spendable = MarketSettlement.spendableMoneyOf(round, household, weak);
    if (spendable < FX_MIN_LOT_BASE_MILLI) {
      return null;
    }
    String directKey = pairKey(weak, target);
    String reverseKey = pairKey(target, weak);
    CurrencyId base;
    CurrencyId quote;
    if (books.containsKey(directKey)) {
      base = weak;
      quote = target;
    } else if (books.containsKey(reverseKey)) {
      base = target;
      quote = weak;
    } else if (weak.value().compareTo(target.value()) <= 0) {
      base = weak;
      quote = target;
    } else {
      base = target;
      quote = weak;
    }
    long limit = 0L;
    Long costBase = power.get(base);
    Long costQuote = power.get(quote);
    if (costBase != null && costQuote != null) {
      limit = HouseholdPurchasingPower.limitPerMille(costBase, costQuote);
    }
    if (limit <= 0L) {
      return null; // F-4 说不出价 ⇒ 不挂（绝不猜一个价、绝不用家户估值表顶上）
    }
    boolean sellBase = base.equals(weak);
    long quantity =
        sellBase
            ? spendable
            : GovFxWindow.mulDivFloor(spendable, 1000L, limit); // 全部弱币按自己的限价能买到的 base 量
    if (quantity < FX_MIN_LOT_BASE_MILLI) {
      return null;
    }
    String key = pairKey(base, quote);
    List<Order> book = books.computeIfAbsent(key, ignored -> new ArrayList<>());
    book.add(Order.household(household, hex, regionId, !sellBase, base, quote, limit, quantity));
    EventLog.channel(FX)
        .trace(
            LogEvent.of(
                "FX_HOUSEHOLD_ORDER",
                EconomyLogSource.ECONOMY_FX,
                "day",
                round.day(),
                "household",
                household.value(),
                "hex",
                IndustryHexKeys.hexKey(hex.q(), hex.r()),
                "side",
                sellBase ? "SELL" : "BUY",
                "base",
                base.value(),
                "quote",
                quote.value(),
                "limitPerMille",
                limit,
                "baseMilli",
                quantity,
                "target",
                target.value(),
                "powerBaseMilli",
                power.getOrDefault(base, 0L),
                "powerQuoteMilli",
                power.getOrDefault(quote, 0L)));
    return key;
  }

  /**
   * ★★ <b>§一.9 的 DEBUG：这一户为什么换/为什么不换</b>（F-1 的读数：逐币"付清需求要付多少"、算不出的币、选中的最强币、挂了几条）。
   *
   * <p>★ 只在"手里至少两种币、且有生活需求"的家户上发（那些正是本批做出判定的现场）；缺价按<b>币种具名</b>列出，绝不静默。
   */
  private static void logHouseholdPower(
      long day,
      HexCoord hex,
      HouseholdId household,
      Map<CurrencyId, Long> power,
      List<CurrencyId> unquotable,
      CurrencyId target,
      int plannedOrders,
      String reason) {
    EventLog.channel(FX)
        .debug(
            LogEvent.of(
                "FX_HOUSEHOLD_POWER",
                EconomyLogSource.ECONOMY_FX,
                "day",
                day,
                "household",
                household.value(),
                "hex",
                IndustryHexKeys.hexKey(hex.q(), hex.r()),
                "powerMilli",
                powerText(power),
                "unquotable",
                currencyNames(unquotable),
                "target",
                target == null ? "" : target.value(),
                "orders",
                plannedOrders,
                "reason",
                reason));
  }

  /** 逐币购买力读数的具名文本（币种 id 升序；{@code silver=12345,gold=6789}）。 */
  private static String powerText(Map<CurrencyId, Long> power) {
    List<String> parts = new ArrayList<>();
    for (CurrencyId currency : power.keySet()) {
      parts.add(currency.value() + "=" + power.get(currency));
    }
    parts.sort(Comparator.naturalOrder());
    return String.join(",", parts);
  }

  /** 币种名表（升序；读数字段用）。 */
  private static String currencyNames(List<CurrencyId> currencies) {
    List<String> names = new ArrayList<>();
    for (CurrencyId currency : currencies) {
      if (currency != null) {
        names.add(currency.value());
      }
    }
    names.sort(Comparator.naturalOrder());
    return String.join(",", names);
  }

  /**
   * ★ <b>世界这一轮是不是"不止一种币"</b>（计划前的廉价早退门）：市场计价币 ∪ 逐户持币里出现第二种就为真。
   *
   * <p>★ 单币世界 ⇒ 本段<b>一个数都不动</b>（连价表都不建）：这是 I-C2"缺省中性"与"不为此多花算力"的同一条落地。
   */
  private static boolean hasMultipleCurrencies(
      MarketSettlement.MarketRound round, Map<HexCoord, Market> markets) {
    CurrencyId first = null;
    for (Market market : markets.values()) {
      if (first == null) {
        first = market.numeraire();
      } else if (!first.equals(market.numeraire())) {
        return true;
      }
    }
    for (HouseholdId household : round.householdEconomies().keySet()) {
      for (CurrencyId currency : round.moneyOf(household).keySet()) {
        if (currency == null) {
          continue;
        }
        if (first == null) {
          first = currency;
        } else if (!first.equals(currency)) {
          return true;
        }
      }
    }
    return false;
  }

  // ── 撮合一本（一个币对）────────────────────────────────────────────────────────

  private static void matchBook(
      MarketSettlement.MarketRound round,
      List<Order> book,
      List<FxFill> fills,
      List<FxRoundResult.NamedRejection> rejections) {
    List<Order> bids = new ArrayList<>();
    List<Order> asks = new ArrayList<>();
    for (Order order : book) {
      if (order.buy) {
        bids.add(order);
      } else {
        asks.add(order);
      }
    }
    bids.sort(
        Comparator.comparingLong((Order o) -> -o.limitPerMille).thenComparing(Order::ownerRank));
    asks.sort(
        Comparator.comparingLong((Order o) -> o.limitPerMille).thenComparing(Order::ownerRank));
    int i = 0;
    int j = 0;
    while (i < bids.size() && j < asks.size()) {
      Order bid = bids.get(i);
      Order ask = asks.get(j);
      if (bid.remaining < FX_MIN_LOT_BASE_MILLI) {
        i++;
        continue;
      }
      if (ask.remaining < FX_MIN_LOT_BASE_MILLI) {
        j++;
        continue;
      }
      if (bid.limitPerMille < ask.limitPerMille) {
        break; // 最好的买价都不再交叉 ⇒ 后面的更不会
      }
      if (bid.owner.equals(ask.owner)) {
        // 契约/一致性故障（生产路径上被相反报价判据与家户单互斥挡住 ⇒ 不可达）：fail-closed 作废两条并记
        // ERROR，绝不"自己跟自己成交"（§一.9：契约故障不降级）。
        bid.remaining = 0L;
        ask.remaining = 0L;
        EventLog.channel(FX)
            .error(
                LogEvent.of(
                    "FX_SELF_TRADE_BLOCKED",
                    EconomyLogSource.ECONOMY_FX,
                    "day",
                    round.day(),
                    "owner",
                    bid.owner,
                    "base",
                    bid.base.value(),
                    "quote",
                    bid.quote.value()));
        i++;
        j++;
        continue;
      }
      long price;
      try {
        price = FxPricing.fillPricePerMille(bid.limitPerMille, ask.limitPerMille);
      } catch (IllegalArgumentException e) {
        break;
      }
      long buyerMoney = MarketSettlement.spendableMoneyOf(round, bid.household, bid.quote);
      long sellerBase = MarketSettlement.spendableMoneyOf(round, ask.household, ask.base);
      long maxByMoney = GovFxWindow.mulDivFloor(buyerMoney, 1000L, price);
      long quantity =
          Math.min(Math.min(bid.remaining, ask.remaining), Math.min(maxByMoney, sellerBase));
      if (quantity < FX_MIN_LOT_BASE_MILLI) {
        // 谁把它卡住的，就把谁移出簿（具名一次），绝不留一个"看起来还能成交"的槽位。
        Order blocked = maxByMoney <= sellerBase ? bid : ask;
        rejectOnce(
            round.day(),
            rejections,
            new FxRoundResult.NamedRejection(
                FxRejectReason.INSUFFICIENT_FUNDS,
                blocked.owner,
                blocked.base,
                blocked.quote,
                blocked.remaining,
                0L,
                blocked.buy
                    ? "买方本地货币可花额不足（" + buyerMoney + " 毫 " + blocked.quote.value() + "）"
                    : "卖方外币可花额不足（" + sellerBase + " 毫 " + blocked.base.value() + "）"),
            blocked.window ? "FX_REJECTED" : "FX_HOUSEHOLD_UNFILLED",
            blocked.window);
        blocked.remaining = 0L;
        if (blocked == bid) {
          i++;
        } else {
          j++;
        }
        continue;
      }
      long quoteLeg = GovFxWindow.quotePerMille(quantity, price);
      if (quoteLeg > buyerMoney) {
        quantity -= 1L; // ceil 的边界：绝不越过买方实际可花额
        if (quantity < FX_MIN_LOT_BASE_MILLI) {
          bid.remaining = 0L;
          i++;
          continue;
        }
        quoteLeg = GovFxWindow.quotePerMille(quantity, price);
      }
      boolean windowInvolved = bid.window || ask.window;
      TransferReason legReason =
          windowInvolved ? TransferReason.GOV_FX_WINDOW : TransferReason.FX_TRADE;
      // ★ 有家户参与时逐字保持旧口径（家户那一侧的格优先）⇒ 既有世界的 location 一个数都不动；
      //   ★ 同币对多窗口（G3）下**两条窗口单之间**也可能交叉（GOV-A 的买价 ≥ GOV-B 的卖价）——
      //     此时两侧没有家户格 ⇒ 取卖方（窗口）的格 = 其国库户所在格（见 Order.window 的 hex）。
      //     （改前这里恒取 `ask.hex != null ? ask.hex : bid.hex` ⇒ 两侧都是窗口时 location = null，
      //      applyTransfer 当场抛「Transfer.location 不得为 null」，整轮日结算被炸掉。）
      HexCoord location = ask.window ? bid.hex : ask.hex;
      // base 腿：卖方 → 买方；quote 腿：买方 → 卖方。两条腿都走唯一写口（铁律 2）。
      applyLeg(
          round,
          round
              .ledger()
              .mint(
                  ask.owner, bid.owner, location, Map.of(), Map.of(ask.base, quantity), legReason));
      applyLeg(
          round,
          round
              .ledger()
              .mint(
                  bid.owner,
                  ask.owner,
                  location,
                  Map.of(),
                  Map.of(ask.quote, quoteLeg),
                  legReason));
      bid.remaining -= quantity;
      ask.remaining -= quantity;
      FxFill fill =
          new FxFill(
              round.day(),
              location,
              ask.regionId,
              ask.base,
              ask.quote,
              quantity,
              quoteLeg,
              price,
              bid.owner,
              ask.owner,
              windowInvolved ? FxVenue.GOV_WINDOW : FxVenue.HOUSEHOLD);
      fills.add(fill);
      if (bid.window) {
        bid.windowState.buyFilledBase += quantity;
        bid.windowState.buyQuotePaid += quoteLeg;
      }
      if (ask.window) {
        ask.windowState.sellFilledBase += quantity;
        ask.windowState.sellQuoteReceived += quoteLeg;
      }
      EventLog.channel(FX)
          .trace(
              LogEvent.of(
                  "FX_FILL",
                  EconomyLogSource.ECONOMY_FX,
                  "day",
                  round.day(),
                  "base",
                  fill.base().value(),
                  "quote",
                  fill.quote().value(),
                  "baseMilli",
                  quantity,
                  "quoteMilli",
                  quoteLeg,
                  "pricePerMille",
                  price,
                  "venue",
                  fill.venue().wire(),
                  "buyer",
                  fill.buyer(),
                  "seller",
                  fill.seller()));
    }
  }

  // ── 未成交归因 ──────────────────────────────────────────────────────────────────

  private static void attributeResidue(
      long day, Map<String, List<Order>> books, List<FxRoundResult.NamedRejection> rejections) {
    for (List<Order> book : books.values()) {
      long bids = 0L;
      long asks = 0L;
      for (Order candidate : book) {
        if (candidate.buy) {
          bids++;
        } else {
          asks++;
        }
      }
      for (Order order : book) {
        if (order.remaining <= 0L) {
          continue;
        }
        if (order.window) {
          if (order.buy) {
            order.windowState.unfilledBuyBase += order.remaining;
            // 政府买入没有对手方（M3）：窗口摆了买盘，但这一轮没有可成交的家户卖单。
            rejectOnce(
                day,
                rejections,
                new FxRoundResult.NamedRejection(
                    FxRejectReason.NO_COUNTERPARTY,
                    order.owner,
                    order.base,
                    order.quote,
                    order.originalQuantity,
                    order.originalQuantity - order.remaining,
                    "政府买入无对手方（本轮没有可成交的家户卖单 / 家户卖盘已吃尽）"),
                "FX_REJECTED",
                true);
          } else {
            order.windowState.unfilledSellBase += order.remaining;
          }
          continue;
        }
        // 家户残量：说清"还剩多少、为什么"（结算序的最后一层归因；不准静默）。
        //   ★ 两个具名档的区别是**事实层面**的：对面一侧压根没有挂单 ⇒ NO_COUNTERPARTY（"没人接"）；
        //     对面有挂单但限价不交叉 ⇒ NO_CROSS（"价不拢"）；部分成交后把对面吃尽 ⇒ NO_COUNTERPARTY。
        //   ★★ P-T5 的日志级别：家户残量是**逐笔**读数（本批 §一.9 口径：INFO = 本轮汇总、DEBUG = 判据、
        //     TRACE = 逐笔）⇒ 走 TRACE；它仍逐条进 rejections（读口与"复核未成交"不受影响），绝不静默。
        //     ★ 窗口单的拒保持原有 INFO（那是"政府窗口停做/无对手方"这类政策面事实，级别不降）。
        boolean oppositePresent = order.buy ? asks > 0L : bids > 0L;
        FxRejectReason residueReason =
            order.originalQuantity != order.remaining || !oppositePresent
                ? FxRejectReason.NO_COUNTERPARTY
                : FxRejectReason.NO_CROSS;
        rejectOnce(
            day,
            rejections,
            new FxRoundResult.NamedRejection(
                residueReason,
                order.owner,
                order.base,
                order.quote,
                order.originalQuantity,
                order.originalQuantity - order.remaining,
                "本轮对手盘不足（剩余 " + order.remaining + " 毫 " + order.base.value() + "）"),
            "FX_HOUSEHOLD_UNFILLED",
            false);
      }
    }
  }

  // ── 组装读数 ────────────────────────────────────────────────────────────────────

  private static FxRoundResult finish(
      MarketSettlement.MarketRound round,
      List<WindowState> windows,
      HouseholdPlan plan,
      List<FxFill> fills,
      List<FxRoundResult.NamedRejection> rejections) {
    List<FxRoundResult.WindowOutcome> outcomes = new ArrayList<>();
    for (WindowState window : windows) {
      outcomes.add(
          new FxRoundResult.WindowOutcome(
              window.spec.governmentId(),
              window.quote.base(),
              window.quote.quote(),
              window.quote.bidPerMille(),
              window.quote.askPerMille(),
              window.quote.reserveBaseMilli(),
              window.quote.reserveCapBaseMilli(),
              window.quote.buyCapacityBaseMilli(),
              window.quote.sellCapacityBaseMilli(),
              window.buyFilledBase,
              window.sellFilledBase,
              window.buyQuotePaid,
              window.sellQuoteReceived,
              window.unfilledBuyBase,
              window.unfilledSellBase,
              window.quote.buyBlocked(),
              window.quote.sellBlocked()));
    }
    FxRoundResult result = new FxRoundResult(fills, outcomes, rejections);
    // ★★ §一.9（本批口径）：INFO = <b>本轮换汇汇总</b>——参与户数 / 币对数 / 成交量（含家户侧），另有窗口数与逐笔拒数。
    //   ★ 触发条件加上 plan.orders() > 0：本轮"挂了单但一笔没成"也是换汇面发生过的事，不许静默。
    if (!fills.isEmpty() || !rejections.isEmpty() || plan.orders() > 0) {
      EventLog.channel(FX)
          .info(
              LogEvent.of(
                  "FX_ROUND",
                  EconomyLogSource.ECONOMY_FX,
                  "day",
                  round.day(),
                  "windows",
                  windows.size(),
                  "households",
                  plan.households(),
                  "orders",
                  plan.orders(),
                  "pairs",
                  plan.pairs(),
                  "fills",
                  fills.size(),
                  "baseVolumeMilli",
                  baseVolume(fills),
                  "windowFills",
                  windowFillCount(fills),
                  "householdFills",
                  householdFillCount(fills),
                  "householdBaseVolumeMilli",
                  householdBaseVolume(fills),
                  "rejections",
                  rejections.size()));
    }
    return result;
  }

  private static long baseVolume(List<FxFill> fills) {
    long volume = 0L;
    for (FxFill fill : fills) {
      volume = Math.addExact(volume, fill.baseMilli());
    }
    return volume;
  }

  private static long windowFillCount(List<FxFill> fills) {
    long count = 0L;
    for (FxFill fill : fills) {
      if (fill.windowInvolved()) {
        count++;
      }
    }
    return count;
  }

  /** ★ P-T5：本轮<b>不涉及窗口</b>（民间簿内部成交）的笔数。 */
  private static long householdFillCount(List<FxFill> fills) {
    long count = 0L;
    for (FxFill fill : fills) {
      if (!fill.windowInvolved()) {
        count++;
      }
    }
    return count;
  }

  /** ★ P-T5：本轮<b>不涉及窗口</b>的成交量（base 最小单位；与 baseVolume 同一量纲、同一求和口径）。 */
  private static long householdBaseVolume(List<FxFill> fills) {
    long volume = 0L;
    for (FxFill fill : fills) {
      if (!fill.windowInvolved()) {
        volume = Math.addExact(volume, fill.baseMilli());
      }
    }
    return volume;
  }

  /** 两腿都经唯一写口 {@code EconomySettlement.applyTransfer}（铁律 2）。 */
  private static void applyLeg(MarketSettlement.MarketRound round, Transfer transfer) {
    EconomySettlement.applyTransfer(
        round.householdGoods(),
        round.householdMoney(),
        round.householdFrozenGoods(),
        round.householdFrozenMoney(),
        round.householdOfActor(),
        transfer);
  }

  private static String pairKey(CurrencyId base, CurrencyId quote) {
    return OfficialRate.keyOf(base, quote);
  }

  private static void logWindowEvent(
      long day,
      String event,
      FxRejectReason reason,
      GovernmentId governmentId,
      OfficialRate rate,
      long reserveBaseMilli,
      String detail) {
    EventLog.channel(FX)
        .info(
            LogEvent.of(
                event,
                EconomyLogSource.ECONOMY_FX,
                "day",
                day,
                "reason",
                reason.wire(),
                "government",
                governmentId.value(),
                "base",
                rate.base().value(),
                "quote",
                rate.quote().value(),
                "reserveBaseMilli",
                reserveBaseMilli,
                "detail",
                detail));
  }

  /**
   * 逐笔具名拒去重：同一 (reason, actor, pair) 只留第一条（一轮里同一原因刷屏没有信息量）。
   *
   * <p>★ <b>级别由调用方给</b>（P-T5）：窗口面的拒（政策价停做 / 政府无对手方 / 政府付不出）保持 <b>INFO</b>；家户面的 逐笔残量与资金不足走
   * <b>TRACE</b> —— 本批 §一.9 口径 = INFO 汇总 / DEBUG 判据 / TRACE 逐笔。两种都逐条进 {@code rejections}（读口不丢事实）。
   */
  private static void rejectOnce(
      long day,
      List<FxRoundResult.NamedRejection> rejections,
      FxRoundResult.NamedRejection candidate,
      String event,
      boolean info) {
    for (FxRoundResult.NamedRejection existing : rejections) {
      if (existing.reason() == candidate.reason()
          && existing.actor().equals(candidate.actor())
          && existing.base().equals(candidate.base())
          && existing.quote().equals(candidate.quote())) {
        return;
      }
    }
    rejections.add(candidate);
    LogEvent logEvent =
        LogEvent.of(
            event,
            EconomyLogSource.ECONOMY_FX,
            "day",
            day,
            "reason",
            candidate.reason().wire(),
            "actor",
            candidate.actor(),
            "base",
            candidate.base().value(),
            "quote",
            candidate.quote().value(),
            "requestedBaseMilli",
            candidate.requestedBaseMilli(),
            "filledBaseMilli",
            candidate.filledBaseMilli(),
            "detail",
            candidate.detail());
    if (info) {
      EventLog.channel(FX).info(logEvent);
    } else {
      EventLog.channel(FX).trace(logEvent);
    }
  }

  /**
   * 一个币对的官方报价<b>摘要</b>（窗口簿建簿 + 多份报价日志用）。
   *
   * <p>★★ <b>P-T5 起它不再是家户挂单价的锚</b>（家户限价只从 F-1/F-4 的购买力口径来）；家户"货比三家"的旧口径 （买单限价 = 最便宜卖价 / 卖单底价 =
   * 最高买价再折价）随旧规则一起退役，见 {@link #FX_MIN_LOT_BASE_MILLI} 的注。
   */
  private record PairRates(CurrencyId base, CurrencyId quote, long bidPerMille, long askPerMille) {

    /**
     * ★★ §4.4：同一币对多份报价的<b>合并口径</b>（唯一拼写点）：{@code bid} 取<b>最高</b>买价、{@code ask} 取<b>最低</b>卖价 ——
     * "对手方最划算的那份"（窗口簿的读数字段用）。
     */
    PairRates best(PairRates other) {
      return new PairRates(
          base,
          quote,
          Math.max(bidPerMille, other.bidPerMille),
          Math.min(askPerMille, other.askPerMille));
    }
  }

  /** 同币对的一份报价 + 它的具名属主（冲突日志用；逐轮瞬态，不进任何状态）。 */
  private record PairQuote(GovernmentId governmentId, OfficialRate rate) {}

  /**
   * ★★ <b>§4.4 / §一.9：同一币对多份报价的具名记录</b>。
   *
   * <pre>
   * 多份【不同】报价 ⇒ 一条 INFO FX_PAIR_MULTIPLE_QUOTES：pair / 各是谁什么价（规范序）/ 谁最优 / 摘要取值
   * 多份【相同】报价 ⇒ 一条都不发（否则多政府同价这种常态会把日志刷成噪声）
   * 单份报价        ⇒ 一条都不发（没有"冲突"可言）
   * </pre>
   *
   * <p>★ <b>为什么"谁最优"必须具名</b>：这正是 F9"后到者被静默丢弃"的正面替代 —— 一个政府把价挂低了却没起作用时， 只看日志就能回答"是不是被谁的更优价压过了"。
   *
   * <p>★ <b>报的是谁</b>：报价的属主 GOV（窗口的身份）。区级覆盖在本仓是"投到该区法定币发行者窗口上"的一条生效报价 （{@code
   * MarketZoneBook.effectiveRatesOf}），{@code FxRoundInput.Window} 不携带区身份 ⇒ 这里只点 GOV 的名。
   */
  private static void logMultipleQuotes(
      long day, Map<String, List<PairQuote>> quotesByPair, Map<String, PairRates> pairs) {
    for (Map.Entry<String, List<PairQuote>> entry : quotesByPair.entrySet()) {
      List<PairQuote> quotes = entry.getValue();
      if (quotes.size() < 2) {
        continue;
      }
      Set<String> distinct = new LinkedHashSet<>();
      for (PairQuote quote : quotes) {
        distinct.add(quote.rate().buyPerMille() + "/" + quote.rate().sellPerMille());
      }
      if (distinct.size() < 2) {
        continue; // 多份相同报价 ⇒ 不刷日志（§4.4）
      }
      PairRates summary = pairs.get(entry.getKey());
      List<String> named = new ArrayList<>();
      PairQuote bestBid = quotes.get(0);
      PairQuote bestAsk = quotes.get(0);
      for (PairQuote quote : quotes) {
        named.add(
            quote.governmentId().value()
                + ":"
                + quote.rate().buyPerMille()
                + "/"
                + quote.rate().sellPerMille());
        if (quote.rate().buyPerMille() > bestBid.rate().buyPerMille()) {
          bestBid = quote;
        }
        if (quote.rate().sellPerMille() < bestAsk.rate().sellPerMille()) {
          bestAsk = quote;
        }
      }
      EventLog.channel(FX)
          .info(
              LogEvent.of(
                  "FX_PAIR_MULTIPLE_QUOTES",
                  EconomyLogSource.ECONOMY_FX,
                  "day",
                  day,
                  "pair",
                  entry.getKey(),
                  "quotes",
                  quotes.size(),
                  "distinctQuotes",
                  distinct.size(),
                  "detail",
                  String.join(", ", named),
                  "bestBid",
                  bestBid.governmentId().value() + "=" + bestBid.rate().buyPerMille(),
                  "bestAsk",
                  bestAsk.governmentId().value() + "=" + bestAsk.rate().sellPerMille(),
                  "summaryBidPerMille",
                  summary == null ? 0L : summary.bidPerMille(),
                  "summaryAskPerMille",
                  summary == null ? 0L : summary.askPerMille()));
    }
  }

  /** 窗口的格 = 其国库户所在格（家户行的当前格；没有该行 ⇒ {@code null}，见 Order.window 的注）。 */
  private static HexCoord treasuryHex(MarketSettlement.MarketRound round, HouseholdId treasury) {
    HouseholdEconomy row = round.householdEconomies().get(treasury);
    return row == null ? null : row.view().hex();
  }

  /** 窗口的当轮状态（可变计数器：本轮成交/未成交；不进状态）。 */
  private static final class WindowState {
    final FxRoundInput.Window spec;
    final HouseholdId treasury;
    final GovFxWindow.Quote quote;

    /**
     * ★ 窗口的格 = 其国库户所在格（{@code null} = 该国库户没有经济行）。
     *
     * <p>★ <b>它只服务"两侧都不是家户"的那一种成交</b>（同币对多窗口下两条窗口单互相交叉）：{@code Transfer} 的契约要求 {@code location}
     * 非空，两条窗口单之间没有任何家户格可借 ⇒ 用卖方窗口的国库户格。窗口单的 {@code regionId} 仍是空串（读数字段一字未动）。
     */
    final HexCoord hex;

    long buyFilledBase;
    long sellFilledBase;
    long buyQuotePaid;
    long sellQuoteReceived;
    long unfilledBuyBase;
    long unfilledSellBase;

    WindowState(
        FxRoundInput.Window spec, HouseholdId treasury, GovFxWindow.Quote quote, HexCoord hex) {
      this.spec = spec;
      this.treasury = treasury;
      this.quote = quote;
      this.hex = hex;
    }
  }

  /**
   * 簿上的一条委托（窗口单与家户单同形；{@code window} 区分场所与容量来源）。
   *
   * <p>★ <b>{@code remaining} 就地可变</b>：本类是逐轮瞬态的撮合器（与 {@code MarketSettlement} 的槽位同一形制）， 不进任何持久状态。
   */
  private static final class Order {
    static final Comparator<Order> HOUSEHOLD_ORDER =
        Comparator.comparing((Order order) -> order.buy ? 1 : 0)
            .thenComparingLong(order -> order.buy ? -order.limitPerMille : order.limitPerMille)
            .thenComparing(Order::ownerRank);

    final ActorRef owner;
    final HouseholdId household;
    final HexCoord hex;
    final String regionId;
    final boolean buy;
    final CurrencyId base;
    final CurrencyId quote;
    final long limitPerMille;
    final boolean window;
    final WindowState windowState;
    final long originalQuantity;
    long remaining;

    private Order(
        ActorRef owner,
        HouseholdId household,
        HexCoord hex,
        String regionId,
        boolean buy,
        CurrencyId base,
        CurrencyId quote,
        long limitPerMille,
        long quantity,
        boolean window,
        WindowState windowState) {
      this.owner = owner;
      this.household = household;
      this.hex = hex;
      this.regionId = regionId == null ? "" : regionId;
      this.buy = buy;
      this.base = base;
      this.quote = quote;
      this.limitPerMille = limitPerMille;
      this.remaining = quantity;
      this.originalQuantity = quantity;
      this.window = window;
      this.windowState = windowState;
    }

    static Order window(
        WindowState state, boolean buy, long limitPerMille, long quantity, HexCoord hex) {
      return new Order(
          state.spec.treasury(),
          state.treasury,
          hex,
          "",
          buy,
          state.quote.base(),
          state.quote.quote(),
          limitPerMille,
          quantity,
          true,
          state);
    }

    static Order household(
        HouseholdId household,
        HexCoord hex,
        String regionId,
        boolean buy,
        CurrencyId base,
        CurrencyId quote,
        long limitPerMille,
        long quantity) {
      return new Order(
          HouseholdActors.of(household),
          household,
          hex,
          regionId,
          buy,
          base,
          quote,
          limitPerMille,
          quantity,
          false,
          null);
    }

    String ownerRank() {
      return (household == null ? "" : household.value()) + "|" + (buy ? "b" : "s");
    }
  }
}
