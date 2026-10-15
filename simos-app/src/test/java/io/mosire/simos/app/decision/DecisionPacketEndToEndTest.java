package io.mosire.simos.app.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.approval.ApprovalChannel;
import io.mosire.agentlib.approval.ApprovalCoordinator;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.approval.AutoApproveGate;
import io.mosire.agentlib.approval.ConfirmGate;
import io.mosire.agentlib.approval.PendingApprovals;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolExecutionGuard;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.testing.GmActAsWorldFixture;
import io.mosire.simos.app.testing.GovE2eWorld;
import io.mosire.simos.app.tools.SimosToolSource;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.app.tools.write.GovWorldBootstrap;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.id.DecisionPacketId;
import io.mosire.simos.ga.model.DecisionMaker;
import io.mosire.simos.ga.model.DecisionPacket;
import io.mosire.simos.ga.model.FormattedCall;
import io.mosire.simos.ga.model.PacketStatus;
import io.mosire.simos.ga.tools.ToolBuckets;
import io.mosire.simos.ga.tools.write.ConfirmPacketTool;
import io.mosire.simos.ga.tools.write.GmDecidePacketTool;
import io.mosire.simos.ga.tools.write.GmPacketExecuteTool;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.state.SimulationState;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>P2（2026-10-15 测试面）：决策包端到端——{@code propose → confirm → decide → execute} 一条链走完</b> （把批 3b
 * 的仓库外判据沉淀成仓内可跑用例）。
 *
 * <h2>真实流程有一步 {@code confirm}（★ 设计书 §11.3 的更正）</h2>
 *
 * <p>{@code propose} 落的是 <b>{@code DRAFT}</b>，而 GM <b>不能裁决 DRAFT</b>（{@code GmDecidePacketTool}：只有
 * {@code PENDING} 可裁决）⇒ 真实流程是 {@code DM propose → DM confirm（→ PENDING）→ GM decide → GM execute}。
 * 本类逐跳断言包状态，把这条状态机钉住（"propose 完直接 decide" 的写法会在这里红）。
 *
 * <h2>判据</h2>
 *
 * <ol>
 *   <li><b>恰好一条 revision</b>：{@code gm.packet.execute} 一次 ⇒ head +1 且 {@code revisions} 行数
 *       +1（两个独立口径）， 且 {@code outcomeJson} 真的回写到那条 call 上；
 *   <li><b>重试不重复落盘</b>：第二次 execute ⇒ 非成功（{@code BAD_REQUEST}）且 head / revisions / 国库三个数都不动；
 *   <li>★ <b>世界真的变了</b>（具体数值）：国库 copper {@code 100000 → 107777}（{@code issueMoney} 7777 毫）；
 *   <li>★★ <b>DM 侧零阻塞的判别对照</b>：同一条链上<b>直接调</b>类2 工具（{@code setBudgetPolicy}）⇒ {@code
 *       APPROVAL_DENIED} 且 <b>asks=1</b>（阻塞路径真的存在）；走 {@code propose} ⇒ success 且 <b>asks 不增</b>
 *       （决策包路径零审批）+ {@code Shell.pendingApprovals()} 一个不挂。
 * </ol>
 *
 * <p>★ 世界 = {@link GmActAsWorldFixture} 的真壳小世界（真审批链、真工具桶、真 store；LLM 是假的），DM 侧调用走真 {@link
 * DecisionCallerFactory}。
 */
class DecisionPacketEndToEndTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String DM = GmActAsWorldFixture.DM;
  private static final String GOV_CENTRAL = GovWorldBootstrap.CENTRAL_GOV_ID;

  /** 类2 工具（{@code {DM,GM} + Ask(SENSITIVE)}）：零阻塞对照的靶子。 */
  private static final String CLASS2_TOOL = "simos.gov.setBudgetPolicy";

  /** 走到 execute 的那条 call（类2 + 世界写；发行 7777 毫铜 ⇒ 国库 +7777）。 */
  private static final String EFFECT_TOOL = "simos.gov.issueMoney";

  /** 发行量（设计书 §11.2 的 B4 逐跳数字：国库 copper 100000 → 107777）。 */
  private static final long ISSUE_MILLI = 7777L;

  @TempDir Path tempDir;

  private GmActAsWorldFixture.World world;
  private CoreSimos core;
  private ToolRegistry dmRegistry;
  private DecisionCallerFactory factory;
  private String mapId;

  @BeforeEach
  void startWorld() {
    world = GmActAsWorldFixture.start(tempDir.resolve("world"), new GmActAsWorldFixture.Fake());
    core = world.shell().coreSimos();
    mapId = world.mapId();
    dmRegistry = new ToolRegistry();
    dmRegistry.registerAll(world.shell().toolsFor(SimosToolSource.Role.DECISION_AGENT));
    factory =
        DecisionCallerFactory.forDecisionFace(
            world.shell().toolAuthorizer(),
            ToolBuckets.namesOf(world.shell().toolsFor(SimosToolSource.Role.DECISION_AGENT)));
  }

  @AfterEach
  void stopWorld() {
    if (world != null) {
      world.close();
      world = null;
    }
  }

  @Test
  void theWholeChainLandsExactlyOneRevisionAndTheWorldReallyChanges() throws Exception {
    long copperBefore = treasuryCopper();
    System.out.println("[P2] treasuryCopper before=" + copperBefore);

    // ① propose（DM 侧）⇒ DRAFT（★ GM 不能裁决 DRAFT）。
    long head0 = world.head();
    ToolResult propose = propose(factory, EFFECT_TOOL, issueMoneyArgs());
    assertThat(propose.success()).as("propose 必须成功：%s", propose.message()).isTrue();
    assertThat(world.head()).as("propose 自己落一条 revision（DRAFT 包）").isEqualTo(head0 + 1L);
    String packetId = JSON.readTree(propose.message()).path("packetId").asText();
    System.out.println("[P2] packetId=" + packetId);
    assertThat(packetStatus(packetId))
        .as("★ propose 的产物是 DRAFT（真实状态机）")
        .isEqualTo(PacketStatus.DRAFT);

    // ② confirm（DM 侧）⇒ PENDING（GM 只裁决 PENDING）。
    long head1 = world.head();
    ToolResult confirm =
        factory.execute(
            dmRegistry,
            ConfirmPacketTool.NAME,
            dm(),
            world.state(),
            mapId,
            Map.of("branch", "main", "expectedRevision", head1));
    assertThat(confirm.success()).as("confirm 必须成功：%s", confirm.message()).isTrue();
    assertThat(world.head()).as("confirm 落一条 revision").isEqualTo(head1 + 1L);
    assertThat(packetStatus(packetId)).as("★ confirm 之后才是 PENDING").isEqualTo(PacketStatus.PENDING);

    // ③ decide APPROVE（GM 侧）。
    long head2 = world.head();
    Map<String, Object> decideArgs = new LinkedHashMap<>();
    decideArgs.put("packetId", packetId);
    decideArgs.put("decision", "APPROVE");
    decideArgs.put("note", "P2 端到端：批准");
    decideArgs.put("branch", "main");
    decideArgs.put("expectedRevision", head2);
    ToolResult decide = gmExec(GmDecidePacketTool.NAME, decideArgs);
    assertThat(decide.success()).as("decide 必须成功：%s", decide.message()).isTrue();
    assertThat(world.head()).as("decide 落一条 revision").isEqualTo(head2 + 1L);
    assertThat(packetStatus(packetId)).as("★ 批准后包是 APPROVED").isEqualTo(PacketStatus.APPROVED);

    // ④ execute（GM 侧）：★ 恰好一条 revision + outcome 回写。
    long head3 = world.head();
    long rows3 = core.revisions(GmActAsWorldFixture.MAIN).size();
    long copperBeforeExec = treasuryCopper();
    ToolResult exec1 = gmExec(GmPacketExecuteTool.NAME, executeArgs(packetId, head3));
    System.out.println(
        "[P2] exec1 code=" + exec1.code() + " body=" + truncate(exec1.message(), 400));
    assertThat(exec1.success()).as("execute 必须成功：%s", exec1.message()).isTrue();
    assertThat(world.head())
        .as("★★ 一次 execute = 恰好一条 revision（head 口径；效果命令 + outcome 回写同批）")
        .isEqualTo(head3 + 1L);
    assertThat(core.revisions(GmActAsWorldFixture.MAIN).size())
        .as("★★ 一次 execute = 恰好一条 revision（revisions 行数口径，与 head 独立）")
        .isEqualTo(rows3 + 1L);

    DecisionPacket packet = packetOf(packetId);
    assertThat(packet).as("包必须还在").isNotNull();
    FormattedCall call = packet.calls().get(0);
    System.out.println("[P2] outcome=" + call.outcomeJson().orElse("(无)"));
    assertThat(call.outcomeJson()).as("★★ outcome 必须回写到那条 call 上（同批，不是第二趟）").isPresent();
    assertThat(call.outcomeJson().orElse(""))
        .as("★ outcome 的内容是这次执行的摘要（committed）")
        .contains("committed");

    // 判据 3：世界真的变了（具体数值）。
    long copperAfterExec = treasuryCopper();
    System.out.println(
        "[P2] treasuryCopper "
            + copperBeforeExec
            + " -> "
            + copperAfterExec
            + " (delta="
            + (copperAfterExec - copperBeforeExec)
            + ")");
    assertThat(copperBeforeExec).as("创世事实：中央国库 copper = %d 毫", 100_000L).isEqualTo(100_000L);
    assertThat(copperAfterExec)
        .as("★★ 世界真的变了：发钞 %d 毫 ⇒ 国库 copper +%d（真落盘）", ISSUE_MILLI, ISSUE_MILLI)
        .isEqualTo(100_000L + ISSUE_MILLI);
    assertThat(copperBefore).as("★ 这条链开始前的国库余额（对照）").isEqualTo(100_000L);

    // ⑤ 重试：非成功 + 零落盘（三个数都不动）。
    long head4 = world.head();
    long rows4 = core.revisions(GmActAsWorldFixture.MAIN).size();
    ToolResult exec2 = gmExec(GmPacketExecuteTool.NAME, executeArgs(packetId, head4));
    System.out.println(
        "[P2] retry code=" + exec2.code() + " body=" + truncate(exec2.message(), 300));
    assertThat(exec2.success()).as("★★ 重试必须非成功（否则就是重复落盘）").isFalse();
    assertThat(exec2.code()).as("★ 重试的具名码").isEqualTo("BAD_REQUEST");
    assertThat(world.head()).as("★★ 重试零落盘（head）").isEqualTo(head4);
    assertThat(core.revisions(GmActAsWorldFixture.MAIN).size())
        .as("★★ 重试零落盘（revisions 行数）")
        .isEqualTo(rows4);
    assertThat(treasuryCopper()).as("★★ 重试零落盘（国库余额一动不动）").isEqualTo(copperAfterExec);
  }

  /**
   * ★★ <b>DM 侧零阻塞的判别对照</b>（设计书 §8.4 B3；批 3b 靠它把"顺手少问了一次"与"这条路上根本没有闸"分开）。
   *
   * <p>同一条链、同一个 DM、同一条类2 工具：
   *
   * <ul>
   *   <li><b>直接调</b> ⇒ {@code APPROVAL_DENIED} 且 <b>asks=1</b>（类2 的阻塞路径真的存在：{@code AutoApproveGate
   *       → ConfirmGate（恒 empty）→ 到人}）；
   *   <li><b>走 propose</b> ⇒ success 且 <b>asks 不增</b>，{@code Shell.pendingApprovals()}
   *       也一个不挂（决策包路径零审批）。
   * </ul>
   *
   * <p>★ 这里用一个<b>记录通道</b>的 {@link ApprovalCoordinator} 装在同一条真链上（{@code ToolCallAuthorizer.of(guard,
   * coordinator)} + 真 {@link DecisionCallerFactory}），"被问了几次人"是<b>数出来的</b>，不是推断的。
   */
  @Test
  void directClassTwoCallBlocksOnApprovalWhileProposeAsksNobody() {
    PendingApprovals pending = new PendingApprovals();
    RecordingChannel channel = new RecordingChannel();
    ApprovalCoordinator coordinator =
        new ApprovalCoordinator(
            List.of(new AutoApproveGate(pending), new ConfirmGate()),
            List.of(channel),
            pending,
            Duration.ofMillis(250),
            null);
    DecisionCallerFactory instrumented =
        DecisionCallerFactory.forDecisionFace(
            ToolCallAuthorizer.of(new ToolExecutionGuard(), coordinator),
            ToolBuckets.namesOf(world.shell().toolsFor(SimosToolSource.Role.DECISION_AGENT)));

    SimulationState state = world.state();
    ToolResult direct =
        instrumented.execute(dmRegistry, CLASS2_TOOL, dm(), state, mapId, budgetPolicyArgs());
    System.out.println(
        "[P2-B3] direct code="
            + direct.code()
            + " success="
            + direct.success()
            + " asks="
            + channel.published);
    assertThat(direct.success())
        .as("★★ 类2 工具直写必须被审批链挡住（无人应答 ⇒ 拒），实得 message=%s", direct.message())
        .isFalse();
    assertThat(direct.code())
        .as("★ 具名码 APPROVAL_DENIED（不是笼统的 TOOL_ERROR）")
        .isEqualTo(ToolCallAuthorizer.APPROVAL_DENIED);
    assertThat(channel.published).as("★★ 直写真的问了人一次（阻塞路径存在）").isEqualTo(1);

    int asksAfterDirect = channel.published;
    ToolResult viaPropose = propose(instrumented, CLASS2_TOOL, budgetPolicyArgs());
    System.out.println(
        "[P2-B3] propose code="
            + viaPropose.code()
            + " success="
            + viaPropose.success()
            + " asks="
            + channel.published);
    assertThat(viaPropose.success()).as("★★ 走 propose 必须成功（零阻塞）：%s", viaPropose.message()).isTrue();
    assertThat(channel.published)
        .as("★★ DM 侧零阻塞：propose 一问都不加（同一条链上的判别对照）")
        .isEqualTo(asksAfterDirect);
    assertThat(world.shell().pendingApprovals().pending())
        .as("★★ 走 propose 之后真壳的待批表必须还是空的")
        .isEmpty();
  }

  // ────────────────────────────── 调用小件 ──────────────────────────────

  private ToolResult propose(
      DecisionCallerFactory caller, String tool, Map<String, Object> innerArgs) {
    Map<String, Object> call = new LinkedHashMap<>();
    call.put("tool", tool);
    call.put("args", innerArgs);
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("intent", "P2 端到端：" + tool);
    args.put("calls", List.of(call));
    return caller.execute(dmRegistry, "simos.ga.packet.propose", dm(), world.state(), mapId, args);
  }

  private DecisionMaker dm() {
    return world.dm(DM);
  }

  private ToolResult gmExec(String tool, Map<String, Object> args) {
    return GovE2eWorld.exec(gmTool(tool), args);
  }

  private AgentTool gmTool(String name) {
    return world.shell().toolsFor(SimosToolSource.Role.GM).stream()
        .filter(tool -> tool.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("GM 面上必须有 " + name));
  }

  private PacketStatus packetStatus(String packetId) {
    DecisionPacket packet = packetOf(packetId);
    assertThat(packet).as("包里读不到 %s", packetId).isNotNull();
    return packet.status();
  }

  private DecisionPacket packetOf(String packetId) {
    GaState ga = ToolSupport.gaState(world.state());
    return ga.decisionPackets().get(DecisionPacketId.parse(packetId));
  }

  /** 中央 GOV 国库的 copper 余额（直接读 actor 切片，不经工具）。 */
  private long treasuryCopper() {
    SimulationState state = world.state();
    var actor = ((ActorSnapshot) state.module("actor").orElseThrow()).data();
    HouseholdAccountKey key =
        new HouseholdAccountKey(HouseholdId.parse(GovernmentHouseholds.of(GOV_CENTRAL).value()));
    var inventory = actor.accounts().get(key);
    assertThat(inventory).as("中央 GOV 的国库账户必须在（SmallWorld 创世事实）").isNotNull();
    return inventory.money().getOrDefault(new CurrencyId("copper"), 0L);
  }

  private Map<String, Object> issueMoneyArgs() {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("govUnitId", GOV_CENTRAL);
    args.put("currency", "copper");
    args.put("amountMilli", ISSUE_MILLI);
    args.put("reason", "P2 端到端：发行一笔铜");
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
    args.put("reason", "P2 零阻塞对照：预算政策");
    return args;
  }

  private static Map<String, Object> executeArgs(String packetId, long expectedRevision) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("packetId", packetId);
    args.put("branch", "main");
    args.put("expectedRevision", expectedRevision);
    return args;
  }

  private static String json(Map<String, Object> value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("JSON 序列化失败", e);
    }
  }

  private static String truncate(String text, int max) {
    if (text == null) {
      return "(null)";
    }
    return text.substring(0, Math.min(max, text.length()));
  }

  /** 记录通道：真的被"问人"时计数 +1（本身不替人答 ⇒ 到点即拒，fail-closed）。 */
  private static final class RecordingChannel implements ApprovalChannel {

    private int published;

    @Override
    public String name() {
      return "test-recording";
    }

    @Override
    public boolean available() {
      return true;
    }

    @Override
    public void publish(ApprovalRequest request) {
      published++;
    }

    @Override
    public Optional<ApprovalDecision> await(String id, Duration wait) {
      try {
        Thread.sleep(Math.min(Math.max(wait.toMillis(), 0L), 200L));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return Optional.empty();
    }
  }
}
