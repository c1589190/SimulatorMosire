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
 * ★★★ <b>D-032 P3c1：M3 第一遍（预期）生产编排的瞬态结果</b>。
 *
 * <p>它保存三件 M6 必需的信息：① 家户选择的生产位置组合与家户级收益/劳动预算；② 本 tick 锁定的雇佣关系；③ M3 交给 M4 的 {@link
 * MarketIntent}。另外保存全部正收益候选，供 M6 只在非雇佣小生产的合法范围内重排；并保存 P4c1 的 {@link WageDemand} 清单（入选 MONEY_WAGE
 * 位置的招工需求，供 M6/M8 只读、P4c2 聚合）。它<b>不</b>写库存、货币、 土地、债务或生产实例；不落 {@code EconomyData}/ChangeSet/Codec。
 *
 * <p>★ 确定性：{@code byHousehold}、{@code byUnit}、{@code positiveCandidates} 的 key 一律按稳定 id 升序；{@code
 * locks} 按 (unit, employer, worker, role, currency, labor, wage) 升序；{@code wageDemands} 按 (unit,
 * marketZone, industry, role) 升序且同 {@code (unit, role)} 至多一条。{@code marketIntents} 的语义顺序由 M3 决定
 * （同优先级内的生成顺序会影响后续市场装配），因此保留调用方传入序；调用方必须用稳定生成序构造，不得从无序集合迭代。
 *
 * <p>★ 本类型只活在市场轮的一个 tick 内，不提供 {@code with}/{@code apply} 等状态改写口。
 */
record ExpectedProductionPlan(
    long day,
    Map<HouseholdId, HouseholdProductionPlan> byHousehold,
    Map<ProductionUnitId, PositionPlan.UnitPlan> byUnit,
    List<EmploymentLock> locks,
    List<MarketIntent> marketIntents,
    Map<HouseholdId, List<PositionPlan>> positiveCandidates,
    List<WageDemand> wageDemands) {

  ExpectedProductionPlan {
    if (day < 0L) {
      throw new IllegalArgumentException("ExpectedProductionPlan.day 不得为负: " + day);
    }
    byHousehold = copyHouseholdPlans(byHousehold);
    byUnit = copyUnitPlans(byUnit);
    locks = copyLocks(locks);
    marketIntents = copyMarketIntents(marketIntents);
    positiveCandidates = copyPositiveCandidates(positiveCandidates);
    wageDemands = copyWageDemands(wageDemands);
  }

  private static Map<HouseholdId, HouseholdProductionPlan> copyHouseholdPlans(
      Map<HouseholdId, HouseholdProductionPlan> source) {
    Objects.requireNonNull(source, "ExpectedProductionPlan.byHousehold 不得为 null");
    List<HouseholdId> keys = new ArrayList<>(source.keySet());
    for (HouseholdId key : keys) {
      Objects.requireNonNull(key, "ExpectedProductionPlan.byHousehold 的 key 不得为 null");
    }
    keys.sort(Comparator.comparing(HouseholdId::value));
    LinkedHashMap<HouseholdId, HouseholdProductionPlan> copy = new LinkedHashMap<>();
    for (HouseholdId key : keys) {
      HouseholdProductionPlan value = source.get(key);
      if (value == null) {
        throw new IllegalArgumentException(
            "ExpectedProductionPlan.byHousehold 的 value 不得为 null: " + key.value());
      }
      if (!key.equals(value.household())) {
        throw new IllegalArgumentException(
            "ExpectedProductionPlan.byHousehold 的 key 必须等于值内 household：key="
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
    Objects.requireNonNull(source, "ExpectedProductionPlan.byUnit 不得为 null");
    List<ProductionUnitId> keys = new ArrayList<>(source.keySet());
    for (ProductionUnitId key : keys) {
      Objects.requireNonNull(key, "ExpectedProductionPlan.byUnit 的 key 不得为 null");
    }
    keys.sort(Comparator.comparing(ProductionUnitId::value));
    LinkedHashMap<ProductionUnitId, PositionPlan.UnitPlan> copy = new LinkedHashMap<>();
    for (ProductionUnitId key : keys) {
      PositionPlan.UnitPlan value = source.get(key);
      if (value == null) {
        throw new IllegalArgumentException(
            "ExpectedProductionPlan.byUnit 的 value 不得为 null: " + key.value());
      }
      if (!key.equals(value.unit())) {
        throw new IllegalArgumentException(
            "ExpectedProductionPlan.byUnit 的 key 必须等于值内 unit：key="
                + key.value()
                + "，value="
                + value.unit().value());
      }
      copy.put(key, value);
    }
    return Collections.unmodifiableMap(copy);
  }

  private static List<EmploymentLock> copyLocks(List<EmploymentLock> source) {
    Objects.requireNonNull(source, "ExpectedProductionPlan.locks 不得为 null（无锁请用空表）");
    List<EmploymentLock> copy = new ArrayList<>(source.size());
    for (EmploymentLock lock : source) {
      if (lock == null) {
        throw new IllegalArgumentException("ExpectedProductionPlan.locks 不得含 null");
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

  private static List<MarketIntent> copyMarketIntents(List<MarketIntent> source) {
    Objects.requireNonNull(source, "ExpectedProductionPlan.marketIntents 不得为 null（无意向请用空表）");
    List<MarketIntent> copy = new ArrayList<>(source.size());
    for (MarketIntent intent : source) {
      if (intent == null) {
        throw new IllegalArgumentException("ExpectedProductionPlan.marketIntents 不得含 null");
      }
      copy.add(intent);
    }
    // 保留 M3 的规范生成序；MarketIntent 的 priority/side/kind 语义依赖这份顺序。
    return List.copyOf(copy);
  }

  private static Map<HouseholdId, List<PositionPlan>> copyPositiveCandidates(
      Map<HouseholdId, List<PositionPlan>> source) {
    Objects.requireNonNull(source, "ExpectedProductionPlan.positiveCandidates 不得为 null（无候选请用空表）");
    List<HouseholdId> keys = new ArrayList<>(source.keySet());
    for (HouseholdId key : keys) {
      Objects.requireNonNull(key, "ExpectedProductionPlan.positiveCandidates 的 key 不得为 null");
    }
    keys.sort(Comparator.comparing(HouseholdId::value));
    LinkedHashMap<HouseholdId, List<PositionPlan>> copy = new LinkedHashMap<>();
    for (HouseholdId key : keys) {
      List<PositionPlan> candidates = source.get(key);
      if (candidates == null) {
        throw new IllegalArgumentException(
            "ExpectedProductionPlan.positiveCandidates 的 value 不得为 null: " + key.value());
      }
      if (candidates.isEmpty()) {
        throw new IllegalArgumentException(
            "ExpectedProductionPlan.positiveCandidates 的 value 不得为空表（无候选请省略该户）: " + key.value());
      }
      List<PositionPlan> candidateCopy = new ArrayList<>(candidates.size());
      for (PositionPlan candidate : candidates) {
        if (candidate == null) {
          throw new IllegalArgumentException(
              "ExpectedProductionPlan.positiveCandidates 不得含 null: " + key.value());
        }
        if (candidate.positionNetMilli() <= 0L) {
          throw new IllegalArgumentException(
              "ExpectedProductionPlan.positiveCandidates 只允许净收益 > 0 的候选：household="
                  + key.value()
                  + "，unit="
                  + candidate.unit().value()
                  + "，net="
                  + candidate.positionNetMilli());
        }
        candidateCopy.add(candidate);
      }
      copy.put(key, List.copyOf(candidateCopy)); // 候选内部的排序由 M3 的稳定生成序负责
    }
    return Collections.unmodifiableMap(copy);
  }

  /**
   * ★★★ P4c1：冻结 {@link WageDemand} 清单 —— 非 null、按 {@link WageDemand#CANONICAL_ORDER} 稳定排序、同 {@code
   * (unit, role)} 至多一条（不重复累加；跨 unit 的锚级聚合留给 P4c2），并保持不可变。
   */
  private static List<WageDemand> copyWageDemands(List<WageDemand> source) {
    Objects.requireNonNull(source, "ExpectedProductionPlan.wageDemands 不得为 null（无招工需求请用空表）");
    List<WageDemand> copy = new ArrayList<>(source.size());
    for (WageDemand demand : source) {
      if (demand == null) {
        throw new IllegalArgumentException("ExpectedProductionPlan.wageDemands 不得含 null");
      }
      copy.add(demand);
    }
    copy.sort(WageDemand.CANONICAL_ORDER);
    java.util.Set<String> seen = new java.util.LinkedHashSet<>();
    for (WageDemand demand : copy) {
      String key = demand.unit().value() + "\u0000" + demand.role().value();
      if (!seen.add(key)) {
        throw new IllegalArgumentException(
            "ExpectedProductionPlan.wageDemands 同 (unit, role) 不得重复: "
                + demand.unit().value()
                + "|"
                + demand.role().value());
      }
    }
    return List.copyOf(copy);
  }
}
