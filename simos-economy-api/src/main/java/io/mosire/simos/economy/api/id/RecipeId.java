package io.mosire.simos.economy.api.id;

/**
 * 生产配方 ID（设计稿 §4.2）：版本化 {@code ProductionRecipe} 的稳定身份，归 {@code production} 切片。
 *
 * <p>配方参数（每规模所需劳动、土地、畜力、原料）随版本演进，ID 标识"哪一份配方"，不是它的参数值。 裸值 {@code toString()} + {@code static parse}
 * 三件套（铁律 1）。
 */
public record RecipeId(String value) {

  public RecipeId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("RecipeId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层）。 */
  public static RecipeId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("RecipeId 不得为空白: " + text);
    }
    return new RecipeId(text);
  }
}
