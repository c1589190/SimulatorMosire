package io.mosire.simos.ga.tools.read;

import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.id.DecisionPacketId;
import io.mosire.simos.ga.model.DecisionRecord;
import io.mosire.simos.ga.spi.GmOnlyRead;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.tools.DecisionPacketViews;
import io.mosire.simos.ga.tools.GaToolReads;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code simos.gm.packet}（GM 只读领域投影，设计书 §8.3）。
 *
 * <p>数据源是 {@link DecisionRecord}：输出旧 {@code DecisionPacket} 视图字段 + 精确 {@code packageState} +
 * verdicts + intent + 逐 call 的
 * preview/targets/draftChecks/mergedPlanId/outcomeJson。它<b>只读</b>，不是第二套决策面； 任何裁决/执行必须走 AgentLib
 * 五工具。
 */
public final class GmPacketTool implements SimosTool, GmOnlyRead {

  public static final String NAME = "simos.gm.packet";

  private static final ResourceManifest GA_READ =
      ResourceManifest.of(ToolResources.GA_NAMESPACE, ResourcePolicy.READ_ONLY);

  private final ToolReadPort reads;

  public GmPacketTool(ToolReadPort reads) {
    this.reads = Objects.requireNonNull(reads, "reads");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 读单个决策包（只读领域投影）：参数 {packetId} 或 {tick + proposerId}，另 {branch?(缺省 "
        + ToolResources.DEFAULT_BRANCH
        + "), expectedRevision?(缺省 head)}。返回领域投影 + packageState + verdicts + intent + 逐 call "
        + "preview/targets/draftChecks/mergedPlanId/outcomeJson。★ 精确状态以 packageState 为准；本工具不改任何状态。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSchema.targetProps();
    props.put("packetId", ToolSchema.prop("string", "决策包 id（与 {tick+proposerId} 二选一）"));
    props.put("tick", ToolSchema.prop("integer", "包所属 tick（与 proposerId 一起定位）"));
    props.put("proposerId", ToolSchema.prop("string", "决策人 id（与 tick 一起定位）"));
    return ToolSchema.schema(props, List.of());
  }

  @Override
  public ResourceManifest resources() {
    return GA_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      ToolResources.requireAll(
          context, Operation.READ, List.of(ResourceId.of(ToolResources.GA_NAMESPACE, "*")));
      Map<String, Object> args = context.arguments();
      SimulationState state = GaToolReads.stateOfExpectedRevision(reads, args);
      GaState ga = GaToolReads.ga(state);
      DecisionPacketId packetId = resolvePacketId(args);
      DecisionRecord record = ga.decisionPackets().get(packetId);
      if (record == null) {
        return ToolResult.error("NOT_FOUND", "决策包不存在: " + packetId.value());
      }
      return ToolResults.ok(DecisionPacketViews.packetView(record, state));
    } catch (IllegalArgumentException | IllegalStateException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "读取决策包失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static DecisionPacketId resolvePacketId(Map<String, Object> args) {
    String explicit = ToolSchema.optionalText(args, "packetId", null);
    if (explicit != null) {
      return DecisionPacketId.parse(explicit);
    }
    Long tick = ToolSchema.optionalLong(args, "tick");
    String proposerId = ToolSchema.optionalText(args, "proposerId", null);
    if (tick == null || proposerId == null) {
      throw new IllegalArgumentException("必须给 packetId，或 tick + proposerId 两个都给");
    }
    if (tick < 0L) {
      throw new IllegalArgumentException("tick 不得为负: " + tick);
    }
    return DecisionPacketId.parse("pkt-" + DecisionMakerId.parse(proposerId).value() + "-" + tick);
  }

  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }
}
