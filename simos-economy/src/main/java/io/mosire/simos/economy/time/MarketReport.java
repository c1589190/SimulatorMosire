package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.market.MarketTaxLayer;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
import io.mosire.simos.economy.api.market.PriceMode;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * ★★ <b>一轮区域市场的只读报告</b>（M2.3/M2.4 的读数原料；L3 的逐区读数组件接它）。
 *
 * <p>★★ <b>它不落盘</b>：本层（L2）只保证"信息被产生且不聚合丢失"，把"逐区逐商品供给/需求/成交量/到货价/运费/损耗/ 未成交原因分布/未利用运力"落成读数组件是
 * L3（M2.7）的事。⇒ 旧日推进器的 lastMarketReport()（R3a 已删除） 交出的就是这一份；L3 的 {@code MarketReadout}
 * 把它折进去（读时派生、进程内、重启即失），不必回头改撮合。
 *
 * <p>★★ <b>四类信息一个不少</b>（L3 需要的都在这里，不是"以后再算"）：
 *
 * <ul>
 *   <li>{@link #fills()}：每一笔成交（含区内即时与跨区在途）—— 发货格/收货格/买卖双方/量/单价/运费/ETA/在途批次 id/逐票预排损耗；
 *   <li>{@link #unfilled()}：每一笔未成交剩余与**原因档** + **所在格**（{@link MarketUnfilledReason}；买/卖两侧分开）；
 *   <li>{@link #routes()}：每条路线的运力、用量、瓶颈标志 —— ★ "有货、有路、运力不足 ⇒ 城市仍可能缺粮，系统报物流瓶颈"的落点；
 *   <li>{@link #freightPaidByCurrency()} / {@link #freightUncollectedByCurrency()} / {@link
 *       #scheduledLossMilli()}： 运费实收、无承运人时未收的运费（★ 前两项按币分列）、按批次损耗率预排的在途损耗（实际损耗在到货日进 ledger）。
 * </ul>
 *
 * <p>★★ <b>跨区结算暂设即时</b>（M2.0 #4 的读数契约标注）：{@link #CROSS_REGION_SETTLEMENT_IMMEDIATE} 恒为 {@code true}
 * —— 货款与运费在**发运日**结清、货在 ETA 之后才到。L3 的读数组件必须原样标注这条简化，不得把"付款日"读成"到货日"。 ★ M2.6：{@link #priceMode()} 与
 * {@link #priceUpdates()} 让读数分清"固定价 / 自适应"。
 *
 * @param day 本轮的世界日
 * @param trigger 本轮为什么开市（例行/关账/低库存追加）
 * @param carrierPresent 世界里有没有承运主体（{@code ActorKind.ORGANIZATION} 且会话里有货币账）—— {@code false}
 *     时运费**不收**（没有收款方就不收，禁钱凭空消失）
 * @param fills 逐笔成交（保序）
 * @param unfilled 逐笔未成交剩余（保序）
 * @param routes 逐路线运力用量（保序）
 * @param freightPaidByCurrency ★★ P-T4：本轮实收运费<b>按币分列</b>（键 = 铸这条 {@code CARRIER_FEE}
 *     腿用的钱，即该笔买方的支付币； 毫；保序 + 不可变）。禁跨币相加 ⇒ 单币世界恒只有一项，值与本批之前逐值相同
 * @param freightUncollectedByCurrency ★★ P-T4：因无承运人/运力不足而未收的运费<b>按币分列</b>（键 = 该笔买方的支付币；读数看得见，不静默）
 * @param scheduledLossMilli 按批次损耗率预排的在途损耗（毫商品；到货日才真的从在途量里扣）
 * @param immediateFills 区内即时成交笔数
 * @param crossRegionFills 跨区在途成交笔数
 * @param priceMode ★ M2.6：本轮报价模式（{@code fixed} = 固定价、{@code adaptive} = 自适应）—— 读数必须能区分
 * @param priceUpdates ★ M2.6：自适应模式下的逐 (集散节点, 商品) 改价记录；固定模式恒为空表
 * @param sellerOutcomes ★ S3：逐卖方槽位的只读结果（成本估计 / 成交 / 未成交原因 / 被谁挤掉）；空表 = 本轮没有卖方槽
 * @param buyerOutcomes ★ S3：逐买方槽位的只读结果（库存/覆盖/缺口/预算/下单/成交/原因）；空表 = 本轮没有买方槽
 * @param creditFills ★★ D-030：本轮信用成交的债务来源（钱货销售另在 {@link #fills()} 里一笔；借实物只有本表一笔）。
 *     它是"信用成交"与"现金成交"的分辨点：同一笔钱货销售同时出现在 {@code fills} 与 {@code creditFills}，纯现金成交 只在 {@code fills}。
 */
public record MarketReport(
    long day,
    MarketTrigger trigger,
    boolean carrierPresent,
    List<Fill> fills,
    List<Unfilled> unfilled,
    List<RouteUsage> routes,
    Map<CurrencyId, Long> freightPaidByCurrency,
    Map<CurrencyId, Long> freightUncollectedByCurrency,
    long scheduledLossMilli,
    long immediateFills,
    long crossRegionFills,
    PriceMode priceMode,
    List<PriceUpdate> priceUpdates,
    List<SellerOutcome> sellerOutcomes,
    List<BuyerOutcome> buyerOutcomes,
    List<CreditFill> creditFills,
    FxRoundResult fx) {

  /**
   * ★★ <b>跨区结算暂设即时</b>（M2.0 #4 的具名标记）：货款与运费在发运日结清，货在 ETA 之后到。 ★ L3 的读数契约接这一位；本批不做"到货付款"。★
   * R3a：旧撮合引擎删除，此常量由 {@code MarketSettlement} 迁来，值恒为 {@code true}。
   */
  public static final boolean CROSS_REGION_SETTLEMENT_IMMEDIATE = true;

  /**
   * ★★ <b>D-027：逐票"区级税费"读数快照</b>（identity 键 → 毫计价货币税费）。
   *
   * <p>★ 它是 record 组件之外的<b>派生只读件</b>：record 不许有实例字段，故不进 {@code toString}/{@code equals}/
   * 序列化形状；键用<b>引用身份</b>（不是 {@code equals}），不会把两份等值报告串味。{@link #withRegulatedTariff}
   * 是唯一写入点；其余构造路径这里没有条目 ⇒ 读数为 0。
   */
  private static final Map<Object, Map<Fill, Long>> REGULATED_TARIFF_BY_REPORT =
      java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

  /**
   * ★★ <b>P-T1b：逐层税项的只读快照</b>（identity 键 → 税项列表；形制与 {@link #REGULATED_TARIFF_BY_REPORT} 逐字相同）。
   *
   * <p>★★ <b>它回答什么</b>（计划 §2.5 的读口判据）："这笔货被抽了哪些税、各多少、<b>进哪个国库</b>、什么币" —— 层（{@link
   * MarketTaxLayer}）/ 金额 / 币种 / 收款政府<b>逐项列出</b>，且这些项的总额<b>就是买方多付的那一部分</b> （买方实付 = 货款 + 运费 +
   * 税项之和；卖方仍收原价、承运方收运费 ⇒ 差额只可能进国库，货币守恒 I-C1）。
   *
   * <p>★ <b>按币分列（I-C10）</b>：金额只以<b>该笔的买方支付币</b>表达，求和一律按币分组（{@link #taxByCurrency()}）， 禁跨币相加。
   */
  private static final Map<Object, List<TaxItem>> TAX_ITEMS_BY_REPORT =
      java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

  /**
   * ★★ <b>P-T1b：一条税项</b>（读口形状 = 层 / 金额 / 币种 / 收款政府；另带商品与区，便于逐项追溯）。
   *
   * @param layer 层（出口税 / 进口税 / 区内市场税）
   * @param government 收款政府（政府身份串；多政府共管一个区时，<b>一层会有多条</b>——每政府一条）
   * @param currency 币种（= 该笔的买方支付币；钱腿就铸在它上面）
   * @param amountMilli 金额（毫；恒 &gt; 0 —— 0 额不进表）
   * @param commodity 商品
   * @param zone 收税那一侧的区（出口税 = 源区 / 进口税 = 目的区 / 区内税 = 本区）
   */
  public record TaxItem(
      MarketTaxLayer layer,
      String government,
      CurrencyId currency,
      long amountMilli,
      CommodityId commodity,
      String zone) {

    public TaxItem {
      Objects.requireNonNull(layer, "TaxItem.layer 不得为 null");
      if (government == null || government.isBlank()) {
        throw new IllegalArgumentException("TaxItem.government 不得为空白");
      }
      Objects.requireNonNull(currency, "TaxItem.currency 不得为 null");
      if (amountMilli <= 0L) {
        throw new IllegalArgumentException("TaxItem.amountMilli 必须 > 0（0 额不记）: " + amountMilli);
      }
      Objects.requireNonNull(commodity, "TaxItem.commodity 不得为 null");
      if (zone == null || zone.isBlank()) {
        throw new IllegalArgumentException("TaxItem.zone 不得为空白");
      }
    }
  }

  /**
   * ★★ <b>旧形状兼容构造器（A2a）</b>：没有外汇读数的报告（{@link FxRoundResult#none()}）。
   *
   * <p>★ 它保住的正是"既有构造点/既有夹具一字不改仍编译"这一条：{@code fx} 是 A2a 新增的第 17 个组件。
   */
  public MarketReport(
      long day,
      MarketTrigger trigger,
      boolean carrierPresent,
      List<Fill> fills,
      List<Unfilled> unfilled,
      List<RouteUsage> routes,
      Map<CurrencyId, Long> freightPaidByCurrency,
      Map<CurrencyId, Long> freightUncollectedByCurrency,
      long scheduledLossMilli,
      long immediateFills,
      long crossRegionFills,
      PriceMode priceMode,
      List<PriceUpdate> priceUpdates,
      List<SellerOutcome> sellerOutcomes,
      List<BuyerOutcome> buyerOutcomes,
      List<CreditFill> creditFills) {
    this(
        day,
        trigger,
        carrierPresent,
        fills,
        unfilled,
        routes,
        freightPaidByCurrency,
        freightUncollectedByCurrency,
        scheduledLossMilli,
        immediateFills,
        crossRegionFills,
        priceMode,
        priceUpdates,
        sellerOutcomes,
        buyerOutcomes,
        creditFills,
        FxRoundResult.none());
  }

  public MarketReport {
    Objects.requireNonNull(trigger, "trigger");
    Objects.requireNonNull(priceMode, "priceMode");
    fills = fills == null ? List.of() : List.copyOf(fills);
    unfilled = unfilled == null ? List.of() : List.copyOf(unfilled);
    routes = routes == null ? List.of() : List.copyOf(routes);
    // ★ 冻结写在**赋值处**（照 MarketReadout/FieldDelta 的成例：SpotBugs 只认它自己看得见的
    //   Collections.unmodifiable*），helper 只负责"拷一份 + 校验"。
    freightPaidByCurrency =
        Collections.unmodifiableMap(copyMoney(freightPaidByCurrency, "freightPaidByCurrency"));
    freightUncollectedByCurrency =
        Collections.unmodifiableMap(
            copyMoney(freightUncollectedByCurrency, "freightUncollectedByCurrency"));
    priceUpdates = priceUpdates == null ? List.of() : List.copyOf(priceUpdates);
    sellerOutcomes = sellerOutcomes == null ? List.of() : List.copyOf(sellerOutcomes);
    buyerOutcomes = buyerOutcomes == null ? List.of() : List.copyOf(buyerOutcomes);
    creditFills = creditFills == null ? List.of() : List.copyOf(creditFills);
    fx = fx == null ? FxRoundResult.none() : fx;
  }

  /**
   * ★★ <b>P-T4：逐币金额表的构造期防御性拷贝</b>（保序）。★ I7：用 {@code LinkedHashMap} 保序，<b>不用</b> {@code
   * Map.copyOf}（后者的迭代序不是内容的纯函数）；不可变包装写在赋值处（见构造器）。
   *
   * <p>★ 拒绝 null 键/值与负额：读数的"没有"必须是空表（= 没有这种币的金额），不是 0、更不是负。
   */
  private static Map<CurrencyId, Long> copyMoney(Map<CurrencyId, Long> amounts, String what) {
    Map<CurrencyId, Long> copy = new LinkedHashMap<>();
    if (amounts != null) {
      for (Map.Entry<CurrencyId, Long> entry : amounts.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0L) {
          throw new IllegalArgumentException("MarketReport." + what + " 不得含 null 键/值或负额: " + entry);
        }
        copy.put(entry.getKey(), entry.getValue());
      }
    }
    return copy;
  }

  /** 只按<b>引用身份</b>相等的外部键（见 {@link #REGULATED_TARIFF_BY_REPORT}）。 */
  private static final class IdentityKey {
    private final Object target;

    IdentityKey(Object target) {
      this.target = target;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof IdentityKey key && key.target == target;
    }

    @Override
    public int hashCode() {
      return System.identityHashCode(target);
    }
  }

  /**
   * ★★ <b>D-027：带区级税费读数的报告工厂</b>（唯一会填 {@link #regulatedTariffByCurrency()} 的入口）。
   *
   * <p>★ 为什么税费不落成 {@link Fill} 字段：本批税费<b>只记读数、不搬钱</b>（收款方未定），把它塞进成交形状会诱导
   * 读口把它加进到货价；放在这里则"谁要是真收了"这件事一眼可辨。{@code tariffPerUnit} 里非 0 的项才产生读数； 税费按<b>成交毛量</b>折算（{@code ⌊量 ×
   * 单价税 ÷ 1000⌋}，向下取整）。
   *
   * <p>★★ P-T4：税费的<b>币种不另开一列</b>——它就是<b>卖方格计价币</b>，而那个事实的唯一拼写点是 {@link
   * Fill#unitCurrency()}（费率本身出自卖方所在区的调控）。逐票总额按它分组进 {@link #regulatedTariffByCurrency()}。
   *
   * @param tariffByFill 逐票单位税费（毫卖方计价币/商品单位；空/缺项/≤0 = 该票不记税费）—— 税费按<b>成交毛量</b>折算 （{@code ⌊量 × 单价税 ÷
   *     1000⌋}，向下取整）
   */
  public static MarketReport withRegulatedTariff(
      long day,
      MarketTrigger trigger,
      boolean carrierPresent,
      List<Fill> fills,
      List<Unfilled> unfilled,
      List<RouteUsage> routes,
      Map<CurrencyId, Long> freightPaidByCurrency,
      Map<CurrencyId, Long> freightUncollectedByCurrency,
      long scheduledLossMilli,
      long immediateFills,
      long crossRegionFills,
      PriceMode priceMode,
      List<PriceUpdate> priceUpdates,
      List<SellerOutcome> sellerOutcomes,
      List<BuyerOutcome> buyerOutcomes,
      List<CreditFill> creditFills,
      Map<Fill, Long> tariffByFill) {
    return withRegulatedTariff(
        day,
        trigger,
        carrierPresent,
        fills,
        unfilled,
        routes,
        freightPaidByCurrency,
        freightUncollectedByCurrency,
        scheduledLossMilli,
        immediateFills,
        crossRegionFills,
        priceMode,
        priceUpdates,
        sellerOutcomes,
        buyerOutcomes,
        creditFills,
        tariffByFill,
        FxRoundResult.none());
  }

  /**
   * ★★ <b>A2a：带本轮外汇读数的报告工厂</b>（区间税费那套逻辑一个字不改，只是把 {@code fx} 一起带进 record）。
   *
   * <p>★ 旧签名保留并委托到本方法（{@code fx = none()}）⇒ 既有调用点/夹具零串改。
   */
  public static MarketReport withRegulatedTariff(
      long day,
      MarketTrigger trigger,
      boolean carrierPresent,
      List<Fill> fills,
      List<Unfilled> unfilled,
      List<RouteUsage> routes,
      Map<CurrencyId, Long> freightPaidByCurrency,
      Map<CurrencyId, Long> freightUncollectedByCurrency,
      long scheduledLossMilli,
      long immediateFills,
      long crossRegionFills,
      PriceMode priceMode,
      List<PriceUpdate> priceUpdates,
      List<SellerOutcome> sellerOutcomes,
      List<BuyerOutcome> buyerOutcomes,
      List<CreditFill> creditFills,
      Map<Fill, Long> tariffByFill,
      FxRoundResult fx) {
    return withRegulatedTariff(
        day,
        trigger,
        carrierPresent,
        fills,
        unfilled,
        routes,
        freightPaidByCurrency,
        freightUncollectedByCurrency,
        scheduledLossMilli,
        immediateFills,
        crossRegionFills,
        priceMode,
        priceUpdates,
        sellerOutcomes,
        buyerOutcomes,
        creditFills,
        tariffByFill,
        fx,
        List.of());
  }

  /**
   * ★★ <b>P-T1b：带三轮税项读数的报告工厂</b>（唯一会填 {@link #taxItems()} / {@link #taxByCurrency()} 的入口）。
   *
   * <p>★★ <b>与 {@code tariffByFill} 的关系</b>：{@code tariffByFill} 是 D-027 的<b>逐票单位税费读数</b> （毫卖方计价币 /
   * 商品单位；P-T4 起按卖方计价币分组进 {@link #regulatedTariffByCurrency()}）—— 它<b>照旧保留</b> （既有读口一字不改）；本参数是
   * P-T1b"<b>真收款</b>"之后的<b>逐层真实税项</b>（金额、币种、收款政府）， 两者是同一件事的"费率读数 / 真收账目"两面。
   *
   * <p>★ 旧签名保留并委托到本方法（{@code taxItems = List.of()}）⇒ 既有调用点/夹具零串改。
   *
   * @param taxItems 逐层税项（金额已经是该笔的买方支付币；空 = 本轮没有收税 ⇒ 读口空表）
   */
  public static MarketReport withRegulatedTariff(
      long day,
      MarketTrigger trigger,
      boolean carrierPresent,
      List<Fill> fills,
      List<Unfilled> unfilled,
      List<RouteUsage> routes,
      Map<CurrencyId, Long> freightPaidByCurrency,
      Map<CurrencyId, Long> freightUncollectedByCurrency,
      long scheduledLossMilli,
      long immediateFills,
      long crossRegionFills,
      PriceMode priceMode,
      List<PriceUpdate> priceUpdates,
      List<SellerOutcome> sellerOutcomes,
      List<BuyerOutcome> buyerOutcomes,
      List<CreditFill> creditFills,
      Map<Fill, Long> tariffByFill,
      FxRoundResult fx,
      List<TaxItem> taxItems) {
    MarketReport report =
        new MarketReport(
            day,
            trigger,
            carrierPresent,
            fills,
            unfilled,
            routes,
            freightPaidByCurrency,
            freightUncollectedByCurrency,
            scheduledLossMilli,
            immediateFills,
            crossRegionFills,
            priceMode,
            priceUpdates,
            sellerOutcomes,
            buyerOutcomes,
            creditFills,
            fx);
    if (taxItems != null && !taxItems.isEmpty()) {
      // ★ 保序冻结（逐票顺序 → 逐层顺序 → 逐政府规范序），且逐条判非 null：读数里不许出现"半个税项"。
      List<TaxItem> copy = new java.util.ArrayList<>(taxItems.size());
      for (TaxItem item : taxItems) {
        copy.add(Objects.requireNonNull(item, "taxItems 不得含 null"));
      }
      TAX_ITEMS_BY_REPORT.put(new IdentityKey(report), Collections.unmodifiableList(copy));
    }
    if (tariffByFill == null || tariffByFill.isEmpty() || report.fills.isEmpty()) {
      return report;
    }
    Map<Fill, Long> byFill = new LinkedHashMap<>();
    for (Map.Entry<Fill, Long> entry : tariffByFill.entrySet()) {
      Fill fill = entry.getKey();
      Long rate = entry.getValue();
      if (fill == null || rate == null || rate <= 0L || !report.fills.contains(fill)) {
        continue;
      }
      long tariff = fill.quantity() * rate / EconomyVocabulary.MILLI_PER_COMMODITY_UNIT;
      if (tariff <= 0L) {
        continue;
      }
      byFill.put(fill, tariff);
    }
    if (!byFill.isEmpty()) {
      REGULATED_TARIFF_BY_REPORT.put(new IdentityKey(report), byFill);
    }
    return report;
  }

  /**
   * ★★ <b>P-T1b：本轮逐层税项</b>（保序：逐票顺序 → 逐层顺序 → 逐政府规范序；没有收税 ⇒ 空表）。
   *
   * <p>★ 读口形状 = <b>层 / 金额 / 币种 / 收款政府</b>（另带商品与区）；这些项的总额就是"买方多付的部分"。
   */
  public List<TaxItem> taxItems() {
    List<TaxItem> items = TAX_ITEMS_BY_REPORT.get(new IdentityKey(this));
    return items == null ? List.of() : items;
  }

  /**
   * ★★ <b>P-T1b：税项总额（<b>按币分列</b>）</b>—— 就是"买方多付的那一部分"，也就是真进国库的总额。
   *
   * <pre>
   * 买方实付 = 货款 + 运费 + Σ税项      （货款仍全部进卖方、运费进承运方 ⇒ 差额只可能进国库，I-C1）
   * </pre>
   *
   * <p>★★ <b>为什么是逐币表</b>（I-C10）：多币世界里把不同币的税加起来就是把两种钱当一种（P-T4 的负向守卫 N5）。
   */
  public Map<CurrencyId, Long> taxByCurrency() {
    List<TaxItem> items = TAX_ITEMS_BY_REPORT.get(new IdentityKey(this));
    Map<CurrencyId, Long> totals = new LinkedHashMap<>();
    if (items != null) {
      for (TaxItem item : items) {
        totals.merge(item.currency(), item.amountMilli(), Math::addExact);
      }
    }
    return Collections.unmodifiableMap(totals);
  }

  /**
   * ★★ <b>P-T1b：逐（层 × 收款政府 × 币种）的税项汇总</b>（保序 = 首次出现序）—— §一.9 的"当日分层的税汇总"用它。
   *
   * <p>★ 键是 {@code (层, 政府, 币种)} 三元组：任一项不同就是不同的钱（禁跨币相加、禁把两个政府的税合成一条）。 ★ 空表 = 本轮没有收税（不是 0）。
   */
  public Map<TaxKey, Long> taxByLayerGovernmentCurrency() {
    List<TaxItem> items = TAX_ITEMS_BY_REPORT.get(new IdentityKey(this));
    Map<TaxKey, Long> totals = new LinkedHashMap<>();
    if (items != null) {
      for (TaxItem item : items) {
        totals.merge(
            new TaxKey(item.layer(), item.government(), item.currency()),
            item.amountMilli(),
            Math::addExact);
      }
    }
    return Collections.unmodifiableMap(totals);
  }

  /** ★★ <b>P-T1b：税项汇总的键</b>（层 × 收款政府 × 币种；三段都不同才是不同的钱）。 */
  public record TaxKey(MarketTaxLayer layer, String government, CurrencyId currency) {

    public TaxKey {
      Objects.requireNonNull(layer, "TaxKey.layer 不得为 null");
      if (government == null || government.isBlank()) {
        throw new IllegalArgumentException("TaxKey.government 不得为空白");
      }
      Objects.requireNonNull(currency, "TaxKey.currency 不得为 null");
    }
  }

  /** ★★ A2a：本轮的外汇读数（没有 ⇒ {@link FxRoundResult#none()}，不是 null；构造期已归一）。 */
  public FxRoundResult fx() {
    return fx;
  }

  /** ★★ <b>D-027：单 hex 即时成交笔数</b>（{@code immediate && !from.equals(to)}）—— 同一格不跨 hex，不计。 */
  public long immediateCrossHexFills() {
    long count = 0L;
    for (Fill fill : fills) {
      if (fill.immediate() && !fill.from().equals(fill.to())) {
        count++;
      }
    }
    return count;
  }

  /**
   * ★★ <b>D-027：单 hex 即时成交的损耗合计</b>（毫商品；{@code immediate && !from.equals(to)} 的 {@link
   * Fill#lossMilli()} 之和）。它是"区内跨 hex 物流成本"的唯一读数入口。
   */
  public long immediateCrossHexLossMilli() {
    long sum = 0L;
    for (Fill fill : fills) {
      if (fill.immediate() && !fill.from().equals(fill.to())) {
        sum += fill.lossMilli();
      }
    }
    return sum;
  }

  /**
   * ★★ <b>D-027：区级税费读数合计</b>（<b>按币分列</b>）—— 本批<b>只记读数，不搬钱</b>（收款方未定，P-T1 才定国库）。
   *
   * <p>★★ <b>P-T4：为什么是逐币表而不是一个 long</b>：税费按<b>卖方格计价币</b>（= 该笔成交的 {@link
   * Fill#unitCurrency()}）计价，多币世界里把不同币的税费加起来就是把两种钱当一种。⇒ 本读数回答"这笔货被抽了多少税、<b>什么币</b>"； "进哪个国库"是 P-T1
   * 的事。没有调控/没有成交 ⇒ <b>空表</b>（不是 0）。
   */
  public Map<CurrencyId, Long> regulatedTariffByCurrency() {
    Map<Fill, Long> byFill = REGULATED_TARIFF_BY_REPORT.get(new IdentityKey(this));
    Map<CurrencyId, Long> totals = new LinkedHashMap<>();
    if (byFill != null) {
      for (Map.Entry<Fill, Long> entry : byFill.entrySet()) {
        totals.merge(entry.getKey().unitCurrency(), entry.getValue(), Math::addExact);
      }
    }
    return Collections.unmodifiableMap(totals);
  }

  /** 没有任何市场活动的空报告。 */
  public static MarketReport empty(long day, MarketTrigger trigger, boolean carrierPresent) {
    return new MarketReport(
        day,
        trigger,
        carrierPresent,
        List.of(),
        List.of(),
        List.of(),
        Map.of(),
        Map.of(),
        0L,
        0L,
        0L,
        PriceMode.FIXED, // R3a：旧撮合的 priceMode() 是"默认固定价"（自适应开关出厂 false），此处逐值保留
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  /** 未成交原因分布（档位 → 条数；保序 = {@link MarketUnfilledReason} 的声明序）。 */
  public Map<MarketUnfilledReason, Long> unfilledReasonCounts() {
    Map<MarketUnfilledReason, Long> counts = new LinkedHashMap<>();
    for (Unfilled item : unfilled) {
      counts.merge(item.reason(), 1L, Long::sum);
    }
    return counts;
  }

  /**
   * ★★ <b>M0.3「物流缺口」的逐格读数</b>（M2.7 复评落点）：该格**买方侧**未成交、且原因属于 {@link
   * MarketUnfilledReason#logistics()} 的剩余量之和（毫商品）。
   *
   * <p>★ 口径：只看买方没买到的量（"需求被运力/路网挡住"）；卖方没卖掉的剩余不是物流缺口。没有市场、没开市、 或报告来自别格 ⇒ 0 ——
   * 这是"真的没有物流缺口"，不是"读不到"（读不到由调用方的 {@code unavailable} 具名）。
   */
  public long logisticsGapMilli(HexCoord hex) {
    Objects.requireNonNull(hex, "hex");
    long sum = 0L;
    for (Unfilled item : unfilled) {
      if (item.buyerSide() && item.hex().equals(hex) && item.reason().logistics()) {
        sum += item.quantity();
      }
    }
    return sum;
  }

  /**
   * ★ 一笔成交。{@code immediate == true} 时 {@code shipmentId} 为空串（区内即时，没有在途批次）； 否则 {@code shipmentId} 是
   * {@code EconomyData.shipments} 里的键。
   *
   * <p>★★ <b>P-T4：每一栏钱都带币种列</b>（I-C10 读口不混币）—— 异币成交时"单价"与"实付"是<b>两种钱</b>，
   * 读口必须能分别回答"单价是哪一币、实付是哪一币、运费是哪一币"，且<b>永远不把两栏相加</b>：
   *
   * <ul>
   *   <li>{@link #unitCurrency()}：{@link #unitPriceMilli()} 的币 = <b>卖方格计价币</b>（{@code
   *       sell.market.numeraire()}；卖方那个市场的计价币，不是买方的）；
   *   <li>{@link #paymentCurrency()}：{@link #goodsPaymentMilli()} 的币 = <b>买方支付币</b>（{@code
   *       buy.currency}）—— 货款的钱腿就铸在这种钱上；
   *   <li>{@link #freightCurrency()}：{@link #freightPerUnitMilli()} / {@link #freightMilli()} 的币 ——
   *       <b>恒等于 {@link #paymentCurrency()}</b>（运费腿同样铸在买方支付币上，唯一拼写点 {@code
   *       MarketSettlement.executeTrade}）。★ 它是一条<b>具名派生列</b>，不是第二个真值：写成"运费币"是为了让读口能直接回答"运费是哪一币"，
   *       而不必去猜。
   * </ul>
   *
   * <p>★ 相加的唯一合法形态见 {@link #landedUnitPriceMilli()}：<b>只有单价币 == 运费币时</b>"单价 + 单位运费"才有定义。
   *
   * @param unitCurrency 单价币（卖方格计价币）
   * @param unitPriceMilli 成交单价（毫单价币 / 商品单位；跨区时 = 卖方格参考价，**不含**运费）
   * @param paymentCurrency 实付币（买方支付币）
   * @param goodsPaymentMilli 货款（毫实付币）
   * @param freightPerUnitMilli 单位运费（毫运费币 / 商品单位；无承运人时为 0）
   * @param freightMilli 运费（毫运费币；无承运人时为 0）
   * @param arrivalTick 到货世界日（区内即时 = 成交日）
   * @param lossMilli ★ M2.7：本票按路线损耗率**预排**的在途损耗（毫商品；公式与到货日的扣减逐字同源）；区内即时 = 0
   */
  public record Fill(
      HexCoord from,
      HexCoord to,
      CommodityId commodity,
      ActorRef seller,
      ActorRef buyer,
      long quantity,
      CurrencyId unitCurrency,
      long unitPriceMilli,
      CurrencyId paymentCurrency,
      long goodsPaymentMilli,
      long freightPerUnitMilli,
      long freightMilli,
      long arrivalTick,
      boolean immediate,
      String shipmentId,
      long lossMilli) {

    public Fill {
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(to, "to");
      Objects.requireNonNull(commodity, "commodity");
      Objects.requireNonNull(seller, "seller");
      Objects.requireNonNull(buyer, "buyer");
      Objects.requireNonNull(unitCurrency, "unitCurrency");
      Objects.requireNonNull(paymentCurrency, "paymentCurrency");
      Objects.requireNonNull(shipmentId, "shipmentId");
    }

    /** ★★ P-T4：运费币 = {@link #paymentCurrency()}（运费腿铸在买方支付币上；见 record 注）。 */
    public CurrencyId freightCurrency() {
      return paymentCurrency;
    }

    /**
     * ★★ P-T4：本笔的"单价 + 单位运费"（毫/商品单位）—— <b>只在 {@link #unitCurrency()} == {@link #freightCurrency()}
     * 时有定义</b>（同一种钱相加）。
     *
     * <p>★ 异币成交（卖方币 ≠ 买方币）时两栏是<b>两种钱</b>：本方法返回 {@link OptionalLong#empty()}， 调用方必须<b>具名排除</b>（照
     * {@code EnterpriseProfitBook.addRevenue} 的先例），<b>不得</b>自己相加。 单币世界两栏恒相同 ⇒ 返回值与本批之前逐值相同。
     */
    public OptionalLong landedUnitPriceMilli() {
      if (!unitCurrency.equals(paymentCurrency)) {
        return OptionalLong.empty();
      }
      return OptionalLong.of(unitPriceMilli + freightPerUnitMilli);
    }
  }

  /**
   * ★ 一笔未成交剩余。{@code buyerSide == true} 读作"买方没买到"，{@code false} 读作"卖方没卖掉"。
   *
   * <p>★ M2.7：{@code hex} = 这一单所在的格（买方 = 收货格、卖方 = 发货格）—— 逐区读数需要它把剩余归到区， 不能靠"猜 actor
   * 在哪"（那会是同一事实的第二处拼写）。
   *
   * <p>★★ <b>F-2（2026-10-10）：{@code logisticsDetail} = "卡在运力"这一档的**可分辨归因**</b>—— 改前"没有服务卖"与"运力不足"同为
   * {@link MarketUnfilledReason#LOGISTICS_CAPACITY} ⇒ 公开读数不可分辨（而处置完全不同： 前者等下一轮产出、后者退单/等运力）。它装的是
   * {@code MerchantCapacityPool.LogisticsBlock} 的具名字面量； {@code null} = 这一笔不是卡在运力（或没有具名归因）。★
   * 它只作读数：不改任何判据、不进账本、不落状态。
   *
   * @param logisticsDetail 卡在运力时的具名归因档（见上；{@code null} = 不适用）
   */
  public record Unfilled(
      ActorRef actor,
      boolean buyerSide,
      CommodityId commodity,
      long quantity,
      MarketUnfilledReason reason,
      HexCoord hex,
      String logisticsDetail) {

    public Unfilled {
      Objects.requireNonNull(actor, "actor");
      Objects.requireNonNull(commodity, "commodity");
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(hex, "hex");
    }

    /**
     * ★ <b>F-2 之前的 6 参入口</b>（夹具/旧读者）：{@code logisticsDetail = null}（"没有具名归因"）。
     *
     * <p>★ 保留它是为了让**既有夹具**不必为一条纯读数改签名（新代码请用规范构造器）。
     */
    public Unfilled(
        ActorRef actor,
        boolean buyerSide,
        CommodityId commodity,
        long quantity,
        MarketUnfilledReason reason,
        HexCoord hex) {
      this(actor, buyerSide, commodity, quantity, reason, hex, null);
    }
  }

  /**
   * ★★ <b>M2.6 自适应模式的一条改价记录</b>（逐集散节点 × 商品）：{@code nextPriceMilli} 从下一轮起生效。
   *
   * <p>★ 它只服务读数与审计（固定模式恒为空表）；价格真值始终在 {@code EconomyData.markets} 里。
   */
  public record PriceUpdate(
      HexCoord anchor, CommodityId commodity, long previousPriceMilli, long nextPriceMilli) {

    public PriceUpdate {
      Objects.requireNonNull(anchor, "anchor");
      Objects.requireNonNull(commodity, "commodity");
      if (previousPriceMilli < 0L || nextPriceMilli < 0L) {
        throw new IllegalArgumentException(
            "PriceUpdate 的两个价格都不得为负（0 = 明确免费交易）: " + previousPriceMilli + " -> " + nextPriceMilli);
      }
    }
  }

  /**
   * ★ 一条路线的运力用量与瓶颈标志。{@code bottleneck == true} = 本轮"买卖两边都还有剩余、且运力窗口被用满" —— 这正是"有货、有路、运力不足 ⇒
   * 城市仍可能缺粮"的可读信号。
   */
  public record RouteUsage(
      HexCoord from,
      HexCoord to,
      CommodityId commodity,
      long capacityPerWindow,
      long costPerUnit,
      long used,
      long demandMilli,
      long supplyMilli,
      boolean bottleneck) {

    public RouteUsage {
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(to, "to");
      Objects.requireNonNull(commodity, "commodity");
    }
  }

  /**
   * ★★ <b>D-030：一笔市场信用成交（债务来源读数）</b>。
   *
   * <p>★★ <b>量纲</b>：{@code quantityMilli} = 该合同本笔**本金**，单位由 {@code unit} 给出 —— {@code
   * DebtUnit.Money} 时是毫计价货币（借银买货），{@code DebtUnit.Commodity} 时是毫商品（借实物）。{@code commodity}
   * 是这笔信用服务的商品：借货币买货 ⇒ 买到的商品；借实物 ⇒ 借出的商品。{@code hex} = 卖方槽位所在格（区内信用成交的 货物起点；单区世界里就是区内某格）。
   *
   * <p>★ <b>与 {@link #fills()} 的关系</b>：借货币买货对卖方仍是一笔现金销售 ⇒ 同一笔同时写一条普通 {@code Fill}
   * （钱货）与本条（债务来源）；借实物没有货币腿 ⇒ 只写本条，{@code fills} 不伪造一条 0 货款销售。因此"现金成交"与 "信用成交"的分辨点是本表：出现在这里的 {@code
   * (borrower, commodity)} 对应的那笔成交即信用成交。
   *
   * @param hex 卖方槽位所在格（区内信用的货物起点）
   * @param commodity 本笔信用服务的商品（买到的或借出的）
   * @param borrower 债务人 / 买方主体
   * @param lenderOrSeller 债权人 / 卖方主体（借货币 = 出借人；借实物 = 卖家）
   * @param quantityMilli 本笔本金（单位 = {@code unit}；毫钱 或 毫商品）
   * @param unit 债务计量单位（{@code Money} / {@code Commodity}）
   * @param debtId 累加后的债务合同稳定身份（同一四元组跨笔恒同一条）
   * @param ratePerMille 每周期利率（千分数；本批 = {@code BORROW_RATE_PER_MILLE_PER_CYCLE}）
   * @param dueCycle 到期周期（本批 = 当前周期 + 1）
   */
  public record CreditFill(
      HexCoord hex,
      CommodityId commodity,
      ActorRef borrower,
      ActorRef lenderOrSeller,
      long quantityMilli,
      DebtUnit unit,
      DebtContractId debtId,
      long ratePerMille,
      long dueCycle) {

    public CreditFill {
      Objects.requireNonNull(hex, "hex");
      Objects.requireNonNull(commodity, "commodity");
      Objects.requireNonNull(borrower, "borrower");
      Objects.requireNonNull(lenderOrSeller, "lenderOrSeller");
      Objects.requireNonNull(unit, "unit");
      Objects.requireNonNull(debtId, "debtId");
      if (quantityMilli <= 0L) {
        throw new IllegalArgumentException("CreditFill.quantityMilli 必须 > 0: " + quantityMilli);
      }
      if (ratePerMille < 0L) {
        throw new IllegalArgumentException("CreditFill.ratePerMille 不得为负: " + ratePerMille);
      }
      if (dueCycle < 0L) {
        throw new IllegalArgumentException("CreditFill.dueCycle 不得为负: " + dueCycle);
      }
    }
  }

  /**
   * ★★ <b>S3：逐卖方的只读结果</b>（不落盘；L3 读数组件按区/商品聚合）。
   *
   * <p>★★ <b>量纲</b>：{@code unitCostEstimateMilli} 与 {@code freightPerUnitMilli} 的单位是 {@link
   * ProducerCostBook#ESTIMATE_SCALE 千分之一毫银 / 单位规模}（唯一拼写点在成本簿；这样"劳动成本"不会被整数除法 截成 0）。{@code
   * unitPriceMilli} 仍是成交口径的毫银 / 商品单位（参考价）。
   *
   * @param roundDay 本轮世界日
   * @param actor 卖方主体
   * @param unitId 认出的生产单元（认不出 ⇒ empty，成本按未知档排后；R3B.2 起归属到 unit）
   * @param hex 卖单所在格（发货格）
   * @param commodity 商品
   * @param offeredQty 挂单量（报价时的 {@code sellable}；毫单位）
   * @param filledQty 已成交（毫单位）
   * @param unfilledQty 未成交剩余（毫单位）
   * @param unitPriceMilli 本格参考价（毫银 / 商品单位；成本不改变成交单价）
   * @param unitCostEstimateMilli 单位成本估计（千分之一毫银 / 规模；缺价按 0 计但 {@code priceMissing=true}）
   * @param freightPerUnitMilli 卖方承担/发生的单位运费（本批恒 0：跨区运费由买方付，见市场类注）
   * @param bestAcceptedLandedPriceMilli 本轮同商品已成交的最低到货价（无成交 ⇒ empty，绝不填 0）
   * @param costRank 同商品同区按到货成本升序的名次（0 起；未知成本排在已知之后）
   * @param unfilledReason 未成交原因（卖光/无剩余 ⇒ empty）
   * @param outcompetedByActorCount 更便宜且真的卖掉的卖方家数（{@code OUTCOMPETED} 的证据；不是估算）
   * @param outcompetedQty 被更便宜卖方挤掉的数量（= 本槽未成交剩余，当且仅当原因是 {@code OUTCOMPETED}）
   * @param priceMissing 投入/劳动有一项缺参考价（读数必须能标 {@code PRICE_MISSING}）
   * @param costKnown 认出了经营配方（false ⇒ 成本未知，排序排在已知成本之后）
   */
  public record SellerOutcome(
      long roundDay,
      ActorRef actor,
      Optional<ProductionUnitId> unitId,
      HexCoord hex,
      CommodityId commodity,
      long offeredQty,
      long filledQty,
      long unfilledQty,
      long unitPriceMilli,
      long unitCostEstimateMilli,
      long freightPerUnitMilli,
      OptionalLong bestAcceptedLandedPriceMilli,
      int costRank,
      Optional<MarketUnfilledReason> unfilledReason,
      long outcompetedByActorCount,
      long outcompetedQty,
      boolean priceMissing,
      boolean costKnown) {

    public SellerOutcome {
      Objects.requireNonNull(actor, "actor");
      Objects.requireNonNull(unitId, "unitId");
      Objects.requireNonNull(hex, "hex");
      Objects.requireNonNull(commodity, "commodity");
      Objects.requireNonNull(bestAcceptedLandedPriceMilli, "bestAcceptedLandedPriceMilli");
      Objects.requireNonNull(unfilledReason, "unfilledReason");
      if (offeredQty < 0L || filledQty < 0L || unfilledQty < 0L || filledQty + unfilledQty < 0L) {
        throw new IllegalArgumentException(
            "SellerOutcome 的数量不得为负: " + offeredQty + "/" + filledQty + "/" + unfilledQty);
      }
      if (costRank < 0 || outcompetedByActorCount < 0L || outcompetedQty < 0L) {
        throw new IllegalArgumentException(
            "SellerOutcome 的排名/计数不得为负: " + costRank + "/" + outcompetedByActorCount);
      }
    }
  }

  /**
   * ★★ <b>S3：逐买方的只读结果</b>（不落盘；L3 读数组件按区/商品聚合）。
   *
   * @param roundDay 本轮世界日
   * @param actor 买方主体
   * @param household 家户（经营者 ⇒ empty）
   * @param hex 收货格
   * @param commodity 商品
   * @param stockOnHandMilli 可动用库存（持有 − 冻结；毫单位）
   * @param stockCoverDays 库存覆盖天数 = 库存 ÷ 日需求（日需求 0 ⇒ empty，不填 0 冒充"没库存"）
   * @param gapQty 缺口 = 目标 − 自有 − 在途（≤ 0 时计 {@code STOCK_SUFFICIENT}；毫单位）
   * @param desiredQty 目标（家户 = 生活保留、经营者 = 必要投入；毫单位）
   * @param spendableMoneyMilli 可花货币（持有 − 冻结；毫币）
   * @param affordableQty 按参考价折算的买得起量（毫单位）
   * @param orderedQty 本槽挂单量（= 0 时看原因：库存够 / 没钱）
   * @param filledQty 已成交（毫单位）
   * @param unfilledReason 未成交原因（买满 ⇒ empty；库存已足 ⇒ {@code STOCK_SUFFICIENT}）
   */
  public record BuyerOutcome(
      long roundDay,
      ActorRef actor,
      Optional<HouseholdId> household,
      HexCoord hex,
      CommodityId commodity,
      long stockOnHandMilli,
      OptionalLong stockCoverDays,
      long gapQty,
      long desiredQty,
      long spendableMoneyMilli,
      long affordableQty,
      long orderedQty,
      long filledQty,
      Optional<MarketUnfilledReason> unfilledReason) {

    public BuyerOutcome {
      Objects.requireNonNull(actor, "actor");
      Objects.requireNonNull(household, "household");
      Objects.requireNonNull(hex, "hex");
      Objects.requireNonNull(commodity, "commodity");
      Objects.requireNonNull(stockCoverDays, "stockCoverDays");
      Objects.requireNonNull(unfilledReason, "unfilledReason");
      if (stockOnHandMilli < 0L
          || gapQty < 0L
          || desiredQty < 0L
          || spendableMoneyMilli < 0L
          || affordableQty < 0L
          || orderedQty < 0L
          || filledQty < 0L) {
        throw new IllegalArgumentException(
            "BuyerOutcome 的库存/数量不得为负: " + stockOnHandMilli + "/" + gapQty + "/" + desiredQty);
      }
    }
  }
}
