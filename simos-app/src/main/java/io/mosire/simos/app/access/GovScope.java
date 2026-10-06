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
import io.mosire.simos.unit.UnitId;
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
 *   <li>{@code unit} = 自己 + 位置落在上述 hex 的单位 + <b>上述全部单位的后代</b> + <b>下辖 GOV 单位及其后代</b>；
 *   <li>{@code actor} = <b>只授自己 GOV 与 {@code superiorGov} 两个 GOV 的国库格路径</b>（ {@link
 *       ResourcePaths#actor(int, int)}，即 {@code q_r}），供显式 {@code actor.RemitGovTreasury}
 *       上缴；<b>不是</b>全辖区 actor，也不把辖区里的其他账列进来。
 * </ul>
 *
 * <p>★★ <b>2026-10-20 用户裁定：只扩 {@code unit} 命名空间</b>（中央—地方博弈的边界）：
 *
 * <ul>
 *   <li>沿 {@code GovernmentFormation.superiorGov()} 上溯能到达本决策人 GOV 的**下辖 GOV** ⇒ 该 GOV 单位及其全部后代进
 *       {@code unit} 面；这是"中央政府能看到下辖政府的单位编制/单位家户"的落点；
 *   <li>★ <b>hex 权限不通用</b>：下辖 GOV 所在格子**不**因此进入 {@code map}/{@code social}——中央不能顺带读下级格子上的人口/地图；
 *       {@code actor} 也仍只保留自己 + {@code superiorGov} 的国库路径（原有口径，一字不扩）。
 *   <li>本类**不是** {@link io.mosire.simos.app.gov.GovTerritory}：那个是"名义全境"的显示派生；本类只产出授权前缀。
 * </ul>
 *
 * <p>★★ <b>两条边界（控制方口径，逐条实现）</b>：
 *
 * <ol>
 *   <li><b>{@code Unit.jurisdiction} 为空</b> ⇒ 只授"自己所在格 + 自己"（集中办公的直辖最小集）：不 deny-all（那会让新建
 *       中央的决策人当场变瞎），也不放全量；{@code unit} 命名空间含自己、自己的后代、以及下辖 GOV 链（2026-10-20 起）——同格的其他单位不因"集中办公"自动进入直辖
 *       （它们要进范围，得由本级 jurisdiction 定义，或由另一条命令授权）。
 *   <li><b>单位没有有效位置</b> ⇒ <b>只授 unit 命名空间里自己/后代/下辖 GOV 的前缀</b>：{@code map}/{@code social}/{@code actor} 显式
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

    Set<RegionId> jurisdictionRegions =
        govUnit
            .jurisdiction()
            .map(j -> new LinkedHashSet<>(j.taxRatePerMilleByRegion().keySet()))
            .orElseGet(LinkedHashSet::new);

    // ── map / social / actor：口径与 2026-10-20 之前逐字一致（本批**只扩 unit**）────────────────
    Set<HexCoord> authorizedHexes = new LinkedHashSet<>(); // 保插入序（Region 表序 + own 先加）
    Set<String> regionPrefixes = new TreeSet<>(); // ★ 有序：范围内容与迭代序无关
    Set<String> mapPrefixes = new TreeSet<>();
    Set<String> socialPrefixes = new TreeSet<>();
    Set<String> actorPrefixes = new TreeSet<>();
    if (own.isPresent()) {
      GameMap map = ToolSupport.gameMap(state);
      authorizedHexes.add(own.get()); // ★ 自己所在格恒在（用户口径：直辖 = own + jurisdiction）
      for (RegionId regionId : jurisdictionRegions) {
        Region region = map.regions().get(regionId);
        if (region == null) {
          continue; // ★ 区域查无 ⇒ 跳过该 Region（见类注），不整体 deny-all
        }
        regionPrefixes.add(ToolSupport.resourceRegion(mapId, regionId.value()).path());
        authorizedHexes.addAll(region.hexes());
      }
      mapPrefixes.addAll(regionPrefixes);
      // ★ 逐格 hex 前缀（含自己所在格与管辖 Region 的全部 hex）：region 前缀只覆盖"区域读口"，
      //   直接对 map:<mapId>/hex/<q>_<r> 的判定/命令目标声明必须逐格授权（控制方 2026-10-01 修复）。
      for (HexCoord coord : authorizedHexes) {
        mapPrefixes.add(ToolSupport.resourceHex(mapId, coord.q(), coord.r()).path());
        socialPrefixes.add(ToolSupport.resourceSocial(coord.q(), coord.r()).path());
      }
      // ★ actor：只授自己 + superiorGov 两个 GOV 的国库格路径（显式上缴 actor.RemitGovTreasury 的目标声明）。
      //   superiorGov 不存在 / 不在 unit 切片 / 当刻无位置 ⇒ 不加那一条（不整体 deny-all、也不放全量）。
      actorPrefixes.add(ResourcePaths.actor(own.get().q(), own.get().r()));
      ((GovernmentFormation) govUnit.module().orElseThrow())
          .superiorGov()
          .filter(units.units()::containsKey)
          .flatMap(superiorId -> units.effectivePosition(superiorId, at))
          .ifPresent(
              superiorAt -> actorPrefixes.add(ResourcePaths.actor(superiorAt.q(), superiorAt.r())));
    }

    // ── unit：本批唯一扩展的命名空间（用户裁定：可见单位的全部下属单位/下辖 GOV 都可操作）─────────────
    // roots = 自己 + （有位置时）辖区内单位 + 下辖 GOV；随后统一追加全部后代（一个实现，见 ScopeUnitExpansion）。
    Set<UnitId> unitRoots = new LinkedHashSet<>();
    unitRoots.add(govUnit.id());
    if (own.isPresent() && !jurisdictionRegions.isEmpty()) {
      // ★ 边界一：只有本级确有管辖时才把"位置落在授权 hex 的单位"纳进来；空管辖只授自己（见类注）。
      for (Unit unit : units.units().values()) {
        Optional<HexCoord> position = units.effectivePosition(unit.id(), at);
        if (position.isPresent() && authorizedHexes.contains(position.get())) {
          unitRoots.add(unit.id());
        }
      }
    }
    // ★★ 下辖 GOV：沿 superiorGov() 上溯能到达自己的 GOV 单位（含 indirect）⇒ 该 GOV 及其后代加入 unit 面。
    //   这是"中央政府能看下辖政府编制/单位家户"的落点；map/social/actor **不**由此扩（见类注）。
    unitRoots.addAll(subordinateGovUnits(units, govUnit.id()));

    Set<String> unitPrefixes = new TreeSet<>();
    for (UnitId root : unitRoots) {
      unitPrefixes.add(ToolSupport.resourceUnit(root.value()).path());
    }
    for (UnitId descendant : ScopeUnitExpansion.descendants(units, unitRoots, at)) {
      unitPrefixes.add(ToolSupport.resourceUnit(descendant.value()).path());
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

  /**
   * ★★ <b>下辖 GOV 集合</b>（2026-10-20 用户裁定）：扫描全部带 {@link GovernmentFormation} 的单位，沿
   * {@code superiorGov()} 逐级上溯；**能到达 {@code self}** 的那些 GOV 单位就是本决策人的下辖政府（含 indirect）。
   *
   * <p>★ <b>有界防环</b>：{@code seen} 记录本链走过的 id，重复即停；链断在"父查无 / 非 GOV / 无 superiorGov"也停。
   * 坏状态（手工拼的环）不会死循环，也不会把环上单位误判成下辖。
   *
   * <p>★ <b>不含 {@code self}</b>：自己由调用方显式加入 unit roots。
   */
  private static Set<UnitId> subordinateGovUnits(UnitState units, UnitId self) {
    Set<UnitId> subordinate = new LinkedHashSet<>();
    for (Unit unit : units.units().values()) {
      if (unit.id().equals(self)) {
        continue;
      }
      if (!(unit.module().orElse(null) instanceof GovernmentFormation)) {
        continue; // 下辖 GOV 的定义 = 带 GovernmentFormation 的单位；普通下属走 ScopeUnitExpansion 的后代展开
      }
      Set<UnitId> seen = new LinkedHashSet<>();
      UnitId current = unit.id();
      while (seen.add(current)) {
        Unit currentUnit = units.units().get(current);
        if (currentUnit == null
            || !(currentUnit.module().orElse(null) instanceof GovernmentFormation formation)) {
          break; // 链断在查无 / 非 GOV ⇒ 这条到不了 self
        }
        Optional<UnitId> superior = formation.superiorGov();
        if (superior.isEmpty()) {
          break; // 无上级（中央）⇒ 这条到不了 self
        }
        UnitId next = superior.get();
        if (next.equals(self)) {
          subordinate.add(unit.id());
          break;
        }
        if (seen.contains(next)) {
          break; // 环：有界退出
        }
        current = next;
      }
    }
    return subordinate;
  }

  /** 四命名空间显式 deny-all（照 {@code ArmyScope.denyAll}）：单位不存在 / 不是 GOV 时使用。 */
  private static ResourceScopeMap denyAll() {
    return ResourceScopeMap.of(ToolSupport.MAP_NAMESPACE, ResourceScope.none())
        .withNamespace(ToolSupport.UNIT_NAMESPACE, ResourceScope.none())
        .withNamespace(ToolSupport.SOCIAL_NAMESPACE, ResourceScope.none())
        .withNamespace(ToolSupport.ACTOR_NAMESPACE, ResourceScope.none());
  }
}
