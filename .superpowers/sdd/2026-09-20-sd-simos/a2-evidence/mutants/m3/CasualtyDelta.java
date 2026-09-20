package io.mosire.simos.sd.model;

import io.mosire.simos.unit.UnitId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 战损增量（spec §三.4，N3）：**双轨**——人员（{@code personnel}）与装备（{@code equipment}）——记的是 **delta（事件）**，
 * 不是覆写绝对强度。
 *
 * <p>★ **代码侧强制的不变量**（N3 / §八.4，绝不交给 AI）：{@code personnel <= 0}、每个 {@code equipment} 值为**负**。 "|Δ| ≤
 * 当前值"的上界依赖 unit 切片，由**命令期**读当前值校验（C3），不在本 record。
 *
 * <p>★ {@code equipment} 保序不可变（{@code LinkedHashMap} + 冻在赋值处；**禁用** {@code Map.copyOf}）。
 */
public record CasualtyDelta(
    UnitId unit, int personnel, Map<String, Integer> equipment, LossClass lossClass) {

  public CasualtyDelta {
    if (unit == null) {
      throw new IllegalArgumentException("unit 不得为 null");
    }
    if (personnel > 0) {
      throw new IllegalArgumentException("personnel 必须 ≤ 0: " + personnel);
    }
    if (lossClass == null) {
      throw new IllegalArgumentException("lossClass 不得为 null");
    }
    if (equipment == null) {
      throw new IllegalArgumentException("equipment 不得为 null");
    }
    Map<String, Integer> copy = new LinkedHashMap<>();
    for (Map.Entry<String, Integer> entry : equipment.entrySet()) {
      if (entry.getKey() == null || entry.getKey().isBlank()) {
        throw new IllegalArgumentException("equipment 的键不得空白");
      }
      // MUTANT: equipment 负值校验已删
      copy.put(entry.getKey(), entry.getValue());
    }
    equipment = Collections.unmodifiableMap(copy); // ★ 冻在赋值处
  }
}
