package io.mosire.simos.economy.classfirst;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★★ <b>{@link ClassPool} 的线格式（R1 的字段集与字段序的唯一拼写点）</b>。
 *
 * <p>★★ <b>为什么从 {@code EconomyCodec} 搬到这里并标在类型上</b>：{@code ClassPool} 不是 record，裸 Jackson （Timeline
 * 的 changeset mapper）既不认识它的 no-arg 访问器、也不会去找 economy 私有 mapper 上注册的序列化器 ⇒ 任何含非空 {@code classFirst}
 * 的变更集在 {@code core.submitBatch} 落盘时抛 {@code No serializer found for ClassPool}。 把这份 (de)serializer
 * 作为类型级注解挂在 {@link ClassPool} 上之后，<b>任何一台 mapper</b>（EconomyCodec 的私有 mapper、 Timeline 的 changeset
 * mapper、诊断用裸 mapper）都走同一份字段集 —— 不新增第二套拼写，也不再依赖"谁记得注册模块"。
 *
 * <p>★ 读侧仍收敛到 {@link ClassPool#restored}（唯一重建入口）；{@code stock} 只接受 {@link AssetKind#stock()} 维度，
 * {@code OPERATED_LAND}/{@code LEASE_SECURITY}/{@code DEBT} 仍由各自的专用字段重建（与 R1 codec 逐字同形）。
 */
public final class ClassPoolJson {

  private ClassPoolJson() {}

  /** ★ 写侧：字段序固定（{@code modeId → … → debtByUnit}），与 R1 的 {@code ClassPoolSerializer} 逐字段相同。 */
  public static final class Serializer extends JsonSerializer<ClassPool> {

    @Override
    public void serialize(ClassPool pool, JsonGenerator generator, SerializerProvider serializers)
        throws IOException {
      generator.writeStartObject();
      generator.writeStringField("modeId", pool.modeId());
      generator.writeStringField("classPositionId", pool.classPositionId());
      generator.writeNumberField("population", pool.population());
      generator.writeNumberField("labor", pool.labor());
      generator.writeObjectFieldStart("stock");
      for (AssetKind kind : AssetKind.ordered()) {
        if (kind.stock()) {
          generator.writeNumberField(kind.name(), pool.stock(kind));
        }
      }
      generator.writeEndObject();
      generator.writeNumberField("operatedLand", pool.stock(AssetKind.OPERATED_LAND));
      generator.writeNumberField("leaseHolding", pool.leaseHolding());
      generator.writeNumberField("laborEfficiencyPerMille", pool.laborEfficiencyPerMille());
      generator.writeNumberField("flowUpRemainderMilli", pool.flowUpRemainderMilli());
      generator.writeNumberField("flowDownRemainderMilli", pool.flowDownRemainderMilli());
      generator.writeNumberField("collectionCooldownUntilTick", pool.collectionCooldownUntilTick());
      generator.writeNumberField("debtGrainMilli", pool.debtGrainMilli());
      generator.writeObjectFieldStart("debtByUnit");
      for (Map.Entry<String, Long> entry : pool.debtByUnit().entrySet()) {
        generator.writeNumberField(entry.getKey(), entry.getValue());
      }
      generator.writeEndObject();
      generator.writeEndObject();
    }
  }

  /** ★ 读侧：唯一入口 {@link ClassPool#restored}；旧形状/未知 {@code stock} 维度 fail-closed。 */
  public static final class Deserializer extends JsonDeserializer<ClassPool> {

    @Override
    public ClassPool deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      JsonNode raw = parser.getCodec().readTree(parser);
      if (!(raw instanceof ObjectNode node)) {
        throw new IllegalStateException("ClassPool 必须是 JSON 对象: " + raw);
      }
      String modeId = requiredText(node, "modeId");
      String classPositionId = requiredText(node, "classPositionId");
      LinkedHashMap<AssetKind, Long> stocks = new LinkedHashMap<>();
      JsonNode stockNode = node.get("stock");
      if (stockNode != null && !stockNode.isNull()) {
        if (!(stockNode instanceof ObjectNode stockObject)) {
          throw new IllegalStateException("ClassPool.stock 必须是对象: " + stockNode);
        }
        List<String> names = new ArrayList<>();
        stockObject.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
          AssetKind kind;
          try {
            kind = AssetKind.valueOf(name);
          } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ClassPool.stock 的库存维度不认识: " + name, e);
          }
          if (!kind.stock()) {
            throw new IllegalStateException("ClassPool.stock 只允许真实库存（stock=true）维度，收到: " + name);
          }
          stocks.put(kind, stockObject.get(name).asLong());
        }
      }
      LinkedHashMap<String, Long> debtByUnit = new LinkedHashMap<>();
      JsonNode debtNode = node.get("debtByUnit");
      if (debtNode != null && !debtNode.isNull()) {
        if (!(debtNode instanceof ObjectNode debtObject)) {
          throw new IllegalStateException("ClassPool.debtByUnit 必须是对象: " + debtNode);
        }
        List<String> units = new ArrayList<>();
        debtObject.fieldNames().forEachRemaining(units::add);
        for (String unit : units) {
          debtByUnit.put(unit, debtObject.get(unit).asLong());
        }
      }
      return ClassPool.restored(
          modeId,
          classPositionId,
          node.path("population").asLong(),
          node.path("labor").asLong(),
          stocks,
          node.path("operatedLand").asLong(),
          node.path("leaseHolding").asLong(),
          node.path("laborEfficiencyPerMille").asLong(),
          node.path("flowUpRemainderMilli").asLong(),
          node.path("flowDownRemainderMilli").asLong(),
          node.path("collectionCooldownUntilTick").asLong(),
          debtByUnit,
          node.path("debtGrainMilli").asLong());
    }

    private static String requiredText(ObjectNode node, String field) {
      JsonNode value = node.get(field);
      if (value == null || !value.isTextual() || value.asText().isBlank()) {
        throw new IllegalStateException("ClassPool 的字段 " + field + " 必须是非空文本: " + node);
      }
      return value.asText();
    }
  }
}
