package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code unit.SplitFormation} 窄工具（M2，spec §八.3）：**拆分编制**的唯一窄写面。
 *
 * <p>★ **进 GM 桶与决策人桶**（用户裁定 D-1）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ {@code subUnitIds} **不得为空**：至少指名一个目标，`[]` 会被域层拒（理由带**可执行的下一步**）。被指名的 子单位从 {@code rootId}
 * 的编制里脱出（脱离一个节点、不级联，与 {@code unit.DetachUnit} 同形）。
 */
public final class UnitSplitFormationTool extends AbstractNarrowWriteTool {

  public static final String NAME = "unit.SplitFormation";

  public UnitSplitFormationTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "拆分编制 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "拆分编制：固定 unit.SplitFormation，载荷 {rootId, subUnitIds[]（不得为空）}";
  }
}
