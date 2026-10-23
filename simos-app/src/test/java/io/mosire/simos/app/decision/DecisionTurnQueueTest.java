package io.mosire.simos.app.decision;

import static io.mosire.simos.app.decision.DecisionPackageTestSupport.item;
import static io.mosire.simos.app.decision.DecisionPackageTestSupport.metaFor;
import static io.mosire.simos.app.decision.DecisionPackageTestSupport.proposedRecord;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.decision.DecisionTurnScope;
import io.mosire.agentlib.decision.PackageItem;
import io.mosire.agentlib.decision.PackageState;
import io.mosire.agentlib.decision.PendingDecisionQueue;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.testing.GmActAsWorldFixture;
import io.mosire.simos.ga.decision.DecisionAgentRunner;
import io.mosire.simos.ga.decision.SimosDecisionClock;
import io.mosire.simos.ga.model.DecisionRecord;
import io.mosire.simos.ga.tools.write.RequestTool;
import io.mosire.simos.util.spi.DecisionMakerId;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ 设计 §10.1 D1–D6：DM 的每个 Ask 入本轮队列、回合末 append 同一 tick 包、纯文本兜底、 包控制面 Allow、confirm/reopen
 * 领域标记、冻结后拒绝、abort 载体队列交回。
 */
class DecisionTurnQueueTest {

  private static final String DM = GmActAsWorldFixture.DM;
  private static final String GOV_CENTRAL = "gov-central";
  private static final String CLASS_TWO_TOOL = "simos.gov.setBudgetPolicy";

  @TempDir Path tempDir;

  private GmActAsWorldFixture.World world;
  private Shell shell;
  private ToolRegistry dmRegistry;
  private DecisionCallerFactory factory;
  private DecisionTurnFinalizer finalizer;

  @BeforeEach
  void startWorld() {
    world = GmActAsWorldFixture.start(tempDir.resolve("world"), new GmActAsWorldFixture.Fake());
    shell = world.shell();
    dmRegistry = DecisionPackageTestSupport.decisionRegistry(shell);
    factory = DecisionPackageTestSupport.decisionCaller(shell);
    finalizer =
        new DecisionTurnFinalizer(
            shell.coreSimos(),
            new DecisionCallPreviewer(
                shell.toolsFor(io.mosire.simos.app.tools.SimosToolSource.Role.DECISION_AGENT),
                world.mapId()),
            new io.mosire.simos.app.tools.ga.AppGaToolIdentity(),
            world.mapId());
  }

  @AfterEach
  void stopWorld() {
    DecisionTurnScope.clear();
    if (world != null) {
      world.close();
      world = null;
    }
  }

  // ── D1：绑定队列的 Ask 只入队，不执行、不投 PendingApprovals ───────────────────────────

  @Test
  void boundAskOnlyEntersTheTurnQueueWithoutExecutionOrPendingApprovals() {
    long headBefore = head();
    PendingDecisionQueue queue =
        new PendingDecisionQueue(DM, AccessToken.DEFAULT, SimosDecisionClock.QUEUE_DEADLINE_MILLIS);
    DecisionTurnScope.bind(queue);
    try {
      ToolResult classTwo =
          DecisionPackageTestSupport.executeDm(
              shell, dmRegistry, factory, world.dm(DM), CLASS_TWO_TOOL, budgetPolicyArgs());
      assertThat(classTwo.success())
          .as("D1：Ask 被受理（进入决策包），不是失败也不是执行：%s", classTwo.message())
          .isTrue();
      assertThat(classTwo.message()).contains("已加入决策包");

      ToolResult request =
          DecisionPackageTestSupport.executeDm(
              shell,
              dmRegistry,
              factory,
              world.dm(DM),
              RequestTool.NAME,
              Map.of("text", "请给我一份本旬报告"));
      assertThat(request.success()).as("D1：纯文本 Ask 同样入队：%s", request.message()).isTrue();
    } finally {
      DecisionTurnScope.clear(queue);
    }

    assertThat(queue.size()).as("D1：两条 Ask 都进同一个队列").isEqualTo(2);
    assertThat(head()).as("D1：Ask 未执行 ⇒ 世界零 revision").isEqualTo(headBefore);
    assertThat(shell.pendingApprovals().pending()).as("D1：绑定队列后不得再投单条 PendingApprovals").isEmpty();
    assertThat(DecisionPackageTestSupport.record(shell.coreSimos(), packetId(DM)))
        .as("D1：队列只是回合内对象，回合末才建包；此刻不应有包")
        .isNull();
  }

  // ── D2：同 DM 同 tick 四轮，四个 Ask 进同一个包，index 稳定递增 ──────────────────────

  @Test
  void fourRoundsAppendToTheSamePacketWithStableIncreasingIndexes() {
    long headBefore = head();
    for (int round = 0; round < 4; round++) {
      PendingDecisionQueue queue =
          new PendingDecisionQueue(
              DM, AccessToken.DEFAULT, SimosDecisionClock.QUEUE_DEADLINE_MILLIS);
      queue.register(
          RequestTool.NAME,
          Map.of("text", "第 " + round + " 轮请求"),
          new io.mosire.agentlib.approval.ToolGate.Ask(
              RequestTool.NAME, "round " + round, io.mosire.agentlib.approval.AskKind.SENSITIVE));
      DecisionAgentRunner.DecisionTurn turn =
          new DecisionAgentRunner.DecisionTurn(
              "conv-" + round,
              0,
              List.of(),
              Optional.empty(),
              Optional.of("第 " + round + " 轮收口文本"),
              queue);
      finalizer.finalizeTurn(GmActAsWorldFixture.MAIN, new DecisionMakerId(DM), turn);

      DecisionRecord record = record(DM);
      assertThat(record).as("D2：第 %d 轮后包必须存在", round + 1).isNotNull();
      assertThat(record.id().value()).as("D2：包 id 固定 pkt-<dm>-<tick>").isEqualTo(packetId(DM));
      assertThat(record.packageState().pkg().items())
          .as("D2：第 %d 轮后应有且只有 %d 条", round + 1, round + 1)
          .hasSize(round + 1);
      assertThat(record.packageState().pkg().items())
          .extracting(PackageItem::index)
          .containsExactlyElementsOf(
              java.util.stream.IntStream.rangeClosed(0, round).boxed().toList());
      assertThat(record.packageState().pkg().items().get(round).toolName())
          .isEqualTo(RequestTool.NAME);
      assertThat(record.callMeta().keySet())
          .as("D2：每轮 append 的 callMeta 键集合 == 包 items index 集合")
          .containsExactlyInAnyOrderElementsOf(
              record.packageState().pkg().items().stream().map(PackageItem::index).toList());
      assertThat(head()).as("D2：每轮恰好 append 一条 revision").isEqualTo(headBefore + round + 1L);
    }
  }

  // ── D3：纯文本兜底入包；无文本空回合不建包 ─────────────────────────────────────────

  @Test
  void pureTextFallbackCreatesOneRequestItemAndEmptyTurnWritesNothing() {
    long headBefore = head();
    PendingDecisionQueue empty =
        new PendingDecisionQueue(DM, AccessToken.DEFAULT, SimosDecisionClock.QUEUE_DEADLINE_MILLIS);
    finalizer.finalizeTurn(
        GmActAsWorldFixture.MAIN,
        new DecisionMakerId(DM),
        new DecisionAgentRunner.DecisionTurn(
            "text-only", 0, List.of(), Optional.empty(), Optional.of("  请准备秋收报告  "), empty));

    DecisionRecord created = record(DM);
    assertThat(created).isNotNull();
    assertThat(created.packageState().pkg().items()).hasSize(1);
    PackageItem only = created.packageState().pkg().items().get(0);
    assertThat(only.toolName()).isEqualTo(RequestTool.NAME);
    assertThat(only.args()).containsEntry("text", "请准备秋收报告");
    assertThat(created.intent()).isEqualTo("请准备秋收报告");
    assertThat(head()).isEqualTo(headBefore + 1L);

    long afterFallbackHead = head();
    PendingDecisionQueue blank =
        new PendingDecisionQueue(DM, AccessToken.DEFAULT, SimosDecisionClock.QUEUE_DEADLINE_MILLIS);
    finalizer.finalizeTurn(
        GmActAsWorldFixture.MAIN,
        new DecisionMakerId(DM),
        new DecisionAgentRunner.DecisionTurn(
            "blank", 0, List.of(), Optional.empty(), Optional.of("   "), blank));
    assertThat(head()).as("D3：空队列 + 空白文本不得制造空包/revision").isEqualTo(afterFallbackHead);
    assertThat(record(DM).packageState().pkg().items()).hasSize(1);
  }

  // ── D4 + D5：控制面 Allow、领域标记、冻结后全部具名拒、裁决后 reopen 拒 ─────────────────

  @Test
  void controlSurfaceIsAllowDomainOnlyAndFrozenAfterFirstVerdict() {
    String packetId = packetId(DM);
    List<PackageItem> items =
        List.of(
            item(0, "simos.gov.issueMoney", issueMoneyArgs(11L)),
            item(1, RequestTool.NAME, Map.of("text", "B")),
            item(2, RequestTool.NAME, Map.of("text", "C")));
    DecisionRecord base =
        proposedRecord(
            packetId,
            new DecisionMakerId(DM),
            currentTick(),
            head() + 1L,
            items,
            "initial intent",
            metaFor(items));
    DecisionPackageTestSupport.applyCommitted(shell.coreSimos(), 0L, base);

    for (String tool :
        List.of(
            "simos.ga.packet.intent",
            "simos.ga.packet.revise",
            "simos.ga.packet.drop",
            "simos.ga.packet.confirm",
            "simos.ga.packet.reopen",
            "simos.ga.packet.my")) {
      var real = decisionTool(tool);
      assertThat(real.gate(DecisionPackageTestSupport.gmContext(real, Map.of())))
          .as("D4/D5：%s 必须是 Allow（否则会自己入包）", tool)
          .isEqualTo(io.mosire.agentlib.approval.ToolGate.ALLOW);
    }

    long beforeIntent = head();
    ToolResult intent =
        DecisionPackageTestSupport.executeDm(
            shell,
            dmRegistry,
            factory,
            world.dm(DM),
            "simos.ga.packet.intent",
            Map.of("text", "new intent"));
    assertThat(intent.success()).as(intent.message()).isTrue();
    DecisionRecord afterIntent = record(DM);
    assertThat(afterIntent.intent()).isEqualTo("new intent");
    assertThat(afterIntent.packageState().pkg().state())
        .as("D5：intent 不改 AgentLib PackageState")
        .isEqualTo(base.packageState().pkg().state());
    assertThat(afterIntent.packageState().contentVersion())
        .isEqualTo(base.packageState().contentVersion());
    assertThat(afterIntent.packageState().verdicts()).isEmpty();
    assertThat(head()).isEqualTo(beforeIntent + 1L);

    long beforeConfirm = head();
    ToolResult confirm =
        DecisionPackageTestSupport.executeDm(
            shell, dmRegistry, factory, world.dm(DM), "simos.ga.packet.confirm", Map.of());
    assertThat(confirm.success()).as(confirm.message()).isTrue();
    DecisionRecord confirmed = record(DM);
    assertThat(confirmed.confirmedAtRevision()).isPresent();
    assertThat(confirmed.packageState())
        .as("D5：confirm 只写领域标记")
        .isEqualTo(afterIntent.packageState());
    assertThat(head()).isEqualTo(beforeConfirm + 1L);

    long beforeReopen = head();
    ToolResult reopen =
        DecisionPackageTestSupport.executeDm(
            shell, dmRegistry, factory, world.dm(DM), "simos.ga.packet.reopen", Map.of());
    assertThat(reopen.success()).as(reopen.message()).isTrue();
    DecisionRecord reopened = record(DM);
    assertThat(reopened.confirmedAtRevision()).isEmpty();
    assertThat(reopened.packageState()).as("D5：reopen 只清领域标记").isEqualTo(confirmed.packageState());
    assertThat(head()).isEqualTo(beforeReopen + 1L);

    long beforeRevise = head();
    ToolResult revise =
        DecisionPackageTestSupport.executeDm(
            shell,
            dmRegistry,
            factory,
            world.dm(DM),
            "simos.ga.packet.revise",
            Map.of("callIndex", 0, "tool", "simos.gov.issueMoney", "args", issueMoneyArgs(22L)));
    assertThat(revise.success()).as(revise.message()).isTrue();
    assertThat(
            ((Number) record(DM).packageState().pkg().items().get(0).args().get("amountMilli"))
                .longValue())
        .isEqualTo(22L);
    assertThat(record(DM).packageState().contentVersion())
        .isEqualTo(reopened.packageState().contentVersion() + 1L);
    assertThat(head()).isEqualTo(beforeRevise + 1L);

    ToolResult drop =
        DecisionPackageTestSupport.executeDm(
            shell,
            dmRegistry,
            factory,
            world.dm(DM),
            "simos.ga.packet.drop",
            Map.of("callIndex", 1));
    assertThat(drop.success()).as(drop.message()).isTrue();
    assertThat(record(DM).packageState().pkg().items())
        .extracting(PackageItem::index)
        .as("I3：drop 留洞，不重排 index")
        .containsExactly(0, 2);

    // GM 首次逐条表态 ⇒ DECIDING，内容冻结。
    ToolResult gmApprove =
        DecisionPackageTestSupport.executeGm(
            shell,
            "decision_package_decide_item",
            Map.of("packageId", packetId, "index", 0, "verdict", "approve", "note", "freeze"));
    assertThat(gmApprove.success()).as(gmApprove.message()).isTrue();
    assertThat(record(DM).packageState().pkg().state()).isEqualTo(PackageState.DECIDING);

    long frozenHead = head();
    Map<String, Map<String, Object>> frozenCalls = new LinkedHashMap<>();
    frozenCalls.put("simos.ga.packet.intent", Map.of("text", "blocked"));
    frozenCalls.put(
        "simos.ga.packet.revise",
        Map.of("callIndex", 0, "tool", "simos.gov.issueMoney", "args", issueMoneyArgs(33L)));
    frozenCalls.put("simos.ga.packet.drop", Map.of("callIndex", 2));
    for (Map.Entry<String, Map<String, Object>> call : frozenCalls.entrySet()) {
      ToolResult rejected =
          DecisionPackageTestSupport.executeDm(
              shell, dmRegistry, factory, world.dm(DM), call.getKey(), call.getValue());
      assertThat(rejected.success()).as("D4：冻结后 %s 必须拒", call.getKey()).isFalse();
      assertThat(rejected.code()).isEqualTo("PACKAGE_CONTENT_FROZEN");
    }
    assertThat(head()).as("D4：冻结后的控制面尝试全部零 revision").isEqualTo(frozenHead);

    // 第二条表态完成 ⇒ DECIDED；已裁决后 reopen 拒。
    ToolResult gmReject =
        DecisionPackageTestSupport.executeGm(
            shell,
            "decision_package_decide_item",
            Map.of("packageId", packetId, "index", 2, "verdict", "reject", "note", "done"));
    assertThat(gmReject.success()).as(gmReject.message()).isTrue();
    assertThat(record(DM).packageState().pkg().state()).isEqualTo(PackageState.DECIDED);
    long decidedHead = head();
    ToolResult reopenAfterDecision =
        DecisionPackageTestSupport.executeDm(
            shell, dmRegistry, factory, world.dm(DM), "simos.ga.packet.reopen", Map.of());
    assertThat(reopenAfterDecision.success()).as("D5：已裁决后 reopen 必须拒").isFalse();
    assertThat(reopenAfterDecision.code()).isEqualTo("BAD_REQUEST");
    assertThat(head()).as("D5：reopen 拒时零 revision").isEqualTo(decidedHead);
  }

  // ── D6 / abort：队列在异常路径交回 finalizer；冻结后 flush 具名拒、不静默丢 ─────────────

  @Test
  void abortedTurnQueueIsSettledAndFrozenFlushIsNamedNotSilent() {
    PendingDecisionQueue aborted =
        new PendingDecisionQueue(DM, AccessToken.DEFAULT, SimosDecisionClock.QUEUE_DEADLINE_MILLIS);
    aborted.register(
        RequestTool.NAME,
        Map.of("text", "中止前已登记的请求"),
        new io.mosire.agentlib.approval.ToolGate.Ask(
            RequestTool.NAME, "abort", io.mosire.agentlib.approval.AskKind.SENSITIVE));
    long beforeAbort = head();
    finalizer.finalizeAborted(GmActAsWorldFixture.MAIN, new DecisionMakerId(DM), aborted);
    assertThat(head()).as("abort 路径必须也落一条 ApplyDecisionPackage").isEqualTo(beforeAbort + 1L);
    assertThat(record(DM).packageState().pkg().items()).hasSize(1);
    assertThat(record(DM).packageState().pkg().items().get(0).args())
        .containsEntry("text", "中止前已登记的请求");

    // 先让 GM 开始逐条裁决（当前只有 1 条，需要再造一条避免直接终局）。
    List<PackageItem> more = List.of(item(1, RequestTool.NAME, Map.of("text", "second")));
    DecisionRecord current = record(DM);
    var appended =
        io.mosire.agentlib.decision.DecisionPackageTransitions.append(
            current.packageState(), more, current.packageState().contentVersion(), currentTick());
    Map<Integer, io.mosire.simos.ga.model.DecisionCallMeta> callMeta =
        new LinkedHashMap<>(current.callMeta());
    callMeta.put(1, DecisionPackageTestSupport.meta());
    DecisionRecord withTwoItems =
        new DecisionRecord(
            current.id(),
            current.branch(),
            current.tick(),
            current.proposerId(),
            current.intent(),
            current.createdAtRevision(),
            current.confirmedAtRevision(),
            current.reasonInfoId(),
            current.decidedAtRevision(),
            callMeta,
            appended);
    DecisionPackageTestSupport.applyCommitted(
        shell.coreSimos(), current.packageState().contentVersion(), withTwoItems);
    ToolResult gmApprove =
        DecisionPackageTestSupport.executeGm(
            shell,
            "decision_package_decide_item",
            Map.of("packageId", packetId(DM), "index", 0, "verdict", "approve", "note", "freeze"));
    assertThat(gmApprove.success()).as(gmApprove.message()).isTrue();
    DecisionRecord frozen = record(DM);
    long frozenHead = head();

    PendingDecisionQueue late =
        new PendingDecisionQueue(DM, AccessToken.DEFAULT, SimosDecisionClock.QUEUE_DEADLINE_MILLIS);
    late.register(
        RequestTool.NAME,
        Map.of("text", "不该静默丢失"),
        new io.mosire.agentlib.approval.ToolGate.Ask(
            RequestTool.NAME, "late", io.mosire.agentlib.approval.AskKind.SENSITIVE));
    assertThatThrownBy(
            () ->
                finalizer.finalizeTurn(
                    GmActAsWorldFixture.MAIN,
                    new DecisionMakerId(DM),
                    new DecisionAgentRunner.DecisionTurn(
                        "late", 0, List.of(), Optional.empty(), Optional.of("late text"), late)))
        .as("D6 的现有落点：没有回合启动守卫，但回合末必须具名拒而不是静默丢 Ask")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PACKAGE_CONTENT_FROZEN");
    assertThat(head()).as("冻结后 flush 零 revision").isEqualTo(frozenHead);
    assertThat(record(DM)).isEqualTo(frozen);
  }

  // ── 小件 ────────────────────────────────────────────────────────────────────────

  private io.mosire.agentlib.tool.AgentTool decisionTool(String name) {
    return shell.toolsFor(io.mosire.simos.app.tools.SimosToolSource.Role.DECISION_AGENT).stream()
        .filter(tool -> tool.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("决策人面缺工具: " + name));
  }

  private long head() {
    return shell.coreSimos().head(GmActAsWorldFixture.MAIN).orElseThrow().value();
  }

  private long currentTick() {
    return DecisionPackageTestSupport.state(shell.coreSimos()).meta().timestamp().tick();
  }

  private DecisionRecord record(String dm) {
    return DecisionPackageTestSupport.record(shell.coreSimos(), packetId(dm));
  }

  private static String packetId(String dm) {
    return "pkt-" + dm + "-0";
  }

  private static Map<String, Object> issueMoneyArgs(long amountMilli) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("govUnitId", GOV_CENTRAL);
    args.put("currency", "copper");
    args.put("amountMilli", amountMilli);
    args.put("reason", "D4/D5 控制面测试");
    return args;
  }

  private static Map<String, Object> budgetPolicyArgs() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("unitId", GOV_CENTRAL);
    Map<String, Object> category = new LinkedHashMap<>();
    category.put("category", "ADMIN_SALARY");
    category.put("minPerCycle", 0L);
    category.put("capPerCycle", 5_000_000L);
    payload.put("orderedCategories", List.of(category));
    payload.put("mode", "PATCH");
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("payloadJson", json(payload));
    args.put("reason", "D1 Ask 队列测试");
    return args;
  }

  private static String json(Map<String, Object> value) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
