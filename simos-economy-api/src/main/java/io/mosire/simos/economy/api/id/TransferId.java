package io.mosire.simos.economy.api.id;

/**
 * 转移凭据 ID（设计稿 §2）：一次双边商品/货币转移的稳定身份，归 {@code ledger} 切片。
 *
 * <p>每笔买方扣款 = 卖方入账 + 政府税入账 + 运输方收入，各腿各自的凭据可回溯到同一 ID。 裸值 {@code toString()} + {@code static parse}
 * 三件套（铁律 1）。
 */
public record TransferId(String value) {

  public TransferId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("TransferId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层）。 */
  public static TransferId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("TransferId 不得为空白: " + text);
    }
    return new TransferId(text);
  }
}
