package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code unit.CancelRoute} 窄工具（M2，spec §八.3）：**取消路线**的唯一窄写面。
 *
 * <p>★ **进 GM 桶与决策人桶**（用户裁定 D-1）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ 单位不存在 ⇒ 域层拒（`单位不存在: <id>`）；单位本来就没有在途路线时**不拒**（本操作面不判"无变化的命令"）。
 */
public final class UnitCancelRouteTool extends AbstractNarrowWriteTool {

  public static final String NAME = "unit.CancelRoute";

  public UnitCancelRouteTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return "unit.PlaceAt";
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "取消路线 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "取消路线：固定 unit.CancelRoute，载荷 {id}";
  }
}
