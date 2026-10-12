package io.mosire.simos.sd.spi;

import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Map;

/**
 * ★★ <b>跨切片读写口：ga 侧决策表（{@code directives} / {@code decisionMakers} / {@code info}）在 sd
 * 侧的<b>唯一</b>窗口</b> （2026-10-12 批次 3b-2 自 {@code DirectiveLookup} 扩宽而来）。
 *
 * <p>★★ <b>为什么需要它</b>：{@code directives}（3b-1）、{@code decisionMakers} 与 {@code info}（3b-2）三张表搬进
 * {@code simos-ga}（{@code GaState} 的第 3/5/6 个组件），而 sd 侧仍有四处要碰它们：{@code SdResolver} 的 {@code
 * sd:directive.<id>} 与 {@code sd:decision-maker.<id>} 存在性判据、{@code sd.DeleteNation} 的"仍有决策人挂在这个 国家上
 * ⇒ 具名拒"守卫、{@code SdTimeParticipant} 的 {@code Action.PutInfo}（延期效果写 INFO 覆盖层）。{@code simos-sd}
 * <b>不得</b>依赖 {@code simos-ga}（那会与既有的 {@code ga → sd} 成环，模块边界 enforcer 建图期直接拒）⇒ 读写口由
 * <b>装配注入</b>：接口在 sd（本类，消费方一侧）、实现在 ga（{@code GaDecisionTablesLookup}，{@code ga → sd} 是既有方向）、 组合根
 * {@code Shell} 接线。形制与 {@code DecisionToolCaller} / {@code ToolImages} 同款（"接口在消费方、实现在另一侧、装配期注入"）。
 *
 * <p>★★ <b>只读三件 + 一个"整表替换 info"的写口</b>：
 *
 * <ul>
 *   <li>三个只读口每调用一次就从传入的 {@link SimulationState} 取一次切片，<b>不 cache、不改写</b>（铁律 3）；
 *   <li>{@link #replaceInfo} 是<b>唯一</b>的写口，且它<b>返回不透明的 {@link ChangeSet}</b>——sd 侧据此把"这次时间推进要写的
 *       INFO 覆盖层"装进 {@link io.mosire.simos.util.spi.WorldTimeProposal} 的 {@code "ga"} 键（sd 不认识
 *       {@code GaChangeSet}，也不需要认识：{@code ChangeSet} 是 util 的接口）。切片缺席 / 类型不对 = <b>装配故障</b>，实现方当场抛
 *       （不折成 {@code false}——那会把"装配错了"静默降级成"这条令不存在"）。
 * </ul>
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

  /**
   * ga 侧 {@code info} 覆盖层的只读视图（保序不可变；键 = canonical 地址串，值 = 该地址下的条目列表）。
   *
   * <p>★ 与 {@link #decisionMakers} 同一条纪律：返回那一份表，调用方只读。
   */
  Map<String, List<SdInfoEntry>> info(SimulationState state);

  /**
   * 用算好的整张 {@code info} 表<b>替换</b> ga 切片里的 {@code info} 组件，返回 ga 命名空间的变更集（<b>不透明</b>）。
   *
   * <p>★★ <b>为什么是"整表替换"而不是"逐条追加"</b>：{@code SdTimeParticipant} 的 {@code Action.PutInfo} 本就在循环里维护
   * "本日为止的整张 info 表"（条目序号 {@code <地址>#<序号>} 由追加前的 {@code entries.size()} 注入）。把整张表交出来，语义与
   * 搬迁前<b>逐字相同</b>；换成增量 API 反而要在两个模块里各维护一次序号。
   *
   * @param state 当前状态（实现方从它的 ga 切片出发算差异）
   * @param info 新的整张 info 表（非 null；与 {@link #info} 的返回同形状）
   * @return ga 命名空间的变更集（调用方原样装进多切片提案；非 null）
   */
  ChangeSet replaceInfo(SimulationState state, Map<String, List<SdInfoEntry>> info);
}
