package io.mosire.simos.sd.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.sd.testing.SdFixtures;
import io.mosire.simos.sd.testing.SdWorlds;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import org.junit.jupiter.api.Test;

/** {@code sd.CreateDecisionMaker} 的正常 / 拒绝路径，并直证 N9（白名单不得含通用写）。 */
class CreateDecisionMakerHandlerTest {

  private static final CreateDecisionMakerHandler HANDLER = new CreateDecisionMakerHandler();

  @Test
  void createsDecisionMakerWhenAffiliationExists() {
    SdState base = SdFixtures.full();
    SdState next =
        applied(
            base,
            SdWorlds.world(base),
            "{\"id\":\"dm9\",\"affiliation\":{\"kind\":\"nation\",\"id\":\"n1\"},"
                + "\"allowedTools\":[\"sd.SubmitVerdict\"],\"cadence\":3}");
    assertThat(next.decisionMakers()).containsKey(new DecisionMakerId("dm9"));
    assertThat(next.decisionMakers().get(new DecisionMakerId("dm9")).allowedTools())
        .containsExactly("sd.SubmitVerdict");
  }

  @Test
  void rejectsDuplicateId() {
    SdState base = SdFixtures.full();
    HandlerOutcome outcome =
        HANDLER.handle(
            SdWorlds.world(base),
            "{\"id\":\"dm1\",\"affiliation\":{\"kind\":\"nation\",\"id\":\"n1\"},"
                + "\"allowedTools\":[],\"cadence\":1}");
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).isEqualTo("决策人已存在: dm1");
  }

  @Test
  void rejectsMissingAffiliationTarget() {
    SdState base = SdFixtures.full();
    HandlerOutcome outcome =
        HANDLER.handle(
            SdWorlds.world(base),
            "{\"id\":\"dm9\",\"affiliation\":{\"kind\":\"nation\",\"id\":\"ghost\"},"
                + "\"allowedTools\":[],\"cadence\":1}");
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains("affiliation 目标不存在");
  }

  @Test
  void rejectsAffiliationToMissingArmy() {
    SdState base = SdFixtures.full();
    HandlerOutcome outcome =
        HANDLER.handle(
            SdWorlds.world(base),
            "{\"id\":\"dm9\",\"affiliation\":{\"kind\":\"army\",\"id\":\"ghost\"},"
                + "\"allowedTools\":[],\"cadence\":1}");
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains("affiliation 目标不存在");
  }

  @Test
  void rejectsGenericWriteInAllowedTools() {
    SdState base = SdFixtures.full();
    HandlerOutcome outcome =
        HANDLER.handle(
            SdWorlds.world(base),
            "{\"id\":\"dm9\",\"affiliation\":{\"kind\":\"nation\",\"id\":\"n1\"},"
                + "\"allowedTools\":[\"simos.command.submit\"],\"cadence\":1}");
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason())
        .as("N9：决策 Agent 不得握通用写")
        .contains("simos.command.submit")
        .contains("N9");
  }

  private static SdState applied(SdState base, SimulationState world, String payload) {
    HandlerOutcome outcome = HANDLER.handle(world, payload);
    assertThat(outcome).as("期望 Applied，实际: %s", outcome).isInstanceOf(HandlerOutcome.Applied.class);
    SdChangeSet cs = (SdChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    return SdChangeSet.apply(cs, base);
  }
}
