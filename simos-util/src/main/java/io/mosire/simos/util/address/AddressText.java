package io.mosire.simos.util.address;

/** 地址词法工具：裸词判定、引号转义与"按需加引"（spec §3.3/§3.4）。包内可见。 */
final class AddressText {

  private AddressText() {}

  /** 裸词：非空、不含任何空白、且不含 `: . [ ] "`（spec §3.2：kind 与命名空间的合法性判据）。 */
  static boolean isBareWord(String s) {
    if (s == null || s.isEmpty()) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (Character.isWhitespace(c)) {
        return false;
      }
      switch (c) {
        case ':', '.', '[', ']', '"' -> {
          return false;
        }
        default -> {}
      }
    }
    return true;
  }

  /** 无条件加引：内部 `"` 写成 `""`。 */
  static String quote(String s) {
    return '"' + s.replace("\"", "\"\"") + '"';
  }

  /**
   * 名字的规范写法：命中 spec §3.4 三条加引条件才加引（条件 2：为空串或含任意空白）。
   *
   * @param kindAbsent 该名字所属的 Entity 是否缺省了 kind（条件 3：缺 kind 且名字含 `.` 必须加引）
   */
  static String quoteIfNeeded(String name, boolean kindAbsent) {
    boolean needs =
        name.isEmpty()
            || name.chars().anyMatch(Character::isWhitespace)
            || name.indexOf(':') >= 0
            || name.indexOf('[') >= 0
            || name.indexOf(']') >= 0
            || name.indexOf('"') >= 0
            || (kindAbsent && name.indexOf('.') >= 0);
    return needs ? quote(name) : name;
  }
}
