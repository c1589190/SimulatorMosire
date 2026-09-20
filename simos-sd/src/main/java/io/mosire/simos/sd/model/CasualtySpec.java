package io.mosire.simos.sd.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结局对应的战损**规模**（spec §三.3 的 {@code OutcomeOption} 携带）。
 *
 * <p>★ 执行期取代说明：**spec 未定义本类型的字段形状**（只在 §三.3 的 {@code OutcomeOption} 里出现名字）。实现期定为"非负幅度 + 冻结
 * map"——{@code personnel}/{@code equipment} 都是**预期损失的规模**（正值），真正的**增量**（负值）由 {@code CasualtyDelta}
 * 承载（N3）。记台账。
 *
 * <p>★ {@code equipment} 保序不可变（{@code LinkedHashMap} + 冻在赋值处；**禁用** {@code Map.copyOf}）。
 */
public record CasualtySpec(int personnel, Map<String, Integer> equipment) {

  public CasualtySpec {
    if (personnel < 0) {
      throw new IllegalArgumentException("personnel 必须 ≥ 0: " + personnel);
    }
    if (equipment == null) {
      throw new IllegalArgumentException("equipment 不得为 null");
    }
    Map<String, Integer> copy = new LinkedHashMap<>();
    for (Map.Entry<String, Integer> entry : equipment.entrySet()) {
      if (entry.getKey() == null || entry.getKey().isBlank()) {
        throw new IllegalArgumentException("equipment 的键不得空白");
      }
      if (entry.getValue() == null || entry.getValue() < 0) {
        throw new IllegalArgumentException("equipment 的值必须 ≥ 0: " + entry.getKey());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    equipment = Collections.unmodifiableMap(copy); // ★ 冻在赋值处
  }
}
