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
import io.mosire.simos.app.tools.DecisionPacketViews;
import io.mosire.simos.app.tools.GmOnlyRead;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DecisionPacketId;
import io.mosire.simos.sd.model.DecisionPacket;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code simos.gm.packet}（D2 决策包计划 §4）：GM 读单个决策包的全量（intent、逐 call 的参数/目标/预览/拟稿检查/状态）。
 *
 * <p>★ 定位两选一：{@code packetId}，或 {@code tick + proposerId}（包 id 是 {@code pkt-<proposer>-<tick>}
 * 的确定性形态， 但本工具不替调用方拼——两种都显式收）。
 *
 * <p>★ 只在 GM 桶（{@link GmOnlyRead}），视图与 {@code simos.sd.packet.my} 共用 {@link DecisionPacketViews}。
 */
public final class GmPacketTool implements AgentTool, GmOnlyRead {

  /** 工具名（全局唯一）。★ 不是命令类型 ⇒ 不进 catalog / PAYLOAD_HINTS。 */
  public static final String NAME = "simos.gm.packet";

  private static final ResourceManifest SD_READ =
      ResourceManifest.of(ToolSupport.SD_NAMESPACE, ResourcePolicy.READ_ONLY);

  private final QueryService query;

  public GmPacketTool(QueryService query) {
    this.query = Objects.requireNonNull(query, "query");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 读单个决策包全量（只读）：参数 {packetId} 或 {tick + proposerId}，另 {branch?(缺省 "
        + ToolSupport.DEFAULT_BRANCH
        + "), expectedRevision?(缺省 head)}。返回 {packetId, branch, tick, proposerId, status, intent, "
        + "calls:[{callIndex, toolName, argsJson, args, targets, previewJson, preview, draftChecks, status, "
        + "mergedPlanId?}], decidedBy?, decidedAtRevision?, decisionNote?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSupport.targetProps();
    props.put("packetId", ToolSupport.prop("string", "决策包 id（与 {tick+proposerId} 二选一）"));
    props.put("tick", ToolSupport.prop("integer", "包所属 tick（与 proposerId 一起定位）"));
    props.put("proposerId", ToolSupport.prop("string", "决策人 id（与 tick 一起定位）"));
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
      DecisionPacketId packetId = resolvePacketId(args);
      DecisionPacket packet = sd.decisionPackets().get(packetId);
      if (packet == null) {
        return ToolResult.error("NOT_FOUND", "决策包不存在: " + packetId.value());
      }
      return ToolSupport.ok(DecisionPacketViews.packetView(packet, state));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "读取决策包失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static DecisionPacketId resolvePacketId(Map<String, Object> args) {
    String explicit = ToolSupport.optionalText(args, "packetId", null);
    if (explicit != null) {
      return DecisionPacketId.parse(explicit);
    }
    Long tick = ToolSupport.optionalLong(args, "tick");
    String proposerId = ToolSupport.optionalText(args, "proposerId", null);
    if (tick == null || proposerId == null) {
      throw new IllegalArgumentException("必须给 packetId，或 tick + proposerId 两个都给");
    }
    if (tick < 0L) {
      throw new IllegalArgumentException("tick 不得为负: " + tick);
    }
    return DecisionPacketId.parse("pkt-" + DecisionMakerId.parse(proposerId).value() + "-" + tick);
  }
}
