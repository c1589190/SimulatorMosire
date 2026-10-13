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
import io.mosire.simos.ga.model.AccessLimit;
import io.mosire.simos.ga.model.DecisionMaker;
import io.mosire.simos.ga.model.DisclosurePolicy;
import io.mosire.simos.ga.spi.SetDecisionMakerAccessHandler;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.codec.GovCodec;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T9 端到端（N11，取代 D4 的 {@code sd.SetViewScope} 版）：{@code sd.SetDecisionMakerAccess} 经真 {@code
 * CoreSimos.submit} **写入 revision**（revisions 行 +1），重放后 {@code AccessLimit} 仍逐值在；未知 dm ⇒ 拒绝且 head
 * 不动。
 *
 * <p>★ **断言逐条换成了新语义**（不是删掉几条）：旧用例断言"可见格被写进去了"（{@code visibleHexes} 含 (2,3)）；新命令写的是 **前缀 +
 * 披露档**，故断言换成前缀逐值与披露档逐值。
 */
class SdSetDecisionMakerAccessEndToEndTest {

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
  void setAccessLimitIsARevisionAndSurvivesReplay() {
    CoreSimos core = start();
    CommandResult result = core.submit(envelope(1, payload("dm1")));
    assertThat(result).isInstanceOf(CommandResult.Committed.class);
    assertThat(((CommandResult.Committed) result).ref())
        .isEqualTo(new StateRef(MAIN, new RevisionId(2)));
    assertThat(core.revisions(MAIN)).as("写入产生一条 revision 行").hasSize(2);

    GaState replayed = sdState(core, 2);
    AccessLimit limit = replayed.decisionMakers().get(new DecisionMakerId("dm1")).accessLimit();
    assertThat(limit.prefixesByNamespace().get("map"))
        .as("前缀逐值活过落盘-重放（铁律 2/5）")
        .containsExactly("Map1/region/r1");
    assertThat(limit.adjudicationDisclosure()).isEqualTo(DisclosurePolicy.FULL);
  }

  @Test
  void unknownDecisionMakerIsRejectedAndLeavesNoRevision() {
    CoreSimos core = start();
    CommandResult result = core.submit(envelope(1, payload("ghost")));
    assertThat(result).isInstanceOf(CommandResult.Rejected.class);
    assertThat(((CommandResult.Rejected) result).reason()).contains("决策人不存在");
    assertThat(core.head(MAIN).orElseThrow()).isEqualTo(new RevisionId(1));
    assertThat(core.revisions(MAIN)).hasSize(1);
  }

  private CoreSimos start() {
    core = new CoreSimos(new CoreConfig(tempDir, 100, SimosObjectMapper.create()));
    core.register(new GaCodec());
    // ★ 2026-10-13 夹具切片欠账：世界补 army / gov 空片 ⇒ 信封必须随之注册两个 codec（漏一个 = 创世当场抛）。
    core.register(new ArmyCodec());
    core.register(new GovCodec());
    core.register(new SetDecisionMakerAccessHandler());
    core.bootstrapGenesis(genesis());
    return core;
  }

  private static GaState sdState(CoreSimos core, long revision) {
    SimulationState state = core.replay(new StateRef(MAIN, new RevisionId(revision)));
    return ((GaSnapshot) state.module("ga").orElseThrow()).state();
  }

  private static CommandEnvelope envelope(long expectedRevision, String payload) {
    return new CommandEnvelope(
        "cmd-" + expectedRevision,
        "cmd-" + expectedRevision,
        "gm:test",
        MAIN,
        new RevisionId(expectedRevision),
        "ga.SetDecisionMakerAccess",
        payload);
  }

  private static String payload(String dm) {
    return "{\"decisionMakerId\":\""
        + dm
        + "\",\"accessLimit\":{\"map\":[\"Map1/region/r1\"]},"
        + "\"adjudicationDisclosure\":\"FULL\"}";
  }

  private static SimulationState genesis() {
    StateRef ref = new StateRef(MAIN, new RevisionId(1));
    DecisionMakerId dm1 = new DecisionMakerId("dm1");
    DecisionMaker maker = new DecisionMaker(dm1, Set.of(), AccessLimit.empty(), 1);
    GaState sd = GaState.empty().withDecisionMakers(Map.of(dm1, maker));
    return new SimulationState(
        new StateMeta(ref, SimosTimestamp.of(0)),
        Map.of(
            "ga", new GaSnapshot(ref, SimosTimestamp.of(0), sd),
            // ★ 2026-10-13 夹具切片欠账：army 也是时间参与者（ArmyTimeParticipant 逐日判"两军相遇"）
            //   ⇒ 引擎契约"状态必须带每个参与者的切片"要求它必须在场（缺席是装配故障，不是"无事"）；本夹具未播种 ⇒ 空片。
            "army", new ArmySnapshot(ref, SimosTimestamp.of(0), ArmyData.empty()),
            // ★ 2026-10-13 夹具切片欠账：gov 空片也在场（与 army 同批补；空 gov ⇒ 行政未激活，行为不变）。
            "gov", new GovSnapshot(ref, SimosTimestamp.of(0), GovState.empty())),
        InMemoryInfoSystem.empty());
  }
}
