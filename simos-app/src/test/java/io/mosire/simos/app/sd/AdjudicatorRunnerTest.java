package io.mosire.simos.app.sd;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.sd.adjudication.Breakpoints;
import io.mosire.simos.sd.adjudication.Judgement;
import io.mosire.simos.sd.adjudication.LlmClient;
import io.mosire.simos.sd.adjudication.LlmDecisionAdjudicator;
import io.mosire.simos.sd.adjudication.LlmRequest;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.spi.SubmitVerdictHandler;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** D7 判据：断点合并（D1+D3 一次调用）/ 全断点 7 次调用 / N13 失败不中断 tick / 判决落成真 revision。 */
class AdjudicatorRunnerTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final String COMBAT = "sd:combat.c1";

  @TempDir Path tempDir;

  private CoreSimos core;

  @AfterEach
  void closeCore() {
    if (core != null) {
      core.close();
    }
  }

  @Test
  void d1AndD3AreOneCallAndTheVerdictIsFrozen() {
    CoreSimos core = start();
    FakeLlm fake = new FakeLlm(Set.of());
    AdjudicatorRunner runner = new AdjudicatorRunner(core, new LlmDecisionAdjudicator(fake));

    List<Judgement> results =
        runner.run(
            MAIN,
            new RevisionId(1),
            List.of(Breakpoints.D1, Breakpoints.D3),
            Map.of(Breakpoints.D1, COMBAT));

    assertThat(fake.calls.get()).as("D1 与 D3 合并为同一次调用").isEqualTo(1);
    assertThat(results).hasSize(1);
    assertThat(results.get(0)).isInstanceOf(Judgement.Accepted.class);
    assertThat(core.head(MAIN).orElseThrow()).as("判决落成真 revision").isEqualTo(new RevisionId(2));
    assertThat(sdState(core, 2).verdicts()).hasSize(1);
  }

  @Test
  void allEightBreakpointsNeedSevenCalls() {
    CoreSimos core = start();
    FakeLlm fake = new FakeLlm(Set.of());
    AdjudicatorRunner runner = new AdjudicatorRunner(core, new LlmDecisionAdjudicator(fake));

    List<Judgement> results =
        runner.run(
            MAIN,
            new RevisionId(1),
            List.of(
                Breakpoints.D1,
                Breakpoints.D2,
                Breakpoints.D3,
                Breakpoints.D4,
                Breakpoints.D5,
                Breakpoints.D6,
                Breakpoints.D7,
                Breakpoints.D8),
            Map.of(Breakpoints.D1, COMBAT, Breakpoints.D6, COMBAT));

    assertThat(fake.calls.get()).isEqualTo(7);
    assertThat(results).hasSize(7);
    assertThat(core.head(MAIN).orElseThrow()).as("D1 与 D6 两组各落一条判决").isEqualTo(new RevisionId(3));
  }

  @Test
  void failedBreakpointDoesNotStopTheTick() {
    CoreSimos core = start();
    FakeLlm fake = new FakeLlm(Set.of("D2"));
    AdjudicatorRunner runner = new AdjudicatorRunner(core, new LlmDecisionAdjudicator(fake));

    List<Judgement> results =
        runner.run(
            MAIN,
            new RevisionId(1),
            List.of(Breakpoints.D1, Breakpoints.D3, Breakpoints.D2),
            Map.of(Breakpoints.D1, COMBAT));

    assertThat(fake.calls.get()).as("D1+D3 组与 D2 组各调一次").isEqualTo(2);
    assertThat(results).hasSize(2);
    assertThat(results.get(0)).isInstanceOf(Judgement.Accepted.class);
    assertThat(results.get(1)).as("N13：失败降级，不中断").isInstanceOf(Judgement.Failed.class);
    assertThat(core.head(MAIN).orElseThrow()).as("只有 D1 判决落盘").isEqualTo(new RevisionId(2));
  }

  private CoreSimos start() {
    core = new CoreSimos(new CoreConfig(tempDir, 100, SimosObjectMapper.create()));
    core.register(new SdCodec());
    core.register(new SubmitVerdictHandler());
    core.bootstrapGenesis(genesis());
    return core;
  }

  private static SdState sdState(CoreSimos core, long revision) {
    SimulationState state = core.replay(new StateRef(MAIN, new RevisionId(revision)));
    return ((SdSnapshot) state.module("sd").orElseThrow()).state();
  }

  private static SimulationState genesis() {
    StateRef ref = new StateRef(MAIN, new RevisionId(1));
    return new SimulationState(
        new StateMeta(ref, SimosTimestamp.of(0)),
        Map.of("sd", new SdSnapshot(ref, SimosTimestamp.of(0), SdState.empty())),
        InMemoryInfoSystem.empty());
  }

  private static String payloadFor(String breakpoint) {
    return switch (breakpoint) {
      case "D1", "D3" ->
          "{\"stageId\":\"s1\",\"selectedOutcomeId\":\"o1\",\"casualtyDeltas\":[],"
              + "\"rationaleText\":\"x\"}";
      case "D2" ->
          "{\"directiveId\":\"d1\",\"intentText\":\"x\",\"commands\":[],\"rationaleText\":\"x\"}";
      case "D4" -> "{\"action\":\"ENGAGE\",\"unitIds\":[],\"rationaleText\":\"x\"}";
      case "D5" -> "{\"op\":\"SPLIT\",\"rootUnitId\":\"u1\",\"rationaleText\":\"x\"}";
      case "D6" -> "{\"disposition\":\"HOLD\",\"rationaleText\":\"x\"}";
      default -> "{\"action\":\"NOOP\",\"rationaleText\":\"x\"}";
    };
  }

  private static final class FakeLlm implements LlmClient {

    private final AtomicInteger calls = new AtomicInteger();
    private final Set<String> failFor;

    FakeLlm(Set<String> failFor) {
      this.failFor = failFor;
    }

    @Override
    public String complete(LlmRequest request) {
      calls.incrementAndGet();
      String breakpoint = request.breakpoint().value();
      if (failFor.contains(breakpoint)) {
        throw new IllegalStateException("timeout:" + breakpoint);
      }
      return payloadFor(breakpoint);
    }
  }
}
