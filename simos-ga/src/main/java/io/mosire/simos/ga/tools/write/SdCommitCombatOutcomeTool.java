package io.mosire.simos.ga.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.ga.tools.GaToolIdentity;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code sd.CommitCombatOutcome} 窄工具（M3，spec §八.3）：**给交战选定结局**的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ 结局必须落在**该阶段**的结局表里（只在别的阶段的表里也拒）；且**已选定过就不再覆盖**（N2 恰一个）。
 */
public final class SdCommitCombatOutcomeTool extends AbstractNarrowWriteTool implements SimosTool {

  public static final String NAME = "sd.CommitCombatOutcome";

  public SdCommitCombatOutcomeTool(ToolWritePort writes, GaToolIdentity identity, String mapId) {
    super(writes, identity, mapId);
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

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}
