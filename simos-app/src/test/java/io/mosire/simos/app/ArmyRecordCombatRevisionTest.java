package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.CombatRecord;
import io.mosire.simos.army.CombatRecordId;
import io.mosire.simos.army.codec.ArmyCodec;
import io.mosire.simos.army.spi.RecordCombatHandler;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ {@code army.RecordCombat} 的 <b>revision 级判据</b>（本仓原先只有域级证据：{@code RecordCombatHandlerTest} 直接调
 * handler 折 {@code HandlerOutcome} 再 {@code ArmyChangeSet.apply}，**从不经过 core**，因此"这条命令到底落不落
 * revision、落几条" 没有护栏）。
 *
 * <p>形制照 {@link SdPutInfoEndToEndTest}（本仓现成的"经 core 提交并断言 revision"形态，{@code simos-app/src/test}
 * 下同包）：真 {@link CoreSimos} + 真 {@code bootstrapGenesis} + {@link CommandEnvelope} → 断言 {@link
 * CommandResult.Committed} 的新坐标 + {@code core.head} + {@code core.revisions} 行数 + {@code
 * core.replay} 回读。**不自创第二套提交夹具。**
 *
 * <ul>
 *   <li><b>①</b> 提交一条 ⇒ head <b>+1</b>（恰一张新 revision），且 {@code ArmySnapshot.data().combats()} <b>恰
 *       1 条</b>、逐字段可回放；
 *   <li><b>②</b> 同 id 再提交 ⇒ {@link CommandResult.Rejected}（具名：含 id）+ <b>head 不变</b>（零
 *       revision：时间线不多一行）；
 *   <li>★ 另附 tick 语义的两条 revision 级对照：过去可补记（仍恰一张 revision）、未来具名拒（零 revision）。
 * </ul>
 *
 * <p>★ <b>为什么必须落在这层</b>：handler 返回 {@code Applied} 却写出**空变更集**时，{@code CommandBus} 照样提交一张新
 * revision（{@code CommandBus.commit} 不判空）——"命令被接受"与"状态真的变了"是两件事，只有 revision 级 + 重放级断言能同时钉住两者。
 *
 * <p>★ 世界是手搭创世 checkpoint（只有 army 切片 + {@link ArmyCodec}），不碰数据库文件以外的任何东西（{@link TempDir}）。
 */
class ArmyRecordCombatRevisionTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);
  private static final HexCoord H34 = new HexCoord(3, 4);
  private static final UnitId U1 = new UnitId("u-1");
  private static final UnitId U2 = new UnitId("u-2");

  @TempDir Path tempDir;

  private CoreSimos core;

  @AfterEach
  void closeCore() {
    if (core != null) {
      core.close();
    }
  }

  // ── ① 提交 ⇒ head +1 且 combats 恰 1 条 ─────────────────────────────────────────────

  @Test
  void recordingACombatCostsExactlyOneRevisionAndTheRecordIsReplayable() {
    CoreSimos core = start();
    long headBefore = head(core);
    int revisionsBefore = core.revisions(MAIN).size();

    CommandResult result = core.submit(envelope(headBefore, payload("c-1")));

    assertThat(result).as(describe(result)).isInstanceOf(CommandResult.Committed.class);
    assertThat(((CommandResult.Committed) result).ref())
        .as("一条 army.RecordCombat = 恰好一张新 revision（不是 0 张、也不是连跳）")
        .isEqualTo(new StateRef(MAIN, new RevisionId(headBefore + 1)));
    assertThat(head(core)).as("head 前进一步").isEqualTo(headBefore + 1);
    assertThat(core.revisions(MAIN)).as("时间线上恰好多一行").hasSize(revisionsBefore + 1);

    ArmyData after = armyState(core, headBefore + 1);
    assertThat(after.combats()).as("★ ArmySnapshot.data().combats() 恰 1 条").hasSize(1);
    CombatRecord record = after.combats().get(new CombatRecordId("c-1"));
    assertThat(record).as("记录真的以这个 id 落了盘（不是写在别的键上）").isNotNull();
    assertThat(record.id()).isEqualTo(new CombatRecordId("c-1"));
    assertThat(record.kind()).isEqualTo("野战");
    assertThat(record.tick()).as("缺省 tick = 世界当前 tick（7）").isEqualTo(7L);
    assertThat(record.hex()).isEqualTo(H34);
    assertThat(record.participants()).as("参与者保序").containsExactly(U1, U2);
    assertThat(record.text()).isEqualTo("记录级过程");
    assertThat(record.stages()).as("初始阶段由 handler 合成").hasSize(1);
    assertThat(record.stages().get(0).id().value()).isEqualTo(RecordCombatHandler.INITIAL_STAGE_ID);

    assertThat(armyState(core, headBefore).combats())
        .as("旧 revision 上没有这条记录（新坐标只落在新 revision 上，历史可回放）")
        .isEmpty();
  }

  // ── ② 同 id 再提交 ⇒ Rejected 且零 revision ─────────────────────────────────────────

  @Test
  void aSecondCombatWithTheSameIdIsRejectedAndLeavesTheHeadUntouched() {
    CoreSimos core = start();
    assertThat(core.submit(envelope(1, payload("c-1"))))
        .isInstanceOf(CommandResult.Committed.class);
    long headAfterFirst = head(core);
    int revisionsAfterFirst = core.revisions(MAIN).size();

    CommandResult second = core.submit(envelope(headAfterFirst, payload("c-1")));

    assertThat(second).as("记录 id 是一次性身份 ⇒ 具名拒").isInstanceOf(CommandResult.Rejected.class);
    assertThat(((CommandResult.Rejected) second).reason())
        .as("拒因点名『已存在』与 id（不是泛泛的失败）")
        .contains("已存在")
        .contains("c-1");
    assertThat(head(core)).as("★ 具名拒 ⇒ 零 revision：head 一个号都不动").isEqualTo(headAfterFirst);
    assertThat(core.revisions(MAIN)).as("时间线上不多一行").hasSize(revisionsAfterFirst);
    assertThat(armyState(core, headAfterFirst).combats()).as("仍恰 1 条、内容未被覆写").hasSize(1);
  }

  // ── ★ tick 语义的 revision 级对照（过去可补记 / 未来具名拒）────────────────────────────

  @Test
  void aPastTickIsAllowedAndStillCostsExactlyOneRevision() {
    CoreSimos core = start();

    CommandResult result = core.submit(envelope(1, payloadWithTick("c-past", 3L)));

    assertThat(result).as(describe(result)).isInstanceOf(CommandResult.Committed.class);
    assertThat(((CommandResult.Committed) result).ref())
        .as("补记历史同样恰一张 revision")
        .isEqualTo(new StateRef(MAIN, new RevisionId(2)));
    assertThat(armyState(core, 2).combats().get(new CombatRecordId("c-past")).tick())
        .as("过去可补记：记录 tick 用载荷给的 3，不是世界 tick 7")
        .isEqualTo(3L);
  }

  @Test
  void aFutureTickIsRejectedAndLeavesNoRevision() {
    CoreSimos core = start();
    int revisionsBefore = core.revisions(MAIN).size();

    CommandResult result = core.submit(envelope(1, payloadWithTick("c-future", 8L)));

    assertThat(result).isInstanceOf(CommandResult.Rejected.class);
    assertThat(((CommandResult.Rejected) result).reason())
        .as("拒因点名未来与世界 tick")
        .contains("未来")
        .contains("8")
        .contains("7");
    assertThat(head(core)).as("具名拒 ⇒ 零 revision").isEqualTo(1L);
    assertThat(core.revisions(MAIN)).hasSize(revisionsBefore);
    assertThat(armyState(core, 1).combats()).isEmpty();
  }

  // ── 夹具（照 SdPutInfoEndToEndTest：真 CoreSimos + bootstrapGenesis + submit）──────────

  private CoreSimos start() {
    core = new CoreSimos(new CoreConfig(tempDir, 100, SimosObjectMapper.create()));
    core.register(new ArmyCodec());
    core.register(new RecordCombatHandler());
    core.bootstrapGenesis(genesis());
    return core;
  }

  private static CommandEnvelope envelope(long expectedRevision, String payloadJson) {
    return new CommandEnvelope(
        "cmd-" + expectedRevision,
        "corr-" + expectedRevision,
        "agent:test",
        MAIN,
        new RevisionId(expectedRevision),
        RecordCombatHandler.TYPE,
        payloadJson);
  }

  private static String payload(String id) {
    return "{\"id\":\""
        + id
        + "\",\"kind\":\"野战\",\"hex\":{\"q\":3,\"r\":4},"
        + "\"participants\":[\"u-1\",\"u-2\"],\"text\":\"记录级过程\"}";
  }

  private static String payloadWithTick(String id, long tick) {
    return "{\"id\":\""
        + id
        + "\",\"kind\":\"野战\",\"hex\":{\"q\":3,\"r\":4},"
        + "\"participants\":[\"u-1\",\"u-2\"],\"text\":\"记录级过程\",\"tick\":"
        + tick
        + "}";
  }

  private static long head(CoreSimos core) {
    return core.head(MAIN).orElseThrow(() -> new AssertionError("时间线上没有 main")).value();
  }

  /** 经真 replay 读回该 revision 的 army 切片数据（不是夹具直接落盘）。 */
  private static ArmyData armyState(CoreSimos core, long revision) {
    SimulationState state = core.replay(new StateRef(MAIN, new RevisionId(revision)));
    ArmySnapshot slice =
        (ArmySnapshot) state.module("army").orElseThrow(() -> new AssertionError("状态里没有 army 切片"));
    return slice.data();
  }

  private static SimulationState genesis() {
    StateRef ref = new StateRef(MAIN, new RevisionId(1));
    return new SimulationState(
        new StateMeta(ref, T7),
        Map.of("army", new ArmySnapshot(ref, T7, ArmyData.empty())),
        InMemoryInfoSystem.empty());
  }

  private static String describe(CommandResult result) {
    return result instanceof CommandResult.Rejected rejected
        ? rejected.reason()
        : String.valueOf(result);
  }
}
