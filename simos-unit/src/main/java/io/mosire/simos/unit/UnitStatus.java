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
  ENGAGED
}
