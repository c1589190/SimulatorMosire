package io.mosire.simos.production.contract;

import io.mosire.simos.economy.api.id.CommodityId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★★ <b>D-032 U4b1：多阶段生产的当前阶段运行状态</b>。
 *
 * <p>它是 {@link ProductionProcess} 的唯一阶段状态载体：同一个生产实例只保存一份“现在在哪一段、本段走了多少天、 本段开始投入已经记了哪些商品”。<b>不另立
 * batch state</b>；legacy 单周期实例的 stageState 为 {@link java.util.Optional#empty()}。
 *
 * <p>★ <b>不变量</b>：
 *
 * <ul>
 *   <li>{@code stageId} 非空白；是否为定义里真实存在的阶段由引擎在推进时按 {@link IndustryDefinitionView#stages()} 校验。
 *   <li>{@code stageProgressDays >= 0}。
 *   <li>{@code stageStartInputsUsedMilli} 非 null、键值非 null、逐值 {@code >= 0}；保序不可变。
 *   <li>该表只记录<b>本阶段开始投入</b>，每 tick 投入只累计进 {@link ProductionProcess#cycleInputUsedMilli()}； v1
 *       不重新扣库存，也不把本状态当作库存权威。
 * </ul>
 *
 * @param stageId 当前阶段稳定 id；非空白
 * @param stageProgressDays 当前阶段已推进天数；不得为负
 * @param stageStartInputsUsedMilli 本阶段开始时已记录的开始投入（毫单位，按商品）；不得为 null、逐值不得为负
 */
public record ProductionStageState(
    String stageId, long stageProgressDays, Map<CommodityId, Long> stageStartInputsUsedMilli) {

  public ProductionStageState {
    if (stageId == null || stageId.isBlank()) {
      throw new IllegalArgumentException("ProductionStageState.stageId 不得为空白");
    }
    if (stageProgressDays < 0L) {
      throw new IllegalArgumentException(
          "ProductionStageState.stageProgressDays 不得为负: " + stageProgressDays);
    }
    if (stageStartInputsUsedMilli == null) {
      throw new IllegalArgumentException(
          "ProductionStageState.stageStartInputsUsedMilli 不得为 null（未记录用空表）");
    }
    // ★ 保序不可变：LinkedHashMap 拷贝 + unmodifiableMap（不用 Map.copyOf，避免迭代序丢失）。
    Map<CommodityId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : stageStartInputsUsedMilli.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionStageState.stageStartInputsUsedMilli 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "ProductionStageState.stageStartInputsUsedMilli 不得为负："
                + entry.getKey()
                + " = "
                + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    stageStartInputsUsedMilli = Collections.unmodifiableMap(copy);
  }
}
