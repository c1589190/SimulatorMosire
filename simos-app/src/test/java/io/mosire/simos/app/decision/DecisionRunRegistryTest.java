package io.mosire.simos.app.decision;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.app.decision.DecisionAgentRunner.ToolInvocation;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * {@link DecisionRunRegistry} 的两条 E4 缺陷的判别性用例（**可注入时钟** ⇒ 不靠"把生产超时调成 1ms"来凑）。
 *
 * <p>★ 两条各能把"修之前"的行为钉红：
 *
 * <ul>
 *   <li>**永停 {@code running}**：修之前 {@code status()} 对 {@code running} 的记录**只有**"running"这一种读数（没有最
 *       后期限）。用例把时钟推过 {@link DecisionRunRegistry#RUN_TIMEOUT_MS} 再读 ⇒ 修之前 {@code done=false}、断言
 *       {@code done=true} 当场红。
 *   <li>**连点两次覆盖前一轮的账**：修之前 {@code begin} 无条件覆盖。用例起第二轮并断言**被拒**、且第一轮的读数原样 ⇒ 修之前"第二轮开了"、断言 {@code
 *       empty} 当场红。
 * </ul>
 */
class DecisionRunRegistryTest {

  /** 可推的时钟（毫秒）：用例把"时间"攥在手里，不必真的等 30 分钟。 */
  private static final class TickClock {
    private long now;

    long now() {
      return now;
    }

    void advance(long millis) {
      now += millis;
    }
  }

  private static ToolInvocation call(String id) {
    return new ToolInvocation(id, "simos.map.hex", true, null, "summary-" + id);
  }

  @Test
  void aHungRoundIsSettledToTheTimeoutTerminalStateByTheNextRead() {
    TickClock clock = new TickClock();
    DecisionRunRegistry registry = new DecisionRunRegistry(clock::now);
    OptionalLong token = registry.tryBegin("dm-fra");
    assertThat(token.isPresent()).as("首次起跑必须占得到位").isTrue();
    registry.progress("dm-fra", token.getAsLong(), 3, List.of(call("c1")));

    // 还没到点 ⇒ 如实报"正在跑"（这条同时钉住"别把正常的一轮误判成死"）。
    DecisionRunRegistry.RunStatus running = registry.status("dm-fra").orElseThrow();
    assertThat(running.running()).isTrue();
    assertThat(running.done()).isFalse();
    assertThat(running.outcome()).as("还在跑 ⇒ 没有结局").isNull();

    // ★ 推过上限（不早不晚，正好 +1ms）——**没有任何人调 finish**（这一轮挂死了）。
    clock.advance(DecisionRunRegistry.RUN_TIMEOUT_MS + 1);
    DecisionRunRegistry.RunStatus timedOut = registry.status("dm-fra").orElseThrow();

    assertThat(timedOut.running()).as("★ 挂死一轮**不许**永停 running").isFalse();
    assertThat(timedOut.done()).as("★ 超上限 ⇒ 必须归到终态").isTrue();
    assertThat(timedOut.outcome()).as("终态必须带一个结局（不许静默消失）").isNotNull();
    assertThat(timedOut.outcome().status()).isEqualTo("failed");
    assertThat(timedOut.outcome().reason()).isEqualTo(DecisionRunRegistry.REASON_RUN_TIMEOUT);
    assertThat(timedOut.outcome().detail()).as("终态必须带**可读原因**").contains("30 分钟");
    assertThat(timedOut.llmCalls()).as("结算不丢已有进度（3 次 LLM 的账还在）").isEqualTo(3);
    assertThat(timedOut.toolInvocations())
        .extracting(ToolInvocation::toolCallId)
        .containsExactly("c1");

    // ★★ 结算**不是"只在那一瞬处理"**：此后每次读都是同一个终态、时长不再涨。
    long elapsedAtSettle = timedOut.elapsedMs();
    clock.advance(60_000L);
    DecisionRunRegistry.RunStatus still = registry.status("dm-fra").orElseThrow();
    assertThat(still.done()).isTrue();
    assertThat(still.outcome().reason()).isEqualTo(DecisionRunRegistry.REASON_RUN_TIMEOUT);
    assertThat(still.elapsedMs()).as("终态的时长已定死（结算过一次，不是每次读都重算）").isEqualTo(elapsedAtSettle);
  }

  @Test
  void aRoundAtTheExactBoundaryIsStillRunningAndOnlyTheNextMillisecondTimesItOut() {
    TickClock clock = new TickClock();
    DecisionRunRegistry registry = new DecisionRunRegistry(clock::now);
    registry.tryBegin("dm-fra");

    clock.advance(DecisionRunRegistry.RUN_TIMEOUT_MS);
    assertThat(registry.status("dm-fra").orElseThrow().running())
        .as("正好在上限上 ⇒ 还没死（留活口，免得把合法等审批的一轮误判）")
        .isTrue();

    clock.advance(1L);
    assertThat(registry.status("dm-fra").orElseThrow().done()).as("越过上限 1ms ⇒ 结算").isTrue();
  }

  @Test
  void aSecondRunForTheSameMakerIsRejectedAndTheFirstAccountIsKept() {
    TickClock clock = new TickClock();
    DecisionRunRegistry registry = new DecisionRunRegistry(clock::now);

    OptionalLong first = registry.tryBegin("dm-fra");
    assertThat(first.isPresent()).isTrue();
    registry.progress("dm-fra", first.getAsLong(), 4, List.of(call("c1"), call("c2")));

    // ★★ 连点第二次：**必须被拒**，且**第一轮的账原封不动**（写成"覆盖"必红）。
    OptionalLong second = registry.tryBegin("dm-fra");
    assertThat(second.isEmpty()).as("★ 已有一轮活着在跑 ⇒ 第二次起跑必须被拒（不许静默覆盖）").isTrue();

    DecisionRunRegistry.RunStatus kept = registry.status("dm-fra").orElseThrow();
    assertThat(kept.running()).isTrue();
    assertThat(kept.llmCalls()).as("★ 第一轮的进度账没被第二次覆盖").isEqualTo(4);
    assertThat(kept.toolInvocations()).hasSize(2);
  }

  @Test
  void aRunMayStartAgainOnceThePreviousReachedATerminalState() {
    DecisionRunRegistry registry = new DecisionRunRegistry(() -> 0L);
    OptionalLong first = registry.tryBegin("dm-fra");
    registry.finish(
        "dm-fra",
        first.getAsLong(),
        new DecisionRunRegistry.Outcome("ok", null, null, "done", "cid"));

    assertThat(registry.tryBegin("dm-fra").isPresent()).as("上一轮已收工 ⇒ 允许再起一轮（既有'重跑'行为不回退）").isTrue();
  }

  @Test
  void aStaleThreadFromATimedOutRoundCannotTouchTheNewRoundsAccount() {
    TickClock clock = new TickClock();
    DecisionRunRegistry registry = new DecisionRunRegistry(clock::now);
    OptionalLong stale = registry.tryBegin("dm-fra");
    long staleToken = stale.getAsLong();
    registry.progress("dm-fra", staleToken, 5, List.of(call("c1")));

    // 判死（读时结算）⇒ 允许再起一轮。
    clock.advance(DecisionRunRegistry.RUN_TIMEOUT_MS + 1);
    assertThat(registry.status("dm-fra").orElseThrow().done()).isTrue();
    OptionalLong fresh = registry.tryBegin("dm-fra");
    assertThat(fresh.isPresent()).as("判死之后可以再起一轮（否则挂死一次就把这个人锁死到进程重启）").isTrue();
    long freshToken = fresh.getAsLong();
    assertThat(freshToken).isNotEqualTo(staleToken);

    // ★ 上一轮那条**迟到的**线程若真的醒了，带着旧令牌写不进来。
    registry.progress("dm-fra", staleToken, 99, List.of(call("stale")));
    registry.finish(
        "dm-fra",
        staleToken,
        new DecisionRunRegistry.Outcome("ok", null, null, "迟到的老结局", "stale-cid"));

    DecisionRunRegistry.RunStatus now = registry.status("dm-fra").orElseThrow();
    assertThat(now.running()).as("新一轮仍在跑（没被老线程的 finish 收掉）").isTrue();
    assertThat(now.llmCalls()).as("新一轮的账没被老线程的进度污染").isZero();
    assertThat(now.toolInvocations()).isEmpty();
  }

  @Test
  void anAbandonedReservationLeavesNoRecord() {
    DecisionRunRegistry registry = new DecisionRunRegistry(() -> 0L);
    OptionalLong token = registry.tryBegin("dm-fra");
    assertThat(registry.status("dm-fra")).isNotEmpty();

    // 触发事实没落盘 ⇒ 撤位：不能留下一条"一直在跑"的假账。
    registry.abandon("dm-fra", token.getAsLong());
    assertThat(registry.status("dm-fra")).as("撤位后回到'没有这一轮记录'").isEmpty();
    assertThat(registry.tryBegin("dm-fra").isPresent()).as("撤位后可以重新发起").isTrue();
  }

  @Test
  void statusForAnUnknownMakerIsEmptyAndNeverFabricated() {
    DecisionRunRegistry registry = new DecisionRunRegistry(() -> 0L);
    Optional<DecisionRunRegistry.RunStatus> none = registry.status("dm-nobody");
    assertThat(none).isEmpty();
  }
}
