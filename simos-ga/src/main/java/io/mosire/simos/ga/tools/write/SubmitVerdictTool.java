package io.mosire.simos.ga.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.ga.tools.GaToolIdentity;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code ga.SubmitVerdict} 窄工具（spec §八.3，N9）：裁决者冻结判决的**唯一**写面。
 *
 * <p>★ **资源声明 = sd 域的自己那一块**（T10）：交判决与出令同属**决策行为**（spec §2.2 的两条决策窄写）， 不是"直接改地图/单位数据"⇒
 * 不复用基类缺省的三命名空间粗断言（那条会让受限调用者**整调被拒**）。
 */
public final class SubmitVerdictTool extends AbstractNarrowWriteTool implements SimosTool {

  public static final String NAME = "ga.SubmitVerdict";

  /** 本工具只碰 sd 命名空间；缺省策略取 {@code READ_ONLY}（未表态者不得**写**）——理由见 {@code IssueDirectiveTool}。 */
  private static final ResourceManifest SD_WRITE =
      ResourceManifest.of(ToolResources.SD_NAMESPACE, ResourcePolicy.READ_ONLY);

  public SubmitVerdictTool(ToolWritePort writes, GaToolIdentity identity, String mapId) {
    super(writes, identity, mapId);
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
    return "裁决者冻结判决：固定 ga.SubmitVerdict，载荷 {verdictId, breakpoint, subject, payload, meta}";
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

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.DM, ToolRole.GM);
  }
}
