package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.GmOnlyRead;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.sd.model.DecisionPacket;
import io.mosire.simos.sd.model.FormattedCall;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code simos.gm.packets}（D2 决策包计划 §4）：GM 的决策包**摘要列表**（只读、GM 桶）。
 *
 * <p>★ 参与筛选：tick / status / proposerId / tool（含该工具名的一条 call）；只发
 * id/tick/proposer/status/callCount/toolNames。
 *
 * <p>★ 只在 GM 桶（{@link GmOnlyRead}），资源声明 sd 只读；GM 的 sd 命名空间是 unlimited。
 */
public final class GmPacketsTool implements AgentTool, GmOnlyRead {

  /** 工具名（全局唯一）。★ 不是命令类型 ⇒ 不进 catalog / PAYLOAD_HINTS。 */
  public static final String NAME = "simos.gm.packets";

  private static final ResourceManifest SD_READ =
      ResourceManifest.of(ToolSupport.SD_NAMESPACE, ResourcePolicy.READ_ONLY);

  private final QueryService query;

  public GmPacketsTool(QueryService query) {
    this.query = Objects.requireNonNull(query, "query");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 查看决策包摘要列表（只读）：参数 {tick?, status?, proposerId?, tool?, branch?(缺省 "
        + ToolSupport.DEFAULT_BRANCH
        + "), expectedRevision?(缺省 head)}。返回 {branch, revision, count, packets:[{id, tick, proposerId, "
        + "status, callCount, toolNames}]}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSupport.targetProps();
    props.put("tick", ToolSupport.prop("integer", "只看该 tick"));
    props.put(
        "status",
        ToolSupport.prop(
            "string", "只看该状态（DRAFT|PENDING|APPROVED|REJECTED|MERGED|PARTIALLY_APPROVED）"));
    props.put("proposerId", ToolSupport.prop("string", "只看该决策人提交的包"));
    props.put("tool", ToolSupport.prop("string", "只看含该工具名 call 的包"));
    return ToolSupport.schema(props, List.of());
  }

  @Override
  public ResourceManifest resources() {
    return SD_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      ToolSupport.requireAll(
          context, Operation.READ, List.of(ResourceId.of(ToolSupport.SD_NAMESPACE, "*")));
      Map<String, Object> args = context.arguments();
      BranchId branch =
          new BranchId(ToolSupport.optionalText(args, "branch", ToolSupport.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSupport.optionalLong(args, "expectedRevision");
      SimulationState state =
          expectedRevisionArg == null
              ? query.stateAt(QueryService.QueryTarget.head(branch))
              : query.stateAt(
                  QueryService.QueryTarget.at(branch, new RevisionId(expectedRevisionArg)));
      SdState sd = ToolSupport.sdState(state);
      Long tick = ToolSupport.optionalLong(args, "tick");
      String status = ToolSupport.optionalText(args, "status", null);
      String proposerId = ToolSupport.optionalText(args, "proposerId", null);
      String tool = ToolSupport.optionalText(args, "tool", null);
      List<Map<String, Object>> packets = new ArrayList<>();
      for (DecisionPacket packet : sd.decisionPackets().values()) {
        if (tick != null && packet.tick() != tick) {
          continue;
        }
        if (status != null && !status.equals(packet.status().name())) {
          continue;
        }
        if (proposerId != null && !proposerId.equals(packet.proposerId().value())) {
          continue;
        }
        if (tool != null && !containsTool(packet, tool)) {
          continue;
        }
        packets.add(summary(packet));
      }
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("branch", state.meta().ref().branch().value());
      view.put("revision", state.meta().ref().revision().value());
      view.put("count", packets.size());
      view.put("packets", packets);
      return ToolSupport.ok(view);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "读取决策包列表失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static boolean containsTool(DecisionPacket packet, String tool) {
    for (FormattedCall call : packet.calls()) {
      if (tool.equals(call.toolName())) {
        return true;
      }
    }
    return false;
  }

  private static Map<String, Object> summary(DecisionPacket packet) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", packet.id().value());
    row.put("tick", packet.tick());
    row.put("proposerId", packet.proposerId().value());
    row.put("status", packet.status().name());
    row.put("callCount", packet.calls().size());
    List<String> toolNames = new ArrayList<>(packet.calls().size());
    for (FormattedCall call : packet.calls()) {
      toolNames.add(call.toolName());
    }
    row.put("toolNames", toolNames);
    return row;
  }
}
