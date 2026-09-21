package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
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
}
