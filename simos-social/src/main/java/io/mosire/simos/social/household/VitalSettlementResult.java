package io.mosire.simos.social.household;

import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.population.HouseholdPopulationEvent;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★★ <b>每 tick 生死结算结果</b>（2026-10-09 Social 每 tick 计划 §3.1）：一次 {@link
 * HouseholdBook#settleVitalEvents(SocialData, long, io.mosire.simos.calendar.CalendarClock)} 返回的
 * 新状态 + 逐家户人口变化 + 汇总读数。
 *
 * <p>★ {@code populationDeltas} 是<b>净变化</b>（出生为正、死亡为负），键只含变化非 0 的家户；调用方（App 的经济 同步）直接把它加到经济行的
 * {@code population} 上。
 *
 * <p>★ {@code events} 是本次落账的事件（与 {@code data.populationEvents()} 里新增的那些同对象）；逐事件日志由 {@link
 * HouseholdBook#applyEvents} 产出，本结果不再重复记。
 *
 * @param data 结算后的新社会状态（余数表已更新、无主余数已清理）；不得为 null
 * @param populationDeltas 逐家户净人口变化（出生 − 死亡）；不得为 null、值非 0
 * @param births 本次出生总数；不得为负
 * @param deaths 本次死亡总数；不得为负
 * @param day 结算日（世界 tick）；不得为负
 * @param events 本次落账的事件（保序、冻结）；不得为 null
 */
public record VitalSettlementResult(
    SocialData data,
    Map<HouseholdId, Long> populationDeltas,
    long births,
    long deaths,
    long day,
    List<HouseholdPopulationEvent> events) {

  public VitalSettlementResult {
    if (data == null) {
      throw new IllegalArgumentException("VitalSettlementResult.data 不得为 null");
    }
    if (populationDeltas == null) {
      throw new IllegalArgumentException("VitalSettlementResult.populationDeltas 不得为 null");
    }
    if (events == null) {
      throw new IllegalArgumentException("VitalSettlementResult.events 不得为 null");
    }
    if (births < 0L) {
      throw new IllegalArgumentException("VitalSettlementResult.births 不得为负: " + births);
    }
    if (deaths < 0L) {
      throw new IllegalArgumentException("VitalSettlementResult.deaths 不得为负: " + deaths);
    }
    if (day < 0L) {
      throw new IllegalArgumentException("VitalSettlementResult.day 不得为负: " + day);
    }
    Map<HouseholdId, Long> deltasCopy = new LinkedHashMap<>();
    long deltaSum = 0L;
    for (Map.Entry<HouseholdId, Long> entry : populationDeltas.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("VitalSettlementResult.populationDeltas 的键与值都不得为 null");
      }
      if (entry.getValue() == 0L) {
        throw new IllegalArgumentException(
            "VitalSettlementResult.populationDeltas 不应含 0 变化: " + entry.getKey());
      }
      deltasCopy.put(entry.getKey(), entry.getValue());
      deltaSum = Math.addExact(deltaSum, entry.getValue());
    }
    if (deltaSum != Math.subtractExact(births, deaths)) {
      throw new IllegalArgumentException(
          "VitalSettlementResult 人口变化之和与出生−死亡不一致: Σdelta="
              + deltaSum
              + " births="
              + births
              + " deaths="
              + deaths);
    }
    populationDeltas = Collections.unmodifiableMap(deltasCopy); // ★ 冻在赋值处
    events = List.copyOf(events);
  }
}
