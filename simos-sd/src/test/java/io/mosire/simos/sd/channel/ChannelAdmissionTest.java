package io.mosire.simos.sd.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.DisclosurePolicy;
import io.mosire.simos.sd.state.SdState;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** D5 判据（N16/N17/R9）：身份模块侧校验、落点白名单、按 actor 的 accessLimit 构造脱敏简报（渠道拿不到全量）。 */
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

  /**
   * 脱敏简报**逐 actor 不同**（N17 的模块侧那一半）。
   *
   * <p>★ **T9 起简报的内容也变了**（语义变更，不是删字段）：它列的是 actor 的 **GM 额外限制**（前缀图 + 字段级剔除 + 披露档），
   * 不再是旧的"绝对可见集合"——可见集合现在由 app 层范围函数**现算**，sd 看不见它（见 {@code ChannelAdmission#redactedBrief}
   * 的类注）。故断言从 {@code visibleRegions/visibleUnits} 换成前缀内容。
   */
  @Test
  void redactedBriefDiffersPerActorAndCarriesThatActorsLimit() {
    SdState state = sdState();

    String briefA = ChannelAdmission.redactedBrief(state, ACTOR_A);
    String briefB = ChannelAdmission.redactedBrief(state, ACTOR_B);

    assertThat(briefA).contains("Map1/region/r-a").doesNotContain("Map1/region/r-b");
    assertThat(briefB).contains("Map1/region/r-b").doesNotContain("Map1/region/r-a");
    assertThat(briefA).as("披露档随 actor 走").contains("PERCEPTION_ONLY").doesNotContain("FULL");
    assertThat(briefA).as("两 actor 的限制不同 ⇒ 简报必须不同（否则 N17 失效）").isNotEqualTo(briefB);
  }

  /** actor 不存在 ⇒ 简报按**无额外限制**的缺省（fail-closed 在披露档上：{@code WITHHELD}）。 */
  @Test
  void unknownActorGetsTheEmptyLimitAndWithheldDisclosure() {
    String brief = ChannelAdmission.redactedBrief(sdState(), new ActorId("ghost"));
    assertThat(brief).contains("\"accessLimit\":{}").contains("WITHHELD");
  }

  private static SdState sdState() {
    DecisionMaker a =
        new DecisionMaker(
            new DecisionMakerId("dm-a"),
            new Affiliation.Nation(new NationId("n1")),
            Set.of(),
            new AccessLimit(
                Map.of("map", Set.of("Map1/region/r-a"), "unit", Set.of("u-a")),
                Set.of(),
                DisclosurePolicy.PERCEPTION_ONLY),
            1);
    DecisionMaker b =
        new DecisionMaker(
            new DecisionMakerId("dm-b"),
            new Affiliation.Nation(new NationId("n2")),
            Set.of(),
            new AccessLimit(
                Map.of("map", Set.of("Map1/region/r-b"), "unit", Set.of("u-b")),
                Set.of(),
                DisclosurePolicy.PERCEPTION_ONLY),
            1);
    return SdState.empty().withDecisionMakers(Map.of(a.id(), a, b.id(), b));
  }
}
