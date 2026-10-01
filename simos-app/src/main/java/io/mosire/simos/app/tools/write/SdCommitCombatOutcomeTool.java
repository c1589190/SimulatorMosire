package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.core.CoreSimos;
import java.util.List;
import java.util.Map;

/**
 * {@code sd.CommitCombatOutcome} 窄工具（M3，spec §八.3）：**给交战选定结局**的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ 结局必须落在**该阶段**的结局表里（只在别的阶段的表里也拒）；且**已选定过就不再覆盖**（N2 恰一个）。
 */
public final class SdCommitCombatOutcomeTool extends AbstractNarrowWriteTool {

  public static final String NAME = "sd.CommitCombatOutcome";

  public SdCommitCombatOutcomeTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "定结局 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "定结局：固定 sd.CommitCombatOutcome，载荷 {combatId, stageId, selectedOutcomeId}（三者全必填；★ 结局必须在该阶段的 outcomeTable 里，且**已选定过就不再覆盖**）";
  }

  @Override
  public ResourceManifest resources() {
    return SD_NAMESPACE_WRITE;
  }

  /**
   * ★ P0 资源对齐：本工具钉死的 {@code sd.*} 命令只产 {@code SdChangeSet}（实际只写 sd 命名空间），故写断言取 {@code sd:*}（GM 侧
   * unlimited），不再沿用基类的三命名空间粗断言。
   */
  @Override
  protected List<ResourceId> writeResources(ToolContext context) {
    return sdNamespaceWriteResources();
  }
}
