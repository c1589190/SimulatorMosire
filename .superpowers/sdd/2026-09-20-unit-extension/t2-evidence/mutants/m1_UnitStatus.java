package io.mosire.simos.unit;

/**
 * 单位的三态作战状态（spec §三.2，E3）：**移动 / 休整 / 交战**。
 *
 * <p>★ **与 {@code MovementStatus} 正交**：后者是**路线进度**（走没走完 / 地图变了），由 {@code UnitMoves.evaluate}
 * 现算、**不落库**；本类型是**持久作战状态**（普通字段，P13）。二者类型不混、字段不合——把本类型塞进 {@code MovementStatus} 是 spec §三.1
 * 明文禁止的。历史由 M4 的 revision 承载（与 {@code member} 同族），故 T1 用普通字段。
 */
public enum UnitStatus {
  MOVING,
  RESTING,
  ENGAGED;

  /**
   * 三态 → 速度因子（spec §三.2 / P5，‰ 定点）：{@code MOVING=1000}、{@code RESTING=500}、{@code ENGAGED=250}。
   *
   * <p>★ **v1 值、可调、非永久**（P5 明文）：只借机制形状，不承诺数值真值。三值互不相等且 {@code RESTING/ENGAGED < MOVING} 是 E3 的全部要求。
   */
  public int factorPerMille() {
    return switch (this) {
      case MOVING -> 1000;
      case RESTING -> 1000;
      case ENGAGED -> 1000;
    };
  }
}
