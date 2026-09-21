package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.Nation;
import io.mosire.simos.sd.model.ViewScope;
import io.mosire.simos.sd.spi.SetDecisionMakerProviderHandler;
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
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M11 判据 C7：{@code sd.SetDecisionMakerProvider} 经**真** {@code CoreSimos.submit} 落 revision、可
 * replay； 与 {@code sd.SetViewScope} 组合时绑定不丢。
 */
class SdProviderBindingEndToEndTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final DecisionMakerId DM1 = new DecisionMakerId("dm1");

  @TempDir Path tempDir;

  private CoreSimos core;

  @AfterEach
  void closeCore() {
    if (core != null) {
      core.close();
    }
  }

  @Test
  void bindingLandsARevisionAndSurvivesReplay() {
    CoreSimos core = start();
    assertThat(sdState(core, 1).decisionMakers().get(DM1).providerId()).isEmpty();

    CommandResult result =
        core.submit(
            envelope(
                1,
                "sd.SetDecisionMakerProvider",
                "{\"decisionMakerId\":\"dm1\",\"providerId\":\"p-a\"}"));
    assertThat(result).isInstanceOf(CommandResult.Committed.class);
    assertThat(((CommandResult.Committed) result).ref())
        .isEqualTo(new StateRef(MAIN, new RevisionId(2)));

    SdState replayed = sdState(core, 2);
    assertThat(replayed.decisionMakers().get(DM1).providerId()).contains("p-a");
    assertThat(sdState(core, 2)).as("同一 revision 两次 replay 相等").isEqualTo(replayed);
  }

  @Test
  void settingViewScopeAfterBindingKeepsTheProvider() {
    CoreSimos core = start();
    core.submit(
        envelope(
            1,
            "sd.SetDecisionMakerProvider",
            "{\"decisionMakerId\":\"dm1\",\"providerId\":\"p-a\"}"));
    CommandResult scope =
        core.submit(
            envelope(
                2,
                "sd.SetViewScope",
                "{\"decisionMakerId\":\"dm1\",\"viewScope\":{\"visibleRegions\":[\"r1\"]}}"));
    assertThat(scope).isInstanceOf(CommandResult.Committed.class);

    DecisionMaker maker = sdState(core, 3).decisionMakers().get(DM1);
    assertThat(maker.providerId()).as("配权不得丢绑定").contains("p-a");
    assertThat(maker.viewScope().visibleRegions()).containsExactly(new RegionId("r1"));
  }

  @Test
  void unknownDecisionMakerIsRejectedAndLeavesNoRevision() {
    CoreSimos core = start();
    CommandResult result =
        core.submit(
            envelope(
                1,
                "sd.SetDecisionMakerProvider",
                "{\"decisionMakerId\":\"ghost\",\"providerId\":\"p\"}"));
    assertThat(result).isInstanceOf(CommandResult.Rejected.class);
    assertThat(core.head(MAIN).orElseThrow()).isEqualTo(new RevisionId(1));
  }

  private CoreSimos start() {
    core = new CoreSimos(new CoreConfig(tempDir, 100, SimosObjectMapper.create()));
    core.register(new SdCodec());
    core.register(new SetDecisionMakerProviderHandler());
    core.register(new SetViewScopeHandler());
    core.bootstrapGenesis(genesis());
    return core;
  }

  private static SdState sdState(CoreSimos core, long revision) {
    SimulationState state = core.replay(new StateRef(MAIN, new RevisionId(revision)));
    SdSnapshot slice =
        (SdSnapshot) state.module("sd").orElseThrow(() -> new AssertionError("状态里没有 sd 切片"));
    return slice.state();
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

  private static SimulationState genesis() {
    StateRef ref = new StateRef(MAIN, new RevisionId(1));
    DecisionMaker dm =
        new DecisionMaker(
            DM1,
            new Affiliation.Nation(new NationId("n1")),
            Set.of(),
            ViewScope.empty(),
            1,
            Optional.empty());
    SdState sd =
        SdState.empty()
            .withNations(
                Map.of(
                    new NationId("n1"),
                    new Nation(new NationId("n1"), "甲国", new RegionId("r1"), 1)))
            .withDecisionMakers(Map.of(DM1, dm));
    return new SimulationState(
        new StateMeta(ref, SimosTimestamp.of(0)),
        Map.of("sd", new SdSnapshot(ref, SimosTimestamp.of(0), sd)),
        InMemoryInfoSystem.empty());
  }
}
