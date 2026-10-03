package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.DemandEntry;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.economy.ProportionalSplit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★★ <b>需求 → 逐家户目标量</b>（R4-E2）：把 {@code EconomyData.demands} 的生效需求翻成"本格每个家户、每种商品每轮要补多少"。
 *
 * <p>★★ <b>两条口径</b>：
 *
 * <ul>
 *   <li>{@link DemandEntry.DemandScope#HOUSEHOLD}：直接归该家户；{@code PER_CAPITA} = 每人量 × 该户人口，{@code
 *       TOTAL} = 总量；
 *   <li>{@link DemandEntry.DemandScope#HEX}：按本格家户人口摊到户（{@link ProportionalSplit} 最大余数法；并列按 {@link
 *       HouseholdId#value()} 升序 —— 排序后的下标序就是并列序）。{@code PER_CAPITA} 先乘本格总人口再摊，{@code TOTAL} 直接摊。
 * </ul>
 *
 * <p>★★ <b>处理顺序 = 预算优先级</b>：返回的每个 list 按 {@code (priority 升序, DemandId 值升序)} 追加 ——
 * 调用方按这个序切预算，就能让高优先级需求先拿到有限的钱。多个需求落在同一个（户, 商品）时 list 有多项，调用方逐项扣减。
 *
 * <p>★★ <b>不落第二份状态</b>：本类只现算；需求账本仍是唯一真相（同 spec"订单生成前现算缺口，不落第二份"）。
 *
 * <p>★ <b>复杂度</b>：每个 hex 扫一遍 demand 表（表通常很小）；不触碰 unit×allocation 全表。
 */
final class DemandTargets {

  private DemandTargets() {}

  /**
   * 本格逐家户、逐商品的**有序**需求分段（每个 list 一项 = 一条有效需求分到该户的量）。
   *
   * @param demands 需求账本（可为空表）
   * @param rows 家户行（判人口、判 HOUSEHOLD 需求是否住在本格）
   * @param hex 本格
   * @param keys 本格的家户（{@code EconomySettlement.rowsByHex} 的序，调用方保证）
   * @param day 当前日（判 created/expires）
   */
  static Map<HouseholdId, Map<CommodityId, List<Long>>> partsForHex(
      Map<DemandId, DemandEntry> demands,
      Map<HouseholdId, ClassRow> rows,
      HexCoord hex,
      List<HouseholdId> keys,
      long day) {
    if (demands.isEmpty() || keys.isEmpty()) {
      return Map.of();
    }
    Map<HouseholdId, Long> populations = new LinkedHashMap<>();
    for (HouseholdId key : keys) {
      ClassRow row = rows.get(key);
      if (row != null) {
        populations.put(key, row.population());
      }
    }
    if (populations.isEmpty()) {
      return Map.of();
    }
    List<DemandEntry> effective = new ArrayList<>();
    for (DemandEntry demand : demands.values()) {
      if (!demand.effectiveOn(day)) {
        continue;
      }
      if (demand.scope() == DemandEntry.DemandScope.HOUSEHOLD) {
        if (populations.containsKey(demand.household().orElseThrow())) {
          effective.add(demand);
        }
      } else if (hex.equals(demand.hex().orElseThrow())) {
        effective.add(demand);
      }
    }
    if (effective.isEmpty()) {
      return Map.of();
    }
    // ★ 预算优先级序（同 priority 按 DemandId 值升序）；list 的追加序即调用方的扣预算序。
    effective.sort(
        Comparator.comparingInt(DemandEntry::priority)
            .thenComparing(demand -> demand.id().value()));
    Map<HouseholdId, Map<CommodityId, List<Long>>> parts = new LinkedHashMap<>();
    for (DemandEntry demand : effective) {
      if (demand.scope() == DemandEntry.DemandScope.HOUSEHOLD) {
        HouseholdId household = demand.household().orElseThrow();
        long amount = amountFor(demand, populations.getOrDefault(household, 0L));
        if (amount > 0L) {
          add(parts, household, demand.commodity(), amount);
        }
        continue;
      }
      // HEX：只把量摊到人口 > 0 的户（0 人口户的份额按定义是 0，不参与并列序）。
      List<HouseholdId> targets = new ArrayList<>();
      long totalPopulation = 0L;
      for (Map.Entry<HouseholdId, Long> entry : populations.entrySet()) {
        if (entry.getValue() > 0L) {
          targets.add(entry.getKey());
          totalPopulation = Math.addExact(totalPopulation, entry.getValue());
        }
      }
      if (targets.isEmpty()) {
        continue;
      }
      targets.sort(Comparator.comparing(HouseholdId::value));
      long total = amountFor(demand, totalPopulation);
      if (total <= 0L) {
        continue;
      }
      long[] weights = new long[targets.size()];
      for (int i = 0; i < targets.size(); i++) {
        weights[i] = populations.get(targets.get(i));
      }
      long[] shares = ProportionalSplit.byDenominator(total, weights, totalPopulation);
      for (int i = 0; i < targets.size(); i++) {
        if (shares[i] > 0L) {
          add(parts, targets.get(i), demand.commodity(), shares[i]);
        }
      }
    }
    return parts;
  }

  /** 全格的需求总量快照（读口归因用）：{@code hexKey → 家户 → 商品 → 目标总量}。与 {@link #partsForHex} 走同一段逻辑。 */
  static Map<String, Map<HouseholdId, Map<CommodityId, Long>>> totalsByHex(
      Map<DemandId, DemandEntry> demands, Map<HouseholdId, ClassRow> rows, long day) {
    if (demands.isEmpty() || rows.isEmpty()) {
      return Map.of();
    }
    Map<HexCoord, List<HouseholdId>> keysByHex = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, ClassRow> entry : rows.entrySet()) {
      keysByHex
          .computeIfAbsent(entry.getValue().view().hex(), ignored -> new ArrayList<>())
          .add(entry.getKey());
    }
    Map<String, Map<HouseholdId, Map<CommodityId, Long>>> totals = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, List<HouseholdId>> entry : keysByHex.entrySet()) {
      Map<HouseholdId, Map<CommodityId, List<Long>>> parts =
          partsForHex(demands, rows, entry.getKey(), entry.getValue(), day);
      if (parts.isEmpty()) {
        continue;
      }
      Map<HouseholdId, Map<CommodityId, Long>> byHousehold = new LinkedHashMap<>();
      for (Map.Entry<HouseholdId, Map<CommodityId, List<Long>>> household : parts.entrySet()) {
        Map<CommodityId, Long> byCommodity = new LinkedHashMap<>();
        for (Map.Entry<CommodityId, List<Long>> commodity : household.getValue().entrySet()) {
          long sum = 0L;
          for (long part : commodity.getValue()) {
            sum = Math.addExact(sum, part);
          }
          if (sum > 0L) {
            byCommodity.put(commodity.getKey(), sum);
          }
        }
        if (!byCommodity.isEmpty()) {
          byHousehold.put(household.getKey(), byCommodity);
        }
      }
      if (!byHousehold.isEmpty()) {
        totals.put(
            io.mosire.simos.economy.model.IndustryHexKeys.hexKey(
                entry.getKey().q(), entry.getKey().r()),
            byHousehold);
      }
    }
    return totals;
  }

  /** 单条需求对某人口的量：{@code PER_CAPITA} × 人口（安全乘），{@code TOTAL} = 原量。 */
  private static long amountFor(DemandEntry demand, long population) {
    if (demand.unit() == DemandEntry.DemandUnit.PER_CAPITA) {
      return Math.multiplyExact(demand.quantityPerCycle(), population);
    }
    return demand.quantityPerCycle();
  }

  private static void add(
      Map<HouseholdId, Map<CommodityId, List<Long>>> parts,
      HouseholdId household,
      CommodityId commodity,
      long amount) {
    parts
        .computeIfAbsent(household, ignored -> new LinkedHashMap<>())
        .computeIfAbsent(commodity, ignored -> new ArrayList<>())
        .add(amount);
  }
}
