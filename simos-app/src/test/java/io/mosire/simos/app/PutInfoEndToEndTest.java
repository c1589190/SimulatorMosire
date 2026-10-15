package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.codec.ArmyCodec;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.ga.GaSnapshot;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.codec.GaCodec;
import io.mosire.simos.ga.id.DecisionInfoId;
import io.mosire.simos.ga.model.DecisionInfoEntry;
import io.mosire.simos.ga.spi.PutInfoHandler;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.codec.GovCodec;
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
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A5 的端到端验收：{@code sd.PutInfo} 经**真** {@code CoreSimos.submit} 落 revision，再经**真** {@code Replay} 读回
 * ⇒ INFO 条目逐字段相等（R14 / 铁律 5）。★ 这是"info 组件随 revision 重放"的实测，不是夹具直接落盘。
 */
class PutInfoEndToEndTest {

  private static final BranchId MAIN = new BranchId("main");

  @TempDir Path tempDir;

  private CoreSimos core;

  @AfterEach
  void closeCore() {
    if (core != null) {
      core.close();
    }
  }

  @Test
  void putInfoSurvivesARealReplay() {
    CoreSimos core = start();
    CommandResult result =
        core.submit(
            envelope(
                1,
                "{\"address\":\"map:Map1:region.r1\",\"key\":\"brief\",\"value\":\"hello\","
                    + "\"note\":\"n\"}"));
    assertThat(result).isInstanceOf(CommandResult.Committed.class);
    assertThat(((CommandResult.Committed) result).ref())
        .isEqualTo(new StateRef(MAIN, new RevisionId(2)));

    GaState replayed = sdState(core, 2);
    assertThat(replayed.info().get("map:Map1:region.r1"))
        .containsExactly(
            new DecisionInfoEntry(
                new DecisionInfoId("map:Map1:region.r1#0"),
                0L,
                Set.of(),
                Set.of(),
                "brief",
                "hello",
                Optional.of("n"),
                new RevisionId(1),
                Optional.empty(),
                Optional.empty()));
  }

  @Test
  void secondPutInfoAppendsAndIsAlsoReplayable() {
    CoreSimos core = start();
    assertThat(core.submit(envelope(1, put("k1", "v1"))))
        .isInstanceOf(CommandResult.Committed.class);
    assertThat(core.submit(envelope(2, put("k2", "v2"))))
        .isInstanceOf(CommandResult.Committed.class);

    assertThat(sdState(core, 3).info().get("map:Map1")).hasSize(2);
  }

  @Test
  void malformedAddressIsRejectedAndLeavesNoRevision() {
    CoreSimos core = start();
    CommandResult result =
        core.submit(envelope(1, "{\"address\":\"oops\",\"key\":\"k\",\"value\":\"v\"}"));
    assertThat(result).isInstanceOf(CommandResult.Rejected.class);
    assertThat(core.head(MAIN).orElseThrow()).as("拒绝是原子的：head 不动").isEqualTo(new RevisionId(1));
  }

  private CoreSimos start() {
    core = new CoreSimos(new CoreConfig(tempDir, 100, SimosObjectMapper.create()));
    core.register(new GaCodec());
    // ★ 2026-10-13 夹具切片欠账：世界补 army / gov 空片 ⇒ 信封必须随之注册两个 codec（漏一个 = 创世当场抛）。
    core.register(new ArmyCodec());
    core.register(new GovCodec());
    core.register(new PutInfoHandler());
    core.bootstrapGenesis(genesis());
    return core;
  }

  private static GaState sdState(CoreSimos core, long revision) {
    SimulationState state = core.replay(new StateRef(MAIN, new RevisionId(revision)));
    GaSnapshot slice =
        (GaSnapshot) state.module("ga").orElseThrow(() -> new AssertionError("状态里没有 ga 切片"));
    return slice.state();
  }

  private static CommandEnvelope envelope(long expectedRevision, String payload) {
    return new CommandEnvelope(
        "cmd-" + expectedRevision,
        "cmd-" + expectedRevision,
        "agent:test",
        MAIN,
        new RevisionId(expectedRevision),
        "ga.PutInfo",
        payload);
  }

  private static String put(String key, String value) {
    return "{\"address\":\"map:Map1\",\"key\":\"" + key + "\",\"value\":\"" + value + "\"}";
  }

  private static SimulationState genesis() {
    StateRef ref = new StateRef(MAIN, new RevisionId(1));
    return new SimulationState(
        new StateMeta(ref, SimosTimestamp.of(0)),
        Map.of(
            "ga", new GaSnapshot(ref, SimosTimestamp.of(0), GaState.empty()),
            // ★ 2026-10-13 夹具切片欠账：army 也是时间参与者（ArmyTimeParticipant 逐日判"两军相遇"）
            //   ⇒ 引擎契约"状态必须带每个参与者的切片"要求它必须在场（缺席是装配故障，不是"无事"）；本夹具未播种 ⇒ 空片。
            "army", new ArmySnapshot(ref, SimosTimestamp.of(0), ArmyData.empty()),
            // ★ 2026-10-13 夹具切片欠账：gov 空片也在场（与 army 同批补；空 gov ⇒ 行政未激活，行为不变）。
            "gov", new GovSnapshot(ref, SimosTimestamp.of(0), GovState.empty())),
        InMemoryInfoSystem.empty());
  }
}
