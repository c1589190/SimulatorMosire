package io.mosire.simos.ga.tools;

import io.mosire.simos.ga.model.MergedEffectPlan;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * ★★ <b>决策效果执行口</b>（2026-10-12 B3 第 7 域）：把"一批已批准的效果"交给宿主的执行器（{@code
 * DecisionEffectExecutor}），并把结局**镜像**回来。
 *
 * <p>★ <b>为什么是端口</b>：执行器要按工具名取出**真工具实例**去执行（宿主可执行工具面）⇒ 它持有全部域的 工具 = 装配（设计书 §4.5 判据）⇒ 只能住 app。本模块的
 * GM 工具只负责"从 sd 状态里读出要执行什么、把结局渲染成工具结果"。
 *
 * <p>★ <b>三处类型为什么是镜像</b>（{@link Effect} / {@link Outcome} / {@link Result}）：app 的 {@code
 * DecisionEffectExecutor.EffectCall/CallOutcome/ExecutionResult} 进不了本模块；镜像**逐字段同形**（含 {@code
 * sourceCallRefs} 的保序冻结与 {@code outcomes} 的长度对应不变式），转换只在 app 适配器一处 ⇒ 结局档位、拒因码、字段集与迁入前 逐字相同。
 *
 * <p>★ <b>失败语义不许折平</b>：执行器把"规划失败"折在 {@link Result#status()} 里（它不抛）；真正的装配错误（工具不在清单里等）由
 * 实现原样抛出，调用方按既有口径折成 {@code TOOL_ERROR}。
 */
public interface GaEffectExecution {

  /** 一次执行的结局档位（镜像 {@code DecisionEffectExecutor.Status}）。 */
  enum Status {
    COMMITTED,
    REJECTED,
    CONFLICT,
    PLANNING_FAILED
  }

  /**
   * 一条待执行效果。
   *
   * @param packetId 来源 packet id（合并计划效果没有单一来源时 = null）
   * @param callIndex packet 内 callIndex；合并计划效果用 orderedEffects 下标
   * @param toolName 真实工具名（必须在宿主可执行工具面里）
   * @param argsJson 目标工具参数 JSON 对象文本
   * @param sourceCallRefs 来源 call 稳定引用（{@code <packetId>:<callIndex>}），保序
   */
  record Effect(
      String packetId,
      int callIndex,
      String toolName,
      String argsJson,
      List<String> sourceCallRefs) {

    public Effect {
      if (callIndex < 0) {
        throw new IllegalArgumentException("Effect.callIndex 必须 ≥ 0: " + callIndex);
      }
      if (toolName == null || toolName.isBlank()) {
        throw new IllegalArgumentException("Effect.toolName 不得为空白");
      }
      if (argsJson == null) {
        throw new IllegalArgumentException("Effect.argsJson 不得为 null（无参数用 {}）");
      }
      if (sourceCallRefs == null) {
        throw new IllegalArgumentException("Effect.sourceCallRefs 不得为 null（无来源用空表）");
      }
      for (String ref : sourceCallRefs) {
        if (ref == null || ref.isBlank()) {
          throw new IllegalArgumentException("Effect.sourceCallRefs 不得含空白");
        }
      }
      // ★ 保序冻结在赋值处（不用 List.copyOf：本仓明文口径——迭代序不是内容的纯函数）。
      sourceCallRefs = Collections.unmodifiableList(new java.util.ArrayList<>(sourceCallRefs));
    }
  }

  /**
   * 一条 call/effect 的执行结局。
   *
   * @param call 对应的效果（{@code packetId} 可为 null：合并计划效果没有单一来源）
   * @param commandFrom 批内命令区间起点（0-based；empty 时 = -1）
   * @param commandTo 批内命令区间终点（0-based；empty 时 = -1）
   */
  record Outcome(Effect call, String result, String outcomeJson, int commandFrom, int commandTo) {

    public boolean empty() {
      return commandFrom < 0 || commandTo < 0;
    }
  }

  /**
   * 一次执行的结果。
   *
   * @param status 四档结局
   * @param ref {@code COMMITTED} = 新坐标；{@code CONFLICT} = 真实 head；其余 null
   * @param outcomes 成功时逐 call/effect 结局；失败 = 空表（零写入，不伪造 outcome）
   */
  record Result(
      String batchId,
      Status status,
      StateRef ref,
      String failureCode,
      String reason,
      String summaryJson,
      List<Outcome> outcomes) {

    public Result {
      outcomes = Collections.unmodifiableList(new java.util.ArrayList<>(outcomes));
    }

    public boolean committed() {
      return status == Status.COMMITTED;
    }
  }

  /**
   * 执行一批效果。
   *
   * @param base 执行读/写的 base 坐标（branch/expectedRevision/tick 都取它）
   * @param initiator 落盘发起者（装配面给的；proposer 留在 packet/outcome，不冒充执行者）
   * @param calls 按稳定序排好的效果（非空；空表 ⇒ 调用方应先拒）
   * @param mergedPlan 合并计划 apply 时给出（执行器负责把 outcome 写回 plan）；packet.execute 传空
   */
  Result execute(
      SimulationState base,
      String initiator,
      List<Effect> calls,
      Optional<MergedEffectPlan> mergedPlan);

  /** 效果是否可执行的判据：{@link Effect#toolName()} 必须在宿主可执行工具面里（{@code GaEffectExecution.knownTool}）。 */
  boolean knownTool(String toolName);
}
