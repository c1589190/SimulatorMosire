package io.mosire.simos.economy.time;

import io.mosire.simos.social.api.id.HouseholdId;
import java.util.List;
import java.util.Objects;

/**
 * ★★★ <b>D-032 P3c1：单家户在两遍编排中的选择结果</b>。
 *
 * <p>{@code chosen} 是本户按单位劳动净收益贪心选出的位置组合；{@code expectedNetMilli} 是这一遍计划的家户级净收益读数 （由调用方按 {@link
 * PositionEconomics} 汇总，本类型不另造一份公式）；{@code laborBudgetMilli} 是 Social 给出的本户劳动
 * 预算。雇佣锁不在本类型里——锁是跨家户的瞬态对象，归 {@code ExpectedProductionPlan.locks}。
 *
 * <p>★ 预算守卫只约束<b>自家劳动</b>：{@code Σ chosen.selfLaborMilliPerDay ≤ laborBudgetMilli}。雇工劳动由受雇方自己的
 * 预算承担，不能重复算到雇主预算里。
 *
 * <p>本类型只活在一个 tick 内：不落 {@code EconomyData}、不进 ChangeSet/Codec，也不提供任何状态改写方法。
 */
record HouseholdProductionPlan(
    HouseholdId household,
    List<PositionPlan> chosen,
    long expectedNetMilli,
    long laborBudgetMilli) {

  HouseholdProductionPlan {
    Objects.requireNonNull(household, "HouseholdProductionPlan.household 不得为 null");
    Objects.requireNonNull(chosen, "HouseholdProductionPlan.chosen 不得为 null（无生产请用空表）");
    if (expectedNetMilli < 0L) {
      throw new IllegalArgumentException(
          "HouseholdProductionPlan.expectedNetMilli 不得为负（无正收益位置时应为 0）: " + expectedNetMilli);
    }
    if (laborBudgetMilli < 0L) {
      throw new IllegalArgumentException(
          "HouseholdProductionPlan.laborBudgetMilli 不得为负: " + laborBudgetMilli);
    }
    List<PositionPlan> chosenCopy = new java.util.ArrayList<>(chosen.size());
    long selfLabor = 0L;
    for (PositionPlan position : chosen) {
      if (position == null) {
        throw new IllegalArgumentException(
            "HouseholdProductionPlan.chosen 不得含 null: " + household.value());
      }
      chosenCopy.add(position);
      try {
        selfLabor = Math.addExact(selfLabor, position.selfLaborMilliPerDay());
      } catch (ArithmeticException overflow) {
        throw new IllegalArgumentException(
            "HouseholdProductionPlan.chosen 的自家劳动合计越过 long 上界: " + household.value(), overflow);
      }
    }
    if (selfLabor > laborBudgetMilli) {
      throw new IllegalArgumentException(
          "HouseholdProductionPlan 的自家劳动超出预算：household="
              + household.value()
              + "，selfLabor="
              + selfLabor
              + "，budget="
              + laborBudgetMilli);
    }
    chosen = List.copyOf(chosenCopy); // 保序不可变
  }
}
