package io.mosire.simos.app.access;

import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.spi.NationTag;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * **国家决策人**的可见范围（spec §3.2）：本国区域。
 *
 * <p>逻辑：扫 {@code GameMap.regions()}，取 {@code region.meta().tag()} **逐字等于** {@link NationTag#tagFor}
 * 的区域 ⇒ 前缀 {@code <mapId>/region/<rid>} 每条。
 *
 * <p>★ **为什么按区域、不逐 hex**：{@code ResourceScope} 的前缀是段边界匹配，逐 hex 要给几万条； 区域级只需几条到几十条（真档 59223 hex、98
 * 区域）。
 *
 * <p>★ **tag 比较用逐字相等**：{@code "nation:FRAX"} 不是 {@code "nation:FRA"}——
 * 写成前缀/子串匹配会把邻国（甚至任意带该前缀的名字）划进本国范围，且**不会报错**。
 *
 * <p>★ **无匹配区域 ⇒ 显式 deny-all**（{@link DecisionScopeFunction#scopeOfPrefixes}）：
 * 国家还没圈地、或区域全被删掉时，这个决策人**什么都看不见**，而不是"回落到不设限"。
 *
 * <p>★ **三个命名空间各自表态**（T10 起）：{@code map} = 本国区域、{@code unit} = 位置落在本国区域内的单位、 {@code social} =
 * 本国区域内的 hex（spec §3.2 逐条）。三者**必须同时配**——只配 {@code map} 会让 {@code unit}/{@code social}
 * 回落到工具缺省策略（{@code READ_ONLY}）⇒ **静默全放行**（spec §5.2 第 3 条："空 = 不表态"与"够不着"方向相反）。
 *
 * <p>★ **读侧的细粒度化必须与这里的配前缀成对上线**（spec §5.2 第 2 条）：粗断言（{@code unit:"*"}）撞上这里的 逐 id
 * 前缀会**整调被拒**——两者是同一轮（T10）的两半。
 */
public final class NationScope implements DecisionScopeFunction {

  /** 无状态实现 ⇒ 一个实例够用（注册表里按类型存的就是它）。 */
  public static final NationScope INSTANCE = new NationScope();

  private NationScope() {}

  @Override
  public ResourceScopeMap scopesFor(DecisionMaker dm, SimulationState state, String mapId) {
    Objects.requireNonNull(dm, "dm");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(mapId, "mapId");
    if (!(dm.affiliation() instanceof Affiliation.Nation nation)) {
      throw new IllegalArgumentException(
          "NationScope 只服务 Affiliation.Nation，收到 "
              + dm.affiliation().getClass().getName()
              + "（分派由 DecisionScopeFunctions 负责）");
    }
    String nationTag = NationTag.tagFor(nation.nationId());
    GameMap map = ToolSupport.gameMap(state);

    Set<String> regionPrefixes = new TreeSet<>(); // ★ 有序：范围内容与区域迭代序无关（Set.copyOf 不保序）
    Set<RegionId> ownRegions = new LinkedHashSet<>(); // RegionId 不是 Comparable ⇒ 只当成员集用
    Set<String> socialPrefixes = new TreeSet<>();
    for (Region region : map.regions().values()) {
      if (!nationTag.equals(region.meta().tag())) {
        continue;
      }
      ownRegions.add(region.id());
      regionPrefixes.add(ToolSupport.resourceRegion(mapId, region.id().value()).path());
      // social：本国区域内的 hex（spec §3.2 "人口按 hex 取"；spec §3.3 的 social 路径是 <q>_<r>，不带 mapId）
      for (HexCoord coord : region.hexes()) {
        socialPrefixes.add(ToolSupport.resourceSocial(coord.q(), coord.r()).path());
      }
    }

    // unit：**按单位位置落在本国区域内算**（spec §3.2）——与 GUI/facet 同口径走 effectivePosition
    // （编队里根单位自身没有位置、跟随父单位；自己读 position 字段会得到"不知道在哪"）。
    Set<String> unitPrefixes = new TreeSet<>();
    UnitState units = ToolSupport.unitState(state);
    SimosTimestamp at = state.meta().timestamp();
    for (Unit unit : units.units().values()) {
      Optional<HexCoord> position = units.effectivePosition(unit.id(), at);
      if (position.isEmpty()) {
        continue; // 不知道在哪 ⇒ 不是"本国单位"（fail-closed）
      }
      for (RegionId owner : map.regionIndex().regionOf(position.get())) {
        if (ownRegions.contains(owner)) {
          unitPrefixes.add(ToolSupport.resourceUnit(unit.id().value()).path());
          break;
        }
      }
    }

    // ★ 三个命名空间**都要表态**：只配 map 会让 unit/social 回落到工具缺省策略（READ_ONLY）⇒ **静默全放行**
    //   （spec §5.2 第 3 条：空 = 不表态 = 放行，"够不着"必须显式 none()）。
    return ResourceScopeMap.of(
            ToolSupport.MAP_NAMESPACE, DecisionScopeFunction.scopeOfPrefixes(regionPrefixes))
        .withNamespace(
            ToolSupport.UNIT_NAMESPACE, DecisionScopeFunction.scopeOfPrefixes(unitPrefixes))
        .withNamespace(
            ToolSupport.SOCIAL_NAMESPACE, DecisionScopeFunction.scopeOfPrefixes(socialPrefixes));
  }
}
