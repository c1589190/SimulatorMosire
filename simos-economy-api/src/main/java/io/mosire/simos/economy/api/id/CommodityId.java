package io.mosire.simos.economy.api.id;

/**
 * 商品 ID（设计稿 §2/§3）：可交易的粮、布、种子、耕牛等商品的稳定身份，归共用契约层。
 *
 * <p>数量用最小计量单位的定点整数；商品 ID 只是身份，不带计量单位或价格。 裸值 {@code toString()} + {@code static parse} 三件套（铁律 1）。
 */
public record CommodityId(String value) {

  public CommodityId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("CommodityId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层）。 */
  public static CommodityId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("CommodityId 不得为空白: " + text);
    }
    return new CommodityId(text);
  }
}
