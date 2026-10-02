package io.mosire.simos.calendar;

import java.util.Objects;

/**
 * 某日某地的季节状态（设计稿 §5.4）。
 *
 * <p><b>口径</b>：季界当天 {@code dayOfSeason = 1}、{@code progress = 0.0}； {@code progress = (dayOfSeason
 * − 1) / daysInSeason}，恒在 {@code [0,1)}——本季最后一天 progress 为 {@code (daysInSeason−1)/daysInSeason}，不是
 * 1.0。
 *
 * <p>{@code progress} 是给农业/经济连续曲线（农忙/农闲）的钩子；本批只提供 {@code SeasonState}， 不接任何结算/存储。
 *
 * @param phase 季节相位（非空）
 * @param dayOfSeason 本季第几天（1 起，≤ daysInSeason）
 * @param daysInSeason 本季总天数（≥1；温带季 89~94、热带雨季/旱季按黄经窗口）
 * @param progress 本季进度 ∈ [0,1)，见类注口径
 */
public record SeasonState(ClimatePhase phase, int dayOfSeason, int daysInSeason, double progress) {

  /**
   * 构造期校验：相位非空，{@code daysInSeason ≥ 1}，{@code dayOfSeason ∈ [1, daysInSeason]}， {@code progress ∈
   * [0,1)}。
   *
   * @throws IllegalArgumentException 任一范围越界
   * @throws NullPointerException phase 为 null
   */
  public SeasonState {
    Objects.requireNonNull(phase, "phase");
    if (daysInSeason < 1) {
      throw new IllegalArgumentException("daysInSeason 必须 ≥ 1：" + daysInSeason);
    }
    if (dayOfSeason < 1 || dayOfSeason > daysInSeason) {
      throw new IllegalArgumentException("dayOfSeason 必须在 [1," + daysInSeason + "]：" + dayOfSeason);
    }
    if (!(progress >= 0.0) || !(progress < 1.0)) {
      throw new IllegalArgumentException("progress 必须在 [0,1)：" + progress);
    }
  }
}
