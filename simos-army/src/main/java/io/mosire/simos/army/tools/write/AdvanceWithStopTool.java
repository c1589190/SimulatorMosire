package io.mosire.simos.army.tools.write;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.army.stop.AdvanceStopPort;
import io.mosire.simos.army.stop.StopReportViews;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.util.state.BranchId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ {@code simos.emergency.advance}（2026-10-13 急停协议）：<b>走"有急停"那条路的推进入口</b>——推进 {@code [from, to)}，
 * 若某天有模块报了突发事件 ⇒ 世界<b>停在那一天</b>（那天已落盘、已 drain），并把<b>原因系统</b>直接返回。
 *
 * <p>★★ <b>为什么需要这条新入口（如实记，这是对约束设计书 §7.2 的一处"加做"）</b>：既有的 {@code simos.advance}（MCP）与 {@code
 * /api/advance}（GUI）都直接走 {@code CoreSimos.submit}，返回值是 {@code CommandResult} 三种封闭 ⇒ 急停会被折成 {@code
 * Committed(atTick)}，{@link io.mosire.simos.util.spi.StopReport} <b>无处安放</b>。而口径 3 = (A) 明确"<b>只把
 * drain 接进急停路径</b>"、既有两个入口的断链<b>不在本批</b>（那是有意的范围裁剪）。⇒ 控制方 2026-10-13 要求 B 在自己负责的那一处<b>新增</b>一个真的走
 * {@code CoreSimos.submitAdvance(cmd)} 的入口，P5/P6 与真机实测才可达；既有两个入口一个字未动。
 *
 * <p>★ <b>为什么住 {@code simos-army}</b>：本批给 B 的文件所有权是 {@code simos-army/**} + {@code Shell.java}（设计书
 * §7.2）， 而 {@code AdvanceTime}/{@code AdvanceOutcome} 住 core（army 的允许依赖不含 core）⇒ 工具住 army、推进动作由 app
 * 经 {@link AdvanceStopPort} 注入（见该接口类注）。★ 它与 {@code simos.advance} <b>并存</b>：后者行为一字未改（口径 3=A）。
 *
 * <p>★ <b>参数</b>：{@code branch, expectedRevision, from, to?}（{@code to} 缺省 = {@code from + 1}；与
 * {@code simos.advance} 同口径）。★ <b>返回</b>：{@code result} ∈ {@code committed | stopped | rejected |
 * conflict} ——{@code stopped} 时带 {@code atTick} / {@code worldRef} / {@code reasons[]}（与 {@code
 * simos.army.stopReport} <b>同形</b>）。
 *
 * <p>★ <b>只在 GM 桶</b>：推进是 GM 的推演动作（与 {@code simos.advance} 同档）。
 */
public final class AdvanceWithStopTool implements SimosTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.emergency.advance";

  private final AdvanceStopPort port;
  private final String mapId;

  public AdvanceWithStopTool(AdvanceStopPort port, String mapId) {
    this.port = Objects.requireNonNull(port, "port");
    this.mapId = Objects.requireNonNull(mapId, "mapId");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "推进时间（**有急停**的那条路，与 simos.advance 并存、后者行为未变）：{branch, expectedRevision, from, to?}"
        + "（to 缺省 = from + 1）。推进中若某天有模块报突发事件（本批：army 的\"两军相遇\"，且该军被 simos.army.contactWatch"
        + " 要求监控）⇒ 世界**停在那一天**（该天已推进完、已落盘、已 drain），返回 "
        + "{result:\"stopped\", atTick, worldRef:{branch,revision}, reasons:[{namespace,kind,payload,logRefs}]}；"
        + "整段走完 ⇒ {result:\"committed\", ref:{branch,revision}}；被拒 ⇒ REJECTED；并发冲突 ⇒ CONFLICT（current=真实 head）。"
        + "★ 从 atTick 续推：用 stopped 返回的 revision 作 expectedRevision、from=atTick。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("branch", ToolSchema.prop("string", "分支名"));
    props.put("expectedRevision", ToolSchema.prop("integer", "期望的 base revision（乐观并发）"));
    props.put("from", ToolSchema.prop("integer", "区间起点 tick（左闭）"));
    props.put(
        "to",
        ToolSchema.prop("integer", "推进终点 tick（缺省 = from + 1；可给 to = from + N，1 ≤ N ≤ 36500）"));
    return ToolSchema.schema(props, List.of("branch", "expectedRevision", "from"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写（与 simos.advance 同档）：GM 侧走 GmAutoApproveGate。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return ToolResources.ALL_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "推进时间（有急停）branch="
            + args.get("branch")
            + " expected="
            + args.get("expectedRevision")
            + " from="
            + args.get("from")
            + " to="
            + (ToolSchema.has(args, "to") ? args.get("to") : args.get("from") + " + 1（缺省）"),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      ToolResources.requireAllWrite(context, mapId);
      Map<String, Object> args = context.arguments();
      BranchId branch = new BranchId(ToolSchema.requiredText(args, "branch"));
      long expectedRevision = ToolSchema.requiredLong(args, "expectedRevision");
      long from = ToolSchema.requiredLong(args, "from");
      long to = ToolSchema.has(args, "to") ? ToolSchema.requiredLong(args, "to") : from + 1;
      return fold(port.advance(branch, expectedRevision, from, to));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 与其余工具同一条（T10）：资源拒因必须原样逃到 ToolCallAuthorizer 的边界。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "时间推进提交失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** 四种结局的折叠（{@code stopped} 也走 {@code ok}：世界真的推进了，它是一段真实推进的终点）。 */
  private static ToolResult fold(AdvanceStopPort.Outcome outcome) {
    return switch (outcome) {
      case AdvanceStopPort.Outcome.Committed committed -> {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("result", "committed");
        view.put("ref", ToolResults.stateRef(committed.ref()));
        yield ToolResults.ok(view);
      }
      case AdvanceStopPort.Outcome.Stopped stopped -> {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("result", "stopped");
        view.putAll(StopReportViews.view(stopped.report()));
        yield ToolResults.ok(view);
      }
      case AdvanceStopPort.Outcome.Rejected rejected ->
          ToolResult.error("REJECTED", rejected.reason());
      case AdvanceStopPort.Outcome.Conflict conflict -> {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("result", "conflict");
        view.put("current", ToolResults.stateRef(conflict.current()));
        yield ToolResult.error("CONFLICT", ToolResults.json(view));
      }
    };
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }
}
