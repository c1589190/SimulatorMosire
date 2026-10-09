package io.mosire.simos.economy.api.market;

import java.util.Objects;

/**
 * ★★ <b>一条口岸税规则（2026-10-09/10 口岸设计书 §13.5 T-5 的政策形状之一）</b>： <b>{@link PortTaxMode 计量方式} +
 * 数额</b>，<b>每条规则各自指定</b>从量还是从价（用户原话「规则可以灵活，从量从价都行」）。
 *
 * <pre>
 * PortTaxRule(mode: NONE | PER_UNIT_MILLI | AD_VALOREM_PER_MILLE, amount: ≥ 0)
 *   NONE                    ⇒ 不收税（缺省）；amount 必须 = 0（非 0 = 非法规则，构造期具名拒）
 *   PER_UNIT_MILLI          ⇒ amount = 毫（计价货币）/ 商品单位        ← 从量
 *   AD_VALOREM_PER_MILLE    ⇒ amount = 货值的千分比（‰）              ← 从价
 * </pre>
 *
 * <p>★★ <b>它是"政策"而不是"执行"</b>：本类型只装税收规则的形状，<b>不含任何金额计算</b>—— "该收多少、收哪种币、进哪个国库"是市场结算与组合根的事（{@link
 * PortRule} 的类注写了本批的边界）。
 *
 * <p>★ <b>缺省 = {@link #none()}</b>（不收税）⇒ 与"没设这条规则"逐值同义（I-P1/I-P8 的缺省语义中性： 未设限制/未设税 ⇒ 逐值不变）。★ 显式 0
 * 与未设同义，不做"设置过/没设置过"的区分（OR 规则与税都只读有效值）。
 *
 * <p>★ <b>不封顶</b>（用户 2026-10-23「都不封顶」）：{@code amount} 只判 {@code ≥ 0}； 负税是<b>非法规则</b> ⇒ 构造期具名拒（N1
 * 负向判据），不静默取绝对值。
 *
 * @param mode 计量方式；不得为 null
 * @param amount 数额（按 {@link #mode()} 的量纲解释；≥ 0；不封顶）
 */
public record PortTaxRule(PortTaxMode mode, long amount) {

  /** 不收税（缺省；{@code amount = 0}）。 */
  private static final PortTaxRule NONE = new PortTaxRule(PortTaxMode.NONE, 0L);

  public PortTaxRule {
    Objects.requireNonNull(mode, "PortTaxRule.mode 不得为 null（不收税给 PortTaxMode.NONE）");
    if (amount < 0L) {
      throw new IllegalArgumentException(
          "口岸税额不得为负（非法规则）: mode=" + mode.value() + ", amount=" + amount);
    }
    if (mode == PortTaxMode.NONE && amount != 0L) {
      throw new IllegalArgumentException(
          "PortTaxMode.NONE 的 amount 必须为 0（不收税就是 0；要收税请指名计量方式）: " + amount);
    }
  }

  /** 不收税（缺省；唯一拼写点）。 */
  public static PortTaxRule none() {
    return NONE;
  }

  /** 从量：{@code amount} = 毫（计价货币）/ 商品单位。 */
  public static PortTaxRule perUnitMilli(long amountMilli) {
    return new PortTaxRule(PortTaxMode.PER_UNIT_MILLI, amountMilli);
  }

  /** 从价：{@code amount} = 货值的千分比（‰）。 */
  public static PortTaxRule adValoremPerMille(long amountPerMille) {
    return new PortTaxRule(PortTaxMode.AD_VALOREM_PER_MILLE, amountPerMille);
  }

  /**
   * 是不是"不收税"（{@link PortTaxMode#NONE}；此时 {@code amount} 必为 0）。
   *
   * <p>★ <b>名字刻意不用 {@code isNone()}</b>：本类型是<b>会被 Jackson 写进 GOV 状态</b>的值类型，{@code isXxx()}
   * 会被内省成线格式属性（{@code "none":true}），而读侧保持 {@code FAIL_ON_UNKNOWN_PROPERTIES} 严格 ⇒ 写出来的档自己读不回
   * （手工往返实测：{@code UnrecognizedPropertyException: Unrecognized field "effective"}）。本仓口径 = 值类型零
   * Jackson 注解 ⇒ 用非 getter 名从源头消掉这条线格式污染。
   */
  public boolean taxFree() {
    return mode == PortTaxMode.NONE;
  }

  /**
   * 这条规则是否<b>真的会收</b>（指名了计量方式且数额 &gt; 0）—— "显式 0"读作不收（与未设同义）。
   *
   * <p>★ 它只回答"这条规则有没有作用面"，<b>不</b>回答"本批会不会真收钱"（见 {@link PortRule} 的类注）。
   */
  public boolean leviesTax() {
    return mode != PortTaxMode.NONE && amount > 0L;
  }

  /** 读数/日志的人可读拼法（{@code none} / {@code per_unit_milli:5} / {@code ad_valorem_per_mille:100}）。 */
  @Override
  public String toString() {
    return taxFree() ? "none" : mode.value() + ":" + amount;
  }
}
