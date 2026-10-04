package io.mosire.simos.social.api.id;

/**
 * ★★ <b>家户的稳定身份</b>（2026-10-09 家户/人口架构 §3.1：从 {@code simos-economy-api} 迁入 social 契约层）。
 *
 * <p>铁律 1 的落点：地址（哪一格 / 哪个 unit）是定位方式，<b>ID 是身份</b>。家户从 hex 搬到 unit、阶层变了、 同一视图出现第二个家户，身份都不变。
 *
 * <p>★ <b>它是不透明值对象</b>：规范串（{@link #toString()} / {@link #parse(String)} 互逆）就是 {@link #value()} 本身；
 * 校验只做"非空白"，格式不在这里猜。★ 创世家户 id / 旧档 {@code legacy-} id 的拼写是<b>经济侧</b>的词汇，由 经济契约层的 {@code
 * HouseholdIds} 持有——契约层不背经济迁移语义。
 *
 * <p>★ 本类型<b>零 Jackson 注解</b>（架构 §3.1：social-api 不依赖 Jackson）：键的读写走各 codec 自己注册的 {@code
 * toString()}/{@code parse} 配对，值的线格式由持有它的模块 codec 负责。
 *
 * @param value 非空白规范串
 */
public record HouseholdId(String value) {

  public HouseholdId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("HouseholdId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白（同其余 opaque id；格式权威在创世/旧档两处经济侧拼写点）。 */
  public static HouseholdId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("HouseholdId 不得为空白: " + text);
    }
    return new HouseholdId(text);
  }
}
