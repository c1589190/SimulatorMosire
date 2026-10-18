package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.fx.FxFill;
import io.mosire.simos.economy.api.fx.FxRejectReason;
import io.mosire.simos.economy.api.fx.OfficialRate;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
  static final org.slf4j.Logger FX = EconomyLog.fx();

  private FxSettlement() {}

  // ── ★★ P-T5：民间簿的家户单（口径 = F-1/F-2/F-3/F-4，唯一拼写点见 HouseholdPurchasingPower）────

  /**
   * ★ <b>本轮家户侧的量（只进日志，不进任何状态）</b>。
   *
   * @param households 挂出至少一条单的家户数（INFO 的"参与户数"）
   * @param orders 家户单总数
   * @param pairs 家户单覆盖的币对数（= 被家户单触及的簿数）
   */
  record HouseholdPlan(int households, int orders, int pairs) {

    static HouseholdPlan empty() {
      return new HouseholdPlan(0, 0, 0);
    }
  }

  /**
   * 一个币对的官方报价<b>摘要</b>（窗口簿建簿 + 多份报价日志用）。
   *
   * <p>★★ <b>P-T5 起它不再是家户挂单价的锚</b>（家户限价只从 F-1/F-4 的购买力口径来）；家户"货比三家"的旧口径 （买单限价 = 最便宜卖价 / 卖单底价 =
   * 最高买价再折价）随旧规则一起退役，见 {@link #FX_MIN_LOT_BASE_MILLI} 的注。
   */
  record PairRates(CurrencyId base, CurrencyId quote, long bidPerMille, long askPerMille) {

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
  record PairQuote(GovernmentId governmentId, OfficialRate rate) {}

  /** 窗口的当轮状态（可变计数器：本轮成交/未成交；不进状态）。 */
  static final class WindowState {
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
  static final class Order {
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

    Order(
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

  static FxRoundResult match(
      MarketSettlement.MarketRound round,
      Map<MarketZoneId, MarketTable> markets,
      MarketTopology topology) {
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
      String key = FxQuoteBook.pairKey(rate.base(), rate.quote());
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
        FxQuoteBook.rejectOnce(
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
        FxQuoteBook.logWindowEvent(
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
      windows.add(new WindowState(spec, treasury, quote, FxQuoteBook.treasuryHex(round, treasury)));
      if (quote.buyBlocked() != null) {
        FxQuoteBook.logWindowEvent(
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
        FxQuoteBook.rejectOnce(
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
        FxQuoteBook.logWindowEvent(
            round.day(),
            "FX_WINDOW_SELL_BLOCKED",
            quote.sellBlocked(),
            spec.governmentId(),
            rate,
            reserve,
            "reserve=" + reserve);
        FxQuoteBook.rejectOnce(
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
    FxQuoteBook.logMultipleQuotes(round.day(), quotesByPair, pairs);

    // ── ② 建簿：窗口簿（每个"有官方汇率的币对"一本；窗口停做不影响市场的存在）────────────────────
    Map<String, List<Order>> books = new LinkedHashMap<>();
    for (Map.Entry<String, PairRates> entry : pairs.entrySet()) {
      books.put(entry.getKey(), new ArrayList<>());
    }
    for (WindowState window : windows) {
      String key = FxQuoteBook.pairKey(window.quote.base(), window.quote.quote());
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
    HouseholdPlan plan = FxQuoteBook.planHouseholdOrders(round, markets, topology, books);

    // ── ③ 逐币对撮合（价格优先 + canonical 时间序；吃深度）─────────────────────────────
    List<FxFill> fills = new ArrayList<>();
    for (Map.Entry<String, List<Order>> entry : books.entrySet()) {
      FxMatchBook.matchBook(round, entry.getValue(), fills, rejections);
    }

    // ── ④ 未成交的具名归因（不许静默留残量）──────────────────────────────────────────
    FxMatchBook.attributeResidue(round.day(), books, rejections);
    return FxMatchBook.finish(round, windows, plan, fills, rejections);
  }

  static List<CurrencyId> heldCurrencies(
      MarketSettlement.MarketRound round, HouseholdId household) {
    return FxQuoteBook.heldCurrencies(round, household);
  }

  static boolean hasMultipleCurrencies(
      MarketSettlement.MarketRound round, Map<MarketZoneId, MarketTable> markets) {
    return FxQuoteBook.hasMultipleCurrencies(round, markets);
  }
}
