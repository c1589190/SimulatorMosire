package io.mosire.simos.social.api.population;

/**
 * 家户人口事件类型（2026-10-09 家户/人口架构 §4.3）：事件是人口状态唯一可回放的落账形状。
 *
 * <ul>
 *   <li>{@link #BIRTH}：出生，加入最小年龄段；
 *   <li>{@link #DEATH}：按年龄段死亡；
 *   <li>{@link #TRANSFER_IN} / {@link #TRANSFER_OUT}：跨家户/跨 unit 转移人口；
 *   <li>{@link #GM_ADJUST}：GM 直接调整；
 *   <li>{@link #RATE_SET}：逐家户率表变更。
 * </ul>
 *
 * <p>★ 常量名即稳定拼写（进事件线格式/回放日志），改名视同改线格式。
 */
public enum PopulationEventType {
  BIRTH,
  DEATH,
  TRANSFER_IN,
  TRANSFER_OUT,
  GM_ADJUST,
  RATE_SET
}
