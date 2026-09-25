package io.mosire.simos.economy.api.id;

/**
 * 市场节点 ID（设计稿 §6.2）：现货市场/交易节点的稳定身份，归 {@code market} 切片。
 *
 * <p>首期可先实现同 Hex 现货市场；跨 Hex 扩展时市场节点不随行政辖区变化。裸值 {@code toString()} + {@code static parse} 三件套（铁律 1）。
 */
public record MarketId(String value) {

  public MarketId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("MarketId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层）。 */
  public static MarketId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("MarketId 不得为空白: " + text);
    }
    return new MarketId(text);
  }
}
