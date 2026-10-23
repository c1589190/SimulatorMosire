package io.mosire.simos.app.decision;

import static io.mosire.simos.app.decision.DecisionPackageTestSupport.applyCommitted;
import static io.mosire.simos.app.decision.DecisionPackageTestSupport.item;
import static io.mosire.simos.app.decision.DecisionPackageTestSupport.metaFor;
import static io.mosire.simos.app.decision.DecisionPackageTestSupport.proposedRecord;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import io.mosire.agentlib.decision.ProposeRequest;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.testing.GmActAsWorldFixture;
import io.mosire.simos.app.tools.write.GovWorldBootstrap;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.model.DecisionCallMeta;
import io.mosire.simos.ga.model.DecisionRecord;
import io.mosire.simos.ga.model.PacketStatus;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.spi.CommandTarget;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.SimulationState;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ 设计 §10.2 G1–G6 + §10.5 的 GM 决策面验收：五工具直接复用 AgentLib；读口只读 {@code
 * GaState.decisionPackets}；所有裁决/提交走 {@code ga.ApplyDecisionPackage}。
 */
class DecisionPackageGmPortTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String DM = GmActAsWorldFixture.DM;
  private static final String DM_PROVINCE = GmActAsWorldFixture.DM_PROVINCE;
  private static final String GOV_CENTRAL = GovWorldBootstrap.CENTRAL_GOV_ID;
  private static final String CURRENT = "pkt-" + DM + "-0";
  private static final String OLD = "pkt-" + DM + "-7";
  private static final String MERGED = "pkt-" + DM_PROVINCE + "-0";
  private static final String DECIDED = "pkt-second-0";

  @TempDir Path tempDir;

  private GmActAsWorldFixture.World world;
  private Shell shell;
  private CoreSimos core;

  @BeforeEach
  void startWorld() {
    world = GmActAsWorldFixture.start(tempDir.resolve("world"), new GmActAsWorldFixture.Fake());
    shell = world.shell();
    core = world.shell().coreSimos();
  }

  @AfterEach
  void stopWorld() {
    if (world != null) {
      world.close();
      world = null;
    }
  }

  // ── G1：五工具在 GM 面、角色 {GM}，非 GM 调用被权限面拒 ─────────────────────────────

  @Test
  void fiveDecisionPackageToolsAreGmOnly() {
    Set<String> gmNames =
        shell.toolsFor(io.mosire.simos.app.tools.SimosToolSource.Role.GM).stream()
            .map(AgentTool::name)
            .collect(java.util.stream.Collectors.toSet());
    assertThat(gmNames)
        .as("G1：AgentLib 五工具必须直接注册到 GM 面")
        .contains(DecisionPackageTools.TOOL_NAMES.toArray(String[]::new));

    for (String name : DecisionPackageTools.TOOL_NAMES) {
      AgentTool tool = DecisionPackageTestSupport.gmTool(shell, name);
      assertThat(tool.roles()).as("G1：%s 声明角色恰为 {GM}", name).containsExactly("GM");
    }

    AgentTool list = DecisionPackageTestSupport.gmTool(shell, DecisionPackageTools.LIST_NAME);
    ToolResult nonGm =
        DecisionPackageTestSupport.gmAuthorizer()
            .execute(
                DecisionPackageTestSupport.gmRegistry(shell),
                DecisionPackageTools.LIST_NAME,
                DecisionPackageTestSupport.nonGmContext(list, Map.of(), "DM"));
    assertThat(nonGm.success()).as("G1：非 GM 调用不能靠工具自身绕过角色闸").isFalse();
    assertThat(nonGm.code()).isEqualTo("PERMISSION_DENIED");

    ToolResult gm =
        DecisionPackageTestSupport.executeGm(shell, DecisionPackageTools.LIST_NAME, Map.of());
    assertThat(gm.success()).as("G1：GM 角色可调用：%s", gm.message()).isTrue();
  }

  // ── G2 + G3 + G6：list 当前 tick / get 明文审计 / 只读领域投影 ──────────────────────────

  @Test
  void listIsCurrentTickOnlyGetAuditsFullReadsAndDomainProjectionIsReadOnly() throws Exception {
    String secret = "PLAINTEXT-SECRET-MARKER-42";
    List<PackageItem> currentItems =
        List.of(
            item(
                0,
                "simos.gov.issueMoney",
                Map.of(
                    "govUnitId",
                    GOV_CENTRAL,
                    "currency",
                    "copper",
                    "amountMilli",
                    7L,
                    "reason",
                    secret)),
            item(1, "simos.gov.setBudgetPolicy", Map.of("payloadJson", "{}")));
    DecisionRecord current =
        proposedRecord(
            CURRENT,
            new DecisionMakerId(DM),
            0L,
            head() + 1L,
            currentItems,
            "current intent",
            metaFor(currentItems));
    applyCommitted(core, 0L, current);

    List<PackageItem> oldItems =
        List.of(item(0, "simos.gov.issueMoney", Map.of("reason", "old " + secret)));
    applyCommitted(
        core,
        0L,
        proposedRecord(
            OLD,
            new DecisionMakerId(DM),
            7L,
            head() + 1L,
            oldItems,
            "old intent",
            metaFor(oldItems)));

    List<PackageItem> mergedItems =
        List.of(item(0, "simos.gov.issueMoney", Map.of("amountMilli", 9L)));
    DecisionCallMeta mergedMeta = DecisionPackageTestSupport.mergedMeta("merge-0-1");
    applyCommitted(
        core,
        0L,
        proposedRecord(
            MERGED,
            new DecisionMakerId(DM_PROVINCE),
            0L,
            head() + 1L,
            mergedItems,
            "merged intent",
            Map.of(0, mergedMeta)));

    List<PackageItem> decidedItems = List.of(item(0, "simos.gov.issueMoney", Map.of()));
    DecisionRecord decidedBase =
        proposedRecord(
            DECIDED,
            new DecisionMakerId("second"),
            0L,
            head() + 1L,
            decidedItems,
            "decided intent",
            metaFor(decidedItems));
    DecisionPackageSnapshot decidedSnapshot =
        DecisionPackageTransitions.decide(
            decidedBase.packageState(),
            decidedBase.packageState().contentVersion(),
            new Decision(Set.of(0), "gm-test", "ok"),
            0L);
    applyCommitted(
        core,
        0L,
        DecisionPackageTestSupport.withSnapshot(
            decidedBase, decidedSnapshot, OptionalLong.of(head() + 1L)));

    ToolResult list =
        DecisionPackageTestSupport.executeGm(shell, DecisionPackageTools.LIST_NAME, Map.of());
    assertThat(list.success()).as(list.message()).isTrue();
    JsonNode body = JSON.readTree(list.message());
    assertThat(ids(body.path("pending")))
        .as("G2：pending 只含当前 tick 且非 MERGED")
        .containsExactly(CURRENT);
    assertThat(ids(body.path("awaitingCommit")))
        .as("G2：DECIDED 在当前 tick 的 awaitingCommit")
        .containsExactly(DECIDED);
    assertThat(ids(body.path("pending"))).doesNotContain(OLD, MERGED);
    assertThat(ids(body.path("awaitingCommit"))).doesNotContain(OLD, MERGED);

    ToolResult oldGet =
        DecisionPackageTestSupport.executeGm(
            shell, DecisionPackageTools.GET_NAME, Map.of("packageId", OLD));
    assertThat(oldGet.success()).as("G2：历史包仍可按 id 读（find 是历史入口）").isTrue();
    assertThat(oldGet.message()).contains(OLD);

    long beforeFullReadHead = head();
    ToolResult redacted =
        DecisionPackageTestSupport.executeGm(
            shell, DecisionPackageTools.GET_NAME, Map.of("packageId", CURRENT, "full", false));
    assertThat(redacted.success()).as(redacted.message()).isTrue();
    assertThat(redacted.message()).as("G3：full=false 不得含参数明文").doesNotContain(secret);
    assertThat(head()).as("G3：概览读取不写世界").isEqualTo(beforeFullReadHead);

    ToolResult full =
        DecisionPackageTestSupport.executeGm(
            shell, DecisionPackageTools.GET_NAME, Map.of("packageId", CURRENT, "full", true));
    assertThat(full.success()).as(full.message()).isTrue();
    assertThat(full.message()).as("G3：显式 full=true 才返回 args 明文").contains(secret);
    assertThat(head()).as("G3：full=true 的读审计落一条 revision").isEqualTo(beforeFullReadHead + 1L);
    GaState afterAudit = DecisionPackageTestSupport.ga(core);
    List<String> auditKeys =
        afterAudit.info().keySet().stream()
            .filter(key -> key.startsWith("ga:decision-read."))
            .toList();
    assertThat(auditKeys).as("G3：DecisionReadAudit 落审计地址").hasSize(1);
    assertThat(afterAudit.info().get(auditKeys.get(0)))
        .extracting(entry -> String.valueOf(entry.value()))
        .anySatisfy(value -> assertThat(value).contains(CURRENT).doesNotContain(secret));

    // G6：只读领域投影带全 intent / preview / targets / draftChecks / mergedPlanId / outcomeJson，且不改状态。
    DecisionCallMeta richMeta =
        new DecisionCallMeta(
            List.of(new CommandTarget("gov", GOV_CENTRAL)),
            "{\"preview\":\"ok\"}",
            List.of("scope-ok", "preview-ok"),
            Optional.of("merge-0-9"),
            Optional.of("{\"result\":\"committed\"}"));
    String richId = "pkt-rich-0";
    List<PackageItem> richItems =
        List.of(item(0, "simos.gov.issueMoney", Map.of("amountMilli", 1L)));
    applyCommitted(
        core,
        0L,
        proposedRecord(
            richId,
            new DecisionMakerId("rich"),
            0L,
            head() + 1L,
            richItems,
            "rich intent",
            Map.of(0, richMeta)));
    DecisionRecord richBefore = DecisionPackageTestSupport.record(core, richId);
    long richHead = head();
    ToolResult projection =
        DecisionPackageTestSupport.executeGm(shell, "simos.gm.packet", Map.of("packetId", richId));
    assertThat(projection.success()).as(projection.message()).isTrue();
    JsonNode view = JSON.readTree(projection.message());
    assertThat(view.path("intent").asText()).isEqualTo("rich intent");
    assertThat(view.path("packageState").path("contentVersion").asLong()).isEqualTo(1L);
    assertThat(view.path("calls").get(0).path("previewJson").asText()).contains("preview");
    assertThat(view.path("calls").get(0).path("targets").get(0).path("namespace").asText())
        .isEqualTo("gov");
    assertThat(view.path("calls").get(0).path("draftChecks")).hasSize(2);
    assertThat(view.path("calls").get(0).path("mergedPlanId").asText()).isEqualTo("merge-0-9");
    assertThat(view.path("calls").get(0).path("outcomeJson").asText()).contains("committed");
    assertThat(head()).as("G6：simos.gm.packet 是只读投影，零 revision").isEqualTo(richHead);
    assertThat(DecisionPackageTestSupport.record(core, richId)).isEqualTo(richBefore);
  }

  @Test
  void gmPacketsStaysRegisteredAsReadOnlyProjectionAndDoesNotMigrateState() throws Exception {
    List<PackageItem> items = List.of(item(0, "simos.gov.issueMoney", Map.of("amountMilli", 1L)));
    applyCommitted(
        core,
        0L,
        proposedRecord(
            CURRENT,
            new DecisionMakerId(DM),
            0L,
            head() + 1L,
            items,
            "read-only list",
            metaFor(items)));
    DecisionRecord before = DecisionPackageTestSupport.record(core, CURRENT);
    long headBefore = head();
    ToolResult result = DecisionPackageTestSupport.executeGm(shell, "simos.gm.packets", Map.of());
    assertThat(result.success()).as(result.message()).isTrue();
    JsonNode body = JSON.readTree(result.message());
    assertThat(body.path("packets")).isNotEmpty();
    assertThat(head()).as("GmPacketsTool 是只读投影：零 revision").isEqualTo(headBefore);
    assertThat(DecisionPackageTestSupport.record(core, CURRENT))
        .as("GmPacketsTool 不迁状态、不改包")
        .isEqualTo(before);
  }

  // ── G4 + G5 + 负向：decide_item / decide / commit 一条 revision、重复幂等、具名拒 ─────

  @Test
  void decisionAndCommitAreSingleRevisionAndCommitIsIdempotent() throws Exception {
    String packetId = "pkt-" + DM + "-0";
    List<PackageItem> items =
        List.of(
            item(
                0,
                "simos.gov.issueMoney",
                Map.of(
                    "govUnitId",
                    GOV_CENTRAL,
                    "currency",
                    "copper",
                    "amountMilli",
                    7777L,
                    "reason",
                    "G5 commit test")),
            item(1, "simos.gov.setBudgetPolicy", Map.of("payloadJson", "{}")));
    applyCommitted(
        core,
        0L,
        proposedRecord(
            packetId,
            new DecisionMakerId(DM),
            0L,
            head() + 1L,
            items,
            "commit intent",
            metaFor(items)));

    long headBeforeFirst = head();
    ToolResult approveFirst =
        DecisionPackageTestSupport.executeGm(
            shell,
            DecisionPackageTools.DECIDE_ITEM_NAME,
            Map.of("packageId", packetId, "index", 0, "verdict", "approve", "note", "ok"));
    assertThat(approveFirst.success()).as(approveFirst.message()).isTrue();
    assertThat(head()).as("G4：一次逐条表态 = 一条 revision").isEqualTo(headBeforeFirst + 1L);
    DecisionRecord deciding = DecisionPackageTestSupport.record(core, packetId);
    assertThat(deciding.packageState().pkg().state()).isEqualTo(PackageState.DECIDING);
    assertThat(deciding.packageState().verdicts()).hasSize(1);
    assertThat(JSON.readTree(approveFirst.message()).path("state").asText()).isEqualTo("DECIDING");

    long headBeforeRepeat = head();
    ToolResult repeat =
        DecisionPackageTestSupport.executeGm(
            shell,
            DecisionPackageTools.DECIDE_ITEM_NAME,
            Map.of("packageId", packetId, "index", 0, "verdict", "reject"));
    assertThat(repeat.success()).as("负向：重复表态具名拒").isFalse();
    assertThat(repeat.code()).isEqualTo(DecisionPackages.ITEM_ALREADY_DECIDED);
    assertThat(head()).as("负向：重复表态零 revision").isEqualTo(headBeforeRepeat);

    ToolResult unknown =
        DecisionPackageTestSupport.executeGm(
            shell,
            DecisionPackageTools.DECIDE_ITEM_NAME,
            Map.of("packageId", packetId, "index", 99, "verdict", "approve"));
    assertThat(unknown.success()).isFalse();
    assertThat(unknown.code()).isEqualTo(DecisionPackages.INVALID_ITEM_INDEX);
    assertThat(head()).isEqualTo(headBeforeRepeat);

    ToolResult wholeAfterPartial =
        DecisionPackageTestSupport.executeGm(
            shell,
            DecisionPackageTools.DECIDE_NAME,
            Map.of("packageId", packetId, "approvedIndexes", List.of(0)));
    assertThat(wholeAfterPartial.success()).as("负向：DECIDING 后整包裁决拒").isFalse();
    assertThat(wholeAfterPartial.code()).isEqualTo(DecisionPackages.PARTIAL_DECISION);
    assertThat(head()).isEqualTo(headBeforeRepeat);

    long headBeforeSecond = head();
    ToolResult rejectSecond =
        DecisionPackageTestSupport.executeGm(
            shell,
            DecisionPackageTools.DECIDE_ITEM_NAME,
            Map.of("packageId", packetId, "index", 1, "verdict", "reject", "note", "no"));
    assertThat(rejectSecond.success()).as(rejectSecond.message()).isTrue();
    assertThat(head()).isEqualTo(headBeforeSecond + 1L);
    DecisionRecord decided = DecisionPackageTestSupport.record(core, packetId);
    assertThat(decided.packageState().pkg().state()).isEqualTo(PackageState.DECIDED);
    assertThat(decided.packageState().verdicts()).hasSize(2);
    assertThat(JSON.readTree(rejectSecond.message()).path("approved")).hasSize(1);

    long copperBefore = treasuryCopper();
    long headBeforeCommit = head();
    long revisionsBefore = core.revisions(GmActAsWorldFixture.MAIN).size();
    ToolResult commit =
        DecisionPackageTestSupport.executeGm(
            shell, DecisionPackageTools.COMMIT_NAME, Map.of("packageId", packetId));
    assertThat(commit.success()).as("G5 commit 必须成功：%s", commit.message()).isTrue();
    JsonNode commitView = JSON.readTree(commit.message());
    assertThat(commitView.path("commit").isNull()).as("G5：成功 commit 必须有 receipt").isFalse();
    assertThat(commitView.path("commit").path("status").asText()).isEqualTo("COMMITTED");
    String revisionRef = commitView.path("commit").path("revisionRef").asText();
    assertThat(revisionRef).isNotBlank();
    assertThat(commitView.path("approved")).extracting(JsonNode::asInt).containsExactly(0);
    assertThat(commitView.path("rejected")).extracting(JsonNode::asInt).containsExactly(1);
    assertThat(head()).as("G5：一次 commit = 恰好一条 revision").isEqualTo(headBeforeCommit + 1L);
    assertThat(core.revisions(GmActAsWorldFixture.MAIN).size()).isEqualTo(revisionsBefore + 1L);
    assertThat(treasuryCopper()).as("G5：只执行被批子集").isEqualTo(copperBefore + 7777L);

    DecisionRecord committed = DecisionPackageTestSupport.record(core, packetId);
    assertThat(committed.packageState().pkg().state()).isEqualTo(PackageState.COMMITTED);
    assertThat(committed.packageState().receipt()).isNotNull();
    assertThat(committed.packageState().receipt().revisionRef()).isEqualTo(revisionRef);
    assertThat(committed.packageState().receipt().outcomes())
        .containsExactly(CommitReceipt.ItemOutcome.COMMITTED, CommitReceipt.ItemOutcome.REJECTED);
    assertThat(committed.callMeta().get(0).outcomeJson()).isPresent();
    assertThat(committed.callMeta().get(1).outcomeJson()).isEmpty();
    assertThat(committed.view().status()).isEqualTo(PacketStatus.PARTIALLY_APPROVED);

    long headAfterCommit = head();
    long copperAfterCommit = treasuryCopper();
    ToolResult repeatCommit =
        DecisionPackageTestSupport.executeGm(
            shell, DecisionPackageTools.COMMIT_NAME, Map.of("packageId", packetId));
    assertThat(repeatCommit.success()).as("G5：重复 commit 返回同一 receipt，不是错误").isTrue();
    JsonNode repeatView = JSON.readTree(repeatCommit.message());
    assertThat(repeatView.path("commit").path("revisionRef").asText()).isEqualTo(revisionRef);
    assertThat(head()).as("G5：重复 commit 零 revision").isEqualTo(headAfterCommit);
    assertThat(treasuryCopper()).as("G5：重复 commit 不二次执行").isEqualTo(copperAfterCommit);
  }

  @Test
  void allRejectedPackageEndsRejectedWithoutCommitOrEffects() throws Exception {
    String packetId = "pkt-" + DM + "-0";
    List<PackageItem> items = List.of(item(0, "simos.gov.issueMoney", Map.of("amountMilli", 5L)));
    applyCommitted(
        core,
        0L,
        proposedRecord(
            packetId,
            new DecisionMakerId(DM),
            0L,
            head() + 1L,
            items,
            "all reject",
            metaFor(items)));
    long before = head();
    ToolResult decide =
        DecisionPackageTestSupport.executeGm(
            shell,
            DecisionPackageTools.DECIDE_NAME,
            Map.of("packageId", packetId, "approvedIndexes", List.of()));
    assertThat(decide.success()).as(decide.message()).isTrue();
    assertThat(head()).isEqualTo(before + 1L);
    DecisionRecord rejected = DecisionPackageTestSupport.record(core, packetId);
    assertThat(rejected.packageState().pkg().state()).isEqualTo(PackageState.REJECTED);
    assertThat(rejected.packageState().receipt()).isNotNull();
    assertThat(rejected.packageState().receipt().status()).isEqualTo(CommitReceipt.Status.REJECTED);
    assertThat(rejected.callMeta().get(0).outcomeJson()).isEmpty();

    long headAfterReject = head();
    ToolResult commit =
        DecisionPackageTestSupport.executeGm(
            shell, DecisionPackageTools.COMMIT_NAME, Map.of("packageId", packetId));
    JsonNode commitView = JSON.readTree(commit.message());
    assertThat(commitView.path("commit").path("status").asText())
        .as("负向：被批子集为空 ⇒ REJECTED 回执，不产生 COMMITTED 效果")
        .isEqualTo("REJECTED");
    assertThat(head()).as("负向：全驳后 commit 零 revision").isEqualTo(headAfterReject);
    assertThat(DecisionPackageTestSupport.record(core, packetId)).isEqualTo(rejected);
  }

  @Test
  void conflictReceiptYieldsConflictedSnapshotAndNoCommittedOutcome() {
    String packetId = "pkt-conflict-0";
    List<PackageItem> items = List.of(item(0, "simos.gov.issueMoney", Map.of("amountMilli", 1L)));
    DecisionPackageSnapshot proposed =
        DecisionPackageTestSupport.proposed(
            packetId, new DecisionMakerId("dm-conflict"), 0L, items);
    DecisionPackageSnapshot decided =
        DecisionPackageTransitions.decide(
            proposed, proposed.contentVersion(), new Decision(Set.of(0), "gm-test", ""), 0L);
    var plan = DecisionPackageTransitions.prepareCommit(decided, 0L);
    CommitReceipt conflict =
        new CommitReceipt(
            CommitReceipt.Status.CONFLICTED,
            null,
            List.of(CommitReceipt.ItemOutcome.NOT_COMMITTED),
            "head 已被其他写入者推进");
    DecisionPackageSnapshot after = DecisionPackageTransitions.completeCommit(plan, conflict);

    assertThat(after.pkg().state())
        .as("负向：CONFLICTED 回执必须把包落成 CONFLICTED，而不是伪装成成功")
        .isEqualTo(PackageState.CONFLICTED);
    assertThat(after.receipt()).isEqualTo(conflict);
    assertThat(after.receipt().outcomes())
        .as("负向：冲突时不得出现 COMMITTED 条目结果")
        .containsExactly(CommitReceipt.ItemOutcome.NOT_COMMITTED);
    assertThat(after.receipt().revisionRef()).as("冲突没有可对账的 revision").isNull();
  }

  @Test
  void staleContentVersionAndFrozenContentAreNamedAndZeroChange() {
    String packetId = "pkt-" + DM + "-0";
    List<PackageItem> items =
        List.of(
            item(0, "simos.gov.issueMoney", Map.of("amountMilli", 1L)),
            item(1, "simos.gov.setBudgetPolicy", Map.of("payloadJson", "{}")));
    DecisionRecord base =
        proposedRecord(
            packetId, new DecisionMakerId(DM), 0L, head() + 1L, items, "stale", metaFor(items));
    applyCommitted(core, 0L, base);
    DecisionRecord before = DecisionPackageTestSupport.record(core, packetId);
    long headBefore = head();
    DecisionRecord changed =
        new DecisionRecord(
            before.id(),
            before.branch(),
            before.tick(),
            before.proposerId(),
            "must not land",
            before.createdAtRevision(),
            before.confirmedAtRevision(),
            before.reasonInfoId(),
            before.decidedAtRevision(),
            before.callMeta(),
            before.packageState());
    BatchResult stale = DecisionPackageTestSupport.apply(core, 0L, changed);
    assertThat(stale).isInstanceOf(BatchResult.Rejected.class);
    assertThat(stale.toString()).contains("PACKAGE_VERSION_STALE");
    assertThat(head()).as("负向：stale version 零 revision").isEqualTo(headBefore);
    assertThat(DecisionPackageTestSupport.record(core, packetId)).isEqualTo(before);

    DecisionPackageSnapshot deciding =
        DecisionPackageTransitions.itemDecided(
            before.packageState(),
            before.packageState().contentVersion(),
            0,
            false,
            "gm-test",
            "",
            0L);
    applyCommitted(
        core,
        before.packageState().contentVersion(),
        DecisionPackageTestSupport.withSnapshot(before, deciding, before.decidedAtRevision()));
    DecisionRecord frozen = DecisionPackageTestSupport.record(core, packetId);
    long frozenHead = head();
    ToolResult intent =
        DecisionPackageTestSupport.executeDm(
            shell,
            DecisionPackageTestSupport.decisionRegistry(shell),
            DecisionPackageTestSupport.decisionCaller(shell),
            world.dm(DM),
            "simos.ga.packet.intent",
            Map.of("text", "after freeze"));
    assertThat(intent.success()).as("负向：首次逐条表态后 intent 必须拒").isFalse();
    assertThat(intent.code()).isEqualTo("PACKAGE_CONTENT_FROZEN");
    assertThat(head()).as("负向：冻结后零 revision").isEqualTo(frozenHead);
    assertThat(DecisionPackageTestSupport.record(core, packetId)).isEqualTo(frozen);
  }

  @Test
  void emptyPackageIsRejectedAndRequestCreatesOneItem() {
    ProposeRequest empty = new ProposeRequest(DM, AccessToken.DEFAULT, List.of(), Long.MAX_VALUE);
    assertThatThrownBy(() -> DecisionPackageTransitions.propose("pkt-empty-0", empty, 0L))
        .as("负向：空包必须拒")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("items 不得为空");

    List<PackageItem> one = List.of(item(0, "simos.ga.request", Map.of("text", "请给我一份报告")));
    DecisionRecord requestPackage =
        proposedRecord(
            "pkt-request-0",
            new DecisionMakerId(DM),
            0L,
            head() + 1L,
            one,
            "请给我一份报告",
            metaFor(one));
    applyCommitted(core, 0L, requestPackage);
    assertThat(
            DecisionPackageTestSupport.record(core, "pkt-request-0").packageState().pkg().items())
        .extracting(PackageItem::toolName)
        .containsExactly("simos.ga.request");
  }

  @Test
  void oldDecisionCommandTypesAreAbsentAndCannotBypassApplyDecisionPackage() {
    long headBefore = head();
    for (String type :
        List.of("ga.UpsertDecisionPacket", "ga.SubmitDecisionPacket", "ga.DecideDecisionPacket")) {
      var envelope =
          new io.mosire.simos.core.command.CommandEnvelope(
              java.util.UUID.randomUUID().toString(),
              java.util.UUID.randomUUID().toString(),
              DecisionPackageTestSupport.INITIATOR,
              GmActAsWorldFixture.MAIN,
              new io.mosire.simos.util.state.RevisionId(head()),
              type,
              "{}");
      BatchResult result;
      try {
        result = core.submitBatch(List.of(envelope));
      } catch (RuntimeException rejectedAtBoundary) {
        result = null;
      }
      assertThat(result)
          .as("I11：旧命令 %s 不得被命令注册表接受", type)
          .isNotInstanceOf(BatchResult.Committed.class);
      assertThat(head()).as("I11：旧命令 %s 零 revision", type).isEqualTo(headBefore);
    }
  }

  // ── 小件 ────────────────────────────────────────────────────────────────────────

  private long head() {
    return core.head(GmActAsWorldFixture.MAIN).orElseThrow().value();
  }

  private static List<String> ids(JsonNode rows) {
    List<String> out = new ArrayList<>();
    for (JsonNode row : rows) {
      out.add(row.path("id").asText());
    }
    return out;
  }

  private long treasuryCopper() {
    SimulationState state = DecisionPackageTestSupport.state(core);
    var actor = ((ActorSnapshot) state.module("actor").orElseThrow()).data();
    HouseholdAccountKey key =
        new HouseholdAccountKey(HouseholdId.parse(GovernmentHouseholds.of(GOV_CENTRAL).value()));
    var inventory = actor.accounts().get(key);
    assertThat(inventory).as("中央 GOV 的国库账户必须在").isNotNull();
    return inventory.money().getOrDefault(new CurrencyId("copper"), 0L);
  }
}
