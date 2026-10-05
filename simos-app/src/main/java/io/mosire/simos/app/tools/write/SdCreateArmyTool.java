package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.core.CoreSimos;
import java.util.List;
import java.util.Map;

/**
 * {@code sd.CreateArmy} 窄工具（M3，spec §八.3）：**建军**的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ 前置（id 已存在 / {@code masterGovUnitId} 不存在或不是 GOV / {@code rootUnitId}
 * 不存在）都在**域层**判、逐条有可读文案——工具层不重复校验（同上）。 旧 {@code nationId} 键在域层具名拒并指路 {@code masterGovUnitId}/{@code
 * unit.SetArmyFormation}。
 */
public final class SdCreateArmyTool extends AbstractNarrowWriteTool {

  public static final String NAME = "sd.CreateArmy";

  public SdCreateArmyTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "建军 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "建军：固定 sd.CreateArmy，载荷 {armyId, masterGovUnitId?, rootUnitId, name}"
        + "（armyId/rootUnitId/name 必填；masterGovUnitId 缺省 = 未认主子，给了必须存在且带 GovernmentFormation；"
        + "★ 旧 nationId 键已拒并指路 masterGovUnitId）";
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
}
