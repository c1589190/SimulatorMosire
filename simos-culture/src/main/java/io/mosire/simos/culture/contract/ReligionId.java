package io.mosire.simos.culture.contract;

/**
 * ★★ <b>宗教（Religion）的稳定身份</b>（D-031 spec §6.2；铁律 1）。
 *
 * <p>它表达家户所认可/归属的宗教这一非经济社会存在；本轮只做<b>typed 标签</b>，<b>不</b>实现传播、同化、宗教利息（riba）等真实效果 （spec §2 非目标）。
 *
 * <p>★ <b>铁律 1 三件套</b>：非空白守卫 + 裸值 {@code toString()} + {@code static parse}；零 Jackson 注解。
 *
 * @param value 非空白规范串
 */
public record ReligionId(String value) {

  public ReligionId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("ReligionId 不得为空白");
    }
  }

  /** 裸值（见类注）。 */
  @Override
  public String toString() {
    return value;
  }

  /** 解析 {@link #toString()} 的产物；空白与 {@code null} 抛。 */
  public static ReligionId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("ReligionId 不得为空白: " + text);
    }
    return new ReligionId(text);
  }
}
