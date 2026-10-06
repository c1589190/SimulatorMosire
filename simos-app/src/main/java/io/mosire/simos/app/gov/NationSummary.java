package io.mosire.simos.app.gov;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.GovernmentLevel;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * <b>Nation 的只读派生概括视图</b>（阶段 12，计划 §2.4 / 用户裁定 4/5/7）：每个 {@link GovernmentLevel#CENTRAL} 的中央 GOV 沿
 * {@link GovernmentFormation#superiorGov()} <b>向下</b>收集自己 + 全部下级 GOV，汇总显示名、名义辖区与人口。
 *
 * <p>★★ <b>只给显示/后续外交，绝不进任何授权判定</b>（裁定 4）：它不给 {@code GovScope}/{@code ArmyScope}/{@code
 * AdjudicateTick} 使用，也不是 {@code sd.Nation} 的替代或状态。{@link #of} 是**纯函数**——不落盘、不新增状态、 不改任何切片；每次调用现算。
 *
 * <p>★ <b>口径</b>：
 *
 * <ul>
 *   <li>{@code displayName} = 中央 GOV 单位名；{@code centralGovUnit} = 中央 GOV 的 {@link UnitId}；
 *   <li>{@code govUnits} = 以中央为根的 BFS 序（自己在前，随后按 {@code units} 插入序的直接下级，逐层展开； {@code seen} 防环）；
 *   <li>{@code nominalRegions} = 上述 GOV 的 {@code Unit.jurisdiction} 各 Region 的并集，保首次出现序；
 *   <li>{@code totalPopulation} = nominalRegions 覆盖的 <b>hex 并集</b>上 {@link
 *       SocialData#populationAt(HexCoord)} 之和；Region 查无 ⇒ 跳过；同一格被多个 Region 覆盖只计一次（名义领地按格并入）。
 * </ul>
 *
 * <p>★ <b>坏数据的降级</b>：悬空 {@code superiorGov}/悬空单位 BFS 时跳过该支，不抛；手工拼出的环由 {@code seen} 兜底。 一个没有 {@code
 * GovernmentFormation} 的层级根不会被本视图当作 Nation；没有 CENTRAL GOV 时返回空列表。
 *
 * @param displayName 中央 GOV 单位名（非空白）
 * @param centralGovUnit 中央 GOV 单位 id（非 null）
 * @param govUnits 该中央统领的全部 GOV（保序不可变，含自己；非 null）
 * @param nominalRegions 名义辖区 Region 并集（保序不可变；非 null）
 * @param totalPopulation 名义辖区 hex 并集的人口合计
 */
public record NationSummary(
    String displayName,
    UnitId centralGovUnit,
    List<UnitId> govUnits,
    Set<RegionId> nominalRegions,
    long totalPopulation) {

  public NationSummary {
    if (displayName == null || displayName.isBlank()) {
      throw new IllegalArgumentException("displayName 不得为空白");
    }
    if (centralGovUnit == null) {
      throw new IllegalArgumentException("centralGovUnit 不得为 null");
    }
    if (govUnits == null) {
      throw new IllegalArgumentException("govUnits 不得为 null");
    }
    if (nominalRegions == null) {
      throw new IllegalArgumentException("nominalRegions 不得为 null");
    }
    List<UnitId> govCopy = new ArrayList<>(govUnits.size());
    for (UnitId id : govUnits) {
      if (id == null) {
        throw new IllegalArgumentException("govUnits 不得含 null");
      }
      govCopy.add(id);
    }
    govUnits = Collections.unmodifiableList(govCopy);
    Set<RegionId> regionCopy = new LinkedHashSet<>();
    for (RegionId regionId : nominalRegions) {
      if (regionId == null) {
        throw new IllegalArgumentException("nominalRegions 不得含 null");
      }
      regionCopy.add(regionId);
    }
    nominalRegions = Collections.unmodifiableSet(regionCopy);
  }

  /**
   * 派生当前所有 CENTRAL GOV 的 NationSummary（纯函数，见类注）。
   *
   * @param units unit 切片（GOV 编制与管辖的唯一真值来源）；不得为 null
   * @param map 地图切片（Region → hex 展开）；不得为 null
   * @param social 社会切片（人口读数）；不得为 null
   * @return 按 {@code units} 插入序排列的不可变列表（每个 CENTRAL GOV 一项；无 CENTRAL ⇒ 空列表）
   */
  public static List<NationSummary> of(UnitState units, GameMap map, SocialData social) {
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(social, "social");

    // 反向索引 superiorGov → 直接下级 GOV（按 units 插入序；BFS 结果序 = 内容的纯函数）。
    Map<UnitId, List<UnitId>> children = new LinkedHashMap<>();
    for (Unit unit : units.units().values()) {
      if (unit.module().orElse(null) instanceof GovernmentFormation formation
          && formation.superiorGov().isPresent()) {
        children
            .computeIfAbsent(formation.superiorGov().get(), key -> new ArrayList<>())
            .add(unit.id());
      }
    }

    List<NationSummary> summaries = new ArrayList<>();
    for (Unit unit : units.units().values()) {
      if (!(unit.module().orElse(null) instanceof GovernmentFormation central)
          || central.level() != GovernmentLevel.CENTRAL) {
        continue;
      }

      List<UnitId> govUnits = new ArrayList<>();
      Set<RegionId> nominalRegions = new LinkedHashSet<>();
      Set<UnitId> seen = new LinkedHashSet<>();
      Deque<UnitId> queue = new ArrayDeque<>();
      queue.add(unit.id());
      while (!queue.isEmpty()) {
        UnitId govId = queue.poll();
        if (!seen.add(govId)) {
          continue; // 环/重复入队：显示端不许死循环
        }
        Unit govUnit = units.units().get(govId);
        if (govUnit == null || !(govUnit.module().orElse(null) instanceof GovernmentFormation)) {
          continue; // 悬空 superiorGov：跳过这一支，不抛（显示派生对坏数据降级）
        }
        govUnits.add(govId);
        govUnit
            .jurisdiction()
            .ifPresent(j -> nominalRegions.addAll(j.taxRatePerMilleByRegion().keySet()));
        queue.addAll(children.getOrDefault(govId, List.of()));
      }

      long totalPopulation = 0L;
      Set<HexCoord> countedHexes = new LinkedHashSet<>();
      for (RegionId regionId : nominalRegions) {
        Region region = map.regions().get(regionId);
        if (region == null) {
          continue; // Region 查无 ⇒ 跳过（不因一个悬空区域整体作废）
        }
        for (HexCoord hex : region.hexes()) {
          if (countedHexes.add(hex)) {
            totalPopulation += social.populationAt(hex);
          }
        }
      }

      summaries.add(
          new NationSummary(unit.name(), unit.id(), govUnits, nominalRegions, totalPopulation));
    }
    return List.copyOf(summaries);
  }
}
