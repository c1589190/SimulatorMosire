package io.mosire.simos.ga.tools;

import io.mosire.simos.util.spi.CommandTarget;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Map;

/**
 * ★★ <b>决策包"提议"面的宿主编排口</b>（2026-10-12 B3 第 7 域）：决策包工具（propose / 合并计划校验 / 执行）要问宿主的两件事——
 * "这个工具名在不在可提议清单里"与"这条提议的真预览与可寻址目标是什么"。
 *
 * <p>★ <b>为什么是端口</b>：那两件事的唯一实现是 app 的 {@code ProposalCatalog}（{@code
 * DecisionEffectExecutor.ExecutionResult} / {@code ProposalCatalog} 按设计书 §4.5 判据**留
 * app**：它**持有域工具实例**并**读世界** ⇒ 是装配），而它要吃全部 16 个模块的工具 实例 ⇒ 按 I-G2 只能住 app。<b>接口在 ga、实现在
 * app</b>：实现逐条转调 {@code ProposalCatalog}，**不重写**预览语义。
 *
 * <p>★ <b>为什么 {@code preview}/{@code targets} 在 ga 侧合成一对方法而不是一条</b>：既有语义就是"先真预览、再从参数 ∪
 * 预览里提目标"（{@code ProposalCatalog#targets} 的第二实参吃前一步的预览结果，两处共用同一份 preview 值）——把它压成一条会把
 * 既有两次调用折成一次，语义上等价但会丢掉"预览给的目标与提议记录里存的是同一份"这条可对照性。
 */
public interface GaProposalHost {

  /** 该工具名是否在可提议清单里（{@code ProposalCatalog.contains}）。 */
  boolean contains(String toolName);

  /** 真预览（系统上下文、{@code preview=true}、不落盘）。 */
  Map<String, Object> preview(String toolName, SimulationState state, Map<String, Object> args);

  /** 从参数 ∪ 预览里提取可寻址目标（保序）。 */
  List<CommandTarget> targets(
      String toolName,
      SimulationState state,
      Map<String, Object> args,
      Map<String, Object> preview);
}
