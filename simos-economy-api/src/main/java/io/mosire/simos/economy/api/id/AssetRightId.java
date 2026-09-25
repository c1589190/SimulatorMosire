package io.mosire.simos.economy.api.id;

/**
 * 资产权利 ID（设计稿 §4.1）：所有权份额、控制/租用/抵押权等一条权利的稳定身份，归 {@code property} 切片。
 *
 * <p>占有、控制、投入是三件事，绝不互相推断；每条权利各有独立 ID。裸值 {@code toString()} + {@code static parse} 三件套（铁律 1）。
 */
public record AssetRightId(String value) {

  public AssetRightId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("AssetRightId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层）。 */
  public static AssetRightId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("AssetRightId 不得为空白: " + text);
    }
    return new AssetRightId(text);
  }
}
