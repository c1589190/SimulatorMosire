package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DecisionPacketId;
import io.mosire.simos.sd.model.DecisionPacket;
import io.mosire.simos.sd.model.PacketStatus;
import io.mosire.simos.sd.spi.SubmitDecisionPacketHandler;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code simos.sd.packet.submit}（D2 决策包计划 §3.2）：把本 tick 的 DRAFT 决策包置为 PENDING。
 *
 * <p>★ 只接受 DRAFT；**已 PENDING ⇒ 幂等成功**（返回现状，不写 revision）；已裁决 ⇒ 具名拒；本 tick 没有包 ⇒ 具名拒。
 *
 * <p>★ 身份从 {@code context.identity()} 派生（不采信载荷）；只写自己的 {@code sd:decision-packet/<自己>} 前缀。
 */
public final class SubmitPacketTool implements AgentTool {

  /** 工具名（全局唯一）。★ 不是命令类型 ⇒ 不进 catalog / PAYLOAD_HINTS。 */
  public static final String NAME = "simos.sd.packet.submit";

  private static final ResourceManifest SD_WRITE =
      ResourceManifest.of(ToolSupport.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private final CoreSimos core;
  private final QueryService query;
  private final String initiator;

  public SubmitPacketTool(CoreSimos core, QueryService query, String initiator) {
    this.core = Objects.requireNonNull(core, "core");
    this.query = Objects.requireNonNull(query, "query");
    this.initiator = Objects.requireNonNull(initiator, "initiator");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "决策人提交本 tick 的决策包：DRAFT → PENDING。参数 {branch?(缺省 "
        + ToolSupport.DEFAULT_BRANCH
        + "), expectedRevision?(缺省 head)}。本 tick 没有包 ⇒ BAD_REQUEST；已 PENDING ⇒ 幂等成功；"
        + "已裁决 ⇒ BAD_REQUEST。返回 {packetId, status, callCount, submission?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop("integer", "读取/提交的乐观并发 base revision（缺省 = 该分支 head；给出必须 ≥ 0）"));
    return ToolSupport.schema(props, List.of());
  }

  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return SD_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    return new ToolGate.Ask(name(), "提交本 tick 决策包", AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      Optional<String> decisionMakerId =
          DecisionCallerFactory.decisionMakerIdOf(context.identity());
      if (decisionMakerId.isEmpty()) {
        return ToolResult.error(
            "FORBIDDEN", "simos.sd.packet.submit 只能由决策人身份调用（身份里没有 decision-maker: 前缀）");
      }
      DecisionMakerId proposerId = DecisionMakerId.parse(decisionMakerId.get());
      BranchId branch =
          new BranchId(ToolSupport.optionalText(args, "branch", ToolSupport.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSupport.optionalLong(args, "expectedRevision");
      if (expectedRevisionArg != null && expectedRevisionArg < 0L) {
        return ToolResult.error("BAD_REQUEST", "expectedRevision 不得为负: " + expectedRevisionArg);
      }
      SimulationState state =
          expectedRevisionArg == null
              ? query.stateAt(QueryService.QueryTarget.head(branch))
              : query.stateAt(
                  QueryService.QueryTarget.at(branch, new RevisionId(expectedRevisionArg)));
      SdState sd = ToolSupport.sdState(state);
      long tick = state.meta().timestamp().tick();
      DecisionPacketId packetId = DecisionPacketId.parse("pkt-" + proposerId.value() + "-" + tick);
      DecisionPacket packet = sd.decisionPackets().get(packetId);
      if (packet == null) {
        return ToolResult.error(
            "BAD_REQUEST", "本 tick 还没有决策包（先 propose 或 intent）: " + packetId.value());
      }
      if (!packet.proposerId().equals(proposerId)) {
        return ToolResult.error("FORBIDDEN", "决策包不属于调用者: " + packetId.value());
      }
      ToolSupport.requireAll(
          context,
          Operation.WRITE,
          List.of(ToolSupport.resourceSd("decision-packet", proposerId.value())));
      if (packet.status() == PacketStatus.PENDING) {
        return ToolSupport.ok(view(packet, false)); // 幂等：已提交返回现状
      }
      if (packet.status() != PacketStatus.DRAFT) {
        return ToolResult.error(
            "BAD_REQUEST",
            "只有 DRAFT 决策包可提交，当前状态: " + packet.status() + "（" + packetId.value() + "）");
      }
      String commandId = UUID.randomUUID().toString();
      CommandEnvelope envelope =
          new CommandEnvelope(
              commandId,
              commandId,
              initiator,
              branch,
              new RevisionId(state.meta().ref().revision().value()),
              SubmitDecisionPacketHandler.TYPE,
              DecisionPacketPayloads.submit(packetId.value(), proposerId.value()));
      CommandResult result = core.submit(envelope);
      Map<String, Object> view = view(packet, true);
      view.put("status", PacketStatus.PENDING.name());
      return DecisionPacketPayloads.fold(result, view, commandId);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "提交失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static Map<String, Object> view(DecisionPacket packet, boolean submitted) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("packetId", packet.id().value());
    view.put("status", packet.status().name());
    view.put("callCount", packet.calls().size());
    view.put("submitted", submitted);
    return view;
  }
}
