package io.mosire.simos.production.contract;

/**
 * ★★ <b>MONEY_WAGE 抵物折价策略（单一配置来源）</b>（D-032 D1 §2.4；用户 U23-c）。
 *
 * <p>单位折价 = 当地所选参考价 × {@code discountPerMille / 1000}。系数允许小于 1000（例如 500 = 五折），也允许大于
 * 1000；负值拒绝，{@code 0} 合法但表示折后单位价值为 0（economy 必须 skip 该商品并保留债务）。 默认工程值 = {@link
 * BarterReferencePrice#LOCAL_BID} + 1000（即现行 bid×1 行为）。
 *
 * <p>★★ <b>模块边界</b>：本类型只声明规则/纯配置。production 不读市场、不读钱包、不读他户状态； economy/D3
 * 负责读当地市场价并执行估值/抵付。工资债利率不在本类型，也不在本册。
 *
 * @param referencePrice 参考价类型；仅 {@link BarterReferencePrice#LOCAL_BID} 或 {@link
 *     BarterReferencePrice#LOCAL_ASK}
 * @param discountPerMille 折价系数（千分比）；必须为非负值，可小于 1000；{@code 0} 表示折后单位价值为 0
 */
public record BarterDiscountPolicy(BarterReferencePrice referencePrice, long discountPerMille) {

  /** 工程默认：当地 bid 价 × 1（与 U23 前 bid×1 行为逐值一致）。 */
  public static BarterDiscountPolicy defaultPolicy() {
    return new BarterDiscountPolicy(BarterReferencePrice.LOCAL_BID, 1000L);
  }

  public BarterDiscountPolicy {
    if (referencePrice == null) {
      throw new IllegalArgumentException("BarterDiscountPolicy.referencePrice 不得为 null");
    }
    if (discountPerMille < 0L) {
      throw new IllegalArgumentException(
          "BarterDiscountPolicy.discountPerMille 不得为负: " + discountPerMille);
    }
  }
}
