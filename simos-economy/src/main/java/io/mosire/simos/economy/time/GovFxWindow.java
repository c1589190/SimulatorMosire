package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.fx.FxRejectReason;
import io.mosire.simos.economy.api.fx.OfficialRate;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import java.util.Objects;

/**
 * ★★ <b>政府外汇窗口</b>（阶段 2-A2a；约束设计书 §3.4 / §5 I21 / §6.1 F2·F3 / §7 M2·M3）。
 *
 * <pre>
 * 报价：bidP = officialBuy    // 政府买入 base（外币）付 quote（本币）
 *       askP = officialSell   // 政府卖出 base 收 quote
 * 三项约束（缺一不可）：
 *   ① 储备上限 R_max：政府该币种库存 ≥ R_max ⇒ 停止买入（只卖不买）
 *   ② 储备下限 0    ：库存 = 0 ⇒ 只买不卖（<b>不许卖空</b> = 不许凭空造外币）
 *   ③ 政府不许凭空持币：每一笔都有对手方（家户）；无对手方 ⇒ 拒
 * </pre>
 *
 * <p>★★ <b>报价与容量的关系</b>：{@link Quote} 是"这一轮窗口摆在市场上的两条腿"——报价<b>不等于</b>成交量（I18）： 成交量由容量（{@link
 * Quote#buyCapacityBaseMilli()} / {@link Quote#sellCapacityBaseMilli()}）决定。容量为 0 时报价
 * 仍在（政策价没变），但那一侧<b>停做</b>，且停做的原因是<b>具名</b>的（{@link Quote#buyBlocked()} / {@link
 * Quote#sellBlocked()}）。
 *
 * <p>★★ <b>为什么容量还要被国库的 quote 余额再封一次</b>：政府买入 base 要付 quote；挂出买不起的量 ⇒ 成交时才发现付不出 （唯一的写口 {@code
 * applyTransfer} 会当场抛）。⇒ 容量在<b>挂单之前</b>就按"国库实际可花 quote"封顶，并在封顶时记具名原因 {@link
 * FxRejectReason#INSUFFICIENT_FUNDS}（不是静默缩小）。
 *
 * <p>★ <b>相反报价（{@code bidP ≥ askP}）</b>：政府"买得比卖得贵"，窗口两侧自成交会变成一台无风险抽水机（政府自己给自己 送钱）。本批的处置是<b>两侧都停</b>
 * + 具名 {@link FxRejectReason#INVERTED_QUOTE}（fail-closed；真要做补贴政策，那是另一条 政策通道，不是外汇窗口的默认语义）。
 */
public final class GovFxWindow {

  /**
   * ★★ <b>储备上限 {@code R_max} 的出厂口径</b>（千分比）：{@code R_max = 该 GOV 对该币种的累计发行量 × 500‰}。
   *
   * <p>★ <b>为什么是"发行量的一个分数"而不是一个绝对数</b>：<b>绝对数</b>换个世界/换个精度就没有意义（本仓的
   * "没人写下来的量纲"教训）；而"政府不打算把超过自己发行量一半的外币囤回库里"是一条与规模无关、且<b>能被触到</b>的政策线 —— 阶段 2 的判据 F3 要求"库存触顶 ⇒
   * 停止买入（具名）"必须真的可达。★ 它是 <b>GM 可调默认值</b>（参数目录落地后迁入），不是物理常数。
   */
  public static final long DEFAULT_RESERVE_CAP_PER_MILLE = 500L;

  private GovFxWindow() {}

  /**
   * 窗口摆在市场上的一轮报价与容量（{@code null} 的 {@code *Blocked} = 那一侧照常做）。
   *
   * @param governmentId 窗口属主（= 定这条官方汇率的 GOV）
   * @param base 窗口买卖的标的币（外币）
   * @param quote 计价币（本币）
   * @param bidPerMille 政府买入 base 的报价（per-mille）
   * @param askPerMille 政府卖出 base 的报价（per-mille）
   * @param reserveBaseMilli 窗口当刻的该币种储备（= 国库可花余额；最小单位）
   * @param reserveCapBaseMilli 储备上限 {@code R_max}（最小单位）
   * @param buyCapacityBaseMilli 本轮还能买多少 base（≥ 0）
   * @param sellCapacityBaseMilli 本轮还能卖多少 base（≥ 0；恒 ≤ 储备 ⇒ 结构上不可能卖空）
   * @param buyBlocked 买入侧停做的具名原因（{@code null} = 照常）
   * @param sellBlocked 卖出侧停做的具名原因（{@code null} = 照常）
   */
  public record Quote(
      GovernmentId governmentId,
      CurrencyId base,
      CurrencyId quote,
      long bidPerMille,
      long askPerMille,
      long reserveBaseMilli,
      long reserveCapBaseMilli,
      long buyCapacityBaseMilli,
      long sellCapacityBaseMilli,
      FxRejectReason buyBlocked,
      FxRejectReason sellBlocked) {

    public Quote {
      Objects.requireNonNull(governmentId, "Quote.governmentId 不得为 null");
      Objects.requireNonNull(base, "Quote.base 不得为 null");
      Objects.requireNonNull(quote, "Quote.quote 不得为 null");
      if (bidPerMille <= 0L || askPerMille <= 0L) {
        throw new IllegalArgumentException(
            "窗口报价必须 > 0（说不出价就不该挂单）: bid=" + bidPerMille + " ask=" + askPerMille);
      }
      if (reserveBaseMilli < 0L || reserveCapBaseMilli < 0L) {
        throw new IllegalArgumentException(
            "储备/上限不得为负: reserve=" + reserveBaseMilli + " cap=" + reserveCapBaseMilli);
      }
      if (buyCapacityBaseMilli < 0L || sellCapacityBaseMilli < 0L) {
        throw new IllegalArgumentException(
            "窗口容量不得为负: buy=" + buyCapacityBaseMilli + " sell=" + sellCapacityBaseMilli);
      }
      if (buyCapacityBaseMilli > 0L && buyBlocked != null) {
        throw new IllegalArgumentException("买入侧还有容量却带停做原因（口径漂开）: " + buyBlocked);
      }
      if (sellCapacityBaseMilli > 0L && sellBlocked != null) {
        throw new IllegalArgumentException("卖出侧还有容量却带停做原因（口径漂开）: " + sellBlocked);
      }
    }

    /** 这一侧还能不能做（容量 &gt; 0）。 */
    public boolean canBuy() {
      return buyCapacityBaseMilli > 0L;
    }

    /** 这一侧还能不能做（容量 &gt; 0）。 */
    public boolean canSell() {
      return sellCapacityBaseMilli > 0L;
    }

    /** 两侧都停 ⇒ 本轮窗口不参与（原因在两侧的具名字段里）。 */
    public boolean inactive() {
      return !canBuy() && !canSell();
    }
  }

  /**
   * ★★ <b>算出一轮报价与容量（三项约束的唯一落点）</b>。
   *
   * @param governmentId 窗口属主（= 定这条官方汇率的 GOV）
   * @param rate 官方汇率（{@code bidP = buyPerMille}、{@code askP = sellPerMille}）
   * @param reserveBaseMilli 窗口当刻的该币种储备（国库可花余额；≥ 0）
   * @param reserveCapBaseMilli 储备上限 {@code R_max}（≥ 0）
   * @param treasuryQuoteSpendableMilli 国库当刻可花的计价币（买 base 要付它；≥ 0）
   */
  public static Quote quote(
      GovernmentId governmentId,
      OfficialRate rate,
      long reserveBaseMilli,
      long reserveCapBaseMilli,
      long treasuryQuoteSpendableMilli) {
    Objects.requireNonNull(rate, "rate");
    Objects.requireNonNull(governmentId, "governmentId（窗口属主必须具名）");
    if (reserveBaseMilli < 0L || reserveCapBaseMilli < 0L || treasuryQuoteSpendableMilli < 0L) {
      throw new IllegalArgumentException(
          "储备/上限/可花额不得为负: reserve="
              + reserveBaseMilli
              + " cap="
              + reserveCapBaseMilli
              + " spendable="
              + treasuryQuoteSpendableMilli);
    }
    long bidPerMille = rate.buyPerMille();
    long askPerMille = rate.sellPerMille();
    if (bidPerMille >= askPerMille) {
      // 相反报价：两侧都停（见类注）。
      return new Quote(
          governmentId,
          rate.base(),
          rate.quote(),
          bidPerMille,
          askPerMille,
          reserveBaseMilli,
          reserveCapBaseMilli,
          0L,
          0L,
          FxRejectReason.INVERTED_QUOTE,
          FxRejectReason.INVERTED_QUOTE);
    }
    // ① 储备上限 ⇒ 停止买入
    long buyCapacity = Math.max(0L, reserveCapBaseMilli - reserveBaseMilli);
    FxRejectReason buyBlocked = buyCapacity <= 0L ? FxRejectReason.RESERVE_CAP : null;
    // 买得起多少 base：quote 可花额 ÷ bidP（floor）—— 挂出买不起的量会在成交时才炸（见类注）。
    long affordableBase = mulDivFloor(treasuryQuoteSpendableMilli, 1000L, bidPerMille);
    if (buyCapacity > affordableBase) {
      buyCapacity = affordableBase;
      if (buyCapacity <= 0L) {
        buyBlocked = FxRejectReason.INSUFFICIENT_FUNDS;
      }
    }
    // ② 储备下限 0 ⇒ 只买不卖；不许卖空（容量恒 ≤ 储备）
    long sellCapacity = reserveBaseMilli;
    FxRejectReason sellBlocked = sellCapacity <= 0L ? FxRejectReason.RESERVE_EXHAUSTED : null;
    return new Quote(
        governmentId,
        rate.base(),
        rate.quote(),
        bidPerMille,
        askPerMille,
        reserveBaseMilli,
        reserveCapBaseMilli,
        buyCapacity,
        sellCapacity,
        buyBlocked,
        sellBlocked);
  }

  /**
   * ★★ <b>一笔"政府买入 base"的请求</b>（= 家户拿外币来换本币）：容量不足 ⇒ <b>具名拒</b>，绝不静默少成交。
   *
   * @param quote 当轮窗口报价
   * @param requestedBaseMilli 家户请求卖出的 base（&gt; 0）
   * @param counterpartyPresent 这一笔有没有对手方（政府不许凭空持币 ⇒ 无对手方必拒，M3）
   */
  public static Fill requestWindowBuy(
      Quote quote, long requestedBaseMilli, boolean counterpartyPresent) {
    Objects.requireNonNull(quote, "quote");
    if (requestedBaseMilli <= 0L) {
      throw new IllegalArgumentException("请求量必须 > 0: " + requestedBaseMilli);
    }
    if (!counterpartyPresent) {
      return Fill.rejected(FxRejectReason.NO_COUNTERPARTY, quote.bidPerMille());
    }
    if (quote.buyBlocked() != null) {
      return Fill.rejected(quote.buyBlocked(), quote.bidPerMille());
    }
    if (requestedBaseMilli > quote.buyCapacityBaseMilli()) {
      return Fill.rejected(FxRejectReason.RESERVE_CAP, quote.bidPerMille());
    }
    return new Fill(
        true,
        requestedBaseMilli,
        quotePerMille(requestedBaseMilli, quote.bidPerMille()),
        quote.bidPerMille(),
        null);
  }

  /**
   * ★★ <b>一笔"政府卖出 base"的请求</b>（= 家户拿本币来买外币）：超过储备 ⇒ <b>具名拒</b>（{@link
   * FxRejectReason#RESERVE_EXHAUSTED}，M2）。
   */
  public static Fill requestWindowSell(Quote quote, long requestedBaseMilli) {
    Objects.requireNonNull(quote, "quote");
    if (requestedBaseMilli <= 0L) {
      throw new IllegalArgumentException("请求量必须 > 0: " + requestedBaseMilli);
    }
    if (quote.sellBlocked() != null) {
      return Fill.rejected(quote.sellBlocked(), quote.askPerMille());
    }
    if (requestedBaseMilli > quote.sellCapacityBaseMilli()) {
      return Fill.rejected(FxRejectReason.RESERVE_EXHAUSTED, quote.askPerMille());
    }
    return new Fill(
        true,
        requestedBaseMilli,
        quotePerMille(requestedBaseMilli, quote.askPerMille()),
        quote.askPerMille(),
        null);
  }

  /** 请求的结果：成交 ⇒ 具名金额；拒 ⇒ 具名原因（{@code baseMilli == 0}）。 */
  public record Fill(
      boolean filled,
      long baseMilli,
      long quoteMilli,
      long pricePerMille,
      FxRejectReason rejection) {

    public Fill {
      if (filled) {
        if (baseMilli <= 0L || quoteMilli <= 0L || pricePerMille <= 0L) {
          throw new IllegalArgumentException(
              "成交必须有正的两条腿与正的价格: " + baseMilli + "/" + quoteMilli + "/" + pricePerMille);
        }
        if (rejection != null) {
          throw new IllegalArgumentException("成交不得同时带拒因: " + rejection);
        }
      } else {
        if (rejection == null) {
          throw new IllegalArgumentException("没成交必须给具名原因（不许静默）");
        }
        if (baseMilli != 0L || quoteMilli != 0L) {
          throw new IllegalArgumentException("没成交不得带金额: " + baseMilli + "/" + quoteMilli);
        }
        if (pricePerMille <= 0L) {
          throw new IllegalArgumentException("拒因里必须带上当时那一侧的报价: " + pricePerMille);
        }
      }
    }

    static Fill rejected(FxRejectReason reason, long pricePerMille) {
      return new Fill(false, 0L, 0L, pricePerMille, reason);
    }
  }

  /** base 量按 per-mille 报价折成 quote 量（向上取整：政府不会因为取整而少收一毫）。 */
  static long quotePerMille(long baseMilli, long pricePerMille) {
    return ceilDiv(Math.multiplyExact(baseMilli, pricePerMille), 1000L);
  }

  static long mulDivFloor(long value, long multiplier, long divisor) {
    return Math.multiplyExact(value, multiplier) / divisor;
  }

  static long ceilDiv(long numerator, long denominator) {
    return Math.addExact(numerator, denominator - 1L) / denominator;
  }
}
