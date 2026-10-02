package io.mosire.simos.calendar;

/**
 * 温带四季（枚举序 = 北半球相位顺序：春→夏→秋→冬）。
 *
 * <p>南半球季节相位整体 +180°：同一个日号上，南半球相位 = 北半球相位 + 2 个季位 （在 {@link ZonedSeasonSystem} 里通过季界节气索引 +12
 * 实现，不在这里翻名字）。
 */
public enum TemperateSeason implements ClimatePhase {
  SPRING("spring", "春"),
  SUMMER("summer", "夏"),
  AUTUMN("autumn", "秋"),
  WINTER("winter", "冬");

  private final String key;
  private final String chineseName;

  TemperateSeason(String key, String chineseName) {
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
