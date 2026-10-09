package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.market.MarketTaxLayer;
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
import java.util.Set;

/**
 * ★★ <b>M-C：商号利润读数（每轮算出来的读数，**不落状态**）</b>—— 设计书 §12 H-A/H-F、§12.3、计划 §2.4 M1、§7 Q-23。
 *
 * <pre>
 * 自运自货（H-A/H-G：承运方 ∧ 货主都是纯商号）⇒ 该笔不产生运费 ⇒ 纯商号的**自营账**里没有运费腿
 *   利润 = 差价收入 − 劳动力成本 − 本钱占用 − 损耗 − 出口税 − 进口税 − 区内税 −（运费支出）
 * 其余情形（H-F：顺便跑商承担的运力、或纯商号为非商号的货承运）⇒ 运费独立计算 ⇒ 另有运费收入/支出腿
 *   利润 = 上述 ＋ 运费收入
 * ⇒ 它是**每轮现算的读数**：进 INFO 汇总 + TRACE 逐户，**不落任何状态**（Q-23 默认；旧 MerchantFirm.lastProfitMilli
 *   已随商号行退役，本类不把它请回来）。
 * </pre>
 *
 * <p>★★ <b>逐腿的口径（每一条都指得出数据来源）</b>：
 *
 * <pre>
 * 差价收入     Σ 作为卖方的货款实收 − Σ 作为买方的货款实付（逐币分列；同一笔 MARKET_TRADE 的两端）
 * 劳动力成本   该户本轮**承运工作量**折算的劳动 × 具名小时机会成本
 *              劳动小时 = ⌈耗用运力 × 该户劳动投入 ÷ 该户运力预算⌉（劳动的份额；数据来自运力池）
 *              小时机会成本 = {@link #LABOR_OPPORTUNITY_COST_MICRO_PER_HOUR} 微毫银/毫小时（理由见下）
 * 本钱占用     该户作为买方**在途**占款的机会成本 = Σ 支出 × 在途天数 × 既有市场利率 ÷ (1000 × 周期天数)
 *              ★ 即时成交（0 天）⇒ 0；★ 世界利率 20‰/周期 ⇒ 本腿在毫级通常为 0（读数按微毫列出，不静默丢）
 * 损耗         该户作为买方在本轮**实际计量**的实物损耗 × 该笔买方单价（毫）
 * 三层税       逐层读取（出口 / 进口 / 区内），层与币都不合并（I-C3/I-C10）
 * 运费收入     **只对顺便跑商**计入（H-F）；纯商号按其口径不收运费（H-A/H-B），若它当真收到（边界情形）具名列出
 * 运费支出     该户作为买方实付的运费（逐币；★ 冻结算式里没写这一腿，但漏掉会把付了运费的户读高，见 J-3）
 * </pre>
 *
 * <p>★★ <b>劳动机会成本的口径（具名常量 + 理由）</b>：{@link #LABOR_OPPORTUNITY_COST_MICRO_PER_HOUR} = 10 微毫银/毫小时。
 * 推导：一个人一个周期的口粮 = 10,000 毫粮 = 10 毫银（{@code EconomyVocabulary}），同期劳动投入 ≈ 120 天 × 8,000 毫小时 = 960,000
 * 毫小时 ⇒ 10 毫银 ÷ 960,000 ≈ 10.4 微毫银/毫小时。★ 它正是用户裁定的前提"家户有**剩余**劳动力"
 * ——剩余劳动的机会成本低（回家种地只能多打一份自己的口粮），所以跑商对"顺便"的家户才有意义。
 *
 * <p>★★ <b>只读输入、逐轮瞬态、不落状态、无随机（I7）</b>：全部累加器都是保序 {@link LinkedHashMap}， 输出按家户 id
 * 升序（内容的纯函数）；本类<b>不改任何余额、不铸转移、不影响任何判据</b>。
 */
final class MerchantProfitBook {

  private static final org.slf4j.Logger LOG = EconomyLog.market();

  private static final org.slf4j.Logger TRACE = EconomyLog.trace();

  /** 微 / 毫（与 {@code MarketTaxBook.MICRO_PER_MILLI} 同值；本类不为此引入依赖）。 */
  static final long MICRO_PER_MILLI = 1_000L;

  /**
   * ★ 劳动机会成本（微毫银 / 毫小时）：{@code 10} —— 剩余劳动力"回家种地"能赚到的量级（推导见类注）。
   *
   * <p>★ 为什么不是“产业工资档”（{@code FIXED_MONEY_WAGE} = 1,000 毫/周期·规模）：那是**被雇的劳动**的价格，
   * 而跑商用的是家户**自己的剩余劳动**；用工资档会把"剩余劳动"当成"雇一个人"，与用户裁定 的前提相反。
   */
  static final long LABOR_OPPORTUNITY_COST_MICRO_PER_HOUR = 10L;

  /** 逐户账目（保序；键 = 家户 id）。 */
  private final Map<HouseholdId, Account> byHousehold = new LinkedHashMap<>();

  /** 一条差价腿（卖方实收 / 买方实付；逐币累加）。 */
  void recordSale(HouseholdId seller, CurrencyId currency, long goodsPaymentMilli) {
    if (seller == null || currency == null || goodsPaymentMilli <= 0L) {
      return;
    }
    account(seller).marginByCurrency.merge(currency, goodsPaymentMilli, Math::addExact);
  }

  /** 一条差价腿（买方实付；逐币累加）。 */
  void recordPurchase(HouseholdId buyer, CurrencyId currency, long goodsPaymentMilli) {
    if (buyer == null || currency == null || goodsPaymentMilli <= 0L) {
      return;
    }
    account(buyer).marginByCurrency.merge(currency, -goodsPaymentMilli, Math::addExact);
  }

  /** 买方实付的一笔运费（逐币）。 */
  void recordFreightPaid(HouseholdId buyer, CurrencyId currency, long amountMilli) {
    if (buyer == null || currency == null || amountMilli <= 0L) {
      return;
    }
    account(buyer).freightPaidByCurrency.merge(currency, amountMilli, Math::addExact);
  }

  /**
   * 承运方实收的一笔运费（逐币）—— **按事实进利润的"运费收入"腿**。
   *
   * <p>★★ <b>与冻结书的一处解释性偏离（见实现账本 D-1）</b>：冻结写法只给"顺便跑商"列运费收入腿。但在本批落地的 H-A/H-F
   * 口径下（自运自货才免运费），**纯商号也会因承运非商号的货而收到运费** —— 把它从利润里抹掉会把真实收入 读丢（用户要求"得算利润"）。⇒
   * 本腿<b>按事实计入</b>；纯商号"自运自货"那一部分本来就是 0（免运费） ⇒ 冻结算式在**自营场景**逐值成立。
   */
  void recordFreightEarned(HouseholdId carrier, CurrencyId currency, long amountMilli) {
    if (carrier == null || currency == null || amountMilli <= 0L) {
      return;
    }
    account(carrier).freightEarnedByCurrency.merge(currency, amountMilli, Math::addExact);
  }

  /** 买方实付的一层税（逐层 × 逐币；层与币都不合并）。 */
  void recordTax(HouseholdId buyer, MarketTaxLayer layer, CurrencyId currency, long amountMilli) {
    if (buyer == null || layer == null || currency == null || amountMilli <= 0L) {
      return;
    }
    account(buyer)
        .taxByLayer
        .computeIfAbsent(layer, ignored -> new LinkedHashMap<>())
        .merge(currency, amountMilli, Math::addExact);
  }

  /** 买方本轮实际计量的实物损耗，按该笔买方单价折成的价值（逐币）。 */
  void recordLoss(HouseholdId buyer, CurrencyId currency, long valueMilli) {
    if (buyer == null || currency == null || valueMilli <= 0L) {
      return;
    }
    account(buyer).lossValueByCurrency.merge(currency, valueMilli, Math::addExact);
  }

  /** 买方在途占款的机会成本（**微毫**；毫级取整在 {@link Account#profitByCurrency} 里做）。 */
  void recordCapitalOccupancy(HouseholdId buyer, CurrencyId currency, long amountMicroMilli) {
    if (buyer == null || currency == null || amountMicroMilli <= 0L) {
      return;
    }
    account(buyer)
        .capitalOccupancyMicroByCurrency
        .merge(currency, amountMicroMilli, Math::addExact);
  }

  /**
   * ★★ <b>一条承运条目（= 一次跑商）</b>：承运方的劳动成本 + 工具消耗 + （纯商号的）被豁免运费读数。
   *
   * @param carrier 提供运力的家户
   * @param pureMerchant 该户是不是纯商号（H-2；只进读数标签与 INFO 计数 —— 运费收入腿按事实计入，见 {@link #recordFreightEarned}）
   * @param currency 本条的金额币 = 买方支付币（运费腿就铸在它上面）
   * @param laborHoursMilli 本条折算的劳动投入（毫小时）
   * @param toolMilli 本条**实扣**的工具（毫工具；0 = 没扣到/无工具）
   * @param waivedFreightMilli 本条按 M-A2 口径本应付、但因 H-A 免掉的运费（毫；0 = 未免）
   */
  void recordRun(
      HouseholdId carrier,
      boolean pureMerchant,
      CurrencyId currency,
      long laborHoursMilli,
      long toolMilli,
      long waivedFreightMilli) {
    if (carrier == null || currency == null) {
      return;
    }
    Account account = account(carrier);
    account.pureMerchant = pureMerchant;
    account.runs++;
    if (laborHoursMilli > 0L) {
      account.laborHoursMilli = Math.addExact(account.laborHoursMilli, laborHoursMilli);
      long micro = Math.multiplyExact(laborHoursMilli, LABOR_OPPORTUNITY_COST_MICRO_PER_HOUR);
      account.laborCostMicroByCurrency.merge(currency, micro, Math::addExact);
    }
    if (toolMilli > 0L) {
      account.toolBurnMilli = Math.addExact(account.toolBurnMilli, toolMilli);
    }
    if (waivedFreightMilli > 0L) {
      account.waivedFreightByCurrency.merge(currency, waivedFreightMilli, Math::addExact);
    }
  }

  /**
   * ★★ <b>轮末汇总</b>（§一.9：INFO = 商号门槛与利润汇总；TRACE = 逐户逐腿）。
   *
   * @param day 世界日（所有行都带它 —— 本类只被市场轮调用）
   * @param merchants <b>读数范围</b> = 本轮的**跑商家户**（运力池成员；判据 {@code MerchantIdentity.selectsMerchant}）。
   *     ★ 本类在成交点记录了**所有**家户的腿（记录与成交同址、不判身份），但**只有商号的腿产出读数**： 非商号家户的买卖不是"商号利润"，不该进这份读数。★
   *     空集（世界没有跑商家户）⇒ 一行不打（I-C2）。
   */
  void logRoundSummary(long day, Set<HouseholdId> merchants) {
    if (merchants == null || merchants.isEmpty() || byHousehold.isEmpty()) {
      return; // 没有跑商家户 ⇒ 读数不产出（缺省语义中性）
    }
    List<HouseholdId> households = sortedMerchants(merchants);
    if (households.isEmpty()) {
      return;
    }
    long pureMerchants = 0L;
    long incidental = 0L;
    long runs = 0L;
    long toolBurnMilli = 0L;
    Map<CurrencyId, Long> profitTotal = new LinkedHashMap<>();
    Map<CurrencyId, Long> waivedTotal = new LinkedHashMap<>();
    Map<CurrencyId, Long> freightEarnedTotal = new LinkedHashMap<>();
    for (HouseholdId household : households) {
      Account account = byHousehold.get(household);
      if (account.pureMerchant) {
        pureMerchants++;
      } else {
        incidental++;
      }
      runs = Math.addExact(runs, account.runs);
      toolBurnMilli = Math.addExact(toolBurnMilli, account.toolBurnMilli);
      for (Map.Entry<CurrencyId, Long> leg : account.profitByCurrency().entrySet()) {
        profitTotal.merge(leg.getKey(), leg.getValue(), Math::addExact);
      }
      for (Map.Entry<CurrencyId, Long> leg : account.waivedFreightByCurrency.entrySet()) {
        waivedTotal.merge(leg.getKey(), leg.getValue(), Math::addExact);
      }
      for (Map.Entry<CurrencyId, Long> leg : account.freightEarnedByCurrency.entrySet()) {
        freightEarnedTotal.merge(leg.getKey(), leg.getValue(), Math::addExact);
      }
    }
    if (LOG.isInfoEnabled()) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "MERCHANT_PROFIT_TOTAL",
                  EconomyLogSource.ECONOMY_ORGANIZATION,
                  "day",
                  day,
                  "households",
                  households.size(),
                  "recordedHouseholds",
                  byHousehold.size(),
                  "pureMerchants",
                  pureMerchants,
                  "incidentalCarriers",
                  incidental,
                  "runs",
                  runs,
                  "toolBurnMilli",
                  toolBurnMilli,
                  "toolMilliPerHaul",
                  MerchantHaul.TOOL_MILLI_PER_HAUL,
                  "profitByCurrency",
                  Collections.unmodifiableMap(profitTotal),
                  "freightEarnedByCurrency",
                  Collections.unmodifiableMap(freightEarnedTotal),
                  "waivedFreightByCurrency",
                  Collections.unmodifiableMap(waivedTotal)));
    }
    if (TRACE.isTraceEnabled()) {
      for (HouseholdId household : households) {
        Account account = byHousehold.get(household);
        EventLog.channel(TRACE)
            .trace(
                LogEvent.of(
                    "MERCHANT_PROFIT_HOUSEHOLD",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    day,
                    "household",
                    household.value(),
                    "pureMerchant",
                    account.pureMerchant,
                    "runs",
                    account.runs,
                    "marginByCurrency",
                    Collections.unmodifiableMap(new LinkedHashMap<>(account.marginByCurrency)),
                    "freightEarnedByCurrency",
                    Collections.unmodifiableMap(
                        new LinkedHashMap<>(account.freightEarnedByCurrency)),
                    "freightPaidByCurrency",
                    Collections.unmodifiableMap(new LinkedHashMap<>(account.freightPaidByCurrency)),
                    "laborCostByCurrency",
                    account.laborCostByCurrency(),
                    "lossValueByCurrency",
                    Collections.unmodifiableMap(new LinkedHashMap<>(account.lossValueByCurrency)),
                    "capitalOccupancyMicroByCurrency",
                    Collections.unmodifiableMap(
                        new LinkedHashMap<>(account.capitalOccupancyMicroByCurrency)),
                    "taxByLayer",
                    account.taxReadout(),
                    "toolBurnMilli",
                    account.toolBurnMilli,
                    "laborHoursMilli",
                    account.laborHoursMilli,
                    "waivedFreightByCurrency",
                    Collections.unmodifiableMap(
                        new LinkedHashMap<>(account.waivedFreightByCurrency)),
                    "profitByCurrency",
                    account.profitByCurrency()));
      }
    }
  }

  /** 取（或建）一户的账目。 */
  private Account account(HouseholdId household) {
    Objects.requireNonNull(household, "household");
    return byHousehold.computeIfAbsent(household, ignored -> new Account());
  }

  /** 有读数的**商号**按 id 升序（I7：内容的纯函数，不读插入序/哈希序；范围外的一律不出读数）。 */
  private List<HouseholdId> sortedMerchants(Set<HouseholdId> merchants) {
    List<HouseholdId> households = new ArrayList<>(merchants);
    households.retainAll(byHousehold.keySet());
    households.sort(Comparator.comparing(HouseholdId::value));
    return households;
  }

  /** 一个家户本轮的全部腿（可变累加器；只在协调器单线程路径上写，与 {@code fills}/{@code taxItems} 同纪律）。 */
  private static final class Account {

    /** 差价收入（逐币；+ 卖方实收 / − 买方实付）。 */
    private final Map<CurrencyId, Long> marginByCurrency = new LinkedHashMap<>();

    /** 运费收入（逐币；按事实计入利润 —— 见 {@link #recordFreightEarned} 的口径注）。 */
    private final Map<CurrencyId, Long> freightEarnedByCurrency = new LinkedHashMap<>();

    /** 运费支出（逐币；买方实付）。 */
    private final Map<CurrencyId, Long> freightPaidByCurrency = new LinkedHashMap<>();

    /** 劳动成本（逐币；内部按**微毫**累加，输出时除以 1000）。 */
    private final Map<CurrencyId, Long> laborCostMicroByCurrency = new LinkedHashMap<>();

    /** 在途占款的机会成本（逐币；**微毫**）。 */
    private final Map<CurrencyId, Long> capitalOccupancyMicroByCurrency = new LinkedHashMap<>();

    /** 损耗价值（逐币；毫）。 */
    private final Map<CurrencyId, Long> lossValueByCurrency = new LinkedHashMap<>();

    /** 三层税（层 → 币 → 毫）。 */
    private final Map<MarketTaxLayer, Map<CurrencyId, Long>> taxByLayer = new LinkedHashMap<>();

    /** 被免掉的运费读数（逐币；H-A 的可见证据，不是利润腿）。 */
    private final Map<CurrencyId, Long> waivedFreightByCurrency = new LinkedHashMap<>();

    /** 该户是不是纯商号（H-2；由第一条承运条目定，同轮不变 —— 位置在本轮内不改）。 */
    private boolean pureMerchant;

    /** 本轮的承运条目数（= 跑商次数）。 */
    private long runs;

    /** 本轮折算的承运劳动（毫小时）。 */
    private long laborHoursMilli;

    /** 本轮实扣的工具（毫工具；V-22 的商品账消耗读数）。 */
    private long toolBurnMilli;

    /** 劳动成本（毫；微毫 ÷ 1000，向下取整 —— 毫级以下的零头只留在微毫读数里）。 */
    private Map<CurrencyId, Long> laborCostByCurrency() {
      Map<CurrencyId, Long> out = new LinkedHashMap<>();
      for (Map.Entry<CurrencyId, Long> leg : laborCostMicroByCurrency.entrySet()) {
        out.put(leg.getKey(), leg.getValue() / MICRO_PER_MILLI);
      }
      return Collections.unmodifiableMap(out);
    }

    /** 三层税读数（层 → 币 → 毫；保序）。 */
    private Map<MarketTaxLayer, Map<CurrencyId, Long>> taxReadout() {
      Map<MarketTaxLayer, Map<CurrencyId, Long>> out = new LinkedHashMap<>();
      for (Map.Entry<MarketTaxLayer, Map<CurrencyId, Long>> layer : taxByLayer.entrySet()) {
        out.put(layer.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(layer.getValue())));
      }
      return Collections.unmodifiableMap(out);
    }

    /** 本户本轮逐币利润（唯一算式；纯商号不加运费收入腿，顺便跑商加）。 */
    private Map<CurrencyId, Long> profitByCurrency() {
      Map<CurrencyId, Long> out = new LinkedHashMap<>();
      for (Map.Entry<CurrencyId, Long> leg : marginByCurrency.entrySet()) {
        out.put(leg.getKey(), leg.getValue());
      }
      for (Map.Entry<CurrencyId, Long> leg : freightEarnedByCurrency.entrySet()) {
        out.merge(leg.getKey(), leg.getValue(), Math::addExact);
      }
      for (Map.Entry<CurrencyId, Long> leg : freightPaidByCurrency.entrySet()) {
        out.merge(leg.getKey(), -leg.getValue(), Math::addExact);
      }
      for (Map.Entry<CurrencyId, Long> leg : laborCostByCurrency().entrySet()) {
        out.merge(leg.getKey(), -leg.getValue(), Math::addExact);
      }
      for (Map.Entry<CurrencyId, Long> leg : capitalOccupancyMicroByCurrency.entrySet()) {
        out.merge(leg.getKey(), -(leg.getValue() / MICRO_PER_MILLI), Math::addExact);
      }
      for (Map.Entry<CurrencyId, Long> leg : lossValueByCurrency.entrySet()) {
        out.merge(leg.getKey(), -leg.getValue(), Math::addExact);
      }
      for (Map<CurrencyId, Long> layer : taxByLayer.values()) {
        for (Map.Entry<CurrencyId, Long> leg : layer.entrySet()) {
          out.merge(leg.getKey(), -leg.getValue(), Math::addExact);
        }
      }
      Map<CurrencyId, Long> frozen = new LinkedHashMap<>();
      for (Map.Entry<CurrencyId, Long> leg : out.entrySet()) {
        frozen.put(leg.getKey(), leg.getValue());
      }
      return Collections.unmodifiableMap(frozen);
    }
  }
}
