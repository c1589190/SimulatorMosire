package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.market.PortDirection;

/**
 * ★★ <b>跨区口岸的两侧节流（2026-10-10 追加裁定 3 §12：出入两侧都设规则、一批货要过境必须两边都过）</b> —— 全仓<b>唯一拼写点</b>（契约是 {@code
 * economy-api} 的 {@code ZonePortRegime} / {@link PortDirection}，本类只放算式）。
 *
 * <pre>
 * 源区 A 对该类的<b>出口</b>开放度   E_A = E(A, EXIT)   ‰
 * 目的区 B 对该类的<b>入口</b>开放度 E_B = E(B, ENTRY)  ‰
 * 可通过比例 = E_A × E_B ÷ 1,000,000                     （串行两道闸 = 相乘，不是取 min，§12.3）
 * 可通过量   = ⌊ transit × E_A × E_B ÷ 1,000,000 ⌋        （transit = 该车道上"想跨过去的量"，毫商品）
 * 被拦下量   = transit − 可通过量                        （★ 不进候选集、不记账，只作具名归因/读数）
 * </pre>
 *
 * <p>★★ <b>三条口径（逐条对上用户原话与设计书）</b>：
 *
 * <ol>
 *   <li><b>串行 = 相乘，不是取 min</b>（§12.3）：「不管是效率漏了走私的，还是通过的，都需要两边都过才能跨区」说的是 <b>两道闸依次都要过</b>；取 min
 *       会把"另一侧也拦"丢掉（两侧各 500‰：相乘 250‰，min 仍 500‰）。
 *   <li><b>E 用区级</b>（§12.3）：即 {@code PortRegimeAggregation} 的"按暴露边加权平均"结果，<b>不</b>改成"只算 A–B
 *       之间那一段边界"；与用户 2026-10-09「商品可能从多路涌来 ⇒ 算一个总效率」同源。
 *   <li><b>只有一侧是市场区</b>（§12.2-4）：对面三不管／无区 ⇒ 那一侧没有政府、没有口岸可管 ⇒ <b>开放度 = 1000‰</b> （{@link
 *       #FULLY_OPEN_PER_MILLE}），实际由有规则的那一侧决定。★ 本类<b>不做</b>"哪一侧有没有政府"的判断：那是一侧 1000‰ 已经表达过的事实（缺表 ⇒
 *       管制力 0 ⇒ 开放度 1000），本类只吃开放度。
 * </ol>
 *
 * <p>★★ <b>确定性 + fail-closed</b>：纯整数运算、无随机数、不读时钟（I-P7）；乘积溢出 ⇒ {@link ArithmeticException}
 * 当场炸（不静默截断，照 {@code PortRegimeAggregation} 的同一条纪律）；开放度越界（不在 {@code [0,1000]}）⇒ {@link
 * IllegalArgumentException}（那是跨切片契约故障，不许静默钳）。
 */
public final class PortThrottle {

  /** 千分制：{@code 1000‰ = 1.0}（与 {@code PortRegimeAggregation.PER_MILLE} 同值；两处都就地声明，不互相依赖）。 */
  public static final long PER_MILLE = 1000L;

  /** 两侧都全开（无人管/不设限）的开放度：1000‰。 */
  public static final long FULLY_OPEN_PER_MILLE = PER_MILLE;

  /** 两道闸的乘积分母：{@code E_源 × E_目的 ÷ 1,000,000}（‰ × ‰ ⇒ 百万分之一）。 */
  public static final long TWO_GATE_DENOMINATOR = PER_MILLE * PER_MILLE;

  /**
   * ★★ <b>"本轮没有口岸闸"的哨兵值</b>：注入表为空（{@code PortEnforcementInput.none()}）时，跨区配对**不受口岸约束** ⇒
   * 节流量上限取它，撮合的 {@code Math.min(...)} 逐值等于不加这一项 ⇒ <b>旧世界逐值不变</b>（I-P8）。
   *
   * <p>★ 为什么用 {@code Long.MAX_VALUE} 而不是 0/负数：节流量是"上限"语义，{@code min} 与它恒等；而 0 是"全拦"、 负数会污染 {@code
   * min} 的语义（0 与负数必须各有具名含义，不能拿来当"无闸"）。
   */
  public static final long NO_GATE_MILLI = Long.MAX_VALUE;

  private PortThrottle() {}

  /**
   * ★★ <b>两侧两道闸的可通过量</b>：{@code ⌊ transit × E_源 × E_目的 ÷ 1,000,000 ⌋}（毫商品）。
   *
   * <p>★ <b>单侧全关（任一侧 E = 0）⇒ 0</b>（判据 ②：任一侧 = 0 ⇒ 该 lane 零候选）；<b>两侧全开（各 1000‰）⇒ transit 逐值不变</b>（判据
   * ①：{@code transit × 1000 × 1000 ÷ 1e6 = transit}）。
   *
   * <p>★ <b>先判 0 再相乘</b>：{@code transit = 0} 直接给 0，不让 {@code multiplyExact} 参与无意义的溢出判定。
   *
   * @param transitMilli 这一条车道上"想跨过去的量"（毫商品；≥ 0）
   * @param sourceExitOpennessPerMille 源区该类的<b>出口</b>开放度 {@code E_源}（‰；{@code [0,1000]}）
   * @param destinationEntryOpennessPerMille 目的区该类的<b>入口</b>开放度 {@code E_目的}（‰；{@code [0,1000]}）
   * @throws IllegalArgumentException 入参越界（跨切片契约故障）
   * @throws ArithmeticException 乘积溢出（fail-closed，不静默截断）
   */
  public static long allowedTransitMilli(
      long transitMilli, long sourceExitOpennessPerMille, long destinationEntryOpennessPerMille) {
    requireTransit(transitMilli);
    requireOpenness(sourceExitOpennessPerMille, "E_源（出口开放度‰）");
    requireOpenness(destinationEntryOpennessPerMille, "E_目的（入口开放度‰）");
    if (transitMilli == 0L) {
      return 0L;
    }
    long product =
        Math.multiplyExact(
            Math.multiplyExact(transitMilli, sourceExitOpennessPerMille),
            destinationEntryOpennessPerMille);
    return Math.floorDiv(product, TWO_GATE_DENOMINATOR);
  }

  /**
   * 被拦下的量（毫商品）：{@code transit − 可通过量}（★ 只作具名归因/日志读数，<b>不进候选集、不落状态、不进账本</b>， 设计书
   * §11.2：没管住的那一份就是流入市场的那一份，不额外记录）。
   *
   * @throws IllegalArgumentException 可通过量大于 transit（契约故障：闸不可能放大流量）
   */
  public static long blockedTransitMilli(long transitMilli, long allowedTransitMilli) {
    requireTransit(transitMilli);
    if (allowedTransitMilli < 0L || allowedTransitMilli > transitMilli) {
      throw new IllegalArgumentException(
          "可通过量必须落在 [0, transit]（闸只拦不放）: " + allowedTransitMilli + " / " + transitMilli);
    }
    return transitMilli - allowedTransitMilli;
  }

  /** 这一侧是否"全开"（{@code E = 1000‰}：不设限 / 没人管 / 三不管那一侧）。 */
  public static boolean fullyOpen(long opennessPerMille) {
    requireOpenness(opennessPerMille, "opennessPerMille");
    return opennessPerMille >= FULLY_OPEN_PER_MILLE;
  }

  /** 两侧都全开（⇒ 无节流；判据 ①的谓词形态）。 */
  public static boolean bothFullyOpen(long e1, long e2) {
    return fullyOpen(e1) && fullyOpen(e2);
  }

  /** 这一侧是否"全关"（{@code E = 0‰}：全禁 + 满效率 ⇒ 一点也过不去，§11.2）。 */
  public static boolean fullyClosed(long opennessPerMille) {
    requireOpenness(opennessPerMille, "opennessPerMille");
    return opennessPerMille == 0L;
  }

  /** 某一侧全关 ⇒ 该 lane 零候选（判据 ②）。 */
  public static boolean anySideClosed(long e1, long e2) {
    return fullyClosed(e1) || fullyClosed(e2);
  }

  private static void requireTransit(long transitMilli) {
    if (transitMilli < 0L) {
      throw new IllegalArgumentException("节流量（transit）必须 ≥ 0: " + transitMilli);
    }
  }

  private static void requireOpenness(long opennessPerMille, String field) {
    if (opennessPerMille < 0L || opennessPerMille > PER_MILLE) {
      throw new IllegalArgumentException(
          "PortThrottle." + field + " 必须落在 [0,1000]（开放度是比例量）: " + opennessPerMille);
    }
  }
}
