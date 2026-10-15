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
 * {@code army.CreateArmy} 窄工具（M3，spec §八.3）：**建军**的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ 前置（id 已存在 / {@code masterGovUnitId} 不存在或不是 GOV / {@code rootUnitId}
 * 不存在）都在**域层**判、逐条有可读文案——工具层不重复校验（同上）。 旧 {@code nationId} 键在域层具名拒并指路 {@code masterGovUnitId}/{@code
 * unit.SetArmyFormation}。
 */
public final class CreateArmyTool extends AbstractNarrowWriteTool implements SimosTool {

  /**
   * 工具名（★ 全局唯一，且**不随命令类型改名**）：它在工具面/白名单/MCP 覆盖表里是稳定身份，改名会动可观测面。
   *
   * <p>★ 2026-10-12 批次 3d-1：命令类型由 {@code sd.CreateArmy} 变 {@code army.CreateArmy}（军队身份搬进 army），
   * 但**工具名一字未改**——工具名与命令类型在本仓本来就是两个可独立的拼写（见 {@link
   * io.mosire.simos.ga.tools.write.AbstractNarrowWriteTool#toolName()} 的钩子）。
   */
  public static final String NAME = "simos.ga.createArmy";

  /** 本工具钉死的命令类型（2026-10-12 批次 3d-1 起 {@code army.CreateArmy}）。 */
  public static final String COMMAND_TYPE = "army.CreateArmy";

  public CreateArmyTool(ToolWritePort writes, GaToolIdentity identity, String mapId) {
    super(writes, identity, mapId);
  }

  @Override
  protected String commandType() {
    return COMMAND_TYPE;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "建军 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "建军：固定 army.CreateArmy，载荷 {armyId, masterGovUnitId?, rootUnitId, name}"
        + "（armyId/rootUnitId/name 必填；masterGovUnitId 缺省 = 未认主子，给了必须存在且带 GovernmentFormation；"
        + "★ 旧 nationId 键已拒并指路 masterGovUnitId）";
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
