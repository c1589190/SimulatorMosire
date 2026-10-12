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
 * {@code army.SetArmyMasterGov} 窄工具（阶段 12 后续赋值缺口）：**已存在 Army 的主子改派 / 解除**的唯一窄写面。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ <b>载荷</b>：{@code {armyId, masterGovUnitId?}}。{@code masterGovUnitId} 缺席/null/空串 =
 * 解除认领；给了必须存在且带 {@code GovernmentFormation}。前置（armyId 不存在 / 目标不是 GOV）都在**域层**判、 逐条有可读文案——工具层不重复校验。
 *
 * <p>★ <b>只改 sd 侧</b>：本工具不碰 {@code unit.ArmyFormation}（unit 侧同步走 {@code simos.army.assignGov}
 * 组合工具）； 两者语义独立，命令面不替调用方发明跨域联动。
 */
public final class SdSetArmyMasterGovTool extends AbstractNarrowWriteTool implements SimosTool {

  /**
   * 工具名（★ 全局唯一，且**不随命令类型改名**）：它在工具面/白名单/MCP 覆盖表里是稳定身份，改名会动可观测面。
   *
   * <p>★ 2026-10-12 批次 3d-1：命令类型由 {@code sd.SetArmyMasterGov} 变 {@code
   * army.SetArmyMasterGov}，但**工具名 一字未改**（同 {@link SdCreateArmyTool#NAME} 的口径）。
   */
  public static final String NAME = "sd.SetArmyMasterGov";

  /** 本工具钉死的命令类型（2026-10-12 批次 3d-1 起 {@code army.SetArmyMasterGov}）。 */
  public static final String COMMAND_TYPE = "army.SetArmyMasterGov";

  public SdSetArmyMasterGovTool(ToolWritePort writes, GaToolIdentity identity, String mapId) {
    super(writes, identity, mapId);
  }

  @Override
  protected String commandType() {
    return COMMAND_TYPE;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "改派 army 主子 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "改派/解除 Army 主子 GOV：固定 army.SetArmyMasterGov，载荷 {armyId, masterGovUnitId?}"
        + "（armyId 必填；masterGovUnitId 缺席/null/空串 = 解除认领，给了必须存在且带 GovernmentFormation；"
        + "armyId 不存在 ⇒ 具名拒；只改 army 侧 masterGovUnitId，保留 id/rootUnit/name，不碰 unit 侧 ArmyFormation）";
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
