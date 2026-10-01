package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code unit.SetFormationOffset} 窄工具（M2，spec §八.3）：**设编制偏移**的唯一窄写面。
 *
 * <p>★ **进 GM 桶与决策人桶**（用户裁定 D-1）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ 偏移是相对父的位置修正（{@code appliedTo} 走 {@code Math.addExact} ⇒ 溢出被拒，不静默回绕）。
 *
 * <p>★★ **两个分量全缺 = 清除偏移**，**不是**"不动"；**只给一个分量时另一个按 0**（不是"保持原值"）。 这两条都写进 {@link
 * #description()}，让模型在调用前就看得见。
 */
public final class UnitSetFormationOffsetTool extends AbstractNarrowWriteTool {

  public static final String NAME = "unit.SetFormationOffset";

  public UnitSetFormationOffsetTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "设编制偏移 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "★ 已退役：固定 unit.SetFormationOffset，载荷 {id, dq?, dr?}；RelativeOffset 当前无任何消费点"
        + "（移动/编队/战斗都不读），调用会被具名拒、不写状态；字段仅为旧档保留。"
        + "站位调整请用 unit.PlaceAt / unit.PlanRoute / unit.PlanSparseRoute / unit.AttachUnit / unit.DetachUnit /"
        + " unit.ReparentUnit；相对父的站位偏移暂无替代";
  }
}
