package io.mosire.simos.sd.spi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.VerdictId;
import io.mosire.simos.sd.model.Verdict;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.sd.testing.SdWorlds;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.RevisionId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** D3 判据：判决冻结成数据、含 atRevision、载荷逐字节保留；schema / subject / meta 三类拒绝。 */
class SubmitVerdictHandlerTest {

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private static final String VALID_D1 =
      "{\"stageId\":\"s1\",\"selectedOutcomeId\":\"o1\",\"casualtyDeltas\":[],"
          + "\"rationaleText\":\"强攻\"}";

  private final SubmitVerdictHandler handler = new SubmitVerdictHandler();

  @Test
  void freezesAcceptedPayloadVerbatimWithRevision() {
    HandlerOutcome outcome =
        handler.handle(
            SdWorlds.world(SdState.empty()), json("v1", "D1", "sd:combat.c1", VALID_D1, "m1"));

    SdState after = applied(SdState.empty(), outcome);
    Verdict verdict = after.verdicts().get(new VerdictId("v1"));
    assertThat(verdict.breakpoint().value()).isEqualTo("D1");
    assertThat(verdict.subject().canonical()).isEqualTo("sd:combat.c1");
    assertThat(verdict.payloadJson()).as("判决载荷逐字节冻结").isEqualTo(VALID_D1);
    assertThat(verdict.atRevision()).isEqualTo(new RevisionId(1));
    assertThat(verdict.meta().model()).isEqualTo("m1");
  }

  @Test
  void rejectsDuplicateVerdictId() {
    SdState base =
        applied(
            SdState.empty(),
            handler.handle(
                SdWorlds.world(SdState.empty()), json("v1", "D1", "sd:combat.c1", VALID_D1, "m1")));
    HandlerOutcome outcome =
        handler.handle(SdWorlds.world(base), json("v1", "D1", "sd:combat.c1", VALID_D1, "m1"));
    assertThat(rejected(outcome)).contains("判决已存在").contains("v1");
  }

  @Test
  void rejectsBlankMetaFieldByFieldName() {
    HandlerOutcome outcome =
        handler.handle(
            SdWorlds.world(SdState.empty()), json("v1", "D1", "sd:combat.c1", VALID_D1, " "));
    assertThat(rejected(outcome)).contains("model");
  }

  @Test
  void rejectsSubjectOutsideTheBreakpointSurface() {
    HandlerOutcome outcome =
        handler.handle(
            SdWorlds.world(SdState.empty()), json("v1", "D1", "map:Map1", VALID_D1, "m1"));
    assertThat(rejected(outcome)).contains("裁决面").contains("map:Map1");
  }

  @Test
  void rejectsBreakpointThatDoesNotProduceVerdicts() {
    HandlerOutcome outcome =
        handler.handle(
            SdWorlds.world(SdState.empty()), json("v1", "D2", "sd:combat.c1", VALID_D1, "m1"));
    assertThat(rejected(outcome)).contains("裁决面");
  }

  @Test
  void rejectsPayloadThatFailsTheBreakpointSchema() {
    HandlerOutcome outcome =
        handler.handle(
            SdWorlds.world(SdState.empty()),
            json("v1", "D1", "sd:combat.c1", "{\"stageId\":\"s1\"}", "m1"));
    assertThat(rejected(outcome)).contains("rationaleText");
  }

  private static SdState applied(SdState base, HandlerOutcome outcome) {
    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    return SdChangeSet.apply((SdChangeSet) ((HandlerOutcome.Applied) outcome).changeSet(), base);
  }

  private static String rejected(HandlerOutcome outcome) {
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    return ((HandlerOutcome.Rejected) outcome).reason();
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
}
