package io.mosire.simos.app.sd.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.sd.channel.ActorId;
import io.mosire.simos.sd.channel.DecisionChannel;
import io.mosire.simos.sd.channel.DecisionRequest;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.spi.DirectiveWhitelist;
import io.mosire.simos.sd.spi.IssueDirectiveHandler;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** D5 端到端：渠道声明 + 模块校验 + 落同一落点（R9）+ 留痕（N18）；伪造 actor / 非落点命令 ⇒ 拒且无 revision。 */
class DecisionChannelTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final ActorId DM1 = new ActorId("dm1");

  @TempDir Path tempDir;

  private CoreSimos core;

  @AfterEach
  void closeCore() {
    if (core != null) {
      core.close();
    }
  }

  @Test
  void guiChannelWritesTheSameLandingPointAndLeavesAnAuditTrail() {
    CoreSimos core = start();
    DecisionChannel channel = new GuiDecisionChannel(core, () -> Set.of(DM1));

    channel.submit(DM1, request("sd.IssueDirective", directivePayload()));

    assertThat(core.head(MAIN).orElseThrow()).isEqualTo(new RevisionId(2));
    List<RevisionRow> rows = core.revisions(MAIN);
    RevisionRow written = rows.get(rows.size() - 1);
    assertThat(written.commandType()).isEqualTo("sd.IssueDirective");
    assertThat(written.initiator()).as("N18：渠道 id + actor 留痕").isEqualTo("player:gui:dm1");
    assertThat(channel.available()).isTrue();
  }

  @Test
  void forgedActorIsRejectedAndLeavesNoRevision() {
    CoreSimos core = start();
    DecisionChannel channel = new GuiDecisionChannel(core, () -> Set.of(DM1));

    assertThatThrownBy(
            () ->
                channel.submit(
                    new ActorId("ghost"), request("sd.IssueDirective", directivePayload())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("N16");
    assertThat(core.head(MAIN).orElseThrow()).isEqualTo(new RevisionId(1));
    assertThat(core.revisions(MAIN)).hasSize(1);
  }

  @Test
  void nonLandingPointCommandIsRejected() {
    CoreSimos core = start();
    DecisionChannel channel = new CliDecisionChannel(core, () -> Set.of(DM1));

    assertThatThrownBy(() -> channel.submit(DM1, request("simos.command.submit", "{}")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("R9");
    assertThat(core.head(MAIN).orElseThrow()).isEqualTo(new RevisionId(1));
  }

  private CoreSimos start() {
    core = new CoreSimos(new CoreConfig(tempDir, 100, SimosObjectMapper.create()));
    core.register(new SdCodec());
    core.register(
        new IssueDirectiveHandler(
            new DirectiveWhitelist(Set.of("unit.RenameUnit", "sd.IssueDirective"))));
    core.bootstrapGenesis(genesis());
    return core;
  }

  private static DecisionRequest request(String type, String payload) {
    return new DecisionRequest(MAIN, new RevisionId(1), type, payload);
  }

  private static String directivePayload() {
    return "{\"directiveId\":\"d1\",\"decisionMakerId\":\"dm1\",\"tick\":0,"
        + "\"intentInfo\":\"向北推进\",\"commands\":[]}";
  }

  private static SimulationState genesis() {
    StateRef ref = new StateRef(MAIN, new RevisionId(1));
    DecisionMakerId dm1 = new DecisionMakerId("dm1");
    DecisionMaker maker =
        new DecisionMaker(
            dm1, new Affiliation.Nation(new NationId("n1")), Set.of(), AccessLimit.empty(), 1);
    SdState sd = SdState.empty().withDecisionMakers(Map.of(dm1, maker));
    return new SimulationState(
        new StateMeta(ref, SimosTimestamp.of(0)),
        Map.of("sd", new SdSnapshot(ref, SimosTimestamp.of(0), sd)),
        InMemoryInfoSystem.empty());
  }
}
