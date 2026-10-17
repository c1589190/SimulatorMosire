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
   * ★★ <b>本类的真实工具名由 {@link #commandType()} 给</b>（= {@link #COMMAND_TYPE} = {@code
   * army.CreateArmy}）：本类**不覆写** {@code toolName()} ⇒ 基类缺省实现返回命令类型 —— 那就是工具面 / 两桶名单 / 白名单 / MCP
   * 覆盖表里逐字可见的那个名字。
   *
   * <p>★★ 2026-10-15 批 N ①：此处原有的 {@code public static final String NAME = "simos.ga.createArmy"}
   * 已**删除** —— 它与真实 工具名不一致（批 L 只把字面量由 {@code sd.CreateArmy} 换成另一个错名 {@code
   * simos.ga.createArmy}），而全仓 {@code src/main} / {@code src/test} / JS 对它**零符号引用**（无 javadoc 链接、无
   * {@code import static}、无反射）⇒ 它不撒谎的唯一形态是 "不存在"。★ 不改成"覆写 {@code toolName()} 让那个常量成真"：那会改工具面身份 /
   * 两桶名单 / 白名单（超出批 N）。
   */
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
   * ★ P0 资源对齐（2026-10-15 批 N2 回代码核，原文写 {@code sd.*} / {@code SdChangeSet} / {@code sd:*} 三处均与事实不符）：
   * 本工具钉死的 {@code army.CreateArmy} 命令产 {@code ArmyChangeSet}（{@code CreateArmyHandler}），实际只写 ga
   * 命名空间（{@code ToolResources.GA_NAMESPACE = "ga"}，2026-10-14 批次 F 由 {@code sd} 改名）⇒ 写断言取 {@code
   * ga:*}（{@link #gaNamespaceWriteResources()}，GM 侧 ga 是 unlimited），不再沿用基类的三命名空间粗断言。
   */
  @Override
  protected List<ResourceId> writeResources(ToolContext context) {
    return gaNamespaceWriteResources();
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }
}
