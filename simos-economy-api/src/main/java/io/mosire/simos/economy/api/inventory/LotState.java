package io.mosire.simos.economy.api.inventory;

import io.mosire.simos.economy.api.id.CommodityId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 库存 lot 的领域状态；普通商品恒为 {@link Complete}，土地/生产资料可为 {@link Idle} 或 {@link InProduction}。 */
public sealed interface LotState permits LotState.Complete, LotState.Idle, LotState.InProduction {

  Complete COMPLETE = new Complete();
  Idle IDLE = new Idle();

  /** 最终商品状态。 */
  static Complete complete() {
    return COMPLETE;
  }

  /** 土地/长期生产资料当前空闲。 */
  static Idle idle() {
    return IDLE;
  }

  /** 正在生产的状态。 */
  static InProduction inProduction(
      String planId,
      String stageId,
      long stageProgressDays,
      long cycleProgressDays,
      Map<String, Long> facts) {
    return new InProduction(planId, stageId, stageProgressDays, cycleProgressDays, facts);
  }

  /** 最终商品状态。 */
  record Complete() implements LotState {}

  /** 土地/长期生产资料空闲状态。 */
  record Idle() implements LotState {}

  /**
   * 该 lot 自己的生产进度。
   *
   * @param planId 生产方案 id
   * @param stageId 当前阶段 id
   * @param stageProgressDays 当前阶段已推进天数
   * @param cycleProgressDays 当前周期已推进天数
   * @param facts 生产域解释的有限附加事实；保持输入迭代序且不可变
   * @param startInputPaid 阶段开始投入是否已支付
   * @param cycleInputUsedMilli 本周期累计真实投入（按商品）；保持输入迭代序且不可变
   */
  record InProduction(
      String planId,
      String stageId,
      long stageProgressDays,
      long cycleProgressDays,
      Map<String, Long> facts,
      boolean startInputPaid,
      Map<CommodityId, Long> cycleInputUsedMilli)
      implements LotState {

    public InProduction {
      if (planId == null || planId.isBlank()) {
        throw new IllegalArgumentException("InProduction.planId 不得为空白");
      }
      if (stageId == null || stageId.isBlank()) {
        throw new IllegalArgumentException("InProduction.stageId 不得为空白");
      }
      if (stageProgressDays < 0L) {
        throw new IllegalArgumentException(
            "InProduction.stageProgressDays 不得为负: " + stageProgressDays);
      }
      if (cycleProgressDays < 0L) {
        throw new IllegalArgumentException(
            "InProduction.cycleProgressDays 不得为负: " + cycleProgressDays);
      }
      if (facts == null) {
        throw new IllegalArgumentException("InProduction.facts 不得为 null（无事实用空 map）");
      }
      Map<String, Long> factsCopy = new LinkedHashMap<>();
      for (Map.Entry<String, Long> entry : facts.entrySet()) {
        if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
          throw new IllegalArgumentException(
              "InProduction.facts 的键必须非空白、值不得为 null: " + entry.getKey());
        }
        factsCopy.put(entry.getKey(), entry.getValue());
      }
      facts = Collections.unmodifiableMap(factsCopy); // ★ 保序不可变；不用 Map.copyOf
      if (cycleInputUsedMilli == null) {
        throw new IllegalArgumentException("InProduction.cycleInputUsedMilli 不得为 null（无投入用空 map）");
      }
      Map<CommodityId, Long> cycleInputCopy = new LinkedHashMap<>();
      for (Map.Entry<CommodityId, Long> entry : cycleInputUsedMilli.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException(
              "InProduction.cycleInputUsedMilli 的键与值都不得为 null: " + entry.getKey());
        }
        if (entry.getValue() < 0L) {
          throw new IllegalArgumentException(
              "InProduction.cycleInputUsedMilli 的值不得为负: "
                  + entry.getKey()
                  + "="
                  + entry.getValue());
        }
        cycleInputCopy.put(entry.getKey(), entry.getValue());
      }
      cycleInputUsedMilli = Collections.unmodifiableMap(cycleInputCopy); // ★ 保序不可变；不用 Map.copyOf
    }

    /** 兼容旧调用：未给 startInputPaid / cycleInputUsedMilli 时按 false + 空 map。 */
    public InProduction(
        String planId,
        String stageId,
        long stageProgressDays,
        long cycleProgressDays,
        Map<String, Long> facts) {
      this(planId, stageId, stageProgressDays, cycleProgressDays, facts, false, Map.of());
    }
  }
}
