package io.mosire.simos.social.household;

import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.population.HouseholdPopulationEvent;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ★★ <b>每 tick 生死结算结果</b>（2026-10-09 Social 每 tick 计划 §3.1）：一次 {@link
 * HouseholdBook#settleVitalEvents(SocialData, long, io.mosire.simos.calendar.CalendarClock)} 返回的
 * 新状态 + 逐家户人口变化 + 汇总读数。
 *
 * <p>★ {@code populationDeltas} 是<b>净变化</b>（出生为正、死亡为负），键只含变化非 0 的家户；调用方（App 的经济 同步）直接把它加到经济行的
 * {@code population} 上。
 *
 * <p>★★ <b>W3-fix（2026-10-11）：{@code birthsByHousehold} / {@code deathsByHousehold}
 * 是同一份结算的<b>原值</b></b>—— 净变化读不出"死了多少人、生了多少人"（一户同时生死时它只报差），而经济侧的流水（{@code FlowRow.births} / {@code
 * FlowRow.deaths}）与危机读数要的正是这两个原值。★ 它们是<b>读数</b>：不改任何状态，只把这一 tick 已经算出来的逐户生死
 * 报给接收方（经济侧只负责把它们落进流水，**不判定生死**）。★ 三张表逐户自洽：缺键按 0 计，{@code births − deaths ==
 * populationDeltas}（构造期逐户核）。
 *
 * <p>★ {@code events} 是本次落账的事件（与 {@code data.populationEvents()} 里新增的那些同对象）；逐事件日志由 {@link
 * HouseholdBook#applyEvents} 产出，本结果不再重复记。
 *
 * @param data 结算后的新社会状态（余数表已更新、无主余数已清理）；不得为 null
 * @param populationDeltas 逐家户净人口变化（出生 − 死亡）；不得为 null、值非 0
 * @param birthsByHousehold 逐家户的出生<b>原值</b>（只含 &gt; 0 的家户；缺键 = 0）；不得为 null。★ 与 {@code
 *     deathsByHousehold} 配套，供接收方写经济流水
 * @param deathsByHousehold 逐家户的死亡<b>原值</b>（只含 &gt; 0 的家户；缺键 = 0）；不得为 null。★ <b>含饥荒那一份</b> （{@code
 *     famineDeaths} 是它的子集）——经济侧的 {@code FlowRow.deaths} 读到的就是这个数
 * @param births 本次出生总数；不得为负（= {@code birthsByHousehold} 之和）
 * @param deaths 本次死亡总数；不得为负（★ <b>含饥荒那一份</b>：它与基准死亡共用 DEATH 余数，是同一个总数；= {@code deathsByHousehold}
 *     之和）
 * @param famineDeaths ★★ <b>W3（2026-10-11）/ W3-fix2（D2）/ W3-fix3（按人数）：本次死亡里 cause 含饥荒的饿死人数</b>（人；≥
 *     0；≤ {@code deaths}）。★ <b>口径（设计书 v3.12 §7 的 D2 裁定 + W3-fix3 收口）</b>：按每条 {@code DEATH} 事件的
 *     {@code cause}（{@code BASE_PLUS_FAMINE}）筛选出饥荒那些事件，**把它们的 {@code count}（该批次人数）逐条累加** ——
 *     不是数事件条数；<b>不再</b>按 {@code 饥荒 ppm ÷ 有效 ppm} 从死亡人数里分摊取整（旧式在"单户单日死 1 人"时恒为 0（{@code
 *     floorDiv(1×faminePpm, effectivePpm)} = 0），使读数系统性为 0 而门禁抓不到）。★
 *     它是<b>读数</b>：只进日志/报告，<b>不改</b>任何状态；缺口 = 0 时恒 0。★ 饥荒事件是全部死亡事件的子集、每条 {@code count} ≥ 1 ⇒ 人数 ≤ 该
 *     tick 的死亡人数，不变量由结构保证（逐户在 {@code HouseholdBook} 结算循环里、总量在本记录构造期各核一次）。★ App 用它写"谁因什么死了多少"的 INFO。
 * @param day 结算日（世界 tick）；不得为负
 * @param events 本次落账的事件（保序、冻结）；不得为 null
 */
public record VitalSettlementResult(
    SocialData data,
    Map<HouseholdId, Long> populationDeltas,
    Map<HouseholdId, Long> birthsByHousehold,
    Map<HouseholdId, Long> deathsByHousehold,
    long births,
    long deaths,
    long famineDeaths,
    long day,
    List<HouseholdPopulationEvent> events) {

  public VitalSettlementResult {
    if (data == null) {
      throw new IllegalArgumentException("VitalSettlementResult.data 不得为 null");
    }
    if (populationDeltas == null) {
      throw new IllegalArgumentException("VitalSettlementResult.populationDeltas 不得为 null");
    }
    if (birthsByHousehold == null) {
      throw new IllegalArgumentException("VitalSettlementResult.birthsByHousehold 不得为 null");
    }
    if (deathsByHousehold == null) {
      throw new IllegalArgumentException("VitalSettlementResult.deathsByHousehold 不得为 null");
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
    if (famineDeaths < 0L) {
      throw new IllegalArgumentException(
          "VitalSettlementResult.famineDeaths 不得为负: " + famineDeaths);
    }
    if (famineDeaths > deaths) {
      // ★★ W3-fix2（D2）/ W3-fix3（按人数）：总量侧的"饿死人数 ≤ 死亡人数"不变量 —— 逐户那一道在 HouseholdBook 的结算循环里
      //   （见那里的 householdFamineDeaths 守卫）；两处口径相同：越界即 fail-closed，不静默夹取。
      throw new IllegalArgumentException(
          "VitalSettlementResult.famineDeaths 不得超过 deaths（饥荒致死人数 ≤ 死亡人数）: famineDeaths="
              + famineDeaths
              + " deaths="
              + deaths);
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
    // ★★ W3-fix：逐户出生/死亡的两张原值表 —— 冻结、并逐户与净变化核对（缺键按 0 计）。
    //   "报出去的生死"与"落到经济行的净 delta"必须说的是同一件事；不核对就可能各说各话。
    Map<HouseholdId, Long> birthsCopy = copyCounts(birthsByHousehold, "birthsByHousehold");
    Map<HouseholdId, Long> deathsCopy = copyCounts(deathsByHousehold, "deathsByHousehold");
    Set<HouseholdId> lifecycleHouseholds = new LinkedHashSet<>(birthsCopy.keySet());
    lifecycleHouseholds.addAll(deathsCopy.keySet());
    for (HouseholdId household : lifecycleHouseholds) {
      long householdBirths = birthsCopy.getOrDefault(household, 0L);
      long householdDeaths = deathsCopy.getOrDefault(household, 0L);
      long delta = deltasCopy.getOrDefault(household, 0L);
      if (Math.subtractExact(householdBirths, householdDeaths) != delta) {
        throw new IllegalArgumentException(
            "VitalSettlementResult 逐户自洽被破坏（出生−死亡 ≠ 净变化）: household="
                + household
                + " births="
                + householdBirths
                + " deaths="
                + householdDeaths
                + " delta="
                + delta);
      }
    }
    if (sumCounts(birthsCopy) != births) {
      throw new IllegalArgumentException(
          "VitalSettlementResult 逐户出生之和 ≠ births: Σ="
              + sumCounts(birthsCopy)
              + " births="
              + births);
    }
    if (sumCounts(deathsCopy) != deaths) {
      throw new IllegalArgumentException(
          "VitalSettlementResult 逐户死亡之和 ≠ deaths: Σ="
              + sumCounts(deathsCopy)
              + " deaths="
              + deaths);
    }
    populationDeltas = Collections.unmodifiableMap(deltasCopy); // ★ 冻在赋值处
    birthsByHousehold = Collections.unmodifiableMap(birthsCopy); // ★ 冻在赋值处
    deathsByHousehold = Collections.unmodifiableMap(deathsCopy); // ★ 冻在赋值处
    events = List.copyOf(events);
  }

  /** 逐户计数表的规范形态：键非 null、值非 null 且 &gt; 0（只报"真的发生了"的家户）；保序冻结。 */
  private static Map<HouseholdId, Long> copyCounts(Map<HouseholdId, Long> counts, String field) {
    Map<HouseholdId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, Long> entry : counts.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("VitalSettlementResult." + field + " 的键与值都不得为 null");
      }
      if (entry.getValue() <= 0L) {
        throw new IllegalArgumentException(
            "VitalSettlementResult."
                + field
                + " 只含正值（缺键即 0）: "
                + entry.getKey()
                + "="
                + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return copy;
  }

  /** 逐户计数之和（构造期不变量用）。 */
  private static long sumCounts(Map<HouseholdId, Long> counts) {
    long sum = 0L;
    for (long value : counts.values()) {
      sum = Math.addExact(sum, value);
    }
    return sum;
  }
}
