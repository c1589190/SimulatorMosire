package io.mosire.simos.economy.api.id;

/**
 * 运输/在途货物 ID（设计稿 §6.2）：一条 {@code Shipment}（路线、在途日、运费、履约状态）的稳定身份，归 {@code market} 切片。
 *
 * <p>在途商品的唯一产权与数量仍在 {@code ledger} 的在途账户；Shipment 只记路线与履约。 裸值 {@code toString()} + {@code static
 * parse} 三件套（铁律 1）。
 */
public record ShipmentId(String value) {

  public ShipmentId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("ShipmentId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层）。 */
  public static ShipmentId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("ShipmentId 不得为空白: " + text);
    }
    return new ShipmentId(text);
  }
}
