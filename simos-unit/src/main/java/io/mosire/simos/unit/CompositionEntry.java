package io.mosire.simos.unit;

/**
 * 通用人力/装备表的**一条**（阶段 D3a，2026-10-02 / D-006 + 补裁 R1）：{@code type} + {@code amount}。
 *
 * <p>★★ <b>为什么是一条而不是 Map 的一项</b>：{@code type → amount} 的表要求**保序**（同一份世界往返后条目顺序不变）， 而 {@code
 * Map.copyOf} 不承诺顺序；record 列表把顺序写进结构里，序列化后就是 JSON 数组，读回来逐条等价。
 *
 * <p>★ <b>形状</b>：{@code type} 当前是自然语义 {@link String}（如"重骑兵"/"步枪"），将来可放宽为结构化人力/装备数据；
 * 现在**没有**专门的类型对象，故默认自然文本。{@code amount} 用 {@code long}（人数/件数的量纲在领域侧，本类不解释单位）。
 *
 * <p>★ <b>本类自身的不变量</b>：{@code type} 非空白、{@code amount ≥ 0}。同一张表**不得有重复 type**——那条由 {@link Unit}
 * 的紧凑构造器判（列表级不变量，单条记录看不到别人）。{@code amount} 为 0 合法（"这个 type 存在但当前没有"）。
 *
 * <p>★ 本类**不是增量**：战损/直改的有符号增量用 {@link CompositionDelta}，它不要求 {@code amount ≥ 0}。分开两个类型是为了让
 * "状态表里不可能出现负数"在类型上成立，而不是靠调用方自觉。
 */
public record CompositionEntry(String type, long amount) {

  public CompositionEntry {
    if (type == null || type.isBlank()) {
      throw new IllegalArgumentException("type 不得为空白");
    }
    if (amount < 0L) {
      throw new IllegalArgumentException("amount 必须 ≥ 0: " + amount);
    }
  }
}
