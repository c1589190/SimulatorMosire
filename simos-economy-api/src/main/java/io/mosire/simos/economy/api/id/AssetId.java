package io.mosire.simos.economy.api.id;

/**
 * 生产资料批次 ID（设计稿 §4.1）：土地、耕牛、农具、机器等耐久资产的稳定身份，归 {@code property} 切片。
 *
 * <p>裸值 {@code toString()} + {@code static parse} 三件套（铁律 1）；改名/迁址不改 ID，不自增、不用随机 UUID。
 */
public record AssetId(String value) {

  public AssetId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("AssetId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层）。 */
  public static AssetId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("AssetId 不得为空白: " + text);
    }
    return new AssetId(text);
  }
}
