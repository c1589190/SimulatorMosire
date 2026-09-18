package io.mosire.simos.app.tools.read;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code simos.timeline.branches}（spec §7.1 读工具）：分支清单与各自 head。
 *
 * <p>经 {@link CoreSimos#branches()} / {@link CoreSimos#head} 的**只读委托**（spec §S8，零写面）。
 */
public final class BranchListTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.timeline.branches";

  private final CoreSimos core;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "CoreSimos 是本工具的唯一只读事实来源（只调 branches/head 只读委托），非内部表示外泄")
  public BranchListTool(CoreSimos core) {
    this.core = core;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "列出全部分支及其 head（最大 revision）";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    return ToolSupport.schema(new LinkedHashMap<>(), List.of());
  }

  @Override
  public ToolResult execute(ToolContext context) {
    List<String> names = new ArrayList<>();
    for (BranchId branch : core.branches()) {
      names.add(branch.value());
    }
    Collections.sort(names);
    Map<String, Object> heads = new LinkedHashMap<>();
    for (String name : names) {
      Optional<RevisionId> head = core.head(new BranchId(name));
      head.ifPresent(revision -> heads.put(name, revision.value()));
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("branches", names);
    view.put("heads", heads);
    return ToolSupport.ok(view);
  }
}
