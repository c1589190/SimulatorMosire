package io.mosire.simos.economy.api.id;

/**
 * 产业 ID（新经济设计 §3.1）：农业/手工业等**产业整体**的稳定身份，归 {@code economy} 切片的产业表。
 *
 * <p>聚合式设计不再逐生产单位登记：一个 {@code IndustryId} 就是"一整个产业"，周期/进度/日投入/产出函数都挂在它上面。 裸值 {@code toString()} +
 * {@code static parse} 三件套（铁律 1）。
 */
public record IndustryId(String value) {

  public IndustryId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("IndustryId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层）。 */
  public static IndustryId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("IndustryId 不得为空白: " + text);
    }
    return new IndustryId(text);
  }
}
