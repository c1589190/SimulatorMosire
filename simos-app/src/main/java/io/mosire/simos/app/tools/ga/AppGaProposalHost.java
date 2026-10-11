package io.mosire.simos.app.tools.ga;

import io.mosire.simos.app.decision.ProposalCatalog;
import io.mosire.simos.ga.tools.GaProposalHost;
import io.mosire.simos.util.spi.CommandTarget;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Map;

/**
 * ★★ <b>{@link GaProposalHost} 的 app 实现</b>（2026-10-12 B3 第七域）：**逐条转调** {@link ProposalCatalog}
 * ——真预览、目标提取、清单判定三件事的唯一实现在它那里（它持有全部域的工具实例 ⇒ 是装配，按设计书 §4.5 判据留 app）。
 */
public final class AppGaProposalHost implements GaProposalHost {

  private final ProposalCatalog catalog;

  public AppGaProposalHost(ProposalCatalog catalog) {
    this.catalog = java.util.Objects.requireNonNull(catalog, "catalog");
  }

  @Override
  public boolean contains(String toolName) {
    return catalog.contains(toolName);
  }

  @Override
  public Map<String, Object> preview(
      String toolName, SimulationState state, Map<String, Object> args) {
    return catalog.preview(toolName, state, args);
  }

  @Override
  public List<CommandTarget> targets(
      String toolName,
      SimulationState state,
      Map<String, Object> args,
      Map<String, Object> preview) {
    return catalog.targets(toolName, state, args, preview);
  }
}
