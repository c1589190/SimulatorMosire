package io.mosire.simos.sd.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.DisclosurePolicy;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.sd.testing.SdFixtures;
import io.mosire.simos.sd.testing.SdWorlds;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.spi.HandlerOutcome;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** D4 判据：GM 配权写进 DecisionMaker（可回放的数据）；dm 不存在 / 载荷非法 ⇒ 拒绝。 */
class SetViewScopeHandlerTest {

  private final SetViewScopeHandler handler = new SetViewScopeHandler();

  @Test
  void writesViewScopeOntoTheDecisionMaker() {
    SdState base = withDecisionMaker(SdState.empty());
    HandlerOutcome outcome = handle(base, payload("dm1"));

    SdState after = applied(base, outcome);
    DecisionMaker maker = after.decisionMakers().get(SdFixtures.DM1);
    assertThat(maker.viewScope().visibleRegions()).containsExactly(new RegionId("r1"));
    assertThat(maker.viewScope().visibleHexes()).containsExactly(new HexCoord(1, 1));
    assertThat(maker.viewScope().visibleUnits()).containsExactly(new UnitId("u-1"));
    assertThat(maker.viewScope().seeOwnUnits()).isTrue();
    assertThat(maker.viewScope().adjudicationDisclosure())
        .isEqualTo(DisclosurePolicy.PERCEPTION_ONLY);
    assertThat(maker.viewScope().redactedFields()).containsExactly("position");
    assertThat(maker.allowedTools())
        .as("配权不动其他字段")
        .isEqualTo(SdFixtures.decisionMaker(SdFixtures.DM1).allowedTools());
    assertThat(maker.decisionCadenceTicks()).isEqualTo(1);
  }

  @Test
  void rejectsUnknownDecisionMaker() {
    HandlerOutcome outcome = handle(SdState.empty(), payload("ghost"));
    assertThat(rejected(outcome)).contains("决策人不存在").contains("ghost");
  }

  @Test
  void rejectsMalformedViewScope() {
    SdState base = withDecisionMaker(SdState.empty());
    String json = "{\"decisionMakerId\":\"dm1\",\"viewScope\":\"FULL\"}";
    HandlerOutcome outcome = handler.handle(SdWorlds.world(base), json);
    assertThat(rejected(outcome)).contains("viewScope");
  }

  @Test
  void rejectsIllegalDisclosurePolicy() {
    SdState base = withDecisionMaker(SdState.empty());
    String json =
        "{\"decisionMakerId\":\"dm1\",\"viewScope\":{\"adjudicationDisclosure\":\"MAYBE\"}}";
    HandlerOutcome outcome = handler.handle(SdWorlds.world(base), json);
    assertThat(rejected(outcome)).contains("adjudicationDisclosure");
  }

  private static SdState withDecisionMaker(SdState base) {
    return base.withDecisionMakers(
        Map.of(SdFixtures.DM1, SdFixtures.decisionMaker(SdFixtures.DM1)));
  }

  private HandlerOutcome handle(SdState base, String payloadJson) {
    return handler.handle(SdWorlds.world(base), payloadJson);
  }

  private static SdState applied(SdState base, HandlerOutcome outcome) {
    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    return SdChangeSet.apply((SdChangeSet) ((HandlerOutcome.Applied) outcome).changeSet(), base);
  }

  private static String rejected(HandlerOutcome outcome) {
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    return ((HandlerOutcome.Rejected) outcome).reason();
  }

  private static String payload(String dm) {
    return "{"
        + "\"decisionMakerId\":\""
        + dm
        + "\",\"viewScope\":{\"visibleRegions\":[\"r1\"],\"visibleHexes\":[{\"q\":1,\"r\":1}],"
        + "\"visibleUnits\":[\"u-1\"],\"seeOwnUnits\":true,"
        + "\"adjudicationDisclosure\":\"PERCEPTION_ONLY\",\"redactedFields\":[\"position\"]}}";
  }
}
