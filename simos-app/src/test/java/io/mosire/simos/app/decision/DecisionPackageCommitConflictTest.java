package io.mosire.simos.app.decision;

import static io.mosire.simos.app.decision.DecisionPackageTestSupport.applyCommitted;
import static io.mosire.simos.app.decision.DecisionPackageTestSupport.item;
import static io.mosire.simos.app.decision.DecisionPackageTestSupport.metaFor;
import static io.mosire.simos.app.decision.DecisionPackageTestSupport.proposedRecord;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.decision.CommitReceipt;
import io.mosire.agentlib.decision.Decision;
import io.mosire.agentlib.decision.DecisionPackageSnapshot;
import io.mosire.agentlib.decision.DecisionPackageTools;
import io.mosire.agentlib.decision.DecisionPackageTransitions;
import io.mosire.agentlib.decision.DecisionPackages;
import io.mosire.agentlib.decision.PackageItem;
import io.mosire.agentlib.decision.PackageState;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.testing.GmActAsWorldFixture;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.model.DecisionRecord;
import io.mosire.simos.ga.spi.PutInfoHandler;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ 设计 §6.3 / §10.5：effect 批 commit 冲突的核心端到端。
 *
 * <p>真实链路：AgentLib {@code decision_package_commit} → {@link CoreDecisionSubmissionPort} → {@link
 * DecisionEffectExecutor} → core batch。夹具用“规划期并发写者”确定性地把 head 推走； 另用“规划期同时改包”的写者验证旧 plan 不覆盖新状态。
 */
class DecisionPackageCommitConflictTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String DM = GmActAsWorldFixture.DM;
  private static final String PACKET = "pkt-" + DM + "-0";
  private static final BranchId MAIN = GmActAsWorldFixture.MAIN;
  private static final String INITIATOR = "test:commit-conflict";

  @TempDir Path tempDir;

  private GmActAsWorldFixture.World world;
  private CoreSimos core;
  private CoreDecisionSubmissionPort submissions;
  private AgentTool commitTool;

  @BeforeEach
  void startWorld() {
    world = GmActAsWorldFixture.start(tempDir.resolve("world"), new GmActAsWorldFixture.Fake());
    core = world.shell().coreSimos();
  }

  @AfterEach
  void stopWorld() {
    if (world != null) {
      world.close();
    }
  }

  @Test
  void commitConflictIsPersistedAsConflictedWithZeroEffectAndNoOutcome() throws Exception {
    PlanningMutationTool target =
        new PlanningMutationTool(
            "test.conflict.effect",
            () -> submitPutInfo("ga:test.conflict-writer", "writer", "advanced"));
    mutationTools.put(target.name(), target);
    seedDecidedPackage(target.name());
    long headBeforeCommit = head();

    ToolResult result = callCommit();
    assertThat(result.success()).as(result.message()).isTrue();
    JsonNode body = JSON.readTree(result.message());
    assertThat(body.path("commit").path("status").asText()).isEqualTo("CONFLICTED");

    DecisionRecord committed = record();
    assertThat(committed.packageState().pkg().state())
        .as("commit 冲突必须把包落成 CONFLICTED")
        .isEqualTo(PackageState.CONFLICTED);
    CommitReceipt receipt = committed.packageState().receipt();
    assertThat(receipt).isNotNull();
    assertThat(receipt.status()).isEqualTo(CommitReceipt.Status.CONFLICTED);
    assertThat(receipt.revisionRef()).as("冲突没有可对账的 revision").isNull();
    assertThat(receipt.outcomes())
        .as("冲突时条目结果只能是 NOT_COMMITTED")
        .containsOnly(CommitReceipt.ItemOutcome.NOT_COMMITTED);

    assertThat(committed.callMeta().get(0).outcomeJson()).as("零 effect ⇒ 不得回写 outcome").isEmpty();
    GaState after = DecisionPackageTestSupport.ga(core);
    assertThat(infoHasAddress(after, "ga:test.conflict-writer"))
        .as("夹具自证：并发写者确实推了 head（否则测不到冲突）")
        .isTrue();
    assertThat(infoHasAddress(after, "ga:test.commit-effect")).as("冲突批中的 effect 必须零落盘").isFalse();
    assertThat(head())
        .as("head = 并发写者 1 + conflict 落定 1；effect 批 0")
        .isEqualTo(headBeforeCommit + 2L);
  }

  @Test
  void concurrentPackageChangeRejectsWithNamedStaleAndNeverOverwrites() throws Exception {
    PlanningMutationTool target =
        new PlanningMutationTool(
            "test.conflict.stale",
            () -> {
              submitPutInfo("ga:test.conflict-writer-2", "writer", "advanced");
              mutatePackageVersionConcurrently();
            });
    mutationTools.put(target.name(), target);
    seedDecidedPackage(target.name());
    long headBeforeCommit = head();
    long versionBeforeCommit = record().packageState().contentVersion();

    ToolResult result = callCommit();
    assertThat(result.success()).as("并发改动包 ⇒ commit 必须具名拒").isFalse();
    assertThat(result.code())
        .as("旧 plan 不得覆盖新包；具名码只能是 stale/commit_failed")
        .isIn(DecisionPackages.PACKAGE_VERSION_STALE, DecisionPackages.COMMIT_FAILED);

    DecisionRecord after = record();
    assertThat(after.packageState().contentVersion())
        .as("并发写者的新版本必须保留")
        .isEqualTo(versionBeforeCommit + 1L);
    assertThat(after.intent()).as("旧 plan 不得覆盖并发写者的领域字段").isEqualTo("concurrent change");
    assertThat(after.callMeta().get(0).outcomeJson()).as("零 effect ⇒ 零 outcome").isEmpty();
    assertThat(head()).as("head = 并发写者 1 + 并发包更新 1；冲突落定批 0").isEqualTo(headBeforeCommit + 2L);
    assertThat(infoHasAddress(DecisionPackageTestSupport.ga(core), "ga:test.commit-effect"))
        .as("冲突批中的 effect 必须零落盘")
        .isFalse();
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────

  private void seedDecidedPackage(String toolName) {
    List<PackageItem> items = List.of(item(0, toolName, Map.of("q", 1L, "r", 1L)));
    DecisionRecord proposed =
        proposedRecord(
            PACKET,
            new DecisionMakerId(DM),
            0L,
            head() + 1L,
            items,
            "commit conflict",
            metaFor(items));
    applyCommitted(core, 0L, proposed);
    DecisionRecord current = DecisionPackageTestSupport.record(core, PACKET);
    DecisionPackageSnapshot decided =
        DecisionPackageTransitions.decide(
            current.packageState(),
            current.packageState().contentVersion(),
            new Decision(Set.of(0), "gm-test", ""),
            0L);
    applyCommitted(
        core,
        current.packageState().contentVersion(),
        DecisionPackageTestSupport.withSnapshot(current, decided, OptionalLong.of(head() + 1L)));

    PlanningMutationTool target = requireTarget(toolName);
    DecisionCallPreviewer previewer = new DecisionCallPreviewer(List.of(target), "Map1");
    submissions = new CoreDecisionSubmissionPort(core, MAIN, INITIATOR, previewer);
    DecisionPackageTools tools =
        new DecisionPackageTools(
            new CoreDecisionPackageReadPort(core, MAIN), submissions, Set.of("GM"));
    commitTool = tools.commitTool();
  }

  /** 当前用例注入的 mutation 工具（只在同一测试线程内使用）。 */
  private final Map<String, PlanningMutationTool> mutationTools = new java.util.HashMap<>();

  private PlanningMutationTool requireTarget(String name) {
    PlanningMutationTool tool = mutationTools.get(name);
    if (tool == null) {
      throw new IllegalStateException("测试夹具未注册 mutation 工具: " + name);
    }
    return tool;
  }

  private ToolResult callCommit() {
    ToolContext context =
        DecisionPackageTestSupport.gmContext(commitTool, Map.of("packageId", PACKET));
    return commitTool.execute(context);
  }

  private void submitPutInfo(String address, String key, String value) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("address", address);
    payload.put("key", key);
    payload.put("value", value);
    payload.put("tick", 0L);
    long expected = head();
    try {
      CommandEnvelope envelope =
          new CommandEnvelope(
              UUID.randomUUID().toString(),
              UUID.randomUUID().toString(),
              INITIATOR,
              MAIN,
              new RevisionId(expected),
              PutInfoHandler.TYPE,
              JSON.writeValueAsString(payload));
      BatchResult result = core.submitBatch(List.of(envelope));
      assertThat(result).isInstanceOf(BatchResult.Committed.class);
    } catch (Exception e) {
      throw new AssertionError("并发写者提交失败", e);
    }
  }

  private void mutatePackageVersionConcurrently() {
    DecisionRecord current = record();
    DecisionPackageSnapshot bumped =
        new DecisionPackageSnapshot(
            current.packageState().pkg(),
            current.packageState().contentVersion() + 1L,
            current.packageState().verdicts(),
            current.packageState().receipt(),
            current.packageState().expiryReason());
    DecisionRecord changed =
        new DecisionRecord(
            current.id(),
            current.branch(),
            current.tick(),
            current.proposerId(),
            "concurrent change",
            current.createdAtRevision(),
            current.confirmedAtRevision(),
            current.reasonInfoId(),
            current.decidedAtRevision(),
            current.callMeta(),
            bumped);
    applyCommitted(core, current.packageState().contentVersion(), changed);
  }

  private DecisionRecord record() {
    return DecisionPackageTestSupport.record(core, PACKET);
  }

  private long head() {
    return core.head(MAIN).orElseThrow().value();
  }

  private static boolean infoHasAddress(GaState state, String address) {
    return state.info().keySet().stream().anyMatch(key -> key.startsWith(address));
  }

  /** 一个在 planOnly 执行期跑宿主 mutation 的假效果工具。 */
  private final class PlanningMutationTool implements AgentTool {

    private final String name;
    private final Runnable onPlan;

    private PlanningMutationTool(String name, Runnable onPlan) {
      this.name = name;
      this.onPlan = onPlan;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public ResourceManifest resources() {
      return ResourceManifest.NONE;
    }

    @Override
    public ToolResult execute(ToolContext context) {
      onPlan.run();
      try {
        Map<String, Object> effectPayload = new LinkedHashMap<>();
        effectPayload.put("address", "ga:test.commit-effect");
        effectPayload.put("key", "effect");
        effectPayload.put("value", "should-not-land");
        effectPayload.put("tick", 0L);
        String view =
            JSON.writeValueAsString(
                Map.of(
                    "plannedCommands",
                    List.of(
                        Map.of(
                            "type",
                            PutInfoHandler.TYPE,
                            "payloadJson",
                            JSON.writeValueAsString(effectPayload)))));
        return ToolResult.ok(view);
      } catch (Exception e) {
        throw new IllegalStateException("构造 plannedCommands 失败", e);
      }
    }
  }
}
