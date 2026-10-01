package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.core.CoreSimos;
import java.util.List;
import java.util.Map;

/**
 * {@code sd.SetStageOutcomeTable} 窄工具（M3，spec §八.3）：**设某阶段的结局表**的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ 结局表是**整体替换**；空表或权重 ≤0 被域层拒（N2），理由原文到达调用方。
 */
public final class SdSetStageOutcomeTableTool extends AbstractNarrowWriteTool {

  public static final String NAME = "sd.SetStageOutcomeTable";

  public SdSetStageOutcomeTableTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "设阶段结局表 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "设阶段结局表：固定 sd.SetStageOutcomeTable，载荷 {combatId, stageId, outcomes}";
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
