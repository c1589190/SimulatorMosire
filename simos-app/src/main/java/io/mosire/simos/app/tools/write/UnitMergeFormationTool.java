package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code unit.MergeFormation} 窄工具（M2，spec §八.3）：**合并编制**的唯一窄写面。
 *
 * <p>★ **进 GM 桶与决策人桶**（用户裁定 D-1）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★★ **两个前置条件都写进 {@link #description()}**：① 两者**同格**（任一方的有效位置取不到 ⇒ 也拒）； ② {@code childId}
 * 的状态**必须是 {@code MOVING}**（{@code RESTING}/{@code ENGAGED} 拒）。违反任一条 ⇒ 域层拒，理由逐条可读。
 *
 * <p>★ 成功路径复用「合体」的级联：{@code childId} **及其全部后代**在同一刻追加 {@code attached=true} 段。
 */
public final class UnitMergeFormationTool extends AbstractNarrowWriteTool {

  public static final String NAME = "unit.MergeFormation";

  public UnitMergeFormationTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "合并编制 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "合并编制：固定 unit.MergeFormation，载荷 {childId, parentId}（★ 必须同格且 child 状态为 MOVING）";
  }
}
