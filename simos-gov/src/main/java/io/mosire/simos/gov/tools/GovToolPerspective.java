package io.mosire.simos.gov.tools;

import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Optional;

/**
 * 政府工具面的**视角口**：调用者身份派生、权限围栏、以及 gov 够不着的两块事实（sd 决策人 / 组合根的解析规则）。
 *
 * <p>★★ <b>权限围栏不抄第二份</b>（AGENT.md §三.3）：可见性谓词的唯一实现在 app 的 {@code ToolSupport}；本端口只声明、app 转调。
 *
 * <p>★★ <b>为什么 sd 的读数要经端口</b>：{@code simos-sd} 是 gov 的**下游**（enforcer 禁列、且 gov→sd 会让 sd→gov 成环），
 * 而政府工具的"我只能操作自己所属 GOV"这条身份派生判据必须读 sd 的决策人表（{@code Affiliation.Gov}）⇒ 由 app 转调。 ★ 返回的记录带 {@code
 * affiliationText}（{@code affiliation().toString()} 的原样文本）：既有拒因/告警文案里嵌了它， 换一份渲染就会改掉对外文本（I-G1）。
 */
public interface GovToolPerspective {

  /** 从调用者身份取决策人 id（{@code decision-maker:<id>}）；非决策人 ⇒ {@link Optional#empty()}。 */
  Optional<String> decisionMakerIdOf(AgentIdentity identity);

  /**
   * sd 切片里的一个决策人事实。
   *
   * @param id 决策人 id
   * @param known 该 id 是不是本世界已知的决策人
   * @param gov 归属是不是 {@code Affiliation.Gov}
   * @param govUnitId {@code gov} 为真时的 GOV 单位 id（裸值）；否则 {@code null}
   * @param affiliationText {@code affiliation().toString()} 的原样文本（{@code known} 为假时 = 空串）
   */
  record GovDecisionMaker(
      String id, boolean known, boolean gov, String govUnitId, String affiliationText) {}

  /**
   * 查一个决策人（{@code known=false} = 表里没有）。
   *
   * @throws IllegalArgumentException id 形状非法（与 {@code util.spi.DecisionMakerId} 的构造同一条拒因与文案）
   */
  GovDecisionMaker decisionMaker(SimulationState state, String decisionMakerId);

  /** 全部决策人（**迭代序 = sd 状态表自身的序**；调用方要稳定序就自己按 id 排——既有调用点都排了）。 */
  List<GovDecisionMaker> decisionMakers(SimulationState state);

  /**
   * 该 GOV 单位的国库家户（{@code GovernmentHouseholdResolver.requireGovernmentHousehold} 的唯一实现）。
   *
   * @throws IllegalArgumentException 单位没有国库家户（既有文案逐字不变）
   */
  HouseholdId governmentHousehold(Unit unit, String govUnitId);

  /** 逃亡率的档位（{@code GovernmentServiceDesertionBridge.rateTier} 的唯一实现；整数档位，不是文本）。 */
  int rateTier(long fleeRatePerMille);

  /** 区域可见性围栏（{@code ToolSupport.regionVisible} 的唯一实现）。 */
  boolean regionVisible(ToolContext context, String mapId, RegionId regionId);

  /** 该 Region tag 是不是"国家"tag（{@code NationTag.isNationTag} 的唯一实现；前缀字面量只在 sd 侧）。 */
  boolean nationTag(String tag);

  /**
   * 引用该决策人的 Directive id 表（**按 id 字典序**；sd 的 {@code directives} 只读投影）。
   *
   * <p>★ 排序在实现里做死：既有告警文案按 id 字典序列样本，调用方不得再排一次（会与文案的口径分叉）。
   */
  java.util.List<String> directiveIdsReferencing(SimulationState state, String decisionMakerId);
}
