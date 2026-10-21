package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.llm.LlmMessage;
import io.mosire.agentlib.llm.LlmRequest;
import io.mosire.agentlib.llm.LlmResponse;
import io.mosire.agentlib.store.SqliteConversationStore;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.decision.DecisionAgentService;
import io.mosire.simos.app.testing.GmActAsWorldFixture;
import io.mosire.simos.app.tools.SimosToolSource;
import io.mosire.simos.ga.decision.DecisionAgentRunner;
import io.mosire.simos.ga.model.DecisionMaker;
import io.mosire.simos.ga.model.PacketStatus;
import io.mosire.simos.ga.tools.ToolBuckets;
import io.mosire.simos.ga.tools.write.ConfirmPacketTool;
import io.mosire.simos.ga.tools.write.GmActAsTool;
import io.mosire.simos.ga.tools.write.ReopenPacketTool;
import io.mosire.simos.ga.tools.write.RunDecisionMakersTool;
import io.mosire.simos.ga.tools.write.RunDecisionTool;
import io.mosire.simos.ga.tools.write.StartDecisionTool;
import io.mosire.simos.util.state.BranchId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>批 G「GM 代打」{@code simos.gm.actAs} 的功能判据</b>（2026-10-15，测试面）——设计书 {@code
 * docs/superpowers/plans/2026-10-14-gm-dm-interaction.md} §3.7 + §3.7.1 + §4 + §6/§6.1。
 *
 * <p>★★ <b>本类同时补上上一批台账 §6.3 那 8 条「未做」的判据</b>——它们与 G 判据同一个根因（本仓没有夹具能造出可落地的 {@code propose}
 * call）。根因已由 {@link GmActAsWorldFixture} 补掉：那座小世界有编制（{@code gov-central} 带 {@code
 * GovernmentFormation}）、有家户（政府家户 + 官吏户 + 108 个普通户）、起了真 Shell、种了绑 {@code gov-central} 的决策人。
 *
 * <h2>三条落地裁定（§3.7.1，最高权威）在代码里怎么被钉住</h2>
 *
 * <table>
 *   <caption>裁定 ⇒ 判据</caption>
 *   <tr><th>#</th><th>裁定（用户原话）</th><th>判据</th></tr>
 *   <tr><td>1</td><td>「log 肯定是要的，agent 上下文没必要伪造」</td>
 *       <td>{@link #g6_actAsWritesNoConversationMessage()}（会话动态不变）·
 *           {@link #g7_actAsAppearsInGmToolUsageButTheInnerCallDoesNot()}（留痕）·
 *           {@link #g8_theDecisionPacketCarriesNoGmProxyMarker()}（包里无 GM 标记）</td></tr>
 *   <tr><td>2</td><td>「不能」（不能触发跑轮）</td>
 *       <td>{@link #g3_orchestrationToolsAreRejectedByName()}</td></tr>
 *   <tr><td>3</td><td>「GM 想放哪放哪」</td>
 *       <td>{@link #g4_theGmChosenCoordinateIsUsedVerbatim()}</td></tr>
 * </table>
 *
 * <h2>★ 判别力从哪来（不是「调一下看它绿」）</h2>
 *
 * <ul>
 *   <li><b>G2 逐码对拷</b>：四种内层结局（{@code TOOL_NOT_FOUND} / {@code PERMISSION_DENIED} / {@code
 *       RESOURCE_DENIED} / {@code BAD_REQUEST}）各造一次，断言<b>外层码 == 内层码</b> —— 把「折成一句调用失败」的
 *       实现当场打死（四种折法只要折了，四条里至少三条红）；
 *   <li><b>G9 同一工具两个 DM 两种结局</b>：证明用的是<b>那个 DM 的</b>白名单，而不是 GM 的（只有「窄 DM 也成功」才会让这条失效）；
 *   <li><b>G4 历史坐标真的生效</b>：不只是回显对得上 —— 历史坐标下 {@code stateRevision != head} 且代打确实在那个状态上执行， 证明权限组是在
 *       GM 给的那个状态上现算的（「永远用 head」的实现过不了这一条）。
 * </ul>
 *
 * <p>★ <b>不碰生产代码</b>：全部经真装配（真 Shell + 真工具桶 + 真权限链 + 真 store），没有一条断言读测试自己造的第二份真相。
 */
class GmActAsToolTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String MAIN = "main";

  /** 一条在 DM 面上、{@code gate=ALLOW}、写 {@code ga} 决策域的<b>安全</b>内层工具（G7 要证它不留痕）。 */
  private static final String INNER_INTENT = "simos.ga.packet.intent";

  /** GM-only 的读工具（不在决策人桶 ⇒ 代打够不着）。 */
  private static final String GM_ONLY_READ = "simos.gm.inbox";

  /** 一个真能落地的提议工具（{@code ProposalCatalog} 成员；本夹具世界里它的真 preview 会成功）。 */
  private static final String PROPOSABLE = "simos.gov.retireStaff";

  /** {@link #PROPOSABLE} 的目标家户：在 {@code capital-province}（中央直辖）内 ⇒ 该 DM 够得着。 */
  private static final String IN_SCOPE_HOUSEHOLD = "hh-0_0-rural-landed_peasant";

  /** 越界的目标家户：在 {@code small-world}（省辖区）⇒ 中央 DM 够不着（§6-4 负向的靶子）。 */
  private static final String OUT_OF_SCOPE_HOUSEHOLD = "hh-gov-gov-province";

  @TempDir Path tempDir;

  private GmActAsWorldFixture.Fake fake;
  private GmActAsWorldFixture.World world;

  @BeforeEach
  void startWorld() {
    fake = new GmActAsWorldFixture.Fake();
    world = GmActAsWorldFixture.start(tempDir.resolve("world"), fake);
  }

  @AfterEach
  void stopWorld() {
    if (world != null) {
      world.close();
      world = null;
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════════════════
  // G1 —— 真调一次内层工具
  // ══════════════════════════════════════════════════════════════════════════════════════════

  /**
   * ★★ <b>G1：{@code actAs} 让内层工具<b>真的执行</b></b>（设计书 §3.7「执行路径只有一条」）。
   *
   * <p>判据三处：① 外层成功；② <b>用 {@code simos.gm.packet} 读回</b>那个 DM 的包，{@code intent} 逐字等于代打写进去的那句 ——
   * 这是「内层真的落了盘」的<b>外部读回</b>，不是「外层说成功了」；③ 落了一条真 revision（铁律 2：Command → ChangeSet → Revision）。
   *
   * <p>★ 为什么用 {@code intent} 作靶子：它是 {@code gate=ALLOW} 的决策表达，代打它既证明「内层执行了」又不引入审批 ——
   * 本判据要测的是<b>执行</b>，不是审批链。
   */
  @Test
  void g1_theInnerToolReallyExecutesAndTheWorldReadsItBack() {
    assertThat(world.packetOf(GmActAsWorldFixture.DM)).as("前提：这个 DM 还没有包").isNull();
    long before = world.head();

    ToolResult result =
        actAs(
            Map.of(
                "decisionMakerId",
                GmActAsWorldFixture.DM,
                "tool",
                INNER_INTENT,
                "args",
                Map.of("text", "代打落一句意图")));

    assertThat(result.success())
        .as("★★ G1：代打必须成功（实得 %s：%s）", result.code(), result.message())
        .isTrue();
    assertThat(result.code()).as("成功时没有码").isNull();
    assertThat(world.head()).as("★ G1：内层是真的写通道 ⇒ 落一条真 revision").isEqualTo(before + 1L);

    // ★★ 外部读回：经 GM 的只读口读那个 DM 的包（不是读 actAs 的回显）。
    JsonNode packet = gmPacket();
    assertThat(packet.path("proposerId").asText())
        .as("★★ G1：包里的署名就是那个 DM")
        .isEqualTo(GmActAsWorldFixture.DM);
    assertThat(packet.path("intent").asText())
        .as("★★ G1：内层真的执行了 —— 包里 intent 逐字等于代打写进去的那句（外部读回，不是回显）")
        .isEqualTo("代打落一句意图");
    assertThat(packet.path("status").asText()).isEqualTo("DRAFT");
  }

  // ══════════════════════════════════════════════════════════════════════════════════════════
  // G2 —— 拒码原样透出（四种内层结局逐一对拷）
  // ══════════════════════════════════════════════════════════════════════════════════════════

  /**
   * ★★ <b>G2：内层结局的码<b>原样透出</b>，不许折成一句「调用失败」</b>（设计书 §3.7「照实回 {@code PERMISSION_DENIED} / {@code
   * TOOL_NOT_FOUND}，不许为了让 GM 成功而绕过任何判定」）。
   *
   * <p>四种各造一次，逐条断言 <b>外层 {@code ToolResult.code()} == 回显里的 code == 内层真码</b>：
   *
   * <ol>
   *   <li>{@code TOOL_NOT_FOUND}：目标不在<b>决策人桶</b>（GM-only 工具，压根没发给决策人）；
   *   <li>{@code PERMISSION_DENIED}：目标在桶里但不在该 DM 的 <b>{@code allowedTools} 白名单</b>（GM 再收窄）；
   *   <li>{@code RESOURCE_DENIED}：目标在桶里、在白名单里，但该 DM <b>够不着它声明的资源</b>（{@code accessLimit} 把 {@code
   *       ga} 钉成「够不着」⇒ 资源声明前置闸）；
   *   <li>{@code BAD_REQUEST}：参数坏（内层自己的用法校验）。
   * </ol>
   *
   * <p>★★ <b>为什么这四种必须分开</b>：它们落在判定链的四段（查表 / 白名单 / 资源声明前置闸 / 参数）， 折成一句「代打失败」会让 GM 无法分辨「这个名字打错了」与「这个
   * DM 够不着」—— 那正是本工具存在的意义（可见、可诊断）。
   *
   * <p>★ ②③ 靠<b>另外两座世界</b>造：{@code allowedTools} / {@code accessLimit} 是决策人身上的<b>世界事实</b> （随
   * revision 落盘），只能开世界时给 —— 中途改就是绕过判定链造状态。
   */
  @Test
  void g2_theInnerOutcomeCodeIsPropagatedVerbatim() {
    // ① TOOL_NOT_FOUND：GM-only 读工具不在决策人桶。
    ToolResult notFound =
        actAs(Map.of("decisionMakerId", GmActAsWorldFixture.DM, "tool", GM_ONLY_READ));
    assertThat(notFound.success()).isFalse();
    assertThat(notFound.code())
        .as(
            "★★ G2-①：GM-only 工具不在决策人桶 ⇒ 查表阶段 TOOL_NOT_FOUND（实得 %s：%s）",
            notFound.code(), notFound.message())
        .isEqualTo("TOOL_NOT_FOUND");
    assertThat(innerCode(notFound))
        .as("★★ G2-①：外层码与回显里的 code 必须一致（同一个结局，不许两处各说一套）")
        .isEqualTo(notFound.code());
    assertThat(notFound.message())
        .as("★★ G2-①：★ 内层的真拒因必须原样带出来（不是一句「调用失败」）")
        .contains("工具不存在")
        .contains(GM_ONLY_READ);

    // ④ BAD_REQUEST：参数坏（内层自己的用法校验）。
    ToolResult badArgs =
        actAs(
            Map.of(
                "decisionMakerId",
                GmActAsWorldFixture.DM,
                "tool",
                INNER_INTENT,
                "args",
                Map.of("text", "")));
    assertThat(badArgs.success()).isFalse();
    assertThat(badArgs.code())
        .as("★★ G2-④：坏参数 ⇒ 内层的 BAD_REQUEST 原样透出（实得 %s：%s）", badArgs.code(), badArgs.message())
        .isEqualTo("BAD_REQUEST");
    assertThat(innerCode(badArgs)).isEqualTo(badArgs.code());
    assertThat(badArgs.message()).as("★★ G2-④：★ 内层的真拒因（点出哪个参数）必须原样带出来").contains("text");

    // ② PERMISSION_DENIED + ③ RESOURCE_DENIED：换两座世界（决策人的世界事实不同）。
    world.close();
    world = null;

    GmActAsWorldFixture.Fake narrowFake = new GmActAsWorldFixture.Fake();
    try (GmActAsWorldFixture.World narrow =
        GmActAsWorldFixture.start(
            tempDir.resolve("narrow"),
            narrowFake,
            Map.of(GmActAsWorldFixture.DM, GmActAsWorldFixture.GOV_CENTRAL),
            Map.of(GmActAsWorldFixture.DM, Set.of("simos.unit.get")),
            Map.of())) {
      ToolResult denied = viaWorld(narrow, INNER_INTENT, Map.of("text", "x"));
      assertThat(denied.success()).isFalse();
      assertThat(denied.code())
          .as(
              "★★ G2-②：目标在桶里、但被该 DM 的 allowedTools 收窄掉 ⇒ PERMISSION_DENIED（实得 %s：%s）",
              denied.code(), denied.message())
          .isEqualTo("PERMISSION_DENIED");
      assertThat(innerCode(denied)).isEqualTo(denied.code());
      assertThat(denied.message()).as("★★ G2-②：★ 内层的真拒因（点出白名单）必须原样带出来").contains("白名单");
    }

    GmActAsWorldFixture.Fake lockedFake = new GmActAsWorldFixture.Fake();
    try (GmActAsWorldFixture.World locked =
        GmActAsWorldFixture.start(
            tempDir.resolve("locked"),
            lockedFake,
            Map.of(GmActAsWorldFixture.DM, GmActAsWorldFixture.GOV_CENTRAL),
            Map.of(),
            // ★ accessLimit 把 ga 钉成「够不着」（空前缀集 = 显式 none()，不是「不表态」）。
            Map.of(GmActAsWorldFixture.DM, Map.of("ga", Set.of())))) {
      ToolResult denied = viaWorld(locked, INNER_INTENT, Map.of("text", "x"));
      assertThat(denied.success()).isFalse();
      assertThat(denied.code())
          .as(
              "★★ G2-③：该 DM 在 ga 面「够不着」⇒ 资源声明前置闸 RESOURCE_DENIED（实得 %s：%s）",
              denied.code(), denied.message())
          .isEqualTo("RESOURCE_DENIED");
      assertThat(innerCode(denied)).isEqualTo(denied.code());
      assertThat(denied.message()).as("★★ G2-③：★ 内层的真拒因（点出够不着哪个命名空间）必须原样带出来").contains("够不着");
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════════════════
  // G3 —— 编排族必拒（裁定 2）
  // ══════════════════════════════════════════════════════════════════════════════════════════

  /**
   * ★★ <b>G3：三个编排工具（会真跑一轮 LLM）由代打触发 ⇒ <b>具名拒</b> {@code BAD_REQUEST}</b>（§3.7.1 裁定 2，用户原话 「不能」）。
   *
   * <p>★ <b>引用常量、不抄字面量</b>：三个名字取自 {@link RunDecisionMakersTool#NAME} / {@link RunDecisionTool#NAME}
   * / {@link StartDecisionTool#NAME} —— 常量改了本用例跟着改，抄的那份不会（本仓最贵的一类失效）。
   *
   * <p>★ <b>为什么必须是 BAD_REQUEST 而不是「碰巧 TOOL_NOT_FOUND」</b>：它们本来就不在决策人桶里、会被自然拒 ——
   * 但显式拒的文案要能告诉模型「这条路不走」（而不是「这个名字打错了」）。故本条同时断言<b>文案点出编排</b>， 且逐条<b>零 revision</b>（拒 = 世界零变化）、<b>零
   * LLM 调用</b>（裁定 2 的要害）。
   */
  @Test
  void g3_orchestrationToolsAreRejectedByName() {
    long before = world.head();
    for (String orchestration :
        List.of(RunDecisionMakersTool.NAME, RunDecisionTool.NAME, StartDecisionTool.NAME)) {
      ToolResult result =
          actAs(Map.of("decisionMakerId", GmActAsWorldFixture.DM, "tool", orchestration));
      assertThat(result.success())
          .as("★★ G3：代打不许触发跑轮 —— %s 必须被拒（实得 %s）", orchestration, result.message())
          .isFalse();
      assertThat(result.code())
          .as("★★ G3：%s ⇒ 具名拒 BAD_REQUEST（「这条路不走」而不是「名字打错了」）", orchestration)
          .isEqualTo("BAD_REQUEST");
      assertThat(result.message())
          .as("★★ G3：%s 的拒因必须点出「编排族 / 跑轮」，模型据此停止重试", orchestration)
          .contains(orchestration)
          .contains("跑轮");
    }
    assertThat(world.head()).as("★ G3：三条被拒 = 零 revision").isEqualTo(before);
    assertThat(fake.calls()).as("★★ G3：一条 LLM 都没跑（裁定 2 的要害）").isZero();
  }

  // ══════════════════════════════════════════════════════════════════════════════════════════
  // G4 —— 坐标 GM 想放哪放哪（裁定 3）
  // ══════════════════════════════════════════════════════════════════════════════════════════

  /**
   * ★★ <b>G4：GM 给的 {@code branch}/{@code expectedRevision} <b>原样生效</b></b>（§3.7.1 裁定 3，用户原话「GM
   * 想放哪放哪」）。
   *
   * <p>★ <b>本条的判别力在「不只是回显」</b>：两侧都要有 —— 传历史坐标 ⇒ {@code stateRevision == 历史}（且 <b>{@code !=
   * head}</b>）；不传 ⇒ {@code stateRevision == head}。只有两侧都在，才分得开「原样生效」与「永远用 head」。
   */
  @Test
  void g4_theGmChosenCoordinateIsUsedVerbatim() {
    long genesis = 1L;
    assertThat(world.head()).as("夹具前提：head 就是创世（还没人写过）").isEqualTo(genesis);

    // 先落一条真实写入 ⇒ head 前进，历史坐标（创世）与 head 分开。
    assertThat(
            actAs(
                    Map.of(
                        "decisionMakerId",
                        GmActAsWorldFixture.DM,
                        "tool",
                        INNER_INTENT,
                        "args",
                        Map.of("text", "第一条")))
                .success())
        .as("夹具：先落一条")
        .isTrue();
    long headAfter = world.head();
    assertThat(headAfter).isGreaterThan(genesis);

    // ① 历史坐标：在创世上代打。
    ToolResult historical =
        actAsAt(
            Map.of(
                "decisionMakerId",
                GmActAsWorldFixture.DM,
                "tool",
                INNER_INTENT,
                "args",
                Map.of("text", "写在创世上")),
            genesis);
    assertThat(historical.success())
        .as("★★ G4：在历史坐标上代打必须成功（实得 %s：%s）", historical.code(), historical.message())
        .isTrue();
    JsonNode historicalView = parse(historical.message());
    assertThat(historicalView.path("branch").asText())
        .as("★★ G4：回显的 branch 就是 GM 给的那个")
        .isEqualTo(MAIN);
    assertThat(historicalView.path("stateRevision").asLong())
        .as("★★ G4：★ 权限组与执行都在 GM 给的那个 revision 上（head 此刻是 %s）", headAfter)
        .isEqualTo(genesis);
    assertThat(historicalView.path("stateRevision").asLong())
        .as("★★ G4：这一条同时把「永远用 head」的实现打死")
        .isNotEqualTo(headAfter);

    // ② 缺省坐标：不传 ⇒ head（既有惯例）。
    //   ★ 必须先**捕获**当下的 head 再调 —— 这一次调用自己会落一条新 revision，
    //     拿"调用后的 head"去比"调用中读到的 stateRevision"是把两个时刻混起来（本用例第一次写就是这么红的）。
    long headBeforeDefault = world.head();
    ToolResult atHead =
        actAs(
            Map.of(
                "decisionMakerId",
                GmActAsWorldFixture.DM,
                "tool",
                INNER_INTENT,
                "args",
                Map.of("text", "写在 head 上")));
    assertThat(atHead.success()).as("缺省坐标也必须成功").isTrue();
    assertThat(parse(atHead.message()).path("stateRevision").asLong())
        .as("★★ G4：不传坐标 ⇒ 缺省 head=%s（与传历史坐标 %s 形成对照）", headBeforeDefault, genesis)
        .isEqualTo(headBeforeDefault)
        .isNotEqualTo(genesis);
    assertThat(world.head())
        .as("★ G4：这一条真落在 head 的后继上（head %s ⇒ %s）", headBeforeDefault, headBeforeDefault + 1L)
        .isEqualTo(headBeforeDefault + 1L);
  }

  // ══════════════════════════════════════════════════════════════════════════════════════════
  // G5 —— 决策人不存在 ⇒ 具名拒
  // ══════════════════════════════════════════════════════════════════════════════════════════

  /** ★ <b>G5：{@code decisionMakerId} 在该坐标下查无 ⇒ 具名拒 {@code BAD_REQUEST}</b>（设计书 §3.7）。 */
  @Test
  void g5_anUnknownDecisionMakerIsRejectedByName() {
    long before = world.head();
    ToolResult result =
        actAs(
            Map.of(
                "decisionMakerId", "dm-nobody", "tool", INNER_INTENT, "args", Map.of("text", "x")));
    assertThat(result.success()).isFalse();
    assertThat(result.code()).as("★ G5：查无 ⇒ 用法错 BAD_REQUEST").isEqualTo("BAD_REQUEST");
    assertThat(result.message())
        .as("★ G5：拒因必须回显调用者填的那个 id")
        .contains("dm-nobody")
        .contains("决策人不存在");
    assertThat(result.message())
        .as("★ G5：★ 但**不列名册** —— 不许把世界里的其他决策人 id 泄漏给调用者（与 GM 侧其余工具同纪律）")
        .doesNotContain(GmActAsWorldFixture.DM_PROVINCE);
    assertThat(world.head()).as("★ G5：拒 = 零 revision").isEqualTo(before);
  }

  // ══════════════════════════════════════════════════════════════════════════════════════════
  // G6 / G7 / G8 —— 裁定 1「留痕只记日志；agent 上下文没必要伪造」
  // ══════════════════════════════════════════════════════════════════════════════════════════

  /**
   * ★★ <b>G6：{@code actAs} <b>不写任何会话</b></b>（§3.7.1 裁定 1「agent 上下文没必要伪造」的要害）。
   *
   * <p>★★ <b>这是动态判据，不是静态论证</b>：① 先给那个 DM 预置一段会话并读回它（证明「这段会话存在且可读」）； ② 调一次代打；③ <b>再读一次，逐条比对</b> ——
   * 长度与内容都不许变。★ 若实现往 {@code conversations} 追加一条，第 ③ 步当场红；★ 若它跑了 LLM，{@code fake.calls()} 那一行红。
   */
  @Test
  void g6_actAsWritesNoConversationMessage() {
    DecisionMaker dm = world.dm(GmActAsWorldFixture.DM);
    String conversationId = DecisionAgentRunner.conversationIdOf(dm);
    Path conversationsDb =
        world.shell().config().storeDir().resolve(DecisionAgentService.CONVERSATIONS_FILE_NAME);

    // ① 预置一段会话（真 store 的同一张表）：证明「这段会话存在、可读、可数」。
    try (SqliteConversationStore store = SqliteConversationStore.open(conversationsDb)) {
      store.append(conversationId, LlmMessage.user("预置：这段会话必须原样不动"));
    }
    List<String> before = readConversation(conversationsDb, conversationId);
    assertThat(before).as("夹具前提：预置会话真的落盘了（否则下面那条「不变」是空==空）").hasSize(1);

    // ② 代打一次（内层真的执行了）。
    assertThat(
            actAs(
                    Map.of(
                        "decisionMakerId",
                        GmActAsWorldFixture.DM,
                        "tool",
                        INNER_INTENT,
                        "args",
                        Map.of("text", "代打不该动会话")))
                .success())
        .as("夹具：代打必须成功")
        .isTrue();

    // ③ 再读：长度与内容都不许变。
    List<String> after = readConversation(conversationsDb, conversationId);
    assertThat(after).as("★★ G6：代打**不写任何会话**（裁定 1：agent 上下文没必要伪造）—— 条数不变").hasSize(before.size());
    assertThat(after).as("★★ G6：内容逐条不变（不是「条数一样但内容被换了」）").containsExactlyElementsOf(before);
    assertThat(fake.calls()).as("★★ G6：代打不跑 LLM（没有一条会话被伪造，也没有一次模型调用）").isZero();
  }

  /**
   * ★★ <b>G7：留痕 —— {@code GmToolUsage} 里真出现 {@code simos.gm.actAs}；而<b>内层那次不进</b></b>（§3.7.1 裁定 1
   * 「log 肯定是要的」+ §3.7「{@code GmToolUsage} 的口径就是 GM MCP 口用过的工具」）。
   *
   * <p>★ <b>读回走真口</b>：{@code simos.gm.tool-usage}（GM 桶的只读工具）⇒ 读到的是 GM 交互界面看到的同一份快照。
   *
   * <p>★★ <b>两侧都必须断言</b>：只断言「actAs 在表里」过不了「内层也塞进去了」的实现；只断言「内层不在」过不了「压根没留痕」的 实现。两条一起才钉住「代打 =
   * 一条留痕，内层走决策人权限链」。
   */
  @Test
  void g7_actAsAppearsInGmToolUsageButTheInnerCallDoesNot() throws InterruptedException {
    assertThat(
            actAs(
                    Map.of(
                        "decisionMakerId",
                        GmActAsWorldFixture.DM,
                        "tool",
                        INNER_INTENT,
                        "args",
                        Map.of("text", "留痕靶子")))
                .success())
        .as("夹具：代打必须成功")
        .isTrue();

    List<String> tools = awaitToolUsageContaining(GmActAsTool.NAME);
    assertThat(tools)
        .as("★★ G7：GM 口每次工具执行都留痕 ⇒ simos.gm.actAs 必须真出现在 GmToolUsage 里")
        .contains(GmActAsTool.NAME);
    assertThat(tools)
        .as("★★ G7：★ 内层那次**不进** GmToolUsage —— 它走的是决策人桶的权限链（不是 GM 口）；" + "把两个口混成一份读数会让「GM 用过什么」失真")
        .doesNotContain(INNER_INTENT);
  }

  /**
   * ★★ <b>G8：包里没有「GM 代打」标记</b>（§3.7.1 裁定 1：包里 {@code proposerId} 就是那个 DM，<b>不多一个字段</b> ——
   * 用户要的是<b>真实的伪造</b>）。
   *
   * <p>判据三处：① 经 {@code simos.gm.packet} 读回的包里 {@code proposerId} == 那个 DM；② 包的<b>键集合</b>与「DM
   * 自己跑出来的包」逐字同形；③ 包里<b>没有</b>任何名字里带 {@code gm}/{@code actas} 的键。
   *
   * <p>★ ② 是关键：只断言「没有 gm 键」过不了一个<b>改名</b>的标记（比如叫 {@code proxyFor}）。
   */
  @Test
  void g8_theDecisionPacketCarriesNoGmProxyMarker() {
    // ① 代打促成的包。
    assertThat(
            actAs(
                    Map.of(
                        "decisionMakerId",
                        GmActAsWorldFixture.DM,
                        "tool",
                        INNER_INTENT,
                        "args",
                        Map.of("text", "代打写的")))
                .success())
        .as("夹具：代打必须成功")
        .isTrue();
    JsonNode viaActAs = gmPacket();
    assertThat(viaActAs.path("proposerId").asText())
        .as("★★ G8：proposerId 就是那个 DM（身份由 callerFor 按该 DM 建、从 context.identity() 派生）")
        .isEqualTo(GmActAsWorldFixture.DM);

    // 对照：同一个 DM 自己调同一条工具（不经过代打）⇒ 键集合必须逐字相同。
    long before = world.head();
    assertThat(dmCall(INNER_INTENT, Map.of("text", "DM 自己写的")).success())
        .as("对照：DM 自己调 intent 也必须成功")
        .isTrue();
    assertThat(world.head()).isGreaterThan(before);
    JsonNode viaSelf = gmPacket();

    assertThat(fieldNames(viaActAs))
        .as(
            "★★ G8：代打促成的包与「DM 自己跑出来的包」**键集合逐字同形** —— 没有任何多出来的字段"
                + "（只断言「没有 gm 键」过不了一个改名的标记，故此处用两侧对照）")
        .containsExactlyInAnyOrderElementsOf(fieldNames(viaSelf));

    for (String name : fieldNames(viaActAs)) {
      assertThat(name.toLowerCase(Locale.ROOT))
          .as("★★ G8：包里不许出现任何「谁促成的」标记键（实得 %s）", name)
          .doesNotContain("gm")
          .doesNotContain("actas");
    }
  }

  // ══════════════════════════════════════════════════════════════════════════════════════════
  // G9 / G10 —— 「只能干那个 DM 能干的」+ roles()={GM}
  // ══════════════════════════════════════════════════════════════════════════════════════════

  /**
   * ★★ <b>G9：代打用的是<b>决策人桶</b>的注册表与<b>那个 DM 的</b>白名单 —— 不是 GM 的</b>（设计书 §3.7 「用的注册表必须是<b>决策人桶</b>（不是
   * GM 桶），用的白名单必须是 {@code whitelistFor(该 DM)}」）。
   *
   * <p>★★ <b>三个靶子，缺一条都分不开</b>（本用例第一版只做了中间那条，被"注册表是测试喂进去的"这一点架空了 —— 如实记）：
   *
   * <ol>
   *   <li><b>公理：这条工具在 GM 面、<u>不在</u>决策人面</b>（{@code simos.gm.inbox}）—— 先钉住前提， 否则下面两条都是空==空；
   *   <li><b>注册表这一维</b>：把<b>同一个工具实例</b>装进一个"决策人桶 + 这一条 GM-only 工具"的注册表再代打 ⇒ 必须<b>仍然成功</b>。★
   *       这条是<b>真的判别力所在</b>：它证明代打用的是<b>调用方喂进来的那个注册表</b>， 而不是自己偷偷摸到 GM 全局面去查表。★ 谁把 {@code actAs}
   *       改成"绕过注册表、直接按名字找 GM 面"， 这一条就从"成功"变"被拒"（或反过来），当场红；
   *   <li><b>白名单这一维</b>：同一个工具、同一个世界，只把 {@code allowedTools} 收窄 ⇒ 必须 {@code PERMISSION_DENIED} + 零
   *       revision。
   * </ol>
   *
   * <p>★ 为什么用 {@code allowedTools} 而不是 {@code accessLimit} 来收窄：前者是<b>白名单闸</b>（「工具没发给你」），
   * 后者是<b>资源闸</b>（「发给你了但够不着」）—— 两个是判定链上不同的段，G2 已分别钉过；本条把「同一工具、两个 DM、两种结局」 与 G2 的「同一 DM、四种结局」配成一对。
   */
  @Test
  void g9_theDecisionBucketRegistryAndTheDmWhitelistGateTheCallNotTheGmBucket() {
    // 宽侧：本轮的 world（allowedTools 空）。
    assertThat(
            actAs(
                    Map.of(
                        "decisionMakerId",
                        GmActAsWorldFixture.DM,
                        "tool",
                        INNER_INTENT,
                        "args",
                        Map.of("text", "宽 DM 写的")))
                .success())
        .as("★★ G9-宽：allowedTools 空的 DM ⇒ 沿用派生面 ⇒ 成功")
        .isTrue();

    // ① 公理：这条工具在 GM 面、**不在**决策人面（否则下面第 ② 条是空==空）。
    assertThat(world.shell().toolRegistry().find(GM_ONLY_READ))
        .as("★★ G9-①：%s 必须在 GM 面（= 运行时 MCP 口）", GM_ONLY_READ)
        .isPresent();
    assertThat(world.decisionToolNames())
        .as("★★ G9-①：%s **不在**决策人面（roles() 只有 GM ⇒ 决策人的注册表里根本没有它）", GM_ONLY_READ)
        .doesNotContain(GM_ONLY_READ);
    // 先证"用真决策人桶代打它 ⇒ TOOL_NOT_FOUND"（G2-① 的同一机制，此处作对照基线）。
    ToolResult baseline =
        actAs(Map.of("decisionMakerId", GmActAsWorldFixture.DM, "tool", GM_ONLY_READ));
    assertThat(baseline.code())
        .as("★★ G9-①：决策人桶里查无此名 ⇒ 基线是 TOOL_NOT_FOUND")
        .isEqualTo("TOOL_NOT_FOUND");

    // ② ★★ 注册表这一维：把**同一个 GM-only 工具实例**装进"决策人桶 + 它"的注册表再代打 ⇒ 必须成功。
    //    ⇒ 代打用的是调用方给的那个注册表；谁改成"绕过注册表直接查 GM 全局面"，这条立刻红。
    //   ★ 做法：拿**同一个真实例**（真 caller、真 reads、真 mapId），只把 decisionTools 换成加了一条的桶
    //     —— 两个实例只差那一维。★ 不把 `actAs` 自己往白名单里塞：那会同时放宽**外层**，代打还没跑到目标解析
    //     就先被白名单拒（本用例第二版正是这么红的：`PERMISSION_DENIED: 工具 simos.gm.actAs 不在
    //     allowed-tools 白名单内`），测到的就不是"注册表"这一维了。
    AgentTool gmOnly = world.shell().toolRegistry().find(GM_ONLY_READ).orElseThrow();
    ToolRegistry widened = registryFor(world);
    widened.register(gmOnly);
    ToolResult reached = actAsWithWidenedRegistry(widened, GM_ONLY_READ);

    world.close();
    world = null;

    // ③ 白名单这一维：换一座世界，同一个工具、只把 allowedTools 收窄。
    GmActAsWorldFixture.Fake narrowFake = new GmActAsWorldFixture.Fake();
    try (GmActAsWorldFixture.World narrow =
        GmActAsWorldFixture.start(
            tempDir.resolve("narrow-whitelist"),
            narrowFake,
            Map.of(GmActAsWorldFixture.DM, GmActAsWorldFixture.GOV_CENTRAL),
            Map.of(GmActAsWorldFixture.DM, Set.of("simos.unit.get")),
            Map.of())) {
      ToolResult denied = viaWorld(narrow, INNER_INTENT, Map.of("text", "x"));
      assertThat(denied.success())
          .as("★★ G9-③：同一个工具、同一个世界，只把 allowedTools 收窄 ⇒ 必须被拒（实得 %s）", denied.message())
          .isFalse();
      assertThat(denied.code())
          .as("★★ G9-③：拒的机制是**白名单**（那个 DM 的白名单里没有它）⇒ PERMISSION_DENIED")
          .isEqualTo("PERMISSION_DENIED");
      assertThat(narrow.head()).as("★ G9-③：被拒 ⇒ 零 revision（不是「跑了但结果被丢」）").isEqualTo(1L);
    }
  }

  /**
   * 用**给定的注册表**代打一次（其余分量逐字取自真装配）。
   *
   * <p>★ 这是 G9-② 的判别力所在：G9-① 已证明 {@code GM_ONLY_READ} 在 GM 面、不在决策人面； 这里把**同一个工具实例**装进注册表再代打 —— 成功 ⇒
   * 代打用的就是**这个注册表**。
   *
   * <p>★ 为什么在用例里新建一个 {@link GmActAsTool} 实例，而不是改生产代码：{@code decisionTools} 是 <b>构造器注入</b>的世界事实（生产路径
   * = {@code SimosToolSource} 的决策人桶那一份）； 在用例里换一份，等价于"如果装配给错了桶会怎样"—— 那正是本条要钉的那一维。
   */
  private ToolResult actAsWithWidenedRegistry(ToolRegistry registry, String innerTool) {
    GmActAsTool tool =
        new GmActAsTool(
            factoryFor(world),
            registry,
            new io.mosire.simos.app.tools.QueryServiceToolReadPort(
                new io.mosire.simos.app.query.QueryService(
                    world.shell().coreSimos(),
                    new io.mosire.simos.util.resolve.ResolverRegistry(),
                    new io.mosire.simos.util.facet.FacetRegistry())),
            world.mapId());
    return executeInner(tool, innerTool);
  }

  /**
   * 用**真 GM 上下文**直接执行给定的那个 {@code actAs} 实例（外层身份/权限逐字取自 {@code Shell.gmCaller()}）。
   *
   * <p>★ 必须直接调实例，不能走 {@code shell.toolRegistry()}：注册表里那一条是<b>装配时那个实例</b>
   * （决策人桶注入的），拿它去跑就测不到"换一份注册表会怎样"。
   */
  private ToolResult executeInner(GmActAsTool tool, String innerTool) {
    ToolContext gm = Shell.gmCaller();
    Map<String, Object> args = actAsArgs(innerTool, Map.of());
    args.put("branch", MAIN);
    args.put("expectedRevision", world.head());
    return tool.execute(
        new ToolContext(
            gm.caller(), gm.permissions(), gm.config(), args, gm.identity(), gm.resources()));
  }

  /** 一次 {@code actAs} 的载荷（不含坐标；{@code gmExecuteIn} 会补 head）。 */
  private static Map<String, Object> actAsArgs(String innerTool, Map<String, ?> innerArgs) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("decisionMakerId", GmActAsWorldFixture.DM);
    args.put("tool", innerTool);
    args.put("args", innerArgs);
    return args;
  }

  /**
   * ★★ <b>G10：{@code roles()={GM}} ⇒ 代打<b>不进决策人桶</b></b>（设计书 §3.7「它不进 DM 桶」+ §4 I2「闸门在 GM 侧」）。
   *
   * <p>★ 形制照 {@code DecisionPacketToolGateScanTest}：<b>源码扫描</b>（{@code roles()} 的唯一声明点）+
   * <b>装配侧桶对照</b> （派生面里没有它）两侧都钉。★ 只扫源码会漏「声明对了但挂错桶」；只查桶会漏「声明被改成 {GM,DM} 但桶没跟上」 （后者会在 {@code
   * ToolRoleAudit.requireFaces} 装配期抛，但那条不在本用例的可见面里）。
   */
  @Test
  void g10_actAsDeclaresGmOnlyAndIsAbsentFromTheDecisionFace() throws IOException {
    Set<String> decisionFace = world.decisionToolNames();
    assertThat(decisionFace).as("★★ G10：代打**不进决策人桶**（决策人不得替别人代打）").doesNotContain(GmActAsTool.NAME);
    assertThat(world.shell().toolRegistry().find(GmActAsTool.NAME))
        .as("★★ G10：它**在** GM 面（= 运行时 MCP 口）")
        .isPresent();

    // ① 装配侧：决策人白名单（由桶派生）里没有它。
    DecisionCallerFactory factory =
        DecisionCallerFactory.forDecisionFace(
            world.shell().gmToolAuthorizer(),
            ToolBuckets.namesOf(world.shell().toolsFor(SimosToolSource.Role.DECISION_AGENT)));
    assertThat(factory.whitelist())
        .as("★★ G10：决策人白名单（从决策人桶派生）里不得有 %s", GmActAsTool.NAME)
        .doesNotContain(GmActAsTool.NAME);

    // ② 源码侧：roles() 的唯一拼写点是 {GM}（不是 {GM, DM}）。
    String source = Files.readString(actAsSource());
    assertThat(source)
        .as("★★ G10：%s 的源码里 roles() 必须返回 Set.of(ToolRole.GM)", GmActAsTool.NAME)
        .contains("return Set.of(ToolRole.GM);");
    assertThat(source)
        .as("★★ G10：★ 反证 —— 不许出现 {GM, DM} 那种双角色声明")
        .doesNotContain("ToolRole.GM, ToolRole.DM")
        .doesNotContain("ToolRole.DM, ToolRole.GM");
    assertThat(source)
        .as("★ G10：工具名常量必须是本仓唯一拼写点")
        .contains("public static final String NAME = \"" + GmActAsTool.NAME + "\";");
  }

  // ══════════════════════════════════════════════════════════════════════════════════════════
  // 上一批 §6.3 的 8 条（同一根因：夹具造不出可落地的 call —— 已补）
  // ══════════════════════════════════════════════════════════════════════════════════════════

  /**
   * ★★ <b>§6-4 正向 + §6-5：一次 {@code propose{calls:[c1,c2,c3]}} ⇒ 3 条 call、{@code gm.packet} 全可见；
   * {@code revise{callIndex}} 后 args 真变 + <b>preview 重算过</b>；{@code drop{callIndex}} 后 callCount
   * 少一、callIndex <b>重排连续</b></b>（设计书 §6 第 4、5 条）。
   *
   * <p>★ <b>「preview 重算过」怎么判</b>：改 {@code count} 1 ⇒ 2，真 preview 里的 {@code sourcePopulationAfter}
   * （2→1 vs 2→0）与 {@code infoText} 都会跟着变 ⇒ 断言 preview 的<b>具体数值</b>变了，而不是只断言「preview 字段还在」。
   * 只断言「还在」的话，一个把旧 {@code previewJson} 原样抄回去的实现照样绿。
   *
   * <p>★ <b>「callIndex 重排连续」怎么判</b>：先 propose 三条，drop 中间那条（index 1），断言剩下两条的 callIndex 恰好是 {@code
   * [0, 1]}（不是 {@code [0, 2]}）—— 「洞」会让后续按 index 的 revise/drop 全部错位。
   *
   * <p>★ <b>「只落一条 revision」</b>：一次 propose 无论几条 call，都是<b>一批</b>（同 branch + 同 expectedRevision）⇒
   * 恰一条 revision。这条同时把「逐条各写一次」的实现打死。
   */
  @Test
  void c6_4_and_6_5_multiCallProposeThenReviseAndDrop() throws IOException {
    // ① 一次 propose 三条。
    long headBefore = world.head();
    ToolResult proposed =
        proposeCalls(
            Map.of("intent", "三条意图"),
            List.of(
                call(PROPOSABLE, retireArgs(1, IN_SCOPE_HOUSEHOLD)),
                call("simos.gov.recruit", recruitArgs()),
                call("simos.gov.dispatchTeam", dispatchArgs())));
    assertThat(proposed.success())
        .as("★★ §6-4：三条可落地的 call 必须一次全成（实得 %s：%s）", proposed.code(), proposed.message())
        .isTrue();

    JsonNode packet = gmPacket();
    assertThat(packet.path("calls"))
        .as("★★ §6-4：一次 propose{calls:[c1,c2,c3]} ⇒ 包里恰 3 条 call（GM 经 gm.packet 全看得见）")
        .hasSize(3);
    assertThat(toolNames(packet))
        .as("★★ §6-4：三条 call 的工具名逐条对得上（不是三条同名或丢了一条）")
        .containsExactly(PROPOSABLE, "simos.gov.recruit", "simos.gov.dispatchTeam");
    assertThat(callIndexes(packet)).as("初始 callIndex 连续").containsExactly(0, 1, 2);
    assertThat(parse(proposed.message()).path("callCount").asInt())
        .as("回执里的 callCount")
        .isEqualTo(3);
    assertThat(world.head())
        .as("★★ §6-4：★ 三条 call 一次 propose ⇒ **只落一条 revision**（不是逐条各写一次）")
        .isEqualTo(headBefore + 1L);

    JsonNode previewBefore = packet.path("calls").get(0).path("preview");
    long beforeAfter = previewBefore.path("sourcePopulationAfter").asLong();
    String textBefore = previewBefore.path("infoText").asText();

    long headBeforeRevise = world.head();

    // ② revise index 0：count 1 ⇒ 2 ⇒ preview 必须**重算**。
    ToolResult revised =
        dmCall(
            "simos.ga.packet.revise",
            Map.of("callIndex", 0, "tool", PROPOSABLE, "args", retireArgs(2, IN_SCOPE_HOUSEHOLD)));
    assertThat(revised.success())
        .as("★★ §6-5：revise 必须成功（实得 %s：%s）", revised.code(), revised.message())
        .isTrue();

    JsonNode afterRevise = gmPacket();
    JsonNode previewAfter = afterRevise.path("calls").get(0).path("preview");
    assertThat(
            JSON.readTree(afterRevise.path("calls").get(0).path("argsJson").asText())
                .path("count")
                .asLong())
        .as("★★ §6-5：revise 后 args 真的变了（count 1 ⇒ 2）")
        .isEqualTo(2L);
    assertThat(previewAfter.path("sourcePopulationAfter").asLong())
        .as("★★ §6-5：★ preview **重算过** —— 退休 2 人后的岗位户人口必须跟着变（改前 %s）", beforeAfter)
        .isNotEqualTo(beforeAfter);
    assertThat(previewAfter.path("infoText").asText())
        .as("★★ §6-5：preview 的文字摘要也必须重算（只抄回旧 previewJson 的实现过不了这条）")
        .isNotEqualTo(textBefore);
    assertThat(afterRevise.path("calls")).as("★ §6-5：revise 只换那一条，条数不变").hasSize(3);
    assertThat(world.head())
        .as("★ §6-5：revise 是一条真包写 ⇒ 一条 revision")
        .isEqualTo(headBeforeRevise + 1L);

    // ③ drop index 1（中间那条）⇒ callCount 少一 + callIndex **重排连续**。
    long headBeforeDrop = world.head();
    ToolResult dropped = dmCall("simos.ga.packet.drop", Map.of("callIndex", 1));
    assertThat(dropped.success())
        .as("★★ §6-5：drop 必须成功（实得 %s：%s）", dropped.code(), dropped.message())
        .isTrue();

    JsonNode afterDrop = gmPacket();
    assertThat(afterDrop.path("calls")).as("★★ §6-5：callCount 少一（3 ⇒ 2）").hasSize(2);
    assertThat(callIndexes(afterDrop))
        .as("★★ §6-5：★ callIndex **重排连续** —— 剩 [0,1] 而不是 [0,2]（洞会让后续 revise/drop 全部错位）")
        .containsExactly(0, 1);
    assertThat(toolNames(afterDrop))
        .as("★★ §6-5：删掉的是**中间那条**（recruit），两侧的留下")
        .containsExactly(PROPOSABLE, "simos.gov.dispatchTeam");
    assertThat(world.head()).as("★ §6-5：drop 也是一条真包写 ⇒ 一条 revision").isEqualTo(headBeforeDrop + 1L);
  }

  /**
   * ★★ <b>§6-6：先 {@code intent{A}} 再 {@code intent{B}} ⇒ 包里<b>只有 B</b>；GM 读到的 {@code intent == B}
   * 且<b>没有「【最后陈述】」尾巴</b></b>（设计书 §6 第 6 条 + 批 B 的新语义）。
   *
   * <p>★ <b>为什么「没有尾巴」必须单独钉</b>：旧行为是「把整轮最后一段自然语言并进来并加 {@code 【最后陈述】} 前缀」。显式 intent 覆盖后若还追加尾巴，句子会变成
   * {@code B【最后陈述】…} ⇒ 逐字相等这一条会红，而「包含 B」那一条照样绿。故此处用 {@code isEqualTo} 而不是 {@code contains}。
   */
  @Test
  void c6_6_aSecondIntentOverwritesTheFirstWithNoTrailingLastStatement() {
    assertThat(
            actAs(
                    Map.of(
                        "decisionMakerId",
                        GmActAsWorldFixture.DM,
                        "tool",
                        INNER_INTENT,
                        "args",
                        Map.of("text", "意图A")))
                .success())
        .as("夹具：第一条 intent")
        .isTrue();
    assertThat(gmPacket().path("intent").asText()).isEqualTo("意图A");

    assertThat(
            actAs(
                    Map.of(
                        "decisionMakerId",
                        GmActAsWorldFixture.DM,
                        "tool",
                        INNER_INTENT,
                        "args",
                        Map.of("text", "意图B")))
                .success())
        .as("夹具：第二条 intent")
        .isTrue();

    String intent = gmPacket().path("intent").asText();
    assertThat(intent).as("★★ §6-6：第二个 intent **覆盖**第一个（包里只有 B）").isEqualTo("意图B");
    assertThat(intent)
        .as("★★ §6-6：★ 没有「【最后陈述】」尾巴（逐字相等已蕴含，此处再具名一次以免被读成「包含」）")
        .doesNotContain("【最后陈述】")
        .doesNotContain("意图A");
  }

  /**
   * ★★ <b>§6-7：{@code confirm} ⇒ {@code submitted:true} + {@code gm.packets{status:PENDING}} 查得到；再
   * {@code confirm} ⇒ <b>幂等</b></b>（设计书 §6 第 7 条 + §4 I10）。
   *
   * <p>★ <b>「从 gm.packets 查」是本条的另一半</b>：只读 {@code confirm} 的回执等于信它自己报的账；这里走 GM 的<b>看板只读口</b>
   * （{@code simos.gm.packets}）按 {@code status=PENDING} 过滤后必须查到它。
   *
   * <p>★ <b>幂等怎么判</b>：第二次 {@code confirm} 也要成功，且包状态仍是 PENDING、<b>零 revision</b>（幂等不是「再写一遍」）。
   */
  @Test
  void c6_7_confirmMarksPendingIsVisibleToTheGmBoardAndIsIdempotent() throws IOException {
    assertThat(
            actAs(
                    Map.of(
                        "decisionMakerId",
                        GmActAsWorldFixture.DM,
                        "tool",
                        INNER_INTENT,
                        "args",
                        Map.of("text", "要确认的意图")))
                .success())
        .as("夹具：先落一句 intent")
        .isTrue();

    long beforeConfirm = world.head();
    ToolResult confirmed = dmCall("simos.ga.packet.confirm", Map.of());
    assertThat(confirmed.success())
        .as("★★ §6-7：confirm 必须成功（实得 %s：%s）", confirmed.code(), confirmed.message())
        .isTrue();
    assertThat(parse(confirmed.message()).path("submitted").asBoolean())
        .as("★★ §6-7：回执里的 submitted 必须是 true")
        .isTrue();
    assertThat(world.head())
        .as("★ §6-7：confirm 是一条真包写 ⇒ 一条 revision")
        .isEqualTo(beforeConfirm + 1L);

    JsonNode pending = gmPackets("PENDING");
    assertThat(packetIds(pending))
        .as(
            "★★ §6-7：★ 经 GM 看板只读口（gm.packets{status:PENDING}）必须查得到这个包"
                + "（只读 confirm 自己的回执 = 信它自己报的账）")
        .contains(packetId());

    // 再 confirm ⇒ 幂等。
    long beforeSecond = world.head();
    ToolResult again = dmCall("simos.ga.packet.confirm", Map.of());
    assertThat(again.success())
        .as("★★ §6-7：第二次 confirm 必须**成功**（幂等，不是具名拒）—— 实得 %s：%s", again.code(), again.message())
        .isTrue();
    assertThat(gmPacket().path("status").asText())
        .as("★★ §6-7：幂等**不翻状态**（仍是 PENDING）")
        .isEqualTo("PENDING");
    assertThat(world.head()).as("★★ §6-7：★ 幂等 = 零 revision（「再写一遍」不是幂等）").isEqualTo(beforeSecond);
  }

  /**
   * ★★ <b>N6：决策包的身份<b>从 {@code context.identity()} 派生、不采信载荷</b>；包里没有 {@code from} 键</b> （设计书 §6.1
   * N6 + §4 I7）。
   *
   * <p>★★ <b>判据的靶子必须选对（本用例第一次写选错了，如实记）</b>：{@code simulate a forged signature} 有两种形态 —— ① 往**内层工具的
   * args** 里塞一个 {@code from}；② 往 args 里塞一个**别的决策人的 {@code decisionMakerId}**。 ① 测不出东西：args
   * 是**那个工具自己的参数**，里面多一个键是合法的（它就该被原样存下来给 GM 裁决时看）， 把它当"伪造署名"是把 args 误读成了信封。② 才是真靶子：**若身份能从载荷取，DM
   * 就能替别人提交**。 ⇒ 本条钉 ②：包里的 {@code proposerId} 恒 == 那个 DM（不受载荷影响），且包的**顶层视图没有** {@code from}/ {@code
   * proposer} 这类"署名"键（署名只有一个来源：{@code proposerId}）。
   */
  @Test
  void n6_thePacketIdentityComesFromTheContextNotThePayload() throws IOException {
    String other = GmActAsWorldFixture.DM_PROVINCE;
    String forgedFrom = "dm-forged-by-model";
    // ② 往内层工具的 args 里塞"另一个决策人"与一个 from：**载荷**层面的伪造尝试。
    Map<String, Object> forged = new LinkedHashMap<>(retireArgs(1, IN_SCOPE_HOUSEHOLD));
    forged.put("decisionMakerId", other);
    forged.put("from", forgedFrom);
    forged.put("proposerId", other);

    ToolResult proposed =
        proposeCalls(
            Map.of("intent", "N6 靶子"), List.of(Map.of("tool", PROPOSABLE, "args", forged)));
    assertThat(proposed.success())
        .as("夹具：带伪造键的提议必须仍然成功（工具不因为多几个键而炸）—— 实得 %s：%s", proposed.code(), proposed.message())
        .isTrue();

    JsonNode packet = gmPacket();
    assertThat(packet.path("proposerId").asText())
        .as(
            "★★ N6：★ 署名**只能**从调用者身份派生 —— 载荷里塞了 decisionMakerId=%s / proposerId=%s 都不生效",
            other, other)
        .isEqualTo(GmActAsWorldFixture.DM)
        .isNotEqualTo(other)
        .isNotEqualTo(forgedFrom);
    assertThat(fieldNames(packet))
        .as("★★ N6：包的**顶层信封**没有 from / proposer 这类第二个署名来源（署名只有一个：proposerId）")
        .doesNotContain("from")
        .doesNotContain("proposer");
    assertThat(world.dm(other))
        .as("★ N6 夹具自证：%s 真的在决策人表里 —— 伪造目标**存在**、只是不生效" + "（否则上面那条「没换成它」是空==空）", other)
        .isNotNull();
  }

  /**
   * ★★ <b>N4：{@code reopen} 对四种终态<b>逐一</b>具名拒 + 零 revision</b>（设计书 §6.1 N4 + §4 I3）。
   *
   * <p>★★ <b>上一批只做了 APPROVED，并给了「四种终态必然先被 {@code decidedBy} 守卫拦下 ⇒ 逐一测是同一处代码的四次重复」的
   * 理由</b>。本轮把这四种<b>真造出来</b>（用真 GM 裁决工具逐条造），因为「同一处代码」这个论证只有<b>真跑过四种</b>才算证过 —— 而 {@code
   * PARTIALLY_APPROVED} 需要「至少一条真 call」，正是本批夹具补掉的那一块。
   *
   * <p>判据逐条：① 拒；② 码 {@code BAD_REQUEST}；③ 文案点出「已裁决 / 不可回退」；④ 状态一个字节没变；⑤ <b>零 revision</b>。
   */
  @Test
  void n4_reopenIsRejectedForEachOfTheFourTerminalStatusesWithZeroRevision() throws IOException {
    for (PacketStatus terminal :
        List.of(
            PacketStatus.APPROVED,
            PacketStatus.REJECTED,
            PacketStatus.MERGED,
            PacketStatus.PARTIALLY_APPROVED)) {
      // ★ 每个终态一个新世界：终态不可逆（I3），四种没法在同一个世界上叠。
      Path dir = tempDir.resolve("n4-" + terminal.name().toLowerCase(Locale.ROOT));
      GmActAsWorldFixture.Fake each = new GmActAsWorldFixture.Fake();
      try (GmActAsWorldFixture.World one = GmActAsWorldFixture.start(dir, each)) {
        // ① 造一个 DRAFT 包 + **两条**真 call。
        //   ★ 为什么必须两条：`GmDecidePacketTool.expectedStatus` 判 PARTIALLY_APPROVED 的条件是
        //     「callIndexes 非空**且存在未被选中的 call**」（回代码核过）⇒ 只有一条 call 时
        //     选它 = 全选 = APPROVED（本用例第一次写就是这么红的：expected PARTIALLY_APPROVED but was APPROVED）。
        ToolResult proposed =
            viaWorldPropose(
                one,
                Map.of("intent", "N4 靶子 " + terminal),
                List.of(
                    call(PROPOSABLE, retireArgs(1, IN_SCOPE_HOUSEHOLD)),
                    call("simos.gov.recruit", recruitArgs())));
        assertThat(proposed.success())
            .as("[%s] 夹具：propose 必须成功（实得 %s）", terminal, proposed.message())
            .isTrue();
        // ② 推成 PENDING（真 DM 工具）。
        assertThat(viaWorldDm(one, "simos.ga.packet.confirm", Map.of()).success())
            .as("[%s] 夹具：confirm 必须成功", terminal)
            .isTrue();
        // ③ 真 GM 裁决成终态。★ MERGE 需要一条**真** mergedPlan（先 upsert，再 decide(MERGE, planId)）。
        String mergedPlanId = null;
        if (terminal == PacketStatus.MERGED) {
          ToolResult plan =
              viaWorldGm(
                  one,
                  "simos.gm.mergedPlan.upsert",
                  Map.of(
                      "participantIds", List.of(GmActAsWorldFixture.DM),
                      "orderedEffects",
                          List.of(
                              Map.of(
                                  "toolName",
                                  PROPOSABLE,
                                  "argsJson",
                                  JSON.writeValueAsString(retireArgs(1, IN_SCOPE_HOUSEHOLD))))));
          assertThat(plan.success())
              .as("[%s] 夹具：mergedPlan.upsert 必须成功（实得 %s：%s）", terminal, plan.code(), plan.message())
              .isTrue();
          mergedPlanId = parse(plan.message()).path("planId").asText();
          assertThat(mergedPlanId).as("[%s] 夹具：计划 id 非空", terminal).isNotBlank();
        }
        ToolResult decided =
            viaWorldGm(one, "simos.gm.packet.decide", decideIn(one, terminal, mergedPlanId));
        assertThat(decided.success())
            .as("[%s] 夹具：GM 裁决必须成功（实得 %s：%s）", terminal, decided.code(), decided.message())
            .isTrue();
        assertThat(viaWorldPacket(one).path("status").asText())
            .as("[%s] 夹具：包已到终态", terminal)
            .isEqualTo(terminal.name());

        long before = one.head();
        String intentBefore = viaWorldPacket(one).path("intent").asText();

        // ④ DM 试 reopen。
        ToolResult reopened = viaWorldDm(one, ReopenPacketTool.NAME, Map.of());
        assertThat(reopened.success())
            .as("★★ N4[%s]：已裁决的包不可回到 DRAFT（实得 %s）", terminal, reopened.message())
            .isFalse();
        assertThat(reopened.code())
            .as("★★ N4[%s]：码是 BAD_REQUEST（改行为：已裁决不能再改）", terminal)
            .isEqualTo("BAD_REQUEST");
        assertThat(reopened.message())
            .as("★★ N4[%s]：拒因必须说明「已裁决 / 不能再改」（只说失败会让模型死循环重试）", terminal)
            .contains("裁决")
            // ★★ 判别力指纹（2026-10-15 加严）：报文必须来自 `PacketDraftSupport.rejectIfDecided` 那**唯一一处**
            //   判据，而不是 `ReopenPacketTool` 自己那条"只有 PENDING 可撤回"的状态检查
            //   （★ 实测：把 rejectIfDecided 摘掉之后，那条检查会接住同一个调用、报文是
            //   「只有已确认（PENDING）的决策包可撤回，当前状态: APPROVED」——**不含**"不能再改" ⇒ 这里当场红）。
            .contains("不能再改");

        JsonNode after = viaWorldPacket(one);
        assertThat(after.path("status").asText())
            .as("★★ N4[%s]：被拒的 reopen 不得把包翻回 DRAFT", terminal)
            .isEqualTo(terminal.name());
        assertThat(after.path("intent").asText())
            .as("★★ N4[%s]：包内容一个字节都没变", terminal)
            .isEqualTo(intentBefore);
        assertThat(one.head())
            .as("★★ N4[%s]：★ 零 revision（「被拒」必须真的是零副作用）", terminal)
            .isEqualTo(before);
      }
    }
  }

  /**
   * ★★ <b>§6-4 的负向：一次 {@code propose{calls:[c1,c2,c3]}} 里<b>任一条越界</b> ⇒ 具名拒 + <b>包不变 + 零
   * revision</b> （「全成或全不成」）</b>（设计书 §6 第 4 条 + §4 I4「不丢决策」的对偶）。
   *
   * <p>★ <b>越界的那一条怎么造</b>：{@code gov.retireStaff} 的目标家户指向 {@code hh-gov-gov-province} —— 它在 {@code
   * small-world}（省）辖区，而 {@code dm-central} 的直辖只有 {@code capital-province} ⇒ 真 preview 成功、<b>目标
   * scope 校验</b>失败（{@code FORBIDDEN}）。★ 这正是「越界」的<b>真实</b>形态（不是塞一个坏 id：那是 BAD_REQUEST，落在另一段判定上）。
   *
   * <p>★ <b>「包不变」必须逐字段比</b>：先造一个已有一句 intent 的包（无 call），再提三条（前两条合法、第三条越界）⇒ 断言 intent 一字未动、{@code
   * calls} 仍为空（前两条<b>没有</b>被落下一半）、revision 不变。
   */
  @Test
  void c6_4_anyOutOfScopeCallRejectsTheWholeBatchAndLeavesThePacketUnchanged() {
    // 先造一个「已有的包」（一句 intent，无 call）—— 用来证「失败的那次没往里落半截」。
    assertThat(
            actAs(
                    Map.of(
                        "decisionMakerId",
                        GmActAsWorldFixture.DM,
                        "tool",
                        INNER_INTENT,
                        "args",
                        Map.of("text", "负向用例之前就有的意图")))
                .success())
        .as("夹具：先有一句 intent")
        .isTrue();
    JsonNode before = gmPacket();
    assertThat(before.path("calls")).as("夹具前提：这个包此刻没有 call").isEmpty();
    long headBefore = world.head();

    // 三条里**第三条越界**（目标家户在另一个 GOV 的辖区）。
    ToolResult rejected =
        proposeCalls(
            Map.of("intent", "不该落地"),
            List.of(
                call(PROPOSABLE, retireArgs(1, IN_SCOPE_HOUSEHOLD)),
                call("simos.gov.recruit", recruitArgs()),
                call(PROPOSABLE, retireArgs(1, OUT_OF_SCOPE_HOUSEHOLD))));

    assertThat(rejected.success())
        .as("★★ §6-4 负向：任一条越界 ⇒ 整次具名拒（实得 code=%s msg=%s）", rejected.code(), rejected.message())
        .isFalse();
    assertThat(rejected.code())
        .as("★★ §6-4 负向：越界的机制是**目标 scope**（不是参数错）⇒ FORBIDDEN")
        .isEqualTo("FORBIDDEN");
    assertThat(rejected.message())
        .as(
            "★★ §6-4 负向：拒因必须点出**越界的目标资源**（模型据此知道改哪一条）——"
                + "真原文是 `目标超出可见范围: unit:gov-province`（该家户挂在省 GOV 上 ⇒ 先撞到那条 unit 目标）")
        .contains("超出可见范围")
        .contains("gov-province");

    JsonNode after = gmPacket();
    assertThat(after.path("calls")).as("★★ §6-4 负向：★ **全成或全不成** —— 前两条合法 call 一条都不许落进包里").isEmpty();
    assertThat(after.path("intent").asText())
        .as("★★ §6-4 负向：包的内容一个字节都没变")
        .isEqualTo(before.path("intent").asText());
    assertThat(world.head()).as("★★ §6-4 负向：★ 零 revision（不是「落了再回滚」）").isEqualTo(headBefore);
  }

  // ══════════════════════════════════════════════════════════════════════════════════════════
  // ★★ 2026-10-15 批 J（I13）：PENDING 不是修改锁 —— 「提交多少轮都没问题」的四条正面判据
  //
  //   为什么落在**本类**：本类有 `GmActAsWorldFixture`（能造**可落地**的 call）与**直接调工具**的口
  //   （`dmCall`，不经 runner）—— 后者是必需的：`RunDecisionEndToEndTest` 那条路上回合末的
  //   `DecisionTurnFinalizer.auto-no-submit` 兜底会把 DRAFT 再结算成 PENDING ⇒ "以 DRAFT 写回"在那里**看不见**。
  //   本类的判据全部是"落盘后立刻读"，没有中间层。
  // ══════════════════════════════════════════════════════════════════════════════════════════

  /**
   * ★★ <b>不变量 I13-a：PENDING 上四条修改口（{@code intent} / {@code revise} / {@code drop} / {@code
   * propose}）<b>全部成功</b>，且包<b>以 DRAFT 写回</b>、<b>恰好一次</b> revision、视图带 {@code autoWithdrawn}</b>。
   *
   * <p>★★ <b>这条直接打死三种坏实现</b>：
   *
   * <ol>
   *   <li><b>旧口径回潮</b>（PENDING ⇒ 具名拒）：{@code success()} 那条当场红；
   *   <li>★★ <b>"改成功但原样带 PENDING 回去"</b>（批 I 台账 §1.3 记的<b>真实</b>旧形态：{@code existing.withCalls(…)}
   *       不带 {@code withStatus(DRAFT)}，而视图却谎报 DRAFT ⇒ 状态与视图不一致）：{@code status == DRAFT} 那条当场红；
   *   <li><b>"先 reopen 再改"（两次 revision）</b>或<b>"没真写"（零 revision）</b>：{@code delta == 1} 那条当场红。
   * </ol>
   *
   * <p>★ <b>为什么四条口各起一座新世界</b>：每条口都要"包此刻是 PENDING"这个前提，而改完它就不是了 ⇒
   * 同一条口跑两次会串味（第二次的前提不成立）。四座世界各占一个子目录，互不影响。
   */
  @Test
  void i13_a_allFourMutatingToolsSucceedOnAPendingPacketAndWriteItBackAsDraft() {
    Map<String, java.util.function.BiFunction<GmActAsWorldFixture.World, String, ToolResult>>
        mutators = new LinkedHashMap<>();
    mutators.put(
        INNER_INTENT, (w, ignored) -> viaWorldDm(w, INNER_INTENT, Map.of("text", "改后的意图")));
    mutators.put(
        "simos.ga.packet.revise",
        (w, ignored) ->
            viaWorldDm(
                w,
                "simos.ga.packet.revise",
                Map.of(
                    "callIndex",
                    0,
                    "args",
                    retireArgs(1, "hh-0_0-rural-landless_laborer"),
                    "intent",
                    "改后的意图")));
    mutators.put(
        "simos.ga.packet.drop",
        (w, ignored) -> viaWorldDm(w, "simos.ga.packet.drop", Map.of("callIndex", 0)));
    mutators.put(
        "simos.ga.packet.propose",
        (w, ignored) ->
            viaWorldPropose(
                w, Map.of("intent", "再提一条"), List.of(call("simos.gov.recruit", recruitArgs()))));

    // ★★ 四条口一次报全（与 I14 同一条理由：逐条当场断言会在**第 1 条**就中断整个方法 ⇒
    //   变异自证时看不见另外三条口也判了）。
    List<String> faults = new ArrayList<>();
    for (Map.Entry<
            String, java.util.function.BiFunction<GmActAsWorldFixture.World, String, ToolResult>>
        entry : mutators.entrySet()) {
      String tool = entry.getKey();
      Path dir = tempDir.resolve("i13a-" + tool.replace('.', '-'));
      GmActAsWorldFixture.Fake each = new GmActAsWorldFixture.Fake();
      try (GmActAsWorldFixture.World one = GmActAsWorldFixture.start(dir, each)) {
        // 前提：一条真 call 的 PENDING 包。
        String before = pendingPacketWithOneCall(one);

        long headBefore = one.head();
        ToolResult mutated = entry.getValue().apply(one, before);
        long headAfter = one.head();
        String statusAfter = viaWorldPacket(one).path("status").asText();
        long delta = headAfter - headBefore;
        String message = mutated.message() == null ? "" : mutated.message();
        List<String> faultsHere = new ArrayList<>();
        if (!mutated.success()) {
          faultsHere.add("被拒了（code=" + mutated.code() + " msg=" + message + "）");
        }
        if (!message.contains("\"autoWithdrawn\":true")) {
          faultsHere.add("视图没有 autoWithdrawn:true");
        }
        if (!message.contains(ConfirmPacketTool.NAME)) {
          faultsHere.add("note 没点出下一步是 " + ConfirmPacketTool.NAME);
        }
        if (!"DRAFT".equals(statusAfter)) {
          faultsHere.add("写回的状态是 " + statusAfter + "（应为 DRAFT）");
        }
        if (delta != 1L) {
          faultsHere.add("revision delta=" + delta + "（应为恰好 1）");
        }
        if (!faultsHere.isEmpty()) {
          faults.add("[" + tool + "] " + faultsHere);
        }
      }
    }

    assertThat(faults)
        .as(
            "★★★ I13-a：PENDING 上 **四条修改口**（intent / revise / drop / propose）条条必须成功、"
                + "以 DRAFT 写回、恰好一次 revision、视图带 autoWithdrawn（此处一次报全四条）")
        .isEmpty();
  }

  /**
   * ★★ <b>不变量 I13-b：{@code confirm} 连调两次 —— 第一次 DRAFT→PENDING（写一条 revision）， <b>第二次幂等成功且零
   * revision</b></b>（＝"又提交一次，以这次为准"）。
   *
   * <p>★ 判别力：只断言"两次都成功、状态都是 PENDING"过不了<b>每次重新 upsert</b> 的实现 ⇒ 第二条 revision 断言才是本用例的核心（与 {@code
   * reopenOnAnAlreadyDraftPacketIsIdempotentAndWritesNoRevision} 同一形制）。
   */
  @Test
  void i13_b_confirmIsIdempotentOnAnAlreadyPendingPacketWithZeroRevision() {
    assertThat(viaWorldDm(world, INNER_INTENT, Map.of("text", "I13-b 靶子")).success())
        .as("夹具：先有一条 DRAFT 包")
        .isTrue();
    assertThat(viaWorldPacket(world).path("status").asText()).as("夹具前提：DRAFT").isEqualTo("DRAFT");

    long headBeforeFirst = world.head();
    ToolResult first = viaWorldDm(world, ConfirmPacketTool.NAME, Map.of());
    long headAfterFirst = world.head();
    assertThat(first.success()).as("★ I13-b：第一次 confirm 成功（实得 %s）", first.message()).isTrue();
    assertThat(parse(first.message()).path("submitted").asBoolean())
        .as("★ I13-b：第一次是**真提交**（submitted:true）")
        .isTrue();
    assertThat(viaWorldPacket(world).path("status").asText())
        .as("★ I13-b：DRAFT → PENDING")
        .isEqualTo("PENDING");
    assertThat(headAfterFirst - headBeforeFirst)
        .as("★ I13-b：第一次**写一条 revision**（提交是世界事实，铁律 2）")
        .isEqualTo(1L);

    long headBeforeSecond = world.head();
    ToolResult second = viaWorldDm(world, ConfirmPacketTool.NAME, Map.of());
    assertThat(second.success())
        .as("★★ I13-b：已 PENDING ⇒ 第二次**幂等成功**（不是拒；实得 %s：%s）", second.code(), second.message())
        .isTrue();
    assertThat(viaWorldPacket(world).path("status").asText())
        .as("★ I13-b：状态仍是 PENDING")
        .isEqualTo("PENDING");
    assertThat(world.head())
        .as("★★★ I13-b：第二次**零 revision**（「每次都重新 upsert」的实现会在这里红）")
        .isEqualTo(headBeforeSecond);
  }

  /**
   * ★★ <b>不变量 I13-c：反复提交 —— 提交 → 改（自动撤回）→ 再提交 → 再改 → 再提交，<b>每一轮都成立</b>， 且 GM
   * 读到的<b>永远是最新一次的内容</b></b>（用户原话「提交多少轮都没问题」+「gm 应当取最后一轮提交的决策」）。
   *
   * <p>★ <b>为什么不能只测一轮</b>：单轮只能证明"第一次之后还改得动"；用户原话的要害是"<b>多少轮都没问题</b>" ——
   * 那是个<b>可反复成立</b>的性质，必须在循环里真跑一遍。★ 本用例连做 <b>3 轮</b>，每轮： {@code intent}（改）⇒ 断言包回 DRAFT ⇒ {@code
   * confirm}（提交）⇒ 断言 PENDING ⇒ **用真 {@code gm.packet} 读回**、逐轮比对 intent 是<b>本轮新写的那句</b>。
   */
  @Test
  void i13_c_repeatedSubmissionRoundsAllSucceedAndGmAlwaysSeesTheLatestContent() {
    for (int round = 1; round <= 3; round++) {
      String text = "第" + round + "次提交的意图";

      ToolResult written = viaWorldDm(world, INNER_INTENT, Map.of("text", text));
      assertThat(written.success())
          .as("★★ I13-c：第 %d 轮的改必须成功（实得 %s）", round, written.message())
          .isTrue();
      assertThat(viaWorldPacket(world).path("status").asText())
          .as("★★ I13-c：第 %d 轮改完 ⇒ 包回 DRAFT（这就是「自动撤回」）", round)
          .isEqualTo("DRAFT");

      ToolResult submitted = viaWorldDm(world, ConfirmPacketTool.NAME, Map.of());
      assertThat(submitted.success())
          .as("★★ I13-c：第 %d 轮的再提交必须成功（实得 %s：%s）", round, submitted.code(), submitted.message())
          .isTrue();
      JsonNode snapshot = viaWorldPacket(world);
      assertThat(snapshot.path("status").asText())
          .as("★★ I13-c：第 %d 轮提交后 ⇒ PENDING", round)
          .isEqualTo("PENDING");
      assertThat(snapshot.path("intent").asText())
          .as("★★★ I13-c：GM 用 gm.packet 读到的**永远是本轮最新那次**的内容（「gm 应当取最后一轮提交的决策」）", round)
          .isEqualTo(text);
    }
    assertThat(viaWorldPacket(world).path("intent").asText())
        .as("★ I13-c：三轮跑完，包里留下的就是第 3 轮那句（不是第 1 轮的，也不是三段拼起来的）")
        .isEqualTo("第3次提交的意图");
  }

  /**
   * ★ <b>夹具助手：把包推到"已 PENDING 且带一条真 call"</b>（I13-a 的四条修改口都要这个前提）。
   *
   * @return 包 id（I13-a 的用例不必用它，但便于失败时定位）
   */
  private static String pendingPacketWithOneCall(GmActAsWorldFixture.World target) {
    ToolResult proposed =
        viaWorldPropose(
            target,
            Map.of("intent", "PENDING 前提"),
            List.of(call(PROPOSABLE, retireArgs(1, IN_SCOPE_HOUSEHOLD))));
    assertThat(proposed.success())
        .as("夹具：propose 必须成功（实得 %s：%s）", proposed.code(), proposed.message())
        .isTrue();
    ToolResult confirmed = viaWorldDm(target, ConfirmPacketTool.NAME, Map.of());
    assertThat(confirmed.success()).as("夹具：confirm 必须成功（实得 %s）", confirmed.message()).isTrue();
    JsonNode packet = viaWorldPacket(target);
    assertThat(packet.path("status").asText()).as("夹具前提：包已 PENDING").isEqualTo("PENDING");
    assertThat(packet.path("calls")).as("夹具前提：包里有一条真 call").hasSize(1);
    return packet.path("packetId").asText();
  }

  /**
   * ★★ <b>不变量 I14（本次语义变更唯一的风险点）：<b>四种终态 × 四条修改口 = 16 条负向</b>，条条具名拒 + 零 revision + 包内容不变</b>。
   *
   * <p>★★ <b>为什么这条是"唯一的风险点"</b>（设计书 §3.9.3 原话）：旧实现挡住"已裁决"靠的是"<b>只有 DRAFT 才放行</b>" ——PENDING 一放开（用户
   * 2026-10-15「提交多少轮都没问题」），那条顺带防线就没了。⇒ "已裁决 ⇒ 拒"必须<b>显式</b>补上， 且四个工具都得有。★ <b>漏掉一个的症状</b>是"DM 能把 GM
   * 已批的包改了"，而 {@code ga.UpsertDecisionPacket} 本身<b>没有状态机</b>（整包覆盖）⇒ <b>没有任何一层会报错</b>。
   *
   * <p>★★ <b>16 条怎么造</b>：外层循环四种终态（每种一座新世界 —— 终态不可逆，I3），内层循环四条修改口。 终态的造法与 {@link
   * #n4_reopenIsRejectedForEachOfTheFourTerminalStatusesWithZeroRevision} 逐字同源 （真 GM 裁决；{@code
   * MERGED} 先 upsert 一条真 mergedPlan；{@code PARTIALLY_APPROVED} 需两条真 call）。
   *
   * <p>★★ <b>逐条判据（每条都带判别力指纹）</b>：
   *
   * <ol>
   *   <li>{@code success() == false} + 码 {@code BAD_REQUEST}；
   *   <li>★★ <b>报文含 {@code decidedBy=} 或 {@code status=}</b> —— 这是 §四要求的"判别力指纹"：
   *       <b>只断言"拒"过不了"因为别的原因被拒"</b>（例如 {@code callIndex} 不存在、参数形状错）；
   *   <li>★★ <b>零 revision</b>（"被拒"必须真的是零副作用，判据在写入之前）；
   *   <li>★★ <b>包逐字段不变</b>：状态仍是那个终态、{@code intent} 一字未动、{@code calls} 条数不变、 {@code decidedBy}
   *       仍非空（不许被洗掉）。
   * </ol>
   */
  @Test
  void i14_allFourMutatingToolsAreRejectedOnEachOfTheFourTerminalStatusesWithZeroRevision()
      throws IOException {
    // ★★ 为什么**收集**而不是"逐条当场断言"（实测教训，如实记）：逐条断言会在**第 1 条**就中断整个方法 ⇒
    //   变异自证时只能看见"1 条红"，看不见"剩下 15 条也判了"。收集起来一次报 ⇒ 16 条**每条都被求值**，
    //   变异日志里能一次看全（这正是"16 条负向"这句承诺的可验证形态）。
    List<String> failures = new ArrayList<>();
    List<String> matchedGuardMessages = new ArrayList<>();
    int attempted = 0;
    for (PacketStatus terminal :
        List.of(
            PacketStatus.APPROVED,
            PacketStatus.REJECTED,
            PacketStatus.MERGED,
            PacketStatus.PARTIALLY_APPROVED)) {
      Path dir = tempDir.resolve("i14-" + terminal.name().toLowerCase(Locale.ROOT));
      GmActAsWorldFixture.Fake each = new GmActAsWorldFixture.Fake();
      try (GmActAsWorldFixture.World one = GmActAsWorldFixture.start(dir, each)) {
        decideToTerminal(one, terminal, each);
        JsonNode before = viaWorldPacket(one);
        // ★ 夹具前提也用收集器：前提不成立时应报"前提"，而不是伪装成"判据通过"。
        if (before.path("decidedBy").asText().isBlank()) {
          failures.add("[" + terminal + "] 夹具前提不成立：decidedBy 是空的（I14 第一条判据的靶子没了）");
        }
        long headBefore = one.head();

        Map<String, ToolResult> attempts = new LinkedHashMap<>();
        attempts.put("intent", viaWorldDm(one, INNER_INTENT, Map.of("text", "越权改写：intent")));
        attempts.put(
            "revise",
            viaWorldDm(
                one,
                "simos.ga.packet.revise",
                Map.of("callIndex", 0, "args", retireArgs(1, IN_SCOPE_HOUSEHOLD))));
        attempts.put("drop", viaWorldDm(one, "simos.ga.packet.drop", Map.of("callIndex", 0)));
        attempts.put(
            "propose",
            viaWorldPropose(
                one,
                Map.of("intent", "越权改写：propose"),
                List.of(call("simos.gov.recruit", recruitArgs()))));

        for (Map.Entry<String, ToolResult> attempt : attempts.entrySet()) {
          String tool = attempt.getKey();
          ToolResult denied = attempt.getValue();
          boolean rejected = !denied.success();
          boolean named = "BAD_REQUEST".equals(denied.code());
          boolean byTheDecidedGuard =
              denied.message() != null
                  && (denied.message().contains("不能再改")
                      || denied.message().contains("decidedBy=")
                      || denied.message().contains("status="));
          if (!(rejected && named && byTheDecidedGuard)) {
            failures.add(
                "["
                    + terminal
                    + " × "
                    + tool
                    + "] success="
                    + denied.success()
                    + " code="
                    + denied.code()
                    + " message="
                    + denied.message());
          }
          matchedGuardMessages.add(denied.message());
          attempted++;
        }

        // ★★ 后置检查**也进收集器**（与上面同一条理由：逐条当场断言会在第 1 个终态就中断整个方法，
        //    那样变异自证只能看见"1 条红"，看不见"剩下三条终态的同族判据也判了"）。
        JsonNode after = viaWorldPacket(one);
        final JsonNode beforeSnapshot = before;
        List<String> postFaults = new ArrayList<>();
        if (!terminal.name().equals(after.path("status").asText())) {
          postFaults.add(
              "status=" + after.path("status").asText() + "（应为 " + terminal.name() + "）");
        }
        if (!beforeSnapshot.path("intent").asText().equals(after.path("intent").asText())) {
          postFaults.add("intent 被改了：" + after.path("intent").asText());
        }
        if (beforeSnapshot.path("calls").size() != after.path("calls").size()) {
          postFaults.add(
              "calls 条数变了："
                  + beforeSnapshot.path("calls").size()
                  + " → "
                  + after.path("calls").size());
        }
        if (!beforeSnapshot.path("decidedBy").asText().equals(after.path("decidedBy").asText())) {
          postFaults.add("decidedBy 被改了：" + after.path("decidedBy").asText());
        }
        if (one.head() != headBefore) {
          postFaults.add("revision 前进了：" + headBefore + " → " + one.head());
        }
        if (!postFaults.isEmpty()) {
          failures.add("[" + terminal + " × 四条口] 被拒之后世界仍变了：" + postFaults);
        }
      }
    }

    assertThat(failures)
        .as(
            "★★★ I14：**四种终态 × 四条修改口 = 16 条**负向，条条必须具名拒（此处一次报全；"
                + "摘掉 rejectIfDecided 这条判据时会 16 条一起出现 —— 见批 J 台账 §4 的变异自证 m1）")
        .isEmpty();
    assertThat(attempted)
        .as("★ 判据自证：16 条**全部真的求值过**（4 终态 × 4 修改口）—— 少求值一条 = 判据面被静默缩小")
        .isEqualTo(16);
    assertThat(matchedGuardMessages)
        .as(
            "★★★ I14：16 条拒因**全部**含唯一判据的文案「不能再改」 ⇒ 它们是同一个判据挡下的，"
                + "而不是四条口各被别的原因（参数错 / callIndex 不存在）拒掉")
        .hasSize(16)
        .allMatch(message -> message != null && message.contains("不能再改"));
  }

  /**
   * ★★ <b>不变量 I14-b：{@code decidedBy} 非空但状态<b>残留 PENDING</b> 的契约故障，也要拒（合取、fail-closed）</b>。
   *
   * <p>★★ <b>为什么这条值得单独钉</b>：它是 {@code confirm} 里<b>判据顺序</b>的理由（批 I 台账 §1.6）—— {@code
   * rejectIfDecided} <b>必须放在</b>"已 PENDING ⇒ 幂等成功"那一支<b>之前</b>；反过来的话， "已裁决但状态残留
   * PENDING"的包会被当成"又提交一次"<b>幂等放过去</b>，而它其实已经被 GM 裁决过了。 同族地，四条修改口也不许只看状态。
   *
   * <p>★★ <b>这条判据怎么落（诚实说明）</b>：本仓<b>没有任何合法路径</b>能造出这种包 —— 要造它必须"直写状态" （把 {@code decidedBy} 填上却把
   * {@code status} 留成 PENDING），而那正是 <b>I12 / 铁律 2</b>（所有修改走 {@code Command → ChangeSet →
   * Revision}）禁止的。★ 故本判据落在<b>源码</b>上：真读 {@code PacketDraftSupport} 的 {@code rejectIfDecided}
   * 实现，钉住它写的是 <b>{@code ||} 合取</b> （{@code decidedBy} 非空 <b>或</b> 终态）而不是"只看状态"。★ 形制照 {@link
   * io.mosire.simos.app.tools.DecisionPacketToolGateScanTest}（§6.1 N10 的源码扫描判据）。
   *
   * <p>★ <b>它的判别力</b>：把判据改成"只看 status"（把 {@code decidedBy.isEmpty() && !terminal} 写成 {@code
   * !terminal}）⇒ 源码里不再有 {@code decidedBy.isEmpty()} 与 {@code ||}/或的合取形态 ⇒ 本用例当场红。
   */
  @Test
  void i14_b_theDecidedGuardIsAConjunctionSoAContractFaultIsAlsoRejected() throws IOException {
    Path source =
        Path.of(
            "..",
            "simos-ga",
            "src",
            "main",
            "java",
            "io",
            "mosire",
            "simos",
            "ga",
            "tools",
            "write",
            "PacketDraftSupport.java");
    assertThat(Files.isRegularFile(source))
        .as("扫描面必须存在（「空 == 空」恒真是本仓踩过的陷阱）：%s", source.toAbsolutePath())
        .isTrue();
    String text = Files.readString(source);
    int start = text.indexOf("static Optional<ToolResult> rejectIfDecided(");
    assertThat(start).as("PacketDraftSupport 里必须有 rejectIfDecided（I14 的唯一实现处）").isGreaterThan(0);
    int end = text.indexOf("static Optional<ToolResult> rejectIfNotCurrentTick(");
    assertThat(end)
        .as("rejectIfDecided 之后必须有 rejectIfNotCurrentTick（用来切出扫描区间）")
        .isGreaterThan(start);
    String body = text.substring(start, end);

    assertThat(body)
        .as(
            "★★★ I14-b：判据必须是**合取** —— 同时读 decidedBy 与 status。"
                + "只读 status 的实现会让「decidedBy 非空但状态残留 PENDING」的契约故障**静默通过**（fail-open）")
        .contains("decidedBy")
        .contains("isDecidedStatus");
    // ★★ 形态无关的等式（**实测校准**）：本实现写的是 De Morgan 形态
    //    「两者都为空 ⇒ 放行（`decidedBy.isEmpty() && !terminal` ⇒ return empty）」，**没有**字面的 `||`。
    //    ★ 第一版我断言了字面 `||` ⇒ 当场红（实测），而那是**我的判据写错了**，不是实现错了：
    //      `if (A.isEmpty() && !B) return 放行;` 与 `if (A.isPresent() || B) 拒;` 是同一条件。
    //    ⇒ 改判"表达式形状"等于把判据钉在实现风格上（第二处真相）。真正要守的是**语义**，故这里断言：
    //      ① 合取里**两个条件都在**（decidedBy 这条**是**放行条件的合取项 —— 这正是"只读 status"会丢掉的那一项）；
    //      ② `&&` ⇒ 必须**同时**成立才放行（任一条不成立 ⇒ 落到下面那条拒）。
    //    ★ 判别力：把判据改成"只看 status"（删掉 `decidedBy.isEmpty() &&` 那半截）⇒ ① ② 两条一起红。
    assertThat(body)
        .as(
            "★★★ I14-b：decidedBy 那一支必须是**放行条件的合取项**（`decidedBy.isEmpty() && …`）—— "
                + "只读 status 的实现会让契约故障 fail-open")
        .contains("decidedBy.isEmpty() &&");
    assertThat(body).as("★★ I14-b：合取（&&）⇒ 两个条件必须同时成立才放行，否则落到具名拒（fail-closed）").contains("&&");
  }

  /**
   * ★ <b>夹具助手：把世界里的包推到某个终态</b>（I14 与 N4 同源）。
   *
   * <p>★ <b>为什么每条都得</b>：终态<b>不可逆</b>（I3）⇒ 四种没法在同一个世界上叠着造。
   */
  private static void decideToTerminal(
      GmActAsWorldFixture.World one, PacketStatus terminal, GmActAsWorldFixture.Fake each)
      throws IOException {
    // ① 两条真 call（PARTIALLY_APPROVED 需要"至少一条未选中的 call"，见 N4 的说明）。
    ToolResult proposed =
        viaWorldPropose(
            one,
            Map.of("intent", "I14 靶子 " + terminal),
            List.of(
                call(PROPOSABLE, retireArgs(1, IN_SCOPE_HOUSEHOLD)),
                call("simos.gov.recruit", recruitArgs())));
    assertThat(proposed.success())
        .as("[%s] 夹具：propose 必须成功（实得 %s）", terminal, proposed.message())
        .isTrue();
    assertThat(viaWorldDm(one, ConfirmPacketTool.NAME, Map.of()).success())
        .as("[%s] 夹具：confirm 必须成功", terminal)
        .isTrue();
    // ② MERGE 需要一条**真** mergedPlan（先 upsert，再 decide(MERGE, planId)）。
    String mergedPlanId = null;
    if (terminal == PacketStatus.MERGED) {
      ToolResult plan =
          viaWorldGm(
              one,
              "simos.gm.mergedPlan.upsert",
              Map.of(
                  "participantIds", List.of(GmActAsWorldFixture.DM),
                  "orderedEffects",
                      List.of(
                          Map.of(
                              "toolName",
                              PROPOSABLE,
                              "argsJson",
                              JSON.writeValueAsString(retireArgs(1, IN_SCOPE_HOUSEHOLD))))));
      assertThat(plan.success())
          .as("[%s] 夹具：mergedPlan.upsert 必须成功（实得 %s）", terminal, plan.message())
          .isTrue();
      mergedPlanId = parse(plan.message()).path("planId").asText();
      assertThat(mergedPlanId).as("[%s] 夹具：计划 id 非空", terminal).isNotBlank();
    }
    ToolResult decided =
        viaWorldGm(one, "simos.gm.packet.decide", decideIn(one, terminal, mergedPlanId));
    assertThat(decided.success())
        .as("[%s] 夹具：GM 裁决必须成功（实得 %s：%s）", terminal, decided.code(), decided.message())
        .isTrue();
    assertThat(viaWorldPacket(one).path("status").asText())
        .as("[%s] 夹具：包已到终态", terminal)
        .isEqualTo(terminal.name());
  }

  // ══════════════════════════════════════════════════════════════════════════════════════════
  // ★★ 2026-10-15 批 J（I15）：只许动当前 tick 的包 —— 历史 tick 只读
  // ══════════════════════════════════════════════════════════════════════════════════════════

  /**
   * ★★ <b>不变量 I15-a：历史 tick 上 {@code revise} / {@code confirm} / {@code reopen} 三个写口 <b>全部具名拒</b> +
   * 零 revision</b>（设计书 §3.9.3 第一条红线）。
   *
   * <p>★ <b>为什么这条是新语义逼出来的</b>：包 id 是 {@code pkt-<决策人>-<tick>}，按 tick 分的容器 ⇒ 若允许在 tick 1 改写 tick 0
   * 的包，DM 就能<b>改写历史</b> —— 那正是用户「做完决策这个 tick 就不能再提交命令了」要挡住的事。
   *
   * <p>★★ <b>三个口的 tick 怎么给（回代码核过，两个形状不一样）</b>：
   *
   * <ul>
   *   <li>{@code confirm} / {@code reopen} 有显式 <b>{@code tick} 参数</b> ⇒ 直接点历史 tick；
   *   <li>★★ {@code revise} <b>没有</b> {@code tick} 参数 ⇒ 它点名历史 tick 靠的是 <b>{@code
   *       expectedRevision=<历史坐标>}</b>：{@code state.meta().timestamp().tick()} 随之是历史 tick。 ⇒ 这正是批 I
   *       台账 §7-D1 记的「{@code expectedRevision} 旁路」（控制方已裁定「不过头」）：本用例把那条旁路 <b>真跑一次</b>钉住。
   * </ul>
   *
   * <p>★ <b>判据四处</b>：① 三个口都拒；② 码 {@code BAD_REQUEST}；③ 报文点出「只许动当前 tick」与它看见的两个 tick
   * （判别力指纹：不是被别的理由拒的）；④ <b>零 revision</b> + 历史包一个字节没变。 ★ 另加一条<b>对照</b>：同一个 DM 在<b>当前</b> tick
   * 上是写得动的 —— 否则「拒」可能来自「包不存在」之类的别的原因。
   */
  @Test
  void i15_a_writeToolsAreRejectedByNameOnAHistoricalTickWithZeroRevision() {
    // ① 在 tick 0 造一个 PENDING 包（历史包）。
    String packetId = pendingPacketWithOneCall(world);
    long tick0 = tick();
    assertThat(tick0).as("夹具前提：world 从 tick 0 起").isZero();
    // ★ 历史坐标**精确**取"推进之前那个 head"（它在 tick 0 上）——不用 `head-1` 之类推出来的数：
    //   推出来的数可能落在别处，那这条判据就测不到它想测的东西了（"空 == 空"的变体）。
    long revisionAtTick0 = world.head();
    JsonNode before = viaWorldPacket(world);

    // ② 推进到 tick 1（真 GM 写工具；推演本身是纪律而非系统刹车，见 GmRoundTool 的说明）。
    ToolResult advanced = advanceTo(tick0 + 1);
    assertThat(advanced.success())
        .as("夹具：时间推进必须成功（实得 %s：%s）", advanced.code(), advanced.message())
        .isTrue();
    assertThat(tick()).as("夹具前提：世界已在 tick 1").isEqualTo(1L);
    long headAtTick1 = world.head();

    // ③ 三个写口各自点名 tick 0（历史）⇒ 全部具名拒。
    Map<String, ToolResult> attempts = new LinkedHashMap<>();
    attempts.put("confirm", viaWorldDm(world, ConfirmPacketTool.NAME, Map.of("tick", tick0)));
    attempts.put("reopen", viaWorldDm(world, ReopenPacketTool.NAME, Map.of("tick", tick0)));
    attempts.put(
        "revise",
        viaWorldDm(
            world,
            "simos.ga.packet.revise",
            Map.of(
                "callIndex",
                0,
                "args",
                retireArgs(1, "hh-0_0-rural-landless_laborer"),
                // ★★ 旁路：没有 tick 参数的口，靠历史坐标把 tick 拉回 0（这正是 §3.9.3 之外必须补的那条）。
                "expectedRevision",
                revisionAtTick0)));

    for (Map.Entry<String, ToolResult> attempt : attempts.entrySet()) {
      ToolResult denied = attempt.getValue();
      assertThat(denied.success())
          .as("★★★ I15-a[%s]：历史 tick 上的写必须被拒（实得 %s）", attempt.getKey(), denied.message())
          .isFalse();
      assertThat(denied.code())
          .as("★★ I15-a[%s]：码是用法错（BAD_REQUEST）", attempt.getKey())
          .isEqualTo("BAD_REQUEST");
      assertThat(denied.message())
          .as(
              "★★★ I15-a[%s] 的**判别力指纹**：报文必须点出「只许动当前 tick」并给出它看见的两个 tick" + "（只断言「被拒」会被别的原因蒙混过去）",
              attempt.getKey())
          .contains("只许动当前 tick")
          .contains("tick=" + tick0)
          .contains("tick=" + (tick0 + 1));
    }

    assertThat(world.head()).as("★★★ I15-a：三个写口全部拒在写入之前 ⇒ **零 revision**").isEqualTo(headAtTick1);
    // ④ 用历史 tick 的坐标真读一次历史包：内容一字未变（"拒"必须真的是零副作用）。
    ToolResult historical =
        viaWorldGm(
            world, "simos.gm.packet", Map.of("proposerId", GmActAsWorldFixture.DM, "tick", tick0));
    assertThat(historical.success()).as("★ I15-a 对照：历史 tick 的包**仍读得到**（下面 I15-b 专测这条）").isTrue();
    JsonNode historicalPacket = parse(readBody(historical.message()));
    assertThat(historicalPacket.path("status").asText()).isEqualTo(before.path("status").asText());
    assertThat(historicalPacket.path("intent").asText()).isEqualTo(before.path("intent").asText());
    assertThat(historicalPacket.path("calls")).hasSameSizeAs(before.path("calls"));

    // ⑤ ★★ 对照（**这条是判据强度的一半**）：同一个 DM 在**当前** tick 上是写得动的 ——
    //    否则上面那三条「拒」可能只是「包不存在」之类的别的原因，而不是 tick 判据。
    ToolResult currentTickWrite = viaWorldDm(world, INNER_INTENT, Map.of("text", "当前 tick 上照样写得动"));
    assertThat(currentTickWrite.success())
        .as(
            "★★ I15-a 对照：当前 tick（tick 1）上写同一个 DM 的包必须成功（实得 %s：%s）",
            currentTickWrite.code(), currentTickWrite.message())
        .isTrue();
    assertThat(world.head()).as("★ 对照这次是真写（+1 revision）").isEqualTo(headAtTick1 + 1);
    assertThat(
            parse(
                    readBody(
                        viaWorldGm(
                                world,
                                "simos.gm.packet",
                                Map.of("proposerId", GmActAsWorldFixture.DM, "tick", tick0))
                            .message()))
                .path("intent")
                .asText())
        .as("★★ I15-a：对照写的是 tick 1 的包 ⇒ tick 0 那个历史包的内容**仍然一字未变**")
        .isEqualTo(before.path("intent").asText());
    assertThat(packetId).as("★ 历史包 id 的 tick 段确实是 0").endsWith("-" + tick0);
  }

  /**
   * ★★ <b>不变量 I15-b：历史 tick 的包<b>只读</b> —— {@code simos.gm.packet{tick:历史}} 与 {@code
   * simos.ga.packet.my{tick:历史}} <b>都仍可读</b></b>（不许把读口一起堵死）。
   *
   * <p>★ <b>为什么这条必须单独有</b>：I15 是「只许<b>动</b>当前 tick」，加守卫时最省事的坏实现是 「把 tick 过滤加到读口上」⇒ 那样 GM 再也回看不到上一
   * tick 的决策，<b>历史与 Info 都断了</b>（铁律 1 的反面）。 ⇒ 本用例是「写口严、读口宽」的<b>对偶判据</b>：两侧都断言，缺一条就分不开「只堵写」与「读写一起堵」。
   */
  @Test
  void i15_b_historicalTickPacketsStayReadableThroughBothReadFaces() {
    String packetId = pendingPacketWithOneCall(world);
    long tick0 = tick();
    String intentAtTick0 = viaWorldPacket(world).path("intent").asText();

    assertThat(advanceTo(tick0 + 1).success()).as("夹具：推进到 tick 1").isTrue();
    assertThat(tick()).isEqualTo(1L);

    // ① GM 面读历史 tick（点名 tick，而不是靠缺省 = 当前 tick）。
    ToolResult gmRead =
        viaWorldGm(
            world, "simos.gm.packet", Map.of("proposerId", GmActAsWorldFixture.DM, "tick", tick0));
    assertThat(gmRead.success())
        .as("★★ I15-b：gm.packet{tick=历史} 必须仍可读（实得 %s：%s）", gmRead.code(), gmRead.message())
        .isTrue();
    JsonNode gmView = parse(readBody(gmRead.message()));
    assertThat(gmView.path("packetId").asText()).as("★ I15-b：读到的是那个历史包").isEqualTo(packetId);
    assertThat(gmView.path("tick").asLong()).as("★ I15-b：视图报的 tick 是历史那个").isEqualTo(tick0);
    assertThat(gmView.path("intent").asText()).as("★ I15-b：内容原样").isEqualTo(intentAtTick0);

    // ② GM 看板也要能点名历史 tick（gm.packets 的 tick 过滤）。
    ToolResult gmList = viaWorldGm(world, "simos.gm.packets", Map.of("tick", tick0));
    assertThat(gmList.success())
        .as("★★ I15-b：gm.packets{tick=历史} 必须仍可读（实得 %s：%s）", gmList.code(), gmList.message())
        .isTrue();
    assertThat(packetIds(parse(readBody(gmList.message()))))
        .as("★ I15-b：看板里仍列着那个历史包")
        .contains(packetId);

    // ③ 决策人自己的读口（packet.my）点名历史 tick。
    ToolResult dmRead = viaWorldDm(world, "simos.ga.packet.my", Map.of("tick", tick0));
    assertThat(dmRead.success())
        .as("★★ I15-b：packet.my{tick=历史} 必须仍可读（实得 %s：%s）", dmRead.code(), dmRead.message())
        .isTrue();
    assertThat(readBody(dmRead.message()))
        .as("★ I15-b：决策人读口读到的就是那个历史包的内容")
        .contains(packetId)
        .contains(intentAtTick0);
  }

  /**
   * ★★ <b>不变量 I15-c：{@code expectedRevision=<历史坐标>} <b>旁路</b> —— 写口也要拒</b> （批 I 台账 §7-D1
   * 的偏差；控制方已裁定「不过头」）。
   *
   * <p>★★ <b>这条在测什么、为什么它<b>不是</b> I13-a 的反面</b>：{@code propose}/{@code revise}/{@code drop}
   * <b>没有</b> {@code tick} 参数 ⇒ 它们认的 tick 来自 {@code state.meta().timestamp().tick()}，而 {@code
   * state} 是 {@code expectedRevision} 指出来的那个坐标。★ 若「当前世界 tick」跟着那个<b>历史</b>坐标走，DM 就能拿一个旧坐标 一路写回历史
   * tick 的包（I15 形同虚设）。⇒ 正确形态是：与<b>该分支 head</b> 的 tick 比。
   *
   * <p>★ <b>本用例逐条</b>：三个口分别给一个<b>历史坐标</b> ⇒ 全部具名拒、零 revision、历史包不变。 ★ 与 {@link
   * #i13_a_allFourMutatingToolsSucceedOnAPendingPacketAndWriteItBackAsDraft} 合起来才完整： 那条证「<b>当前</b>
   * tick 上四条口都动得了」，这条证「<b>历史</b>坐标一步也动不了」。
   */
  @Test
  void i15_c_theExpectedRevisionBypassIsAlsoRejectedOnWriteTools() {
    pendingPacketWithOneCall(world);
    long tick0 = tick();
    JsonNode before = viaWorldPacket(world);
    long historicalRevision = world.head();

    assertThat(advanceTo(tick0 + 1).success()).as("夹具：推进到 tick 1").isTrue();
    long headAtTick1 = world.head();

    // ★ 三个口各给一个**历史坐标**（tick 1 的 head 是 headAtTick1，历史坐标是 historicalRevision）。
    Map<String, ToolResult> attempts = new LinkedHashMap<>();
    attempts.put(
        "propose",
        viaWorldProposeAt(
            world,
            Map.of("intent", "拿旧坐标写历史包", "expectedRevision", historicalRevision),
            List.of(call("simos.gov.recruit", recruitArgs()))));
    attempts.put(
        "revise",
        viaWorldDm(
            world,
            "simos.ga.packet.revise",
            Map.of(
                "callIndex",
                0,
                "args",
                retireArgs(1, "hh-0_0-rural-landless_laborer"),
                "expectedRevision",
                historicalRevision)));
    attempts.put(
        "drop",
        viaWorldDm(
            world,
            "simos.ga.packet.drop",
            Map.of("callIndex", 0, "expectedRevision", historicalRevision)));

    for (Map.Entry<String, ToolResult> attempt : attempts.entrySet()) {
      ToolResult denied = attempt.getValue();
      assertThat(denied.success())
          .as(
              "★★★ I15-c[%s]：`expectedRevision=<历史坐标>` **不是**改写历史的旁路（实得 %s）",
              attempt.getKey(), denied.message())
          .isFalse();
      assertThat(denied.code())
          .as("★★ I15-c[%s]：码是用法错（BAD_REQUEST）", attempt.getKey())
          .isEqualTo("BAD_REQUEST");
      assertThat(denied.message())
          .as(
              "★★★ I15-c[%s] 的判别力指纹：报文必须点出「只许动当前 tick」与两个 tick"
                  + "（★ 若判据是拿 expectedRevision 的 tick 当「当前」，这里根本不会拒）",
              attempt.getKey())
          .contains("只许动当前 tick")
          .contains("tick=" + tick0)
          .contains("tick=" + (tick0 + 1));
    }

    assertThat(world.head()).as("★★★ I15-c：三条全部拒 ⇒ 零 revision").isEqualTo(headAtTick1);
    ToolResult historical =
        viaWorldGm(
            world, "simos.gm.packet", Map.of("proposerId", GmActAsWorldFixture.DM, "tick", tick0));
    JsonNode historicalPacket = parse(readBody(historical.message()));
    assertThat(historicalPacket.path("status").asText()).isEqualTo(before.path("status").asText());
    assertThat(historicalPacket.path("intent").asText()).isEqualTo(before.path("intent").asText());
    assertThat(historicalPacket.path("calls")).hasSameSizeAs(before.path("calls"));
  }

  /**
   * ★ <b>夹具助手：真 GM 口推进时间到 {@code to}</b>（I15 要一个「历史 tick」就得先把世界推过去）。
   *
   * <p>★ 用真 {@code simos.advance}（GM 桶的真写工具），不手改状态 —— 铁律 2：所有修改走 {@code Command → ChangeSet →
   * Revision}。
   */
  private ToolResult advanceTo(long to) {
    long from = tick();
    return viaWorldGm(
        world,
        "simos.advance",
        Map.of("branch", MAIN, "expectedRevision", world.head(), "from", from, "to", to));
  }

  /** 同 {@link #viaWorldPropose}，但额外允许显式给 {@code expectedRevision}（I15-c 的旁路靶子）。 */
  private static ToolResult viaWorldProposeAt(
      GmActAsWorldFixture.World target,
      Map<String, Object> extra,
      List<Map<String, Object>> calls) {
    Map<String, Object> args = new LinkedHashMap<>(extra);
    args.put("calls", calls);
    return viaWorldDm(target, "simos.ga.packet.propose", args);
  }

  /**
   * ★★ <b>§6-3：同一 tick 对同一 DM <b>连跑 4 轮</b>（问、答、再问、再答）⇒ 无 id 冲突 / 无 CONFLICT / 上下文连续</b> （设计书 §6 第
   * 3 条 + §4 I5）。
   *
   * <p>★★ <b>这条上一批列为「未做」，理由是「需 runner + 真 provider 注入 + 两条信件」</b>。本批两半都补齐了：夹具起了真 Shell 并注入假
   * LLM（{@link GmActAsWorldFixture.Fake}），四轮的输入走真 {@code ga.RunDecision}（真装配、真 provider 解析、真权限链）。
   *
   * <p>★★ <b>判据必须落在"内层那次工具调用"上，不能只看回合的 ok</b>（本用例第一次写就栽在这里，如实记）： {@code ga.RunDecision} 的 ok
   * 只说明<b>回合</b>正常收口 —— 内层工具被具名拒时回合照样 ok（模型拿到拒因、接着把话说完）。 ⇒ 本条逐个读回执的 {@code
   * toolCalls[].ok}，并断言<b>每轮都真的提了一条 call</b>。
   *
   * <p>判据五处：
   *
   * <ol>
   *   <li>★★ <b>四轮全都成功</b>：四次 {@code ga.RunDecision} 都 ok，且<b>每一轮</b>的 {@code propose} 内层 {@code
   *       ok=true}（码为空）—— 第四轮的视图里 {@code callCount} 已累积到 4；
   *   <li><b>同 tick</b>：四轮跑完 tick 不变（问答不推进世界）；
   *   <li>★★ <b>无 id 冲突</b>：四轮里的<b>每一次</b>内层写都提到同一个包 id（{@code pkt-<dm>-<tick>} 确定性 id）， 且全程<b>没有一条
   *       CONFLICT</b>（"id 冲突"的可执行形态 = 包 id 变了或码是 CONFLICT）；
   *   <li>★★ <b>上下文连续</b>：第 4 轮的请求里<b>带着第 1 轮那句话与第 1 轮的工具结果</b> —— 逐轮重开会话的实现过不了这条；
   *   <li>★★ <b>四条 call 真累积</b>：四轮各 append 一条 ⇒ 包里 {@code calls} 恰好 4 条、{@code callIndex} {@code
   *       [0,1,2,3]}（★ 这是 §4 I6「包能跨 revision 累积」在回合路径上的形态）。
   * </ol>
   *
   * <p>★★ <b>2026-10-15 升级回强判据（旧口径 → 新口径，如实记）</b>：旧版这条钉的是"<b>无 CONFLICT + 永远是同一个包 id</b>" + "第 2~4
   * 轮是 PENDING 状态边界的具名拒（{@code BAD_REQUEST}）"，并把"四轮各落一条 call"记为 <b>做不到</b>—— 理由是当时第 1 轮收口时 {@code
   * DecisionTurnFinalizer} 把包结算成 PENDING，第 2 轮的 {@code propose} 撞上"已提交 ⇒ 拒"的状态边界。★ 用户
   * 2026-10-15「提交多少轮都没问题」+ 设计书 §3.9.2「PENDING 自动撤回成 DRAFT 再改」之后，那道边界<b>没了</b> ⇒ 后三轮**真的
   * append**，原判据随之升级为"<b>4 条 call、四轮同一包 id、无 CONFLICT、codes 全空</b>"。 ★ 强度是<b>升</b>不是平：旧版一条 {@code
   * hasSize(1)} 只能证明"第 1 轮落了"，新版 {@code containsExactly(0,1,2,3)} + {@code containsOnly("")}
   * 把"后三轮被拒"这个曾经的既成事实也钉死了。
   */
  @Test
  void c6_3_fourRoundsInTheSameTickKeepOneContextAndConflictNothing() {
    long tickBefore = tick();
    assertThat(world.packetOf(GmActAsWorldFixture.DM)).as("夹具前提：还没有包").isNull();

    // 四轮脚本：每轮"一条工具调用 + 一句收尾自然语言"（只调工具不说话会让 runner 报错，那是既有回合语义）。
    // ★ 靶子必须是 **propose** 而不是 intent：本判据要的是"真的提了 call"，
    //   而 intent 只写包级 intent、一个 call 都不落（本用例第一次写就是这么红的）。
    for (int round = 1; round <= 4; round++) {
      // ★ 模型侧的名字是 **propose 的线名**（不是目标工具的线名）—— 本用例第一次写把这两者写反了，
      //   症状是模型直接去调 `simos_gov_retireStaff`（内层工具，决策人面根本没有它）⇒ TOOL_NOT_FOUND。
      fake.enqueue(
          LlmResponse.toolCall(
              "m" + round, wireNameOf("simos.ga.packet.propose"), proposeArgsForRound(round)),
          LlmResponse.text("第" + round + "轮结束语"));
    }

    List<String> packetIdsSeen = new ArrayList<>();
    List<String> codesSeen = new ArrayList<>();
    List<String> summariesSeen = new ArrayList<>();
    for (int round = 1; round <= 4; round++) {
      ToolResult run = runDecision();
      assertThat(run.success())
          .as("★★ §6-3：第 %d 轮的**回合**必须正常收口（实得 %s：%s）", round, run.code(), run.message())
          .isTrue();

      JsonNode trace = parse(run.message());
      JsonNode toolCalls = trace.path("toolCalls");
      assertThat(toolCalls).as("★★ §6-3：第 %d 轮必须真的调了内层工具（不是「模型只说话」）", round).isNotEmpty();
      // ★ 逐条找那条 **propose**（回执里的工具名是**线名** `simos_ga_packet_propose`；回代码核过：runner 用
      //   `LlmToolNames.realNameOf` 把线名映回真名**再调工具**，但回执记的是模型给的那个名字）。
      //   ★ 不能取 `toolCalls[0]`：**ProposalCatalog 的真 preview 也是一次真工具执行**，它同样留痕
      //   （实测 toolCalls[0] = `simos_gov_retireStaff`）⇒ 按位置取会读到内层预览，而不是被考察的那次提议。
      //   ★ 回执里记的是**真名**（线名映射成功时）；只有映射失败（工具不存在）才原样记线名 ——
      //     实测两例：`{"tool":"simos.ga.packet.propose","ok":true}`（成功）与
      //     `{"tool":"simos_gov_retireStaff","code":"TOOL_NOT_FOUND"}`（失败）。
      JsonNode proposeCall = findToolCall(toolCalls, "simos.ga.packet.propose");
      String code = proposeCall.path("code").isNull() ? "" : proposeCall.path("code").asText();
      codesSeen.add(code);
      String summary = proposeCall.path("summary").asText();
      summariesSeen.add(summary);
      // ★★ 2026-10-15 翻向之后：**四轮的 propose 全都成功**（PENDING 不是锁 ⇒ 后面的轮次不再撞状态边界）
      //   ⇒ summary **一律**是视图 JSON。旧版这里有一条 `else` 支专门接"具名拒的非 JSON 正文"，
      //   那条支路在新语义下**走不到** ⇒ 删掉它（留一条永远为假的断言就是判据里的死代码）。
      //   ★ 删掉它反而**更严**：现在每一轮都必须给出可解析的视图（旧版允许第 2~4 轮给一段拒因正文）。
      assertThat(summary)
          .as("★★ §6-3：第 %d 轮的 propose 必须**成功**并回一份视图 JSON（新语义下 PENDING 不再拦；实得 %s）", round, summary)
          .startsWith("{");
      packetIdsSeen.add(parse(summary).path("packetId").asText());

      // ★★ 无 CONFLICT：内层码永远不是 CONFLICT（四轮都成功 ⇒ 四轮的空码）。
      assertThat(code)
          .as("★★ §6-3：第 %d 轮的内层码**不得**是 CONFLICT（「同 tick 连跑撞 id」的可执行判据）—— 实得 %s", round, code)
          .isNotEqualTo("CONFLICT");
    }

    assertThat(codesSeen)
        .as(
            "★★ §6-3：**四轮全部成功**（码全为空串）—— 用户 2026-10-15「提交多少轮都没问题」的可执行形态；"
                + "旧版断言的是 `subList(1,4) 全是 BAD_REQUEST`（PENDING 状态边界），那条口径已被 §3.9 取代")
        .containsOnly("");
    assertThat(summariesSeen)
        .as("★★ §6-3：四轮的 summary 都是视图 JSON（不是拒因正文）")
        .allMatch(each -> each.startsWith("{"));
    assertThat(packetIdsSeen)
        .as("★★ §6-3：四轮提到的**永远是同一个包 id**（pkt-<dm>-<tick> 确定性 ⇒ 无 id 冲突），且四轮各报一次")
        .hasSize(4)
        .containsOnly(packetId())
        .doesNotContain((String) null);

    assertThat(tick()).as("★★ §6-3：四轮在**同一个 tick** 里跑完（问答不推进世界）").isEqualTo(tickBefore);
    assertThat(fake.calls()).as("★★ §6-3：四轮 = 每轮两条 LLM 响应（工具调用 + 收尾），共 8 次").isEqualTo(8);

    // ⑤ ★★ 升级回强判据（2026-10-15）：四轮 propose **各落一条 call 且真累积** ——
    //    旧版只能钉"第 1 轮那条还在"（hasSize(1)），因为当时第 2~4 轮被 PENDING 边界拒掉；
    //    新语义下后三轮**真的 append** 了 ⇒ 包里必须是 4 条、callIndex [0,1,2,3]、且四条都指向同一个可落地工具。
    JsonNode packet = gmPacket();
    assertThat(packet.path("packetId").asText()).isEqualTo(packetId());
    assertThat(packet.path("calls"))
        .as(
            "★★ §6-3/I6：四轮 propose **各落一条 call**（旧版是 hasSize(1)：只有第 1 轮成；"
                + "现在「四轮各 append 一条」才是正确形态）")
        .hasSize(4);
    assertThat(callIndexes(packet)).as("★★ §6-3/I6：callIndex 逐轮累积且连续").containsExactly(0, 1, 2, 3);
    assertThat(toolNames(packet))
        .as("★★ §6-3：四条 call 指向同一个可落地工具（逐轮换的是目标家户，不是工具）")
        .containsOnly(PROPOSABLE);
    // ★ 逐轮的 toHouseholdId 各不相同 ⇒ 四条 call 各自可辨（不是把同一条抄了四遍）。
    //   ★★ 这条自证**只能读包本身**：`fake.requests()` 里记的是**消息**（system/user/assistant 的文本），
    //     assistant 那条工具调用的 **args 不在 `LlmMessage.content` 里**（实测：第 1 轮请求渲染出来
    //     只有 system + user 两段，没有 `poor_peasant`）⇒ 拿请求去证"换了家户"是**假靶子**（会红在夹具自证上）。
    List<String> payloadPerCall = new ArrayList<>();
    for (JsonNode callNode : packet.path("calls")) {
      String argsJson = callNode.path("argsJson").asText();
      assertThat(argsJson).as("每条 call 的 argsJson 必须是非空 JSON 对象").startsWith("{");
      payloadPerCall.add(argsJson);
    }
    assertThat(payloadPerCall)
        .as("★★ §6-3：四条 call 的 argsJson **两两不同**（逐轮换目标家户 ⇒ 四条各自可辨，不是同一条抄了四遍）")
        .doesNotHaveDuplicates();
    for (int round = 1; round <= 4; round++) {
      assertThat(payloadPerCall.get(round - 1))
          .as("★ §6-3：第 %d 条 call 指向本轮那个家户（ROUND_HOUSEHOLDS[%d]）", round, round - 1)
          .contains(ROUND_HOUSEHOLDS[round - 1]);
    }
    // ★★ 回执里的**逐轮**读数：视图的 `callCount` 是**这一轮那批**的条数（每轮 1 条 ⇒ 恒为 1，实测），
    //    真正逐轮前进的是 `callIndex`（0,1,2,3）⇒ 用它证"后三轮确实 append 到了同一个包的不同位置"。
    for (int round = 1; round <= 4; round++) {
      JsonNode view = parse(summariesSeen.get(round - 1));
      assertThat(view.path("callCount").asInt())
          .as("★ §6-3：第 %d 轮的视图报的是**本轮那批**的条数（1 条/轮）", round)
          .isEqualTo(1);
      assertThat(view.path("callIndex").asInt())
          .as("★★ §6-3：第 %d 轮 append 到的是包里的第 %d 个位置（逐轮前进 ⇒ 真的累积，而不是覆盖同一个位置）", round, round - 1)
          .isEqualTo(round - 1);
    }

    // ④ ★★ 上下文连续：第 4 轮的请求里带着第 1 轮那句话与第 1 轮的工具结果。
    List<LlmRequest> requests = fake.requests();
    assertThat(requests).as("四轮的请求都记下来了（每轮 2 次）").hasSize(8);
    // ★ 请求索引：每轮 2 次 ⇒ 第 N 轮 = [2(N-1)] 与 [2(N-1)+1]。
    //   索引 0 是**本轮第一次请求**（那时模型还没说过话）⇒ 它里面不可能有"那句话"；
    //   "上下文接续"必须看**本轮第二次请求或更晚**（那时 transcript 里才有上一轮的内容）。
    String firstRoundSecond = render(requests.get(1));
    String fourthRoundSecond = render(requests.get(7));
    assertThat(firstRoundSecond)
        .as("夹具自证：第 1 轮的**第二次**请求里确实有那句话（否则下面的「带着它」是空==空）")
        .contains("第1轮决策");
    assertThat(fourthRoundSecond)
        .as("★★ §6-3：★ 第 4 轮的上下文里**带着第 1 轮那句话** —— 逐轮重开会话的实现过不了这条")
        .contains("第1轮决策");
    assertThat(fourthRoundSecond)
        .as("★★ §6-3：★ 而且带着第 1 轮的**工具结果原文**（propose 回的 packetId）" + "—— 上下文是接续的，不是每轮从零开始")
        .contains(packetId());
  }

  /**
   * ★★ <b>§4 I6：包能<b>跨 revision 累积</b> —— 同 tick 连提三次，三条 call 都在</b>（设计书 §4 I6 逐字：「后一轮的包写 不因前一轮的包写而
   * CONFLICT」+「补一条"同 tick 连跑三轮、三条 call 都在"的用例」）。
   *
   * <p>★★ <b>这条与 §6-3 是两条判据、必须分开</b>（上一批把它们混在一条"未做"里）：§6-3 判的是<b>回合路径</b>（跑四轮 runner）下的 id
   * 与上下文；本条判的是<b>包写本身</b>的累积性 —— 直接把 {@code propose} 连调三次，<b>且每次都把 {@code expectedRevision}
   * 钉在"上一次写的落点"</b>。
   *
   * <p>★★ <b>为什么必须显式给 {@code expectedRevision}</b>：不给 = 缺省 head ⇒ 每次都用最新 head，"累积"就退化成 "三次都写
   * head"，测不出「后一轮不被前一轮的写顶掉」。★ 这正是 §4 I6 那条不变量的判别力所在：写口若把 {@code expectedRevision}
   * 当成"整包重建的基准"，第二次就会把第一次的 call 丢掉（或报 CONFLICT）。
   */
  @Test
  void i6_packetWritesAccumulateAcrossRevisionsWithinOneTick() {
    String[] households = {
      "hh-0_0-rural-landed_peasant", "hh-0_0-rural-landless_laborer", "hh-0_0-urban-poor_peasant"
    };
    List<Long> revisions = new ArrayList<>();
    for (int round = 1; round <= 3; round++) {
      Map<String, Object> args = new LinkedHashMap<>();
      args.put("intent", "第" + round + "次提议");
      args.put(
          "calls",
          List.of(Map.of("tool", PROPOSABLE, "args", retireArgs(1, households[round - 1]))));
      // ★ 每次都钉在**上一次写的落点**（第 1 次 = 当下 head）。
      long base = world.head();
      args.put("expectedRevision", base);
      revisions.add(base);

      ToolResult proposed = dmCall("simos.ga.packet.propose", args);
      assertThat(proposed.success())
          .as(
              "★★ I6：第 %d 次 propose（expectedRevision=%s）必须成功 —— 不因前一次的写而 CONFLICT" + "（实得 %s：%s）",
              round, base, proposed.code(), proposed.message())
          .isTrue();
      assertThat(proposed.code()).as("★★ I6：第 %d 次的码不得是 CONFLICT", round).isNotEqualTo("CONFLICT");

      JsonNode packet = gmPacket();
      assertThat(packet.path("calls"))
          .as("★★ I6：第 %d 次之后包里应当有 %d 条 call（前几次的**都还在**）", round, round)
          .hasSize(round);
      assertThat(callIndexes(packet))
          .as("★★ I6：callIndex 连续累积到第 %d 条", round)
          .containsExactlyElementsOf(range(round));
    }

    assertThat(revisions)
        .as("★ I6：三次的 base revision 逐次前进（strictly increasing ⇒ 真的是「跨 revision 累积」）")
        .isSorted()
        .doesNotHaveDuplicates();
    assertThat(gmPacket().path("status").asText())
        .as("★ I6：不 confirm ⇒ 包一直是 DRAFT（只有 DRAFT 才允许继续累积）")
        .isEqualTo("DRAFT");
  }

  /**
   * 回执 {@code toolCalls} 里按**线名**找那一条（找不到 ⇒ 当场抛，不静默退化成「没有」）。
   *
   * <p>★ 为什么要按名字找：一次 {@code propose} 会在回执里留下<b>多条</b>记录 —— 目标工具的**真 preview** 同样是一次 真工具执行（实测 {@code
   * toolCalls[0] = simos_gov_retireStaff}）⇒ 按位置取会读到预览，而不是被考察的那次提议。
   *
   * @param realName **真**工具名（回执在映射成功时记真名；只有工具不存在时才原样记模型给的线名）
   */
  private static JsonNode findToolCall(JsonNode toolCalls, String realName) {
    for (JsonNode call : toolCalls) {
      if (realName.equals(call.path("tool").asText())) {
        return call;
      }
    }
    throw new AssertionError("回执里没有 " + realName + " 这一次工具调用： " + toolCalls);
  }

  /** {@code [0, 1, …, n-1]}。 */
  private static List<Integer> range(int n) {
    List<Integer> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      out.add(i);
    }
    return out;
  }

  // ══════════════════════════════════════════════════════════════════════════════════════════
  // 夹具 / 小件
  // ══════════════════════════════════════════════════════════════════════════════════════════

  /** 一条 {@code simos.gov.retireStaff} 的 args（真 preview 会成功的那一份）。 */
  private static Map<String, Object> retireArgs(long count, String toHouseholdId) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("unitId", "gov-central");
    args.put("role", "POST");
    args.put("count", count);
    args.put("reason", "判据靶子");
    args.put("toHouseholdId", toHouseholdId);
    return args;
  }

  /** 一条 {@code simos.gov.recruit} 的 args。 */
  private static Map<String, Object> recruitArgs() {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("unitId", "gov-central");
    args.put("role", "POST");
    args.put("count", 1);
    args.put("reason", "判据靶子");
    return args;
  }

  /** 一条 {@code simos.gov.dispatchTeam} 的 args。 */
  private static Map<String, Object> dispatchArgs() {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("unitId", "gov-central");
    args.put("count", 1);
    args.put("reason", "判据靶子");
    return args;
  }

  private static Map<String, Object> withFrom(Map<String, Object> args, String from) {
    Map<String, Object> copy = new LinkedHashMap<>(args);
    copy.put("from", from);
    return copy;
  }

  private static Map<String, Object> call(String tool, Map<String, Object> args) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("tool", tool);
    row.put("args", args);
    return row;
  }

  // ── 真装配的三个调用口（与生产同源；身份/权限/审批都由 callerFor 现算）──────────────────────

  /** GM 口：真身份 + 真权限集（{@code expectedRevision} 取当下 head）。 */
  private ToolResult actAs(Map<String, ?> args) {
    return actAsAt(args, world.head());
  }

  /** GM 口 + <b>显式</b>坐标（G4 的历史一侧）。 */
  private ToolResult actAsAt(Map<String, ?> args, long revision) {
    Map<String, Object> full = new LinkedHashMap<>(args);
    full.put("branch", MAIN);
    full.put("expectedRevision", revision);
    return gmExecute(GmActAsTool.NAME, full);
  }

  /** 决策人口：真身份 + 真权限链（审批走 GM 自动放行那一侧 —— 不是为了绕过判定，是为了不打真审批口）。 */
  private ToolResult dmCall(String tool, Map<String, Object> args) {
    return viaWorldDm(world, tool, args);
  }

  /** 一次 {@code propose}（若干条 call）。 */
  private ToolResult proposeCalls(Map<String, Object> extra, List<Map<String, Object>> calls) {
    return viaWorldPropose(world, extra, calls);
  }

  private static ToolResult viaWorldDm(
      GmActAsWorldFixture.World target, String tool, Map<String, Object> args) {
    return factoryFor(target)
        .execute(registryFor(target), tool, dmOf(target), target.state(), target.mapId(), args);
  }

  private static ToolResult viaWorldPropose(
      GmActAsWorldFixture.World target,
      Map<String, Object> extra,
      List<Map<String, Object>> calls) {
    Map<String, Object> args = new LinkedHashMap<>(extra);
    args.put("calls", calls);
    return viaWorldDm(target, "simos.ga.packet.propose", args);
  }

  /** GM 口（任意一座世界上用）：真身份 + 真权限集。 */
  private static ToolResult viaWorldGm(
      GmActAsWorldFixture.World target, String tool, Map<String, ?> args) {
    Map<String, Object> full = new LinkedHashMap<>(args);
    full.put("branch", MAIN);
    full.put(
        "expectedRevision",
        target.shell().coreSimos().head(new BranchId(MAIN)).orElseThrow().value());
    return gmExecuteIn(target, tool, full);
  }

  /** 经真 GM 口调一次 {@code actAs}（任意一座世界上用）。 */
  private static ToolResult viaWorld(
      GmActAsWorldFixture.World target, String innerTool, Map<String, ?> innerArgs) {
    return viaWorldGm(
        target,
        GmActAsTool.NAME,
        Map.of(
            "decisionMakerId", GmActAsWorldFixture.DM,
            "tool", innerTool,
            "args", innerArgs));
  }

  private ToolResult gmExecute(String tool, Map<String, Object> args) {
    return gmExecuteIn(world, tool, args);
  }

  private static ToolResult gmExecuteIn(
      GmActAsWorldFixture.World target, String tool, Map<String, Object> args) {
    ToolContext gm = Shell.gmCaller();
    ToolContext call =
        new ToolContext(
            gm.caller(), gm.permissions(), gm.config(), args, gm.identity(), gm.resources());
    return target.shell().gmToolAuthorizer().execute(target.shell().toolRegistry(), tool, call);
  }

  private static DecisionCallerFactory factoryFor(GmActAsWorldFixture.World target) {
    return DecisionCallerFactory.forDecisionFace(
        target.shell().gmToolAuthorizer(),
        ToolBuckets.namesOf(target.shell().toolsFor(SimosToolSource.Role.DECISION_AGENT)));
  }

  private static ToolRegistry registryFor(GmActAsWorldFixture.World target) {
    ToolRegistry registry = new ToolRegistry();
    registry.registerAll(target.shell().toolsFor(SimosToolSource.Role.DECISION_AGENT));
    return registry;
  }

  private static DecisionMaker dmOf(GmActAsWorldFixture.World target) {
    return target.dm(GmActAsWorldFixture.DM);
  }

  /**
   * 一轮 {@code ga.RunDecision}（经真 GM 口 ⇒ 真装配、真 provider 解析、真权限链）。
   *
   * <p>★ 参数形状回代码核过：{@code RunDecisionTool} 收的是 {@code payloadJson}（一条 {@code ga.RunDecision}
   * 命令的载荷），<b>不是</b>顶层 {@code decisionMakerId} —— 后者会被命令期拒成 {@code 字段 decisionMakerId 必须是字符串:
   * {}}（本用例第一次写就是这么红的）。
   */
  private ToolResult runDecision() {
    return viaWorldGm(
        world,
        RunDecisionTool.NAME,
        Map.of("payloadJson", "{\"decisionMakerId\":\"" + GmActAsWorldFixture.DM + "\"}"));
  }

  // ── GM 只读口的解析（gm.packet / gm.packets / gm.tool-usage）────────────────────────────────

  /** 读回 {@link GmActAsWorldFixture#DM} 本 tick 的包（真 GM 只读口）。 */
  private JsonNode gmPacket() {
    ToolResult result =
        viaWorldGm(
            world, "simos.gm.packet", Map.of("proposerId", GmActAsWorldFixture.DM, "tick", tick()));
    assertThat(result.success())
        .as("读回 %s 的包必须成功（实得 %s：%s）", GmActAsWorldFixture.DM, result.code(), result.message())
        .isTrue();
    return parse(readBody(result.message()));
  }

  /** 读回某个世界的包（N4 逐终态用）。 */
  private static JsonNode viaWorldPacket(GmActAsWorldFixture.World target) {
    ToolResult result =
        viaWorldGm(
            target,
            "simos.gm.packet",
            Map.of("proposerId", GmActAsWorldFixture.DM, "tick", tickOf(target)));
    assertThat(result.success()).as("读回包必须成功（实得 %s：%s）", result.code(), result.message()).isTrue();
    return parse(readBody(result.message()));
  }

  /** GM 看板（{@code gm.packets}）按状态过滤。 */
  private JsonNode gmPackets(String status) {
    ToolResult result = viaWorldGm(world, "simos.gm.packets", Map.of("status", status));
    assertThat(result.success())
        .as("读回 gm.packets{status=%s} 必须成功（实得 %s：%s）", status, result.code(), result.message())
        .isTrue();
    return parse(readBody(result.message()));
  }

  /**
   * GM 只读口返回的**正文 JSON**。
   *
   * <p>★ {@code ToolResult.message()} 已经是视图 JSON 的原文（{@code ToolResults.json(view)}）；本方法只做
   * "去掉首尾空白"这一步，<b>不做</b>"解析失败就返回空"的兜底 —— 形状不对要当场红（否则一条改名的视图会让 「查得到」静默变成永远为假）。
   */
  private static String readBody(String message) {
    return message == null ? "" : message.trim();
  }

  /**
   * {@code gm.packets} 视图里逐行的**包 id**。
   *
   * <p>★ 键名是 {@code id}（不是 {@code packetId}）—— 回代码核过 {@code GmPacketsTool} 的 {@code row.put("id",
   * …)}。 写错键名会让这条断言**永远为空集**（"查得到"静默变成永远为假），故此处不设兜底。
   */
  private static List<String> packetIds(JsonNode view) {
    JsonNode rows = view.has("packets") ? view.path("packets") : view;
    List<String> ids = new ArrayList<>();
    for (JsonNode row : rows) {
      ids.add(row.path("id").asText());
    }
    return ids;
  }

  private static List<Integer> callIndexes(JsonNode packet) {
    List<Integer> indexes = new ArrayList<>();
    for (JsonNode call : packet.path("calls")) {
      indexes.add(call.path("callIndex").asInt());
    }
    return indexes;
  }

  private static List<String> toolNames(JsonNode packet) {
    List<String> names = new ArrayList<>();
    for (JsonNode call : packet.path("calls")) {
      names.add(call.path("toolName").asText());
    }
    return names;
  }

  private static List<String> fieldNames(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  /** 代打回显里的 {@code code}（失败时才有；成功时为 {@code ""}）。 */
  private static String innerCode(ToolResult result) {
    return parse(result.message()).path("code").asText();
  }

  private static JsonNode parse(String json) {
    try {
      return JSON.readTree(json);
    } catch (IOException e) {
      throw new AssertionError("不是 JSON: " + json, e);
    }
  }

  /**
   * 真 GM 裁决载荷：按目标终态给出 {@code decision}（值取自 {@link #decisionOf}）。
   *
   * @param mergedPlanId 仅 {@code MERGED} 需要（{@code decision=MERGE} 时必须给，否则具名拒）
   */
  private static Map<String, Object> decideIn(
      GmActAsWorldFixture.World target, PacketStatus terminal, String mergedPlanId) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("packetId", packetIdIn(target));
    args.put("decision", decisionOf(terminal));
    // ★ 两个键各有各的适用面（回代码核过的两条具名拒）：
    //   `MERGE 与 callIndexes 互斥` / `mergedPlanId 只在 decision=MERGE 时有意义`
    //   ⇒ PARTIALLY_APPROVED 只给 callIndexes（选第一条、留第二条未选中），MERGE 只给 mergedPlanId。
    if (terminal == PacketStatus.PARTIALLY_APPROVED) {
      args.put("callIndexes", List.of(0));
    }
    if (mergedPlanId != null) {
      args.put("mergedPlanId", mergedPlanId);
    }
    args.put("note", "N4 靶子：" + terminal);
    return args;
  }

  /**
   * 终态 ⇒ {@code simos.gm.packet.decide} 的 {@code decision} 取值。
   *
   * <p>★ 取值来自 {@code GmDecidePacketTool.expectedStatus} 的真映射（回代码核过）：{@code DENY ⇒ REJECTED}、 {@code
   * MERGE ⇒ MERGED}、{@code APPROVE + 未选中的 call ⇒ PARTIALLY_APPROVED}、{@code APPROVE + 全选 ⇒
   * APPROVED}。映射不上 ⇒ 当场抛（不静默跳过那种终态）。
   */
  private static String decisionOf(PacketStatus terminal) {
    return switch (terminal) {
      case APPROVED, PARTIALLY_APPROVED -> "APPROVE";
      case REJECTED -> "DENY";
      case MERGED -> "MERGE";
      default -> throw new IllegalArgumentException("不是终态: " + terminal);
    };
  }

  private long tick() {
    return tickOf(world);
  }

  private static long tickOf(GmActAsWorldFixture.World target) {
    return target.state().meta().timestamp().tick();
  }

  private String packetId() {
    return packetIdIn(world);
  }

  private static String packetIdIn(GmActAsWorldFixture.World target) {
    return "pkt-" + GmActAsWorldFixture.DM + "-" + tickOf(target);
  }

  // ── 会话与留痕的读回 ─────────────────────────────────────────────────────────────────────

  /** 读一段会话的全部消息（渲染成「角色: 正文」）。★ 打不开/读不到 ⇒ 抛（不许静默退化成空表）。 */
  private static List<String> readConversation(Path db, String conversationId) {
    try (SqliteConversationStore store = SqliteConversationStore.open(db)) {
      List<String> rendered = new ArrayList<>();
      for (LlmMessage message : store.load(conversationId)) {
        rendered.add(message.role() + ": " + message.content());
      }
      return rendered;
    }
  }

  /** 轮询 GM 工具使用记录，直到它含 {@code expected}（记录是异步的）或超时 ⇒ 抛。 */
  private List<String> awaitToolUsageContaining(String expected) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    List<String> seen = List.of();
    while (System.nanoTime() < deadline) {
      seen = toolUsageNames();
      if (seen.contains(expected)) {
        return seen;
      }
      Thread.sleep(20);
    }
    throw new AssertionError(
        "10s 内 simos.gm.tool-usage 里没有 " + expected + "（实得 " + seen + "）—— 留痕装配异常");
  }

  private List<String> toolUsageNames() {
    ToolResult result = viaWorldGm(world, "simos.gm.tool-usage", Map.of());
    assertThat(result.success())
        .as("读 simos.gm.tool-usage 必须成功（实得 %s：%s）", result.code(), result.message())
        .isTrue();
    JsonNode view = parse(readBody(result.message()));
    JsonNode rows = view.has("entries") ? view.path("entries") : view;
    List<String> names = new ArrayList<>();
    for (JsonNode row : rows) {
      names.add(row.path("tool").asText());
    }
    return names;
  }

  /** 四轮各用一个**真实存在**的 {@code (0,0)} 家户（都在 {@code capital-province} 内 ⇒ 该 DM 够得着）。 */
  private static final String[] ROUND_HOUSEHOLDS = {
    "hh-0_0-rural-landed_peasant",
    "hh-0_0-rural-landless_laborer",
    "hh-0_0-urban-poor_peasant",
    "hh-0_0-urban-middle_peasant"
  };

  /**
   * 一轮的 {@code propose} 参数（一条可落地的退休 call）。
   *
   * <p>★ 每轮的 {@code toHouseholdId} 换成不同家户 ⇒ 四条被拒/落地的 call 各自可辨（累积判据有逐值判别力）。
   */
  private static Map<String, Object> proposeArgsForRound(int round) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("intent", "第" + round + "轮决策");
    args.put(
        "calls",
        List.of(Map.of("tool", PROPOSABLE, "args", retireArgs(1, ROUND_HOUSEHOLDS[round - 1]))));
    return args;
  }

  /** 真实工具名 ⇒ 供应商线名（唯一转义点在生产里：{@code LlmToolNames.wireNameOf}）。 */
  private static String wireNameOf(String realName) {
    return io.mosire.simos.ga.llm.LlmToolNames.wireNameOf(realName);
  }

  /** 一次 LLM 请求的可读渲染（"上下文里带着什么"的可执行形态）。 */
  private static String render(LlmRequest request) {
    try {
      return JSON.writeValueAsString(request.messages());
    } catch (IOException e) {
      throw new AssertionError("请求渲染失败", e);
    }
  }

  /** {@code GmActAsTool} 的源码（G10 的源码扫描侧；路径相对 {@code simos-app/} —— surefire 的工作目录）。 */
  private static Path actAsSource() {
    Path path =
        Path.of(
            "..",
            "simos-ga",
            "src",
            "main",
            "java",
            "io",
            "mosire",
            "simos",
            "ga",
            "tools",
            "write",
            "GmActAsTool.java");
    if (!Files.isRegularFile(path)) {
      throw new AssertionError("找不到 GmActAsTool 源码（G10 的源码扫描侧）：" + path.toAbsolutePath());
    }
    return path;
  }
}
