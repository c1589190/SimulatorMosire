package io.mosire.simos.sd.guard;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.Nation;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.sd.testing.SdWorlds;
import io.mosire.simos.util.spi.MutationGuard;
import io.mosire.simos.util.state.SimulationState;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@link RegionDeleteGuard} 的真值表：带 tag ⇒ 拒；去 tag ⇒ 放行（证明**不是恒拒**）。 */
class RegionDeleteGuardTest {

  private static final MutationGuard GUARD = new RegionDeleteGuard();

  @Test
  void nameIsStable() {
    assertThat(GUARD.name()).isEqualTo("sd.region-delete");
  }

  @Test
  void taggedRegionIsRejected() {
    Optional<String> rejection = reject(SdState.empty(), "r1");
    assertThat(rejection).isPresent();
    assertThat(rejection.get()).contains("r1").contains("国家 tag");
  }

  @Test
  void untaggedRegionIsAllowed() {
    assertThat(reject(SdState.empty(), "r2")).as("去 tag ⇒ 放行（若恒拒，这条与上一条一起红）").isEmpty();
  }

  @Test
  void homeRegionReferenceIsAlsoRejectedEvenWithoutTag() {
    NationId id = new NationId("n9");
    SdState sd =
        SdState.empty().withNations(Map.of(id, new Nation(id, "甲国", SdWorlds.UNTAGGED_REGION, 1)));
    Optional<String> rejection = reject(sd, "r2");
    assertThat(rejection).isPresent();
    assertThat(rejection.get()).contains("r2").contains("homeRegion");
  }

  @Test
  void otherCommandsAreIgnored() {
    SimulationState world = SdWorlds.world(SdState.empty());
    assertThat(GUARD.rejection(world, "unit.RenameUnit", "{\"regionId\":\"r1\"}")).isEmpty();
    assertThat(GUARD.rejection(world, "sd.PutInfo", "{}")).isEmpty();
  }

  @Test
  void missingRegionAndBadPayloadAreAllowed() {
    SimulationState world = SdWorlds.world(SdState.empty());
    assertThat(GUARD.rejection(world, "map.DeleteRegion", "{\"regionId\":\"ghost\"}")).isEmpty();
    assertThat(GUARD.rejection(world, "map.DeleteRegion", "not-json")).isEmpty();
  }

  @Test
  void decisionIsDeterministic() {
    SimulationState world = SdWorlds.world(SdState.empty());
    assertThat(GUARD.rejection(world, "map.DeleteRegion", "{\"regionId\":\"r1\"}"))
        .isEqualTo(GUARD.rejection(world, "map.DeleteRegion", "{\"regionId\":\"r1\"}"));
  }

  private static Optional<String> reject(SdState sd, String regionId) {
    return GUARD.rejection(
        SdWorlds.world(sd), "map.DeleteRegion", "{\"regionId\":\"" + regionId + "\"}");
  }
}
