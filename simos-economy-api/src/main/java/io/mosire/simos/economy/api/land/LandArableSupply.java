package io.mosire.simos.economy.api.land;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.mosire.simos.map.hex.HexCoord;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * ★★★ <b>每 hex 可耕总量的瞬态快照</b>（D-032 L3/B3）。
 *
 * <p>★★ <b>它不是持久状态组件</b>：不进 {@code EconomyData} / ChangeSet / Codec / Payload，也不从 {@code
 * landHoldings} 反推。真生产路径由 app 从 Map 土地组件树逐 holding hex 读取并构造本值，显式注入 {@code EconomySession}/{@code
 * EconomyDayStepper}；Map 是 arable 总量的唯一持久权威。
 *
 * <p>★ <b>持有表只表达分配</b>：任何持有/转移涉及的 hex 都必须在本表里，且账本要求每 hex 的持有合计恰等于这里的值。本快照只覆盖本次账本涉及的格； 因此空表 +
 * 空持有是合法的空账本。
 *
 * <p>★ <b>不可变、确定性视图</b>：构造时防御拷贝进按 {@link HexCoord} 自然序排序的 {@link TreeMap}，再包成不可修改
 * 视图；同一输入两次构造逐值相等，枚举顺序与调用方传入的 Map 迭代序无关。
 *
 * <p>★ <b>线格式仅供瞬态对象自包含编解码</b>：record 保持 {@code Map<HexCoord, Long>} 的 API 形状，线格式由 {@link
 * LandArableSupplyWireCodec} 负责；该线格式不构成 Economy 的持久组件。无旧 LAND 线格式兼容（Alpha 不迁旧档）。
 *
 * @param arableMilliMuByHex 每 hex 可耕总量（千分亩）；key/value 非 null，value {@code >= 0}
 */
@JsonSerialize(using = LandArableSupplyWireCodec.Serializer.class)
@JsonDeserialize(using = LandArableSupplyWireCodec.Deserializer.class)
public record LandArableSupply(Map<HexCoord, Long> arableMilliMuByHex) {

  public LandArableSupply {
    Objects.requireNonNull(arableMilliMuByHex, "LandArableSupply.arableMilliMuByHex 不得为 null");
    TreeMap<HexCoord, Long> sorted = new TreeMap<>();
    for (Map.Entry<HexCoord, Long> entry : arableMilliMuByHex.entrySet()) {
      HexCoord hex = Objects.requireNonNull(entry.getKey(), "LandArableSupply 的 key 不得为 null");
      Long value = Objects.requireNonNull(entry.getValue(), "LandArableSupply 的 value 不得为 null");
      if (value < 0L) {
        throw new LandViolationException(
            LandFailureReason.LAND_NEGATIVE_QUANTITY,
            "LandArableSupply 的 arableMilliMu 不得为负: " + hex + " -> " + value);
      }
      sorted.put(hex, value);
    }
    arableMilliMuByHex = Collections.unmodifiableNavigableMap(sorted);
  }

  /** 合法空快照（仅用于空土地账本/旧夹具；真生产路径必须由 Map 构造并注入）。 */
  public static LandArableSupply empty() {
    return new LandArableSupply(Map.of());
  }

  /** 该 hex 的快照可耕总量；不在表中返回 empty（不猜 0）。 */
  public OptionalLong arableOfHex(HexCoord hex) {
    Objects.requireNonNull(hex, "LandArableSupply.arableOfHex 的 hex 不得为 null");
    Long value = arableMilliMuByHex.get(hex);
    return value == null ? OptionalLong.empty() : OptionalLong.of(value);
  }

  /** 本表是否覆盖该 hex。 */
  public boolean containsHex(HexCoord hex) {
    Objects.requireNonNull(hex, "LandArableSupply.containsHex 的 hex 不得为 null");
    return arableMilliMuByHex.containsKey(hex);
  }
}
