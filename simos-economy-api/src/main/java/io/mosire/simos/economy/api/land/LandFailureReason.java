package io.mosire.simos.economy.api.land;

/**
 * ★★★ <b>标量 LAND 的具名失败原因</b>（D-032 RA1a，2026-10-19）。
 *
 * <p>本枚举是公共契约：值对象构造与纯账本校验都只暴露这里的 reason，不把内部校验细节或异常类型当成跨责任区口径。 每个常量带一个稳定的 {@link
 * #code()}（小写连字符串），日志、异常消息与验收判据都读这个串，不读 {@link #name()}。
 *
 * <p>★ <b>0 不是错误</b>：{@link #LAND_ZERO_QUANTITY} 只用于“持有/转移/意向的数量必须为正”的场景；土地市场单价为 0 是合法的（见 {@link
 * LandMarketIntent}），不得套用本枚举拒绝。
 */
public enum LandFailureReason {

  /** 涉及的 hex 不在 {@link LandArableSupply} 中（土地总量权威没有这一格）。 */
  LAND_UNKNOWN_HEX("land-unknown-hex"),

  /** 数量为负（持有、转移量、订单量或可耕总量）。 */
  LAND_NEGATIVE_QUANTITY("land-negative-quantity"),

  /** 数量为 0，而该场景要求严格为正。 */
  LAND_ZERO_QUANTITY("land-zero-quantity"),

  /** 转移的源/目标家户相同；跨 hex 换地必须拆成两笔独立转移。 */
  LAND_SAME_OWNER("land-same-owner"),

  /** 源家户在该 hex 的持有量不足以支付本次转移。 */
  LAND_INSUFFICIENT_HOLDING("land-insufficient-holding"),

  /** 初始持有按 hex 汇总后不等于该 hex 的固定可耕总量。 */
  LAND_ARABLE_MISMATCH("land-arable-mismatch"),

  /** 同一 {@code (hex, owner)} 在初始持有里出现两次。 */
  LAND_DUPLICATE_HOLDING("land-duplicate-holding"),

  /** 整数加减/求和使用 {@link Math#addExact(long, long)} 等精确运算时溢出。 */
  LAND_OVERFLOW("land-overflow"),

  /** 市场意向形状非法（目前是单价为负）。 */
  LAND_ORDER_INVALID("land-order-invalid");

  private final String code;

  LandFailureReason(String code) {
    this.code = code;
  }

  /** 稳定的小写连字符代码；日志/异常/验收读它，不读枚举名。 */
  public String code() {
    return code;
  }
}
