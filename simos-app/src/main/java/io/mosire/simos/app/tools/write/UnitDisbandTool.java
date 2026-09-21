package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code unit.DisbandUnit} 窄工具（M2，spec §八.3）：**解散单位**的唯一窄写面。
 *
 * <p>★ **进 GM 桶与决策人桶**（用户裁定 D-1）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★★ **两条守卫只覆盖"链"与"下属"**：单位仍是某条链的 commander/成员、或在此时刻仍有下属 ⇒ 域层拒（理由带"先改链、再解散"这类 **可执行的下一步**）。 ★
 * **悬空的回归目标不在守卫内**：本命令既不检查也不清理它（运行期兜住：目标不存在则不回归、不写任何东西）⇒ {@link #description()} 里**不得**声称它会拒绝这一项。
 */
public final class UnitDisbandTool extends AbstractNarrowWriteTool {

  public static final String NAME = "unit.DisbandUnit";

  public UnitDisbandTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "解散单位 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "解散单位：固定 unit.DisbandUnit，载荷 {id}（仍是链的 commander/成员、或仍有下属时会被拒；★ 不检查悬空的回归目标）";
  }
}
