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
import io.mosire.simos.core.CoreSimos;
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

  private final List<AgentTool> tools;

  /**
   * @param core 唯一写入口（三条写工具经它提交）
   * @param query 只读门面（九条读工具经它读状态）
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
    Objects.requireNonNull(core, "core");
    Objects.requireNonNull(query, "query");
    Objects.requireNonNull(initiator, "initiator");
    Objects.requireNonNull(mapId, "mapId");
    Objects.requireNonNull(commandTypes, "commandTypes");
    this.tools =
        List.of(
            new CatalogTool(commandTypes),
            new StateResolveTool(query, mapId),
            new StateFacetsTool(query, mapId),
            new BranchListTool(core),
            new MapOverviewTool(query, mapId),
            new MapHexTool(query, mapId),
            new UnitListTool(query),
            new UnitGetTool(query),
            new PopulationTool(query),
            new CommandSubmitTool(core, initiator, mapId),
            new AdvanceTool(core, initiator, mapId),
            new ForkTool(core, initiator));
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
