package io.mosire.simos.app.tools.write;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code simos.advance}（spec §7.1 写工具）：时间推进（{@code AdvanceTime}）。
 *
 * <p>构造 {@link AdvanceTime}（身份三件套由本类填：新 UUID × 2 + 注入 initiator）→ {@link CoreSimos#submit} → 结局折叠。
 *
 * <p>★ 2026-09-25 §十一 裁定（取代 2026-09-24 的"每次恰好一天"）：{@code 1 tick = 1 天}，本工具**一次推进 N 天**——{@code to}
 * 缺省 = {@code from + 1}（推一天）；显式给 {@code to} 时允许 {@code to = from + N}（{@code 1 ≤ N ≤ 36500}，越界由
 * Core 拒绝）。 结算语义不跳日：各参与者在这一次推进内部**逐日**推进（等价性见 Core 与 e2e 护栏）。
 */
public final class AdvanceTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.advance";

  private final CoreSimos core;
  private final String initiator;
  private final String mapId;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "CoreSimos 是本工具的唯一写入口（只调 submit），非内部表示外泄；写面仍受权限/审批闸约束")
  public AdvanceTool(CoreSimos core, String initiator, String mapId) {
    this.core = core;
    this.initiator = initiator;
    this.mapId = mapId;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "推进时间：{branch, expectedRevision, from, to?} → AdvanceTime（to 缺省 = from + 1；可给 to = from + N 一次推进 N 天，"
        + "1 ≤ N ≤ 36500，内部逐日结算）";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("branch", ToolSupport.prop("string", "分支名"));
    props.put("expectedRevision", ToolSupport.prop("integer", "期望的 base revision（乐观并发）"));
    props.put("from", ToolSupport.prop("integer", "区间起点 tick（左闭）"));
    props.put(
        "to",
        ToolSupport.prop("integer", "推进终点 tick（缺省 = from + 1；可给 to = from + N，1 ≤ N ≤ 36500）"));
    return ToolSupport.schema(props, List.of("branch", "expectedRevision", "from"));
  }

  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return ToolSupport.ALL_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "推进时间（to 缺省 = from + 1；可 to = from + N 一次推 N 天，1 ≤ N ≤ 36500）branch="
            + args.get("branch")
            + " expected="
            + args.get("expectedRevision")
            + " from="
            + args.get("from")
            + " to="
            + (ToolSupport.has(args, "to") ? args.get("to") : args.get("from") + " + 1（缺省）"),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    String id = UUID.randomUUID().toString();
    try {
      ToolSupport.requireAllWrite(context, mapId);
      Map<String, Object> args = context.arguments();
      long from = ToolSupport.requiredLong(args, "from");
      // ★ §十一：一次 AdvanceTime 可推进 N 天——to 缺省 = from + 1（推一天）；显式 to 允许 to = from + N（Core 判 1 ≤ N ≤
      // 36500）。
      long to = ToolSupport.has(args, "to") ? ToolSupport.requiredLong(args, "to") : from + 1;
      TimeRange range = new TimeRange(SimosTimestamp.of(from), Optional.of(SimosTimestamp.of(to)));
      AdvanceTime command =
          new AdvanceTime(
              id,
              id,
              initiator,
              new BranchId(ToolSupport.requiredText(args, "branch")),
              new RevisionId(ToolSupport.requiredLong(args, "expectedRevision")),
              range);
      return ToolSupport.fold(core.submit(command), id, id);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 与 AbstractNarrowWriteTool 同一条（T10）：资源拒因必须原样逃到 ToolCallAuthorizer 的边界。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "时间推进提交失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }
}
