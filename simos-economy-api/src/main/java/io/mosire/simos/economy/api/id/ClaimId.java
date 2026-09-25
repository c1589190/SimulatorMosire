package io.mosire.simos.economy.api.id;

/**
 * 索取权（应收应付）ID（设计稿 §5/§6.2）：拖欠、欠薪、欠税、本金债权等一条 {@code Claim} 的稳定身份，归 {@code ledger} 切片。
 *
 * <p>实收与应收分开记账，每条索取权是一个稳定对象。裸值 {@code toString()} + {@code static parse} 三件套（铁律 1）。
 */
public record ClaimId(String value) {

  public ClaimId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("ClaimId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层）。 */
  public static ClaimId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("ClaimId 不得为空白: " + text);
    }
    return new ClaimId(text);
  }
}
