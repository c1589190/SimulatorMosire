package io.mosire.simos.app.access;

import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.army.Army;
import io.mosire.simos.army.ArmyId;
import io.mosire.simos.army.ArmyVision;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * **军队决策人**的可见范围（spec §3.2）：军队当前位置 + 视野半径圈内的格。
 *
 * <p>链路（乙收尾起：表本体与写口都在 army）：{@code ArmyData.armyAffiliations()}（决策人 → 军队；写口 = {@code
 * army.AssignDecisionMakerArmy}）→ <b>归属裁定</b>（{@link AppArmyAffiliations#armyOf}：交给 army 的 {@link
 * io.mosire.simos.army.ArmyAffiliations} 拿 {@code ArmyData.armies()} 裁）→ {@link
 * Army#rootUnit()}（归属只存关系，编制在 unit） → {@link ArmyVision#visionHexes(UnitState,
 * io.mosire.simos.unit.UnitId, SimosTimestamp)} 取**当前位置 + 半径圈** ⇒ 前缀 {@code <mapId>/hex/<q>_<r>}
 * 每条（R=1 ⇒ 7 条、R=2 ⇒ 19 条，可控）。★★ <b>视野算法唯一拼写点在 {@code simos-army.ArmyVision}</b>，本类只做裁定/unit
 * 解引用与命名空间投影，不再内联半径算式。
 *
 * <p>★★ <b>2026-10-12 批次 3d-1：本类从"读自称"改成"读自称 × army 的裁定"</b>（与 3c 的 {@code NationScope}
 * 同构，逐条照抄）。{@code armies} 表与 {@code Army} / {@code ArmyId} 搬进 {@code simos-army}，而 {@code
 * Affiliation.Army} 的载荷收窄成**不透明串**（{@code sd} 不得依赖 {@code army}）⇒ 本类不再能自己 {@code
 * sdState(state).armies().get(...)}，改问唯一拼装点 {@link AppArmyAffiliations}。★ <b>乙收尾后那条"自称串"本身也消失了</b>
 * （{@code DecisionMaker.affiliation} 已删、{@code Affiliation} 本体已删）⇒ 本类读的是 army 自己的归属表 （{@code
 * ArmyData.armyAffiliations}，写口 = {@code army.AssignDecisionMakerArmy}）；链路与判定口径一字未变。
 *
 * <p>★ **位置走 {@code effectivePosition}**（与 GUI / 工具面 / facet 同口径）：根单位自身没有有效位置 ⇒ 空答案。
 *
 * <p>★ **纯半径，不做地形遮挡**（用户裁定⑥）：地形、河、敌情都不参与——遮挡是"视野功能"， 归 unit 模块将来做；本轮范围函数只按半径圈格。
 *
 * <p>★★ **算不出来就是"看不见"**（deny-all，fail-closed）：<b>归属读不到</b>（自称串解释不了 / 军队不存在）、军队不在 army 切片里、单位根不在
 * unit 切片里、单位没有有效位置（"不知道在哪"）四种情形都给 {@code ResourceScope.none()}—— 与 {@code RedactingQueryService}
 * 对未知 actor/军队的既有口径一致（那里同样给空集，不是给全量）。★ T10 起 deny-all 覆盖**四个**命名空间（只 deny map 会让 unit/social/actor
 * 回落工具缺省 ⇒ 静默全放行）。
 *
 * <p>★ **T10 补齐的三维 + R3b 的 actor**：{@code map} = 圈内格（逐格前缀）、{@code social} = 圈内格（人口按 hex）、{@code
 * unit} = 位置落在圈内的单位——后两者与国家实现**同一套口径**（"范围内的格/格上的单位"），只是"范围"从区域换成视野圈； {@code actor} = 显式 {@code
 * none()}（军队不走 GOV 国库上缴，{@code actor.RemitGovTreasury} 只对 GOV 决策人开放）。
 */
public final class ArmyScope implements DecisionScopeFunction {

  /** 无状态实现 ⇒ 一个实例够用（注册表里按类型存的就是它）。 */
  public static final ArmyScope INSTANCE = new ArmyScope();

  private ArmyScope() {}

  @Override
  public ResourceScopeMap scopesFor(DecisionMaker dm, SimulationState state, String mapId) {
    Objects.requireNonNull(dm, "dm");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(mapId, "mapId");
    // ★★ 2026-10-12 乙收尾：分派由 DecisionScopeFunctions 按**归属种类名**做（决策人身上不再带 Affiliation）——
    //   走到这里就意味着这个决策人有一条 army 归属；军队不在 armies 表里（那一行被 AppArmyAffiliations 裁掉）
    //   ⇒ 读不到归属 ⇒ 下面一律 deny-all（**不抛、不回落、不给全量**）。
    Optional<ArmyId> armyId = AppArmyAffiliations.armyOf(dm.id(), state);
    if (armyId.isEmpty()) {
      return denyAll();
    }
    Army affiliation = AppArmyAffiliations.armyOrNull(state, armyId.get());
    if (affiliation == null) {
      return denyAll();
    }
    UnitState units = ToolSupport.unitState(state);
    Optional<Set<HexCoord>> visible =
        ArmyVision.visionHexes(units, affiliation.rootUnit(), state.meta().timestamp());
    if (visible.isEmpty()) {
      return denyAll();
    }

    Set<String> prefixes = new TreeSet<>(); // ★ 有序：范围内容与球内迭代序无关（HashSet 不保序）
    Set<String> socialPrefixes = new TreeSet<>();
    Set<HexCoord> circle = visible.get();
    for (HexCoord coord : circle) {
      prefixes.add(ToolSupport.resourceHex(mapId, coord.q(), coord.r()).path());
      // social：圈内格（spec §3.3 的 social 路径是 <q>_<r>，不带 mapId）
      socialPrefixes.add(ToolSupport.resourceSocial(coord.q(), coord.r()).path());
    }

    // unit：**位置落在圈内的单位 + 它们的全部后代**——与国家实现同一套口径（"看得见的格上的单位"），只是"范围"从区域换成视野圈；
    //   ★ 2026-10-20：后代展开让"看得见上级 ⇒ 看得见其下属编制/单位家户"，但 map/social 仍只认圈内格（hex 权限不通用）。
    Set<UnitId> unitRoots = new LinkedHashSet<>();
    SimosTimestamp at = state.meta().timestamp();
    for (Unit unit : units.units().values()) {
      Optional<HexCoord> position = units.effectivePosition(unit.id(), at);
      if (position.isPresent() && circle.contains(position.get())) {
        unitRoots.add(unit.id());
      }
    }
    Set<String> unitPrefixes = new TreeSet<>();
    for (UnitId root : unitRoots) {
      unitPrefixes.add(ToolSupport.resourceUnit(root.value()).path());
    }
    for (UnitId descendant : ScopeUnitExpansion.descendants(units, unitRoots, at)) {
      unitPrefixes.add(ToolSupport.resourceUnit(descendant.value()).path());
    }

    // ★ 四个命名空间**都要表态**（同 NationScope）：只配 map ⇒ unit/social/actor 回落工具缺省 ⇒ 静默全放行。
    return ResourceScopeMap.of(
            ToolSupport.MAP_NAMESPACE, DecisionScopeFunction.scopeOfPrefixes(prefixes))
        .withNamespace(
            ToolSupport.UNIT_NAMESPACE, DecisionScopeFunction.scopeOfPrefixes(unitPrefixes))
        .withNamespace(
            ToolSupport.SOCIAL_NAMESPACE, DecisionScopeFunction.scopeOfPrefixes(socialPrefixes))
        .withNamespace(ToolSupport.ACTOR_NAMESPACE, ResourceScope.none());
  }

  private static ResourceScopeMap denyAll() {
    return ResourceScopeMap.of(ToolSupport.MAP_NAMESPACE, ResourceScope.none())
        .withNamespace(ToolSupport.UNIT_NAMESPACE, ResourceScope.none())
        .withNamespace(ToolSupport.SOCIAL_NAMESPACE, ResourceScope.none())
        .withNamespace(ToolSupport.ACTOR_NAMESPACE, ResourceScope.none());
  }
}
