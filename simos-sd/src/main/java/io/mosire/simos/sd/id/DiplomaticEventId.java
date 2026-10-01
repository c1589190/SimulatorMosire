package io.mosire.simos.sd.id;

/**
 * 外交事件（D-005 / R6）的 ID：**事件记录**（多国谈判逐 tick 记录参与国与内容）的唯一键。
 *
 * <p>★ 与既有一组 id 类型同制：**裸值 {@code toString()} + {@code static parse} 三件套**（铁律 1）。它有两条来源：
 *
 * <ol>
 *   <li>载荷显式给的 id（GM / 决策人可指定稳定的可引用名）；
 *   <li>缺省合成：见 {@link io.mosire.simos.sd.model.DiplomaticEventIds#synthesize}（{@code tick + 该 tick
 *       下已有事件数} 的纯函数，可重放）。
 * </ol>
 *
 * <p>★ 它**不是自增、不是随机 UUID**：同一份 base + 同一载荷恒得同一 id ⇒ 满足铁律 2 的可重放。
 */
public record DiplomaticEventId(String value) {

  public DiplomaticEventId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("DiplomaticEventId 不得为空白");
    }
  }

  /** 裸值（变更集 key 与 JSON 边界用；不是给人看的调试输出）。 */
  @Override
  public String toString() {
    return value;
  }

  /** 解析 {@link #toString()} 的产物；空白与 {@code null} 抛（宁抛不静默）。 */
  public static DiplomaticEventId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("DiplomaticEventId 不得为空白: " + text);
    }
    return new DiplomaticEventId(text);
  }
}
