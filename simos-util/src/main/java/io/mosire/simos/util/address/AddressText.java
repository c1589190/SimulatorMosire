package io.mosire.simos.util.address;

/** 地址词法工具：裸词判定、引号转义与"按需加引"（spec §3.3/§3.4）。包内可见。 */
final class AddressText {

  private AddressText() {}

  /** 结构化字符：出现任一即不可能作为裸词，且（除 `.` 外）是加引条件 1 的判据。 */
  private static boolean hasStructuralChar(String s) {
    return s.indexOf(':') >= 0 || s.indexOf('[') >= 0 || s.indexOf(']') >= 0 || s.indexOf('"') >= 0;
  }

  /**
   * 裸词：非空、不含任何空白、且不含 `: . [ ] "`（spec §3.2：kind 与命名空间的合法性判据）。
   *
   * <p>注意 `.` 单独判：它是裸词的否决字符，却**不是**加引条件 1 的判据（见 {@link #hasStructuralChar}）—— 缺 kind 的 name 含 `.`
   * 才加引，kind 在时 `region.Nation.区域A` 不能加引。
   */
  static boolean isBareWord(String s) {
    if (s == null || s.isEmpty() || s.indexOf('.') >= 0 || hasStructuralChar(s)) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      if (Character.isWhitespace(s.charAt(i))) {
        return false;
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
            || hasStructuralChar(name)
            || (kindAbsent && name.indexOf('.') >= 0);
    return needs ? quote(name) : name;
  }
}
