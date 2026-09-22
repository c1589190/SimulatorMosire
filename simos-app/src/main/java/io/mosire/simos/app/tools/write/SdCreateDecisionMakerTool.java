package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code sd.CreateDecisionMaker} 窄工具（M3，spec §八.3）：**建决策人**的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ {@code allowedTools} 含通用写（{@code simos.command.submit}）即被拒（N9）；★ 创建期 {@code accessLimit}
 * 恒为**无额外限制**、本命令**不接受**该字段——传了会被**静默忽略**（无拒绝）。配权走 {@code sd.SetDecisionMakerAccess}。
 */
public final class SdCreateDecisionMakerTool extends AbstractNarrowWriteTool {

  public static final String NAME = "sd.CreateDecisionMaker";

  public SdCreateDecisionMakerTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "建决策人 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "建决策人：固定 sd.CreateDecisionMaker，载荷 {id, affiliation, allowedTools, cadence}（四者全必填；★ 创建期 accessLimit 恒为无额外限制、本命令不接受该字段，传了会被静默忽略；★ allowedTools 不得含通用写）";
  }
}
