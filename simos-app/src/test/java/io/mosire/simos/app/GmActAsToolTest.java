package io.mosire.simos.app;

import static io.mosire.simos.app.decision.DecisionPackageTestSupport.applyCommitted;
import static io.mosire.simos.app.decision.DecisionPackageTestSupport.item;
import static io.mosire.simos.app.decision.DecisionPackageTestSupport.metaFor;
import static io.mosire.simos.app.decision.DecisionPackageTestSupport.proposedRecord;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.decision.PackageItem;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.decision.DecisionPackageTestSupport;
import io.mosire.simos.app.testing.GmActAsWorldFixture;
import io.mosire.simos.app.testing.GovE2eWorld;
import io.mosire.simos.app.tools.SimosToolSource;
import io.mosire.simos.ga.model.DecisionRecord;
import io.mosire.simos.ga.tools.write.GmActAsTool;
import io.mosire.simos.ga.tools.write.RequestTool;
import io.mosire.simos.util.spi.DecisionMakerId;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ {@code simos.gm.actAs} 在新决策包语义下的核心判据：内层身份是那个 DM、同 tick 的包写仍落在同一个 {@code pkt-<dm>-<tick>}、包内不出现
 * GM 代打标记；GM-only/编排族不能被代打绕过。
 */
class GmActAsToolTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String DM = GmActAsWorldFixture.DM;
  private static final String PACKET = "pkt-" + DM + "-0";

  @TempDir Path tempDir;

  private GmActAsWorldFixture.World world;

  @BeforeEach
  void startWorld() {
    world = GmActAsWorldFixture.start(tempDir.resolve("world"), new GmActAsWorldFixture.Fake());
    List<PackageItem> items = List.of(item(0, RequestTool.NAME, Map.of("text", "seed")));
    DecisionRecord proposed =
        proposedRecord(
            PACKET,
            new DecisionMakerId(DM),
            0L,
            world.head() + 1L,
            items,
            "seed intent",
            metaFor(items));
    applyCommitted(world.shell().coreSimos(), 0L, proposed);
  }

  @AfterEach
  void stopWorld() {
    if (world != null) {
      world.close();
      world = null;
    }
  }

  @Test
  void actAsKeepsDecisionMakerIdentityAndWritesTheSameTickPacketAcrossCalls() throws Exception {
    long headBefore = world.head();
    ToolResult first =
        actAs(
            Map.of(
                "decisionMakerId",
                DM,
                "tool",
                "simos.ga.packet.intent",
                "args",
                Map.of("text", "代打意图 A")));
    assertThat(first.success()).as(first.message()).isTrue();
    assertThat(world.head()).as("一次内层 intent = 一条 revision").isEqualTo(headBefore + 1L);
    DecisionRecord afterFirst =
        DecisionPackageTestSupport.record(world.shell().coreSimos(), PACKET);
    assertThat(afterFirst.intent()).isEqualTo("代打意图 A");
    assertThat(afterFirst.proposerId().value()).as("包署名人仍是 DM，不是 GM").isEqualTo(DM);
    JsonNode firstView = JSON.readTree(first.message());
    assertThat(firstView.path("decisionMakerId").asText()).isEqualTo(DM);
    assertThat(firstView.path("result").asText())
        .as("包里/回执里不得有 GM 代打标记")
        .doesNotContain("gm-proxy");

    long headAfterFirst = world.head();
    ToolResult second =
        actAs(
            Map.of(
                "decisionMakerId",
                DM,
                "tool",
                "simos.ga.packet.intent",
                "args",
                Map.of("text", "代打意图 B")));
    assertThat(second.success()).as(second.message()).isTrue();
    assertThat(world.head()).isEqualTo(headAfterFirst + 1L);
    DecisionRecord afterSecond =
        DecisionPackageTestSupport.record(world.shell().coreSimos(), PACKET);
    assertThat(afterSecond.id()).as("同 DM 同 tick 始终一个包").isEqualTo(afterFirst.id());
    assertThat(afterSecond.intent()).isEqualTo("代打意图 B");
    assertThat(afterSecond.packageState().pkg().items()).as("intent 不是 Ask、不重复入包").hasSize(1);
  }

  @Test
  void actAsCannotCallGmOnlyToolsOrOrchestrationTools() throws Exception {
    long headBefore = world.head();
    ToolResult gmOnly =
        actAs(
            Map.of(
                "decisionMakerId",
                DM,
                "tool",
                "simos.gm.packet",
                "args",
                Map.of("packetId", PACKET)));
    assertThat(gmOnly.success()).as("GM-only 工具在决策人桶里必须不可达").isFalse();
    assertThat(JSON.readTree(gmOnly.message()).path("code").asText()).isEqualTo("TOOL_NOT_FOUND");
    assertThat(world.head()).as("被拒代打零 revision").isEqualTo(headBefore);

    ToolResult orchestration =
        actAs(
            Map.of(
                "decisionMakerId", DM, "tool", "simos.ga.run-decision-makers", "args", Map.of()));
    assertThat(orchestration.success()).as("编排族必须被具名拒，不得由代打触发 LLM 跑轮").isFalse();
    assertThat(orchestration.code()).isEqualTo("BAD_REQUEST");
    assertThat(orchestration.message()).contains("代打只落工具调用");
    assertThat(world.head()).isEqualTo(headBefore);
  }

  private ToolResult actAs(Map<String, Object> args) {
    AgentTool tool =
        world.shell().toolsFor(SimosToolSource.Role.GM).stream()
            .filter(candidate -> candidate.name().equals(GmActAsTool.NAME))
            .findFirst()
            .orElseThrow(() -> new AssertionError("GM 面缺 " + GmActAsTool.NAME));
    return GovE2eWorld.exec(tool, args);
  }
}
