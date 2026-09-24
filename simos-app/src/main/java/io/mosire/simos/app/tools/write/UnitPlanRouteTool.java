package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code unit.PlanRoute} 窄工具（M2，spec §八.3）：**下达路线**的唯一窄写面。
 *
 * <p>★ **进 GM 桶与决策人桶**（用户裁定 D-1）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ 载荷的 {@code waypoints} **同时当作逐格 path**：本命令要求点列本身是相邻的简单路径（稀疏路线的逐段展开是另一条命令 {@code
 * unit.PlanSparseRoute}）。起点必须等于单位在该时刻的有效位置，否则域层拒。
 */
public final class UnitPlanRouteTool extends AbstractNarrowWriteTool {

  public static final String NAME = "unit.PlanRoute";

  public UnitPlanRouteTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "下达路线 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "下达路线：固定 unit.PlanRoute，载荷 {id, waypoints[{q,r}…]}（至少两个路径点；"
        + "★ 首点必须是该单位**当前所在格**，写成邻格会被拒）。"
        + "★★ 只有编制**顶层**能下路线：与别人一同移动的成员会被拒（理由点名顶层）——"
        + "要么改对顶层下令（整支一起跟着走），要么先用 unit.SplitFormation / unit.DetachUnit 把它拆成独立单位";
  }
}
