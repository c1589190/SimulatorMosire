package io.mosire.simos.economy.api.fx;

/**
 * ★★ <b>一笔外汇成交发生在哪个场所</b>（阶段 2-A2a）：家户对家户，还是政府外汇窗口。
 *
 * <p>★ <b>为什么要记它</b>：设计书 §4.5 的一条判据是"官方汇率<b>只在窗口有量时</b>才拉动市场价" ——
 * 要把这句话变成可核的读数，就必须分得清"这笔成交价是被政府挂牌价定的"还是"市场自己定的"。
 */
public enum FxVenue {
  /** 家户 ↔ 家户（两腿都在家户账上）。 */
  HOUSEHOLD("household"),

  /** 一端是政府国库（外汇窗口的挂单成交）。 */
  GOV_WINDOW("gov_window");

  private final String wire;

  FxVenue(String wire) {
    this.wire = wire;
  }

  /** 线格式短名（读口/日志用）。 */
  public String wire() {
    return wire;
  }
}
