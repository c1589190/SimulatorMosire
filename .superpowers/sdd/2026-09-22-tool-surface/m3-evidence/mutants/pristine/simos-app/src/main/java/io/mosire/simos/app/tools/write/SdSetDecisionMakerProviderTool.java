package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code sd.SetDecisionMakerProvider} 窄工具（M3）：**给决策人绑定 LLM provider 引用**的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**（故 {@code EXTERNAL_WITH_GM} 复合口也含它）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★★ **本命令只校验 {@code providerId} 非空白，不校验 provider 是否存在**：存在性由**使用时刻**的解析强制， 解析不到即
 * fail-closed，**绝不静默兜底**。⇒ 工具层**有意不加**存在性校验：那份校验能被 {@code simos.command.submit}
 * 绕过（＝装饰），而「从工具面看不出写错了」这件事已写进 {@link #description()}。
 */
public final class SdSetDecisionMakerProviderTool extends AbstractNarrowWriteTool {

  public static final String NAME = "sd.SetDecisionMakerProvider";

  public SdSetDecisionMakerProviderTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "配 provider branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "配 provider：固定 sd.SetDecisionMakerProvider，载荷 {decisionMakerId, providerId}（两者全必填；★★ 本命令**只校验 providerId 非空白，不校验 provider 是否存在**——存在性在**使用时刻**解析，解析不到即 fail-closed，绝不静默兜底）";
  }
}
