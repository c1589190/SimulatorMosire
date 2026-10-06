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
import io.mosire.simos.sd.spi.UpsertDecisionPacketHandler;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * {@code simos.sd.packet.intent}（D2 决策包计划 §3.2）：把自然语言意图写进本 tick 的 DRAFT 决策包。
 *
 * <p>★ 拟稿期只走 DRAFT：非 DRAFT ⇒ 具名拒（已提交/已裁决的包不得回改留痕）；本 tick 还没有包 ⇒ 建一个空 DRAFT 包再写 intent（一个决策人一个 tick
 * 一个包）。
 *
 * <p>★ 身份从 {@code context.identity()} 派生；身份派生的 proposer 不采信载荷。
 */
public final class PacketIntentTool implements AgentTool {

  /** 工具名（全局唯一）。★ 不是命令类型 ⇒ 不进 catalog / PAYLOAD_HINTS。 */
  public static final String NAME = "simos.sd.packet.intent";

  private static final ResourceManifest SD_WRITE =
      ResourceManifest.of(ToolSupport.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private final CoreSimos core;
  private final QueryService query;
  private final String initiator;

  public PacketIntentTool(CoreSimos core, QueryService query, String initiator) {
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
    return "决策人写本 tick 决策包的 NL 意图（不进 true/false 机械执行）。参数 {text(必填非空白), branch?(缺省 "
        + ToolSupport.DEFAULT_BRANCH
        + "), expectedRevision?(缺省 head)}。只接受 DRAFT；本 tick 无包则新建空 DRAFT 再写。"
        + "返回 {packetId, status, intent, submission?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("text", ToolSupport.prop("string", "意图文本（必填非空白）"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop("integer", "读取/提交的乐观并发 base revision（缺省 = 该分支 head；给出必须 ≥ 0）"));
    return ToolSupport.schema(props, List.of("text"));
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
    return new ToolGate.Ask(
        name(), "决策意图 text=" + context.arguments().get("text"), AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      Optional<String> decisionMakerId =
          DecisionCallerFactory.decisionMakerIdOf(context.identity());
      if (decisionMakerId.isEmpty()) {
        return ToolResult.error(
            "FORBIDDEN", "simos.sd.packet.intent 只能由决策人身份调用（身份里没有 decision-maker: 前缀）");
      }
      DecisionMakerId proposerId = DecisionMakerId.parse(decisionMakerId.get());
      String text = ToolSupport.requiredText(args, "text");
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
      DecisionPacket existing = sd.decisionPackets().get(packetId);
      if (existing != null && existing.status() != PacketStatus.DRAFT) {
        return ToolResult.error(
            "BAD_REQUEST",
            "本 tick 决策包已提交/已裁决，不能再改 intent: " + packetId.value() + " status=" + existing.status());
      }
      ToolSupport.requireAll(
          context,
          Operation.WRITE,
          List.of(ToolSupport.resourceSd("decision-packet", proposerId.value())));
      DecisionPacket packet =
          existing == null
              ? new DecisionPacket(
                  packetId,
                  branch.value(),
                  tick,
                  proposerId,
                  PacketStatus.DRAFT,
                  text,
                  List.of(),
                  state.meta().ref().revision().value(),
                  Optional.empty(),
                  OptionalLong.empty(),
                  Optional.empty(),
                  Optional.empty())
              : existing.withIntent(text);
      String commandId = UUID.randomUUID().toString();
      CommandEnvelope envelope =
          new CommandEnvelope(
              commandId,
              commandId,
              initiator,
              branch,
              new RevisionId(state.meta().ref().revision().value()),
              UpsertDecisionPacketHandler.TYPE,
              DecisionPacketPayloads.upsert(packet));
      CommandResult result = core.submit(envelope);
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("packetId", packetId.value());
      view.put("status", PacketStatus.DRAFT.name());
      view.put("intent", text);
      return DecisionPacketPayloads.fold(result, view, commandId);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "intent 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }
}
