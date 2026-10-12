package io.mosire.simos.sd.spi;

import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;

/**
 * ★★ <b>跨切片只读口：ga 侧 {@code directives} 表在 sd 侧的<b>唯一</b>读取窗</b>（2026-10-12 批次 3b′：两张决策表搬进 {@code
 * simos-ga}）。
 *
 * <p>★★ <b>为什么需要它</b>：{@code directives} 表随批次 3b′ 搬进 {@code simos-ga}（{@code GaState} 的第三个组件）， 而 sd
 * 侧仍有<b>两处</b>读它：{@code SdResolver} 的 {@code sd:directive.<id>} 存在性判据、{@code
 * DeleteDecisionMakerHandler} 的"仍被历史 Directive 引用 ⇒ 具名拒绝"守卫。{@code simos-sd} <b>不得</b>依赖 {@code
 * simos-ga}（那会与既有的 {@code ga → sd} 成环，模块边界 enforcer 建图期直接拒）⇒ 读口由<b>装配注入</b>： 接口在 sd（本类，消费方一侧）、实现在
 * ga（{@code GaDirectiveLookup}，{@code ga → sd} 是既有方向）、组合根 {@code Shell} 接线。形制与 {@code
 * DecisionToolCaller} / {@code DecisionToolNames} / {@code ToolImages} 同款（"接口在消费方、 实现在另一侧、装配期注入"）。
 *
 * <p>★★ <b>只读，且不 cache</b>（铁律 3）：实现方每调用一次就从传入的 {@link SimulationState} 取一次切片，不得持有状态、 不得改写任何切片。切片缺席
 * / 类型不对 = <b>装配故障</b>，实现方当场抛（不折成 {@code false}——那会把"装配错了" 静默降级成"这条令不存在"）。
 *
 * <p>★ <b>地址族不变</b>：本口只回答"表里有没有这条令 / 谁引用了这个决策人"，地址族串（{@code sd:directive.<id>}） 的裁量属批次 3e，本批一字不改。
 */
public interface DirectiveLookup {

  /**
   * {@code directives} 表里是否存在该 id。
   *
   * @param state 当前状态（实现方从中取 ga 切片）
   * @param id 令 id（非 null）
   * @return 存在 ⇒ true
   */
  boolean contains(SimulationState state, DirectiveId id);

  /**
   * 仍引用该决策人的令 id，<b>按 id 自然序</b>（确定性；调用方据此计条数与点名）。
   *
   * @param state 当前状态（实现方从中取 ga 切片）
   * @param id 决策人 id（非 null）
   * @return 不可变列表；无引用 ⇒ 空列表
   */
  List<DirectiveId> referencing(SimulationState state, DecisionMakerId id);
}
