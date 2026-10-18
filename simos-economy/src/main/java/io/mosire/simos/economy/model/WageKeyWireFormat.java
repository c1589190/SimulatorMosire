package io.mosire.simos.economy.model;

/**
 * ★ <b>工资复合键的长度前缀线格式</b>（{@link WageAnchorKey} 使用；包内私有）。
 *
 * <p>构成键的稳定 ID 只保证"非空白"（{@code MarketZoneId} 额外禁 {@code |}），没有共同禁用字符，故不能用裸分隔符拼 {@code
 * toString()}。本类把除最后一段外的每个字段写成 {@code <字符数>:<值>}，最后一段吃掉剩余全部字符；解析只按长度切段， 与字段值可出现的字符无关。
 *
 * <p>格式：两字段 {@code <first.length()>:<first><last>}；三字段 {@code
 * <first.length()>:<first><second.length()>:<second><last>}。
 */
final class WageKeyWireFormat {

  private WageKeyWireFormat() {}

  /** 两字段规范串：{@code <first.length()>:<first><last>}（last 取剩余全部字符）。 */
  static String encode(String first, String last) {
    return first.length() + ":" + first + last;
  }

  /** 三字段规范串：{@code <first.length()>:<first><second.length()>:<second><last>}。 */
  static String encode(String first, String second, String last) {
    return first.length() + ":" + first + second.length() + ":" + second + last;
  }

  /**
   * 解析 {@link #encode} 的产物。
   *
   * @param keyName 抛错消息里的键名
   * @param text 规范串
   * @param fieldNames 字段名（最后一个字段取剩余全部字符）
   * @throws IllegalArgumentException 文本为空、长度前缀缺失/非整数/非正/前导零、长度越界或最后一段为空
   */
  static String[] decode(String keyName, String text, String... fieldNames) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException(keyName + " 不得为空白: " + text);
    }
    if (fieldNames.length < 2) {
      throw new IllegalArgumentException(keyName + " 的线格式至少需要两个字段");
    }
    String[] values = new String[fieldNames.length];
    int cursor = 0;
    for (int i = 0; i < fieldNames.length - 1; i++) {
      int colon = text.indexOf(':', cursor);
      if (colon <= cursor) {
        throw new IllegalArgumentException(
            keyName + " 的规范串缺少 <" + fieldNames[i] + " 长度>: 前缀: " + text);
      }
      int length = lengthOf(keyName, text, cursor, colon, fieldNames[i]);
      int start = colon + 1;
      if (length > text.length() - start) {
        throw new IllegalArgumentException(keyName + " 的 " + fieldNames[i] + " 长度前缀越界: " + text);
      }
      values[i] = text.substring(start, start + length);
      cursor = start + length;
    }
    if (cursor >= text.length()) {
      throw new IllegalArgumentException(
          keyName + " 的 " + fieldNames[fieldNames.length - 1] + " 段不得为空: " + text);
    }
    values[fieldNames.length - 1] = text.substring(cursor);
    return values;
  }

  /** 读取 {@code [from, colon)} 的十进制长度前缀；非整数、非正或带符号/空白均具名拒绝。 */
  private static int lengthOf(String keyName, String text, int from, int colon, String field) {
    String prefix = text.substring(from, colon);
    for (int i = 0; i < prefix.length(); i++) {
      char c = prefix.charAt(i);
      if (c < '0' || c > '9') {
        throw new IllegalArgumentException(keyName + " 的 " + field + " 长度前缀不是十进制非负整数: " + text);
      }
    }
    if (prefix.length() > 1 && prefix.charAt(0) == '0') {
      throw new IllegalArgumentException(keyName + " 的 " + field + " 长度前缀不得有前导零: " + text);
    }
    int length;
    try {
      length = Integer.parseInt(prefix);
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException(keyName + " 的 " + field + " 长度前缀不是整数: " + text, ex);
    }
    if (length <= 0) {
      throw new IllegalArgumentException(keyName + " 的 " + field + " 长度前缀必须为正: " + text);
    }
    return length;
  }
}
