package io.mosire.simos.culture.contract;

/**
 * ★★ <b>文化（Culture）的稳定身份</b>（D-031 spec §6.2；铁律 1）。
 *
 * <p>它表达"某个地方认可的某种文化"这一跨家户/跨格的稳定身份；家户的文化标签经 {@link HouseholdCulture#tag()} 挂到它上面。★ <b>地址是定位方式、ID
 * 是身份</b>：家户搬迁、改名，文化身份不变。
 *
 * <p>★ <b>铁律 1 三件套</b>：非空白守卫 + 裸值 {@code toString()} + {@code static parse}（供变更集 String key 与 JSON
 * key 还原）。本类型<b>零 Jackson 注解</b>：键的读写走各 codec 自己注册的 {@code toString()}/{@code parse} 配对。
 *
 * @param value 非空白规范串
 */
public record CultureId(String value) {

  public CultureId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("CultureId 不得为空白");
    }
  }

  /** 裸值（见类注：它是身份，不是调试输出）。 */
  @Override
  public String toString() {
    return value;
  }

  /** 解析 {@link #toString()} 的产物；空白与 {@code null} 抛（与构造器同口径：宁抛不静默）。 */
  public static CultureId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("CultureId 不得为空白: " + text);
    }
    return new CultureId(text);
  }
}
