package io.mosire.simos.production.contract;

/**
 * ★★ <b>工资抵物折价的参考价类型</b>（D-032 D1 §2.4；用户 U23）。
 *
 * <p>只有两种合法值，分别映射 economy 既有 {@code MarketTable.hasPrice} + {@code bidPriceOf} / {@code
 * askPriceOf}。不新增“最后成交价”或其他市场事实；production 只保存规则，不读市场。
 */
public enum BarterReferencePrice {
  LOCAL_BID("local_bid"),
  LOCAL_ASK("local_ask");

  private final String key;

  BarterReferencePrice(String key) {
    this.key = key;
  }

  /** 线格式/载荷里的稳定键。 */
  public String key() {
    return key;
  }

  /** 解析稳定键（也接受枚举名，便于同版本 codec 读回）；未知 ⇒ {@code null}。 */
  public static BarterReferencePrice fromKey(String key) {
    if (key == null) {
      return null;
    }
    for (BarterReferencePrice value : values()) {
      if (value.key.equals(key) || value.name().equals(key)) {
        return value;
      }
    }
    return null;
  }
}
