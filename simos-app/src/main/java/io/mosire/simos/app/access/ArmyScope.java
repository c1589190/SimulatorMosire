package io.mosire.simos.app.access;

import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.army.ArmyVision;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.Army;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * **军队决策人**的可见范围（spec §3.2）：军队当前位置 + 视野半径圈内的格。
 *
 * <p>链路：{@code Affiliation.Army(armyId)} → sd 的 {@link Army#rootUnit()}（归属只存关系，编制在 unit） → {@link
 * ArmyVision#visionHexes(UnitState, io.mosire.simos.unit.UnitId, SimosTimestamp)} 取**当前位置 + 半径圈** ⇒
 * 前缀 {@code <mapId>/hex/<q>_<r>} 每条（R=1 ⇒ 7 条、R=2 ⇒ 19 条，可控）。★★ <b>视野算法唯一拼写点在 {@code
 * simos-army.ArmyVision}</b>，本类只做 sd/unit 解引用与命名空间投影，不再内联半径算式。
 *
 * <p>★ **位置走 {@code effectivePosition}**（与 GUI / 工具面 / facet 同口径）：根单位自身没有有效位置 ⇒ 空答案。
 *
 * <p>★ **纯半径，不做地形遮挡**（用户裁定⑥）：地形、河、敌情都不参与——遮挡是"视野功能"， 归 unit 模块将来做；本轮范围函数只按半径圈格。
 *
 * <p>★ **算不出来就是"看不见"**（deny-all，fail-closed）：军队不在 sd 切片里、单位根不在 unit 切片里、 单位没有有效位置（"不知道在哪"）三种情形都给
 * {@code ResourceScope.none()}—— 与 {@code RedactingQueryService} 对未知
 * actor/军队的既有口径一致（那里同样给空集，不是给全量）。★ T10 起 deny-all 覆盖**四个**命名空间（只 deny map 会让 unit/social/actor
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
    if (!(dm.affiliation() instanceof Affiliation.Army army)) {
      throw new IllegalArgumentException(
          "ArmyScope 只服务 Affiliation.Army，收到 "
              + dm.affiliation().getClass().getName()
              + "（分派由 DecisionScopeFunctions 负责）");
    }
    Army affiliation = sdState(state).armies().get(army.armyId());
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

    // unit：**位置落在圈内的单位**——与国家实现同一套口径（"看得见的格上的单位"），只是"范围"从区域换成视野圈。
    Set<String> unitPrefixes = new TreeSet<>();
    SimosTimestamp at = state.meta().timestamp();
    for (Unit unit : units.units().values()) {
      Optional<HexCoord> position = units.effectivePosition(unit.id(), at);
      if (position.isPresent() && circle.contains(position.get())) {
        unitPrefixes.add(ToolSupport.resourceUnit(unit.id().value()).path());
      }
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

  /** sd 切片（缺切片/类型不对 = 装配故障，当场炸——与 {@code ToolSupport#unitState} 同口径）。 */
  private static SdState sdState(SimulationState state) {
    Snapshot snapshot =
        state.module("sd").orElseThrow(() -> new IllegalStateException("状态里没有 sd 模块切片——装配故障"));
    if (!(snapshot instanceof SdSnapshot sdSnapshot)) {
      throw new IllegalStateException("sd 模块切片不是 SdSnapshot：" + snapshot.getClass().getName());
    }
    return sdSnapshot.state();
  }
}
