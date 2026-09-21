package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code unit.PlanSparseRoute} 窄工具（M2，spec §八.3）：**下达稀疏路线**的唯一窄写面。
 *
 * <p>★ **进 GM 桶与决策人桶**（用户裁定 D-1）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ 与 {@code unit.PlanRoute} 的差别在**载荷语义**：这里 {@code waypoints} 是**稀疏路点**，相邻两点之间由域层
 * 逐段寻路展开（成本模型由装配注入，不写死）。**任一段不可达即整条被拒**，不是跳过该段——拒绝发生在**命令期**， 不留下半条路线。
 */
public final class UnitPlanSparseRouteTool extends AbstractNarrowWriteTool {

  public static final String NAME = "unit.PlanSparseRoute";

  public UnitPlanSparseRouteTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "下达稀疏路线 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "下达稀疏路线：固定 unit.PlanSparseRoute，载荷 {id, waypoints[{q,r}…]}（逐段展开；任一段不可达即整条被拒）";
  }
}
