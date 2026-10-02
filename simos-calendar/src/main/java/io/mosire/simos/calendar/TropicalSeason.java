package io.mosire.simos.calendar;

/**
 * 热带两季：雨季 / 旱季（D-018 补裁）。
 *
 * <p>窗口由 {@link SeasonSettings} 的 {@code rainyStartLongitude}/{@code rainyEndLongitude} 配置； 缺省
 * {@code [45°,165°)}、南热带移相 180°。
 */
public enum TropicalSeason implements ClimatePhase {
  RAINY("rainy", "雨季"),
  DRY("dry", "旱季");

  private final String key;
  private final String chineseName;

  TropicalSeason(String key, String chineseName) {
    this.key = key;
    this.chineseName = chineseName;
  }

  @Override
  public String key() {
    return key;
  }

  @Override
  public String chineseName() {
    return chineseName;
  }
}
