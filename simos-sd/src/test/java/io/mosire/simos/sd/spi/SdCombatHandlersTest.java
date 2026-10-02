package io.mosire.simos.sd.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.CombatId;
import io.mosire.simos.sd.id.CombatOutcomeId;
import io.mosire.simos.sd.id.CombatStageId;
import io.mosire.simos.sd.id.CombatStateId;
import io.mosire.simos.sd.model.CasualtySpec;
import io.mosire.simos.sd.model.Combat;
import io.mosire.simos.sd.model.CombatStage;
import io.mosire.simos.sd.model.CombatState;
import io.mosire.simos.sd.model.LossRecord;
import io.mosire.simos.sd.model.OutcomeOption;
import io.mosire.simos.sd.model.OutcomeTable;
import io.mosire.simos.sd.model.Trigger;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.sd.testing.SdWorlds;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** C1/C2/C3 的 handler 级判据：Combat 场/阶段/结局与战损双轨+上界。每条拒绝都断言**命令级理由**（与 {@code SdState} 构造期兜底消息区分）。 */
class SdCombatHandlersTest {

  private static final CombatId C1 = new CombatId("c1");
  private static final CombatStageId S1 = new CombatStageId("s1");
  private static final CombatStageId S2 = new CombatStageId("s2");
  private static final CombatStateId CS1 = new CombatStateId("cs1");
  private static final CombatOutcomeId O1 = new CombatOutcomeId("o1");
  private static final CombatOutcomeId O2 = new CombatOutcomeId("o2");
  private static final UnitId U1 = SdWorlds.ROOT_UNIT;
  private static final UnitId U2 = new UnitId("u-2");

  // ── C1 ──────────────────────────────────────────────────────────────

  @Test
  void createCombatRejectsDanglingParticipant() {
    HandlerOutcome outcome =
        handle(new CreateCombatHandler(), SdState.empty(), createPayload("ghost"));
    assertThat(rejected(outcome)).contains("参与单位不存在");
  }

  @Test
  void createCombatRejectsDuplicateId() {
    SdState base = combatState(List.of(stage(S1, at(0), at(5), O1)));
    HandlerOutcome outcome = handle(new CreateCombatHandler(), base, createPayload("u-1"));
    assertThat(rejected(outcome)).contains("交战已存在");
  }

  @Test
  void createCombatThenFirstStageCreatesTwoLayerCombatState() {
    SdState afterCreate =
        applied(
            SdState.empty(),
            handle(new CreateCombatHandler(), SdState.empty(), createPayload("u-1")));
    assertThat(afterCreate.combats().get(C1).stages()).isEmpty();

    HandlerOutcome outcome =
        handle(new AddStageHandler(), afterCreate, addStagePayload(CS1, S1, at(0), at(5), O1));
    SdState afterStage = applied(afterCreate, outcome);
    assertThat(afterStage.combats().get(C1).stages()).hasSize(1);
    assertThat(afterStage.combats().get(C1).participants()).containsExactly(U1);
    CombatState state = afterStage.combatStates().get(CS1);
    assertThat(state.currentStage()).isEqualTo(S1);
    assertThat(state.participants()).as("阶段层的独立参与单位（spec §三.3 两层）").containsExactly(U2);
  }

  @Test
  void addStageRejectsBrokenChain() {
    SdState base = combatState(List.of(stage(S1, at(0), at(5), O1)));
    HandlerOutcome outcome =
        handle(new AddStageHandler(), base, addStagePayload(null, S2, at(6), at(9), O2));
    assertThat(rejected(outcome)).contains("AddCombatStage：").contains("不相等");
  }

  @Test
  void addStageAcceptsEqualChain() {
    SdState base = combatState(List.of(stage(S1, at(0), at(5), O1)));
    SdState next =
        applied(
            base, handle(new AddStageHandler(), base, addStagePayload(null, S2, at(5), at(9), O2)));
    assertThat(next.combats().get(C1).stages()).hasSize(2);
  }

  @Test
  void addStageRejectsMissingCombatStateOnFirstStage() {
    SdState base = SdState.empty().withCombats(Map.of(C1, combat(List.of())));
    HandlerOutcome outcome =
        handle(new AddStageHandler(), base, addStagePayload(null, S1, at(0), at(5), O1));
    assertThat(rejected(outcome)).contains("combatStateId");
  }

  @Test
  void setOutcomeTableRejectsNonPositiveWeight() {
    SdState base = combatState(List.of(stage(S1, at(0), at(5), O1)));
    HandlerOutcome outcome =
        handle(
            new SetOutcomeTableHandler(),
            base,
            "{\"combatId\":\"c1\",\"stageId\":\"s1\","
                + "\"outcomes\":{\"options\":[{\"id\":\"o1\",\"label\":\"胜\",\"weight\":0}]}}");
    assertThat(rejected(outcome)).contains("weight 必须 > 0");
  }

  @Test
  void setOutcomeTableRejectsEmptyTable() {
    SdState base = combatState(List.of(stage(S1, at(0), at(5), O1)));
    HandlerOutcome outcome =
        handle(
            new SetOutcomeTableHandler(),
            base,
            "{\"combatId\":\"c1\",\"stageId\":\"s1\",\"outcomes\":{\"options\":[]}}");
    assertThat(rejected(outcome)).contains("outcomeTable.options 不得为空");
  }

  @Test
  void setOutcomeTableReplacesWeights() {
    SdState base = combatState(List.of(stage(S1, at(0), at(5), O1)));
    SdState next =
        applied(
            base,
            handle(
                new SetOutcomeTableHandler(),
                base,
                "{\"combatId\":\"c1\",\"stageId\":\"s1\","
                    + "\"outcomes\":{\"options\":[{\"id\":\"o1\",\"label\":\"胜\",\"weight\":3}]}}"));
    assertThat(next.combats().get(C1).stages().get(0).outcomes().options().get(0).weight())
        .isEqualTo(3);
  }

  // ── C2 ──────────────────────────────────────────────────────────────

  @Test
  void commitOutcomeAcceptsAndSelectsExactlyOne() {
    SdState base = combatState(twoStages());
    SdState next = applied(base, handle(new CommitOutcomeHandler(), base, commitPayload(S1, O1)));
    assertThat(next.combatStates().get(CS1).selectedOutcome()).contains(O1);
  }

  @Test
  void commitOutcomeRejectsOutcomeFromAnotherStage() {
    SdState base = combatState(twoStages());
    HandlerOutcome outcome = handle(new CommitOutcomeHandler(), base, commitPayload(S1, O2));
    assertThat(rejected(outcome)).contains("不在该阶段的 outcomeTable 里");
  }

  @Test
  void commitOutcomeRejectsSecondSelection() {
    SdState base =
        combatState(twoStages())
            .withCombatStates(
                Map.of(
                    CS1,
                    new CombatState(
                        CS1, C1, S1, SdWorlds.HEX, Set.of(U1), Optional.of(O1), Set.of())));
    HandlerOutcome outcome = handle(new CommitOutcomeHandler(), base, commitPayload(S1, O1));
    assertThat(rejected(outcome)).contains("已选定结局");
  }

  // ── C3 ──────────────────────────────────────────────────────────────

  @Test
  void recordCasualtiesStoresDeltaWithRevision() {
    SdState base = combatState(twoStages());
    SdState next =
        applied(base, handle(new RecordCasualtiesHandler(), base, casualtiesPayload(-10, -5)));
    assertThat(next.lossRecords()).hasSize(1);
    LossRecord record = next.lossRecords().values().iterator().next();
    assertThat(record.atRevision()).isEqualTo(new RevisionId(1));
    assertThat(record.deltas().get(0).personnel()).isEqualTo(-10);
    assertThat(record.deltas().get(0).equipment()).containsEntry("步枪", -5);
    assertThat(next.combatStates().get(CS1).losses()).containsExactly(record.id());
  }

  @Test
  void recordCasualtiesRejectsPositivePersonnel() {
    HandlerOutcome outcome =
        handle(new RecordCasualtiesHandler(), combatState(twoStages()), casualtiesPayload(1, -5));
    assertThat(rejected(outcome)).contains("personnel 必须 ≤ 0");
  }

  @Test
  void recordCasualtiesRejectsPersonnelOverBound() {
    HandlerOutcome outcome =
        handle(
            new RecordCasualtiesHandler(), combatState(twoStages()), casualtiesPayload(-101, -5));
    assertThat(rejected(outcome)).contains("人员战损超出当前值");
  }

  @Test
  void recordCasualtiesAcceptsExactPersonnelBound() {
    SdState base = combatState(twoStages());
    SdState next =
        applied(base, handle(new RecordCasualtiesHandler(), base, casualtiesPayload(-100, -1)));
    assertThat(next.lossRecords()).hasSize(1);
  }

  @Test
  void recordCasualtiesRejectsEquipmentOverBound() {
    HandlerOutcome outcome =
        handle(new RecordCasualtiesHandler(), combatState(twoStages()), casualtiesPayload(0, -51));
    assertThat(rejected(outcome)).contains("装备战损超出当前值");
  }

  @Test
  void recordCasualtiesRejectsUnknownEquipmentKey() {
    HandlerOutcome outcome =
        handle(
            new RecordCasualtiesHandler(),
            combatState(twoStages()),
            "{\"combatId\":\"c1\",\"stageId\":\"s1\",\"deltas\":[{\"unit\":\"u-1\","
                + "\"equipment\":{\"坦克\":-1},\"lossClass\":\"RECOVERABLE\"}]}");
    assertThat(rejected(outcome)).contains("未知装备类型");
  }

  @Test
  void recordCasualtiesRejectsUnknownUnit() {
    HandlerOutcome outcome =
        handle(
            new RecordCasualtiesHandler(),
            combatState(twoStages()),
            "{\"combatId\":\"c1\",\"stageId\":\"s1\",\"deltas\":[{\"unit\":\"ghost\","
                + "\"personnel\":-1,\"lossClass\":\"PERMANENT\"}]}");
    assertThat(rejected(outcome)).contains("单位不存在");
  }

  // ── 夹具与助手 ───────────────────────────────────────────────────────

  private static HandlerOutcome handle(
      io.mosire.simos.util.spi.CommandHandler handler, SdState base, String payload) {
    return handler.handle(world(base), payload);
  }

  private static SimulationState world(SdState sd) {
    UnitState units = withSecondUnit(SdWorlds.units());
    return SdWorlds.world(sd, SdWorlds.map(), units);
  }

  private static UnitState withSecondUnit(UnitState base) {
    Unit extra =
        new Unit(
            U2,
            "第二连",
            new SegmentedSeries<>(
                List.of(new Segment<>(SdWorlds.T0, Optional.<UnitId>empty())), List.of(), null),
            new SegmentedSeries<>(
                List.of(new Segment<>(SdWorlds.T0, Optional.of(SdWorlds.HEX))), List.of(), null),
            List.of(new CompositionEntry("步兵", 100)),
            List.of(new CompositionEntry("步枪", 50)),
            2,
            500,
            Optional.empty());
    Map<UnitId, Unit> units = new LinkedHashMap<>(base.units());
    units.put(U2, extra);
    return base.withUnits(units);
  }

  private static SdState applied(SdState base, HandlerOutcome outcome) {
    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    return SdChangeSet.apply((SdChangeSet) ((HandlerOutcome.Applied) outcome).changeSet(), base);
  }

  private static String rejected(HandlerOutcome outcome) {
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    return ((HandlerOutcome.Rejected) outcome).reason();
  }

  private static Combat combat(List<CombatStage> stages) {
    return new Combat(C1, "交战一", stages, Set.of(U1), Optional.empty());
  }

  private static List<CombatStage> twoStages() {
    return List.of(stage(S1, at(0), at(5), O1), stage(S2, at(5), at(9), O2));
  }

  private static CombatStage stage(
      CombatStageId id, Trigger entry, Trigger exit, CombatOutcomeId outcome) {
    return new CombatStage(
        id,
        id.value(),
        Set.of(),
        List.of(entry),
        List.of(exit),
        0,
        10,
        new OutcomeTable(
            List.of(new OutcomeOption(outcome, "label", 1, new CasualtySpec(0, Map.of())))));
  }

  private static Trigger at(long tick) {
    return new Trigger.AtOrAfterTick(tick);
  }

  private static SdState combatState(List<CombatStage> stages) {
    return SdState.empty()
        .withCombats(Map.of(C1, combat(stages)))
        .withCombatStates(
            Map.of(
                CS1,
                new CombatState(
                    CS1, C1, S1, SdWorlds.HEX, Set.of(U1), Optional.empty(), Set.of())));
  }

  private static String createPayload(String participant) {
    return "{\"combatId\":\"c1\",\"name\":\"交战一\",\"participants\":[\"" + participant + "\"]}";
  }

  private static String addStagePayload(
      CombatStateId combatStateId,
      CombatStageId stageId,
      Trigger entry,
      Trigger exit,
      CombatOutcomeId outcome) {
    String statePart =
        combatStateId == null
            ? ""
            : "\"combatStateId\":\"" + combatStateId.value() + "\",\"hex\":{\"q\":0,\"r\":0},";
    return "{"
        + statePart
        + "\"combatId\":\"c1\",\"stage\":{\"stageId\":\""
        + stageId.value()
        + "\",\"name\":\""
        + stageId.value()
        + "\",\"participants\":[\"u-2\"],\"entry\":["
        + triggerJson(entry)
        + "],\"exit\":["
        + triggerJson(exit)
        + "],\"outcomes\":{\"options\":[{\"id\":\""
        + outcome.value()
        + "\",\"label\":\"label\",\"weight\":1}]}}}";
  }

  private static String triggerJson(Trigger trigger) {
    if (trigger instanceof Trigger.AtOrAfterTick tick) {
      return "{\"@class\":\"at_or_after_tick\",\"tick\":" + tick.tick() + "}";
    }
    throw new IllegalStateException("本夹具只造 AtOrAfterTick");
  }

  private static String commitPayload(CombatStageId stageId, CombatOutcomeId outcome) {
    return "{\"combatId\":\"c1\",\"stageId\":\""
        + stageId.value()
        + "\",\"selectedOutcomeId\":\""
        + outcome.value()
        + "\"}";
  }

  private static String casualtiesPayload(int personnel, int equipment) {
    return "{\"combatId\":\"c1\",\"stageId\":\"s1\",\"deltas\":[{\"unit\":\"u-1\",\"personnel\":"
        + personnel
        + ",\"equipment\":{\"步枪\":"
        + equipment
        + "},\"lossClass\":\"PERMANENT\"}]}";
  }
}
