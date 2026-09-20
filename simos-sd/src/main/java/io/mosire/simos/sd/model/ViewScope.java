package io.mosire.simos.sd.model;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.unit.UnitId;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 可查看范围（spec §七.2，R10 / N6）：**配权本身是数据**——存在 {@code DecisionMaker} 里、进 {@code SdState}/revision ⇒
 * 可回放、可回退分岔。
 *
 * <p>★ 集合一律**保序不可变**（{@code LinkedHashSet} + 冻在赋值处；**禁用** {@code Set.copyOf}——迭代序不是内容的纯函数）。
 * 冻结写在赋值处是 SpotBugs 的硬要求（{@code EI_EXPOSE_REP} 只认构造器体内看得见的包装调用）。
 */
public record ViewScope(
    Set<RegionId> visibleRegions,
    Set<HexCoord> visibleHexes,
    Set<UnitId> visibleUnits,
    boolean seeOwnUnits,
    DisclosurePolicy adjudicationDisclosure,
    Set<String> redactedFields) {

  public ViewScope {
    if (visibleRegions == null) {
      throw new IllegalArgumentException("visibleRegions 不得为 null");
    }
    if (visibleHexes == null) {
      throw new IllegalArgumentException("visibleHexes 不得为 null");
    }
    if (visibleUnits == null) {
      throw new IllegalArgumentException("visibleUnits 不得为 null");
    }
    if (adjudicationDisclosure == null) {
      throw new IllegalArgumentException("adjudicationDisclosure 不得为 null");
    }
    if (redactedFields == null) {
      throw new IllegalArgumentException("redactedFields 不得为 null");
    }
    Set<RegionId> regions = new LinkedHashSet<>();
    for (RegionId region : visibleRegions) {
      if (region == null) {
        throw new IllegalArgumentException("visibleRegions 不得含 null");
      }
      regions.add(region);
    }
    visibleRegions = Collections.unmodifiableSet(regions); // ★ 冻在赋值处
    Set<HexCoord> hexes = new LinkedHashSet<>();
    for (HexCoord hex : visibleHexes) {
      if (hex == null) {
        throw new IllegalArgumentException("visibleHexes 不得含 null");
      }
      hexes.add(hex);
    }
    visibleHexes = Collections.unmodifiableSet(hexes); // ★ 冻在赋值处
    Set<UnitId> units = new LinkedHashSet<>();
    for (UnitId unit : visibleUnits) {
      if (unit == null) {
        throw new IllegalArgumentException("visibleUnits 不得含 null");
      }
      units.add(unit);
    }
    visibleUnits = Collections.unmodifiableSet(units); // ★ 冻在赋值处
    Set<String> fields = new LinkedHashSet<>();
    for (String field : redactedFields) {
      if (field == null || field.isBlank()) {
        throw new IllegalArgumentException("redactedFields 不得含空白");
      }
      fields.add(field);
    }
    redactedFields = Collections.unmodifiableSet(fields); // ★ 冻在赋值处
  }

  /** 空范围（无任何可见项）——往返用例与"默认全脱敏"的起点。 */
  public static ViewScope empty() {
    return new ViewScope(Set.of(), Set.of(), Set.of(), false, DisclosurePolicy.WITHHELD, Set.of());
  }
}
