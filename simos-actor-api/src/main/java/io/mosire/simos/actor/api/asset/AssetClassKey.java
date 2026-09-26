package io.mosire.simos.actor.api.asset;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * ★★ **资产同质性键**（S1 spec §2.3）：{@code kind} 是**封闭词表** {@link AssetKind} 的粗类型， {@code qualities}
 * 是**影响生产的同质性条件** —— "一块 B 等地"与"一块 C 等地"是两个不同的键。
 *
 * <p>★★ **qualities 按键排序**：规范串 {@code "<KIND>|k1=v1|k2=v2"} 里键必须有序，否则 {@code
 * Map.of("a","1","b","2")} 与 {@code Map.of("b","2","a","1")} 会变成**两个键** —— 同一块地两份产权。
 * 排序发生在**构造期**（存进字段的就是规范化后的只读有序表），故 {@code equals} / {@code hashCode} / {@code toString} / {@code
 * parse} 四条路径看到的都是同一份顺序；{@code toString()} 是 {@code parse} 的**逆** （{@code parse(toString())}
 * 恒等于自身）。
 *
 * <p>★ **fail-closed**：粗类型词表外即抛；qualities 的键/值不许含 {@code |} 或 {@code =}（否则规范串不可逆）； qualities 的段没有
 * {@code =} 即抛；**同一个键出现两次即抛**（解析回来只剩一个 ⇒ 不可逆）；键与值都不得为空白 （空白不是名字，静默接受会让"写错一份产权"变成运行时幽灵）。
 *
 * <p>★ {@code qualities == null} **归一成空表**，不是 NPE：{@code null} 与 {@code Map.of()} 表达的是同一件事
 * ——"这个粗类型没有同质性条件"（{@code CATTLE} 就是一个）。
 *
 * <p>★ **{@code qualities} 具体编码哪些维度**：spec §十一 明标**未决策** —— 本类型**不预设任何维度名**， 只保证"给定一组键值，身份唯一且规范"。
 */
public record AssetClassKey(AssetKind kind, Map<String, String> qualities) {

  /** 规范串的段分隔符：{@code "<KIND>|k1=v1|k2=v2"}。 */
  private static final char FIELD_SEPARATOR = '|';

  /** 规范串的键值分隔符。 */
  private static final char KEY_VALUE_SEPARATOR = '=';

  public AssetClassKey {
    if (kind == null) {
      throw new IllegalArgumentException("AssetClassKey 的粗类型不得为 null");
    }
    qualities =
        Collections.unmodifiableMap(
            canonical(qualities)); // ★ 冻在赋值处（SpotBugs 的 EI_EXPOSE_REP 只认它看得见的）
  }

  /**
   * ★ spec §2.3 点名的那个实例的工厂：{@code LAND(arable, quality=B)}。
   *
   * <p>★★ **只此一个工厂**：{@link AssetKind} 的六档是 {@code LAND/CATTLE/TOOL/WORKSHOP/MACHINE/SHIP}， **没有
   * {@code LOOM}** —— spec §2.3 的 {@code LOOM(handloom, tech=T1)} 只是**文档举例**。 凭空发明一个"织机 → 某个
   * AssetKind"的映射，等于替 {@code AssetKind} 改语义； 真要 {@code loom(...)}， 等 {@code AssetKind} 真的增了那一档再加。
   */
  public static AssetClassKey land(Map<String, String> qualities) {
    return new AssetClassKey(AssetKind.LAND, qualities);
  }

  /** 规范串 {@code "<KIND>|k1=v1|k2=v2"}（键**按字典序**，故 {@code toString()} 与构造时的入参顺序无关）。 */
  @Override
  public String toString() {
    StringBuilder out = new StringBuilder(kind.name());
    qualities.forEach(
        (key, value) ->
            out.append(FIELD_SEPARATOR).append(key).append(KEY_VALUE_SEPARATOR).append(value));
    return out.toString();
  }

  /**
   * 按规范串解析：**词表外的粗类型即抛**（消息里点名那个值并列出合法值），qualities 的每段必须是 {@code key=value}（见类注的 fail-closed 清单）。
   */
  public static AssetClassKey parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("AssetClassKey 不得为空白: " + text);
    }
    String[] fields = text.split("\\" + FIELD_SEPARATOR, -1);
    if (fields.length == 1) {
      return new AssetClassKey(parseKind(fields[0]), Map.of());
    }
    // ★ 只为本处的**重复键检测**而建：真正的顺序由构造期的规范化决定。
    Map<String, String> qualities = new LinkedHashMap<>();
    for (int i = 1; i < fields.length; i++) {
      int separator = fields[i].indexOf(KEY_VALUE_SEPARATOR);
      if (separator < 0) {
        throw new IllegalArgumentException(
            "AssetClassKey 的 qualities 段缺少 `=`（规范串将不可逆）: " + fields[i] + "（原文: " + text + "）");
      }
      String key = checkText("键", fields[i].substring(0, separator));
      String value = checkText("值", fields[i].substring(separator + 1));
      if (qualities.putIfAbsent(key, value) != null) {
        throw new IllegalArgumentException(
            "AssetClassKey 的 qualities 键重复（规范串将不可逆）: " + key + "（原文: " + text + "）");
      }
    }
    return new AssetClassKey(parseKind(fields[0]), qualities);
  }

  /** ★★ **规范化**：按键**字典序**排进 {@link TreeMap} —— 入参的迭代顺序在这之后就**不可见**了（只读包装由构造器在赋值处做）。 */
  private static TreeMap<String, String> canonical(Map<String, String> qualities) {
    TreeMap<String, String> sorted = new TreeMap<>();
    if (qualities != null) {
      qualities.forEach((key, value) -> sorted.put(checkText("键", key), checkText("值", value)));
    }
    return sorted;
  }

  /** 粗类型：{@link AssetKind} 词表内的值（词表外即抛，消息里点名并列出合法值）。 */
  private static AssetKind parseKind(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("AssetClassKey 的粗类型不得为空白: " + text);
    }
    try {
      return AssetKind.valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "词表外的资产粗类型: " + text + "（合法值: " + List.of(AssetKind.values()) + "）");
    }
  }

  /** qualities 的键/值：不得为空白、不得含分隔符（两者都会让规范串不可逆）。 */
  private static String checkText(String what, String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("AssetClassKey 的 qualities " + what + " 不得为空白: " + text);
    }
    if (text.indexOf(FIELD_SEPARATOR) >= 0 || text.indexOf(KEY_VALUE_SEPARATOR) >= 0) {
      throw new IllegalArgumentException(
          "AssetClassKey 的 qualities " + what + " 不得含分隔符 `|` / `=`（规范串将不可逆）: " + text);
    }
    return text;
  }
}
