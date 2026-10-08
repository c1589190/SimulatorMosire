package io.mosire.simos.economy.api.fx;

/**
 * ★★ <b>一笔外汇委托的方向</b>（阶段 2-A2a；约束设计书 §3.2）：只有两档，且口径以 {@code base}（标的币）为准。
 *
 * <pre>
 * BUY  = 买入 base、付出 quote   ⇒ 限价 = 愿付<b>上限</b>（quote per base）
 * SELL = 卖出 base、收进 quote   ⇒ 限价 = 愿收<b>下限</b>（quote per base）
 * </pre>
 *
 * <p>★ <b>为什么方向不写成两个类</b>：{@code base}/{@code quote} 已经把"换出什么、换进什么"表达清楚了，再加两个类型会让
 * 撮合侧出现第二份"哪边付出什么"的判据（本仓最反对的"同一事实两处拼写"）。方向只回答"谁付出 base"。
 */
public enum FxSide {
  /** 买入 base、付出 quote。 */
  BUY("buy"),

  /** 卖出 base、收进 quote。 */
  SELL("sell");

  private final String wire;

  FxSide(String wire) {
    this.wire = wire;
  }

  /** 线格式短名（读口/日志用；不进任何键）。 */
  public String wire() {
    return wire;
  }
}
