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
 * {@code simos.gov.createNation} 窄工具（M3，spec §八.3）：**建国家**的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**（故运行时 MCP 口（= GM 组）也含它）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★★ <b>2026-10-15 批 L ②（设计书 §2 N3「甲：补 {@code simos.} 前缀」）</b>：工具名由 {@code gov.CreateNation} 改为
 * {@code simos.gov.createNation}——★ <b>命令类型一字不动</b>，仍是 {@code gov.CreateNation}（{@code
 * CreateNationHandler} 的 {@code type()}）。改名前两者是**同一个字面量**（{@code NAME} 既是工具名又是命令类型），故本批把 {@link
 * #COMMAND_TYPE} 拆成独立常量：工具名与命令类型在本仓本来就是两个可独立的拼写（见基类 {@code toolName()} 钩子）。
 *
 * <p>★ **{@code homeRegionId} 指向的区域必须带 {@code nation:} 前缀的
 * tag**（R13）——这是建国家的前置，缺它即被域层拒绝，理由原文到达调用方（工具层不重复校验：那份校验能被 {@code simos.command.submit} 绕过 ⇒ 是装饰）。
 */
public final class CreateNationTool extends AbstractNarrowWriteTool implements SimosTool {

  /** 工具名（全局唯一；与命令类型 {@code gov.CreateNation} 不同名）。 */
  public static final String NAME = "simos.gov.createNation";

  /** 本工具钉死的命令类型（与 {@code CreateNationHandler.type()} 同字面；★ 不随工具名改）。 */
  private static final String COMMAND_TYPE = "gov.CreateNation";

  public CreateNationTool(ToolWritePort writes, GaToolIdentity identity, String mapId) {
    super(writes, identity, mapId);
  }

  @Override
  protected String commandType() {
    return COMMAND_TYPE;
  }

  /**
   * ★★ <b>必须覆写</b>（基类缺省 = {@link #commandType()}）：本批起**工具名 ≠ 命令类型**（{@code simos.gov.createNation}
   * vs {@code gov.CreateNation}）——不覆写就会静默退回"命令类型当工具名"（正是基类 {@code toolName()} 钩子存在的理由，D1）。
   */
  @Override
  protected String toolName() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "建国家 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "建国家：固定 gov.CreateNation，载荷 {nationId, name, homeRegionId, adminBudgetPerTick}（四者全必填；★ homeRegionId 指向的区域必须**已带 `nation:` 前缀的 tag**，否则被拒——R13）";
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
