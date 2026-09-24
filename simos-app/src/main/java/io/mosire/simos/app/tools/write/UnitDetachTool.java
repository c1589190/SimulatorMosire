package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code unit.DetachUnit} 窄工具（M2，spec §八.3）：**脱离父**的唯一窄写面。
 *
 * <p>★ **进 GM 桶与决策人桶**（用户裁定 D-1）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ **只脱一个节点、不级联**：目标单位的父被清空，**它的后代仍挂在它下面**（整树迁移是 {@code unit.ReparentSubtree}）。已是根单位（父为空）⇒
 * 域层拒「没有可脱离的父」。
 */
public final class UnitDetachTool extends AbstractNarrowWriteTool {

  public static final String NAME = "unit.DetachUnit";

  public UnitDetachTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "脱离父 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "脱离父：固定 unit.DetachUnit，载荷 {id}（没有父的单位会被拒——它本来就是顶层）。"
        + "脱离后 id 成为**独立单位**，可以自己下路线（它的下挂仍跟着它走）";
  }
}
