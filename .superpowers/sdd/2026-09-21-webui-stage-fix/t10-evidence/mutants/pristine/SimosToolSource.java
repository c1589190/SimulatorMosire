package io.mosire.simos.app.tools;

import io.mosire.agentlib.plugin.ToolSource;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.read.BranchListTool;
import io.mosire.simos.app.tools.read.CatalogTool;
import io.mosire.simos.app.tools.read.MapHexTool;
import io.mosire.simos.app.tools.read.MapOverviewTool;
import io.mosire.simos.app.tools.read.PopulationTool;
import io.mosire.simos.app.tools.read.StateFacetsTool;
import io.mosire.simos.app.tools.read.StateResolveTool;
import io.mosire.simos.app.tools.read.UnitGetTool;
import io.mosire.simos.app.tools.read.UnitListTool;
import io.mosire.simos.app.tools.write.AdvanceTool;
import io.mosire.simos.app.tools.write.CommandSubmitTool;
import io.mosire.simos.app.tools.write.ForkTool;
import io.mosire.simos.app.tools.write.IssueDirectiveTool;
import io.mosire.simos.app.tools.write.SetViewScopeTool;
import io.mosire.simos.app.tools.write.StartDecisionTool;
import io.mosire.simos.app.tools.write.SubmitVerdictTool;
import io.mosire.simos.core.CoreSimos;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Simos 工具供给源（M5 T5，spec §7.1）：**3 写 + 9 读**共 12 条 {@link AgentTool}。
 *
 * <p>★ **它是工具清单的唯一出处**：{@code Shell} 建源后经 {@code McpSourceBridge.bind} 把这份快照同步进 {@link
 * io.mosire.agentlib.tool.ToolRegistry}，T7 再把该注册表交给 {@code
 * AgentToMcpServer}。源本身**不向任何全局状态注册**（AgentLib 的 {@code ToolSource} 契约）。
 *
 * <p>★ **{@code onChange} 是 no-op**：本源的集合是构造期定死的静态集（没有插件式的动态增删口子），故订阅即时成立、无需变更通知。若将来工具集
 * 变成动态的，替换这里的空实现即可（契约要求返回可关闭句柄）。
 *
 * <p>★ **写工具的身份与资源**：{@code initiator} 由 {@code Shell} 从 {@link
 * io.mosire.simos.app.ShellConfig#mcpInitiator()} 注入（spec §九，R4 的断言对象）；{@code mapId} 用于 hex 工具的
 * canonical 地址与资源断言（{@code GameMap} 无 id，见 M2/M3 挂起项）。
 */
public final class SimosToolSource implements ToolSource {

  /** 供给源标识（同一运行时内唯一；审计与按源卸载依赖它）。 */
  public static final String ID = "simos";

  /** 工具面按角色分载（spec §八.3，N9/N11；T4 起现有运行时口 = {@link #EXTERNAL_WITH_GM}）。 */
  public enum Role {
    /** 外部 MCP 客户端：保留现状（有通用写），spec §八.3 列为挂起。 */
    EXTERNAL,
    /** GM：配权 + 窄工具，**无通用写**（N11）。 */
    GM,
    /** 决策 Agent：仅窄工具（`sd.IssueDirective` / `sd.SubmitVerdict`），**无通用写**（N9）。 */
    DECISION_AGENT,
    /**
     * 现有 MCP 口（T4，**D2="加"**，spec §二.5）：**EXTERNAL ∪ GM** —— 9 读共享 + 通用写（submit/advance/fork）+ GM
     * 窄写（IssueDirective/SubmitVerdict/**含** SetViewScope）。
     *
     * <p>★ **与 SDSimos 裁定 N9 的冲突在此端口显式记账**：N9 的原意是「专用窄工具，不给决策 Agent 通用 `simos.command.submit`」；本口
     * 保留通用写是**用户裁定 D2 的取舍、不是缺陷**（其持有者可绕过窄工具直接提交任意命令）。N9 在**决策人口**（{@link #DECISION_AGENT} 桶）与
     * `DecisionMaker.allowedTools` 白名单上**照旧有效**。
     */
    EXTERNAL_WITH_GM
  }

  private final List<AgentTool> tools;

  /**
   * 外部 MCP 桶（与既有行为逐条相同：3 写 + 9 读）。
   *
   * @param core 唯一写入口（写工具经它提交）
   * @param query 只读门面（读工具经它读状态）
   * @param initiator 写命令的发起者（C21 的 {@code <kind>:<id>} 形态）
   * @param mapId 本世界的 map 称谓（构造 canonical hex 地址与资源断言）
   * @param commandTypes 已注册命令类型（与 {@code Shell} 注册的 handler 同源，catalog 读它）
   */
  public SimosToolSource(
      CoreSimos core,
      QueryService query,
      String initiator,
      String mapId,
      Set<String> commandTypes) {
    this(core, query, initiator, mapId, commandTypes, Role.EXTERNAL);
  }

  /** 按角色装配工具面：读工具四桶共享；写面各自不同（{@link Role#EXTERNAL_WITH_GM} 是外部写 ∪ GM 窄写的复合面）。 */
  public SimosToolSource(
      CoreSimos core,
      QueryService query,
      String initiator,
      String mapId,
      Set<String> commandTypes,
      Role role) {
    Objects.requireNonNull(core, "core");
    Objects.requireNonNull(query, "query");
    Objects.requireNonNull(initiator, "initiator");
    Objects.requireNonNull(mapId, "mapId");
    Objects.requireNonNull(commandTypes, "commandTypes");
    Objects.requireNonNull(role, "role");
    List<AgentTool> built = new ArrayList<>(readTools(core, query, mapId, commandTypes));
    switch (role) {
      case EXTERNAL -> addExternalWrites(built, core, initiator, mapId);
      case GM -> addGmWrites(built, core, initiator, mapId);
      case DECISION_AGENT -> addDecisionAgentWrites(built, core, initiator, mapId);
      case EXTERNAL_WITH_GM -> {
        addExternalWrites(built, core, initiator, mapId);
        addGmWrites(built, core, initiator, mapId);
      }
    }
    this.tools = List.copyOf(built);
  }

  /** EXTERNAL 桶的通用写（spec §八.3）：submit / advance / fork。 */
  private static void addExternalWrites(
      List<AgentTool> built, CoreSimos core, String initiator, String mapId) {
    built.add(new CommandSubmitTool(core, initiator, mapId));
    built.add(new AdvanceTool(core, initiator, mapId));
    built.add(new ForkTool(core, initiator));
  }

  /** GM 窄写（N11）：两条决策窄工具 + 配权工具（**含** `sd.SetViewScope`）+ 「开始决策」（T10）。 */
  private static void addGmWrites(
      List<AgentTool> built, CoreSimos core, String initiator, String mapId) {
    built.add(new IssueDirectiveTool(core, initiator, mapId));
    built.add(new SubmitVerdictTool(core, initiator, mapId));
    built.add(new SetViewScopeTool(core, initiator, mapId));
    built.add(new StartDecisionTool(core, initiator, mapId));
  }

  /** 决策 Agent 窄写（N9）：**无** `sd.SetViewScope`、**无**通用写。 */
  private static void addDecisionAgentWrites(
      List<AgentTool> built, CoreSimos core, String initiator, String mapId) {
    built.add(new IssueDirectiveTool(core, initiator, mapId));
    built.add(new SubmitVerdictTool(core, initiator, mapId));
  }

  private static List<AgentTool> readTools(
      CoreSimos core, QueryService query, String mapId, Set<String> commandTypes) {
    return List.of(
        new CatalogTool(commandTypes),
        new StateResolveTool(query, mapId),
        new StateFacetsTool(query, mapId),
        new BranchListTool(core),
        new MapOverviewTool(query, mapId),
        new MapHexTool(query, mapId),
        new UnitListTool(query),
        new UnitGetTool(query),
        new PopulationTool(query));
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public List<AgentTool> listTools() {
    return tools;
  }

  /** 静态工具集：订阅即时成立、永不变更，故返回空句柄（契约要求非 null）。 */
  @Override
  public AutoCloseable onChange(Runnable listener) {
    Objects.requireNonNull(listener, "listener");
    return () -> {};
  }
}
