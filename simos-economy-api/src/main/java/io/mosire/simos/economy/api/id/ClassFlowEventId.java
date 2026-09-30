package io.mosire.simos.economy.api.id;

/**
 * ★ <b>阶层流动事件（{@code ClassFlowEvent}）的稳定身份</b>：某一天第几笔上下行迁移。身份 = {@code (tick, sequence)}
 * 的纯函数，{@code sequence} 是该 tick 内事件的确定性序号（0 起）。
 *
 * <p>★ 裸值 {@code toString()} + {@code static parse} 三件套；规范串不含 {@code "."}。
 *
 * @param value 非空白且不含 {@code "."} 的稳定规范串
 */
public record ClassFlowEventId(String value) {

  /** {@link #of(long, long)} 的固定前缀（唯一拼写点）。 */
  public static final String PREFIX = "flow-";

  public ClassFlowEventId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("ClassFlowEventId 不得为空白");
    }
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException("ClassFlowEventId 不得含 '.'（地址会被第一个点截断）: " + value);
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白与不含 {@code "."}；不做格式约束。 */
  public static ClassFlowEventId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("ClassFlowEventId 不得为空白: " + text);
    }
    return new ClassFlowEventId(text);
  }

  /** 新 id 的唯一拼写点：{@code flow-<tick>-<sequence>}（不含 {@code "."}）。 */
  public static ClassFlowEventId of(long tick, long sequence) {
    if (tick < 0L || sequence < 0L) {
      throw new IllegalArgumentException(
          "ClassFlowEventId.of 的 tick/sequence 必须 >= 0: " + tick + "/" + sequence);
    }
    return new ClassFlowEventId(PREFIX + tick + "-" + sequence);
  }
}
