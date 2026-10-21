package io.mosire.simos.economy.api.land;

import java.util.Objects;

/**
 * ★★★ <b>标量 LAND 的业务拒绝</b>（D-032 RA1a）：异常消息以 {@link LandFailureReason#code()} 开头，调用方可读 {@link
 * #reason()} 做具名分支。
 *
 * <p>★ 它继承 {@link IllegalArgumentException}：非法值/非法账本不是环境故障，不掩盖为 {@link RuntimeException}。参数为 {@code
 * null} 仍可用 {@link NullPointerException}（见各值类型构造器），因为“没有这个参数” 与“这个值违反 LAND 不变量”是两类问题。
 */
public final class LandViolationException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  private final LandFailureReason reason;

  /**
   * @param reason 具名失败原因；不得为 null
   * @param detail 人读补充说明；不得为 null（空串可用，但不要用 null 掩盖原因）
   */
  public LandViolationException(LandFailureReason reason, String detail) {
    super(
        Objects.requireNonNull(reason, "reason").code()
            + ": "
            + Objects.requireNonNull(detail, "detail"));
    this.reason = reason;
  }

  /** 具名失败原因。 */
  public LandFailureReason reason() {
    return reason;
  }
}
