package io.mosire.simos.culture.contract;

/**
 * ★★ <b>习俗标签（Custom tag）</b>（D-031 spec §6.2）。
 *
 * <p>它是一段<b>开放</b>的习俗词条（如 {@code "patrilineal"} / {@code "communal-grazing"}），由文化标签的 {@link
 * CultureTag#customs()} 集合承载。★ 本轮它<b>不</b>参与任何公式，也<b>不</b>自动触发效果——只是可编辑、可回放的 typed 标签。
 *
 * <p>★ <b>铁律 1 三件套</b>：非空白守卫 + 裸值 {@code toString()} + {@code static parse}；零 Jackson 注解。
 *
 * @param value 非空白词条
 */
public record CustomTag(String value) {

  public CustomTag {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("CustomTag 不得为空白");
    }
  }

  /** 裸值（见类注）。 */
  @Override
  public String toString() {
    return value;
  }

  /** 解析 {@link #toString()} 的产物；空白与 {@code null} 抛。 */
  public static CustomTag parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("CustomTag 不得为空白: " + text);
    }
    return new CustomTag(text);
  }
}
