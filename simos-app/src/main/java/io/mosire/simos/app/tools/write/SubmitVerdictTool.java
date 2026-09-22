package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import java.util.List;
import java.util.Map;

/**
 * {@code sd.SubmitVerdict} 窄工具（spec §八.3，N9）：裁决者冻结判决的**唯一**写面。
 *
 * <p>★ **资源声明 = sd 域的自己那一块**（T10）：交判决与出令同属**决策行为**（spec §2.2 的两条决策窄写）， 不是"直接改地图/单位数据"⇒
 * 不复用基类缺省的三命名空间粗断言（那条会让受限调用者**整调被拒**）。
 */
public final class SubmitVerdictTool extends AbstractNarrowWriteTool {

  public static final String NAME = "sd.SubmitVerdict";

  /** 本工具只碰 sd 命名空间；缺省策略取 {@code READ_ONLY}（未表态者不得**写**）——理由见 {@code IssueDirectiveTool}。 */
  private static final ResourceManifest SD_WRITE =
      ResourceManifest.of(ToolSupport.SD_NAMESPACE, ResourcePolicy.READ_ONLY);

  public SubmitVerdictTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "提交判决 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "裁决者冻结判决：固定 sd.SubmitVerdict，载荷 {verdictId, breakpoint, subject, payload, meta}";
  }

  @Override
  public ResourceManifest resources() {
    return SD_WRITE;
  }

  /** ★ 写自己的决策域（{@code sd:decision-maker/<自己>}）；非决策人身份（GM）回落基类缺省，见基类 javadoc。 */
  @Override
  protected List<ResourceId> writeResources(ToolContext context) {
    return decisionWriteResources(context);
  }
}
