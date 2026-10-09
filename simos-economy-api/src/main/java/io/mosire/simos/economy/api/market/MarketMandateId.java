package io.mosire.simos.economy.api.market;

/**
 * ★★ <b>政府市场授权（行政家户挂单）的稳定身份</b>（R1：政府国库户作为市场参与者）。
 *
 * <p>★ 它只校验非空白，<b>不校验格式</b>—— 授权名的拼法由命令面唯一决定（{@code gm:<自定名>} 等）。 裸值 {@code toString()} + {@code
 * static parse} 三件套（铁律 1）：它既进 {@code EconomyData.govMarketMandates} 的键， 也进日志与审计串，必须跨 revision 稳定。
 *
 * <p>★ <b>幂等的载体</b>：同一 id 的重复授权（形状逐值相同）是 no-op，绝不双倍下单； 要改形状必须先 {@code
 * economy.CancelGovernmentMarketOrder} 再以新载荷授权（见 {@code
 * io.mosire.simos.economy.api.market.GovernmentMarketMandate}）。
 */
public record MarketMandateId(String value) {

  public MarketMandateId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("MarketMandateId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（命名属命令层）。 */
  public static MarketMandateId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("MarketMandateId 不得为空白: " + text);
    }
    return new MarketMandateId(text);
  }
}
