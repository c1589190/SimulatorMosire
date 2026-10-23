package io.mosire.simos.ga.tools.write;

import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.model.DecisionRecord;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.ga.tools.GaToolIdentity;
import io.mosire.simos.ga.tools.GaToolReads;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code simos.ga.packet.intent}（DM 面）：写本 tick 决策包的领域 NL intent（整段覆盖）。
 *
 * <p>设计书 §7.3：{@code Allow}，写 {@link DecisionRecord}；不改 AgentLib {@code PackageState}。内容冻结后
 * （第一条逐条裁决或任何非 PROPOSED 状态）具名拒 {@code PACKAGE_CONTENT_FROZEN}。
 */
public final class PacketIntentTool implements SimosTool {

  public static final String NAME = "simos.ga.packet.intent";

  private static final ResourceManifest GA_WRITE =
      ResourceManifest.of(ToolResources.GA_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private final ToolWritePort writes;
  private final ToolReadPort reads;
  private final GaToolIdentity identity;

  public PacketIntentTool(ToolWritePort writes, ToolReadPort reads, GaToolIdentity identity) {
    this.writes = Objects.requireNonNull(writes, "writes");
    this.reads = Objects.requireNonNull(reads, "reads");
    this.identity = Objects.requireNonNull(identity, "identity");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "决策人写本 tick 决策包的 NL 意图（整段覆盖；不参与 AgentLib 状态机）。参数 "
        + "{text(必填非空白), tick?(缺省 = 当前世界 tick), branch?(缺省 "
        + ToolResources.DEFAULT_BRANCH
        + "), expectedRevision?(缺省 head)}。本 tick 无包 ⇒ PACKAGE_NOT_FOUND；第一条逐条裁决后 ⇒ "
        + "PACKAGE_CONTENT_FROZEN。返回 {packetId, status, packageState, intent?, submission?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("text", ToolSchema.prop("string", "意图文本（必填非空白；整段覆盖）"));
    props.put("tick", ToolSchema.prop("integer", "要写 intent 的 tick（缺省 = 当前世界 tick）"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision", ToolSchema.prop("integer", "读取/提交的乐观并发 base revision（缺省 = 该分支 head）"));
    return ToolSchema.schema(props, List.of("text"));
  }

  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return GA_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    return ToolGate.ALLOW;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      Optional<String> decisionMakerId = identity.decisionMakerIdOf(context.identity());
      if (decisionMakerId.isEmpty()) {
        return ToolResult.error("FORBIDDEN", NAME + " 只能由决策人身份调用（身份里没有 decision-maker: 前缀）");
      }
      DecisionMakerId proposerId = DecisionMakerId.parse(decisionMakerId.get());
      String text = ToolSchema.requiredText(args, "text");
      BranchId branch = GaToolReads.branch(args);
      Long expectedRevisionArg = ToolSchema.optionalLong(args, "expectedRevision");
      if (expectedRevisionArg != null && expectedRevisionArg < 0L) {
        return ToolResult.error("BAD_REQUEST", "expectedRevision 不得为负: " + expectedRevisionArg);
      }
      SimulationState state = GaToolReads.stateOfExpectedRevision(reads, args);
      GaState ga = GaToolReads.ga(state);
      Long tickArg = ToolSchema.optionalLong(args, "tick");
      if (tickArg != null && tickArg < 0L) {
        return ToolResult.error("BAD_REQUEST", "tick 不得为负: " + tickArg);
      }
      long tick = tickArg == null ? state.meta().timestamp().tick() : tickArg;
      Optional<ToolResult> staleTick =
          DecisionPackageWriteSupport.rejectIfNotCurrentTick(
              DecisionPackageWriteSupport.packetId(proposerId, tick),
              tick,
              DecisionPackageWriteSupport.currentTick(reads, branch));
      if (staleTick.isPresent()) {
        return staleTick.get();
      }
      DecisionRecord existing = DecisionPackageWriteSupport.find(ga, proposerId, tick);
      if (existing == null) {
        return ToolResult.error(
            "PACKAGE_NOT_FOUND",
            "本 tick 没有决策包，无法写 intent: "
                + DecisionPackageWriteSupport.packetId(proposerId, tick).value());
      }
      if (!DecisionPackageWriteSupport.contentEditable(existing)) {
        return DecisionPackageWriteSupport.contentFrozen(existing);
      }
      DecisionPackageWriteSupport.requireSelfWrite(context, identity, proposerId);
      DecisionRecord next =
          DecisionPackageWriteSupport.withDomainFields(
              existing, text, existing.confirmedAtRevision(), existing.reasonInfoId());
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("packetId", next.id().value());
      view.put("status", next.view().status().name());
      view.put("packageState", next.packageState().pkg().state().name());
      view.put("intent", next.intent());
      return DecisionPackageWriteSupport.submit(writes, branch, state, existing, next, view);
    } catch (IllegalArgumentException | IllegalStateException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "intent 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.DM);
  }
}
