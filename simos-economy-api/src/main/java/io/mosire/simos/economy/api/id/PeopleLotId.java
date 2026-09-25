package io.mosire.simos.economy.api.id;

/**
 * 人口批次 ID（设计稿 §2.1）：{@code PeopleLot} 代表 {@code count} 个属性与权利完全相同的人，本 ID 是该批次的稳定身份。
 *
 * <p>裸值 {@code toString()} + {@code static parse} 三件套（铁律 1）；调用方给的短名 tag，不自增、不用随机 UUID。
 */
public record PeopleLotId(String value) {

  public PeopleLotId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("PeopleLotId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层）。 */
  public static PeopleLotId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("PeopleLotId 不得为空白: " + text);
    }
    return new PeopleLotId(text);
  }
}
