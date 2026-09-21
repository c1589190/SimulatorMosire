package io.mosire.simos.sd.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.DisclosurePolicy;
import io.mosire.simos.sd.model.ViewScope;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.unit.UnitId;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** D5 判据（N16/N17/R9）：身份模块侧校验、落点白名单、按 actor scope 构造脱敏简报（渠道拿不到全量）。 */
class ChannelAdmissionTest {

  private static final ActorId ACTOR_A = new ActorId("dm-a");
  private static final ActorId ACTOR_B = new ActorId("dm-b");

  @Test
  void forgedActorIsRejectedByTheModule() {
    Set<ActorId> declared = Set.of(ACTOR_A);
    assertThatThrownBy(() -> ChannelAdmission.requireRepresentable(declared, new ActorId("ghost")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("N16")
        .hasMessageContaining("ghost");
    ChannelAdmission.requireRepresentable(declared, ACTOR_A);
  }

  @Test
  void onlyTheTwoLandingPointsAreAllowed() {
    ChannelAdmission.requireLandingPoint("sd.IssueDirective");
    ChannelAdmission.requireLandingPoint("sd.SubmitVerdict");
    assertThatThrownBy(() -> ChannelAdmission.requireLandingPoint("simos.command.submit"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("R9");
  }

  @Test
  void redactedBriefDiffersPerActorAndHidesInvisibleUnits() {
    SdState state = sdState();

    String briefA = ChannelAdmission.redactedBrief(state, ACTOR_A);
    String briefB = ChannelAdmission.redactedBrief(state, ACTOR_B);

    assertThat(briefA).contains("u-a").contains("r-a").doesNotContain("u-b");
    assertThat(briefB).contains("u-b").contains("r-b").doesNotContain("u-a");
    assertThat(briefA).as("两 scope 的简报必须不同（否则 N17 失效）").isNotEqualTo(briefB);
  }

  private static SdState sdState() {
    DecisionMaker a =
        new DecisionMaker(
            new DecisionMakerId("dm-a"),
            new Affiliation.Nation(new NationId("n1")),
            Set.of(),
            new ViewScope(
                Set.of(new RegionId("r-a")),
                Set.of(new HexCoord(1, 1)),
                Set.of(new UnitId("u-a")),
                false,
                DisclosurePolicy.PERCEPTION_ONLY,
                Set.of()),
            1);
    DecisionMaker b =
        new DecisionMaker(
            new DecisionMakerId("dm-b"),
            new Affiliation.Nation(new NationId("n2")),
            Set.of(),
            new ViewScope(
                Set.of(new RegionId("r-b")),
                Set.of(new HexCoord(2, 2)),
                Set.of(new UnitId("u-b")),
                false,
                DisclosurePolicy.PERCEPTION_ONLY,
                Set.of()),
            1);
    return SdState.empty().withDecisionMakers(Map.of(a.id(), a, b.id(), b));
  }
}
