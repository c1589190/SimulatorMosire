package io.mosire.simos.app.tools.ga;

import io.mosire.simos.app.decision.DecisionCallPreviewer;
import io.mosire.simos.ga.tools.GaProposalHost;
import io.mosire.simos.util.spi.CommandTarget;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link GaProposalHost} 的 app 实现：逐条转调 {@link DecisionCallPreviewer}。
 *
 * <p>端口缩窄为“预览 + 目标提取”，不再回答“能不能入包”；能否入包由 Ask/权限链决定。
 */
public final class AppGaProposalHost implements GaProposalHost {

  private final DecisionCallPreviewer previewer;

  public AppGaProposalHost(DecisionCallPreviewer previewer) {
    this.previewer = Objects.requireNonNull(previewer, "previewer");
  }

  @Override
  public Map<String, Object> preview(
      String toolName, SimulationState state, Map<String, Object> args) {
    return previewer.preview(toolName, state, args);
  }

  @Override
  public List<CommandTarget> targets(
      String toolName,
      SimulationState state,
      Map<String, Object> args,
      Map<String, Object> preview) {
    return previewer.targets(toolName, state, args, preview);
  }
}
