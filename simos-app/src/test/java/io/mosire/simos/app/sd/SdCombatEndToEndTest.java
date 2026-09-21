package io.mosire.simos.app.sd;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.id.CombatOutcomeId;
import io.mosire.simos.sd.id.CombatStageId;
import io.mosire.simos.sd.spi.AddStageHandler;
import io.mosire.simos.sd.spi.CommitOutcomeHandler;
import io.mosire.simos.sd.spi.CreateCombatHandler;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.sd.time.SdTimeParticipant;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * C6 的端到端验收：真 {@code SdCodec} + 真 {@code SdTimeParticipant} + 真 store，全部经 {@code
 * CoreSimos.submit}—— 条件驱动（N1 到点前/后逐值不同）、冻结（同一坐标两次 Replay 逐字节相同）、恰选一个（N2）。
 */
class SdCombatEndToEndTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final SimosTimestamp T = SimosTimestamp.of(0);
  private static final UnitId U1 = new UnitId("u-1");
  private static final CombatStageId S1 = new CombatStageId("s1");
  private static final CombatStageId S2 = new CombatStageId("s2");
  private static final String MAP_ID = "Map1";

  @TempDir Path tempDir;

  private CoreSimos core;

  @AfterEach
  void closeCore() {
    if (core != null) {
      core.close();
    }
  }

  @Test
  void stageAdvanceIsConditionDrivenFrozenAndSelectsExactlyOne() {
    core = start();
    assertThat(submit(1, "sd.CreateCombat", createCombat()))
        .isInstanceOf(CommandResult.Committed.class);
    assertThat(submit(2, "sd.AddCombatStage", addStage(S1, 0, 5, "o1")))
        .isInstanceOf(CommandResult.Committed.class);
    assertThat(submit(3, "sd.AddCombatStage", addStage(S2, 5, 9, "o2")))
        .isInstanceOf(CommandResult.Committed.class);

    assertThat(core.submit(advance(4, 0, 4))).isInstanceOf(CommandResult.Committed.class);
    assertThat(currentStage(5)).as("AtOrAfterTick(5) 到点前不动").isEqualTo(S1);

    assertThat(core.submit(advance(5, 4, 5))).isInstanceOf(CommandResult.Committed.class);
    assertThat(currentStage(6)).as("到点后推进到下一阶段").isEqualTo(S2);
    System.out.println("[C6] condition-driven: rev5=s1 rev6=s2");

    assertThat(sdState(5)).as("同一坐标两次 Replay 逐字段相同").isEqualTo(sdState(5));
    assertThat(new SdCodec().encodeSnapshot(sdSnapshot(5)))
        .as("同一坐标两次编码逐字节相同")
        .isEqualTo(new SdCodec().encodeSnapshot(sdSnapshot(5)));
    System.out.println("[C6] frozen: two replays byte-identical");

    assertThat(submit(6, "sd.CommitCombatOutcome", commit(S2, "o2")))
        .isInstanceOf(CommandResult.Committed.class);
    assertThat(selectedOutcome(7)).contains("o2");
    CommandResult repeat = submit(7, "sd.CommitCombatOutcome", commit(S2, "o2"));
    assertThat(repeat).as("恰一个：已选过不覆盖").isInstanceOf(CommandResult.Rejected.class);
    assertThat(core.head(MAIN).orElseThrow().value()).as("被拒不留 revision").isEqualTo(7L);
    System.out.println("[C6] exactly-one: selected o2 then rejected second");
  }

  // ── 夹具与助手 ───────────────────────────────────────────────────────

  private CoreSimos start() {
    CoreSimos created = new CoreSimos(new CoreConfig(tempDir, 100, SimosObjectMapper.create()));
    created.register(new SdCodec());
    created.register(new UnitCodec());
    created.register(new CreateCombatHandler());
    created.register(new AddStageHandler());
    created.register(new CommitOutcomeHandler());
    created.register(new SdTimeParticipant(MAP_ID));
    created.bootstrapGenesis(genesis());
    return created;
  }

  private CommandResult submit(long expectedRevision, String type, String payload) {
    return core.submit(
        new CommandEnvelope(
            "cmd-" + expectedRevision,
            "cmd-" + expectedRevision,
            "agent:test",
            MAIN,
            new RevisionId(expectedRevision),
            type,
            payload));
  }

  private static String createCombat() {
    return "{\"combatId\":\"c1\",\"name\":\"交战一\",\"participants\":[\"u-1\"]}";
  }

  private static String addStage(CombatStageId stageId, long entry, long exit, String outcome) {
    String combatStatePart =
        stageId.equals(S1) ? "\"combatStateId\":\"cs1\",\"hex\":{\"q\":0,\"r\":0}," : "";
    return "{"
        + combatStatePart
        + "\"combatId\":\"c1\",\"stage\":{\"stageId\":\""
        + stageId.value()
        + "\",\"name\":\""
        + stageId.value()
        + "\",\"entry\":[{\"@class\":\"at_or_after_tick\",\"tick\":"
        + entry
        + "}],\"exit\":[{\"@class\":\"at_or_after_tick\",\"tick\":"
        + exit
        + "}],\"outcomes\":{\"options\":[{\"id\":\""
        + outcome
        + "\",\"label\":\"label\",\"weight\":1}]}}}";
  }

  private static String commit(CombatStageId stageId, String outcome) {
    return "{\"combatId\":\"c1\",\"stageId\":\""
        + stageId.value()
        + "\",\"selectedOutcomeId\":\""
        + outcome
        + "\"}";
  }

  private static AdvanceTime advance(long expectedRevision, long from, long to) {
    return new AdvanceTime(
        "adv-" + expectedRevision,
        "adv-" + expectedRevision,
        "agent:test",
        MAIN,
        new RevisionId(expectedRevision),
        new TimeRange(SimosTimestamp.of(from), Optional.of(SimosTimestamp.of(to))));
  }

  private SdSnapshot sdSnapshot(long revision) {
    return (SdSnapshot)
        core.replay(new StateRef(MAIN, new RevisionId(revision)))
            .module("sd")
            .orElseThrow(() -> new AssertionError("没有 sd 切片"));
  }

  private SdState sdState(long revision) {
    return sdSnapshot(revision).state();
  }

  private CombatStageId currentStage(long revision) {
    return sdState(revision).combatStates().values().iterator().next().currentStage();
  }

  private Optional<String> selectedOutcome(long revision) {
    return sdState(revision)
        .combatStates()
        .values()
        .iterator()
        .next()
        .selectedOutcome()
        .map(CombatOutcomeId::value);
  }

  private static SimulationState genesis() {
    StateRef ref = new StateRef(MAIN, new RevisionId(1));
    Unit unit =
        new Unit(
            U1,
            "第一连",
            new SegmentedSeries<>(
                List.of(new Segment<>(T, Optional.<UnitId>empty())), List.of(), null),
            new SegmentedSeries<>(
                List.of(new Segment<>(T, Optional.of(new HexCoord(0, 0)))), List.of(), null),
            100,
            Map.of("步枪", 50),
            2,
            500,
            Optional.empty());
    UnitState units = new UnitState(new LinkedHashMap<>(Map.of(U1, unit)));
    return new SimulationState(
        new StateMeta(ref, T),
        Map.of(
            "unit", new UnitSnapshot(ref, T, units),
            "sd", new SdSnapshot(ref, T, SdState.empty())),
        InMemoryInfoSystem.empty());
  }
}
