package io.mosire.simos.ga.tools;

import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.simos.util.spi.CommandTarget;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Optional;

/**
 * ★★ <b>ga 工具面的"调用者是谁"口</b>（2026-10-12 B3 第 7 域）：身份派生 + 自指资源前缀 + **越权围栏**三件事的唯一入口。
 *
 * <p>★ <b>为什么必须是端口</b>：三件事的既有唯一实现都在 app 的 {@code DecisionCallerFactory} / {@code
 * DecisionScopeFunctions}——身份派生（{@code decisionMakerIdOf}）、自指域路径（{@code sd:decision-maker/<自己>} /
 * {@code sd:decision-packet/<自己>}）、以及**范围函数现算 ∩ GM 额外限制**的可见范围判据；它们要吃 {@code PlanCatalog} / {@code
 * GameMap} / {@code map} 的格与区（本模块看不见）⇒ 按 I-G2 只能住 app。
 *
 * <p>★ <b>围栏不抄第二份</b>：{@link #scopeViolation} 的实现必须**逐条转调** {@code
 * DecisionCallerFactory.resourceScopesFor(...)}（"目标越界 ⇒ 具名拒因"的判据只在那一处）；本模块只负责把"调用者 + 世界 + 目标清单"喂进去。
 *
 * <p>★ <b>kind 字面量不抄第二份</b>：{@code decision-maker} / {@code decision-packet} 的拼写点是 app 的 {@code
 * DecisionCallerFactory}（其常量本来就是唯一出处），故这里以方法暴露而不是让每个工具各写一个字面量。
 */
public interface GaToolIdentity {

  /**
   * 从**宿主已知的身份**解出决策人 id（**裸值形态**，与 app 侧 {@code DecisionCallerFactory.decisionMakerIdOf}
   * 逐字同形；不由载荷自报）。
   *
   * @return 不是决策人身份 ⇒ 空（调用方按各自口径折成 {@code BAD_REQUEST} / 回落 GM 分支）
   */
  Optional<String> decisionMakerIdOf(AgentIdentity identity);

  /** 决策人自指域的 kind（{@code sd:decision-maker/<id>} 里的 {@code decision-maker}）。 */
  String decisionMakerKind();

  /** 决策包自指域的 kind（{@code sd:decision-packet/<id>} 里的 {@code decision-packet}）。 */
  String decisionPacketKind();

  /**
   * 逐条目标按**调用者现算范围**判越界；返回第一条越界的具名拒因。
   *
   * @param actor 调用者决策人
   * @param state 目标坐标上的完整状态（范围函数要用它算可见集）
   * @param targets 本次提议要触达的目标（{@code ProposalCatalog.targets(...)} 的产物）
   * @param mapId 地图称谓（资源路径要用它）
   * @return 无越界 ⇒ 空
   */
  Optional<String> scopeViolation(
      DecisionMakerId actor, SimulationState state, List<CommandTarget> targets, String mapId);
}
