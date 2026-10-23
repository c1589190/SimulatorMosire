package io.mosire.simos.app.tools.ga;

import io.mosire.simos.app.decision.DecisionCallPreviewer;
import io.mosire.simos.app.decision.DecisionEffectExecutor;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.ga.model.MergedEffectPlan;
import io.mosire.simos.ga.tools.GaEffectExecution;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>{@link GaEffectExecution} 的 app 实现</b>（2026-10-12 B3 第七域）：把 ga 的镜像记录**逐字段**转成 {@link
 * DecisionEffectExecutor} 的入参、再把结局逐字段转回来。
 *
 * <p>★ <b>为什么执行器留 app</b>：它按工具名取出**真工具实例**去执行（{@code DecisionCallPreviewer.requireTool}）⇒ 持有全部域的 工具
 * = 装配（设计书 §4.5 判据）。ga 侧只负责"从 sd 状态里读出要执行什么、把结局渲染成工具结果"。
 *
 * <p>★ <b>零语义改动</b>：档位、拒因码、{@code outcomes} 的逐条对应、{@code batchId} 的原样回报都由被转调者给；本类不做任何折叠、
 * 不吞任何异常（装配错误照旧抛出，由调用方按既有口径折成 {@code TOOL_ERROR}）。
 */
public final class AppGaEffectExecution implements GaEffectExecution {

  private final DecisionCallPreviewer catalog;
  private final DecisionEffectExecutor executor;

  public AppGaEffectExecution(CoreSimos core, DecisionCallPreviewer catalog) {
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.executor = new DecisionEffectExecutor(Objects.requireNonNull(core, "core"), catalog);
  }

  @Override
  public boolean knownTool(String toolName) {
    return catalog.contains(toolName);
  }

  @Override
  public Result execute(
      SimulationState base,
      String initiator,
      List<Effect> calls,
      Optional<MergedEffectPlan> mergedPlan) {
    List<DecisionEffectExecutor.EffectCall> appCalls = new ArrayList<>(calls.size());
    for (Effect call : calls) {
      appCalls.add(
          new DecisionEffectExecutor.EffectCall(
              call.packetId(),
              call.callIndex(),
              call.toolName(),
              call.argsJson(),
              call.sourceCallRefs()));
    }
    DecisionEffectExecutor.ExecutionResult result =
        executor.execute(base, initiator, appCalls, mergedPlan);
    List<Outcome> outcomes = new ArrayList<>(result.outcomes().size());
    for (DecisionEffectExecutor.CallOutcome outcome : result.outcomes()) {
      DecisionEffectExecutor.EffectCall call = outcome.call();
      outcomes.add(
          new Outcome(
              new Effect(
                  call.packetId(),
                  call.callIndex(),
                  call.toolName(),
                  call.argsJson(),
                  call.sourceCallRefs()),
              outcome.result(),
              outcome.outcomeJson(),
              outcome.commandFrom(),
              outcome.commandTo()));
    }
    return new Result(
        result.batchId(),
        Status.valueOf(result.status().name()),
        result.ref(),
        result.failureCode(),
        result.reason(),
        result.summaryJson(),
        outcomes);
  }
}
