package io.mosire.simos.economy.codec;

import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.KeyDeserializer;
import io.mosire.simos.map.hex.HexCoord;

/**
 * ★★★ <b>D-032 C10 blocker-D：{@link HexCoord} 作为 Map 键的读侧绑定</b>。
 *
 * <p>线格式：写侧仍走 Jackson 对非字符串键的默认规则 = {@link HexCoord#toString()}（规范串 {@code q_r}）； 读侧本类调用 {@link
 * HexCoord#parse(String)}。两者互逆。
 *
 * <p>★ <b>为什么需要它</b>：{@code MarketRoundStats.lastTransportByHex} 是 {@code Map<HexCoord,
 * TransportHexObservation>}，而 {@code Timeline.readChangeSet} 走的是 Core 的第四台 mapper ——那台 mapper 按铁律 4
 * 不认识任何领域类型，也没有 economy codec 的键模块。因此键反序列化器必须由 <b>economy
 * 自己的类型注解</b>带进值绑定：{@code @JsonDeserialize(keyUsing = HexCoordKeyDeserializer.class)} 落在 {@code
 * MarketRoundStats} 的 record component 上，Core 无需在编译期依赖 map，也无需改 {@code @class} 类型信息机制或 Map 形状。
 *
 * <p>★ <b>与 {@link EconomyCoreCodecs#keyModule()} 的关系</b>：economy 自己的 mapper 仍注册同一个 {@code
 * HexCoord::parse} 键反序列化器（供 {@code marketTables} 等其它键面使用）；本类是该绑定在
 * <b>类型注解路径</b>上的落点，两处语义逐字相同，不是第二套解析规则。
 */
public final class HexCoordKeyDeserializer extends KeyDeserializer {

  @Override
  public Object deserializeKey(String key, DeserializationContext context) {
    return HexCoord.parse(key);
  }
}
