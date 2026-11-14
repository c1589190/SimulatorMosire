package io.mosire.simos.gov.tools;

import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.nation.Nation;
import io.mosire.simos.gov.nation.NationId;
import io.mosire.simos.gov.nation.NationTag;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Jurisdiction;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 国家读视图（{@code simos.gov.nations}、{@code simos.gov.editNation} 与网页共用的一份派生）。
 *
 * <p>★★ <b>唯一领土口径</b>：国家的领土 = 所有 {@code Region.meta().tag()} <b>逐字等于</b> {@code nation:<nationId>}
 * 的区域的并集（不是前缀匹配；{@code nation:FRAX} 不属于 {@code FRA}）。并集按格去重，绝不双计数。
 *
 * <p>★★ <b>颜色/名称</b>：颜色来自国家区域元数据（优先 homeRegion），名称来自 {@link Nation#name()}。同国多个区域有不同的颜色时， 结果显式给出
 * {@code colorConflict=true}，让网页/PNG 不静默挑一个。
 *
 * <p>★★ <b>GOV 关系</b>：gov 没有 Nation→GOV 字段（Army 只认 GOV，Nation 也没有反向字段）；这里的 GOV 关系是<b>派生</b>： 带
 * {@link GovernmentFormation} 且 jurisdiction 覆盖了该国任一区域的单位。它是展示关系，不新增持久字段、也不替代决策人归属。
 *
 * <p>本类只读状态，不写任何切片；map 模块不认识 gov，本类也不把视图写回 map。
 */
public final class GovNationViews {

  private GovNationViews() {}

  /** 单个国家的派生视图。 */
  public record NationView(
      String nationId,
      String name,
      String homeRegionId,
      int adminBudgetPerTick,
      String color,
      boolean colorConflict,
      List<HexCoord> hexes,
      int hexCount,
      List<String> regionIds,
      List<String> govUnitIds) {

    public NationView {
      hexes = List.copyOf(hexes);
      regionIds = List.copyOf(regionIds);
      govUnitIds = List.copyOf(govUnitIds);
    }
  }

  /** 全部国家（按 nationId 稳定序）。 */
  public static List<NationView> all(
      SimulationState state, GovState gov, GameMap map, UnitState units) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(gov, "gov");
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(units, "units");
    List<Nation> nations = new ArrayList<>(gov.nations().values());
    nations.sort(Comparator.comparing(nation -> nation.id().value()));
    List<NationView> views = new ArrayList<>(nations.size());
    for (Nation nation : nations) {
      views.add(of(nation, map, units));
    }
    return List.copyOf(views);
  }

  /** 单个国家；不存在 ⇒ 空。 */
  public static Optional<NationView> find(
      SimulationState state, GovState gov, GameMap map, UnitState units, NationId nationId) {
    Nation nation = gov.nations().get(nationId);
    return nation == null ? Optional.empty() : Optional.of(of(nation, map, units));
  }

  /** 某国精确标签的全部区域（保 regionId 稳定序）。 */
  public static List<Region> nationRegions(GameMap map, NationId nationId) {
    String tag = NationTag.tagFor(nationId);
    List<Region> regions = new ArrayList<>();
    for (Region region : map.regions().values()) {
      if (tag.equals(region.meta().tag())) {
        regions.add(region);
      }
    }
    regions.sort(Comparator.comparing(region -> region.id().value()));
    return List.copyOf(regions);
  }

  /** 某国领土并集（自然序、去重）。 */
  public static List<HexCoord> union(GameMap map, NationId nationId) {
    Set<HexCoord> union = new LinkedHashSet<>();
    for (Region region : nationRegions(map, nationId)) {
      union.addAll(region.hexes());
    }
    List<HexCoord> ordered = new ArrayList<>(union);
    ordered.sort(Comparator.naturalOrder());
    return List.copyOf(ordered);
  }

  private static NationView of(Nation nation, GameMap map, UnitState units) {
    List<Region> regions = nationRegions(map, nation.id());
    Set<HexCoord> union = new LinkedHashSet<>();
    List<String> regionIds = new ArrayList<>(regions.size());
    String color = null;
    boolean colorConflict = false;
    for (Region region : regions) {
      regionIds.add(region.id().value());
      union.addAll(region.hexes());
      String regionColor = region.meta().color();
      if (regionColor == null || regionColor.isBlank()) {
        continue;
      }
      if (color == null) {
        color = regionColor;
      } else if (!color.equalsIgnoreCase(regionColor)) {
        colorConflict = true;
      }
    }
    Region home = map.regions().get(nation.homeRegion());
    if (home != null && home.meta().color() != null && !home.meta().color().isBlank()) {
      color = home.meta().color();
    }
    List<HexCoord> ordered = new ArrayList<>(union);
    ordered.sort(Comparator.naturalOrder());
    return new NationView(
        nation.id().value(),
        nation.name(),
        nation.homeRegion().value(),
        nation.adminBudgetPerTick(),
        color,
        colorConflict,
        List.copyOf(ordered),
        ordered.size(),
        List.copyOf(regionIds),
        govsOf(regions, units));
  }

  /** jurisdiction 与本国任一区域相交的 GOV 单位（稳定 id 序）。 */
  private static List<String> govsOf(List<Region> regions, UnitState units) {
    Set<RegionId> regionIds = new LinkedHashSet<>();
    for (Region region : regions) {
      regionIds.add(region.id());
    }
    List<String> govs = new ArrayList<>();
    for (Unit unit : units.units().values()) {
      if (!(unit.module().orElse(null) instanceof GovernmentFormation)) {
        continue;
      }
      Optional<Jurisdiction> jurisdiction = unit.jurisdiction();
      if (jurisdiction.isEmpty()) {
        continue;
      }
      Map<RegionId, Long> governed = jurisdiction.get().taxRatePerMilleByRegion();
      for (RegionId regionId : regionIds) {
        if (governed.containsKey(regionId)) {
          govs.add(unit.id().value());
          break;
        }
      }
    }
    govs.sort(Comparator.naturalOrder());
    return List.copyOf(govs);
  }

  /** 视图 → 工具载荷形状（map 结构保序；网页与 MCP 共用）。 */
  public static Map<String, Object> toPayload(NationView view) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("nationId", view.nationId());
    out.put("name", view.name());
    out.put("homeRegionId", view.homeRegionId());
    out.put("adminBudgetPerTick", view.adminBudgetPerTick());
    out.put("color", view.color());
    out.put("colorConflict", view.colorConflict());
    List<Map<String, Object>> hexes = new ArrayList<>(view.hexes().size());
    for (HexCoord hex : view.hexes()) {
      hexes.add(Map.of("q", hex.q(), "r", hex.r()));
    }
    out.put("hexes", List.copyOf(hexes));
    out.put("hexCount", view.hexCount());
    out.put("regionIds", view.regionIds());
    out.put("govUnitIds", view.govUnitIds());
    return out;
  }
}
