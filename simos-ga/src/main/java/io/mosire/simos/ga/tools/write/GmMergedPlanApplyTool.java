package io.mosire.simos.ga.tools.write;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.id.MergedEffectPlanId;
import io.mosire.simos.ga.model.MergedEffect;
import io.mosire.simos.ga.model.MergedEffectPlan;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.tools.GaEffectExecution;
import io.mosire.simos.ga.tools.GaToolReads;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code simos.gm.mergedPlan.apply}（D3 契约 §8）：执行一个合并效果计划——按 {@code orderedEffects} 顺序，一条 revision。
 *
 * <p>★ 只接受**尚未有 outcome** 的计划（重复 apply ⇒ 具名拒）；执行走 {@link DecisionEffectExecutor} （内部组批 → 同批 outcome
 * 回写 → 一次 {@code submitBatch}）。返回逐 effect outcome。
 *
 * <p>★ 只在 GM 桶；工具名不是命令类型 ⇒ 不进 catalog / PAYLOAD_HINTS；资源声明 sd UNRESTRICTED；敏感写。
 */
public final class GmMergedPlanApplyTool implements SimosTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.gm.mergedPlan.apply";

  private static final ResourceManifest GA_WRITE =
      ResourceManifest.of(ToolResources.GA_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private final ToolReadPort reads;
  private final String initiator;
  private final GaEffectExecution executor;

  public GmMergedPlanApplyTool(ToolReadPort reads, String initiator, GaEffectExecution executor) {
    this.reads = Objects.requireNonNull(reads, "reads");
    this.initiator = Objects.requireNonNull(initiator, "initiator");
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 执行合并效果计划（写）：参数 {planId(必填), branch?(缺省 "
        + ToolResources.DEFAULT_BRANCH
        + "), expectedRevision?(缺省 head)}。只接受尚未有 outcome 的计划；按 orderedEffects 顺序经内部执行器"
        + "（内部组批 + 同批 outcome 回写）提交一条 revision；返回 {planId, tick, batchId, revision?, "
        + "effects:[{effectIndex, tool, sourceCallRefs, result, commandFrom?, commandTo?}], submission?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("planId", ToolSchema.prop("string", "合并效果计划 id（必填）"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop("integer", "提交的乐观并发 base revision（缺省 = 该分支 head；给出必须 ≥ 0）"));
    return ToolSchema.schema(props, List.of("planId"));
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
    return new ToolGate.Ask(
        name(), "执行合并计划 planId=" + context.arguments().get("planId"), AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      ToolResources.requireAll(
          context, Operation.WRITE, List.of(ResourceId.of(ToolResources.GA_NAMESPACE, "*")));
      Map<String, Object> args = context.arguments();
      String planId = ToolSchema.requiredText(args, "planId");
      Long expectedArg = ToolSchema.optionalLong(args, "expectedRevision");
      if (expectedArg != null && expectedArg < 0L) {
        return ToolResult.error("BAD_REQUEST", "expectedRevision 不得为负: " + expectedArg);
      }
      SimulationState state = GaToolReads.stateOfExpectedRevision(reads, args);
      GaState ga = GaToolReads.ga(state);
      MergedEffectPlan plan = ga.mergedEffectPlans().get(MergedEffectPlanId.parse(planId));
      if (plan == null) {
        return ToolResult.error("NOT_FOUND", "合并计划不存在: " + planId);
      }
      if (plan.outcome().isPresent()) {
        return ToolResult.error("BAD_REQUEST", "合并计划已有 outcome，拒绝重复 apply: " + planId);
      }
      if (plan.orderedEffects().isEmpty()) {
        return ToolResult.error("BAD_REQUEST", "合并计划没有 orderedEffects，无可执行效果: " + planId);
      }
      List<GaEffectExecution.Effect> calls = new ArrayList<>(plan.orderedEffects().size());
      for (int i = 0; i < plan.orderedEffects().size(); i++) {
        MergedEffect effect = plan.orderedEffects().get(i);
        calls.add(
            new GaEffectExecution.Effect(
                null, i, effect.toolName(), effect.argsJson(), effect.sourceCallRefs()));
      }
      GaEffectExecution.Result result =
          executor.execute(state, initiator, calls, Optional.of(plan));
      return toToolResult(plan, result);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      throw e; // 资源拒因原样逃到 ToolCallAuthorizer 边界
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "执行合并计划失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static ToolResult toToolResult(MergedEffectPlan plan, GaEffectExecution.Result result) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("planId", plan.id().value());
    view.put("tick", plan.tick());
    view.put("batchId", result.batchId());
    if (result.committed()) {
      view.put("revision", result.ref().revision().value());
      List<Map<String, Object>> effects = new ArrayList<>(result.outcomes().size());
      for (GaEffectExecution.Outcome outcome : result.outcomes()) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("effectIndex", outcome.call().callIndex());
        row.put("tool", outcome.call().toolName());
        row.put("sourceCallRefs", outcome.call().sourceCallRefs());
        row.put("result", outcome.result());
        if (!outcome.empty()) {
          row.put("commandFrom", outcome.commandFrom());
          row.put("commandTo", outcome.commandTo());
        }
        effects.add(row);
      }
      view.put("effects", effects);
      view.put("outcome", result.summaryJson());
      view.put(
          "submission",
          ToolResults.committedView(result.ref(), result.batchId(), result.batchId()));
      return ToolResults.ok(view);
    }
    view.put("reason", result.reason());
    Map<String, Object> submission = new LinkedHashMap<>();
    if (result.status() == GaEffectExecution.Status.CONFLICT) {
      submission.put("result", "conflict");
      submission.put("current", ToolResults.stateRef(result.ref()));
      view.put("submission", submission);
      return ToolResult.error("CONFLICT", ToolResults.json(view));
    }
    submission.put("result", "rejected");
    submission.put("reason", result.reason());
    submission.put("commandId", result.batchId());
    submission.put("correlationId", result.batchId());
    view.put("submission", submission);
    return ToolResult.error(
        result.failureCode() == null ? "REJECTED" : result.failureCode(), ToolResults.json(view));
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}
