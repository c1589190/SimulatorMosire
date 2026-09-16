package io.mosire.simos.util.time;

import java.util.List;

/**
 * 时态序列：分段常量 + 离散事件（总纲 §4.7）。
 *
 * <p>**不做插值**：段内恒定，只在段边界或事件处跳变。连续变化（如人口那种积分型序列）由 SocialSimos 自己实现本接口，本模块只保证四条时间语义被它继承（spec §七）。
 */
public interface TemporalSeries<T> {

  /** 即时计算，不物化中间点。 */
  T valueAt(SimosTimestamp t);

  /** 分段常量，按 `from` 严格升序。 */
  List<Segment<T>> segments();

  /** 离散跳变，按插入序（同刻多事件即"插入序"）。 */
  List<Event<T>> events();
}
