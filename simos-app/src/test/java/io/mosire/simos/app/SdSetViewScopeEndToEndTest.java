package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.ViewScope;
import io.mosire.simos.sd.spi.SetViewScopeHandler;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
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
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * D4 端到端（N11）：{@code sd.SetViewScope} 经真 {@code CoreSimos.submit} **写入 revision**（revisions 行 +1），
 * 重放后 {@code ViewScope} 仍逐值在；未知 dm ⇒ 拒绝且 head 不动。
 */
class SdSetViewScopeEndToEndTest {

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
  void setViewScopeIsARevisionAndSurvivesReplay() {
    CoreSimos core = start();
    CommandResult result = core.submit(envelope(1, payload("dm1")));
    assertThat(result).isInstanceOf(CommandResult.Committed.class);
    assertThat(((CommandResult.Committed) result).ref())
        .isEqualTo(new StateRef(MAIN, new RevisionId(2)));
    assertThat(core.revisions(MAIN)).as("写入产生一条 revision 行").hasSize(2);

    SdState replayed = sdState(core, 2);
    assertThat(replayed.decisionMakers().get(new DecisionMakerId("dm1")).viewScope().visibleHexes())
        .containsExactly(new HexCoord(2, 3));
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
    core.register(new SdCodec());
    core.register(new SetViewScopeHandler());
    core.bootstrapGenesis(genesis());
    return core;
  }

  private static SdState sdState(CoreSimos core, long revision) {
    SimulationState state = core.replay(new StateRef(MAIN, new RevisionId(revision)));
    return ((SdSnapshot) state.module("sd").orElseThrow()).state();
  }

  private static CommandEnvelope envelope(long expectedRevision, String payload) {
    return new CommandEnvelope(
        "cmd-" + expectedRevision,
        "cmd-" + expectedRevision,
        "gm:test",
        MAIN,
        new RevisionId(expectedRevision),
        "sd.SetViewScope",
        payload);
  }

  private static String payload(String dm) {
    return "{\"decisionMakerId\":\""
        + dm
        + "\",\"viewScope\":{\"visibleHexes\":[{\"q\":2,\"r\":3}],"
        + "\"adjudicationDisclosure\":\"FULL\"}}";
  }

  private static SimulationState genesis() {
    StateRef ref = new StateRef(MAIN, new RevisionId(1));
    DecisionMakerId dm1 = new DecisionMakerId("dm1");
    DecisionMaker maker =
        new DecisionMaker(
            dm1, new Affiliation.Nation(new NationId("n1")), Set.of(), ViewScope.empty(), 1);
    SdState sd = SdState.empty().withDecisionMakers(Map.of(dm1, maker));
    return new SimulationState(
        new StateMeta(ref, SimosTimestamp.of(0)),
        Map.of("sd", new SdSnapshot(ref, SimosTimestamp.of(0), sd)),
        InMemoryInfoSystem.empty());
  }
}
