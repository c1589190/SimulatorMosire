package io.mosire.simos.production.contract;

/**
 * ★★ <b>角色经验增长的计算基准</b>（D-032 D1 §2.5/§5）。
 *
 * <p>{@link #NORMALIZED_PARTICIPATION} 是工程默认；其分母口径由 economy 传入，production 不猜人口或 可用劳动。{@link
 * #ACTUAL_LABOR} 直接使用角色内该户被认可的实际劳动。
 */
public enum GrowthBasis {
  ACTUAL_LABOR("actual_labor"),
  NORMALIZED_PARTICIPATION("normalized_participation");

  private final String key;

  GrowthBasis(String key) {
    this.key = key;
  }

  /** 线格式/载荷里的稳定键。 */
  public String key() {
    return key;
  }

  /** 解析稳定键（也接受枚举名，便于同版本 codec 读回）；未知 ⇒ {@code null}。 */
  public static GrowthBasis fromKey(String key) {
    if (key == null) {
      return null;
    }
    for (GrowthBasis value : values()) {
      if (value.key.equals(key) || value.name().equals(key)) {
        return value;
      }
    }
    return null;
  }
}
