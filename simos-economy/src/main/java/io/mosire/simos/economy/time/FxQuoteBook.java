package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.FxSettlement.FX;
import static io.mosire.simos.economy.time.FxSettlement.FX_MIN_LOT_BASE_MILLI;
import static io.mosire.simos.economy.time.FxSettlement.HouseholdPlan;
import static io.mosire.simos.economy.time.FxSettlement.Order;
import static io.mosire.simos.economy.time.FxSettlement.PairQuote;
import static io.mosire.simos.economy.time.FxSettlement.PairRates;

import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.fx.FxRejectReason;
import io.mosire.simos.economy.api.fx.OfficialRate;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
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

/** FX 拆分 helper（由 FxSettlement 抽取；行为不变）。 */
final class FxQuoteBook {

  private FxQuoteBook() {}

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
  static HouseholdPlan planHouseholdOrders(
      MarketSettlement.MarketRound round,
      Map<MarketZoneId, MarketTable> markets,
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
    List<HexCoord> hexes = new ArrayList<>();
    for (MarketRegion region : topology.regions()) {
      hexes.addAll(region.members()); // ★ W1：有市场的格 = 拓扑成员格（改动前 = marketTables.keySet()）
    }
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
        // ★★ R2（2026-10-11）**口径边界**：家户外汇单（弱换强）不在 R2 的放行面里 —— R2 放开的是**商品市场买盘**
        //   （口粮通道），故除普通家户外一律不挂家户外汇单：国库户（只按授权/只走 GOV 外汇窗口）与单位户（军户/官吏户，
        //   卖侧原口径）都照旧跳过。★ 这是**比 R2 字面更窄**的 fail-closed 选择，已记进实现账本待裁定。
        if (household == null
            || round.householdMarketRole(household) != MarketSettlement.HouseholdMarketRole.FULL) {
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
        //   ★ P-T5b：与订单侧的支付币选择（{@code MarketPayChoice}）共用<b>同一个</b>拼写点
        //     （{@code HouseholdPurchasingPower.strongest}）—— "换成哪种币"与"用哪种币付"必须是同一个答案。
        CurrencyId target = HouseholdPurchasingPower.strongest(power);
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
   *
   * <p>★ <b>P-T5b：包内可见（去掉 {@code private}）</b> —— 订单侧的支付币选择（{@code MarketPayChoice}）必须用<b>同一条</b>
   * "我手里有哪几种币"的口径，否则"换成最强币"的输入集与"用最强币付"的输入集会在冻结/余额边界上漂开。
   */
  static List<CurrencyId> heldCurrencies(
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
  static String placeHouseholdOrder(
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
  static void logHouseholdPower(
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
  static String powerText(Map<CurrencyId, Long> power) {
    List<String> parts = new ArrayList<>();
    for (Map.Entry<CurrencyId, Long> entry : power.entrySet()) {
      parts.add(entry.getKey().value() + "=" + entry.getValue());
    }
    parts.sort(Comparator.naturalOrder());
    return String.join(",", parts);
  }

  /** 币种名表（升序；读数字段用）。 */
  static String currencyNames(List<CurrencyId> currencies) {
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
   *
   * <p>★ <b>P-T5b：包内可见（去掉 {@code private}）</b> —— 订单侧的支付币选择（{@code MarketPayChoice}）用<b>同一条</b>
   * 早退门：单币世界里连"最强币"都不必问（结构性的逐值不变，而不是"算出来恰好相等"）。
   */
  static boolean hasMultipleCurrencies(
      MarketSettlement.MarketRound round, Map<MarketZoneId, MarketTable> markets) {
    CurrencyId first = null;
    for (MarketTable market : markets.values()) {
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

  static String pairKey(CurrencyId base, CurrencyId quote) {
    return OfficialRate.keyOf(base, quote);
  }

  static void logWindowEvent(
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
  static void rejectOnce(
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
  static void logMultipleQuotes(
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
  static HexCoord treasuryHex(MarketSettlement.MarketRound round, HouseholdId treasury) {
    HouseholdEconomy row = round.householdEconomies().get(treasury);
    return row == null ? null : row.view().hex();
  }
}
