package io.mosire.simos.economy.api.relation;

import io.mosire.simos.economy.api.id.CommodityId;
import java.util.Optional;

/**
 * ★★ <b>一条补偿规则</b>（spec §2.4 的 {@code CompensationRule}）：<b>谁</b>（{@link Recipient}）按<b>什么方式</b>
 * （{@link RuleType}）从<b>哪一层数量</b>（{@link Basis}）拿<b>多少</b>（{@code ratePerMille} 或 {@code
 * fixedAmount}）， 以及它<b>排第几</b>（{@code priority}）。
 *
 * <p>★★ <b>{@code commodity} 的「空」是一个事实，不是一个缺省</b>（裁定 I5.3）：<b>空 = 货币规则</b>（S1 <b>只定义、不结算</b>，结算时进「待
 * S2」的清单）；<b>非空 = 实物规则</b>（结算成实物条目 / cohort 入账）。⇒ 二者<b>二选一</b>， 且由构造期守卫判死（见下）——
 * 「两处都能填」会让货币档的判别力当场消失。★ 所以这是本类型里<b>唯一</b>的 {@code Optional} （判据⑤：别处再冒出一个 {@code
 * Optional}，"空"就有两种含义）。
 *
 * <p>★★ <b>构造期守卫（逐条）</b>：
 *
 * <ol>
 *   <li>{@code type} / {@code recipient} / {@code basis} 非 null；{@code commodity} <b>本身</b>不得为
 *       null（{@code Optional} 的纪律：不许用 null 表示「空」，那会退化成 NPE）；
 *   <li>{@code ratePerMille ∈ [0, 1000]}：分成率不会超过全额，也不会为负；
 *   <li>{@code fixedAmount ≥ 0}：固定额不是债务（负数不是一种数量）；
 *   <li>{@code priority ≥ 0} 且<b>允许重复</b>（次序 = 数据；同值的两条按 {@code rules} 里的先后稳定排序，见 {@link
 *       ProductionRelation}）；
 *   <li><b>二选一</b>：{@code type.money()} ⟺ {@code commodity} 为空 —— 货币规则带商品 ⇒ 抛；实物规则不带商品 ⇒ 抛。
 * </ol>
 *
 * <p>★ <b>有意不判的</b>：{@code type} × {@code basis} 的组合、{@code ratePerMille} 与 {@code fixedAmount} 的
 * 「哪一个是有效字段」—— 哪些组合有公式，是 {@code ProductionSettlement}（Task 3）的公式表说了算；在契约里先禁掉一批组合 = 把还没定的设计写进类型。★
 * 故两个数值字段<b>在一条规则里同时存在</b>（分成档的 {@code fixedAmount} 就是 0，反之亦然）， 这不是冗余，是「一条规则两个旋钮、由 type 决定读哪个」。
 *
 * @param type 规则类型（六档词表；{@code money()} 决定这一条结不结算）
 * @param recipient 受方（actor 或 cohort，**恰其一**）
 * @param basis 数量取自哪一层（六档词表）
 * @param ratePerMille 分成率（千分数，{@code [0, 1000]}；固定额档填 0）
 * @param fixedAmount 固定额（毫单位，{@code ≥ 0}；分成档填 0）
 * @param commodity 商品（**空 = 货币规则，非空 = 实物规则**；本类型唯一的 {@code Optional}）
 * @param priority 次序（{@code ≥ 0}，**允许重复**；结算按升序、同值按表序）
 */
public record CompensationRule(
    RuleType type,
    Recipient recipient,
    Basis basis,
    int ratePerMille,
    long fixedAmount,
    Optional<CommodityId> commodity,
    int priority) {

  public CompensationRule {
    if (type == null) {
      throw new IllegalArgumentException("CompensationRule.type 不得为 null");
    }
    if (recipient == null) {
      throw new IllegalArgumentException("CompensationRule.recipient 不得为 null");
    }
    if (basis == null) {
      throw new IllegalArgumentException("CompensationRule.basis 不得为 null（『30% 的什么』必须显式）");
    }
    if (commodity == null) {
      throw new IllegalArgumentException(
          "CompensationRule.commodity 不得为 null（空要用 Optional.empty()，不许用 null）");
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
          "货币规则不得带商品（" + type + "）：commodity=" + commodity.get() + " —— 货币档只定义字段、不结算（I5.3）");
    }
    if (!type.money() && commodity.isEmpty()) {
      throw new IllegalArgumentException(
          "实物规则必须带商品（" + type + "）：commodity 为空只对货币档合法（空 = 货币是类型事实，不许两处都能填）");
    }
  }
}
