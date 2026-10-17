package io.mosire.simos.economy.time;

import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ★★★ <b>D-032 D3 §2.5.1/§7.1：Social 对下一 tick 生存条件的权威只读投影</b>（W4 接线）。
 *
 * <p>本类型只承载 Social 已经算好的事实：下一 tick 的逐需求行与当前/下一 tick 可用劳动预算。 economy 不得自行重写饥饿、 口粮或营养公式；缺家户/缺
 * tick/负预算一律具名 fail-closed。
 *
 * <p>★★ <b>不可用哨兵与"空表"不是一回事</b>：{@link #unavailable()} 表示组合根没有 Social 投影（例如仅有 economy+actor 的
 * 已退役备用路径）；{@link #of(long, Map, Map)} 的 <b>空表</b>表示 Social 明确给出"无家户/无需求"。真实生产 tick 遇到不可用哨兵且存在 劳动候选
 * ⇒ 必须报 {@code survival-need-missing}，不得用空表静默跳过。
 */
public record SurvivalSocialFacts(
    long nextTick,
    Map<HouseholdId, NextTickSubsistenceNeed> nextTickNeeds,
    Map<HouseholdId, Long> nextTickLaborBudgetMilli,
    boolean available) {

  public SurvivalSocialFacts {
    if (available && nextTick < 0L) {
      throw new SurvivalOutlookException(
          "survival-income-timing-unknown", "可用的 Social 下一 tick 投影不得为负: " + nextTick);
    }
    Objects.requireNonNull(nextTickNeeds, "nextTickNeeds 不得为 null（无需求给空表）");
    Objects.requireNonNull(nextTickLaborBudgetMilli, "nextTickLaborBudgetMilli 不得为 null（无劳动给空表）");
    LinkedHashMap<HouseholdId, NextTickSubsistenceNeed> needs = new LinkedHashMap<>();
    nextTickNeeds.forEach(
        (household, need) -> {
          Objects.requireNonNull(household, "nextTickNeeds 不得含 null 家户");
          Objects.requireNonNull(need, "nextTickNeeds 不得含 null 需求");
          if (available && need.forTick() != nextTick) {
            throw new SurvivalOutlookException(
                "survival-need-stale",
                "Social 下一 tick 需求 forTick="
                    + need.forTick()
                    + " 与投影 nextTick="
                    + nextTick
                    + " 不一致: "
                    + household.value());
          }
          needs.put(household, need);
        });
    LinkedHashMap<HouseholdId, Long> labor = new LinkedHashMap<>();
    nextTickLaborBudgetMilli.forEach(
        (household, budget) -> {
          Objects.requireNonNull(household, "nextTickLaborBudgetMilli 不得含 null 家户");
          Objects.requireNonNull(budget, "nextTickLaborBudgetMilli 不得含 null 预算");
          if (budget < 0L) {
            throw new SurvivalOutlookException(
                "survival-input-invalid", "下一 tick 劳动预算不得为负: " + household.value());
          }
          labor.put(household, budget);
        });
    nextTickNeeds = Collections.unmodifiableMap(needs);
    nextTickLaborBudgetMilli = Collections.unmodifiableMap(labor);
  }

  /** 组合根没有 Social 投影：真实生产 tick 必须具名 fail-closed（不是"无需求"）。 */
  public static SurvivalSocialFacts unavailable() {
    return new SurvivalSocialFacts(-1L, Map.of(), Map.of(), false);
  }

  /** 组合根给出的完整 Social 下一 tick 投影。 */
  public static SurvivalSocialFacts of(
      long nextTick,
      Map<HouseholdId, NextTickSubsistenceNeed> nextTickNeeds,
      Map<HouseholdId, Long> nextTickLaborBudgetMilli) {
    if (nextTick < 0L) {
      throw new SurvivalOutlookException(
          "survival-income-timing-unknown", "下一 tick 不得为负: " + nextTick);
    }
    return new SurvivalSocialFacts(nextTick, nextTickNeeds, nextTickLaborBudgetMilli, true);
  }

  /** 某家户下一 tick 的 Social 需求；缺投影/缺该户 ⇒ {@code survival-need-missing}。 */
  public NextTickSubsistenceNeed requireNeed(HouseholdId household) {
    Objects.requireNonNull(household, "household");
    if (!available) {
      throw new SurvivalOutlookException("survival-need-missing", "Social 下一 tick 需求投影不可用（组合根未接线）");
    }
    NextTickSubsistenceNeed need = nextTickNeeds.get(household);
    if (need == null) {
      throw new SurvivalOutlookException(
          "survival-need-missing", "Social 未给出该家户的下一 tick 需求: " + household.value());
    }
    return need;
  }

  /** 某家户下一 tick 劳动预算；缺投影/缺该户 ⇒ {@code survival-income-projection-missing}。 */
  public long requireLaborBudgetMilli(HouseholdId household) {
    Objects.requireNonNull(household, "household");
    if (!available) {
      throw new SurvivalOutlookException(
          "survival-income-projection-missing", "Social 下一 tick 劳动预算投影不可用（组合根未接线）");
    }
    Long budget = nextTickLaborBudgetMilli.get(household);
    if (budget == null) {
      throw new SurvivalOutlookException(
          "survival-income-projection-missing", "Social 未给出该家户的下一 tick 劳动预算: " + household.value());
    }
    return budget;
  }
}
