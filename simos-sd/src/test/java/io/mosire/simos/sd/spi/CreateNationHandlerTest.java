package io.mosire.simos.sd.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.sd.testing.SdFixtures;
import io.mosire.simos.sd.testing.SdWorlds;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import org.junit.jupiter.api.Test;

/** {@code sd.CreateNation} 的正常 / 拒绝路径，并直证 R13 的"该 Region 无国家 tag ⇒ 拒绝"。 */
class CreateNationHandlerTest {

  private static final CreateNationHandler HANDLER = new CreateNationHandler();

  @Test
  void createsNationWhenRegionCarriesANationTag() {
    SdState base = SdState.empty();
    SdState next =
        applied(
            base,
            SdWorlds.world(base),
            "{\"nationId\":\"n9\",\"name\":\"甲国\",\"homeRegionId\":\"r1\",\"adminBudgetPerTick\":5}");
    assertThat(next.nations()).containsKey(new NationId("n9"));
    assertThat(next.nations().get(new NationId("n9")).homeRegion())
        .isEqualTo(SdWorlds.TAGGED_REGION);
  }

  @Test
  void rejectsDuplicateNationId() {
    SdState base = SdFixtures.full();
    HandlerOutcome outcome =
        HANDLER.handle(
            SdWorlds.world(base),
            "{\"nationId\":\"n1\",\"name\":\"重复\",\"homeRegionId\":\"r1\",\"adminBudgetPerTick\":1}");
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).isEqualTo("国家已存在: n1");
  }

  @Test
  void rejectsMissingHomeRegion() {
    SdState base = SdState.empty();
    HandlerOutcome outcome =
        HANDLER.handle(
            SdWorlds.world(base),
            "{\"nationId\":\"n9\",\"name\":\"甲国\",\"homeRegionId\":\"ghost\",\"adminBudgetPerTick\":1}");
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains("homeRegion 不存在: ghost");
  }

  @Test
  void rejectsRegionWithoutNationTag() {
    SdState base = SdState.empty();
    HandlerOutcome outcome =
        HANDLER.handle(
            SdWorlds.world(base),
            "{\"nationId\":\"n9\",\"name\":\"甲国\",\"homeRegionId\":\"r2\",\"adminBudgetPerTick\":1}");
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason())
        .as("拒绝理由必须点名 region（R13）")
        .contains("r2")
        .contains("无国家 tag");
  }

  private static SdState applied(SdState base, SimulationState world, String payload) {
    HandlerOutcome outcome = HANDLER.handle(world, payload);
    assertThat(outcome).as("期望 Applied，实际: %s", outcome).isInstanceOf(HandlerOutcome.Applied.class);
    SdChangeSet cs = (SdChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    return SdChangeSet.apply(cs, base);
  }
}
