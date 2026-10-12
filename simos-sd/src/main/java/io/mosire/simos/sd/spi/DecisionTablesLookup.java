package io.mosire.simos.sd.spi;

import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.SimulationState;
import java.util.Map;

/**
 * ★★ <b>跨切片只读口：ga 侧决策表（{@code directives} / {@code decisionMakers}）在 sd 侧的<b>唯一</b>窗口</b>
 * （2026-10-12 批次 3b-2 自 {@code DirectiveLookup} 扩宽而来）。
 *
 * <p>★★ <b>为什么需要它</b>：{@code directives}（3b-1）与 {@code decisionMakers}（3b-2）两张表搬进 {@code simos-ga}
 * （{@code GaState} 的组件），而 {@code SdResolver} 仍要判 {@code sd:directive.<id>} 与 {@code
 * sd:decision-maker.<id>} 的存在性。{@code simos-sd} <b>不得</b>依赖 {@code simos-ga}（那会与既有的 {@code ga → sd}
 * 成环，模块边界 enforcer 建图期直接拒）⇒ 读写口由 <b>装配注入</b>：接口在 sd（本类，消费方一侧）、实现在 ga（{@code
 * GaDecisionTablesLookup}，{@code ga → sd} 是既有方向）、组合根 {@code Shell} 接线。形制与 {@code
 * DecisionToolCaller} / {@code ToolImages} 同款（"接口在消费方、实现在另一侧、装配期注入"）。
 *
 * <p>★★ <b>2026-10-12 批次 3b-4：本口由"三读一写"收成"两读"（如实记）</b>——批次 3b-2 还带着 {@code info(SimulationState)} 与
 * {@code replaceInfo(SimulationState, Map)}（{@code SdTimeParticipant} 的 {@code Action.PutInfo}
 * 要在<b>另一个模块</b>的 {@code info} 表上落地）；本批 {@code effects} 与 {@code info} 同住 {@code
 * GaState}、推进器（{@code GaTimeParticipant}）也搬进 ga ⇒ 那条写路径改走 {@code GaState.withInfo(...)}（同一个变更集里），
 * 这两个方法<b>失去全部调用者</b> ⇒ 同批整条删除（不留"看着能注入、其实无人注入"的接缝）。★ 今天的使用者只有 {@code SdResolver}。
 *
 * <p>★ <b>地址族不变</b>：本口只回答"表里有没有 / 表里是什么"，地址族串（{@code sd:directive.<id>} / {@code
 * sd:decision-maker.<id>}）的裁量属批次 3e，本批一字不改。
 */
public interface DecisionTablesLookup {

  /**
   * {@code directives} 表里是否存在该 id。
   *
   * @param state 当前状态（实现方从中取 ga 切片）
   * @param id 令 id（非 null）
   * @return 存在 ⇒ true
   */
  boolean containsDirective(SimulationState state, DirectiveId id);

  /**
   * ga 侧 {@code decisionMakers} 表的只读视图（保序不可变，键 == 值内 id）。
   *
   * <p>★ 返回的是<b>那一份</b>表（不是副本）：调用方只读；要改必须走 {@code ga.*} 命令。
   */
  Map<DecisionMakerId, DecisionMaker> decisionMakers(SimulationState state);
}
