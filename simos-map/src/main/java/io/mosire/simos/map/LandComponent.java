package io.mosire.simos.map;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单 hex 某土地类型下的一个面积成分：稳定 id + 毫亩数量 + 抽象属性表。
 *
 * <p>不可变；属性表按 {@link LandAttributeKey#wireValue()} 规范升序冻结。数量必须为正：0 行由写口先删除，构造层直接拒绝。
 *
 * @param id 稳定身份
 * @param quantityMilliMu 面积（毫亩，{@code > 0}）
 * @param attributes 抽象属性（保序不可变）
 */
public record LandComponent(
    LandComponentId id,
    long quantityMilliMu,
    Map<LandAttributeKey, LandAttributeValue> attributes) {

  public LandComponent {
    if (id == null) {
      throw new IllegalArgumentException("LandComponent.id 不得为 null");
    }
    if (quantityMilliMu <= 0L) {
      throw new IllegalArgumentException(
          "LandComponent.quantityMilliMu 必须大于 0: " + quantityMilliMu);
    }
    if (attributes == null) {
      throw new IllegalArgumentException("LandComponent.attributes 不得为 null");
    }
    for (Map.Entry<LandAttributeKey, LandAttributeValue> entry : attributes.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("LandComponent.attributes 的键与值都不得为 null");
      }
    }
    var ordered = new java.util.ArrayList<>(attributes.entrySet());
    ordered.sort(Comparator.comparing(entry -> entry.getKey().wireValue()));
    var frozen = new LinkedHashMap<LandAttributeKey, LandAttributeValue>();
    for (Map.Entry<LandAttributeKey, LandAttributeValue> entry : ordered) {
      frozen.put(entry.getKey(), entry.getValue());
    }
    attributes = Collections.unmodifiableMap(frozen);
  }

  /** 返回只换 quantity 的新 component（id/属性不变）。 */
  public LandComponent withQuantity(long newQuantityMilliMu) {
    return new LandComponent(id, newQuantityMilliMu, attributes);
  }

  /** 返回只换一个属性的新 component（id/quantity 不变）。 */
  public LandComponent withAttribute(LandAttributeKey key, LandAttributeValue value) {
    if (key == null || value == null) {
      throw new IllegalArgumentException("LandComponent.withAttribute 的 key/value 不得为 null");
    }
    Map<LandAttributeKey, LandAttributeValue> next = new LinkedHashMap<>(attributes);
    next.put(key, value);
    return new LandComponent(id, quantityMilliMu, next);
  }
}
