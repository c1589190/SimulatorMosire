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
 * {@code sd.AddCombatStage} 窄工具（M3，spec §八.3）：**给交战加阶段**的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★★ **{@code combatStateId} / {@code hex} 只在该交战的首个阶段生效**：非首阶段时给了会被**静默忽略**
 * ——不报错、回执也看不出它有没有生效。这是**工具面上唯一的披露点**（见 {@link #description()}）。
 */
public final class SdAddCombatStageTool extends AbstractNarrowWriteTool implements SimosTool {

  public static final String NAME = "sd.AddCombatStage";

  public SdAddCombatStageTool(ToolWritePort writes, GaToolIdentity identity, String mapId) {
    super(writes, identity, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "加战斗阶段 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "加战斗阶段：固定 sd.AddCombatStage，载荷 {combatId, stage, combatStateId?, hex?}（combatId/stage 必填；★ combatStateId 与 hex 只在**该交战的首个阶段**生效——非首阶段时给了（含显式 null）会被**具名拒绝**，不再静默忽略）";
  }

  @Override
  public ResourceManifest resources() {
    return SD_NAMESPACE_WRITE;
  }

  /**
   * ★ P0 资源对齐：本工具钉死的 {@code sd.*} 命令只产 {@code SdChangeSet}（实际只写 sd 命名空间），故写断言取 {@code sd:*}（GM 侧
   * unlimited），不再沿用基类的三命名空间粗断言。
   */
  @Override
  protected List<ResourceId> writeResources(ToolContext context) {
    return sdNamespaceWriteResources();
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}
