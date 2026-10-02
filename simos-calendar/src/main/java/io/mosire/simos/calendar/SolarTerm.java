package io.mosire.simos.calendar;

/**
 * 二十四节气：从黄经 0°（春分）起序，每项间隔 15°，枚举序即黄经序。
 *
 * <p><b>节气表</b>（index / {@link #key()} / {@link #chineseName()} / {@link #longitude()}）：
 *
 * <pre>
 *  0 chunfen 春分 0      1 qingming 清明 15    2 guyu 谷雨 30       3 lixia 立夏 45
 *  4 xiaoman 小满 60     5 mangzhong 芒种 75   6 xiazhi 夏至 90     7 xiaoshu 小暑 105
 *  8 dashu 大暑 120      9 liqiu 立秋 135      10 chushu 处暑 150   11 bailu 白露 165
 * 12 qiufen 秋分 180    13 hanlu 寒露 195      14 shuangjiang 霜降 210 15 lidong 立冬 225
 * 16 xiaoxue 小雪 240   17 daxue 大雪 255      18 dongzhi 冬至 270  19 xiaohan 小寒 285
 * 20 dahan 大寒 300     21 lichun 立春 315     22 yushui 雨水 330   23 jingzhe 惊蛰 345
 * </pre>
 *
 * <p>不变式：{@code values()[i].longitude() == i × 15}（i = 0..23）。{@link SolarTerms#termOf(long)}
 * 直接按枚举序取项，不另建映射表；本枚举是 24 个节气名与黄经的<b>唯一来源</b>。
 */
public enum SolarTerm {
  CHUNFEN("chunfen", "春分", 0.0),
  QINGMING("qingming", "清明", 15.0),
  GUYU("guyu", "谷雨", 30.0),
  LIXIA("lixia", "立夏", 45.0),
  XIAOMAN("xiaoman", "小满", 60.0),
  MANGZHONG("mangzhong", "芒种", 75.0),
  XIAZHI("xiazhi", "夏至", 90.0),
  XIAOSHU("xiaoshu", "小暑", 105.0),
  DASHU("dashu", "大暑", 120.0),
  LIQIU("liqiu", "立秋", 135.0),
  CHUSHU("chushu", "处暑", 150.0),
  BAILU("bailu", "白露", 165.0),
  QIUFEN("qiufen", "秋分", 180.0),
  HANLU("hanlu", "寒露", 195.0),
  SHUANGJIANG("shuangjiang", "霜降", 210.0),
  LIDONG("lidong", "立冬", 225.0),
  XIAOXUE("xiaoxue", "小雪", 240.0),
  DAXUE("daxue", "大雪", 255.0),
  DONGZHI("dongzhi", "冬至", 270.0),
  XIAOHAN("xiaohan", "小寒", 285.0),
  DAHAN("dahan", "大寒", 300.0),
  LICHUN("lichun", "立春", 315.0),
  YUSHUI("yushui", "雨水", 330.0),
  JINGZHE("jingzhe", "惊蛰", 345.0);

  private final String key;
  private final String chineseName;
  private final double longitude;

  SolarTerm(String key, String chineseName, double longitude) {
    this.key = key;
    this.chineseName = chineseName;
    this.longitude = longitude;
  }

  /** 稳定标识（小写拼音，读口/GUI 用）。 */
  public String key() {
    return key;
  }

  /** 中文名（如“立春”）。 */
  public String chineseName() {
    return chineseName;
  }

  /** 该节气的太阳黄经（度，15° 的倍数，范围 [0,360)）。 */
  public double longitude() {
    return longitude;
  }
}
