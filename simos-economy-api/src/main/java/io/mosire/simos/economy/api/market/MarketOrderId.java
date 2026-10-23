package io.mosire.simos.economy.api.market;

/**
 * 统一市场订单的稳定身份。
 *
 * <p>它是 opaque 的非空白值对象；订单的存活期只在一个市场轮内，不落盘、不进 ChangeSet/Codec。
 *
 * @param value 非空白规范串
 */
public record MarketOrderId(String value) {

  public MarketOrderId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("MarketOrderId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 读取一个已经存在的 opaque order id（不重写订单身份格式）。 */
  public static MarketOrderId parse(String text) {
    return new MarketOrderId(text);
  }
}
