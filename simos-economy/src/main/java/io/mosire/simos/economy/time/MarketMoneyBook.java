package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MarketConstants.MARKET;
import static io.mosire.simos.economy.time.MarketFreezeBook.paymentForQuantity;
import static io.mosire.simos.economy.time.MarketModelBook.BuySlot;
import static io.mosire.simos.economy.time.MarketModelBook.MatchContext;
import static io.mosire.simos.economy.time.MarketModelBook.SellSlot;
import static io.mosire.simos.economy.time.MarketOrderPlanner.Participant;
import static io.mosire.simos.economy.time.MarketTradeMath.ceilDivPositive;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketOrderKind;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.economy.api.market.PortDirection;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 市场轮机械拆分 helper（由 MarketSettlement 抽取；行为不变）。 */
final class MarketMoneyBook {

  private MarketMoneyBook() {}

  /**
   * ★★ <b>W4：买侧配给 = 按结算序<b>依次吃满</b></b>（唯一拼写点）。
   *
   * <p>★★ <b>为什么改</b>：改动前买侧用 {@link ProportionalSplit#byDenominator}（按需求权重比例人人分一口）——
   * 那是"没有顺序"的算法：把买槽表怎么排都不改变每户拿到的量（只改 ±1 毫的残差分派）⇒ 用户原话「家户之间的顺序还是得排序的啊」 落不了地。W4 起：<b>需求 &gt;
   * 供给时，按结算序在前面的先拿满，后面的拿剩下的</b>（稀缺品的分配装置 = 排序）。 ★ 供给充足（{@code matched ==
   * demand}）时逐值等价于改动前（每户都拿满自己的需求）。
   *
   * <p>★★ <b>W4-fix2：份额是"取用权"，不是"预先切好的量"</b> —— 本方法原样交出各买槽<b>自己的</b>可付剩余需求 （{@code weights}），由
   * {@link #pairUp} 按列表序（= 结算序）依次取用：先到者先吃满，<b>吃不到（本条 lane 不通）或吃不下的余量留给下一个买槽</b>；总成交仍由卖侧份额（{@code Σ
   * sellParts = matched}）封顶。
   *
   * <p>★★ <b>为什么不能预先切分（W4 初版的写法，已按现场证据改掉）</b>：初版用 {@code min(weights[i], 剩余 matched)} 逐槽把 {@code
   * matched} <b>切完</b> ⇒ 排在后面的买槽份额恒为 0，于是它们 <b>根本进不了</b> {@link #pairUp} 的内层循环。而落账是逐 (买方 × 卖方) lane
   * 判的（自配对 / 币种 / 运力 / 现金腿四关）：高名次买槽在别的格、又没有运力时，它那一份<b>整批丢掉</b>，而唯一能在这条 lane 上成交的同格买槽 已被切成 0。 ★
   * 现场证据（three-powers，{@code -Dsimos.economy.logLevel=DEBUG} 的 {@code
   * MARKET_PAIRING_SHORTFALL}）：{@code day=90 commodity=grain region=c-tp-gold} —— 56 买 / 8 卖， 前 4
   * 名按名次划走全部 12,178,452 毫，指针卡在 {@code sellerIndex=0}（格 -3,3，该格 4 个买槽份额全 0）， {@code executedMilli=0}
   * ⇒ 该区该商品<b>一笔不成</b>，整轮 {@code fills=0}（历史缺陷"市场冻结"复发）。
   *
   * <p>★ <b>与预分配形态的等价性</b>：所有 lane 都通时，"按序取用"与"预先切分"是同一个贪心（{@code Σ sellParts = matched} 封顶）⇒
   * 逐槽成交额<b>逐值相同</b>，W4 的排序语义（谁先拿满）不变。
   *
   * <p>★ <b>卖侧不动</b>：卖方之间仍是按成本层 + 比例（同一档内的成本无差别，谁先谁后不改变成交量），见 {@code matchGroup}。
   *
   * @param weights 各买槽的可成交上限（需求 ∩ 现金腿可负担量；非负），数组序 = 结算序
   * @return 与 {@code weights} 同内容的数组（{@link #pairUp} 只读、不写）
   */
  static long[] buyPartsByPriority(long[] weights) {
    return weights;
  }

  /**
   * ★★★ <b>W5.5（2026-10-11，S-12⑤）：把货币腿算出的清算利率写进交回的价表</b>（下一轮生效）。
   *
   * <pre>
   * 逐 (市场, 新利率): table = marketTables[市场] ⇒ 交回 withLoanRatePerMille(新利率)（其余字段逐值不变）
   * 没有变化（{@code loanRateNext} 为空）⇒ **原样返回入参那一份实例**（调用点按"换了实例才重写工作副本"判）
   * </pre>
   *
   * <p>★ 为什么利率挂价表：见 {@code MarketTable} 的类注（派生形态的世界没有 {@code MarketZone} 记录、但价表持久）。 ★
   * 本方法只改一个字段，且价表没有第二个改价出口（W1 起的既有纪律）。
   */
  static Map<MarketZoneId, MarketTable> applyMoneyMarketLoanRates(
      Map<MarketZoneId, MarketTable> marketTables,
      MoneyMarketSettlement.Round moneyRound,
      long day) {
    if (moneyRound.loanRateNext().isEmpty()) {
      return marketTables;
    }
    Map<MarketZoneId, MarketTable> next = new LinkedHashMap<>(marketTables);
    long rewritten = 0L;
    StringBuilder rates = new StringBuilder();
    for (Map.Entry<MarketZoneId, Long> entry : moneyRound.loanRateNext().entrySet()) {
      MarketTable table = next.get(entry.getKey());
      if (table == null) {
        continue; // 该市场本轮没有价表（被撤/缺表）⇒ 利率无处可写，不静默建表。
      }
      next.put(entry.getKey(), table.withLoanRatePerMille(entry.getValue()));
      rewritten++;
      if (rates.length() > 0) {
        rates.append(',');
      }
      rates
          .append(entry.getKey().value())
          .append('=')
          .append(table.loanRatePerMille())
          .append("->")
          .append(entry.getValue());
    }
    if (rewritten > 0L && MARKET.isInfoEnabled()) {
      EventLog.channel(MARKET)
          .info(
              LogEvent.of(
                  "MONEY_MARKET_RATE_WRITTEN",
                  EconomyLogSource.ECONOMY_DEBT,
                  "day",
                  day,
                  "markets",
                  rewritten,
                  "ratesPerMille",
                  rates.toString(),
                  "effectiveFrom",
                  "next-market-round",
                  "reason",
                  "loan-rate-adapts-to-money-supply-and-demand-S-12-5"));
    }
    return next;
  }

  /**
   * ★★★ <b>W5.5（S-12②）：把借到的钱补进买槽的订单预算</b>（{@link BuySlot#borrowedBudgetTopUpMilli} 的唯一写点）。
   *
   * <pre>
   * 逐买槽: topUp = borrowedByHousehold[买方][本槽支付币]        // 逐 (户, 币) 的借款本金之和
   *         （0 = 这一户这一币没借到 ⇒ 一格都不动；缺省世界逐值退回改前）
   * </pre>
   *
   * <p>★ <b>为什么按 (户, 币) 而不是"这一户借到的总额"</b>：货币腿逐区、以该区计价币清算，而一个家户的买槽可能用别的 币支付（{@link MarketPayChoice}
   * 的"最强持有币"）—— 不是同一种钱就不能混着花（跨币折算本批不做）。
   *
   * <p>★ <b>它不是第二处判据</b>：补进额只进 {@code requestedMoneyOf}（冻结上限）与 {@code payableMoneyOf}（现金腿可付额）
   * 这两个**同一个算式的两个读点**，不新建任何"能不能买"的判定；账户余额那一侧仍由唯一写口落账。
   */
  static void applyMoneyMarketBudgetTopUps(
      MatchContext ctx, MoneyMarketSettlement.Round moneyRound) {
    Map<HouseholdId, Map<CurrencyId, Long>> borrowed = moneyRound.borrowedByHousehold();
    if (borrowed.isEmpty()) {
      return;
    }
    long toppedUp = 0L;
    long slots = 0L;
    for (BuySlot buy : ctx.buys) {
      HouseholdId household = buy.buyer.household();
      if (household == null) {
        continue;
      }
      long amount = borrowed.getOrDefault(household, Map.of()).getOrDefault(buy.currency, 0L);
      if (amount <= 0L) {
        continue;
      }
      buy.borrowedBudgetTopUpMilli = Math.addExact(buy.borrowedBudgetTopUpMilli, amount);
      toppedUp = Math.addExact(toppedUp, amount);
      slots++;
    }
    if (MARKET.isDebugEnabled() && slots > 0L) {
      EventLog.channel(MARKET)
          .debug(
              LogEvent.of(
                  "MONEY_MARKET_BUDGET_TOPUP",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  ctx.round.day,
                  "slots",
                  slots,
                  "borrowedBudgetTotalMilli",
                  toppedUp,
                  "reason",
                  "order-budget-is-a-planning-time-snapshot; borrowed-money-must-be-spendable-before-freezing"));
    }
  }

  /**
   * ★★★ <b>W5.5：货币腿的输入装配</b>（S-12③ 的"本期生产花费所需现金"这个量<b>只有这里</b>能算对）。
   *
   * <pre>
   * 计价币：本区价表的 numeraire（持久形态下它逐值等于 MarketZone.legalTender —— W1 的单一权威）
   * 需求  ：need(户, 区) = Σ 该户在本区的买槽 ⌈数量 × 本区当前牌价 ÷ 1000⌉      // 毫本区计价币
   * 参与户：本区市场轮的家户（{@code ctx.participants} 里 household 非空那些；单位户已被排除）
   * </pre>
   *
   * <p>★★ <b>为什么不用 {@code requestedMoneyOf(buy)}（本仓已有的"这条买单最多花多少钱"）</b>：那个算式是 {@code min(订单预算,
   * 货值)}，而订单预算是<b>下单当刻</b>的可花额 —— 现金 0 的家户那里它恒为 0，而"现金 0"正是
   * <b>必须先借钱</b>的那一户（用户原话②「先拿空气购买货币——也就是借钱」）⇒ 用它算需求会让整条腿恒等于
   * "没有人缺钱"（死分支）。故这里取<b>货值本身</b>（按本区牌价、与买盘目标同一个量），不按现金折回。
   *
   * <p>★★ <b>只统计"用本区计价币支付"的买槽</b>（fail-closed，本批的具名口径）：货币市场逐区、以该区计价币清算； 用别的币支付的买槽需要一条本批不存在的折算通道 ⇒
   * 不计入（该户退到"未付价款记债"）。单币世界/同币支付的买槽 （绝大多数）逐值不受影响。
   *
   * <p>★ 缺表的区（"该区不交易任何商品"）不借钱：没有牌价就折不出"本期生产花费所需现金"。
   */
  static MoneyMarketSettlement.Input moneyMarketInput(MatchContext ctx) {
    Map<MarketZoneId, CurrencyId> zoneCurrency = new LinkedHashMap<>();
    Map<MarketZoneId, MarketTable> zoneTables = new LinkedHashMap<>();
    List<MarketZoneId> orderedZones = new ArrayList<>();
    for (MarketRegion region : ctx.orderedRegions) {
      MarketZoneId zoneId = new MarketZoneId(region.node().nodeId());
      MarketTable table = ctx.tableOf(region);
      if (table == null) {
        continue;
      }
      // ★ 计价币取价表的 numeraire：持久形态下它逐值等于区法定币（W1 的构造期跨表守卫判死漂开），
      //   派生形态（没有区记录）下它是唯一的货币事实 —— 两种形态都是"这个市场按哪种钱报价"。
      zoneCurrency.put(zoneId, table.numeraire());
      // ★★ W5.5：利率/α 的唯一权威是**价表**（逐市场持久；派生形态的世界也有它，见 MarketTable 的类注）。
      zoneTables.put(zoneId, table);
      orderedZones.add(zoneId);
    }
    // ── 参与户按区归组（谁是放贷人不需要买槽：只要在本区市场里、有闲钱）────────────────────────
    Map<MarketZoneId, List<HouseholdId>> zoneHouseholds = new LinkedHashMap<>();
    Map<HouseholdId, HexCoord> householdHex = new LinkedHashMap<>();
    for (Map.Entry<ActorRef, Participant> entry : ctx.participants.entrySet()) {
      Participant participant = entry.getValue();
      HouseholdId household = participant.household();
      if (household == null) {
        continue; // 经营者/组织没有家户账户可借可贷（DebtContract 两端只能是家户）
      }
      HexCoord hex = ctx.participantHex.get(entry.getKey());
      if (hex == null || ctx.topology == null || !ctx.topology.contains(hex)) {
        continue;
      }
      MarketZoneId zoneId = new MarketZoneId(ctx.topology.regionOf(hex).node().nodeId());
      if (!zoneCurrency.containsKey(zoneId)) {
        continue;
      }
      zoneHouseholds.computeIfAbsent(zoneId, ignored -> new ArrayList<>()).add(household);
      householdHex.putIfAbsent(household, hex);
    }
    // ★ 规范序（家户 id 升序）：两簿的配对序另有结算序（区内按净值），这里只保证"逐区家户表"是内容的纯函数（I7）。
    for (List<HouseholdId> households : zoneHouseholds.values()) {
      households.sort(Comparator.comparing(HouseholdId::value));
    }
    // ── ② 需求侧：逐买槽按本区当前牌价折成"毫本区计价币"（与货款算式同源：⌈数量 × 单价 ÷ 1000⌉）──────
    Map<MarketZoneId, Map<HouseholdId, Long>> needByZone = new LinkedHashMap<>();
    for (BuySlot buy : ctx.buys) {
      HouseholdId household = buy.buyer.household();
      if (household == null) {
        continue;
      }
      MarketZoneId zoneId = new MarketZoneId(buy.region.node().nodeId());
      CurrencyId currency = zoneCurrency.get(zoneId);
      if (currency == null || !currency.equals(buy.currency)) {
        continue;
      }
      MarketTable table = ctx.tableOf(buy.region);
      if (table == null) {
        continue;
      }
      // ★ 0 价（白送）= 不需要现金；未定价的商品根本不会生成买槽。
      long need = paymentForQuantity(buy.order.quantity(), table.priceOf(buy.order.commodity()));
      if (need <= 0L) {
        continue;
      }
      needByZone
          .computeIfAbsent(zoneId, ignored -> new LinkedHashMap<>())
          .merge(household, need, Long::sum);
    }
    long dueCycle = ctx.deferredPaymentConfig == null ? 0L : ctx.deferredPaymentConfig.dueCycle();
    return new MoneyMarketSettlement.Input(
        zoneTables,
        orderedZones,
        ctx.ordering,
        zoneCurrency,
        zoneHouseholds,
        needByZone,
        householdHex,
        dueCycle);
  }

  // ── 一笔成交（区内即时 / 跨区在途）────────────────────────────────────────────────

  /**
   * ★★ <b>E（2026-10-09 裁定 R1/R2/R3/R4/R6）：唯一拼写点 —— 本笔"收什么钱、按什么价收"的裁决</b>。
   *
   * <p>★★ <b>为什么必须共用一处</b>：商品面上"钱从买方到卖方"的腿共用<b>这一个拼写点</b>：
   *
   * <pre>
   * ① 现金成交腿 executeTrade          ：payment 按 buy.currency 铸（★ 唯一活着的调用方）
   * ② 信用成交腿 moneyCreditForBuy     ：—— ★★ 已随 W2-fix4 整段退役（市场信用撮合删除），无调用方
   * ③ 借实物腿   goodsCreditForBuy     ：—— 同上（货腿不经货币、币种维为空的判据保留，但无调用方）
   * </pre>
   *
   * <p>★ <b>②③ 的分支为什么还留在代码里（具名）</b>：{@code leg} 这个参数仍是**币种挂单闸**（P-T1e）的挂单类型 词表 —— {@link
   * #orderKindOfLeg} 把"哪条腿"翻成 {@link MarketOrderKind}（货↔钱 / 借贷），而 {@code
   * PortEnforcementInput.CurrencyEnforcement} 与 {@code PortGateTaxAcceptanceTest} 都按这个类型分列规则。
   * 删掉它们要改动那条判据面（超出本批），故只把"已无调用方"具名写在这里；它们**不产生任何成交**。
   *
   * <p>★★ <b>判据（逐字 = 文档 §3.1）</b>：
   *
   * <pre>
   * 买方付出金额 × 我对该币的估值 ≥ 我的保留价 × 数量   ⇒ 成交；否则"不划算"（不落任何账）
   * </pre>
   *
   * 三处的量纲与取法（全部同微刻度，见 {@link HouseholdValuationBook#MICRO_PER_MILLI}）：
   *
   * <ul>
   *   <li>"买方付出金额" = 本笔在<b>买方支付币</b>下的货款（毫），单价由"钱的价"换算： {@code 买方币单价 = ⌈卖方币单价 × 1000 ÷ 我对该币的估值⌉} ⇒
   *       估值越低、要的该币越多（E2）；
   *   <li>"我对该币的估值" = {@link CurrencyValuation#valuationMicro}（本币 1:1 / 世界行情 / 当地实际流通 ⇒ 面值 / 三者都不是
   *       ⇒ 说不出价）；
   *   <li>"我的保留价" = 卖方槽位建槽时冻结的 {@link SellSlot#reservationMicro}（与套利用的保留价同一个算式）。
   * </ul>
   *
   * <p>★★ <b>同币成交不做任何事</b>（{@code buy.currency == sell.receiveCurrency} ⇒ 直接返回原单价）：本币对自己 = 面值
   * 1:1（R3 的锚），判据恒成立 ⇒ 同币路径<b>逐值退回改前</b>（E3 / N2 是结构性保证，不是"实测碰巧一样"）。
   *
   * <p>★★ <b>删掉了什么</b>：A2a/A2b 的"币种不等 ⇒ 具名拒"（{@link MarketUnfilledReason#CURRENCY_MISMATCH}）整条语义。
   * 不划算一律走<b>既有市场性理由</b>（本批取 {@link MarketUnfilledReason#PRICE_LIMIT}：说不出这个价 / 折回本币不够
   * 保留价），<b>不新增"币种不符"语义</b>（N1/N3）。47.9 万条 {@code MARKET_CURRENCY_MISMATCH_REJECTED} 的噪声随之消失，
   * 取而代之的是逐笔 DEBUG 的"为什么"（{@code MARKET_CURRENCY_VALUE_REFUSED}）。
   *
   * <p>★★ <b>调用方的处置（停止 / 跳过这一家）不由本方法规定</b>：现金腿是<b>跳过这一家卖方、看下一家</b>；货币信用腿是 {@code
   * break}（那一轮为这个买方定下的唯一借款链就是它）；借实物腿币种维为空（不拒）。三条腿共享的是<b>判据与日志形态</b>。
   *
   * @param quantity 本次尝试的数量（毫商品；判据按它折算"付出金额"）
   * @param unitPrice 本笔的<b>卖方币</b>单价（毫卖方币 / 商品单位）
   * @param leg 哪条腿（{@code cash} / {@code money-credit} / {@code goods-credit}）
   * @param bookRefusal true = 真的落账（置买卖两侧的市场性理由 + DEBUG why）；false = 只做纸面裁决（配对前的
   *     可负担性/限价预判，不改任何状态、不写日志）
   * @return {@code >= 0} = 以买方支付币计价的单价（{@code 0} = 合法的 0 价/免费）；{@code -1} = 不划算 （调用方不得落账）
   */
  static long settlementUnitPrice(
      MatchContext ctx,
      BuySlot buy,
      SellSlot sell,
      long quantity,
      long unitPrice,
      String leg,
      boolean bookRefusal) {
    if (LEG_GOODS_CREDIT.equals(leg)) {
      // ★ 借实物腿不经货币（债务单位 = 商品）：币种维为空 ⇒ 判据落在既有的"可借余量"上，本方法不新增门槛。
      //   ★★ W2-fix4：这条腿已随市场信用撮合退役 ⇒ **本支当前不可达**（保留理由见方法注：leg 是 P-T1e 的挂单类型词表）。
      return unitPrice;
    }
    // ★★ 3c（V-1）+ P-T1e：候选成立条件的第一项 —— **买方支付币过得了"币种挂单闸"**（卖方接受集 ∩ 两侧口岸规则）。
    //   不给过 ⇒ 候选不成立：具名归因（CURRENCY_NOT_ACCEPTED）、DEBUG 一条"为什么"、返回"不划算"哨兵 ⇒ 三条腿都**不落任何账**
    //   （现金腿由调用方换下一家卖方、信用腿 break；见各自的处置注）。
    //   ★ 判定内容<b>只</b>在 acceptsCurrency / currencyGateBlock 一处拼写；归因、日志、不落账由这里自动生效。
    //   ★ 缺省中性（I-C2）：一条币种规则都没设 ⇒ 闸恒放行 ⇒ 逐值等于改前。
    if (!acceptsCurrency(ctx, buy, sell, buy.currency, orderKindOfLeg(leg))) {
      if (bookRefusal) {
        refuseUnacceptedCurrency(ctx, buy, sell, quantity, unitPrice, leg);
      }
      return UNPROFITABLE_CURRENCY;
    }
    if (buy.currency.equals(sell.market.numeraire())) {
      // ★ 同币：本币 1:1 ⇒ 判据恒成立 ⇒ 逐值退回改前（E3/N2 的结构性保证）。
      return unitPrice;
    }
    CurrencySettlement terms = currencySettlement(ctx, buy, sell, unitPrice);
    if (terms == null) {
      if (bookRefusal) {
        refuseUnprofitableCurrency(
            ctx,
            buy,
            sell,
            quantity,
            unitPrice,
            0L,
            leg,
            "currency-has-no-quotation-and-does-not-circulate-locally",
            -1L);
      }
      return UNPROFITABLE_CURRENCY;
    }
    long payment = paymentForQuantity(quantity, terms.buyerUnitPrice());
    long offeredMicro = multiplyOrFail(payment, terms.valueMicro(), "买方付出金额 × 我对该币的估值");
    long requiredMicro =
        ceilDivPositive(
            multiplyOrFail(sell.reservationMicro, quantity, "我的保留价 × 数量"),
            HouseholdValuationBook.MICRO_PER_MILLI);
    if (offeredMicro < requiredMicro) {
      if (bookRefusal) {
        refuseUnprofitableCurrency(
            ctx,
            buy,
            sell,
            quantity,
            unitPrice,
            terms.valueMicro(),
            leg,
            "value-of-payment-below-my-reservation",
            requiredMicro);
      }
      return UNPROFITABLE_CURRENCY;
    }
    return terms.buyerUnitPrice();
  }

  /**
   * ★★ <b>E：不划算的哨兵</b>（{@code -1}）—— 与"合法的 0 价/免费"（{@code 0}）严格区分： 单价是毫整数、恒 {@code >= 0}，故 {@code
   * -1} 不可能是一个真价格。
   */
  static final long UNPROFITABLE_CURRENCY = -1L;

  /** 三条腿的名字（唯一拼写点；进日志的 {@code leg} 段）。 */
  static final String LEG_CASH = "cash";

  static final String LEG_MONEY_CREDIT = "money-credit";

  static final String LEG_GOODS_CREDIT = "goods-credit";

  /** ★★ E：本笔的收款裁决 —— 买方支付币单价 + 该币在卖方眼里的估值（微本币/毫买币）。 */
  record CurrencySettlement(long buyerUnitPrice, long valueMicro, boolean sameCurrency) {}

  /**
   * ★★ <b>E：本笔的收款裁决</b>（纯函数）：同币 ⇒ 原价 + 面值；异币 ⇒ 查行情/当地流通的估值并折算买方币单价。
   *
   * @return {@code null} = 说不出这种钱的价（调用方按"不划算"落市场性理由，绝不静默按 1:1）
   */
  static CurrencySettlement currencySettlement(
      MatchContext ctx, BuySlot buy, SellSlot sell, long unitPrice) {
    if (buy.currency.equals(sell.market.numeraire())) {
      return new CurrencySettlement(unitPrice, CurrencyValuation.faceValueMicro(), true);
    }
    long valueMicro =
        ctx.currencyValuation()
            .valuationMicro(sell.market.numeraire(), buy.currency, sell.region.node().nodeId());
    if (valueMicro <= 0L) {
      return null;
    }
    return new CurrencySettlement(buyerUnitPriceFor(unitPrice, valueMicro), valueMicro, false);
  }

  /**
   * ★★ <b>E：以买方支付币计价的单价</b> {@code ⌈卖方币单价 × 1000 ÷ 我对该币的估值⌉}（毫买币 / 商品单位）： 估值越低 ⇒ 要的该币越多（E2）；估值 =
   * 1000（面值 1:1）⇒ 逐值等于原单价。
   */
  static long buyerUnitPriceFor(long unitPrice, long valueMicro) {
    if (unitPrice <= 0L) {
      return 0L; // 0 价（免费）：换算后仍是 0，判据由调用方按"付出 0"判
    }
    if (valueMicro <= 0L) {
      throw new IllegalArgumentException("E 的买方币单价要求估值 > 0: " + valueMicro);
    }
    return ceilDivPositive(
        multiplyOrFail(unitPrice, HouseholdValuationBook.MICRO_PER_MILLI, "卖方币单价 × 1000"),
        valueMicro);
  }

  /**
   * ★★ <b>E：以买方支付币计价的单价（纯函数）</b>——限价过滤与可负担量预判用；不落账、不写日志。
   *
   * <p>★ {@code 0} = 说不出这种钱的价（调用方按"不划算"处理）；同币 ⇒ 原价原样返回。
   */
  static long convertedBuyerUnitPrice(
      MatchContext ctx, BuySlot buy, SellSlot sell, long unitPrice) {
    CurrencySettlement terms = currencySettlement(ctx, buy, sell, unitPrice);
    return terms == null ? UNPROFITABLE_CURRENCY : terms.buyerUnitPrice();
  }

  /**
   * ★★ <b>3c：一条（买方 × 一组卖方）的"买方支付币单价" —— "这个单价属于哪个币"的唯一读取口</b>。
   *
   * <p>★★ <b>为什么要有它</b>：改前 {@code matchRoute} 用一只代表槽位（{@code sells.get(0)}）承担整条路线的折算 ——
   * 那隐含"同一路线的卖方计价币一致"。订单可带币之后这条假设不再成立（计划 §2.1 明确要求放宽），
   * 于是折算改为<b>逐卖方槽位</b>做（每个槽位读它自己那张价表的计价币），本方法只负责把逐槽位结果合成一个数。
   *
   * <p>★★ <b>合成规则 = 取"对买方最不利"（折算后单价最高）的那一档</b>：
   *
   * <ul>
   *   <li>它是<b>预判</b>（限价过滤 + 可负担量的配给权重），不是落账判据：逐笔真判在 {@link #pairUp} / {@link #executeTrade}
   *       里按<b>当时那一个</b>卖方槽位重算（同一个拼写点 {@link #currencySettlement}）⇒ 预判绝不可能放行一笔付不起的成交；
   *   <li>取最不利 = 与既有 {@link #chargeAffordableQuantity} 的"保守少买"同一取向（宁可少配、不可多配）；
   *   <li><b>说不出价的槽位不参与</b>（{@code -1}）：那种币对这一买方根本不可成交，把它当"最高价"会把整条路线判死 —— 全组都说不出来时才返回 {@code -1}（=
   *       这条路线对这个买方不可成交）。
   * </ul>
   *
   * <p>★★ <b>缺省语义中性（I-C2）</b>：同一条路线上的卖方同格（{@code activeSellsAtHex} 只从<b>一个</b> hex 取槽位） ⇒ 同一张价表 ⇒
   * 逐槽位结果逐值相同 ⇒ 取最不利 = 取任一个 = 改前 {@code sells.get(0)} 的结果。<b>旧世界逐值不变</b>。
   *
   * <p>★ <b>已知取舍（如实记，留给 P-T1e/P-T5）</b>：真出现"同一路线混合币"的卖方组时，取最不利可能把某买方在这一轮 记为限价不过（具名 {@link
   * MarketUnfilledReason#PRICE_LIMIT}），而它其实能与其中更便宜的卖方成交。要"逐档配对" 就把本方法改成按卖方分组各算一次（调用点两处：首轮 weights
   * 与分档循环）——这是本批刻意不展开的口径。
   */
  static long worstBuyerUnitPrice(
      MatchContext ctx, BuySlot buy, List<SellSlot> sellers, long unitPrice) {
    long worst = UNPROFITABLE_CURRENCY;
    for (SellSlot sell : sellers) {
      long converted = convertedBuyerUnitPrice(ctx, buy, sell, unitPrice);
      if (converted < 0L) {
        continue;
      }
      worst = Math.max(worst, converted);
    }
    return worst;
  }

  /**
   * ★★ <b>3c + P-T1e：币种挂单闸 —— "这条挂单能不能用这种钱成交"的唯一拼写点</b>（计划 §2.1 的候选成立条件 / §5.2 V-1；口岸设计书 §14.3）。
   *
   * <p>★★ <b>判定内容（P-T1e 冻结口径，用户 2026-10-10）</b>：规则可以按 <b>(币种, 挂单类型, 方向)</b>
   * 禁止某一类挂单进/出<b>市场区</b>；一票要过境必须<b>两侧都过</b>（§12 的两道闸对币种同样成立）：
   *
   * <pre>
   * 钱从买方区（源区）流向卖方区（目的区）——
   *   出口闸 = 源区（买方所在区）对 (币, 类型, EXIT) 的开放度
   *   入口闸 = 目的区（卖方所在区）对 (币, 类型, ENTRY) 的开放度
   * 任一侧开放度 = 0（该侧全禁）⇒ 不给过 ⇒ 本候选不成立（具名 CURRENCY_NOT_ACCEPTED）
   * </pre>
   *
   * <p>★★ <b>三条边界（刻意的，逐条有依据，别照"看起来更严"改）</b>：
   *
   * <ol>
   *   <li><b>只作用在跨区流动上</b>：源区 == 目的区（钱没跨边界）⇒ <b>恒放行</b>。依据：设计书 §10.3「口岸限制的是市场选择…
   *       过滤要作用在<b>跨区候选</b>上：本区自产的货没跨边界，不受口岸影响（否则等于"区内禁售"，那是市场管制、不是口岸）」， 以及 §15
   *       的两层分工「口岸层跨区、市场层区内」。⇒ 同区内的借贷/买卖<b>不受</b>任何口岸币种规则影响
   *       （这也正是"本国货币对本国居民来说只能借贷"能成立的前提：本币在本区内的借贷不会被自家的口岸规则掐死）。
   *   <li><b>两侧都要过，且只有"全禁"才算不给过</b>：开放度 = 1000 − 管制力（管制力 = 按暴露边加权平均的 {@code ⌊s×e÷1000⌋}） ⇒ {@code s
   *       = 1000} 且口岸效率 &gt; 0 才是"这一侧真的一分都不放"；部分强度是"抓不严"的比例量，不构成禁令 （挂单闸是禁入/禁出，不是节流；强度进读数与日志）。★
   *       与商品维一致：{@code s = 1000} 但 {@code e = 0}（没有口岸编制） ⇒ 管不住 ⇒ 不拦（P-T1a 已裁的同一条口径："没人管也管不住"）。
   *   <li><b>读的是"参与交易的市场区"的规则，不是"币种法定区"的规则</b>（设计书 §14.3-3 的红线）：一枚币在 两个都与它无关的区之间流动时，它的法定区规则管不着 ⇒
   *       <b>不是</b>"本币不得在境外使用／不得被外国人持有"。
   * </ol>
   *
   * <p>★ <b>挂单类型从"腿"来</b>（{@link #orderKindOfLeg}）：现金成交腿 = 货↔钱（{@link MarketOrderKind#COMMODITY}）、
   * 货币信用腿 = 借贷（{@link MarketOrderKind#LENDING}）；借实物腿不经货币（本方法根本不被它调用）。
   *
   * <p>★ <b>接受集是"规则允许的币"</b>，不是"卖方声明的收款币"：{@link SellSlot#receiveCurrency}（挂单声明的收款币）
   * 今天只进日志/读数（P-T5b 起它是"卖方自己的最强持有币"）——把它当过滤器等于给<b>未设政策</b>的世界加新限制（违反 I-C2），
   * 而且"卖家只收自己最强的那种钱"这条语义从未被裁定。⇒ 本批：<b>缺口 = 政策规则</b>，声明照旧只作读数（记在账本"关键判断"）。
   *
   * <p>★ <b>缺省语义中性（I-C2）</b>：{@code portEnforcement.currencyRegimeActive() == false}（一条币种规则都没设 /
   * 未注入） ⇒ 恒真 ⇒ 旧世界逐值不变；即使表非空，缺键也读作"管制力 0 ⇒ 开放度 1000 ⇒ 放行"。
   */
  static boolean acceptsCurrency(
      MatchContext ctx, BuySlot buy, SellSlot sell, CurrencyId payment, MarketOrderKind orderKind) {
    return currencyGateBlock(ctx, buy, sell, payment, orderKind) == null;
  }

  /**
   * ★★ <b>P-T1e：币种挂单闸的判据本体</b>（{@link #acceptsCurrency} 与 {@link #refuseUnacceptedCurrency} 共用 ——
   * 判定<b>只在这里拼写一次</b>，日志那条不另算一遍）。
   *
   * @return {@code null} = 两侧都给过；非 null = 具名拒因（哪一侧、哪个方向、管制力多少）
   */
  static CurrencyGateBlock currencyGateBlock(
      MatchContext ctx, BuySlot buy, SellSlot sell, CurrencyId payment, MarketOrderKind orderKind) {
    PortEnforcementInput port = ctx.round.portEnforcement();
    if (port == null || !port.currencyRegimeActive()) {
      return null; // 一条币种规则都没有 ⇒ 全币接受（缺省中性，I-C2）
    }
    String sourceZone = buy.regionId; // 钱的来源区 = 买方所在区
    String destinationZone = sell.regionId; // 钱的目的区 = 卖方所在区
    if (sourceZone.equals(destinationZone)) {
      return null; // 口岸是边界闸：同区流动不受口岸影响（§10.3/§15）
    }
    long exitOpenness =
        port.currencyOpennessPerMille(sourceZone, payment, orderKind, PortDirection.EXIT);
    if (exitOpenness <= 0L) {
      return new CurrencyGateBlock(
          "source-zone-exit-closed", PortDirection.EXIT, sourceZone, exitOpenness);
    }
    long entryOpenness =
        port.currencyOpennessPerMille(destinationZone, payment, orderKind, PortDirection.ENTRY);
    if (entryOpenness <= 0L) {
      return new CurrencyGateBlock(
          "destination-zone-entry-closed", PortDirection.ENTRY, destinationZone, entryOpenness);
    }
    return null;
  }

  /**
   * ★ <b>一次币种挂单闸拒因</b>（日志 payload 用；判据与 {@link #currencyGateBlock} 逐字同源）。
   *
   * @param why 规范拒因字面量（{@code source-zone-exit-closed} / {@code destination-zone-entry-closed}）
   * @param direction 被挡住的那一侧的方向
   * @param zoneId 被挡住的那一侧的市场区（注入表的区键）
   * @param opennessPerMille 该侧开放度（判据里必为 0；记下来是为了让"为什么"可核）
   */
  record CurrencyGateBlock(
      String why, PortDirection direction, String zoneId, long opennessPerMille) {}

  /**
   * ★ <b>挂单类型（{@link MarketOrderKind}）与"腿"的唯一映射点</b>（{@link #settlementUnitPrice} 的三条腿）。
   *
   * <p>★ 未登记的腿 ⇒ <b>fail-closed 抛出</b>（不是静默当成某一种）：腿只有三条，多一条就说明调用点与判定点漂开了。
   */
  static MarketOrderKind orderKindOfLeg(String leg) {
    if (LEG_CASH.equals(leg)) {
      return MarketOrderKind.COMMODITY; // 货 ↔ 钱
    }
    if (LEG_MONEY_CREDIT.equals(leg)) {
      return MarketOrderKind.LENDING; // 借来的钱买货（借贷也是市场挂单）
    }
    throw new IllegalStateException("未登记的成交腿（币种挂单闸无从判定挂单类型）: " + leg);
  }

  /**
   * ★★ <b>3c + P-T1e：挂单被币种闸挡下的具名落点</b>（与 {@link #refuseUnprofitableCurrency} 同形： 市场性归因 + DEBUG
   * "为什么" + 轮级 INFO 汇总，绝不静默丢）。
   *
   * <p>★ <b>两侧都记</b>：卖方是"我的挂单在哪个区、声明收什么钱"、买方是"我拿什么钱来买、从哪个区来"，缺一边就读不出是"目的区不让进" 还是"源区不让出"。
   *
   * <p>★ <b>判据不在这里重算</b>：{@link #currencyGateBlock} 是唯一拼写点，本方法只在 DEBUG 打开时问它一次"是哪一侧挡的"
   * （纯函数、逐值可复现）；日志关闭时连这一次询问都不做（热路径上只留两个计数）。
   *
   * <p>★ <b>为什么逐笔是 DEBUG 不是 INFO</b>（§一.9 的取舍）：跨区候选逐笔尝试，逐笔 INFO 就是上一批 47.9 万条噪声的翻版 ——
   * "这一轮被挡住了多少"由轮级 INFO {@code MARKET_CURRENCY_GATE_BLOCKED} 与成交侧 INFO 承担，"哪一笔被哪条规则挡住"归 DEBUG。
   */
  static void refuseUnacceptedCurrency(
      MatchContext ctx, BuySlot buy, SellSlot sell, long quantity, long unitPrice, String leg) {
    MarketUnfilledReason reason = MarketUnfilledReason.CURRENCY_NOT_ACCEPTED;
    if (sell.blocked == null) {
      sell.blocked = reason;
    }
    if (buy.blocked == null) {
      buy.blocked = reason;
    }
    ctx.currencyGateBlocked++;
    ctx.currencyGateBlockedMilli = Math.addExact(ctx.currencyGateBlockedMilli, quantity);
    if (!MARKET.isDebugEnabled()) {
      return; // 日志失败/关闭不得影响结算，也不做无谓的字段拼装
    }
    MarketOrderKind orderKind = orderKindOfLeg(leg);
    CurrencyGateBlock block = currencyGateBlock(ctx, buy, sell, buy.currency, orderKind);
    EventLog.channel(MARKET)
        .debug(
            LogEvent.of(
                "MARKET_CURRENCY_NOT_ACCEPTED",
                EconomyLogSource.ECONOMY_FX,
                "day",
                ctx.round.day,
                "leg",
                leg,
                "orderKind",
                orderKind.value(),
                "reason",
                reason.value(),
                "commodity",
                buy.order.commodity().value(),
                "buyer",
                buy.buyer.actor(),
                "seller",
                sell.seller.actor(),
                "buyerPays",
                buy.currency.value(),
                "sellerAccepts",
                sell.receiveCurrency.value(),
                "sellerOwn",
                sell.market.numeraire().value(),
                "sourceZone",
                buy.regionId,
                "destinationZone",
                sell.regionId,
                "blockedSide",
                block == null ? "-" : block.direction().value(),
                "blockedZone",
                block == null ? "-" : block.zoneId(),
                "blockedZoneOpennessPerMille",
                block == null ? -1L : block.opennessPerMille(),
                "sellerUnitPriceMilli",
                unitPrice,
                "requestedQuantityMilli",
                quantity,
                "why",
                block == null ? "payment-currency-blocked-by-port-rule" : block.why()));
  }

  /**
   * ★★ <b>E：不划算的落点（三条腿共用）</b>——买卖两侧各留<b>市场性理由</b>（{@link MarketUnfilledReason#PRICE_LIMIT}）+ 一条
   * DEBUG "为什么"。
   *
   * <p>★ <b>为什么是 DEBUG 不是 INFO</b>（§一.9 的取舍，理由记在这里）：异币配对数远多于成交数，逐对 INFO 就是上一批 47.9
   * 万条噪声的翻版；"这一轮发生了什么"由成交侧的 INFO 与轮汇总承担，"为什么没成"按纪律归 DEBUG。
   *
   * @param valueMicro 该币在卖方眼里的估值（{@code 0} = 说不出价）；{@code requiredMicro} 只进日志（{@code -1} = 不适用）
   */
  static void refuseUnprofitableCurrency(
      MatchContext ctx,
      BuySlot buy,
      SellSlot sell,
      long quantity,
      long unitPrice,
      long valueMicro,
      String leg,
      String why,
      long requiredMicro) {
    if (sell.blocked == null) {
      sell.blocked = MarketUnfilledReason.PRICE_LIMIT;
    }
    if (buy.blocked == null) {
      buy.blocked = MarketUnfilledReason.PRICE_LIMIT;
    }
    if (!MARKET.isDebugEnabled()) {
      return; // 日志失败/关闭不得影响结算，也不做无谓的字段拼装
    }
    EventLog.channel(MARKET)
        .debug(
            LogEvent.of(
                "MARKET_CURRENCY_VALUE_REFUSED",
                EconomyLogSource.ECONOMY_FX,
                "day",
                ctx.round.day,
                "leg",
                leg,
                "reason",
                MarketUnfilledReason.PRICE_LIMIT.value(),
                "commodity",
                buy.order.commodity().value(),
                "buyer",
                buy.buyer.actor(),
                "seller",
                sell.seller.actor(),
                "buyerPays",
                buy.currency.value(),
                // ★★ 3c：卖方**挂单声明的**收款币（接受集）与它的**估值锚**（本格计价币 = 它自己的钱）分开记
                //   —— 改前两者恒等，混记就读不出"挂单不收支币"与"支币折不回本币"这两种不同的拒因。
                "sellerAccepts",
                sell.receiveCurrency.value(),
                "sellerOwn",
                sell.market.numeraire().value(),
                "valueMicro",
                valueMicro,
                "sellerUnitPriceMilli",
                unitPrice,
                "requestedQuantityMilli",
                quantity,
                "reservationMicro",
                sell.reservationMicro,
                "requiredMicro",
                requiredMicro,
                "why",
                why));
  }

  /** 整数乘法：溢出 ⇒ 具名失败（与既有的"估价一律不静默回退到 double"同口径）。 */
  static long multiplyOrFail(long left, long right, String what) {
    try {
      return Math.multiplyExact(left, right);
    } catch (ArithmeticException overflow) {
      throw new IllegalStateException(
          "E 的估值算式整数溢出（拒绝回退到 double）: " + what + " left=" + left + " right=" + right, overflow);
    }
  }

  /**
   * ★★ <b>E：（§一.9 + 文档 §3.1）一笔异币成交的 INFO —— 哪两户、什么币、估值多少、成交比价</b>。
   *
   * <pre>
   * impliedPerMille = 买方币单价 × 1000 ÷ 卖方币单价   // "1 毫卖方币要多少毫买方币"（‰）
   * </pre>
   *
   * <p>★ 只在<b>真的成交</b>且付款为正时调用（拒绝路径见 {@link #refuseUnprofitableCurrency} 的 DEBUG）；
   * 同币成交不进这里（那是改前的既有行为，没有新信息，也不该刷日志）。
   */
  static void logForeignCurrencyFill(
      MatchContext ctx,
      BuySlot buy,
      SellSlot sell,
      CommodityId commodity,
      long quantity,
      long sellerUnitPrice,
      long buyerUnitPrice,
      long payment) {
    CurrencySettlement terms = currencySettlement(ctx, buy, sell, sellerUnitPrice);
    long valueMicro = terms == null ? 0L : terms.valueMicro();
    long impliedPerMille =
        sellerUnitPrice > 0L
            ? multiplyOrFail(buyerUnitPrice, 1000L, "买方币单价 × 1000") / sellerUnitPrice
            : 0L;
    EventLog.channel(MARKET)
        .info(
            LogEvent.of(
                "MARKET_FOREIGN_CURRENCY_FILL",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                ctx.round.day,
                "commodity",
                commodity.value(),
                "buyer",
                buy.buyer.actor(),
                "seller",
                sell.seller.actor(),
                "buyerHex",
                buy.hex,
                "sellerHex",
                sell.hex,
                "buyerPays",
                buy.currency.value(),
                // ★★ 3c：卖方挂单声明的收款币（接受集）与估值锚（本格计价币）分开记。
                "sellerAccepts",
                sell.receiveCurrency.value(),
                "sellerOwn",
                sell.market.numeraire().value(),
                "valueMicroPerMilli",
                valueMicro,
                "sellerUnitPriceMilli",
                sellerUnitPrice,
                "buyerUnitPriceMilli",
                buyerUnitPrice,
                "impliedPerMille",
                impliedPerMille,
                "quantityMilli",
                quantity,
                "paymentMilli",
                payment));
  }
}
