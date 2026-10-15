package io.mosire.simos.ga.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.ga.tools.GaToolIdentity;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code ga.CreateDecisionMaker} 窄工具（M3，spec §八.3）：**建决策人**的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ {@code allowedTools} 含通用写（{@code simos.command.submit}）即被拒（N9）；★ 创建期 {@code accessLimit}
 * 恒为**无额外限制**、本命令**不接受**该字段——传了会被**静默忽略**（无拒绝）。配权走 {@code ga.SetDecisionMakerAccess}。
 */
public final class CreateDecisionMakerTool extends AbstractNarrowWriteTool implements SimosTool {

  public static final String NAME = "ga.CreateDecisionMaker";

  public CreateDecisionMakerTool(ToolWritePort writes, GaToolIdentity identity, String mapId) {
    super(writes, identity, mapId);
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
    return "建决策人：固定 ga.CreateDecisionMaker，载荷 {id, allowedTools, cadence}（三者全必填；★ affiliation 已整条删除、**不再接受**——传了会被具名拒绝，归属改用 gov.AssignDecisionMakerNation / gov.AssignDecisionMakerGov / army.AssignDecisionMakerArmy；★ 创建期 accessLimit 恒为无额外限制、本命令不接受该字段，传了会被静默忽略；★ allowedTools 不得含通用写）";
  }

  @Override
  public ResourceManifest resources() {
    return GA_NAMESPACE_WRITE;
  }

  /**
   * ★ P0 资源对齐：本工具钉死的 {@code sd.*} 命令只产 {@code SdChangeSet}（实际只写 sd 命名空间），故写断言取 {@code sd:*}（GM 侧
   * unlimited），不再沿用基类的三命名空间粗断言。
   */
  @Override
  protected List<ResourceId> writeResources(ToolContext context) {
    return gaNamespaceWriteResources();
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}
