package io.mosire.simos.unit.move;

/** 在途行程的状态（M3 spec §4.5）。 */
public enum MovementStatus {
  /** 正在走：还有没付清的段。 */
  IN_TRANSIT,
  /** 所有段都付清了。 */
  ARRIVED,
  /** 下一段不可通行（地图变了）：路径保留、就地暂停。 */
  NEED_REPLAN
}
