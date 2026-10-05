package io.mosire.simos.app.gov;

import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.unit.GovernmentFormation;
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
 * <b>名义全境</b>的派生视图（阶段 10a，用户裁定 4 / 计划 §2.2）：沿 {@code GovernmentFormation.superiorGov} 从某个 GOV
 * <b>向下</b>收集整棵 GOV 子树的 {@code Unit.jurisdiction} Region 并集。
 *
 * <p>★★ <b>只读派生，绝不进任何授权判定</b>（用户原话："中央行政单位默认只读直辖；地图上显示的 GOV 区域，是这个中央统领的 所有名义归属地方的集合"）：本类只给 GUI / 后续
 * {@code NationSummary} 显示用，<b>不得</b>被 {@code GovScope}、 {@code AdjudicateTick} 或任何资源判定调用。授权面只看本级
 * {@code Unit.jurisdiction}（{@code GovScope} 的直辖）。
 *
 * <p>★ <b>不含任何状态</b>：不落盘、不进 change set、不改 unit/gov 切片；每次调用现算。
 *
 * <p>★ <b>量纲</b>：返回 {@link RegionId} 集合（不是 hex）——"名义区域的集合"正是裁定 4 的显示语义；逐 hex 展开是显示端的事。
 *
 * <p>★ <b>起点含不含自己</b>：含。本方法返回的是"以给定 GOV 为根的名义全境"，中央自己的直辖 jurisdiction 也是它的名义领土
 * （这是显示语义的直接推论）。若要"只要下级、不含起点"，调用方在结果里减掉起点的 jurisdiction 即可——本类不为一个未裁决的 细分另开一个方法。
 *
 * <p>★ <b>边界</b>：起点查无 / 起点没有 {@code GovernmentFormation} ⇒ 空集（没有"名义全境"可谈，不抛）；环由访问集兜底（正常 路径下 {@code
 * unit.SetGovFormation} 只拒自指，手工拼出的环状态不能让显示端死循环）；缺 {@code superiorGov} 的 GOV 是 层级根，不会再向下延伸。
 */
public final class GovTerritory {

  private GovTerritory() {}

  /**
   * 以 {@code rootGovId} 为根，收集整棵 GOV 子树的管辖 Region 并集。
   *
   * @param units unit 切片（编制/管辖的唯一真值来源）
   * @param rootGovId 根 GOV 单位 id（查无或不是 GOV ⇒ 空集）
   * @return 保插入序的不可变 {@link RegionId} 集合（含起点自己的 jurisdiction；查无 ⇒ {@code Set.of()}）
   */
  public static Set<RegionId> nominalRegions(UnitState units, UnitId rootGovId) {
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(rootGovId, "rootGovId");
    Unit root = units.units().get(rootGovId);
    if (root == null || !(root.module().orElse(null) instanceof GovernmentFormation)) {
      return Set.of();
    }

    // ★ 反向索引：superiorGov → 直接下级 GOV（按 units 的插入序建，结果序 = 内容的纯函数）。
    Map<UnitId, List<UnitId>> children = new LinkedHashMap<>();
    for (Unit unit : units.units().values()) {
      if (unit.module().orElse(null) instanceof GovernmentFormation formation
          && formation.superiorGov().isPresent()) {
        children
            .computeIfAbsent(formation.superiorGov().get(), key -> new ArrayList<>())
            .add(unit.id());
      }
    }

    Set<RegionId> out = new LinkedHashSet<>();
    Set<UnitId> seen = new LinkedHashSet<>();
    Deque<UnitId> queue = new ArrayDeque<>();
    queue.add(rootGovId);
    while (!queue.isEmpty()) {
      UnitId id = queue.poll();
      if (!seen.add(id)) {
        continue; // 环/重复入队：显示端不许死循环
      }
      Unit unit = units.units().get(id);
      if (unit == null || !(unit.module().orElse(null) instanceof GovernmentFormation)) {
        continue; // 悬空 superiorGov：跳过这一支，不抛（显示派生对坏数据要能降级）
      }
      unit.jurisdiction().ifPresent(j -> out.addAll(j.taxRatePerMilleByRegion().keySet()));
      queue.addAll(children.getOrDefault(id, List.of()));
    }
    return Collections.unmodifiableSet(out); // ★ 冻在赋值处（返回处也是赋值语义）
  }
}
