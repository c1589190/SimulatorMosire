package io.mosire.simos.calendar;

/**
 * 季节相位：温带四季或热带雨旱两季的共同契约（设计稿 §5.4）。
 *
 * <p>封闭集合只允许 {@link TemperateSeason} 与 {@link TropicalSeason}；读口/GUI 用 {@link #key()} 作为稳定字段，用
 * {@link #chineseName()} 作为显示文本。
 */
public sealed interface ClimatePhase permits TemperateSeason, TropicalSeason {

  /** 稳定标识（英文小写，如 {@code "spring"} / {@code "rainy"}）。 */
  String key();

  /** 中文显示名（如“春” / “雨季”）。 */
  String chineseName();
}
