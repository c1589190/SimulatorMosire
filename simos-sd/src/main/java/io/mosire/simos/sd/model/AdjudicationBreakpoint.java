package io.mosire.simos.sd.model;

/**
 * AI 决策断点标识（spec §八.2 的 D1~D8）。**裸值 {@code toString()} + {@code static parse} 三件套**（铁律 1 同族）。
 *
 * <p>★ 执行期取代说明：**spec 未定义本类型的形状**（§三.5 的 {@code Verdict} 用到它，§八.2 列了 D1~D8 八个断点，但 §五 未给类型）。
 * 实现期定为稳定标识类型（D2 的 {@code Breakpoints} 以常量引用本类型）；D1 + D3 **合并为同一次调用**（§八.2）。
 */
public record AdjudicationBreakpoint(String value) {

  public AdjudicationBreakpoint {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("AdjudicationBreakpoint 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  public static AdjudicationBreakpoint parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("AdjudicationBreakpoint 不得为空白: " + text);
    }
    return new AdjudicationBreakpoint(text);
  }
}
