package io.mosire.simos.economy.api.market;

import java.util.Objects;

/**
 * ★★ <b>一个政府对"某一类"（某商品 / 某币种）的口岸规则 —— 每类四个数</b> （2026-10-10 冻结口径；设计书 §12/§13.5 T-5）。
 *
 * <pre>
 * PortRule(entryRestrictionPerMille, exitRestrictionPerMille, entryTax, exitTax)
 *   entryRestrictionPerMille  入口限制强度 s_in（‰；缺省 0 = 不限制；不封顶）
 *   exitRestrictionPerMille   出口限制强度 s_out（‰；缺省 0 = 不限制；不封顶）
 *   entryTax                  入口税（{@link PortTaxRule}；缺省 none = 不收税）
 *   exitTax                   出口税（{@link PortTaxRule}；缺省 none = 不收税）
 * </pre>
 *
 * <p>★★ <b>方向维（§12）</b>：{@link PortDirection#ENTRY} 读 {@link #entryRestrictionPerMille()}， {@link
 * PortDirection#EXIT} 读 {@link #exitRestrictionPerMille()}——<b>两侧各自按自己那一侧的政府口岸效率算</b>；
 * 一票货要跨区，必须<b>两道闸都过</b>（串行 = 相乘，见 {@link PortDirection} 的类注）。
 *
 * <p>★★ <b>本批的作用面（P-T1a，别越界）</b>：
 *
 * <ul>
 *   <li><b>限制（s_in/s_out）</b>：本批生效——组合根折成"逐区逐方向开放度 E"，经济侧在<b>跨区候选配对处</b>按 {@code E_源 × E_目的 ÷ 1e6}
 *       节流（被拦下的量不进候选集）；
 *   <li><b>税（entryTax/exitTax）</b>：本批<b>只落形状与校验（+ 日志/读数）</b>——"真收钱、进两侧国库、按币种逐项列出" 是
 *       <b>P-T1b（过境税真收款）</b>；本批一个字都不搬，且缺省 {@link PortTaxRule#none()} ⇒ 逐值不变（I-C2）。
 * </ul>
 *
 * <p>★ <b>缺省语义中性</b>（I-P1/I-P8）：未设限制 = 0 = 不限制、未设税 = none = 不收税；<b>显式 0 与未设同义</b> （OR
 * 规则与税都只读<b>有效值</b>，不做"设置过/没设置过"的区分——那个区分只服务读数）。
 *
 * <p>★ <b>不封顶</b>：限制强度只判 {@code ≥ 0}（用户 2026-10-23「都不封顶」）；负限制是<b>非法政策</b> ⇒ 构造期具名拒（N1
 * 负向判据），不静默取绝对值。
 *
 * <p>★ <b>与 OR 规则的关系</b>（设计书 §4.3）：本类型只装"这条规则是什么"；"该区这一类放不放行/开放度多少"由 {@code PortRegimeAggregation}
 * 按<b>接触面</b>聚合（OR + 按暴露边加权平均），<b>不在这里算</b>。
 *
 * @param entryRestrictionPerMille 入口限制强度（‰；≥ 0，不封顶；0 = 不限制）
 * @param exitRestrictionPerMille 出口限制强度（‰；≥ 0，不封顶；0 = 不限制）
 * @param entryTax 入口税规则；不得为 null（不收税给 {@link PortTaxRule#none()})
 * @param exitTax 出口税规则；不得为 null（不收税给 {@link PortTaxRule#none()})
 */
public record PortRule(
    long entryRestrictionPerMille,
    long exitRestrictionPerMille,
    PortTaxRule entryTax,
    PortTaxRule exitTax) {

  /** 不限制（‰）：缺省值与显式 0 都读作它（I-P1）。 */
  public static final long RESTRICTION_NONE_PER_MILLE = 0L;

  /** 管制强度拉满（‰）：在总效率里把该接触面完全关掉（{@code openness = 1000 − s×e÷1000}）。 */
  public static final long RESTRICTION_FULL_PER_MILLE = 1000L;

  /** 四个数全缺省（不限制、不收税）；"缺键 = 不限制"的唯一拼写点。 */
  private static final PortRule UNRESTRICTED =
      new PortRule(
          RESTRICTION_NONE_PER_MILLE,
          RESTRICTION_NONE_PER_MILLE,
          PortTaxRule.none(),
          PortTaxRule.none());

  public PortRule {
    requireNonNegative(entryRestrictionPerMille, "entryRestrictionPerMille（入口限制‰）");
    requireNonNegative(exitRestrictionPerMille, "exitRestrictionPerMille（出口限制‰）");
    Objects.requireNonNull(entryTax, "PortRule.entryTax 不得为 null（不收税给 PortTaxRule.none()）");
    Objects.requireNonNull(exitTax, "PortRule.exitTax 不得为 null（不收税给 PortTaxRule.none()）");
  }

  /** 不限制、不收税（缺键 GOV / 未设类的默认值）。 */
  public static PortRule unrestricted() {
    return UNRESTRICTED;
  }

  /** 某方向的限制强度（‰）：{@link PortDirection#ENTRY} ⇒ 入口，{@link PortDirection#EXIT} ⇒ 出口。 */
  public long restriction(PortDirection direction) {
    Objects.requireNonNull(direction, "direction");
    return direction == PortDirection.ENTRY ? entryRestrictionPerMille : exitRestrictionPerMille;
  }

  /** 某方向的税规则。 */
  public PortTaxRule tax(PortDirection direction) {
    Objects.requireNonNull(direction, "direction");
    return direction == PortDirection.ENTRY ? entryTax : exitTax;
  }

  /** 有没有任一侧设了限制（读数/日志用；与 {@link #allDefault()} 区分：只看限制那一维）。 */
  public boolean hasRestriction() {
    return entryRestrictionPerMille != RESTRICTION_NONE_PER_MILLE
        || exitRestrictionPerMille != RESTRICTION_NONE_PER_MILLE;
  }

  /** 有没有任一侧设了<b>真会收</b>的税（{@link PortTaxRule#leviesTax()}）。 */
  public boolean hasEffectiveTax() {
    return entryTax.leviesTax() || exitTax.leviesTax();
  }

  /**
   * 四个数是否全缺省（不限制 + 不收税）——"这一条等于没设"，不产生任何口岸面。
   *
   * <p>★ <b>名字刻意不用 {@code isEmpty()}</b>（同 {@code PortTaxRule#taxFree()} 的理由）：本类型会被 Jackson 写进 GOV
   * 状态， getter 形态的方法名会被内省成线格式属性、把严格读侧打红。
   */
  public boolean allDefault() {
    return !hasRestriction() && !entryTax.leviesTax() && !exitTax.leviesTax();
  }

  /** 非法政策（负限制）⇒ 具名拒（N1）；上界不设（用户 2026-10-23「都不封顶」）。 */
  private static void requireNonNegative(long value, String field) {
    if (value < 0L) {
      throw new IllegalArgumentException("口岸限制强度不得为负（非法政策）: " + field + " = " + value);
    }
  }
}
