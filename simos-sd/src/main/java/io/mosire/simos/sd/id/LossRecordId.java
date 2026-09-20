package io.mosire.simos.sd.id;

/**
 * 损失记录 ID（spec §二.1）。★ **由代码确定性生成**（不自增、不用随机 UUID）；记录供回放 / AAR（N3）。 裸值 {@code toString()} + {@code
 * static parse} 三件套（铁律 1）。
 */
public record LossRecordId(String value) {

  public LossRecordId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("LossRecordId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  public static LossRecordId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("LossRecordId 不得为空白: " + text);
    }
    return new LossRecordId(text);
  }
}
