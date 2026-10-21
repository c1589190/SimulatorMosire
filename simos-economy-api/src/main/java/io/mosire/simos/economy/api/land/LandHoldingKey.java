package io.mosire.simos.economy.api.land;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Objects;

/**
 * ★★ <b>标量土地持有的稳定查询/排序键</b>（D-032 RA1a）：{@code (hex, owner)}。
 *
 * <p>★ 它只是 map 键与稳定枚举顺序，不是“土地块身份”：同一户在同一 hex 的数量是标量，任意数量转移不会产生新键。 自然序固定为<b>先 {@link
 * HexCoord#compareTo(HexCoord)}，再 {@link HouseholdId#value()} 的字符串序</b>，禁止 HashMap 迭代序。
 */
public record LandHoldingKey(HexCoord hex, HouseholdId owner)
    implements Comparable<LandHoldingKey> {

  public LandHoldingKey {
    Objects.requireNonNull(hex, "LandHoldingKey.hex 不得为 null");
    Objects.requireNonNull(owner, "LandHoldingKey.owner 不得为 null");
  }

  @Override
  public int compareTo(LandHoldingKey other) {
    Objects.requireNonNull(other, "LandHoldingKey.other 不得为 null");
    int byHex = hex.compareTo(other.hex);
    if (byHex != 0) {
      return byHex;
    }
    return owner.value().compareTo(other.owner.value());
  }

  /**
   * 规范 map key 串：{@code <hex.toString()>|<owner.value().length()>|<owner.value()>}。
   *
   * <p>长度前缀只服务 JSON map key 的反序列化；它<b>不是</b>块身份，也不改变键语义 {@code (hex, owner)}。
   */
  @Override
  public String toString() {
    String ownerValue = owner.value();
    return hex.toString() + "|" + ownerValue.length() + "|" + ownerValue;
  }

  /**
   * 严格解析 {@link #toString()} 的产物。
   *
   * <p>空串、形态不符、长度不是规范十进制、owner 段长度不匹配或解析后不能逐字重建原串，一律抛 {@link IllegalArgumentException}。第三段允许包含
   * {@code |}，因为 owner 的长度由第二段显式给出。
   */
  public static LandHoldingKey parse(String text) {
    if (text == null || text.isEmpty()) {
      throw new IllegalArgumentException("LandHoldingKey 串不得为空: " + text);
    }
    int firstSeparator = text.indexOf('|');
    if (firstSeparator <= 0) {
      throw new IllegalArgumentException("LandHoldingKey 串缺少 hex 分隔符: " + text);
    }
    int secondSeparator = text.indexOf('|', firstSeparator + 1);
    if (secondSeparator <= firstSeparator + 1) {
      throw new IllegalArgumentException("LandHoldingKey 串缺少 owner 长度段: " + text);
    }
    String hexText = text.substring(0, firstSeparator);
    String lengthText = text.substring(firstSeparator + 1, secondSeparator);
    String ownerText = text.substring(secondSeparator + 1);
    final int declaredLength;
    try {
      declaredLength = Integer.parseInt(lengthText);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("LandHoldingKey owner 长度不是整数: " + text, e);
    }
    if (declaredLength != ownerText.length()) {
      throw new IllegalArgumentException(
          "LandHoldingKey owner 长度不匹配: 声明 "
              + declaredLength
              + "，实际 "
              + ownerText.length()
              + "，串="
              + text);
    }
    LandHoldingKey parsed =
        new LandHoldingKey(HexCoord.parse(hexText), HouseholdId.parse(ownerText));
    if (!parsed.toString().equals(text)) {
      throw new IllegalArgumentException("LandHoldingKey 串不是规范形式: " + text);
    }
    return parsed;
  }
}
