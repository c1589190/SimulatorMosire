package io.mosire.simos.ga.tools.write;

import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.decision.DecisionPackageSnapshot;
import io.mosire.agentlib.decision.DecisionPackageTransitions;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.model.DecisionCallMeta;
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
 * {@code simos.ga.packet.drop}（DM 面）：删除本 tick 决策包里的一条调用。
 *
 * <p>设计书 §7.3：{@code Allow}；由 {@link DecisionPackageTransitions#removeItem} 改 AgentLib 快照，并删除对应
 * {@link DecisionCallMeta}。index 不重排（remove 留洞，设计书 I3）。
 */
public final class DropCallTool implements SimosTool {

  public static final String NAME = "simos.ga.packet.drop";

  private static final ResourceManifest GA_WRITE =
      ResourceManifest.of(ToolResources.GA_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private final ToolWritePort writes;
  private final ToolReadPort reads;
  private final GaToolIdentity identity;

  public DropCallTool(ToolWritePort writes, ToolReadPort reads, GaToolIdentity identity) {
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
    return "决策人删除本 tick 决策包里的一条调用：参数 {callIndex(必填、整数 ≥ 0), tick?(缺省当前 tick), "
        + "branch?(缺省 "
        + ToolResources.DEFAULT_BRANCH
        + "), expectedRevision?(缺省 head)}。index 不重排（留洞）；删除后由 AgentLib "
        + "DecisionPackageTransitions.removeItem 递增 contentVersion。"
        + "本 tick 无包 ⇒ PACKAGE_NOT_FOUND；第一条逐条裁决后 ⇒ PACKAGE_CONTENT_FROZEN。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("callIndex", ToolSchema.prop("integer", "要删除的条目 index（必填、整数 ≥ 0）"));
    props.put("tick", ToolSchema.prop("integer", "目标 tick（缺省 = 当前世界 tick）"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision", ToolSchema.prop("integer", "读取/提交的乐观并发 base revision（缺省 = 该分支 head）"));
    return ToolSchema.schema(props, List.of("callIndex"));
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
      long callIndexLong = ToolSchema.requiredLong(args, "callIndex");
      if (callIndexLong < 0L || callIndexLong > Integer.MAX_VALUE) {
        return ToolResult.error("BAD_REQUEST", "callIndex 必须是 ≥ 0 的 int: " + callIndexLong);
      }
      int callIndex = (int) callIndexLong;
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
            "本 tick 没有决策包，无法 drop: "
                + DecisionPackageWriteSupport.packetId(proposerId, tick).value());
      }
      if (!DecisionPackageWriteSupport.contentEditable(existing)) {
        return DecisionPackageWriteSupport.contentFrozen(existing);
      }
      if (!containsIndex(existing, callIndex)) {
        return ToolResult.error(
            "BAD_REQUEST",
            "包里没有 callIndex="
                + callIndex
                + " 的条目（现有 "
                + existing.packageState().pkg().items().size()
                + " 条）");
      }
      DecisionPackageWriteSupport.requireSelfWrite(context, identity, proposerId);
      DecisionPackageSnapshot nextSnapshot =
          DecisionPackageTransitions.removeItem(
              existing.packageState(),
              callIndex,
              existing.packageState().contentVersion(),
              state.meta().timestamp().tick());
      Map<Integer, DecisionCallMeta> nextMeta = new LinkedHashMap<>(existing.callMeta());
      nextMeta.remove(callIndex);
      DecisionRecord next =
          new DecisionRecord(
              existing.id(),
              existing.branch(),
              existing.tick(),
              existing.proposerId(),
              existing.intent(),
              existing.createdAtRevision(),
              existing.confirmedAtRevision(),
              existing.reasonInfoId(),
              existing.decidedAtRevision(),
              nextMeta,
              nextSnapshot);
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("packetId", next.id().value());
      view.put("status", next.view().status().name());
      view.put("packageState", next.packageState().pkg().state().name());
      view.put("callCount", next.packageState().pkg().items().size());
      view.put("droppedCallIndex", callIndex);
      return DecisionPackageWriteSupport.submit(writes, branch, state, existing, next, view);
    } catch (DecisionPackages.RejectionException e) {
      return ToolResult.error(e.code(), e.getMessage());
    } catch (IllegalArgumentException | IllegalStateException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "drop 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static boolean containsIndex(DecisionRecord record, int callIndex) {
    return record.packageState().pkg().items().stream().anyMatch(item -> item.index() == callIndex);
  }

  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.DM);
  }
}
