package io.mosire.simos.app.sd;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.spi.RegisterEffectHandler;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.sd.time.SdTimeParticipant;
import io.mosire.simos.unit.CompositionEntry;
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
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * C5 的端到端验收：sd 参与者只写 sd，跨模块效果经 {@link SdCommandDrain} 落成**真 revision**；drain 幂等（重放不重复提交）； 中途失败留下"sd
 * 已记 effect、unit 未改"的中间态（如实测，不假装原子）。
 */
class SdCommandDrainTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final SimosTimestamp T = SimosTimestamp.of(0);
  private static final UnitId U1 = new UnitId("u-1");
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
  void crossModuleEffectBecomesARealRevisionAndIsIdempotent() {
    CoreSimos core = start();
    assertThat(core.submit(registerEffect(1, "e1", 5, boundPayload())).getClass())
        .isEqualTo(CommandResult.Committed.class);
    assertThat(core.submit(registerEffect(2, "e2", 100, boundPayload())).getClass())
        .as("e2 的 trigger 远未达成 ⇒ 推进后仍是 PLANNED")
        .isEqualTo(CommandResult.Committed.class);
    // ★ 日制裁定：一次推进恰好一天 ⇒ 到 tick 5 要连续提交 5 次单日推进（revision 3 → 8）。
    long atTick5 = advanceDays(3, 5);
    long beforeDrain = revisionRowCount();
    assertThat(unitMember(core, atTick5)).as("推进本身不改 unit").isEqualTo(100);

    List<CommandResult> drained = new SdCommandDrain(core).drainAfterAdvance(MAIN);

    assertThat(drained).hasSize(1);
    assertThat(drained.get(0)).isInstanceOf(CommandResult.Committed.class);
    assertThat(revisionRowCount()).as("跨模块效果落成真 revision").isEqualTo(beforeDrain + 1);
    assertThat(core.head(MAIN).orElseThrow().value()).isEqualTo(atTick5 + 1);
    assertThat(unitMember(core, atTick5 + 1)).as("unit 真的改了").isEqualTo(90);
    System.out.println("[C5] drain committed revision=" + (atTick5 + 1) + " member=90");

    List<CommandResult> again = new SdCommandDrain(core).drainAfterAdvance(MAIN);
    assertThat(again).as("幂等：重复 drain 不重复提交（e2 仍 PLANNED、e1 已 drain）").isEmpty();
    assertThat(core.head(MAIN).orElseThrow().value()).isEqualTo(atTick5 + 1);
    assertThat(revisionRowCount()).isEqualTo(beforeDrain + 1);
  }

  @Test
  void rejectedDrainLeavesTheIntermediateStateVisible() {
    CoreSimos core = start();
    core.submit(
        registerEffect(
            1,
            "e1",
            5,
            "{\"id\":\"u-1\",\"manpower\":[{\"type\":\"步兵\",\"amount\":-9999}],\"equipment\":[]}"));
    // ★ 日制裁定：到 tick 5 = 连续 5 次单日推进（revision 2 → 7）。
    long atTick5 = advanceDays(2, 5);
    long beforeDrain = revisionRowCount();

    List<CommandResult> drained = new SdCommandDrain(core).drainAfterAdvance(MAIN);

    assertThat(drained).hasSize(1);
    assertThat(drained.get(0)).as("越界战损被 unit 侧拒绝").isInstanceOf(CommandResult.Rejected.class);
    assertThat(revisionRowCount()).as("被拒不写行 ⇒ 无半写 revision").isEqualTo(beforeDrain);
    assertThat(unitMember(core, atTick5)).as("unit 未改（中间态）").isEqualTo(100);
    assertThat(effectStatus(core, atTick5, "e1")).as("sd 已记 effect FIRED（中间态）").isEqualTo("FIRED");
    List<CommandResult> retry = new SdCommandDrain(core).drainAfterAdvance(MAIN);
    assertThat(retry).as("被拒的指令下次 drain 会重试（不静默丢弃）").hasSize(1);
    System.out.println(
        "[C5] rejected-drain intermediate state: sd=FIRED unit=100 rows=" + beforeDrain);
  }

  // ── 夹具与助手 ───────────────────────────────────────────────────────

  private CoreSimos start() {
    core = new CoreSimos(new CoreConfig(tempDir, 100, SimosObjectMapper.create()));
    core.register(new SdCodec());
    core.register(new UnitCodec());
    core.register(new RegisterEffectHandler(Set.of("unit.ApplyCasualties")));
    core.register(new io.mosire.simos.unit.spi.ApplyCasualtiesHandler());
    core.register(new SdTimeParticipant(MAP_ID));
    core.bootstrapGenesis(genesis());
    return core;
  }

  private static CommandEnvelope registerEffect(
      long expectedRevision, String effectId, long tick, String payloadJson) {
    String payload =
        "{\"effectId\":\""
            + effectId
            + "\",\"kind\":\"SCHEDULED\","
            + "\"trigger\":{\"@class\":\"at_or_after_tick\",\"tick\":"
            + tick
            + "},"
            + "\"action\":{\"@class\":\"enqueue_unit_command\",\"type\":\"unit.ApplyCasualties\","
            + "\"payloadJson\":"
            + jsonString(payloadJson)
            + "}}";
    return envelope(expectedRevision, "sd.RegisterEffect", payload);
  }

  private static String boundPayload() {
    return "{\"id\":\"u-1\",\"manpower\":[{\"type\":\"步兵\",\"amount\":-10}],\"equipment\":[{\"type\":\"步枪\",\"amount\":-5}]}";
  }

  private static String jsonString(String text) {
    return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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

  /**
   * 从创世 tick 0 起快进 {@code days} 天（日制裁定：一次推进**恰好一天** ⇒ 连续 {@code days} 次单日推进，每次用上一次的新 {@code
   * expectedRevision}）。返回推进后的 head。
   */
  private long advanceDays(long expectedRevision, int days) {
    long head = expectedRevision;
    for (long tick = 0; tick < days; tick++) {
      assertThat(core.submit(advance(head, tick, tick + 1)))
          .as("推进第 %d 天（tick %d → %d）", tick + 1, tick, tick + 1)
          .isInstanceOf(CommandResult.Committed.class);
      head++;
    }
    return head;
  }

  private static CommandEnvelope envelope(long expectedRevision, String type, String payload) {
    return new CommandEnvelope(
        "cmd-" + expectedRevision,
        "cmd-" + expectedRevision,
        "agent:test",
        MAIN,
        new RevisionId(expectedRevision),
        type,
        payload);
  }

  private long revisionRowCount() {
    try (SqliteStore store = SqliteStore.open(tempDir.resolve(CoreSimos.DB_FILE_NAME))) {
      return store.inTransaction(
          connection -> {
            try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM revisions")) {
              return rows.next() ? rows.getLong(1) : 0L;
            }
          });
    }
  }

  private static long unitMember(CoreSimos core, long revision) {
    return unitState(core, revision).units().get(U1).manpower().stream()
        .mapToLong(CompositionEntry::amount)
        .sum();
  }

  private static String effectStatus(CoreSimos core, long revision, String effectId) {
    return sdState(core, revision)
        .effects()
        .get(new io.mosire.simos.sd.id.EffectId(effectId))
        .status()
        .name();
  }

  private static UnitState unitState(CoreSimos core, long revision) {
    UnitSnapshot slice =
        (UnitSnapshot)
            core.replay(new StateRef(MAIN, new RevisionId(revision)))
                .module("unit")
                .orElseThrow(() -> new AssertionError("没有 unit 切片"));
    return slice.state();
  }

  private static SdState sdState(CoreSimos core, long revision) {
    SdSnapshot slice =
        (SdSnapshot)
            core.replay(new StateRef(MAIN, new RevisionId(revision)))
                .module("sd")
                .orElseThrow(() -> new AssertionError("没有 sd 切片"));
    return slice.state();
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
                List.of(new Segment<>(T, Optional.of(new io.mosire.simos.map.hex.HexCoord(0, 0)))),
                List.of(),
                null),
            List.of(new CompositionEntry("步兵", 100)),
            List.of(new CompositionEntry("步枪", 50)),
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
