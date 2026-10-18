package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★★ <b>D-032 P3c1：M6 第二遍（实际）生产编排的瞬态结果</b>。
 *
 * <p>它在统一市场与每日消费之后，用实际库存/土地/劳动重新确认生产；M3 的雇佣关系由 {@link #locksReadOnly()} 原样带入，
 * <b>不得</b>换人/改份额/改工资。本类型没有 {@code marketIntents}：M6 不产生新的市场买卖。
 *
 * <p>★ 确定性：{@code byHousehold}、{@code byUnit} 的 key 一律按稳定 id 升序；{@code locksReadOnly} 按 (unit,
 * employer, worker, role, currency, labor, wage) 升序。位置列表内部保留输入规范序（同输入重复构造结果相同）。
 *
 * <p>★ 本类型只活在 M6/M7 之间，不落 {@code EconomyData}/ChangeSet/Codec，不提供 {@code with}/{@code apply}。
 */
record ActualProductionPlan(
    long day,
    Map<HouseholdId, HouseholdProductionPlan> byHousehold,
    Map<ProductionUnitId, PositionPlan.UnitPlan> byUnit,
    List<EmploymentLock> locksReadOnly) {

  ActualProductionPlan {
    if (day < 0L) {
      throw new IllegalArgumentException("ActualProductionPlan.day 不得为负: " + day);
    }
    byHousehold = copyHouseholdPlans(byHousehold);
    byUnit = copyUnitPlans(byUnit);
    locksReadOnly = copyLocks(locksReadOnly);
  }

  private static Map<HouseholdId, HouseholdProductionPlan> copyHouseholdPlans(
      Map<HouseholdId, HouseholdProductionPlan> source) {
    Objects.requireNonNull(source, "ActualProductionPlan.byHousehold 不得为 null");
    List<HouseholdId> keys = new ArrayList<>(source.keySet());
    for (HouseholdId key : keys) {
      Objects.requireNonNull(key, "ActualProductionPlan.byHousehold 的 key 不得为 null");
    }
    keys.sort(Comparator.comparing(HouseholdId::value));
    LinkedHashMap<HouseholdId, HouseholdProductionPlan> copy = new LinkedHashMap<>();
    for (HouseholdId key : keys) {
      HouseholdProductionPlan value = source.get(key);
      if (value == null) {
        throw new IllegalArgumentException(
            "ActualProductionPlan.byHousehold 的 value 不得为 null: " + key.value());
      }
      if (!key.equals(value.household())) {
        throw new IllegalArgumentException(
            "ActualProductionPlan.byHousehold 的 key 必须等于值内 household：key="
                + key.value()
                + "，value="
                + value.household().value());
      }
      copy.put(key, value);
    }
    return Collections.unmodifiableMap(copy);
  }

  private static Map<ProductionUnitId, PositionPlan.UnitPlan> copyUnitPlans(
      Map<ProductionUnitId, PositionPlan.UnitPlan> source) {
    Objects.requireNonNull(source, "ActualProductionPlan.byUnit 不得为 null");
    List<ProductionUnitId> keys = new ArrayList<>(source.keySet());
    for (ProductionUnitId key : keys) {
      Objects.requireNonNull(key, "ActualProductionPlan.byUnit 的 key 不得为 null");
    }
    keys.sort(Comparator.comparing(ProductionUnitId::value));
    LinkedHashMap<ProductionUnitId, PositionPlan.UnitPlan> copy = new LinkedHashMap<>();
    for (ProductionUnitId key : keys) {
      PositionPlan.UnitPlan value = source.get(key);
      if (value == null) {
        throw new IllegalArgumentException(
            "ActualProductionPlan.byUnit 的 value 不得为 null: " + key.value());
      }
      if (!key.equals(value.unit())) {
        throw new IllegalArgumentException(
            "ActualProductionPlan.byUnit 的 key 必须等于值内 unit：key="
                + key.value()
                + "，value="
                + value.unit().value());
      }
      copy.put(key, value);
    }
    return Collections.unmodifiableMap(copy);
  }

  private static List<EmploymentLock> copyLocks(List<EmploymentLock> source) {
    Objects.requireNonNull(source, "ActualProductionPlan.locksReadOnly 不得为 null（无锁请用空表）");
    List<EmploymentLock> copy = new ArrayList<>(source.size());
    for (EmploymentLock lock : source) {
      if (lock == null) {
        throw new IllegalArgumentException("ActualProductionPlan.locksReadOnly 不得含 null");
      }
      copy.add(lock);
    }
    copy.sort(
        Comparator.comparing((EmploymentLock lock) -> lock.unit().value())
            .thenComparing(lock -> lock.employer().value())
            .thenComparing(lock -> lock.worker().value())
            .thenComparing(lock -> lock.role().value())
            .thenComparing(lock -> lock.currency().value())
            .thenComparingLong(EmploymentLock::laborMilliPerDay)
            .thenComparingLong(EmploymentLock::wagePerLaborMilli));
    return List.copyOf(copy);
  }
}
