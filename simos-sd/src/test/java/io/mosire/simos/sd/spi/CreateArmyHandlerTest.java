package io.mosire.simos.sd.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.sd.testing.SdFixtures;
import io.mosire.simos.sd.testing.SdWorlds;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import org.junit.jupiter.api.Test;

/** {@code sd.CreateArmy} 的正常 / 拒绝路径（含经 unit 切片读根单位存在性）。 */
class CreateArmyHandlerTest {

  private static final CreateArmyHandler HANDLER = new CreateArmyHandler();

  @Test
  void createsArmyWhenNationAndRootUnitExist() {
    SdState base = SdFixtures.full();
    SdState next =
        applied(
            base,
            SdWorlds.world(base),
            "{\"armyId\":\"a9\",\"nationId\":\"n1\",\"rootUnitId\":\"u-1\",\"name\":\"第九军\"}");
    assertThat(next.armies()).containsKey(new ArmyId("a9"));
    assertThat(next.armies().get(new ArmyId("a9")).rootUnit()).isEqualTo(SdWorlds.ROOT_UNIT);
  }

  @Test
  void rejectsDuplicateArmyId() {
    SdState base = SdFixtures.full();
    HandlerOutcome outcome =
        HANDLER.handle(
            SdWorlds.world(base),
            "{\"armyId\":\"a1\",\"nationId\":\"n1\",\"rootUnitId\":\"u-1\",\"name\":\"重复\"}");
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).isEqualTo("军队已存在: a1");
  }

  @Test
  void rejectsMissingNation() {
    SdState base = SdFixtures.full();
    HandlerOutcome outcome =
        HANDLER.handle(
            SdWorlds.world(base),
            "{\"armyId\":\"a9\",\"nationId\":\"ghost\",\"rootUnitId\":\"u-1\",\"name\":\"第九军\"}");
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).isEqualTo("nationId 不存在: ghost");
  }

  @Test
  void rejectsMissingRootUnit() {
    SdState base = SdFixtures.full();
    HandlerOutcome outcome =
        HANDLER.handle(
            SdWorlds.world(base),
            "{\"armyId\":\"a9\",\"nationId\":\"n1\",\"rootUnitId\":\"ghost\",\"name\":\"第九军\"}");
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).isEqualTo("rootUnitId 不存在: ghost");
  }

  private static SdState applied(SdState base, SimulationState world, String payload) {
    HandlerOutcome outcome = HANDLER.handle(world, payload);
    assertThat(outcome).as("期望 Applied，实际: %s", outcome).isInstanceOf(HandlerOutcome.Applied.class);
    SdChangeSet cs = (SdChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    return SdChangeSet.apply(cs, base);
  }
}
