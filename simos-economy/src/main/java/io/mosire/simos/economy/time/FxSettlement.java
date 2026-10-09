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
import java.util.Set;
import java.util.TreeMap;

/**
 * ★★ <b>外汇撮合（阶段 2-A2a；约束设计书 §3.2/§3.4）</b>：把 FX 委托纳入<b>既有的市场轮</b>，与商品撮合共用同一处落账口。
 *
 * <pre>
 * ① 报价：逐窗口（GOV）算 bidP/askP 与两侧容量（三项约束 + 国库付得起；见 {@link GovFxWindow}）
 * ② 建簿：<b>窗口挂单先</b>（政府是这个市场上的挂牌方），再逐格逐户的家户单
 *        家户单两条规则（都只在"该币对有官方汇率"时才有）：
 *          SELL（外币花不出去）：持有 base 且本地法定币<b>可花额为零</b> ⇒ 卖出全部 base
 *                                 限价 = bidP × (1000 − 100)/1000（它急着换成能花的钱 ⇒ 接受折价）
 *          BUY （余钱换外币）  ：本地法定币可花额 − 该户货币保留额 &gt; 0 ⇒ 用余钱按 askP 买 base
 * ③ 撮合：价格-时间优先、可吃深度 —— 买单按限价降序、卖单按限价升序，逐个交叉配对；
 *         成交量 = min(双方剩余, 买方付得起, 卖方拿得出)，成交价 = 两限价的中间价（{@link FxPricing}）
 * ④ 落账：每笔 = <b>两条腿</b>，逐条经唯一写口 {@code EconomySettlement.applyTransfer}（铁律 2 不被绕过）
 * </pre>
 *
 * <p>★★ <b>本类不是第二个 applier</b>：它只算"谁给谁多少"，动账一律 {@code applyTransfer}；因此逐币种守恒（I20）不是
 * 本类的性质，而是"只有一条写路径"的性质 —— 本类一个账户 {@code put} 都不写。
 *
 * <p>★★ <b>同币对多份报价（2026-10-09 A 批；设计书 §4.4 / G3）</b>：同一币对的生效报价可以有多条（多个 GOV 各挂各的价）， <b>全部并存</b> ——
 * 每个窗口各进簿一张单，按 {@code limitPerMille} 价格优先撮合（{@link #matchBook}）。家户挂单价的锚取该 币对的<b>最优摘要</b>：{@code
 * bid} = 所有窗口买价中的<b>最高</b>、{@code ask} = 所有窗口卖价中的<b>最低</b> （"对手方最划算的那份"）⇒ 家户买单限价 =
 * 最便宜的卖价，最优窗口自然先被吃（"货比三家"的落点）。多份【不同】报价按 {@code FX_PAIR_MULTIPLE_QUOTES}
 * 具名记录（INFO，永不静默）；多份【相同】报价不刷日志（§一.9）。★ <b>不得按区收窄撮合域</b>： 家户自己看价、直接买，不存在"只能跟本区成交"（该问法已作废，设计书
 * §1.3/§2.2）。
 *
 * <p>★★ <b>为什么放在市场轮里、而不是另开一条日结算支</b>：① 家户能花的钱是"商品市场撮合之后"的余额（同一份账户表）； ② 冻结/信用/未成交归因都在这一轮里；③
 * 官方汇率与实际汇率的对照必须落在同一个世界日上（F4）。
 *
 * <p>★★ <b>家户规则为什么<b>不</b>看窗口当轮的容量</b>：容量是"政府这一轮做不做"，而家户的限价来自<b>官方报价</b>
 * （政策价是状态，不是这一轮的挂单）。若让家户规则依赖窗口容量，窗口一停（储备触顶/见底）整个外汇市场就消失 —— 而设计书 §4.5
 * 要的恰恰相反：<b>官方汇率只在窗口有量时才拉动市场价</b>，窗口停了，市场还在，只是价格由家户自己定。
 *
 * <p>★ <b>本轮的边界（如实记）</b>：逐轮瞬态、不落盘；FX 成交不进 {@code ProductionLedger.transfers}（那是生产腿的账）， 只进本轮 {@link
 * FxRoundResult} 与日志 ——"最近 N 笔"因此是<b>逐轮</b>读数（跨轮滚动窗口需要持久化，与 I17 冲突，见 {@code FxMarketRate} 的类注）。
 */
final class FxSettlement {

  /**
   * ★ <b>最小手</b>（base 最小单位）：1000 = 1 个币种单位（A 阶段两币种 {@code scale = 3}）。
   *
   * <p>★ 它是<b>GM 可调默认值</b>：没有它，逐毫的尘埃成交会把"实际汇率"这个加权均价搅浑，也给日志刷量。
   */
  static final long FX_MIN_LOT_BASE_MILLI = 1_000L;

  /**
   * ★ <b>家户卖外币的折价</b>（千分比，相对政府买价）：100‰ = 10%。
   *
   * <p>★ 依据：设计书 §3.4 的"外币花不出去"—— 一个持有外币、<b>本地法定币为零</b>的家户要的是"能花的钱"，因此它愿意在
   * 政府买价之下再让一截（它的限价是<b>下限</b>）。★ 这个数不是判据，是 <b>GM 可调默认值</b>（参数目录落地后迁入）；判据是 "官方报价 ≠ 市场成交价"（F4）—— 只要折价
   * &gt; 0，市场价就结构性低于官方买价。
   */
  static final long FX_SELL_DISTRESS_PER_MILLE = 100L;

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
   * @return 本轮的外汇读数（没有窗口/没有市场 ⇒ {@link FxRoundResult#none()}，逐值退回 A2a 之前）
   */
  static FxRoundResult match(
      MarketSettlement.MarketRound round, Map<HexCoord, Market> markets, MarketTopology topology) {
    FxRoundInput input = round.fx();
    if (input == null || !input.isActive() || markets.isEmpty()) {
      return FxRoundResult.none();
    }
    List<FxRoundResult.NamedRejection> rejections = new ArrayList<>();
    List<WindowState> windows = new ArrayList<>();
    Map<String, PairRates> pairs = new TreeMap<>();
    // ★★ §4.4（G3）：同一币对的<b>多份报价全部并存</b>——不再 putIfAbsent"先到先得"。
    //   两个用途各一张表：① pairs = 该币对的<b>最优摘要</b>（家户挂单价的锚）；② quotesByPair = 逐条留名（冲突日志用）。
    //   ★ 逐条报价属于哪个窗口/窗口单怎么进簿，一字未动（见下面的建簿段）。
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
                "国库 actor 不是已登记家户（账户主体只有家户）⇒ 窗口不参与"));
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
                    + "）"));
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
                "政府卖出侧停做（储备 " + reserve + "，不许卖空）"));
      }
    }

    // ── ①.5 报价冲突（★ §4.4 / §一.9）：同一币对多份【不同】报价 ⇒ INFO 具名（谁/各什么价/谁最优）；
    //         多份【相同】报价 ⇒ 不刷日志（否则噪声）。永不静默（这是 F9"静默丢弃"的正面替代）。
    logMultipleQuotes(round.day(), quotesByPair, pairs);

    // ── ② 建簿：每个"有官方汇率的币对"一本（窗口停做不影响市场的存在）────────────────────────
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
    planHouseholdOrders(round, markets, topology, pairs, books);

    // ── ③ 逐币对撮合（价格优先 + canonical 时间序；吃深度）─────────────────────────────
    List<FxFill> fills = new ArrayList<>();
    for (Map.Entry<String, List<Order>> entry : books.entrySet()) {
      matchBook(round, entry.getValue(), fills, rejections);
    }

    // ── ④ 未成交的具名归因（不许静默留残量）──────────────────────────────────────────
    attributeResidue(round.day(), books, rejections);
    return finish(round, windows, fills, rejections);
  }

  // ── 家户单的生成（两条规则）──────────────────────────────────────────────────────

  private static void planHouseholdOrders(
      MarketSettlement.MarketRound round,
      Map<HexCoord, Market> markets,
      MarketTopology topology,
      Map<String, PairRates> pairs,
      Map<String, List<Order>> books) {
    Map<String, List<HouseholdId>> rowsByHex =
        EconomySettlement.rowsByHex(round.householdEconomies());
    List<HexCoord> hexes = new ArrayList<>(markets.keySet());
    hexes.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
    long sellOrders = 0L;
    long buyOrders = 0L;
    for (HexCoord hex : hexes) {
      Market market = markets.get(hex);
      CurrencyId local = market.numeraire();
      List<HouseholdId> rows =
          rowsByHex.getOrDefault(IndustryHexKeys.hexKey(hex.q(), hex.r()), List.of());
      String regionId = topology.contains(hex) ? topology.regionOf(hex).node().nodeId() : "";
      for (HouseholdId household : rows) {
        if (household == null || round.marketExcludedHouseholds().contains(household)) {
          continue;
        }
        long reserveMoney = MarketSettlement.moneyReserveOfHousehold(round, household, market);
        for (PairRates pair : pairs.values()) {
          if (!pair.quote.equals(local)) {
            continue; // 本格法定币不是这个币对的计价币 ⇒ 这对报价与本格无关
          }
          List<Order> book = books.get(pairKey(pair.base, pair.quote));
          if (book == null) {
            continue;
          }
          long spendableBase = MarketSettlement.spendableMoneyOf(round, household, pair.base);
          long spendableQuote = MarketSettlement.spendableMoneyOf(round, household, pair.quote);
          // SELL：外币花不出去（持有外币、本地法定币可花额为零）—— 每户每币对至多一条，且与买盘互斥。
          if (spendableQuote == 0L && spendableBase >= FX_MIN_LOT_BASE_MILLI) {
            long floor =
                Math.max(1L, pair.bidPerMille * (1000L - FX_SELL_DISTRESS_PER_MILLE) / 1000L);
            book.add(
                Order.household(
                    household, hex, regionId, false, pair.base, pair.quote, floor, spendableBase));
            sellOrders++;
            continue;
          }
          long surplus = spendableQuote - reserveMoney;
          if (surplus <= 0L) {
            continue;
          }
          long quantity = GovFxWindow.mulDivFloor(surplus, 1000L, pair.askPerMille);
          if (quantity < FX_MIN_LOT_BASE_MILLI) {
            continue;
          }
          book.add(
              Order.household(
                  household,
                  hex,
                  regionId,
                  true,
                  pair.base,
                  pair.quote,
                  pair.askPerMille,
                  quantity));
          buyOrders++;
        }
      }
    }
    if (sellOrders > 0L || buyOrders > 0L) {
      EventLog.channel(FX)
          .debug(
              LogEvent.of(
                  "FX_ORDER_PLAN",
                  EconomyLogSource.ECONOMY_FX,
                  "day",
                  round.day(),
                  "sellOrders",
                  sellOrders,
                  "buyOrders",
                  buyOrders,
                  "pairs",
                  books.size()));
    }
    for (List<Order> book : books.values()) {
      book.sort(Order.HOUSEHOLD_ORDER);
    }
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
                    : "卖方外币可花额不足（" + sellerBase + " 毫 " + blocked.base.value() + "）"));
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
                    "政府买入无对手方（本轮没有可成交的家户卖单 / 家户卖盘已吃尽）"));
          } else {
            order.windowState.unfilledSellBase += order.remaining;
          }
          continue;
        }
        // 家户残量：说清"还剩多少、为什么"（结算序的最后一层归因；不准静默）。
        //   ★ 两个具名档的区别是**事实层面**的：对面一侧压根没有挂单 ⇒ NO_COUNTERPARTY（"没人接"）；
        //     对面有挂单但限价不交叉 ⇒ NO_CROSS（"价不拢"）；部分成交后把对面吃尽 ⇒ NO_COUNTERPARTY。
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
                "本轮对手盘不足（剩余 " + order.remaining + " 毫 " + order.base.value() + "）"));
      }
    }
  }

  // ── 组装读数 ────────────────────────────────────────────────────────────────────

  private static FxRoundResult finish(
      MarketSettlement.MarketRound round,
      List<WindowState> windows,
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
    if (!fills.isEmpty() || !rejections.isEmpty()) {
      EventLog.channel(FX)
          .info(
              LogEvent.of(
                  "FX_ROUND",
                  EconomyLogSource.ECONOMY_FX,
                  "day",
                  round.day(),
                  "windows",
                  windows.size(),
                  "fills",
                  fills.size(),
                  "baseVolumeMilli",
                  baseVolume(fills),
                  "windowFills",
                  windowFillCount(fills),
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

  /** 逐笔具名拒去重：同一 (reason, actor, pair) 只留第一条（一轮里同一原因刷屏没有信息量）。 */
  private static void rejectOnce(
      long day,
      List<FxRoundResult.NamedRejection> rejections,
      FxRoundResult.NamedRejection candidate) {
    for (FxRoundResult.NamedRejection existing : rejections) {
      if (existing.reason() == candidate.reason()
          && existing.actor().equals(candidate.actor())
          && existing.base().equals(candidate.base())
          && existing.quote().equals(candidate.quote())) {
        return;
      }
    }
    rejections.add(candidate);
    EventLog.channel(FX)
        .info(
            LogEvent.of(
                "FX_REJECTED",
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
                candidate.detail()));
  }

  /** 一个币对的官方报价（家户规则的锚；与窗口当轮容量无关，见类注）。 */
  private record PairRates(CurrencyId base, CurrencyId quote, long bidPerMille, long askPerMille) {

    /**
     * ★★ §4.4：同一币对多份报价的<b>合并口径</b>（唯一拼写点）：{@code bid} 取<b>最高</b>买价、{@code ask} 取<b>最低</b>卖价 ——
     * "对手方最划算的那份"。
     *
     * <p>★ 家户"货比三家"的落点：它的买单限价 = 最便宜的卖价、卖单底价 = 最高买价再折价；撮合按价格优先 ⇒ 最优窗口先被吃。
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
