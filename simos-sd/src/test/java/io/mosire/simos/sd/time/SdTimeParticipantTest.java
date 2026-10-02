package io.mosire.simos.sd.time;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.CombatId;
import io.mosire.simos.sd.id.CombatOutcomeId;
import io.mosire.simos.sd.id.CombatStageId;
import io.mosire.simos.sd.id.CombatStateId;
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.id.LossRecordId;
import io.mosire.simos.sd.model.Action;
import io.mosire.simos.sd.model.CasualtyDelta;
import io.mosire.simos.sd.model.CasualtySpec;
import io.mosire.simos.sd.model.Combat;
import io.mosire.simos.sd.model.CombatStage;
import io.mosire.simos.sd.model.CombatState;
import io.mosire.simos.sd.model.Effect;
import io.mosire.simos.sd.model.EffectKind;
import io.mosire.simos.sd.model.EffectStatus;
import io.mosire.simos.sd.model.LossClass;
import io.mosire.simos.sd.model.LossRecord;
import io.mosire.simos.sd.model.OutcomeOption;
import io.mosire.simos.sd.model.OutcomeTable;
import io.mosire.simos.sd.model.Trigger;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.sd.testing.SdWorlds;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.spi.TimeProposal;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** C4 的 participant 级判据：R6 延期效果（未达成不产生 / 达成只产生一次）与 N1 阶段推进（AtOrAfterTick / ThresholdKills 各实测）。 */
class SdTimeParticipantTest {

  private static final CombatId C1 = new CombatId("c1");
  private static final CombatStageId S1 = new CombatStageId("s1");
  private static final CombatStageId S2 = new CombatStageId("s2");
  private static final CombatStageId S3 = new CombatStageId("s3");
  private static final CombatStateId CS1 = new CombatStateId("cs1");
  private static final CombatOutcomeId O1 = new CombatOutcomeId("o1");
  private static final CombatOutcomeId O2 = new CombatOutcomeId("o2");
  private static final EffectId E1 = new EffectId("e1");
  private static final String MAP_ID = "Map1";

  private final SdTimeParticipant participant = new SdTimeParticipant(MAP_ID);

  @Test
  void noUpperBoundYieldsEmptyProposal() {
    TimeProposal proposal =
        participant.simulate(
            SdWorlds.world(effectBase(EffectStatus.PLANNED)), TimeRange.since(SdWorlds.T0));
    assertThat(((SdChangeSet) proposal.changeSet()).isEmpty()).isTrue();
    assertThat(proposal.reads()).isEmpty();
    assertThat(proposal.writes()).isEmpty();
  }

  @Test
  void effectDoesNotFireBeforeTrigger() {
    SdState base = effectBase(EffectStatus.PLANNED);
    TimeProposal proposal = participant.simulate(SdWorlds.world(base), range(3, 4));
    assertThat(after(base, proposal).effects().get(E1).status()).isEqualTo(EffectStatus.PLANNED);
    assertThat(((SdChangeSet) proposal.changeSet()).isEmpty()).isTrue();
  }

  @Test
  void effectFiresOnceAtTrigger() {
    SdState base = effectBase(EffectStatus.PLANNED);
    TimeProposal proposal = participant.simulate(SdWorlds.world(base), range(4, 5));
    assertThat(after(base, proposal).effects().get(E1).status()).isEqualTo(EffectStatus.FIRED);
  }

  @Test
  void effectDoesNotRepeatOnLaterTick() {
    SdState base = effectBase(EffectStatus.FIRED);
    TimeProposal proposal = participant.simulate(SdWorlds.world(base), range(5, 6));
    assertThat(((SdChangeSet) proposal.changeSet()).isEmpty()).as("R6：达成后再推进不重复产生").isTrue();
  }

  @Test
  void readsAndWritesAreExplicitCanonicalAddresses() {
    SdState base = effectBase(EffectStatus.PLANNED);
    TimeProposal proposal = participant.simulate(SdWorlds.world(base), range(4, 5));
    assertThat(proposal.reads()).containsExactly("sd:effect.e1");
    assertThat(proposal.writes()).containsExactly("sd:effect.e1");
  }

  @Test
  void stageAdvancesWhenAtOrAfterTickExitIsSatisfied() {
    SdState base = stageBase(new Trigger.AtOrAfterTick(5), new Trigger.AtOrAfterTick(5));
    assertThat(after(base, simulate(base, 3, 4)).combatStates().get(CS1).currentStage())
        .as("到点前不动")
        .isEqualTo(S1);
    assertThat(after(base, simulate(base, 5, 6)).combatStates().get(CS1).currentStage())
        .as("到点后推进到下一阶段")
        .isEqualTo(S2);
  }

  @Test
  void stageAdvancesWhenThresholdKillsExitIsSatisfied() {
    SdState base =
        stageBase(new Trigger.ThresholdKills(10), new Trigger.ThresholdKills(10), lossRecord(-10));
    assertThat(after(base, simulate(base, 1, 2)).combatStates().get(CS1).currentStage())
        .as("累计击杀 10 ≥ 阈值 10")
        .isEqualTo(S2);
  }

  @Test
  void setStageActionMovesCurrentStage() {
    SdState base = stageBase(new Trigger.AtOrAfterTick(100), new Trigger.AtOrAfterTick(100));
    Effect effect =
        new Effect(
            E1,
            EffectKind.SCHEDULED,
            new Trigger.AtOrAfterTick(5),
            new Action.SetStage(CS1, S2),
            EffectStatus.PLANNED,
            0);
    SdState withEffect = base.withEffects(Map.of(E1, effect));
    SdState next = after(withEffect, simulate(withEffect, 4, 5));
    assertThat(next.combatStates().get(CS1).currentStage()).isEqualTo(S2);
    assertThat(next.effects().get(E1).status()).isEqualTo(EffectStatus.FIRED);
  }

  @Test
  void putInfoActionAppendsInfoEntry() {
    Effect effect =
        new Effect(
            E1,
            EffectKind.SCHEDULED,
            new Trigger.AtOrAfterTick(5),
            new Action.PutInfo(Address.parse("map:Map1"), "k", "v"),
            EffectStatus.PLANNED,
            0);
    SdState base = SdState.empty().withEffects(Map.of(E1, effect));
    SdState next = after(base, simulate(base, 4, 5));
    assertThat(next.info().get("map:Map1")).hasSize(1);
    assertThat(next.info().get("map:Map1").get(0).key()).isEqualTo("k");
  }

  @Test
  void firedPutInfoEffectDoesNotAppendAgain() {
    Effect effect =
        new Effect(
            E1,
            EffectKind.SCHEDULED,
            new Trigger.AtOrAfterTick(5),
            new Action.PutInfo(Address.parse("map:Map1"), "k", "v"),
            EffectStatus.FIRED,
            0);
    SdState base =
        SdState.empty()
            .withEffects(Map.of(E1, effect))
            .withInfo(
                Map.of(
                    "map:Map1",
                    java.util.List.of(
                        new io.mosire.simos.sd.model.SdInfoEntry(
                            new io.mosire.simos.sd.id.SdInfoId("map:Map1#0"),
                            0L,
                            Set.of(),
                            Set.of(),
                            "k",
                            "v",
                            Optional.empty(),
                            new RevisionId(1),
                            Optional.empty(),
                            Optional.empty()))));
    TimeProposal proposal = simulate(base, 5, 6);
    assertThat(((SdChangeSet) proposal.changeSet()).isEmpty())
        .as("R6：已 FIRED 的 PUT-INFO 效果不再追加")
        .isTrue();
    assertThat(after(base, proposal).info().get("map:Map1")).hasSize(1);
  }

  // ── §十一（2026-09-25）：一次推进 N 天必须**逐日**评估 ────────────────────────────────

  /**
   * ★★ **一次跨多日推进要逐日走完阶段链**：夹具 {@code s1 --AtOrAfterTick(5)--> s2 --AtOrAfterTick(6)--> s3}，推进
   * {@code 4 → 10}。
   *
   * <p>★ 逐日 ⇒ 第 5 天 s1→s2、第 6 天 s2→s3（终态 **s3**）。判别力：只按 {@code to = 10} 求值一次的旧实现**只走一跳** ⇒ 终态停在 s2
   * ⇒ 本条红。
   */
  @Test
  void multiDayAdvanceWalksTheStageChainDayByDay() {
    SdState base = chainBase();

    SdState next = after(base, simulate(base, 4, 10));

    assertThat(next.combatStates().get(CS1).currentStage())
        .as("逐日推进 ⇒ 两跳都发生，到 s3（跳日的实现会停在 s2）")
        .isEqualTo(S3);
  }

  /** ★★ §十一 的等价性判据：{@code simulate(base, 4, 10)} 的终态 == 6 次单日 simulate 的终态。 */
  @Test
  void multiDayAdvanceAgreesWithChainedSingleDayAdvances() {
    SdState base = chainBase();

    SdState once = after(base, simulate(base, 4, 10));
    SdState chained = base;
    for (long day = 4; day < 10; day++) {
      chained = after(chained, simulate(chained, day, day + 1));
    }

    assertThat(once).as("§十一：一次 6 天 == 6 次单日（终态逐值相同）").isEqualTo(chained);
  }

  // ── 夹具与助手 ───────────────────────────────────────────────────────

  private TimeProposal simulate(SdState base, long fromTick, long toTick) {
    return participant.simulate(SdWorlds.world(base), range(fromTick, toTick));
  }

  private static SdState after(SdState base, TimeProposal proposal) {
    return SdChangeSet.apply((SdChangeSet) proposal.changeSet(), base);
  }

  private static TimeRange range(long fromTick, long toTick) {
    return new TimeRange(SimosTimestamp.of(fromTick), Optional.of(SimosTimestamp.of(toTick)));
  }

  private static SdState effectBase(EffectStatus status) {
    Effect effect =
        new Effect(
            E1,
            EffectKind.SCHEDULED,
            new Trigger.AtOrAfterTick(5),
            new Action.EnqueueUnitCommand(
                "unit.ApplyCasualties",
                "{\"id\":\"u-1\",\"manpower\":[{\"type\":\"步兵\",\"amount\":-1}],"
                    + "\"equipment\":[]}"),
            status,
            0);
    return SdState.empty().withEffects(Map.of(E1, effect));
  }

  private static SdState stageBase(Trigger exit, Trigger nextEntry, LossRecord... losses) {
    CombatStage s1 =
        new CombatStage(
            S1, "s1", Set.of(), java.util.List.of(), java.util.List.of(exit), 0, 10, table(O1));
    CombatStage s2 =
        new CombatStage(
            S2,
            "s2",
            Set.of(),
            java.util.List.of(nextEntry),
            java.util.List.of(),
            0,
            10,
            table(O2));
    Combat combat =
        new Combat(
            C1, "交战一", java.util.List.of(s1, s2), Set.of(SdWorlds.ROOT_UNIT), Optional.empty());
    Set<LossRecordId> lossIds = new java.util.LinkedHashSet<>();
    Map<LossRecordId, LossRecord> records = new java.util.LinkedHashMap<>();
    for (LossRecord record : losses) {
      lossIds.add(record.id());
      records.put(record.id(), record);
    }
    CombatState state =
        new CombatState(
            CS1, C1, S1, SdWorlds.HEX, Set.of(SdWorlds.ROOT_UNIT), Optional.empty(), lossIds);
    return SdState.empty()
        .withCombats(Map.of(C1, combat))
        .withLossRecords(records)
        .withCombatStates(Map.of(CS1, state));
  }

  private static OutcomeTable table(CombatOutcomeId outcome) {
    return new OutcomeTable(
        java.util.List.of(new OutcomeOption(outcome, "label", 1, new CasualtySpec(0, Map.of()))));
  }

  /** 三段阶段链 {@code s1 --AtOrAfterTick(5)--> s2 --AtOrAfterTick(6)--> s3}（§十一 逐日阶段推进的载体）。 */
  private static SdState chainBase() {
    CombatStage s1 =
        new CombatStage(
            S1,
            "s1",
            Set.of(),
            java.util.List.of(),
            java.util.List.of(new Trigger.AtOrAfterTick(5)),
            0,
            10,
            table(O1));
    CombatStage s2 =
        new CombatStage(
            S2,
            "s2",
            Set.of(),
            java.util.List.of(new Trigger.AtOrAfterTick(5)),
            java.util.List.of(new Trigger.AtOrAfterTick(6)),
            0,
            10,
            table(O2));
    CombatStage s3 =
        new CombatStage(
            S3,
            "s3",
            Set.of(),
            java.util.List.of(new Trigger.AtOrAfterTick(6)),
            java.util.List.of(),
            0,
            10,
            table(O1));
    Combat combat =
        new Combat(
            C1, "交战一", java.util.List.of(s1, s2, s3), Set.of(SdWorlds.ROOT_UNIT), Optional.empty());
    CombatState state =
        new CombatState(
            CS1, C1, S1, SdWorlds.HEX, Set.of(SdWorlds.ROOT_UNIT), Optional.empty(), Set.of());
    return SdState.empty().withCombats(Map.of(C1, combat)).withCombatStates(Map.of(CS1, state));
  }

  private static LossRecord lossRecord(int personnel) {
    return new LossRecord(
        new LossRecordId("l1"),
        C1,
        S1,
        new RevisionId(1),
        java.util.List.of(
            new CasualtyDelta(SdWorlds.ROOT_UNIT, personnel, Map.of(), LossClass.PERMANENT)));
  }
}
