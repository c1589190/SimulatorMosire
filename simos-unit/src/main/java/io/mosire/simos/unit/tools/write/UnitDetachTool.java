package io.mosire.simos.unit.tools.write;

import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolWritePort;
import java.util.Map;
import java.util.Set;

/**
 * {@code unit.DetachUnit} 窄工具（M2，spec §八.3）：**脱离父**的唯一窄写面。
 *
 * <p>★ **进 GM 桶与决策人桶**（用户裁定 D-1）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ **只脱一个节点、不级联**：目标单位的父被清空，**它的后代仍挂在它下面**（整树迁移是 {@code unit.ReparentSubtree}）。已是根单位（父为空）⇒
 * 域层拒「没有可脱离的父」。
 */
public final class UnitDetachTool extends AbstractNarrowWriteTool implements SimosTool {

  public static final String NAME = "unit.DetachUnit";

  public UnitDetachTool(ToolWritePort writes, String mapId) {
    super(writes, mapId);
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

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}
