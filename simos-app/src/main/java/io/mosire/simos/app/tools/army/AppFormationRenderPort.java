package io.mosire.simos.app.tools.army;

import io.mosire.simos.army.render.FormationImageRenderer;
import io.mosire.simos.army.tools.FormationRenderPort;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * {@link FormationRenderPort} 的宿主实现：渲染委托 {@link FormationImageRenderer}，PNG 写面只暴露内容寻址的窄函数 {@code
 * putPng}（不把 ArtifactStore 的其余写面交给工具）。
 */
public final class AppFormationRenderPort implements FormationRenderPort {

  private final FormationImageRenderer renderer;

  public AppFormationRenderPort(Function<byte[], String> putPng) {
    this.renderer = new FormationImageRenderer(Objects.requireNonNull(putPng, "putPng"));
  }

  @Override
  public Rendered render(
      BranchId branch, RevisionId revision, String armyId, String armyName, List<Node> nodes) {
    // branch/revision 已由工具在同一个读坐标取出树；渲染只画这份完整节点集合，不另行读状态（避免图与 JSON 不同 revision）。
    return renderer.render(armyId, armyName, nodes);
  }
}
