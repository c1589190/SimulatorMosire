package io.mosire.simos.ga.tools;

import io.mosire.simos.util.spi.CommandTarget;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Map;

/**
 * ★★ <b>决策包"提议"面的宿主编排口</b>（2026-10-12 B3 第 7 域）：决策包工具要问宿主的一件事——"这条调用的真预览与可寻址目标是什么"。
 *
 * <p>★ <b>为什么是端口</b>：真预览与目标提取的唯一实现是 app 的 {@code DecisionCallPreviewer} （设计书 §7.3 由 {@code
 * ProposalCatalog} 收窄而来；S2 负责改 app）。它持有域工具实例并读世界 ⇒ 是装配， 按 I-G2 只能住 app。<b>接口在 ga、实现在
 * app</b>：实现只做预览/目标提取，不回答“能否入包”。
 *
 * <p>★ <b>为什么 {@code preview}/{@code targets} 合成一对方法而不是一条</b>：语义就是“先真预览、再从参数 ∪ 预览里提目标”，两处共用同一份
 * preview 值；压成一条会丢掉“预览给的目标与记录里存的是同一份”这条可对照性。
 */
public interface GaProposalHost {

  /** 真预览（系统上下文、{@code preview=true}、不落盘）。 */
  Map<String, Object> preview(String toolName, SimulationState state, Map<String, Object> args);

  /** 从参数 ∪ 预览里提取可寻址目标（保序）。 */
  List<CommandTarget> targets(
      String toolName,
      SimulationState state,
      Map<String, Object> args,
      Map<String, Object> preview);
}
