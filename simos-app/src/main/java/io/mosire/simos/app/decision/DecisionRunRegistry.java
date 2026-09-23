package io.mosire.simos.app.decision;

import io.mosire.simos.app.decision.DecisionAgentRunner.ToolInvocation;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * **「某一轮跑到哪儿了」的进程内留痕**（2026-09-23，用户要的"准实时进度"）：GUI 侧那一轮改成**异步**之后， 调用方（浏览器）需要一个地方去问"还在跑吗 / 跑到第几轮 /
 * 调了什么"。
 *
 * <p>★★ **为什么是进程内的**（明确取舍）：这份账是**过程观测**，不是世界事实——进 {@code revision} 会污染世界状态 （铁律 2 管的是世界事实），另存一张表则要动
 * Core 的 DDL（已关账的代码）。这与 {@code /api/sd/run-decision}
 * 那条"轨迹不落盘"的取舍**同源**，代价也一样：**进程重启即失**，界面上表现为"暂无这一轮的记录"（不是"跑完了没调工具"）。
 *
 * <p>★ **key 是决策人 id**（不是会话 id、不是 run 序号）：界面上同一时刻只关心"这个人**最近**那一轮"。两个人各自跑互不干扰。
 *
 * <p>★ **只写内存、不抛异常**：{@code progress} 在跑那一轮的线程上被同步调用（见 {@link DecisionAgentRunner.ProgressListener}
 * 的类注）——它若抛，会把一整轮 LLM 打断。故所有入口对"没有这个 id" 都**安静忽略**（例如上一轮已被清理、或两个请求交叠）。
 */
public final class DecisionRunRegistry {

  /** 一轮的结局（**如实报**，不折成"跑好了"）。 */
  public record Outcome(
      String status, String reason, String detail, String finalText, String conversationId) {

    public Outcome {
      Objects.requireNonNull(status, "status");
    }
  }

  /** 一次读数的快照（不可变；{@code startedAtEpochMs == null} ⇒ **没有这一轮的记录**）。 */
  public record RunStatus(
      String decisionMakerId,
      boolean running,
      boolean done,
      int llmCalls,
      List<ToolInvocation> toolInvocations,
      Long startedAtEpochMs,
      Long elapsedMs,
      Outcome outcome) {

    public RunStatus {
      // ★ 包装写在**构造器体**里（不是辅助方法、不是 accessor）：SpotBugs 的 EI_EXPOSE_REP 只认构造器体内
      //   直接可见的包装调用（本仓纪律形态 7，M4 Task 12 实测）。
      toolInvocations = List.copyOf(toolInvocations);
      Objects.requireNonNull(decisionMakerId, "decisionMakerId");
    }
  }

  private record Entry(
      boolean running,
      int llmCalls,
      List<ToolInvocation> invocations,
      long startedAtEpochMs,
      Long finishedAtEpochMs,
      Outcome outcome) {

    Entry {
      invocations = List.copyOf(invocations);
    }
  }

  private final Map<String, Entry> byId = new ConcurrentHashMap<>();
  private final LongSupplier clock;

  public DecisionRunRegistry() {
    this(System::currentTimeMillis);
  }

  /** 可注入时钟的形态（用例要钉"已 Ns"就得把时间摁住）。 */
  public DecisionRunRegistry(LongSupplier clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** 起跑：把这个人上一轮的账**整个换掉**（同一个 id 只报最近一轮；旧那一轮的去处在会话库与事件库里）。 */
  public void begin(String decisionMakerId) {
    Objects.requireNonNull(decisionMakerId, "decisionMakerId");
    byId.put(decisionMakerId, new Entry(true, 0, List.of(), clock.getAsLong(), null, null));
  }

  /**
   * 进度（{@link DecisionAgentRunner.ProgressListener} 的落点）：LLM 轮次 + 到目前为止的工具调用。
   *
   * <p>★ **没有这个 id 就忽略**（不新造一条）：进度只对"已经 {@link #begin} 过的那一轮"有意义，凭空造一条会让读数 看起来像"某一轮正在跑"。
   */
  public void progress(String decisionMakerId, int llmCalls, List<ToolInvocation> invocations) {
    if (decisionMakerId == null) {
      return;
    }
    byId.computeIfPresent(
        decisionMakerId,
        (key, entry) ->
            entry.running()
                ? new Entry(
                    true, llmCalls, invocations, entry.startedAtEpochMs(), null, entry.outcome())
                : entry);
  }

  /** 收工：记结局、置 {@code done}（幂等——重复收工只覆盖结局）。 */
  public void finish(String decisionMakerId, Outcome outcome) {
    Objects.requireNonNull(decisionMakerId, "decisionMakerId");
    Objects.requireNonNull(outcome, "outcome");
    byId.computeIfPresent(
        decisionMakerId,
        (key, entry) ->
            new Entry(
                false,
                entry.llmCalls(),
                entry.invocations(),
                entry.startedAtEpochMs(),
                clock.getAsLong(),
                outcome));
  }

  /** 读数：没有记录 ⇒ {@link Optional#empty()}（调用方如实显示"暂无"，**不拿 0 / false 顶替**）。 */
  public Optional<RunStatus> status(String decisionMakerId) {
    if (decisionMakerId == null) {
      return Optional.empty();
    }
    Entry entry = byId.get(decisionMakerId);
    if (entry == null) {
      return Optional.empty();
    }
    long until = entry.running() ? clock.getAsLong() : entry.finishedAtEpochMs();
    return Optional.of(
        new RunStatus(
            decisionMakerId,
            entry.running(),
            !entry.running(),
            entry.llmCalls(),
            entry.invocations(),
            entry.startedAtEpochMs(),
            Math.max(0L, until - entry.startedAtEpochMs()),
            entry.outcome()));
  }
}
