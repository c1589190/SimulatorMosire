package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.gm.GmToolUsage;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.tools.GmOnlyRead;
import io.mosire.simos.app.tools.ToolSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code simos.gm.tool-usage}（工具面补齐，2026-09-25）：**GM 口工具使用记录**（只读）。
 *
 * <p>补的是 GUI 读口 {@code GET /api/gm/tool-usage}（{@code GuiServer:191} 常量、{@code GuiServer:625} 分支）在
 * MCP 侧的对应工具。数据源是进程内有界的 {@link GmToolUsage}（最多 200 条，最新在前）。
 *
 * <p>★ **形状与 GUI 同源**：走 {@link ApiViews#gmToolUsage(List)}（GUI {@code gmToolUsageReply} 用的就是它）。
 *
 * <p>★★ **只在 GM 桶**（{@link GmOnlyRead}）：M4 侦察报告 §二-3 已定这条的性质——它是**运行时监督数据**（谁在什么时候调了哪个工具、
 * 成功与否），语义上属于 GM/运维面，**不是世界状态**（进程重启即空，不进 revision）。四桶共享会把"GM 在看什么"反向暴露给被观测者 （观测者被反向观测）⇒ 决策人桶不开（GUI
 * 该端点同样显式拒 {@code as=}）。资源声明取 {@link ResourceManifest#NONE}： 它不读任何领域命名空间。
 */
public final class GmToolUsageTool implements AgentTool, GmOnlyRead {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.gm.tool-usage";

  private final GmToolUsage usage;

  public GmToolUsageTool(GmToolUsage usage) {
    this.usage = Objects.requireNonNull(usage, "usage");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 口工具使用记录（最新在前，有界 200 条）：{entries:[{tool,ok,code,atEpochMs}]}";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    return ToolSupport.schema(new LinkedHashMap<>(), List.of());
  }

  @Override
  public ResourceManifest resources() {
    return ResourceManifest.NONE;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("entries", ApiViews.gmToolUsage(usage.recent()));
    return ToolSupport.ok(view);
  }
}
