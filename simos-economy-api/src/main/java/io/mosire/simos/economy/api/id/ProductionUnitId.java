package io.mosire.simos.economy.api.id;

/**
 * 生产单元 ID（设计稿 §4.2）：一次单位生产的稳定身份，归 {@code production} 切片；不复用军事 {@code Unit} 的 ID。
 *
 * <p>裸值 {@code toString()} + {@code static parse} 三件套（铁律 1）；不自增、不用随机 UUID。
 */
public record ProductionUnitId(String value) {

  public ProductionUnitId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("ProductionUnitId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层）。 */
  public static ProductionUnitId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("ProductionUnitId 不得为空白: " + text);
    }
    return new ProductionUnitId(text);
  }
}
