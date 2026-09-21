package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code unit.SetStatus} 窄工具（M2，spec §八.3）：**设定单位状态**的唯一窄写面。
 *
 * <p>★ **进 GM 桶与决策人桶**（用户裁定 D-1）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ 状态是**三态**（{@code MOVING} / {@code RESTING} / {@code ENGAGED}），词表外的串在载荷层就被拒（`字段 status
 * 不是合法状态: …`），不落到域层——两者经同一条 {@code ToolSupport.fold} 折成可读的 {@code REJECTED}。
 *
 * <p>★ 改状态**只影响此后新下达的路线**，在途路线的速度在出发时已冻结、不回溯。
 */
public final class UnitSetStatusTool extends AbstractNarrowWriteTool {

  public static final String NAME = "unit.SetStatus";

  public UnitSetStatusTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "设定单位状态 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "设定单位状态：固定 unit.SetStatus，载荷 {id, status（MOVING|RESTING|ENGAGED）}";
  }
}
