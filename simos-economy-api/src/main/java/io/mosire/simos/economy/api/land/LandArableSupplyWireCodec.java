package io.mosire.simos.economy.api.land;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import io.mosire.simos.map.hex.HexCoord;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * ★★★ <b>{@link LandArableSupply} 的自包含 Jackson wire codec</b>（D-032 RA4a）。
 *
 * <p>★★ <b>为什么需要它</b>：{@code LandArableSupply} 的 API 形状是 {@code Map<HexCoord, Long>}，而 {@code
 * Timeline} 的通用 changeset mapper 按铁律 4 看不见 map/economy 的 key 模块。若把 {@link HexCoord} 直接当 JSON map
 * key，replay 会在读取时失败（“Cannot find a (Map) Key deserializer”）。本 codec 把值类型自己变成 可独立编解码的线格式，既不要求 Core
 * 注册领域 key codec，也不让 util 依赖 map。
 *
 * <p>★ <b>线格式</b>：{@code {"arableMilliMuByHex":{"0_0":1000,"1_0":2000}}}；map key = {@link
 * HexCoord#toString()}，value = 原始 long。读入只接受这一个对象字段，未知字段、非规范 key、非整数/超 long 值都 fail-closed。
 *
 * <p>★ <b>确定性</b>：{@link LandArableSupply} 构造期已把 map 冻成按 {@link HexCoord} 自然序的 {@link
 * TreeMap}，序列化直接按该序写，字节是内容的纯函数。
 */
public final class LandArableSupplyWireCodec {

  /** 线格式里唯一允许的字段名（与 record 组件同名，便于读字节时一眼对上）。 */
  static final String FIELD_ARABLE_MILLI_MU_BY_HEX = "arableMilliMuByHex";

  private LandArableSupplyWireCodec() {}

  /** 把内部 {@code Map<HexCoord, Long>} 写成自包含的 String-keyed 对象。 */
  public static final class Serializer extends JsonSerializer<LandArableSupply> {

    @Override
    public void serialize(
        LandArableSupply value, JsonGenerator generator, SerializerProvider serializers)
        throws IOException {
      generator.writeStartObject();
      generator.writeObjectFieldStart(FIELD_ARABLE_MILLI_MU_BY_HEX);
      for (Map.Entry<HexCoord, Long> entry : value.arableMilliMuByHex().entrySet()) {
        generator.writeNumberField(entry.getKey().toString(), entry.getValue());
      }
      generator.writeEndObject();
      generator.writeEndObject();
    }
  }

  /** 从自包含线格式严格重建不可变 {@link LandArableSupply}。 */
  public static final class Deserializer extends JsonDeserializer<LandArableSupply> {

    @Override
    public LandArableSupply deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      JsonNode root = parser.getCodec().readTree(parser);
      if (root == null || !root.isObject()) {
        throw JsonMappingException.from(parser, "LandArableSupply 线格式必须是 JSON 对象");
      }
      if (root.size() != 1 || !root.has(FIELD_ARABLE_MILLI_MU_BY_HEX)) {
        throw JsonMappingException.from(
            parser, "LandArableSupply 线格式只允许字段 " + FIELD_ARABLE_MILLI_MU_BY_HEX + "，实际字段=" + root);
      }
      JsonNode values = root.get(FIELD_ARABLE_MILLI_MU_BY_HEX);
      if (values == null || !values.isObject()) {
        throw JsonMappingException.from(
            parser, "LandArableSupply." + FIELD_ARABLE_MILLI_MU_BY_HEX + " 必须是 JSON 对象");
      }
      TreeMap<HexCoord, Long> decoded = new TreeMap<>();
      Iterator<Map.Entry<String, JsonNode>> fields = values.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        String hexText = field.getKey();
        JsonNode valueNode = field.getValue();
        if (!valueNode.isIntegralNumber() || !valueNode.canConvertToLong()) {
          throw JsonMappingException.from(
              parser, "LandArableSupply 的数量必须是可转成 long 的整数: " + hexText + " -> " + valueNode);
        }
        final HexCoord hex;
        try {
          hex = HexCoord.parse(hexText);
        } catch (IllegalArgumentException e) {
          throw JsonMappingException.from(parser, "LandArableSupply 的 hex 键非法: " + hexText, e);
        }
        if (!hex.toString().equals(hexText)) {
          throw JsonMappingException.from(parser, "LandArableSupply 的 hex 键不是规范形式: " + hexText);
        }
        long quantity = valueNode.longValue();
        if (decoded.put(hex, quantity) != null) {
          throw JsonMappingException.from(parser, "LandArableSupply 的 hex 键重复（规范形式冲突）: " + hexText);
        }
      }
      try {
        return new LandArableSupply(decoded);
      } catch (IllegalArgumentException e) {
        throw JsonMappingException.from(parser, "LandArableSupply 线格式值非法", e);
      }
    }
  }
}
