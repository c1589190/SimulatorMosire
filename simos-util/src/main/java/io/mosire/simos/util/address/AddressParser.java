package io.mosire.simos.util.address;

import java.util.ArrayList;
import java.util.List;

/**
 * 地址解析：切段（`:`，引号内的不切）、宽容写法（`.[`）、按位置与表面形式判定段类型（spec §3.2/§3.5）。 包内可见——对外只有 {@link
 * Address#parse(String)}。
 */
final class AddressParser {

  private AddressParser() {}

  static Address parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("地址不得为空");
    }
    List<String> tokens = new ArrayList<>();
    List<String> raw = splitOnColons(text);
    for (int i = 0; i < raw.size(); i++) {
      String token = raw.get(i);
      if (token.isEmpty()) {
        throw new IllegalArgumentException("第 " + (i + 1) + " 段为空：`" + text + "`");
      }
      tokens.addAll(splitTolerantDot(token, text));
    }
    if (tokens.size() < 2) {
      throw new IllegalArgumentException("地址至少两段（命名空间 + 根主体）：`" + text + "`");
    }
    List<AddressSegment> segments = new ArrayList<>(tokens.size());
    for (int i = 0; i < tokens.size(); i++) {
      segments.add(toSegment(tokens.get(i), i, text));
    }
    return new Address(segments);
  }

  /** 按 `:` 切段；引号内的 `:` 按字面处理（连续两个 `"` 是转义，来回抵消）。 */
  private static List<String> splitOnColons(String text) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean inQuotes = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '"') {
        inQuotes = !inQuotes;
      } else if (c == ':' && !inQuotes) {
        out.add(cur.toString());
        cur.setLength(0);
        continue;
      }
      cur.append(c);
    }
    if (inQuotes) {
      throw new IllegalArgumentException("引号未闭合：`" + text + "`");
    }
    out.add(cur.toString());
    return out;
  }

  /** 兼容写法：未加引号的 `.` 紧跟 `[` 时在此断开（`Map1.[4,3]` → `Map1` + `[4,3]`）。 */
  private static List<String> splitTolerantDot(String token, String whole) {
    int idx = indexOfUnquotedDotBeforeBracket(token);
    if (idx < 0) {
      return List.of(token);
    }
    String left = token.substring(0, idx);
    String right = token.substring(idx + 1);
    if (left.isEmpty() || right.isEmpty()) {
      throw new IllegalArgumentException(
          "兼容写法 `.` `[` 的两侧不得为空：`" + token + "`（地址：`" + whole + "`）");
    }
    return List.of(left, right);
  }

  private static int indexOfUnquotedDotBeforeBracket(String token) {
    boolean inQuotes = false;
    for (int i = 0; i < token.length(); i++) {
      char c = token.charAt(i);
      if (c == '"') {
        inQuotes = !inQuotes;
      } else if (!inQuotes && c == '.' && i + 1 < token.length() && token.charAt(i + 1) == '[') {
        return i;
      }
    }
    return -1;
  }

  /** 段类型判定（spec §3.2 表）：位置 + 表面形式，没有隐式兜底。 */
  private static AddressSegment toSegment(String token, int position, String whole) {
    if (position == 0) {
      if (!AddressText.isBareWord(token)) {
        throw new IllegalArgumentException("首段必须是裸词的命名空间：`" + token + "`（地址：`" + whole + "`）");
      }
      return new Namespace(token);
    }
    if (token.charAt(0) == '[') {
      return toIndex(token, whole);
    }
    int dot = indexOfUnquotedDot(token);
    if (dot < 0) {
      if (isQuoted(token)) {
        return Entity.of(unquote(token));
      }
      if (!AddressText.isBareWord(token)) {
        throw new IllegalArgumentException(
            "第 " + (position + 1) + " 段既不是裸词也不是引号包裹的名字：`" + token + "`（地址：`" + whole + "`）");
      }
      return position == 1 ? Entity.of(token) : new Property(token);
    }
    String left = token.substring(0, dot);
    String right = token.substring(dot + 1);
    if (isQuoted(left)) {
      return Entity.of(nameParts(token, whole));
    }
    if (!AddressText.isBareWord(left)) {
      throw new IllegalArgumentException("kind 必须是裸词或整段加引：`" + left + "`（地址：`" + whole + "`）");
    }
    return Entity.of(left, nameParts(right, whole));
  }

  private static Index toIndex(String token, String whole) {
    if (!token.endsWith("]")) {
      throw new IllegalArgumentException("Index 段未闭合：`" + token + "`（地址：`" + whole + "`）");
    }
    String body = token.substring(1, token.length() - 1);
    if (body.isEmpty()) {
      throw new IllegalArgumentException("Index 段不得为空：`" + token + "`（地址：`" + whole + "`）");
    }
    List<Integer> coords = new ArrayList<>();
    for (String part : body.split(",", -1)) {
      try {
        coords.add(Integer.parseInt(part.strip()));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("Index 坐标不是整数：`" + part + "`（地址：`" + whole + "`）", e);
      }
    }
    return new Index(coords);
  }

  /** 第一个不在引号内的 `.`，没有则返回 -1。 */
  private static int indexOfUnquotedDot(String token) {
    return indexOfUnquotedDotFrom(token, 0);
  }

  private static int indexOfUnquotedDotFrom(String token, int from) {
    boolean inQuotes = false;
    for (int i = 0; i < token.length(); i++) {
      char c = token.charAt(i);
      if (c == '"') {
        inQuotes = !inQuotes;
      } else if (!inQuotes && c == '.' && i >= from) {
        return i;
      }
    }
    return -1;
  }

  /** 名字部分：按未加引号的 `.` 切组件，各组件去引号后以 `.` 连接（spec §3.2）。 */
  private static String nameParts(String part, String whole) {
    StringBuilder sb = new StringBuilder();
    int start = 0;
    while (true) {
      int dot = indexOfUnquotedDotFrom(part, start);
      String component = dot < 0 ? part.substring(start) : part.substring(start, dot);
      sb.append(componentText(component, whole));
      if (dot < 0) {
        return sb.toString();
      }
      sb.append('.');
      start = dot + 1;
    }
  }

  private static String componentText(String component, String whole) {
    if (isQuoted(component)) {
      return unquote(component);
    }
    if (AddressText.isBareWord(component)) {
      return component;
    }
    throw new IllegalArgumentException(
        "名字组件必须加引号（含 : [ ] \" 或空白）：`" + component + "`（地址：`" + whole + "`）");
  }

  /** 整个 token 是否恰好是一个引号包裹的段（`""` 转义计入内容）。 */
  private static boolean isQuoted(String s) {
    if (s.length() < 2 || s.charAt(0) != '"') {
      return false;
    }
    int i = 1;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == '"') {
        if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
          i += 2;
          continue;
        }
        return i == s.length() - 1;
      }
      i++;
    }
    return false;
  }

  /** 引号包裹 token 的内容：`""` → `"`。 */
  private static String unquote(String s) {
    return s.substring(1, s.length() - 1).replace("\"\"", "\"");
  }
}
