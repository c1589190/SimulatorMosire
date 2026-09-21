package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.sd.adjudication.AdjudicationRequest;
import io.mosire.simos.sd.adjudication.AdjudicationSchemas;
import io.mosire.simos.sd.adjudication.Breakpoints;
import io.mosire.simos.sd.adjudication.DecisionAdjudicator;
import io.mosire.simos.sd.adjudication.Judgement;
import io.mosire.simos.sd.adjudication.LlmClient;
import io.mosire.simos.sd.adjudication.LlmDecisionAdjudicator;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.id.VerdictId;
import io.mosire.simos.sd.model.Verdict;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * D3 的端到端验收（N7/N8）：{@code sd.SubmitVerdict} 经**真** {@code CoreSimos.submit} 落 revision，再经**真**
 * {@code Replay} 读回 ⇒ 判决**逐字节相同**、{@code atRevision} 正确；**回放路径不调用任何 LLM 客户端**（调用计数 = 0）； meta 空白 /
 * subject 越面 ⇒ 拒绝且 head 不动。
 */
class SdVerdictFreezeEndToEndTest {

  private static final BranchId MAIN = new BranchId("main");

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private static final String VALID_D1 =
      "{\"stageId\":\"s1\",\"selectedOutcomeId\":\"o1\",\"casualtyDeltas\":[],"
          + "\"rationaleText\":\"强攻\"}";

  @TempDir Path tempDir;

  private CoreSimos core;

  @AfterEach
  void closeCore() {
    if (core != null) {
      core.close();
    }
  }

  @Test
  void verdictSurvivesReplayVerbatimAndReplayNeverCallsTheLlm() {
    CoreSimos core = start();
    AtomicInteger llmCalls = new AtomicInteger();
    LlmClient client =
        request -> {
          llmCalls.incrementAndGet();
          return VALID_D1;
        };
    DecisionAdjudicator adjudicator = new LlmDecisionAdjudicator(client);
    assertThat(
            adjudicator.adjudicate(
                new AdjudicationRequest(
                    "D1", "{}", AdjudicationSchemas.schemaJson(Breakpoints.D1), "[]")))
        .as("非零基线：裁决器确实会调用 LLM（否则下面的 =0 就是恒真）")
        .isInstanceOf(Judgement.Accepted.class);
    int baseline = llmCalls.get();
    assertThat(baseline).isPositive();

    CommandResult result =
        core.submit(envelope(1, json("v1", "D1", "sd:combat.c1", VALID_D1, "m1")));
    assertThat(result).isInstanceOf(CommandResult.Committed.class);
    assertThat(((CommandResult.Committed) result).ref())
        .isEqualTo(new StateRef(MAIN, new RevisionId(2)));

    SdState replayed = sdState(core, 2);
    Verdict verdict = replayed.verdicts().get(new VerdictId("v1"));
    assertThat(verdict.payloadJson()).as("判决载荷逐字节冻结").isEqualTo(VALID_D1);
    assertThat(verdict.atRevision()).isEqualTo(new RevisionId(1));
    assertThat(verdict.meta().promptVersion()).isEqualTo("prompt-1");
    assertThat(sdState(core, 2)).isEqualTo(replayed);
    assertThat(llmCalls.get()).as("N7：回放/读取绝不重跑 LLM").isEqualTo(baseline);
  }

  @Test
  void sameRevisionReplaysToEqualStateTwice() {
    CoreSimos core = start();
    core.submit(envelope(1, json("v1", "D1", "sd:combat.c1", VALID_D1, "m1")));

    StateRef ref = new StateRef(MAIN, new RevisionId(2));
    assertThat(core.replay(ref)).isEqualTo(core.replay(ref));
  }

  @Test
  void blankMetaIsRejectedAndLeavesNoRevision() {
    CoreSimos core = start();
    CommandResult result =
        core.submit(envelope(1, json("v1", "D1", "sd:combat.c1", VALID_D1, " ")));
    assertThat(result).isInstanceOf(CommandResult.Rejected.class);
    assertThat(((CommandResult.Rejected) result).reason()).contains("model");
    assertThat(core.head(MAIN).orElseThrow()).isEqualTo(new RevisionId(1));
  }

  @Test
  void subjectOutsideSurfaceIsRejectedAndLeavesNoRevision() {
    CoreSimos core = start();
    CommandResult result = core.submit(envelope(1, json("v1", "D1", "map:Map1", VALID_D1, "m1")));
    assertThat(result).isInstanceOf(CommandResult.Rejected.class);
    assertThat(core.head(MAIN).orElseThrow()).isEqualTo(new RevisionId(1));
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
    SdSnapshot slice =
        (SdSnapshot) state.module("sd").orElseThrow(() -> new AssertionError("状态里没有 sd 切片"));
    return slice.state();
  }

  private static CommandEnvelope envelope(long expectedRevision, String payload) {
    return new CommandEnvelope(
        "cmd-" + expectedRevision,
        "cmd-" + expectedRevision,
        "agent:test",
        MAIN,
        new RevisionId(expectedRevision),
        "sd.SubmitVerdict",
        payload);
  }

  private static String json(
      String id, String breakpoint, String subject, String payload, String model) {
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("model", model);
    meta.put("promptVersion", "prompt-1");
    meta.put("inputBriefDigest", "digest");
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("verdictId", id);
    root.put("breakpoint", breakpoint);
    root.put("subject", subject);
    root.put("payload", payload);
    root.put("meta", meta);
    try {
      return MAPPER.writeValueAsString(root);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static SimulationState genesis() {
    StateRef ref = new StateRef(MAIN, new RevisionId(1));
    return new SimulationState(
        new StateMeta(ref, SimosTimestamp.of(0)),
        Map.of("sd", new SdSnapshot(ref, SimosTimestamp.of(0), SdState.empty())),
        InMemoryInfoSystem.empty());
  }
}
