package io.mosire.simos.culture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.culture.change.CultureChangeSet;
import io.mosire.simos.culture.codec.CultureCodec;
import io.mosire.simos.culture.contract.CultureId;
import io.mosire.simos.culture.contract.CultureTag;
import io.mosire.simos.culture.contract.CustomTag;
import io.mosire.simos.culture.contract.HouseholdCulture;
import io.mosire.simos.culture.contract.ReligionId;
import io.mosire.simos.culture.spi.SetHouseholdCultureHandler;
import io.mosire.simos.economy.api.culture.HouseholdCultureKey;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>D-032 RC1（v4 §9）：家户文化自然语言 label 与跨模块只读 key</b>。
 *
 * <p>判据：{@code culture.SetHouseholdCulture} 的可选 {@code label} 被逐值写入、缺省空串、非文本拒绝；{@code
 * CultureData.cultureKeyOf} 给出确定性 key（customs 升序去重、label 原样）；economy 侧只读 key 不认识 culture 实现。
 */
class HouseholdCultureLabelTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final StateRef REF = new StateRef(MAIN, new RevisionId(1L));
  private static final SimosTimestamp AT = SimosTimestamp.of(7L);
  private static final HouseholdId HOUSEHOLD = HouseholdId.parse("hh-culture-label");

  @Test
  void setCulturePayloadCarriesTheNaturalLanguageLabelVerbatim() {
    HandlerOutcome outcome =
        new SetHouseholdCultureHandler()
            .handle(
                emptyCultureState(),
                "{\"householdId\":\""
                    + HOUSEHOLD.value()
                    + "\",\"cultureId\":\"latium\",\"religionId\":\"roman\","
                    + "\"customs\":[\"patrilineal\"],\"label\":\"  本地稻农  \"}");

    assertThat(outcome)
        .as("带 label 的合法载荷必须被接受: %s", outcome)
        .isInstanceOf(HandlerOutcome.Applied.class);
    CultureData state = apply(outcome);
    HouseholdCulture value = state.householdCultures().get(HOUSEHOLD);
    assertThat(value).as("家户文化记录真的落盘").isNotNull();
    assertThat(value.label()).as("★ 用户文本原样保留（不 trim）").isEqualTo("  本地稻农  ");
    assertThat(value.tag().culture()).isEqualTo(new CultureId("latium"));
    assertThat(value.tag().religion()).isEqualTo(new ReligionId("roman"));
    assertThat(value.tag().customs()).containsExactly(new CustomTag("patrilineal"));
  }

  @Test
  void missingLabelDefaultsToEmptyString() {
    CultureData state =
        apply(
            new SetHouseholdCultureHandler()
                .handle(
                    emptyCultureState(),
                    "{\"householdId\":\""
                        + HOUSEHOLD.value()
                        + "\",\"cultureId\":\"latium\",\"religionId\":\"roman\"}"));
    assertThat(state.householdCultures().get(HOUSEHOLD).label()).isEmpty();
  }

  @Test
  void nonTextLabelIsRejectedByName() {
    HandlerOutcome outcome =
        new SetHouseholdCultureHandler()
            .handle(
                emptyCultureState(),
                "{\"householdId\":\""
                    + HOUSEHOLD.value()
                    + "\",\"cultureId\":\"latium\",\"religionId\":\"roman\",\"label\":7}");
    assertThat(outcome).as("非文本 label 必须具名拒绝").isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains("label");
  }

  @Test
  void cultureKeySortsAndDeduplicatesCustomsAndKeepsLabel() {
    HouseholdCulture value =
        new HouseholdCulture(
            HOUSEHOLD,
            new CultureTag(
                new CultureId("latium"),
                new ReligionId("roman"),
                new java.util.LinkedHashSet<>(
                    List.of(new CustomTag("zeta"), new CustomTag("alpha"), new CustomTag("zeta")))),
            "本地稻农");
    CultureData state = new CultureData(Map.of(HOUSEHOLD, value));

    HouseholdCultureKey key = state.cultureKeyOf(HOUSEHOLD).orElseThrow();
    assertThat(key.cultureId()).isEqualTo("latium");
    assertThat(key.religionId()).isEqualTo("roman");
    assertThat(key.customs()).as("★ customs 升序去重、确定性").containsExactly("alpha", "zeta");
    assertThat(key.label()).isEqualTo("本地稻农");
    assertThat(state.cultureKeyOf(HouseholdId.parse("hh-missing")))
        .as("缺户 ⇒ empty，不伪造 key")
        .isEmpty();
  }

  @Test
  void cultureLabelSurvivesCodecAndChangeSetRoundTrip() {
    CultureData state =
        apply(
            new SetHouseholdCultureHandler()
                .handle(
                    emptyCultureState(),
                    "{\"householdId\":\""
                        + HOUSEHOLD.value()
                        + "\",\"cultureId\":\"latium\",\"religionId\":\"roman\",\"label\":\"山地牧民\"}"));
    CultureCodec codec = new CultureCodec();
    CultureSnapshot snapshot = new CultureSnapshot(REF, AT, state);
    assertThat(((CultureSnapshot) codec.decodeSnapshot(codec.encodeSnapshot(snapshot))).state())
        .as("label 过 JSON 快照往返逐值保留")
        .isEqualTo(state);

    CultureData target =
        state.withHouseholdCulture(
            new HouseholdCulture(
                HOUSEHOLD,
                new CultureTag(
                    new CultureId("latium"), new ReligionId("roman"), java.util.Set.of()),
                "本地稻农"));
    CultureChangeSet cs = CultureChangeSet.between(state, target);
    assertThat(CultureChangeSet.apply(cs, state)).as("label 进 ChangeSet 并逐字段重建").isEqualTo(target);
  }

  @Test
  void nullLabelFailsClosed() {
    assertThatThrownBy(
            () ->
                new HouseholdCulture(
                    HOUSEHOLD,
                    new CultureTag(
                        new CultureId("latium"), new ReligionId("roman"), java.util.Set.of()),
                    null))
        .as("label=null 必须拒（缺省用空串）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("label");
  }

  private static CultureData apply(HandlerOutcome outcome) {
    CultureChangeSet changeSet = (CultureChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    return CultureChangeSet.apply(changeSet, CultureData.empty());
  }

  private static SimulationState emptyCultureState() {
    return new SimulationState(
        new StateMeta(REF, AT),
        Map.of("culture", new CultureSnapshot(REF, AT, CultureData.empty())),
        InMemoryInfoSystem.empty());
  }
}
