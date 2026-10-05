package io.mosire.simos.app.access;

import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * **政府决策人**的可见范围（阶段 10a，用户裁定 4：<b>直辖</b>）。
 *
 * <p>链路：{@code Affiliation.Gov(govUnitId)} → unit 切片里那个单位（<b>必须存在且带 {@link GovernmentFormation}，否则
 * deny-all 四命名空间</b>，照 {@code ArmyScope} 的 fail-closed）→ 本级 {@code Unit.jurisdiction} 的 Region 集合 ⇒
 * 这些 Region 的<b>逐格</b> hex 前缀 + Region 级前缀 + 自己所在格。
 *
 * <p>★★ <b>这里只算"直辖"</b>：中央决策人读不到省的数据，除非中央自己的 {@code jurisdiction} 里就有那个 Region（or 用 {@code
 * sd.IssueDirective} 的审批链）。<b>名义全境</b>（沿 {@code superiorGov} 聚合下级 GOV 的管辖）在 {@link
 * io.mosire.simos.app.gov.GovTerritory}，<b>绝不进任何授权判定</b>——用户裁定 4 明令它是显示/后续 NationSummary 的只读派生。
 *
 * <p>★ <b>四个命名空间各自表态</b>（与 {@code NationScope}/{@code ArmyScope} 同一条纪律，spec §5.2 第 3 条）：
 *
 * <ul>
 *   <li>{@code map} = 管辖 Region 的逐格 hex 前缀 + {@code map:<mapId>/region/<rid>} 前缀 + 自己所在格；
 *   <li>{@code social} = 上述 hex 的 {@code <q>_<r>}（人口按格取，路径不带 mapId）；
 *   <li>{@code unit} = 自己 + 位置落在上述 hex 的单位；
 *   <li>{@code actor} = <b>只授自己 GOV 与 {@code superiorGov} 两个 GOV 的国库格路径</b>（ {@link
 *       ResourcePaths#actor(int, int)}，即 {@code q_r}），供显式 {@code actor.RemitGovTreasury}
 *       上缴；<b>不是</b>全辖区 actor，也不把辖区里的其他账列进来。
 * </ul>
 *
 * <p>★★ <b>两条边界（控制方口径，逐条实现）</b>：
 *
 * <ol>
 *   <li><b>{@code Unit.jurisdiction} 为空</b> ⇒ 只授"自己所在格 + 自己"（集中办公的直辖最小集）：不 deny-all（那会让新建
 *       中央的决策人当场变瞎），也不放全量；{@code unit} 命名空间<b>只含自己</b>——同格的其他单位不因"集中办公"自动进入直辖 （它们要进范围，得由本级
 *       jurisdiction 定义，或由另一条命令授权）。
 *   <li><b>单位没有有效位置</b> ⇒ <b>只授 unit 命名空间里自己那一条前缀</b>：{@code map}/{@code social}/{@code actor} 显式
 *       {@code none()}（不是回落缺省、也不是全放行）。管辖 Region 此刻**不参与**授权——"不知道自己在哪"是 fail-closed
 *       信号，先授最小身份面，等有了位置再按位置+管辖现算。
 * </ol>
 *
 * <p>★ <b>区域查无</b>（unit 的 jurisdiction 指着一个 map 里已不存在的 RegionId）：跳过该 Region（不产生前缀），不整体
 * deny-all——一个区域的删除不该让整个政府机关变瞎；最终范围按剩下的 Region + 自己所在格算，空结果仍走显式 {@code none()}。
 */
public final class GovScope implements DecisionScopeFunction {

  /** 无状态实现 ⇒ 一个实例够用（注册表里按类型存的就是它）。 */
  public static final GovScope INSTANCE = new GovScope();

  private GovScope() {}

  @Override
  public ResourceScopeMap scopesFor(DecisionMaker dm, SimulationState state, String mapId) {
    Objects.requireNonNull(dm, "dm");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(mapId, "mapId");
    if (!(dm.affiliation() instanceof Affiliation.Gov gov)) {
      throw new IllegalArgumentException(
          "GovScope 只服务 Affiliation.Gov，收到 "
              + dm.affiliation().getClass().getName()
              + "（分派由 DecisionScopeFunctions 负责）");
    }
    // ★ 装配故障（缺 unit 切片）当场炸；"单位不存在 / 不是 GOV"是世界数据的问题 ⇒ deny-all，不抛。
    UnitState units = ToolSupport.unitState(state);
    Unit govUnit = units.units().get(gov.govUnit());
    if (govUnit == null || !(govUnit.module().orElse(null) instanceof GovernmentFormation)) {
      return denyAll();
    }

    SimosTimestamp at = state.meta().timestamp();
    Optional<HexCoord> own = units.effectivePosition(govUnit.id(), at);
    if (own.isEmpty()) {
      // ★ 边界二：无位置 ⇒ 只授 unit 前缀（map/social/actor 显式 none，管辖不参与）。
      return ResourceScopeMap.of(ToolSupport.MAP_NAMESPACE, ResourceScope.none())
          .withNamespace(
              ToolSupport.UNIT_NAMESPACE,
              DecisionScopeFunction.scopeOfPrefixes(
                  Set.of(ToolSupport.resourceUnit(govUnit.id().value()).path())))
          .withNamespace(ToolSupport.SOCIAL_NAMESPACE, ResourceScope.none())
          .withNamespace(ToolSupport.ACTOR_NAMESPACE, ResourceScope.none());
    }

    // ★ 前置检查已确认 module 是 GovernmentFormation；superiorGov 是"显式上缴"的授权链（类注四个命名空间）。
    GovernmentFormation governmentFormation = (GovernmentFormation) govUnit.module().orElseThrow();
    GameMap map = ToolSupport.gameMap(state);
    Set<RegionId> jurisdictionRegions =
        govUnit
            .jurisdiction()
            .map(j -> new LinkedHashSet<>(j.taxRatePerMilleByRegion().keySet()))
            .orElseGet(LinkedHashSet::new);

    Set<HexCoord> authorizedHexes = new LinkedHashSet<>(); // 保插入序（Region 表序 + own 先加）
    authorizedHexes.add(own.get()); // ★ 自己所在格恒在（用户口径：直辖 = own + jurisdiction）
    Set<String> regionPrefixes = new TreeSet<>(); // ★ 有序：范围内容与迭代序无关
    for (RegionId regionId : jurisdictionRegions) {
      Region region = map.regions().get(regionId);
      if (region == null) {
        continue; // ★ 区域查无 ⇒ 跳过该 Region（见类注），不整体 deny-all
      }
      regionPrefixes.add(ToolSupport.resourceRegion(mapId, regionId.value()).path());
      authorizedHexes.addAll(region.hexes());
    }

    Set<String> mapPrefixes = new TreeSet<>();
    mapPrefixes.addAll(regionPrefixes);
    // ★ 逐格 hex 前缀（含自己所在格与管辖 Region 的全部 hex）：region 前缀只覆盖"区域读口"，
    //   直接对 map:<mapId>/hex/<q>_<r> 的判定/命令目标声明必须逐格授权（控制方 2026-10-01 修复）。
    for (HexCoord coord : authorizedHexes) {
      mapPrefixes.add(ToolSupport.resourceHex(mapId, coord.q(), coord.r()).path());
    }

    Set<String> socialPrefixes = new TreeSet<>();
    for (HexCoord coord : authorizedHexes) {
      socialPrefixes.add(ToolSupport.resourceSocial(coord.q(), coord.r()).path());
    }

    // ★ actor：只授自己 + superiorGov 两个 GOV 的国库格路径（显式上缴 actor.RemitGovTreasury 的目标声明）。
    //   superiorGov 不存在 / 不在 unit 切片 / 当刻无位置 ⇒ 不加那一条（不整体 deny-all、也不放全量）。
    Set<String> actorPrefixes = new TreeSet<>();
    actorPrefixes.add(ResourcePaths.actor(own.get().q(), own.get().r()));
    governmentFormation
        .superiorGov()
        .filter(units.units()::containsKey)
        .flatMap(superiorId -> units.effectivePosition(superiorId, at))
        .ifPresent(
            superiorAt -> actorPrefixes.add(ResourcePaths.actor(superiorAt.q(), superiorAt.r())));

    Set<String> unitPrefixes = new TreeSet<>();
    unitPrefixes.add(ToolSupport.resourceUnit(govUnit.id().value()).path());
    if (!jurisdictionRegions.isEmpty()) {
      // ★ 边界一：只有本级确有管辖时才把"位置落在授权 hex 的单位"纳进来；空管辖只授自己（见类注）。
      for (Unit unit : units.units().values()) {
        Optional<HexCoord> position = units.effectivePosition(unit.id(), at);
        if (position.isPresent() && authorizedHexes.contains(position.get())) {
          unitPrefixes.add(ToolSupport.resourceUnit(unit.id().value()).path());
        }
      }
    }

    // ★ 四个命名空间**都要表态**：只配 map 会让 unit/social/actor 回落到工具缺省策略（READ_ONLY）⇒ 静默全放行。
    return ResourceScopeMap.of(
            ToolSupport.MAP_NAMESPACE, DecisionScopeFunction.scopeOfPrefixes(mapPrefixes))
        .withNamespace(
            ToolSupport.UNIT_NAMESPACE, DecisionScopeFunction.scopeOfPrefixes(unitPrefixes))
        .withNamespace(
            ToolSupport.SOCIAL_NAMESPACE, DecisionScopeFunction.scopeOfPrefixes(socialPrefixes))
        .withNamespace(
            ToolSupport.ACTOR_NAMESPACE, DecisionScopeFunction.scopeOfPrefixes(actorPrefixes));
  }

  /** 四命名空间显式 deny-all（照 {@code ArmyScope.denyAll}）：单位不存在 / 不是 GOV 时使用。 */
  private static ResourceScopeMap denyAll() {
    return ResourceScopeMap.of(ToolSupport.MAP_NAMESPACE, ResourceScope.none())
        .withNamespace(ToolSupport.UNIT_NAMESPACE, ResourceScope.none())
        .withNamespace(ToolSupport.SOCIAL_NAMESPACE, ResourceScope.none())
        .withNamespace(ToolSupport.ACTOR_NAMESPACE, ResourceScope.none());
  }
}
