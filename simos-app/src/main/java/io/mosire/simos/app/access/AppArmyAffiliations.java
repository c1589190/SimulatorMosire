package io.mosire.simos.app.access;

import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.army.Army;
import io.mosire.simos.army.ArmyAffiliations;
import io.mosire.simos.army.ArmyId;
import io.mosire.simos.army.spi.ArmyAffiliationPort;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>{@link ArmyAffiliationPort} 的 app 实现</b>（<b>端口在 army、实现在 app、{@code Shell} 注入</b>）——
 * 2026-10-12 批次 3d-1，形制逐条照抄 3c 的 {@link AppNationAffiliations}。
 *
 * <p>★★ <b>为什么实现在 app</b>：这条判据要**同时**看两片——决策人记录（{@code ga} 切片里 {@code Affiliation.Army}
 * 的那个不透明引用）与军队表（{@code army} 切片）。{@code simos-army} 禁 {@code simos-ga}/{@code simos-sd}，{@code
 * simos-ga} 禁 {@code simos-army} ⇒ 只有组合根同时看得见两侧（与 {@code GovJurisdictionGuard} / {@code
 * AppNationAffiliations} 同一条理由）。
 *
 * <p>★★ <b>本类同时是"读侧唯一入口"</b>：{@link #of(SimulationState)} 是"这个世界里谁归属哪个军队"的<b>唯一拼装点</b>——
 * ①决策人记录里的<b>自称</b>（{@link Affiliation.Army} 的不透明串）；②army 的<b>裁定</b>（{@link
 * ArmyAffiliations#resolve} 拿 {@code ArmyData.armies()} 裁掉指向不存在军队的引用）。权限分派（{@link ArmyScope}）与 将来
 * army 自己的删除守卫（{@code army.DeleteArmy}，属乙第 4 步）问的都是这一处，不各写一遍。
 *
 * <p>★ <b>fail-closed 的分工</b>：<b>装配</b>问题（缺切片/类型不对）当场抛（{@link ToolSupport#gaState} 与 {@link
 * ToolSupport#armyData} 的口径）；<b>世界数据</b>问题（没有这个军队 / 没有这个决策人）降权——{@link ArmyAffiliations#resolve}
 * 把它裁掉，读侧拿不到归属 ⇒ <b>deny-all</b>。
 */
public final class AppArmyAffiliations implements ArmyAffiliationPort {

  /**
   * ★ <b>本类无实例状态，但今天不设 {@code INSTANCE} 常量</b>：端口实现只在 {@link #of} / {@link #armyOf} 两个静态装配点 被读（没有
   * {@code Shell} 注入点），留一个"看着能注入、其实无人注入"的常量就是本仓反复消灭的假接缝 （3c 的 {@code
   * AppNationAffiliations.INSTANCE} 是因为 gov 的删除守卫真的注入了它）。将来真出现注入点时再加。
   */
  private AppArmyAffiliations() {}

  /** 当前世界里成立的 army 归属表（唯一拼装点；空表 = 没有任何成立的归属 ⇒ 读侧一律 deny-all）。 */
  public static ArmyAffiliations of(SimulationState state) {
    Objects.requireNonNull(state, "state");
    return ArmyAffiliations.resolve(claimsOf(state), ToolSupport.armyData(state).armies());
  }

  /** 该决策人的军队；**空 = 读不到归属 ⇒ 调用方必须 deny-all**。 */
  public static Optional<ArmyId> armyOf(DecisionMakerId decisionMaker, SimulationState state) {
    Objects.requireNonNull(decisionMaker, "decisionMaker");
    return of(state).armyOf(decisionMaker);
  }

  /** 该军队的记录（{@code null} = 读不到 ⇒ 调用方按"没有归属"处置）。 */
  public static Army armyOrNull(SimulationState state, ArmyId armyId) {
    Objects.requireNonNull(armyId, "armyId");
    return ToolSupport.armyData(state).armies().get(armyId);
  }

  /**
   * 决策人记录里的<b>自称</b>（{@code Affiliation.Army} 的不透明串；其余归属种类不在此表里）。
   *
   * <p>★ 这是 {@link ArmyAffiliationPort} 的契约实现：<b>只报原文，不解释</b>——解析与裁定都在 army 侧。
   */
  @Override
  public Map<DecisionMakerId, String> armyClaims(SimulationState state) {
    return claimsOf(state);
  }

  private static Map<DecisionMakerId, String> claimsOf(SimulationState state) {
    Objects.requireNonNull(state, "state");
    GaState ga = ToolSupport.gaState(state);
    Map<DecisionMakerId, String> claims = new LinkedHashMap<>();
    for (DecisionMaker maker : ga.decisionMakers().values()) {
      if (maker.affiliation() instanceof Affiliation.Army army) {
        claims.put(maker.id(), army.armyId());
      }
    }
    return Map.copyOf(claims);
  }
}
