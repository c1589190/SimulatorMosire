package io.mosire.simos.map.terrain;

/**
 * 地形类型。**高度带是类型自己的属性** —— 用户裁决 U1 要求"按高度从小到大"。
 *
 * <p>★ 带进词表、**不进分类器的代码**：这样判据可断言（带连续、不重叠、覆盖 [0,1]），分类器退化成一个查表，**不再是第二个藏着阈值的词表**（GSimulator 的 L9
 * 正是那么来的）。
 *
 * <p>★ 逐格的**海拔值**仍在 {@code HexCell}；本类型携带的是它的**带**。
 *
 * <p>★ **不可通行以 {@code moveCost} 的哨兵值表达**（具名常量 {@link #IMPASSABLE_MOVE_COST}，海洋取的就是它），**不另加 {@code
 * passable} 之类的布尔**：通行与否只有一处判据，两个字段表达同一件事时"谁说了算"就必然要再裁一次。 哨兵值远大于任何可通行项，且**不参与任何算术**。
 *
 * <p>★ 高度带**左闭右开**（{@code minHeight <= h < maxHeight}）—— 相邻带天然不重叠，无需特判边界。
 *
 * <p>构造期校验**抛 {@link IllegalArgumentException}、不静默夹取**（与 `hex` 包同族口径）。{@code description}
 * 是**有意不设校验**的一个：它只作文档用途，既不参与判据也不被解引用，null 也不破坏往返。
 */
public record TerrainType(
    String key,
    String name,
    String color,
    double minHeight,
    double maxHeight,
    int food,
    int gold,
    int stone,
    int moveCost,
    String description) {

  /**
   * ★ **不可通行的唯一判据**（M3 C5）：{@code moveCost >= 本常量} ⇒ 不可通行。
   *
   * <p>与 {@code TerrainCatalog} 里海洋的取值同源——**由 R2 守卫钉住**（{@code ImpassableSentinelTest}）。
   * 本常量**不参与任何算术**：它是"此路不通"的标记，不是"很贵的路"。
   */
  public static final int IMPASSABLE_MOVE_COST = 999;

  public TerrainType {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("key 不得为空白");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name 不得为空白");
    }
    if (color == null || !color.matches("#[0-9A-Fa-f]{6}")) {
      throw new IllegalArgumentException("color 必须是 #RRGGBB 形式: " + color);
    }
    if (!(minHeight >= 0.0 && minHeight < maxHeight && maxHeight <= 1.0)) {
      throw new IllegalArgumentException("高度带非法: [" + minHeight + ", " + maxHeight + "]");
    }
    if (moveCost < 1) {
      throw new IllegalArgumentException("moveCost 必须 >= 1: " + moveCost);
    }
    if (food < 0 || gold < 0 || stone < 0) {
      throw new IllegalArgumentException("产出不得为负");
    }
  }
}
