package io.mosire.simos.ga.tools.write;

import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.decision.PackageState;
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
import io.mosire.simos.ga.spi.ToolResults;
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
import java.util.OptionalLong;
import java.util.Set;

/**
 * {@code simos.ga.packet.reopen}（DM 面）：清空领域 {@code confirmedAtRevision}。
 *
 * <p>设计书 §7.3：{@code Allow}；<b>不改</b> AgentLib {@code PackageState}。已裁决后拒绝（GM 的决定不能借“撤回确认”被抹掉）。
 */
public final class ReopenPacketTool implements SimosTool {

  public static final String NAME = "simos.ga.packet.reopen";

  private static final ResourceManifest GA_WRITE =
      ResourceManifest.of(ToolResources.GA_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private final ToolWritePort writes;
  private final ToolReadPort reads;
  private final GaToolIdentity identity;

  public ReopenPacketTool(ToolWritePort writes, ToolReadPort reads, GaToolIdentity identity) {
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
    return "决策人撤回自己的确认标记（只清领域 confirmedAtRevision；**不改 PackageState**）：参数 "
        + "{tick?(缺省当前 tick), branch?(缺省 "
        + ToolResources.DEFAULT_BRANCH
        + "), expectedRevision?(缺省 head)}。本 tick 无包 ⇒ PACKAGE_NOT_FOUND；已裁决 ⇒ BAD_REQUEST；"
        + "本来未确认 ⇒ 幂等返回。返回 {packetId, status, packageState, reopened, submission?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("tick", ToolSchema.prop("integer", "目标 tick（缺省 = 当前世界 tick）"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision", ToolSchema.prop("integer", "读取/提交的乐观并发 base revision（缺省 = 该分支 head）"));
    return ToolSchema.schema(props, List.of());
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
            "本 tick 没有决策包，无法 reopen: "
                + DecisionPackageWriteSupport.packetId(proposerId, tick).value());
      }
      if (isDecided(existing)) {
        return ToolResult.error(
            "BAD_REQUEST",
            "决策包已被 GM 裁决，不能撤回确认（state="
                + existing.packageState().pkg().state().name()
                + "）："
                + existing.id().value());
      }
      if (existing.confirmedAtRevision().isEmpty()) {
        return ToolResults.ok(view(existing, false));
      }
      DecisionPackageWriteSupport.requireSelfWrite(context, identity, proposerId);
      DecisionRecord next =
          DecisionPackageWriteSupport.withDomainFields(
              existing, existing.intent(), OptionalLong.empty(), existing.reasonInfoId());
      return DecisionPackageWriteSupport.submit(
          writes, branch, state, existing, next, view(next, true));
    } catch (IllegalArgumentException | IllegalStateException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "reopen 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static boolean isDecided(DecisionRecord record) {
    PackageState state = record.packageState().pkg().state();
    return record.packageState().pkg().decision() != null
        || state == PackageState.DECIDED
        || state == PackageState.COMMITTED
        || state == PackageState.REJECTED
        || state == PackageState.CONFLICTED
        || state == PackageState.EXPIRED;
  }

  private static Map<String, Object> view(DecisionRecord record, boolean reopened) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("packetId", record.id().value());
    view.put("status", record.view().status().name());
    view.put("packageState", record.packageState().pkg().state().name());
    view.put("reopened", reopened);
    return view;
  }

  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.DM);
  }
}
