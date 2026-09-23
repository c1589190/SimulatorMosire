package io.mosire.simos.sd.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.id.CombatId;
import io.mosire.simos.sd.id.CombatOutcomeId;
import io.mosire.simos.sd.id.CombatStageId;
import io.mosire.simos.sd.id.CombatStateId;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.id.LossRecordId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.id.SdInfoId;
import io.mosire.simos.sd.id.VerdictId;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** ID 三件套（spec §二.1 / 铁律 1）：裸值 {@code toString}、{@code static parse} 往返、空白即抛。 */
class SdIdTest {

  @Test
  void everyIdToStringIsTheBareValueAndParseRoundTrips() {
    assertThat(new NationId("n1").toString()).isEqualTo("n1");
    assertThat(new ArmyId("a1").toString()).isEqualTo("a1");
    assertThat(new CombatId("c1").toString()).isEqualTo("c1");
    assertThat(new CombatStageId("s1").toString()).isEqualTo("s1");
    assertThat(new CombatStateId("cs1").toString()).isEqualTo("cs1");
    assertThat(new CombatOutcomeId("o1").toString()).isEqualTo("o1");
    assertThat(new DecisionMakerId("dm1").toString()).isEqualTo("dm1");
    assertThat(new DirectiveId("d1").toString()).isEqualTo("d1");
    assertThat(new EffectId("e1").toString()).isEqualTo("e1");
    assertThat(new VerdictId("v1").toString()).isEqualTo("v1");
    assertThat(new LossRecordId("l1").toString()).isEqualTo("l1");
    assertThat(new SdInfoId("map:Map1#0").toString()).isEqualTo("map:Map1#0");

    assertThat(NationId.parse("n1")).isEqualTo(new NationId("n1"));
    assertThat(ArmyId.parse("a1")).isEqualTo(new ArmyId("a1"));
    assertThat(CombatId.parse("c1")).isEqualTo(new CombatId("c1"));
    assertThat(CombatStageId.parse("s1")).isEqualTo(new CombatStageId("s1"));
    assertThat(CombatStateId.parse("cs1")).isEqualTo(new CombatStateId("cs1"));
    assertThat(CombatOutcomeId.parse("o1")).isEqualTo(new CombatOutcomeId("o1"));
    assertThat(DecisionMakerId.parse("dm1")).isEqualTo(new DecisionMakerId("dm1"));
    assertThat(DirectiveId.parse("d1")).isEqualTo(new DirectiveId("d1"));
    assertThat(EffectId.parse("e1")).isEqualTo(new EffectId("e1"));
    assertThat(VerdictId.parse("v1")).isEqualTo(new VerdictId("v1"));
    assertThat(LossRecordId.parse("l1")).isEqualTo(new LossRecordId("l1"));
    assertThat(SdInfoId.parse("map:Map1#0")).isEqualTo(new SdInfoId("map:Map1#0"));
  }

  @Test
  void blankAndNullInputsAreRejectedByEveryConstructorAndParse() {
    List<Function<String, ?>> factories =
        List.of(
            NationId::new,
            NationId::parse,
            ArmyId::new,
            ArmyId::parse,
            CombatId::new,
            CombatId::parse,
            CombatStageId::new,
            CombatStageId::parse,
            CombatStateId::new,
            CombatStateId::parse,
            CombatOutcomeId::new,
            CombatOutcomeId::parse,
            DecisionMakerId::new,
            DecisionMakerId::parse,
            DirectiveId::new,
            DirectiveId::parse,
            EffectId::new,
            EffectId::parse,
            VerdictId::new,
            VerdictId::parse,
            LossRecordId::new,
            LossRecordId::parse,
            SdInfoId::new,
            SdInfoId::parse);
    for (Function<String, ?> factory : factories) {
      assertThatThrownBy(() -> factory.apply(""))
          .as("空串必须被拒")
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> factory.apply("  "))
          .as("空白必须被拒")
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> factory.apply(null))
          .as("null 必须被拒")
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
