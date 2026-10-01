package io.mosire.simos.unit;

/**
 * 人力/装备表的**有符号增量**（阶段 D3a，2026-10-02 / D-006 + 补裁 R1/R2）：与 {@link CompositionEntry} 同形（{@code type
 * + amount}）， 但 {@code amount} **允许为负**——负增量表示损失/减少，正增量表示补充/新建。
 *
 * <p>★ <b>为什么不复用 {@link CompositionEntry}</b>：状态表的不变量是 {@code amount ≥ 0}（类型上守死），而增量必须能表达负数。
 * 两个类型共享同一个 JSON 形状 {@code {type, amount}}，命令载荷因此仍是一张有序条目表，只是**语义槽位不同**：
 *
 * <ul>
 *   <li>{@code unit.ApplyCasualties}：两条增量都必须 ≤ 0（战损只减员）；
 *   <li>{@code unit.AdjustComposition}：有符号——正增量可新建 type，负增量要求 type 已存在且 {@code |Δ| ≤ 当前值}。
 * </ul>
 *
 * <p>★ <b>本类自身只校验</b>：{@code type} 非空白；{@code amount} 不限符号。上界 / 存在性 / 重复 type 的判据在 {@code
 * UnitOperations} 的对应操作里（它们要看当前单位状态）。
 */
public record CompositionDelta(String type, long amount) {

  public CompositionDelta {
    if (type == null || type.isBlank()) {
      throw new IllegalArgumentException("type 不得为空白");
    }
  }
}
