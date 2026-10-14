package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolCallAuthorizer;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.tools.SimosToolSource;
import io.mosire.simos.app.world.SmallWorld;
import io.mosire.simos.ga.GaSnapshot;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.model.DecisionMaker;
import io.mosire.simos.ga.model.SdInfoEntry;
import io.mosire.simos.ga.tools.ToolBuckets;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>GM ⇄ DM 双向信件 + 推演确认 —— 端到端判据</b>（约束设计书
 * `docs/superpowers/plans/2026-10-14-gm-dm-interaction.md` 的 §3.1 / §3.5 / §4 / §6 第 1·2·5·9·10 条 /
 * §6.1 N1·N2·N3·N7·N9）。
 *
 * <p>★ <b>本类测什么、不测什么</b>：不测"桶里有没有那个名字"（那是 {@code SimosToolsTest} / {@code DecisionFaceGolden}
 * 的事），测的是<b>接线真的通了、闸门真的在</b>：真 {@link Shell} 装配 + 真 GM 工具（{@code simos.gm.*} 走真 handler、真
 * revision）+ 真决策人身份（{@code DecisionCallerFactory} 现算的 {@code decision-maker:<id>}）+ 真落盘（{@code
 * ga:message.*} 条目、revision 前后差）。
 *
 * <p>★★ <b>装置与 {@code DmMessagingEndToEndTest} 同源（两段式起壳）</b>：决策人必须<b>先经真命令建进 store</b>， 第二次 {@code
 * Shell.start} 才能在装配期读到决策人清单。决策人 id 每个实例唯一（带 {@code System.nanoTime()} 后缀）——
 * 装配期会给每个决策人挂默认工作区，而那份挂载表是<b>进程内静态单例</b> ⇒ 同 JVM 里多个 {@code Shell} 共享它。
 *
 * <p>★★ <b>如实记的一条依赖（与 BLOCKED-1 同源）</b>：本类在<b>全量</b> {@code test} 里跑是绿的，但因为它"第二段"会带着
 * 世界里已存在的决策人起壳，它<b>单独跑</b>会撞上装配期那个 {@code M9/W1} 挂载缺陷（见台账 §6 的 BLOCKED-1）。 同一现象在 {@code
 * DmMessagingEndToEndTest} 上早已存在（不是本类引入的）。
 */
class GmDmLettersEndToEndTest {

  /** DM 侧发信（★ 本文件是**独立规格**，故写字面量：改名要有人显式改这里）。 */
  private static final String DM_SEND = "simos.ga.message.send";

  /** GM 侧收件箱（只读，GmOnlyRead）。 */
  private static final String GM_INBOX = "simos.gm.inbox";

  /** GM 侧发信（写）。 */
  private static final String GM_SEND = "simos.gm.message.send";

  /** GM 侧看板（只读，GmOnlyRead）。 */
  private static final String GM_ROUND = "simos.gm.round";

  /** 推演确认（DM 侧，零世界效果）。 */
  private static final String ACK = "simos.ga.adjudication.ack";

  /** GM 的授权命令类型（一次写两条 DM 记录、一条 revision）。 */
  private static final String PEER = "ga.SetDecisionMakerPeer";

  /** ★★ 设计书 §9-A 裁定：GM 的稳定 id = {@code "gm"}（唯一拼写点 {@code ToolResources.GM_ID}）。★ 写字面量当独立规格。 */
  private static final String GM_ID = "gm";

  /**
   * ★★ 同 tick 同方向信件上限（设计书 §9-C 用户裁定「至少改成128」）—— ★ 本文件是**独立规格**，故写死字面量而 <b>不</b>读 {@code
   * GaMessageSendTool.MAX_MESSAGES_PER_TICK_PER_DIRECTION}：读常量的话，"上限被改成 1"这种变异会让用例 <b>跟着一起变小</b>（发
   * 1 条、第 2 条被拒 ⇒ 照样绿），判别力归零。
   */
  private static final int SAME_TICK_MESSAGE_LIMIT = 128;

  private final String suffix = Long.toHexString(System.nanoTime());

  /** A：被授权的决策人（§6 第 1/2 条的提问方）。 */
  private final String dmAId = "dm-a-" + suffix;

  /** B：与 A 互通的另一个决策人（N9 的靶子：GM 收件箱里不得出现 peer DM 之间的信）。 */
  private final String dmBId = "dm-b-" + suffix;

  @TempDir Path tempDir;

  // ── §6 第 1 条 + §4 I1 ────────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>§6 第 1 条：DM 问 GM 一问</b>：DM 调 {@code simos.ga.message.send{to:"gm"}} ⇒ <b>世界零变化</b>（包/信在
   * INFO 覆盖层，不改任何世界命名空间）；GM 调 {@code simos.gm.inbox} 能读到它，且 <b>{@code answered=false}</b>。
   *
   * <p>★ <b>"世界零变化"怎么断言才不是空话</b>：断言的是"**除 info 覆盖层之外**的 ga 切片逐字段不变"—— 只断言 {@code head}
   * 不动会漏掉"写了世界但没落 revision"的实现，只断言 {@code head} 动了又会把合法的 INFO 写入误判成违规。 故这里同时钉两件事：① {@code
   * decisionMakers} / {@code decisionPackets} 逐条不变；② 落的是 {@code ga:message.*} 地址。
   */
  @Test
  void dmAsksGm_gmInboxSeesItUnanswered_andNoWorldChange() {
    try (Shell shell = startShell(seedStoreWithDecisionMakers())) {
      Map<String, Object> dmStateBefore = worldStateOf(shell);

      ToolResult asked = dmCall(shell, dmAId, DM_SEND, Map.of("to", GM_ID, "body", "请问这一带的粮产如何？"));
      assertThat(asked.success()).as("§6-1：DM → GM 必须成功；实得 %s", asked.message()).isTrue();
      assertThat(asked.message()).as("★ 回执里带得上收件人 = gm（地址段由身份与 to 派生）").contains(GM_ID);

      // ★ I1：世界侧（决策人表 / 决策包表）一个字段都不许动。
      assertThat(worldStateOf(shell))
          .as("★★ I1：发信零世界效果（只落 ga:message.* INFO，不动 decisionMakers / decisionPackets）")
          .isEqualTo(dmStateBefore);

      ToolResult inbox = gmCall(shell, GM_INBOX, Map.of());
      assertThat(inbox.success()).as("§6-1：GM 收件箱必须可用；实得 %s", inbox.message()).isTrue();
      assertThat(inbox.message()).as("★ 正文逐字到达").contains("请问这一带的粮产如何？").contains(dmAId);
      assertThat(inboxCount(inbox)).as("§6-1：GM 收件箱里恰好 1 条").isEqualTo(1L);
      assertThat(inboxAnsweredFlags(inbox))
          .as("★★ §6-1：还没回 ⇒ answered=false（answered 是**派生列**：存在 replyTo 指向它的 GM 回信才为 true）")
          .containsExactly(false);
      assertThat(inboxUnanswered(inbox)).as("★ 未答计数也必须对得上").isEqualTo(1L);

      // ★ 地址族：GM 的读法（无过滤读 info 覆盖层）看得见这条，且落在 ga:message.* 上。
      assertThat(infoAddresses(shell))
          .as("★ 信件落 ga:message.*（零新状态：复用既有 INFO 覆盖层）")
          .anyMatch(address -> address.startsWith("ga:message."));
    }
  }

  // ── §6 第 2 条 ────────────────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>§6 第 2 条：GM 答</b>：GM 调 {@code simos.gm.message.send{to:<dm>, body, replyTo:<id>}} ⇒ 那条提问的
   * <b>{@code answered=true}</b>（派生列翻面）；且回信的 {@code from} 恒为 {@code "gm"}。
   *
   * <p>★ <b>{@code replyTo} 是"答"这件事的<u>唯一</u>载体</b>：没有它，GM 的回信在 DM 侧只是"又一封来信"， 而 {@code answered}
   * 与"未答提问 ⇒ 不结算"（§3.3 / N8）两条判据全部会失效 —— 故本用例把它钉死在**行为**上。
   */
  @Test
  void gmAnswers_theQuestionBecomesAnsweredAndTheReplyComesFromGm() {
    try (Shell shell = startShell(seedStoreWithDecisionMakers())) {
      assertThat(dmCall(shell, dmAId, DM_SEND, Map.of("to", GM_ID, "body", "问题甲")).success())
          .isTrue();

      ToolResult inboxBefore = gmCall(shell, GM_INBOX, Map.of());
      String questionId = firstMessageId(inboxBefore);
      assertThat(inboxAnsweredFlags(inboxBefore)).as("前提：还没答").containsExactly(false);

      ToolResult replied =
          gmCall(shell, GM_SEND, Map.of("to", dmAId, "body", "答复甲：粮产充足", "replyTo", questionId));
      assertThat(replied.success()).as("§6-2：GM 回信必须成功；实得 %s", replied.message()).isTrue();

      ToolResult inboxAfter = gmCall(shell, GM_INBOX, Map.of());
      assertThat(inboxAnsweredFlags(inboxAfter))
          .as("★★ §6-2：replyTo 指向的那条提问必须翻成 answered=true")
          .containsExactly(true);
      assertThat(inboxUnanswered(inboxAfter)).as("§6-2：未答计数归零").isZero();

      // ★ GM 的回信自己是**新的**一条（不是把提问改了），且 from 恒为 gm。
      List<SdInfoEntry> messages = messagesInOverlay(shell);
      assertThat(messages).as("§6-2：提问 + 回信 = 两条独立的信").hasSize(2);
      assertThat(messages)
          .as("★★ I7/I8：GM 侧发的信 from 恒为 \"gm\"（编译期常量，载荷里没有 from 键）")
          .anySatisfy(
              entry ->
                  assertThat(String.valueOf(entry.value()))
                      .contains("\"from\":\"" + GM_ID + "\"")
                      .contains("\"to\":\"" + dmAId + "\""));
      // ★ 反向：DM 侧发的信 from 恒为**它自己的 id**，永远不会是 gm（I8：DM 不能冒充 GM 的身份段）。
      assertThat(messages)
          .as("★★ I8：DM 发的信 from 恒为它自己的 id（DM 冒充不了 gm）")
          .anySatisfy(
              entry ->
                  assertThat(String.valueOf(entry.value()))
                      .contains("\"from\":\"" + dmAId + "\"")
                      .doesNotContain("\"from\":\"" + GM_ID + "\""));
    }
  }

  // ── §6.1 N1 / N2 / N3 ───────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>N1：DM 调 {@code simos.gm.message.send} ⇒ 具名拒、世界零变化</b>（不在决策人可达面）。
   *
   * <p>★★ <b>实测与设计书 §6.1 N1 的预测不同（以实测为准；判据强度不降反升）</b>：设计书预测码是 {@code PERMISSION_DENIED}，<b>实际是
   * {@code TOOL_NOT_FOUND}</b>。机制：{@code gm.message.send} 的 {@code roles()={GM}} ⇒ 它<b>不进决策人桶</b> ⇒
   * 决策人那个 {@code ToolRegistry} 里根本没有这条工具 ⇒ 在 {@code ToolExecutionGuard}
   * 的<b>查表阶段</b>就被拒（比白名单/资源断言更早一层）。 ★ 为什么这更严：{@code PERMISSION_DENIED} 是"工具在你这、但你不许用"；{@code
   * TOOL_NOT_FOUND} 是"这条工具压根没发给你" —— 模型连它的名字都看不见，<b>没有可绕的路径</b>。 ★ 如实记：本条的码按<b>实测</b>写死（{@code
   * TOOL_NOT_FOUND}），并把"桶归属"这一机制侧判据同时钉住；台账 §7 单列这处偏差。
   */
  @Test
  void n1_dmCannotCallTheGmSendTool() {
    try (Shell shell = startShell(seedStoreWithDecisionMakers())) {
      long headBefore = head(shell);
      assertThat(decisionBuckets(shell))
          .as("★ N1 的机制侧：gm.message.send 不进决策人桶（roles={GM}）")
          .doesNotContain(GM_SEND);
      assertThat(gmBuckets(shell)).as("★ N1 的机制侧：它在 GM 桶").contains(GM_SEND);

      ToolResult denied = dmCall(shell, dmAId, GM_SEND, Map.of("to", dmBId, "body", "我想冒充 GM"));

      assertThat(denied.success()).as("★★ N1：DM 调 GM 侧发信工具必须拒").isFalse();
      assertThat(denied.code())
          .as(
              "★★ N1：拒在**查表阶段**（这条工具没发到决策人面上）—— 比 PERMISSION_DENIED 更早、无可绕路径；"
                  + "★ 与设计书 §6.1 N1 预测的 PERMISSION_DENIED 不同，以实测为准（见台账 §7）")
          .isEqualTo("TOOL_NOT_FOUND");
      assertThat(head(shell)).as("★ N1：被拒 = 世界零变化").isEqualTo(headBefore);
      assertThat(messagesInOverlay(shell)).as("★ N1：一条信都没落").isEmpty();
    }
  }

  /**
   * ★★ <b>N2：给未授权的另一个 DM 发信 ⇒ {@code RESOURCE_DENIED}</b>；★★ <b>N3：给自己发 ⇒ {@code BAD_REQUEST}</b>
   * （<b>既有守卫不退化</b>）。
   *
   * <p>★ 两条**必须分开钉**（不是重复）：{@code RESOURCE_DENIED} = <b>配置错</b>（改资源：让 GM 授权）， {@code BAD_REQUEST} =
   * <b>用法错</b>（改参数：{@code to} 该填别人）。折成同一个码，模型就分不出"改配置"还是"改参数"。 ★ 设计书 §3.1 明确：DM→GM **不需要** GM
   * 事先授权（写的是自己的疑问），但 DM→DM **仍需授权**（那是把信息给第三方）—— 所以"给未授权 DM 发信"必须仍然拒，这是"广义化 {@code to}
   * 的合法值"**没有**顺手放宽的那条边界。
   */
  @Test
  void n2UnauthorizedPeerIsResourceDenied_andN3SelfSendIsBadRequest() {
    try (Shell shell = startShell(seedStoreWithDecisionMakers())) {
      long headBefore = head(shell);

      ToolResult toUnauthorized =
          dmCall(shell, dmAId, DM_SEND, Map.of("to", dmBId, "body", "给没授权的人"));
      assertThat(toUnauthorized.success()).as("★★ N2：给未授权的 DM 发信必须拒").isFalse();
      assertThat(toUnauthorized.code())
          .as("★★ N2：拒因是**资源级**（peer:<对方> 够不着）—— 修法是让 GM 授权")
          .isEqualTo(ToolCallAuthorizer.RESOURCE_DENIED);
      assertThat(toUnauthorized.message())
          .as("★ N2：具名拒 —— 文案点出够不着哪个 peer 资源")
          .contains("peer:" + dmBId);

      ToolResult toSelf = dmCall(shell, dmAId, DM_SEND, Map.of("to", dmAId, "body", "给自己"));
      assertThat(toSelf.success()).as("★★ N3：给自己发信必须拒（既有守卫不退化）").isFalse();
      assertThat(toSelf.code())
          .as("★★ N3：拒因是**用法错**（BAD_REQUEST）而不是资源够不着 —— 修法是改参数 to")
          .isEqualTo("BAD_REQUEST");
      assertThat(toSelf.message()).as("★ N3：文案点出『不能给自己发消息』（模型据此改参数）").contains("不能给自己发消息");
      assertThat(toSelf.message())
          .as("★ N3：用法错的文案里不得混进资源面的措辞（那会误导模型去改配置）")
          .doesNotContain("不在调用者的可达面内");

      assertThat(head(shell)).as("★★ N2/N3：两条被拒的调用都必须是世界零变化").isEqualTo(headBefore);
      assertThat(messagesInOverlay(shell)).as("★ N2/N3：一条信都没落").isEmpty();
    }
  }

  // ── §6.1 N9 + §4 I11 ────────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>N9（I11）：{@code gm.inbox} 里不得出现 peer DM 之间的信</b>。
   *
   * <p>★ 判据是 {@code to == "gm"}：GM 的收件箱只回"寄给 GM"的信；DM↔DM 的信 {@code to} 是另一个 DM ⇒ 不进 GM 收件箱。 ★
   * 为什么这条不能省：G1 把 DM 的 {@code to} 合法值**广义化**成"{@code gm} 或已授权 peer"——广义化写松一点 （例如"只要是信就都算给 GM 的"）就会让
   * GM 收件箱变成**全量信箱**，而那种坏法**没有任何报错**，只是 GM 看见了不该它看的私信。
   */
  @Test
  void n9_gmInboxNeverShowsPeerToPeerMail() {
    try (Shell shell = startShell(seedStoreWithDecisionMakers())) {
      // 先授权 A↔B，让 DM↔DM 的那条路真的通（否则 N9 会退化成 N2 的重复）。
      authorize(shell, dmAId, dmBId);

      assertThat(dmCall(shell, dmAId, DM_SEND, Map.of("to", dmBId, "body", "A 给 B 的私信")).success())
          .as("前提：A → B 成功（DM↔DM 的信真的落了盘）")
          .isTrue();
      assertThat(dmCall(shell, dmBId, DM_SEND, Map.of("to", dmAId, "body", "B 给 A 的私信")).success())
          .as("前提：B → A 成功")
          .isTrue();
      assertThat(dmCall(shell, dmAId, DM_SEND, Map.of("to", GM_ID, "body", "A 给 GM 的公信")).success())
          .as("前提：A → GM 成功")
          .isTrue();

      ToolResult inbox = gmCall(shell, GM_INBOX, Map.of());
      assertThat(inbox.success()).as("N9：GM 收件箱必须可用；实得 %s", inbox.message()).isTrue();
      assertThat(inboxCount(inbox))
          .as("★★ N9：GM 收件箱**只有**寄给 GM 的那 1 条（两条 DM↔DM 的不得出现）")
          .isEqualTo(1L);
      assertThat(inbox.message())
          .as("★★ N9/I11：peer DM 之间的信一条都不许出现在 GM 收件箱里")
          .contains("A 给 GM 的公信")
          .doesNotContain("A 给 B 的私信")
          .doesNotContain("B 给 A 的私信");
      // ★ 对照：那两条私信**确实**在世界的 INFO 覆盖层里（不是"根本没发出去"——否则本用例是假绿）。
      assertThat(messagesInOverlay(shell))
          .as("★ 对照：三条信都在覆盖层里 ⇒ 上面那条'只有 1 条'是**筛子**的功劳，不是通路坏了")
          .hasSize(3);
    }
  }

  // ── §6 第 9 条 + §3.5 ───────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>§6 第 9 条：推演确认</b>：DM 调 {@code simos.ga.adjudication.ack{tick}} ⇒ {@code simos.gm.round}
   * 里该 DM 的 <b>{@code adjudicationAcked=true}</b>；<b>同 tick 再 ack ⇒ 具名拒</b>（显式 id ⇒ 命令期重复即拒 ⇒
   * 天然幂等一次）。
   *
   * <p>★ 判别力：{@code adjudicationAcked} 是**纯派生列**（从 {@code ga:adjudicationAck.<tick>.<dm>} 算出来的）。
   * 只断言"ack 调用成功"会让"调用成功了但 GM 看不到"这种接线断裂逃掉 —— 故必须从 <b>GM 的读口</b>回读。 ★ 同时钉"同 tick 第二条被拒 +
   * 世界零变化"：没有这道守卫，DM 可以刷无限条 ack（每次都烧一条 revision）。
   */
  @Test
  void adjudicationAckIsVisibleToGmRound_andASecondAckInTheSameTickIsRejected() {
    try (Shell shell = startShell(seedStoreWithDecisionMakers())) {
      long tick = tick(shell);

      ToolResult roundBefore = gmCall(shell, GM_ROUND, Map.of("tick", tick));
      assertThat(roundBefore.success()).as("前提：GM 看板可用；实得 %s", roundBefore.message()).isTrue();
      assertThat(ackedOf(roundBefore, dmAId)).as("前提：还没确认过 ⇒ false").isFalse();

      ToolResult acked = dmCall(shell, dmAId, ACK, Map.of("tick", tick, "note", "这几条令我看过了"));
      assertThat(acked.success()).as("§6-9：ack 必须成功；实得 %s", acked.message()).isTrue();

      ToolResult roundAfter = gmCall(shell, GM_ROUND, Map.of("tick", tick));
      assertThat(ackedOf(roundAfter, dmAId))
          .as("★★ §6-9：GM 看板里该 DM 的 adjudicationAcked 必须翻成 true（纯派生列，从 GM 的读口回读）")
          .isTrue();
      assertThat(roundAfter.message())
          .as("★ §6-10：看板一次给出'谁已确认决策 / 谁有未答问题 / 谁已确认推演'三样")
          .contains("packetStatus")
          .contains("openQuestions")
          .contains("adjudicationAcked")
          .contains(dmAId);

      // ★ 对照：**另一个** DM 没确认过 ⇒ 仍是 false（证明 true 是按 DM 分片算的，不是"有人 ack 就全 true"）。
      assertThat(ackedOf(roundAfter, dmBId)).as("★ 对照：另一个没 ack 的 DM 仍是 false").isFalse();

      // ★ 同 tick 第二条 ⇒ 命令期具名拒 + 世界零变化。
      long headBeforeSecond = head(shell);
      ToolResult second = dmCall(shell, dmAId, ACK, Map.of("tick", tick));
      assertThat(second.success()).as("★★ §6-9：同 tick 再 ack 必须具名拒（显式 id ⇒ 命令期重复即拒）").isFalse();
      assertThat(second.message())
          .as("★ 具名拒 —— 文案点出撞的是哪个条目 id")
          .contains("ack-" + tick + "-" + dmAId);
      assertThat(head(shell)).as("★★ 被拒 = 世界零变化").isEqualTo(headBeforeSecond);
    }
  }

  /**
   * ★★ <b>§3.4.1 的信件序（这一条是一个真缺陷的回归靶子）</b>：信件 id = {@code msg-<from>-<to>-<tick>-<n>}，而 {@code <n>}
   * 是**十进制** ⇒ 按 {@code (tick, id)} <b>字典序</b>排会得到 {@code 0,1,10,11,2,…,9} （同一 tick 内 ≥10
   * 条时）。若注入游标是"最后一条的 id + 1"，新到的第 12 条（{@code -12}）会排在游标**前面** ⇒ {@code start = index(cursor)+1}
   * <b>跳过它</b> ⇒ <b>答复被静默丢掉</b>（正是本设计最不能出的错）。
   *
   * <p>★ <b>本用例怎么钉住"不跳过第 12 条"</b>：同 tick 同方向连发 <b>12</b> 条，然后 ① {@code gm.inbox} 必须一条不少地回 12
   * 条（<b>且顺序必须是数值序</b>：第 10/11/12 条排在 9 之后，不是排在 1 之后）； ② 最关键的靶子——{@link
   * io.mosire.simos.ga.tools.read.GmInboxTool#letters} 给出的**序列**必须是 {@code 0,1,2,…,11}（数值序）。★ 排序改回
   * {@code (tick, id)} ⇒ 本条当场红（变异自证见台账 §4）。
   *
   * <p>★ 为什么是 12 而不是 10：{@code -10} 与 {@code -1} 的字典序差在第 2 个字符，10 条就能暴露；取 12 是为了让 "第 12
   * 条排在游标前"这个**具体的**失效形态一定被造出来（{@code -12} 字典序小于 {@code -2}）。
   */
  @Test
  void lettersWithinOneTickAreOrderedNumericallySoTheTwelfthIsNotSkipped() {
    try (Shell shell = startShell(seedStoreWithDecisionMakers())) {
      int count = 12;
      for (int i = 0; i < count; i++) {
        ToolResult sent =
            dmCall(shell, dmAId, DM_SEND, Map.of("to", GM_ID, "body", "第 " + i + " 封"));
        assertThat(sent.success()).as("前提：第 %d 封必须成功；实得 %s", i + 1, sent.message()).isTrue();
      }

      ToolResult inbox = gmCall(shell, GM_INBOX, Map.of("limit", 200));
      assertThat(inboxCount(inbox)).as("§3.4.1：同 tick 12 封一条都不能少").isEqualTo(count);

      // ★★ 靶子：序列号必须是 **0,1,2,…,11**（数值序）。按 (tick,id) 字典序的实现会给出 0,1,10,11,2,3,…,9。
      assertThat(sequenceNumbers(inbox))
          .as(
              "★★ §3.4.1：同 tick 的信必须按**数值序**排 —— 字典序会给出 "
                  + "[0,1,10,11,2,3,4,5,6,7,8,9]，于是第 12 封（-12）排在游标前面、被注入游标静默跳过")
          .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
      assertThat(messageBodies(inbox))
          .as("★★ §3.4.1：第 11 封（n=11，字典序里排第 4）必须排在最后 —— 它是最容易被跳过的那一封")
          .last()
          .isEqualTo("第 11 封");
      assertThat(messageBodies(inbox))
          .as("★ 对照：第 1 封（n=1）仍排在第 2 位（数值序，不是字典序的 [0,1,10,11,…]）")
          .containsExactly(
              "第 0 封", "第 1 封", "第 2 封", "第 3 封", "第 4 封", "第 5 封", "第 6 封", "第 7 封", "第 8 封",
              "第 9 封", "第 10 封", "第 11 封");
    }
  }

  // ── §6.1 N7 ─────────────────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>N7（I9）：第 129 条同 tick 信件 ⇒ 命令期具名拒、世界零变化</b>。★ 靶子是 <b>GM 侧</b>的发信（DM 侧的同一 上限由 {@code
   * DmMessagingEndToEndTest#p4} 钉住）—— 两侧形状同源（都取自 {@code GaMessageSendTool} 的静态助手），
   * 但**同源不等于同一处代码**：GM 侧若自己抄了一份计数（或漏判），只有这一条会现形。
   *
   * <p>★ 上限取自设计书 §9-C 的用户裁定（「至少改成128」）⇒ 本用例写死 <b>128</b> 当独立规格（不读生产常量： 读常量的话"上限被改成
   * 1"会让用例跟着一起变小、判别力归零）。
   */
  @Test
  void n7_theGmSideAlsoEnforcesThe128LimitPerTickPerDirection() {
    try (Shell shell = startShell(seedStoreWithDecisionMakers())) {
      for (int i = 0; i < SAME_TICK_MESSAGE_LIMIT; i++) {
        ToolResult sent = gmCall(shell, GM_SEND, Map.of("to", dmAId, "body", "GM 第 " + i + " 封"));
        assertThat(sent.success())
            .as(
                "★ N7 正向一侧：GM 侧第 %d 封（上限 %d）必须成功；实得 %s",
                i + 1, SAME_TICK_MESSAGE_LIMIT, sent.message())
            .isTrue();
      }

      long headBeforeOverflow = head(shell);
      ToolResult overflow = gmCall(shell, GM_SEND, Map.of("to", dmAId, "body", "GM 超限那一封"));

      assertThat(overflow.success()).as("★★ N7：GM 侧同 tick 第 129 封必须被拒").isFalse();
      assertThat(overflow.code()).as("N7：拒因是用法错（BAD_REQUEST = 等下一个 tick）").isEqualTo("BAD_REQUEST");
      assertThat(overflow.message())
          .as("★ N7：具名拒 —— 文案点出上限")
          .contains(String.valueOf(SAME_TICK_MESSAGE_LIMIT));
      assertThat(head(shell)).as("★★ N7：被拒 = 世界零变化").isEqualTo(headBeforeOverflow);
      assertThat(messagesInOverlay(shell))
          .as("★ N7：超限那一封一个字都没落地")
          .noneMatch(entry -> String.valueOf(entry.value()).contains("GM 超限那一封"));
      assertThat(messagesInOverlay(shell))
          .as("★ N7：128 封合法的都在（不是'被拒之后连前面的也回滚了'）")
          .hasSize(SAME_TICK_MESSAGE_LIMIT);
    }
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  /** 两段式夹具：先建世界与 2 个决策人并关壳，让第二次 {@code Shell.start} 能在装配期读到决策人清单。 */
  private Path seedStoreWithDecisionMakers() {
    Path store = tempDir.resolve("gm-dm-" + System.nanoTime());
    try (Shell shell = startShell(store)) {
      shell.coreSimos().bootstrapGenesis(SmallWorld.state(mapId()));
      createDecisionMaker(shell, dmAId);
      createDecisionMaker(shell, dmBId);
    }
    return store;
  }

  private static void createDecisionMaker(Shell shell, String id) {
    ToolResult result =
        gmCall(
            shell,
            "ga.CreateDecisionMaker",
            Map.of("payloadJson", "{\"id\":\"" + id + "\",\"allowedTools\":[],\"cadence\":1}"));
    assertThat(result.success()).as("夹具：建决策人 %s 必须成功；实得 %s", id, result.message()).isTrue();
  }

  private static void authorize(Shell shell, String a, String b) {
    ToolResult granted =
        gmCall(
            shell,
            PEER,
            Map.of(
                "payloadJson", "{\"dmA\":\"" + a + "\",\"dmB\":\"" + b + "\",\"allowed\":true}"));
    assertThat(granted.success()).as("夹具：授权 %s ↔ %s 必须成功；实得 %s", a, b, granted.message()).isTrue();
  }

  private ToolResult dmCall(Shell shell, String dmId, String toolName, Map<String, Object> args) {
    return decisionFactory(shell)
        .execute(
            decisionRegistry(shell),
            toolName,
            decisionMaker(shell, dmId),
            headState(shell),
            mapId(),
            args);
  }

  /** GM 面工具调用（自动放行，身份 = 真 GM）。★ 与 {@code DmMessagingEndToEndTest} 同法。 */
  private static ToolResult gmCall(Shell shell, String toolName, Map<String, ?> args) {
    ToolContext gm = Shell.gmCaller();
    Map<String, Object> full = new LinkedHashMap<>(args);
    full.put("branch", "main");
    full.put(
        "expectedRevision", shell.coreSimos().head(new BranchId("main")).orElseThrow().value());
    ToolContext call =
        new ToolContext(
            gm.caller(), gm.permissions(), gm.config(), full, gm.identity(), gm.resources());
    return shell.gmToolAuthorizer().execute(shell.toolRegistry(), toolName, call);
  }

  private Shell startShell(Path store) {
    ShellConfig base = ShellConfig.defaults(store).withPorts(0, 0, 0);
    return Shell.start(
        new ShellConfig(
            base.storeDir(),
            base.checkpointInterval(),
            base.guiPort(),
            base.mcpPort(),
            base.mcpPath(),
            base.approvalPort(),
            "agent:gm-dm-letters-test",
            base.mapId(),
            base.bindAddress()));
  }

  private String mapId() {
    return ShellConfig.defaults(tempDir).mapId();
  }

  private static long head(Shell shell) {
    return shell.coreSimos().head(new BranchId("main")).orElseThrow().value();
  }

  private static SimulationState headState(Shell shell) {
    BranchId main = new BranchId("main");
    return shell.coreSimos().replay(new StateRef(main, new RevisionId(head(shell))));
  }

  private static long tick(Shell shell) {
    return headState(shell).meta().timestamp().tick();
  }

  private static GaState gaState(Shell shell) {
    GaSnapshot ga = (GaSnapshot) headState(shell).module("ga").orElseThrow();
    return ga.state();
  }

  private static DecisionMaker decisionMaker(Shell shell, String id) {
    return Optional.ofNullable(gaState(shell).decisionMakers().get(new DecisionMakerId(id)))
        .orElseThrow(() -> new AssertionError("夹具：世界里必须有决策人 " + id));
  }

  /**
   * ★ I1 的"世界侧"快照：**只取世界命名空间那几张表**（决策人 + 决策包），不取 {@code info()} 覆盖层。
   *
   * <p>★ 为什么必须排除 {@code info()}：信件**本来就**落在 INFO 覆盖层（零新状态的代价与红利都在这里）⇒ 把覆盖层算进"世界状态"会让 I1
   * 变成"发信前后完全相等"这种**恒假**的判据。I1 要的是"不动世界命名空间"。
   */
  private static Map<String, Object> worldStateOf(Shell shell) {
    GaState ga = gaState(shell);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("decisionMakers", ga.decisionMakers());
    out.put("decisionPackets", ga.decisionPackets());
    return out;
  }

  private static List<String> gmBuckets(Shell shell) {
    return shell.toolsFor(SimosToolSource.Role.GM).stream().map(AgentTool::name).toList();
  }

  private static Set<String> decisionBuckets(Shell shell) {
    return ToolBuckets.namesOf(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT));
  }

  private static List<SdInfoEntry> messagesInOverlay(Shell shell) {
    List<SdInfoEntry> out = new ArrayList<>();
    for (List<SdInfoEntry> entries : gaState(shell).info().values()) {
      for (SdInfoEntry entry : entries) {
        if ("message".equals(entry.key())) {
          out.add(entry);
        }
      }
    }
    return out;
  }

  private static List<String> infoAddresses(Shell shell) {
    return List.copyOf(gaState(shell).info().keySet());
  }

  // ── 读 JSON 形状的小助手（都从**真返回**里读，不重算）────────────────────────────────────

  private static JsonNodeReader read(ToolResult result) {
    try {
      return new JsonNodeReader(SimosObjectMapper.create().readTree(result.message()));
    } catch (JsonProcessingException e) {
      throw new AssertionError("返回必须是 JSON: " + result.message(), e);
    }
  }

  private static long inboxCount(ToolResult inbox) {
    return read(inbox).count();
  }

  private static long inboxUnanswered(ToolResult inbox) {
    return read(inbox).unanswered();
  }

  private static String firstMessageId(ToolResult inbox) {
    return read(inbox).messageId(0);
  }

  private static List<Boolean> inboxAnsweredFlags(ToolResult inbox) {
    return read(inbox).answeredFlags();
  }

  /** ★ 每封信 id 的最后一段（{@code msg-<from>-<to>-<tick>-<n>} 里的 {@code n}），按返回顺序。 */
  private static List<Integer> sequenceNumbers(ToolResult inbox) {
    return read(inbox).sequences();
  }

  private static List<String> messageBodies(ToolResult inbox) {
    return read(inbox).bodies();
  }

  private static boolean ackedOf(ToolResult round, String dmId) {
    return read(round).ackedOf(dmId);
  }

  /** 极小的 JSON 读壳（只读本类用到的几个键；不引第三方断言，保持"读的是真返回"）。 */
  private record JsonNodeReader(com.fasterxml.jackson.databind.JsonNode root) {

    long count() {
      return root.path("count").asLong();
    }

    long unanswered() {
      return root.path("unanswered").asLong();
    }

    com.fasterxml.jackson.databind.JsonNode messages() {
      return root.path("messages");
    }

    String messageId(int index) {
      return messages().get(index).path("id").asText();
    }

    List<Boolean> answeredFlags() {
      List<Boolean> out = new ArrayList<>();
      messages().forEach(node -> out.add(node.path("answered").asBoolean()));
      return out;
    }

    List<Integer> sequences() {
      List<Integer> out = new ArrayList<>();
      messages()
          .forEach(
              node -> {
                String id = node.path("id").asText();
                out.add(Integer.parseInt(id.substring(id.lastIndexOf('-') + 1)));
              });
      return out;
    }

    List<String> bodies() {
      List<String> out = new ArrayList<>();
      messages().forEach(node -> out.add(node.path("body").asText()));
      return out;
    }

    boolean ackedOf(String dmId) {
      for (com.fasterxml.jackson.databind.JsonNode row : root.path("decisionMakers")) {
        if (dmId.equals(row.path("id").asText())) {
          return row.path("adjudicationAcked").asBoolean();
        }
      }
      throw new AssertionError("GM 看板里必须有决策人 " + dmId + "；实得 " + root);
    }
  }

  private static DecisionCallerFactory decisionFactory(Shell shell) {
    return DecisionCallerFactory.forDecisionFace(
        shell.gmToolAuthorizer(),
        ToolBuckets.namesOf(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT)));
  }

  private static ToolRegistry decisionRegistry(Shell shell) {
    ToolRegistry registry = new ToolRegistry();
    registry.registerAll(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT));
    return registry;
  }
}
