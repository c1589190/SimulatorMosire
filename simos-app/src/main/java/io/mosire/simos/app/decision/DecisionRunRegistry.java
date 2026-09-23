package io.mosire.simos.app.decision;

import io.mosire.simos.app.decision.DecisionAgentRunner.ToolInvocation;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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
 *
 * <p>★★ **2026-09-24（E4）修两条真缺陷**，它们都在"进程内的账"这一层：
 *
 * <ol>
 *   <li>**永停 {@code running}**：一轮若挂死（LLM 调用不返回 / 执行线程随壳关闭而消失 / 进程被杀）就再没人调 {@code finish} ⇒ 读数永远
 *       {@code running}、界面永远转。**修法**：给一轮一个**有界上限** {@link #RUN_TIMEOUT_MS}， 读状态时（{@link
 *       #status}）发现已超上限就**就地归到失败终态**（reason = {@link #REASON_RUN_TIMEOUT}）——不是
 *       "有人刚好轮询到那一刻才处理"，而是**任何一次读**都会结算（超期判据只看"起跑时刻 + 上限"，与读的时机无关）。
 *   <li>**同一决策人连点两次覆盖前一轮的账**：旧 {@code begin} 无条件 {@code put}，第二轮会把第一轮的进度/轨迹整个换掉。 **修法**：起跑改成**预约制**
 *       {@link #tryBegin}——已有一轮**活着在跑**（未超时）⇒ 返回空，调用方**明确拒绝**，
 *       旧账**原样留下**；配额用**令牌（世代）**区分：只有**本轮令牌**能写进度/收工，迟到的老线程写不进新一轮的账。
 * </ol>
 */
public final class DecisionRunRegistry {

  /**
   * 一轮的**有界上限**（E4）：超过它 ⇒ 判定这一轮**已死**，读状态时归到失败终态（reason = {@link #REASON_RUN_TIMEOUT}）。
   *
   * <p>★★ **取值理由（不是随手取的一个数）**：一轮里若决策人出令（{@code sd.IssueDirective} 是敏感写），那次工具调用 会**阻塞式等审批**，上限 = 壳的
   * {@code APPROVAL_TIMEOUT}（{@code Shell} 里 = **5 分钟**）。一轮里可能出现**多次**
   * 这样的等待（每出一次令一次），故本上限**必须严格大于** {@code APPROVAL_TIMEOUT} 并留余量： {@code 30 分钟 = 6 ×
   * APPROVAL_TIMEOUT}——足够容纳"多次等审批 + 每轮 LLM 的自身耗时"，又仍是一个**有界**的读数。
   *
   * <p>★ **不取与 APPROVAL_TIMEOUT 同量级的小值**：那会把一圈**正常**的 run（正在合法地等审批）误判成死——"把活的说成死的"比 "多转一会儿"更坏。
   */
  public static final long RUN_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

  /** 超时终态的 {@code reason} 常量（调用方/用例据它判别"这是超时，不是别的失败"）。 */
  public static final String REASON_RUN_TIMEOUT = "run-timeout";

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

  /**
   * 一条留痕。★ {@code token} 是**起跑时发的令牌（世代）**：只有带着**本轮令牌**进来的 {@code progress}/{@code finish}
   * 才写得进这条账——这样"超时判死后又起了一轮"时，上一轮那条**迟到的**线程（若它后来真的醒了）写不回新一轮的账。
   */
  private record Entry(
      long token,
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
  private final AtomicLong tokens = new AtomicLong();
  private final LongSupplier clock;

  public DecisionRunRegistry() {
    this(System::currentTimeMillis);
  }

  /** 可注入时钟的形态（用例要钉"已 Ns"、要推过 {@link #RUN_TIMEOUT_MS} 就得把时间摁住）。 */
  public DecisionRunRegistry(LongSupplier clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * **尝试起跑**（E4：取代旧的"无条件覆盖"的 {@code begin}）：该决策人**已有一轮活着在跑**（{@code running} 且未超 {@link
   * #RUN_TIMEOUT_MS}）⇒ 返回空，**不动**已有那一轮的账（⇒ 不覆盖）。
   *
   * <p>★ **为什么返回令牌而不是 void**：并发下"第二个人"必须在**落触发事实之前**就被挡住（否则会白落一条世界事实、白涨一个
   * revision）；而"先查再写"两步之间有窗口，故此处用一次 {@code compute} 原子地"看 + 占"，令牌就是那次占位的凭证。
   *
   * <p>★ **已超时的旧账不算"活着"**：判死后可以再起一轮（否则一个真挂死的 run 会让这个人到进程重启前都跑不了）。旧账会被 新的一轮取代——这正是"界面只关心最近一轮"的既有口径。
   *
   * @return 本轮令牌（{@link #progress} / {@link #finish} / {@link #abandon} 要带它）；已有**活的**一轮 ⇒ 空
   */
  public OptionalLong tryBegin(String decisionMakerId) {
    Objects.requireNonNull(decisionMakerId, "decisionMakerId");
    long now = clock.getAsLong();
    long token = tokens.incrementAndGet();
    Entry candidate = new Entry(token, true, 0, List.of(), now, null, null);
    Entry winner =
        byId.compute(
            decisionMakerId, (key, entry) -> isLiveRunning(entry, now) ? entry : candidate);
    return winner == candidate ? OptionalLong.of(token) : OptionalLong.empty();
  }

  /**
   * **释放一次没真正起跑出去的预约**（触发事实落盘失败时）：只在**令牌仍匹配**时移除，回到"没有这一轮记录"。
   *
   * <p>★ 为什么需要它：预约发生在 {@code core.submit} **之前**（为了不白落触发事实），而 submit 可能因冲突/被拒而失败—— 那时这一轮根本没跑，这条
   * {@code running} 的账必须撤掉，否则它会一直显示"正在跑"直到超时（等于把 E4 那条缺陷换个成因复发）。
   */
  public void abandon(String decisionMakerId, long token) {
    Objects.requireNonNull(decisionMakerId, "decisionMakerId");
    byId.computeIfPresent(decisionMakerId, (key, entry) -> entry.token() == token ? null : entry);
  }

  /**
   * 进度（{@link DecisionAgentRunner.ProgressListener} 的落点）：LLM 轮次 + 到目前为止的工具调用。
   *
   * <p>★ **没有这个 id、或令牌不是本轮** ⇒ 忽略（不新造一条、也不动别人的账）：进度只对"已经 {@link #tryBegin}
   * 过的那一轮"有意义；凭空造一条会让读数看起来像"某一轮正在跑"，令牌不符则是"上一轮迟到的线程"（见 {@link Entry}）。
   */
  public void progress(
      String decisionMakerId, long token, int llmCalls, List<ToolInvocation> invocations) {
    if (decisionMakerId == null) {
      return;
    }
    byId.computeIfPresent(
        decisionMakerId,
        (key, entry) ->
            entry.running() && entry.token() == token
                ? new Entry(
                    token,
                    true,
                    llmCalls,
                    invocations,
                    entry.startedAtEpochMs(),
                    null,
                    entry.outcome())
                : entry);
  }

  /**
   * 收工：记结局、置 {@code done}（幂等——重复收工只覆盖结局）。
   *
   * <p>★ **令牌不符 ⇒ 忽略**：已经判超时、且这个决策人又起了新一轮时，上一轮迟到的 {@code finish} **不得**把新一轮的账改掉。
   */
  public void finish(String decisionMakerId, long token, Outcome outcome) {
    Objects.requireNonNull(decisionMakerId, "decisionMakerId");
    Objects.requireNonNull(outcome, "outcome");
    byId.computeIfPresent(
        decisionMakerId,
        (key, entry) ->
            entry.token() == token
                ? new Entry(
                    token,
                    false,
                    entry.llmCalls(),
                    entry.invocations(),
                    entry.startedAtEpochMs(),
                    clock.getAsLong(),
                    outcome)
                : entry);
  }

  /**
   * 读数：没有记录 ⇒ {@link Optional#empty()}（调用方如实显示"暂无"，**不拿 0 / false 顶替**）。
   *
   * <p>★★ **E4 的关键点：读时结算**。若这一轮还标着 {@code running} 却已超 {@link #RUN_TIMEOUT_MS}，本方法**就地**
   * 把它归到失败终态（{@code status=failed} / {@code reason=run-timeout} / 可读 detail）再返回 ⇒ 从此**每次读**都是终态
   * （{@code finishedAtEpochMs} 已定，{@code elapsedMs} 不再涨）。结算**不依赖"读的时机"**：判据只是"起跑时刻 + 上限"，
   * 哪怕中间一次都没人轮询，下一次读照样落到终态。
   *
   * <p>★ 结算用 {@code compute} 做**原子替换**并带令牌校验：并发下若那一轮其实刚好真的收工了，就以**真结局**为准（不被超时覆盖）。
   */
  public Optional<RunStatus> status(String decisionMakerId) {
    if (decisionMakerId == null) {
      return Optional.empty();
    }
    long now = clock.getAsLong();
    Entry entry = byId.compute(decisionMakerId, (key, current) -> settleIfExpired(current, now));
    if (entry == null) {
      return Optional.empty();
    }
    long until = entry.running() ? now : entry.finishedAtEpochMs();
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

  /** 一轮算不算"活着在跑"：还标着 {@code running}、且距起跑未超上限（{@code null} ⇒ 没有这一轮）。 */
  private static boolean isLiveRunning(Entry entry, long now) {
    return entry != null && entry.running() && now - entry.startedAtEpochMs() <= RUN_TIMEOUT_MS;
  }

  /**
   * **读时结算**：跑着但已超上限 ⇒ 换一条**失败终态**的记录；否则**原样返回**（含"没有这一轮"的 {@code null}）。
   *
   * <p>★ 终态的 {@code conversationId} 如实留 {@code null}：registry 是**进程内**的账，看不到世界事实（会话世代在 {@code sd}
   * 切片里）⇒ 编不出来就不编。
   */
  private static Entry settleIfExpired(Entry entry, long now) {
    // ★ 只在"跑着**且已超上限**"时结算；其余（没有记录 / 已收工 / 还没到点）一律原样返回。
    if (entry == null || !entry.running() || now - entry.startedAtEpochMs() <= RUN_TIMEOUT_MS) {
      return entry;
    }
    return new Entry(
        entry.token(),
        false,
        entry.llmCalls(),
        entry.invocations(),
        entry.startedAtEpochMs(),
        now,
        new Outcome(
            "failed",
            REASON_RUN_TIMEOUT,
            "这一轮超过上限 "
                + (RUN_TIMEOUT_MS / 60_000)
                + " 分钟仍在 running（很可能 LLM 调用挂死，或执行线程已随壳关闭而消失）——已判定为超时终态，不再让它永远转下去",
            null,
            null));
  }
}
