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
 * 三项约束（2026-10-09 起是两项）：
 *   ① 储备上限 R_max：★ 已取消（用户 2026-10-08 原话「什么叫有仓库上限值，取消掉」）——
 *                      买入侧不再被上限封顶，只由"国库当刻可付的 quote"封顶（INSUFFICIENT_FUNDS）
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
   * ★★ <b>{@code R_max} 的"取消"形态</b>（2026-10-09；用户 2026-10-08 原话「什么叫有仓库上限值，取消掉」）。
   *
   * <p>本批起<b>买入侧不再有储备上限</b>：窗口的买入容量只由"国库当刻可付的计价币"封顶（{@link
   * FxRejectReason#INSUFFICIENT_FUNDS}），不再由"该币种累计发行量 × 某个千分比"封顶。★ <b>保留的两条底线一字不动</b>： 储备为 0 ⇒
   * 停卖（{@link FxRejectReason#RESERVE_EXHAUSTED}，不许卖空 = 不许凭空造外币）、 每笔买入必须有对手方（{@link
   * FxRejectReason#NO_COUNTERPARTY}）。
   *
   * <p>★ <b>为什么是一个具名常量，而不是把这个维删掉</b>：{@code Window.reserveCapBaseMilli} 是既有的载荷/读数位
   * （旧构造点/旧夹具仍在传它），删维会让旧载荷失去语义落点；取成 {@code Long.MAX_VALUE} 让"无上限"只有<b>一个拼写点</b>， 判定它也只有 {@link
   * #isReserveCapUnbounded(long)} 一个入口。
   *
   * <p>★★ <b>旧构造点传有限值的语义退化（如实记）</b>：该值此后<b>只作读数留痕，不再封顶买入容量</b> ——
   * 一条"库存触顶就停买"的自定上限在窗口上不再有强制力（该政策线已由用户裁定取消；要重新限流请走吞吐/额度那条政策通道， 不是本常量）。★ 旧的 {@code
   * DEFAULT_RESERVE_CAP_PER_MILLE = 500‰} 已随之删除：留一个"还被传、却被忽略"的千分比 常量正是本条要消灭的形态。
   */
  public static final long UNBOUNDED_RESERVE_CAP_BASE_MILLI = Long.MAX_VALUE;

  /** 该 {@code R_max} 读数是不是"无上限"（{@link #UNBOUNDED_RESERVE_CAP_BASE_MILLI}）—— 判定它的唯一入口。 */
  public static boolean isReserveCapUnbounded(long reserveCapBaseMilli) {
    return reserveCapBaseMilli == UNBOUNDED_RESERVE_CAP_BASE_MILLI;
  }

  /**
   * {@code R_max} 的人类可读标签（日志/明细串用）：无上限 ⇒ {@code unbounded}，有限值 ⇒ 十进制。
   *
   * <p>★ 为什么不直接打印 {@code Long.MAX_VALUE}：日志里那一串 19 位数字会被读成"一个巨大的上限"， 而事实是这条政策线已经取消 ——
   * 标签把这个区别写在字面上（§一.9：拒绝/停做的原因要具名）。
   */
  public static String reserveCapLabel(long reserveCapBaseMilli) {
    return isReserveCapUnbounded(reserveCapBaseMilli)
        ? "unbounded"
        : Long.toString(reserveCapBaseMilli);
  }

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
   * @param reserveCapBaseMilli 储备上限 {@code R_max} 的<b>读数</b>（最小单位；生产路径恒为 {@link
   *     #UNBOUNDED_RESERVE_CAP_BASE_MILLI}）。★ 本批起它<b>不参与容量</b>：有限值只作留痕（见该常量的注）。
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
   * @param reserveCapBaseMilli 储备上限 {@code R_max} 的读数（≥ 0；生产路径恒为 {@link
   *     #UNBOUNDED_RESERVE_CAP_BASE_MILLI}）。★ 本批起<b>不封顶买入</b>（用户 2026-10-08 裁定取消该政策线）
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
    // ① 储备上限已取消（2026-10-09）⇒ 买入容量**不再**被 R_max 封顶，只由"买得起多少 base"决定
    //    （quote 可花额 ÷ bidP，floor）—— 挂出买不起的量会在成交时才炸（见类注）。
    long buyCapacity = mulDivFloor(treasuryQuoteSpendableMilli, 1000L, bidPerMille);
    FxRejectReason buyBlocked = buyCapacity <= 0L ? FxRejectReason.INSUFFICIENT_FUNDS : null;
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
      // ★ 2026-10-09：上限取消后，买入容量的唯一封顶是"国库可付的 quote" ⇒ 超容量就是钱不够，
      //   不再有 RESERVE_CAP 这条生产路径（那条政策线已由用户裁定取消；枚举值留作历史线上契约）。
      return Fill.rejected(FxRejectReason.INSUFFICIENT_FUNDS, quote.bidPerMille());
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
