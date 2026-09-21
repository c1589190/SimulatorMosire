package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code unit.AttachUnit} 窄工具（M2，spec §八.3）：**合体（重新挂到父）**的唯一窄写面。
 *
 * <p>★ **进 GM 桶与决策人桶**（用户裁定 D-1）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ **级联**：目标单位**及其全部后代**在同一刻追加 {@code attached=true} 段；只有目标换父，后代的父不动（整树迁移是 {@code
 * unit.ReparentSubtree}）。{@code parent} 落在自己子树内（含自身）⇒ 域层拒「会成环」。
 *
 * <p>★ **不判"已是父"**：重挂同一父正是"合体"的语义（拆→合往返靠它），故这条**有意不拒**。
 */
public final class UnitAttachTool extends AbstractNarrowWriteTool {

  public static final String NAME = "unit.AttachUnit";

  public UnitAttachTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "合体 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "合体（同格前提下重新挂到父）：固定 unit.AttachUnit，载荷 {id, parent?}";
  }
}
