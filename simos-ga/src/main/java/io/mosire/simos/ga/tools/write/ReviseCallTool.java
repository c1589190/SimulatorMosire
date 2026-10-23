package io.mosire.simos.ga.tools.write;

import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.decision.DecisionPackageSnapshot;
import io.mosire.agentlib.decision.DecisionPackageTransitions;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.decision.PackageItem;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.model.DecisionCallMeta;
import io.mosire.simos.ga.model.DecisionMaker;
import io.mosire.simos.ga.model.DecisionRecord;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.ga.tools.GaProposalHost;
import io.mosire.simos.ga.tools.GaToolIdentity;
import io.mosire.simos.ga.tools.GaToolReads;
import io.mosire.simos.util.spi.CommandTarget;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code simos.ga.packet.revise}（DM 面）：原位替换本 tick 决策包里的一条调用。
 *
 * <p>设计书 §7.3：{@code Allow}；先按与 Ask 入包同一套校验（catalog → 真 preview → scope）重建条目，然后 <b>只由</b> {@link
 * DecisionPackageTransitions#replaceItem} 改 AgentLib 快照，并在同一 {@link DecisionRecord} 中重算 {@link
 * DecisionCallMeta}。第一条逐条裁决后内容冻结 ⇒ {@code PACKAGE_CONTENT_FROZEN}。
 */
public final class ReviseCallTool implements SimosTool {

  public static final String NAME = "simos.ga.packet.revise";

  private static final ResourceManifest GA_WRITE =
      ResourceManifest.of(ToolResources.GA_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private final ToolWritePort writes;
  private final ToolReadPort reads;
  private final DecisionCallValidator validator;
  private final GaToolIdentity identity;

  public ReviseCallTool(
      ToolWritePort writes,
      ToolReadPort reads,
      String mapId,
      GaProposalHost previewer,
      GaToolIdentity identity) {
    this.writes = Objects.requireNonNull(writes, "writes");
    this.reads = Objects.requireNonNull(reads, "reads");
    this.validator = new DecisionCallValidator(mapId, previewer, identity);
    this.identity = Objects.requireNonNull(identity, "identity");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "决策人原位替换本 tick 决策包里的一条调用：参数 {callIndex(必填、整数 ≥ 0), tool?(缺省沿用原 tool), "
        + "args?(缺省沿用原 args), branch?(缺省 "
        + ToolResources.DEFAULT_BRANCH
        + "), expectedRevision?(缺省 head)}。"
        + "改一条与 Ask 入包的校验完全一致（catalog → 真 preview → 逐目标 scope）；成功后由 AgentLib "
        + "DecisionPackageTransitions.replaceItem 递增 contentVersion。"
        + "本 tick 无包 ⇒ PACKAGE_NOT_FOUND；第一条逐条裁决后 ⇒ PACKAGE_CONTENT_FROZEN。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("callIndex", ToolSchema.prop("integer", "要替换的条目 index（必填、整数 ≥ 0）"));
    props.put("tool", ToolSchema.prop("string", "新的工具名（缺省 = 沿用原 tool）"));
    props.put("args", ToolSchema.prop("object", "新的工具参数对象（缺省 = 沿用原 args）"));
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
      String newToolArg = ToolSchema.optionalText(args, "tool", null);
      Map<String, Object> newArgsArg = DecisionCallValidator.optionalMap(args, "args");
      BranchId branch = GaToolReads.branch(args);
      Long expectedRevisionArg = ToolSchema.optionalLong(args, "expectedRevision");
      if (expectedRevisionArg != null && expectedRevisionArg < 0L) {
        return ToolResult.error("BAD_REQUEST", "expectedRevision 不得为负: " + expectedRevisionArg);
      }
      SimulationState state = GaToolReads.stateOfExpectedRevision(reads, args);
      GaState ga = GaToolReads.ga(state);
      DecisionMaker dm = ga.decisionMakers().get(proposerId);
      if (dm == null) {
        return ToolResult.error("FORBIDDEN", "决策人不存在: " + proposerId.value());
      }
      long tick = state.meta().timestamp().tick();
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
            "本 tick 没有决策包，无法 revise: "
                + DecisionPackageWriteSupport.packetId(proposerId, tick).value());
      }
      if (!DecisionPackageWriteSupport.contentEditable(existing)) {
        return DecisionPackageWriteSupport.contentFrozen(existing);
      }
      PackageItem current = itemAt(existing, callIndex);
      if (current == null) {
        return ToolResult.error(
            "BAD_REQUEST",
            "包里没有 callIndex="
                + callIndex
                + " 的条目（现有 "
                + existing.packageState().pkg().items().size()
                + " 条）");
      }
      String toolName = newToolArg == null ? current.toolName() : newToolArg;
      Map<String, Object> innerArgs =
          newArgsArg == null ? new LinkedHashMap<>(current.args()) : newArgsArg;
      DecisionCallValidator.ValidatedCall checked =
          validator.validate(dm, state, toolName, innerArgs, callIndex);
      if (checked.error() != null) {
        return checked.error();
      }
      DecisionPackageWriteSupport.requireSelfWrite(context, identity, proposerId);
      DecisionPackageSnapshot nextSnapshot =
          DecisionPackageTransitions.replaceItem(
              existing.packageState(),
              callIndex,
              checked.item(),
              existing.packageState().contentVersion(),
              state.meta().timestamp().tick());
      Map<Integer, DecisionCallMeta> nextMeta = new LinkedHashMap<>(existing.callMeta());
      nextMeta.put(callIndex, checked.meta());
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
      view.put("callIndex", callIndex);
      view.put("tool", checked.item().toolName());
      view.put("targets", targetsView(checked.meta().targets()));
      view.put("preview", checked.preview());
      return DecisionPackageWriteSupport.submit(writes, branch, state, existing, next, view);
    } catch (DecisionPackages.RejectionException e) {
      return ToolResult.error(e.code(), e.getMessage());
    } catch (IllegalArgumentException | IllegalStateException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "revise 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static PackageItem itemAt(DecisionRecord record, int callIndex) {
    for (PackageItem item : record.packageState().pkg().items()) {
      if (item.index() == callIndex) {
        return item;
      }
    }
    return null;
  }

  private static List<Map<String, Object>> targetsView(List<CommandTarget> targets) {
    List<Map<String, Object>> out = new ArrayList<>(targets.size());
    for (CommandTarget target : targets) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("namespace", target.namespace());
      row.put("path", target.path());
      out.add(row);
    }
    return out;
  }

  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.DM);
  }
}
