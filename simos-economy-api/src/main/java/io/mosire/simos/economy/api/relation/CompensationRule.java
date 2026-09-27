package io.mosire.simos.economy.api.relation;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import java.util.Optional;

/**
 * ★★ <b>一条补偿规则</b>（spec §2.4 的 {@code CompensationRule}）：<b>谁</b>（{@link Recipient}）按<b>什么方式</b>
 * （{@link RuleType}）从<b>哪一层池</b>（{@link Pool}）取、<b>怎么在受方之间分</b>（{@link Weight}）、拿<b>多少</b> （{@code
 * ratePerMille} 或 {@code fixedAmount}），以及它<b>排第几</b>（{@code priority}）。
 *
 * <p>★★ <b>H2（裁定 D5-B）：{@code basis} 已拆成 {@link Pool} × {@link Weight} 两个正交字段</b>——旧的那个单词把
 * "池怎么算"与"池怎么分"揉在一起，于是 {@code LABOR_AMOUNT} 这个名字既能读成"池是劳动"、又能读成"按劳动分" （真实公式是"净产 × 率 × 本受方劳动 ÷
 * Σ劳动"，见 {@code ProductionSettlement} 的公式表）。 ★ 旧五档到两个字段的<b>逐档映射表在 {@link Basis}
 * 的类注里</b>（那是旧档读侧的兼容词表，生产代码不再读它）。
 *
 * <p>★★ <b>两个"空"各是一个事实，不是一个缺省</b>（裁定 I5.3 + H2 的币种位）：
 *
 * <ul>
 *   <li>{@code commodity}：<b>空 = 货币规则</b>（H4 起**真的结算**：按"付方本期可用货币"付款，付不出的部分只进读数、 **不落债权** —— 见
 *       {@code ProductionSettlement.settleMoneyRule}）；<b>非空 = 实物规则</b>；
 *   <li>{@code currency}：<b>空 = 实物规则</b>；<b>非空 = 货币规则</b>（钱的<b>种类</b>必须说清 —— "1000 毫钱"没有意义，"1000
 *       毫<b>银</b>"才是）。
 * </ul>
 *
 * ★ 两者<b>互为反相</b>且各由构造期守卫判死（见下）：「两处都能填」会让货币档的判别力当场消失。 ★ <b>本类型恰有这两个 {@code
 * Optional}</b>：别处再冒出一个，"空"就有第三种含义。
 *
 * <p>★★ <b>构造期守卫（逐条）</b>：
 *
 * <ol>
 *   <li>{@code type} / {@code recipient} / {@code pool} / {@code weight} 非 null；{@code commodity} /
 *       {@code currency} <b>本身</b>不得为 null（{@code Optional} 的纪律：不许用 null 表示「空」，那会退化成 NPE）；
 *   <li>{@code ratePerMille ∈ [0, 1000]}：分成率不会超过全额，也不会为负；
 *   <li>{@code fixedAmount ≥ 0}：固定额不是债务（负数不是一种数量）；
 *   <li>{@code priority ≥ 0} 且<b>允许重复</b>（次序 = 数据；同值的两条按 {@code rules} 里的先后稳定排序，见 {@link
 *       ProductionRelation}）；
 *   <li><b>二选一（商品侧）</b>：{@code type.money()} ⟺ {@code commodity} 为空；
 *   <li>★ <b>二选一（币种侧）</b>：{@code type.money()} ⟺ {@code currency} 非空 —— 实物规则说"这是什么钱"是坏数据，
 *       货币规则不说"这是什么钱"同样是坏数据（H2 补的那一维）；
 *   <li>★ <b>固定额没有权重</b>：{@code pool == FIXED_AMOUNT} ⇒ {@code weight} 必须为 {@link Weight#NONE}
 *       （"固定额按劳动量分"不是一种制度，是两个字段被填错了一个）。
 * </ol>
 *
 * <p>★ <b>有意不判的</b>：{@code type} × {@code pool} × {@code weight} 的组合、{@code ratePerMille} 与 {@code
 * fixedAmount} 的「哪一个是有效字段」—— 哪些组合有公式，是 {@code ProductionSettlement}（Task 3）的公式表说了算； 在契约里先禁掉一批组合 =
 * 把还没定的设计写进类型。★ 故两个数值字段<b>在一条规则里同时存在</b>（分成档的 {@code fixedAmount} 就是 0，反之亦然），这不是冗余，是「一条规则两个旋钮、由
 * type 决定读哪个」。
 *
 * @param type 规则类型（六档词表；{@code money()} 决定这一条走货币那一支还是实物那一支）
 * @param recipient 受方（actor 或 cohort，**恰其一**）
 * @param pool 数量取自哪一层池（四档词表）
 * @param weight 池在受方之间怎么分（三档词表；固定额档恒 {@link Weight#NONE}）
 * @param ratePerMille 分成率（千分数，{@code [0, 1000]}；固定额档填 0）
 * @param fixedAmount 固定额（毫单位，{@code ≥ 0}；分成档填 0）
 * @param commodity 商品（**空 = 货币规则，非空 = 实物规则**）
 * @param currency 货币种类（**非空 = 货币规则，空 = 实物规则**；与 {@code commodity} 互为反相）
 * @param priority 次序（{@code ≥ 0}，**允许重复**；结算按升序、同值按表序）
 */
public record CompensationRule(
    RuleType type,
    Recipient recipient,
    Pool pool,
    Weight weight,
    int ratePerMille,
    long fixedAmount,
    Optional<CommodityId> commodity,
    Optional<CurrencyId> currency,
    int priority) {

  public CompensationRule {
    if (type == null) {
      throw new IllegalArgumentException("CompensationRule.type 不得为 null");
    }
    if (recipient == null) {
      throw new IllegalArgumentException("CompensationRule.recipient 不得为 null");
    }
    if (pool == null) {
      throw new IllegalArgumentException("CompensationRule.pool 不得为 null（『从哪一层取』必须显式）");
    }
    if (weight == null) {
      throw new IllegalArgumentException("CompensationRule.weight 不得为 null（『怎么分』必须显式，不分请给 NONE）");
    }
    if (commodity == null) {
      throw new IllegalArgumentException(
          "CompensationRule.commodity 不得为 null（空要用 Optional.empty()，不许用 null）");
    }
    if (currency == null) {
      throw new IllegalArgumentException(
          "CompensationRule.currency 不得为 null（空要用 Optional.empty()，不许用 null）");
    }
    if (ratePerMille < 0 || ratePerMille > 1000) {
      throw new IllegalArgumentException(
          "CompensationRule.ratePerMille 越界（应在 [0, 1000]）: " + ratePerMille);
    }
    if (fixedAmount < 0L) {
      throw new IllegalArgumentException("CompensationRule.fixedAmount 不得为负: " + fixedAmount);
    }
    if (priority < 0) {
      throw new IllegalArgumentException("CompensationRule.priority 不得为负: " + priority);
    }
    if (type.money() && commodity.isPresent()) {
      throw new IllegalArgumentException(
          "货币规则不得带商品（" + type + "）：commodity=" + commodity.get() + " —— 货币档只带币种、不带商品（H4 起真的结算）");
    }
    if (!type.money() && commodity.isEmpty()) {
      throw new IllegalArgumentException(
          "实物规则必须带商品（" + type + "）：commodity 为空只对货币档合法（空 = 货币是类型事实，不许两处都能填）");
    }
    if (type.money() && currency.isEmpty()) {
      throw new IllegalArgumentException(
          "货币规则必须带币种（" + type + "）：『1000 毫钱』没说是什么钱（H2 补的币种位）—— " + "货币档要么说清币种、要么说明它为什么不是货币规则");
    }
    if (!type.money() && currency.isPresent()) {
      throw new IllegalArgumentException(
          "实物规则不得带币种（" + type + "）：currency=" + currency.get() + " —— 实物档恒空，币种只对货币档合法");
    }
    if (pool == Pool.FIXED_AMOUNT && weight != Weight.NONE) {
      throw new IllegalArgumentException(
          "固定额没有『按什么权重分』这一维（"
              + pool
              + " 配了 "
              + weight
              + "）：固定额与产出、劳动都无关，"
              + "权重不适用 —— 这两个字段被填错了一个");
    }
  }
}
