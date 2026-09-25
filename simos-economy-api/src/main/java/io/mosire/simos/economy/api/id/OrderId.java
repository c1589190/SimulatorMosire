package io.mosire.simos.economy.api.id;

/**
 * 买卖订单 ID（设计稿 §6.2）：一条买/卖订单的稳定身份，归 {@code market} 切片。
 *
 * <p>撮合按商品、市场、价格、时间、稳定 ID 确定性排序；订单 ID 是那条排序里的稳定 tie-breaker。 裸值 {@code toString()} + {@code static
 * parse} 三件套（铁律 1）。
 */
public record OrderId(String value) {

  public OrderId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("OrderId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层）。 */
  public static OrderId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("OrderId 不得为空白: " + text);
    }
    return new OrderId(text);
  }
}
