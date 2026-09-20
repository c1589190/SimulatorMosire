package io.mosire.simos.unit.spi;

import static io.mosire.simos.unit.spi.SpiFixture.U1;
import static io.mosire.simos.unit.spi.SpiFixture.map;
import static io.mosire.simos.unit.spi.SpiFixture.state;
import static io.mosire.simos.unit.spi.SpiFixture.unitState;
import static io.mosire.simos.unit.spi.SpiFixture.unitWithMovement;
import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.util.spi.HandlerOutcome;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@link SetStatusHandler}（T2 / spec §三.2）：happy path + 未知状态 / 未知 id 拒绝 + 在途不回溯。 */
class SetStatusHandlerTest {

  private static final SetStatusHandler HANDLER = new SetStatusHandler();

  private static UnitState baseState() {
    return unitState(unitWithMovement(Optional.empty()));
  }

  private static UnitState applied(UnitState base, String payloadJson) {
    HandlerOutcome outcome = HANDLER.handle(state(map(), base), payloadJson);
    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    UnitChangeSet changeSet = (UnitChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    return UnitChangeSet.apply(changeSet, base);
  }

  private static String rejected(UnitState base, String payloadJson) {
    HandlerOutcome outcome = HANDLER.handle(state(map(), base), payloadJson);
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    return ((HandlerOutcome.Rejected) outcome).reason();
  }

  @Test
  void typeIsUnitSetStatus() {
    assertThat(HANDLER.type()).isEqualTo("unit.SetStatus");
  }

  @Test
  void setsTheStatusAndLeavesEverythingElseUntouched() {
    UnitState base = baseState();
    UnitState next = applied(base, "{\"id\":\"u-1\",\"status\":\"RESTING\"}");

    assertThat(next.units().get(U1).status()).isEqualTo(UnitStatus.RESTING);
    assertThat(next.units().get(U1).name()).isEqualTo(base.units().get(U1).name());
    assertThat(next.units().get(U1).member()).isEqualTo(base.units().get(U1).member());
    assertThat(next.units().get(U1).movement()).isEqualTo(base.units().get(U1).movement());
  }

  @Test
  void unknownStatusStringIsRejected() {
    assertThat(rejected(baseState(), "{\"id\":\"u-1\",\"status\":\"SLEEPING\"}"))
        .contains("不是合法状态");
  }

  @Test
  void missingOrNonTextualStatusIsRejected() {
    assertThat(rejected(baseState(), "{\"id\":\"u-1\"}")).contains("status");
    assertThat(rejected(baseState(), "{\"id\":\"u-1\",\"status\":42}")).contains("status");
  }

  @Test
  void unknownUnitIsRejected() {
    assertThat(rejected(baseState(), "{\"id\":\"u-404\",\"status\":\"MOVING\"}")).contains("单位不存在");
  }

  @Test
  void sameStatusIsAppliedWithEmptyChangeSet() {
    UnitState base = baseState();
    HandlerOutcome outcome =
        HANDLER.handle(state(map(), base), "{\"id\":\"u-1\",\"status\":\"MOVING\"}");
    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    assertThat(((UnitChangeSet) ((HandlerOutcome.Applied) outcome).changeSet()).isEmpty()).isTrue();
  }
}
