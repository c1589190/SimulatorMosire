package io.mosire.simos.app.tools.read;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code simos.timeline.revisions}（工具面 M4）：**时间轴的节点清单**（{@code branch} / {@code head} / {@code
 * nodes[]}）。
 *
 * <p>补的是 M4 侦察报告里那条**部分缺口**：已有的 {@code simos.timeline.branches} 只给"有哪些分支、各自 head 在哪"，**不给节点**——于是
 * Agent 看得到"有几条 revision"，看不到"每条 revision 是什么命令、谁提交的、父是谁"。 数据早就在 {@link
 * CoreSimos#revisions(BranchId)} 里，缺的只是这一个读口。
 *
 * <p>★ **形状与 GUI 同源**：{@link ApiViews#timeline(BranchId, long, List)} —— GUI {@code /api/timeline}
 * 用的就是它。
 *
 * <p>★ **只读**：{@code core.head} / {@code core.revisions} 都不触发封存与写盘（R1 口径）。分支不存在 ⇒ {@code
 * NOT_FOUND}（与 GUI 的 404 同口径：不给"空分支"这种含糊结果）。
 */
public final class TimelineRevisionsTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.timeline.revisions";

  private final CoreSimos core;

  // ★ CoreSimos 是本工具的唯一读入口（只调 head/replay 等只读面），不是"可变内部表示外泄"：
  //   与同族的写工具（AdvanceTool 的 EI_EXPOSE_REP2）同口径豁免。
  @SuppressFBWarnings(value = "EI_EXPOSE_REP2", justification = "CoreSimos 是共享读入口（只调只读面），非内部表示外泄")
  public TimelineRevisionsTool(CoreSimos core) {
    this.core = core;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "时间轴节点清单：{branch, head, nodes:[{revision,tick,commandType,initiator,parent}]}";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 main）"));
    return ToolSupport.schema(props, List.of());
  }

  @Override
  public ResourceManifest resources() {
    return ToolSupport.ALL_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      BranchId branch =
          new BranchId(ToolSupport.optionalText(args, "branch", ToolSupport.DEFAULT_BRANCH));
      Optional<RevisionId> head = core.head(branch);
      if (head.isEmpty()) {
        return ToolResult.error("NOT_FOUND", "分支不存在: " + branch.value());
      }
      List<RevisionRow> rows = core.revisions(branch);
      return ToolSupport.ok(ApiViews.timeline(branch, head.get().value(), rows));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }
}
