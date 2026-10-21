package io.mosire.simos.economy.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Objects;

/**
 * ★★★ <b>D-032 C6：临时运输能力批次的稳定复合键</b>。
 *
 * <p>键 = {@code (household, homeHex, marketRoundId)}。批次是<b>短寿命、市场轮内累积</b>的经济状态： 同一家户在同一 homeHex、同一
 * marketRoundId 只有一个值；不得跨市场轮保留，也不是普通商品库存。
 *
 * <p>★ <b>线格式</b>：复用 {@link WageKeyWireFormat} 的长度前缀编码 （{@code <household 字符数>:<household><homeHex
 * 字符数>:<homeHex><marketRoundId>}）。 {@link HouseholdId} 的合法值可以含 {@code '|'} 等分隔符，裸拼无法与 {@link
 * #parse(String)} 严格互逆； 长度前缀使前三段边界只由长度决定，最后一段 {@code marketRoundId} 吃掉剩余全部字符。
 *
 * @param household 运输能力归属的 operator OWNER 家户；不得为 null
 * @param homeHex 生产时 operator 户的 homeHex；不得为 null
 * @param marketRoundId 本批次将要结算的市场日（绝对 tick）；不得为负
 */
public record TransportCapacityBatchKey(
    HouseholdId household, HexCoord homeHex, long marketRoundId) {

  public TransportCapacityBatchKey {
    Objects.requireNonNull(household, "TransportCapacityBatchKey.household 不得为 null");
    Objects.requireNonNull(homeHex, "TransportCapacityBatchKey.homeHex 不得为 null");
    if (marketRoundId < 0L) {
      throw new IllegalArgumentException(
          "TransportCapacityBatchKey.marketRoundId 不得为负: " + marketRoundId);
    }
  }

  /** 规范串（长度前缀；与 {@link #parse(String)} 互为逆）。 */
  @Override
  @JsonValue
  public String toString() {
    return WageKeyWireFormat.encode(
        household.value(), homeHex.toString(), Long.toString(marketRoundId));
  }

  /**
   * 解析 {@link #toString()} 的产物。
   *
   * @throws IllegalArgumentException 文本为空、长度前缀缺失/非整数/非正/前导零、长度越界或 marketRoundId 非十进制非负整数
   */
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public static TransportCapacityBatchKey parse(String text) {
    String[] parts =
        WageKeyWireFormat.decode(
            "TransportCapacityBatchKey", text, "household", "homeHex", "marketRoundId");
    long marketRoundId;
    try {
      marketRoundId = Long.parseLong(parts[2]);
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException(
          "TransportCapacityBatchKey.marketRoundId 不是十进制整数: " + text, ex);
    }
    if (marketRoundId < 0L) {
      throw new IllegalArgumentException("TransportCapacityBatchKey.marketRoundId 不得为负: " + text);
    }
    return new TransportCapacityBatchKey(
        HouseholdId.parse(parts[0]), HexCoord.parse(parts[1]), marketRoundId);
  }
}
