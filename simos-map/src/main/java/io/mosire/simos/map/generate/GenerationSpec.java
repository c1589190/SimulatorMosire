package io.mosire.simos.map.generate;

/**
 * ★ **骨架**：地图生成的参数面。本包由 Task 5 起头，**Task 8 才把它扩成完整参数面**。
 *
 * <p>骨架为什么先建：{@code GameMap} 的 {@code spec} 组件**需要这个类型存在才能编译**，而完整参数面在 Task 8。⇒ 本任务只给 {@code seed}
 * 与 {@link #defaults(long)}，**不替 Task 8 决定字段**（半径、噪声参数、湿度门等一律留白）。
 *
 * <p>★ **{@code spec} 组件从 Task 5 起就非 null**（裁定 R-48-e）：不留"临时可空"的中间世界 —— 那会让 Task 6 的 {@code apply}
 * 与 Task 7 的反射枚举各绕开它一轮，而"收紧"那一步**没人把守**。 空图的种子取规范值 0（空图没有生成历史，语义上说得通）， **不是**"没有种子"。
 *
 * <p>★ 生成参数是**不可变的生成输入**：地图生成后它只是溯源信息（spec §7.4），**不进变更集**。
 *
 * @param seed 随机种子（L7 的落盘点：同种子必须生成同一张图）
 */
public record GenerationSpec(long seed) {

  /**
   * 规范默认值。**收种子、不吞种子** —— 它只是把参数面将来的其余字段一次性定死在一个地方，而不是一个忽略入参的占位实现。
   *
   * <p>{@code GameMap.empty()} 用 {@code defaults(0L)}。
   */
  public static GenerationSpec defaults(long seed) {
    return new GenerationSpec(seed);
  }
}
