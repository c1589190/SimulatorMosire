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
 * ★★ <b>2026-10-14 决策人互致消息（{@code simos.ga.message.*}）—— 端到端判据（约束设计书 §6.1 P1~P8、§6.2 N5、§10.4
 * 行为契约）</b>。
 *
 * <p>本类**不测"桶里有没有那个名字"**（那是 {@code SimosToolsTest}/{@code DecisionFaceGolden}
 * 的事），它测的是<b>接线真的通了、闸门真的在</b>： ① 真 {@link Shell} 装配 + 真 GM 命令（{@code ga.SetDecisionMakerPeer} 走真
 * handler、真 revision）+ ② 真决策人身份（{@code DecisionCallerFactory.callerFor} 现算的 {@code
 * decision-maker:<id>} + {@code peer} 可达面）+ ③ 真工具（{@code simos.ga.message.send}/{@code inbox} 经
 * {@code ToolCallAuthorizer} 唯一入口）+ ④ 真落盘（{@code ga.PutInfo} 条目、revision 前后差）。
 *
 * <p>★★ <b>装置说明（为什么是"两段式起壳"）</b>：与 {@code AgentLibDocsWiringTest} 同款 —— 决策人必须 <b>先经真命令建进
 * store</b>，第二次 {@code Shell.start} 才能在装配期读到决策人清单。故夹具 = ① 空库起壳 → bootstrap small-world → GM 建 4
 * 个决策人 → 关壳；② 非空 store 再起壳。
 *
 * <p>★★ <b>为什么必须走 {@code shell.gmToolAuthorizer()} 而不是 {@code shell.toolAuthorizer()}</b>：
 * 后者带真审批链（没人点会阻塞到超时）。本类判的是**资源面/闸门**，不是审批链；发信工具本身 {@code gate()=ALLOW}（I7：事先授权，不是逐条审批），判它用哪条
 * authorizer 都不改变结论。
 *
 * <p>★★ <b>决策人 id 每个实例唯一</b>（带 {@code System.nanoTime()} 后缀）：装配期会给每个决策人挂默认工作区，
 * 而那份挂载表是**进程内静态单例**（{@code SimosToolSource.ExternalWorkspaceHost}）⇒ 同 JVM 里多个 {@code Shell}
 * 共享它，重名的 DM 会让后一个用例的挂载指向已被清理的目录（假红/假绿）。同一理由见 {@code AgentLibDocsWiringTest}。
 */
class DmMessagingEndToEndTest {

  /** 发信工具名（★ 本文件是**独立规格**，故写字面量：改名要有人显式改这里）。 */
  private static final String SEND = "simos.ga.message.send";

  /** 收件箱工具名（同上）。 */
  private static final String INBOX = "simos.ga.message.inbox";

  /** GM 的授权命令类型（一次写两条 DM 记录、一条 revision）。 */
  private static final String PEER = "ga.SetDecisionMakerPeer";

  /** 旧上报族的两条工具名：**本批之后必须任何面都不出现**（P8）。 */
  private static final List<String> OLD_REPORT_FAMILY =
      List.of("simos.sd.report", "simos.sd.reports");

  private final String suffix = Long.toHexString(System.nanoTime());

  /** A / B：被授权互通的一对（P1/P2/P4/P5）。 */
  private final String dmAId = "dm-a-" + suffix;

  private final String dmBId = "dm-b-" + suffix;

  /** C：**零授权**的决策人 —— 既是 P7 的第三方，也是 P7b 的靶子（inbox 可用、send 对谁都拒）。 */
  private final String dmCId = "dm-c-" + suffix;

  /** D：{@code allowedTools} **非空**的决策人 —— D4（授权顺带并工具名）的靶子。 */
  private final String dmDId = "dm-d-" + suffix;

  @TempDir Path tempDir;

  // ── P1 + §10.4 行为契约 ───────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>P1 + §10.4 行为契约</b>：GM 授权 A↔B 后，A {@code send{to:B}} 成功，<b>B 的 inbox 读得到且正文逐字一致</b>；
   * 同时钉住本次可见性放宽的**唯一风险面** —— ★ <b>发件人在自己的 inbox 里看不到自己发出去的信</b>。
   *
   * <p>★ 为什么"发件人看不到自己发的"必须单独钉（设计书 §10.4）：{@code inbox} 的可见性走既有端口的三轴并集 （{@code tags} / 归属 / **发送人 =
   * 自己**），第三轴会让**自己发出去的信同样"可见"** ⇒ 靠"收件人 = 自己"那道筛子 把它筛掉。筛子一旦失效，A 的 inbox 里会冒出自己写的信 ——
   * 而"看得见"这件事**不会报错**，只会让决策人把 自己的话当成对方的话。这条断言就是那道筛子的靶子。
   */
  @Test
  void p1_authorizedPairExchangesMessagesAndTheSenderDoesNotSeeItsOwnMail() {
    try (Shell shell = startShell(seedStoreWithDecisionMakers())) {
      authorize(shell, dmAId, dmBId);
      long headBeforeSend = head(shell);

      ToolResult sent = sendAs(shell, dmAId, dmBId, "问候", "你好，B");
      assertThat(sent.success()).as("P1：A → B 必须成功；实得 %s", sent.message()).isTrue();
      assertThat(head(shell)).as("P1：一条消息 = 恰好 +1 条 revision").isEqualTo(headBeforeSend + 1L);

      ToolResult inboxB = inboxAs(shell, dmBId);
      assertThat(inboxB.success()).as("P1：B 的 inbox 必须可用；实得 %s", inboxB.message()).isTrue();
      String bodyB = inboxB.message();
      assertThat(bodyB).as("★★ P1：B 读得到 A 的正文（逐字）").contains("你好，B");
      assertThat(bodyB).as("P1：B 读得到发件人与主题").contains(dmAId).contains("问候");
      assertThat(count(inboxB)).as("P1：B 只该看到 1 条").isEqualTo(1L);

      // ★★ §10.4 的唯一风险面：发件人在**自己的** inbox 里看不到自己发出去的信。
      ToolResult inboxA = inboxAs(shell, dmAId);
      assertThat(inboxA.success()).as("A 的 inbox 本身可用；实得 %s", inboxA.message()).isTrue();
      assertThat(count(inboxA))
          .as("★★ §10.4：发件人在自己 inbox 里**看不到自己发出去的信**（to==自己 那道筛子的靶子）")
          .isZero();
      assertThat(inboxA.message()).as("★ A 自己的 inbox 里不得回显自己刚写的正文").doesNotContain("你好，B");

      // ★ 反向：B 回一条，A 才看得到 —— 证明"看不到"不是"整条通路坏了"。
      ToolResult sentBack = sendAs(shell, dmBId, dmAId, null, "收到，A");
      assertThat(sentBack.success()).as("P2 前置：B → A 也必须成功；实得 %s", sentBack.message()).isTrue();
      ToolResult inboxAAfter = inboxAs(shell, dmAId);
      assertThat(count(inboxAAfter)).as("★ 对照：B 回信之后 A 的 inbox 里恰好有 1 条（B 的）").isEqualTo(1L);
      assertThat(inboxAAfter.message()).contains("收到，A").contains(dmBId);

      // ★ 行为契约最后一条：GM 看得见全部（GM 的读法 = 无过滤地读 info 覆盖层，见 messagesInOverlay）。
      assertThat(messagesInOverlay(shell)).as("★ §10.4：GM 看得见全部两条消息（A→B 与 B→A）").hasSize(2);
    }
  }

  // ── P2 ────────────────────────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>P2 双向</b>：**一次**授权之后两个方向都能发 —— 不需要为反方向再授权一次。
   *
   * <p>★ 为什么这条是用户裁定的核心：用户原话「**双向**」。若实现成"逐条设"，GM 点两次之间是**半开态** （A 能写 B、B
   * 回不了）——那是可达且无告警的错误状态。本用例从**行为侧**钉住"一次授权 ⇒ 两向都通"（机制侧 由 {@code SetDecisionMakerPeerHandler} 的一条
   * revision 承担，见 {@code p2b}）。
   */
  @Test
  void p2_oneAuthorizationMakesBothDirectionsWork() {
    try (Shell shell = startShell(seedStoreWithDecisionMakers())) {
      authorize(shell, dmAId, dmBId);

      assertThat(sendAs(shell, dmAId, dmBId, null, "A→B").success()).as("P2：授权后 A → B 成功").isTrue();
      ToolResult back = sendAs(shell, dmBId, dmAId, null, "B→A");
      assertThat(back.success())
          .as("★★ P2：**同一次授权**之后 B → A 也成功（不再授权第二次）；实得 %s", back.message())
          .isTrue();
      assertThat(count(inboxAs(shell, dmAId))).as("P2：A 收到 B 的那一条").isEqualTo(1L);
      assertThat(count(inboxAs(shell, dmBId))).as("P2：B 收到 A 的那一条").isEqualTo(1L);
    }
  }

  /**
   * ★★ <b>P2 的机制侧（I3：双向原子）</b>：一次 {@code ga.SetDecisionMakerPeer} ⇒ 两侧各出现对方、 <b>只 +1 条
   * revision</b>。
   *
   * <p>★ 为什么行为侧之外还要钉这一条：行为侧（上一条）在"两次调用各写一条"的实现下**同样会绿**（GM 手工点两次 也能得到两个方向都通）——
   * 而那是用户明确否掉的形态。本用例专门排除它：**两个方向来自同一次提交**。
   */
  @Test
  void p2b_oneAuthorizationWritesBothSidesInExactlyOneRevision() {
    try (Shell shell = startShell(seedStoreWithDecisionMakers())) {
      long before = head(shell);

      ToolResult granted = grant(shell, dmAId, dmBId, true);
      assertThat(granted.success()).as("授权命令必须成功；实得 %s", granted.message()).isTrue();
      assertThat(head(shell))
          .as("★★ I3：一次授权 = **恰好 +1** 条 revision（两条记录同一次提交）")
          .isEqualTo(before + 1L);

      assertThat(peersOf(shell, dmAId)).as("I3：A 的可达面里出现 B").containsExactly(dmBId);
      assertThat(peersOf(shell, dmBId)).as("I3：同一次提交里 B 的可达面里出现 A（半开不可表达）").containsExactly(dmAId);

      // 关闸：同样一条命令、同样只 +1 条 revision，两侧各删去对方。
      long beforeRevoke = head(shell);
      assertThat(grant(shell, dmAId, dmBId, false).success()).as("关闸命令必须成功").isTrue();
      assertThat(head(shell)).as("关闸同样只 +1 条 revision").isEqualTo(beforeRevoke + 1L);
      assertThat(peersOf(shell, dmAId)).as("关闸后 A 不再够得着 B").isEmpty();
      assertThat(peersOf(shell, dmBId)).as("关闸后 B 不再够得着 A").isEmpty();
      assertThat(sendAs(shell, dmAId, dmBId, null, "关闸后").success())
          .as("★ 关闸的可执行形态：A → B 变成未授权拒")
          .isFalse();
    }
  }

  // ── P3 ────────────────────────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>P3 未授权</b>：A 发给**没被授权**的 C ⇒ <b>具名拒</b>（可达面文案），且 <b>世界零变化（无 revision）</b>。
   *
   * <p>★ 判别力：断言里同时钉"拒因是**资源级**"（不是白名单/身份/tool-not-found）与"**零 revision**" —— 只钉 {@code
   * success()==false} 会让"先写进去再报错"的实现逃掉。
   */
  @Test
  void p3_unauthorizedSendIsNamedRejectedAndLeavesNoRevision() {
    try (Shell shell = startShell(seedStoreWithDecisionMakers())) {
      authorize(shell, dmAId, dmBId);
      long before = head(shell);

      ToolResult denied = sendAs(shell, dmAId, dmCId, null, "给 C 的信");

      assertThat(denied.success()).as("P3：未授权必须拒").isFalse();
      assertThat(denied.code())
          .as("★ P3：拒因必须是**资源级**（可达面），不是白名单/身份级 —— 换个人名就该过")
          .isEqualTo(ToolCallAuthorizer.RESOURCE_DENIED);
      assertThat(denied.message())
          .as("★ P3：具名拒 —— 文案点出是哪个 peer 资源够不着")
          .contains("peer:" + dmCId)
          .contains("不在调用者的可达面内");
      assertThat(head(shell)).as("★★ P3：被拒 = 世界零变化（零 revision）").isEqualTo(before);
    }
  }

  // ── P4 ────────────────────────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>P4 每 tick 每方向一条（I1）</b>：A 在同一 tick 给 B 发第二条 ⇒ **命令期具名拒**（条目 id 已存在）， 且零 revision；B 的
   * inbox 仍然只有第一条。
   *
   * <p>★ 为什么"每 tick 一条"不需要自建限流器：条目 id = {@code msg-<from>-<to>-<tick>}，而 {@code PutInfoHandler} 对显式
   * id **命令期重复即拒**。本用例就是那条机制的靶子（N2 变异：去掉显式 id ⇒ 本用例红）。
   */
  @Test
  void p4_secondMessageInTheSameTickIsRejectedByNameAndLeavesNoRevision() {
    try (Shell shell = startShell(seedStoreWithDecisionMakers())) {
      authorize(shell, dmAId, dmBId);
      assertThat(sendAs(shell, dmAId, dmBId, "第一条", "第一封").success()).as("前提：第一条成功").isTrue();

      long beforeSecond = head(shell);
      ToolResult second = sendAs(shell, dmAId, dmBId, "第二条", "第二封");

      assertThat(second.success()).as("★★ P4：同 tick 第二条必须被拒").isFalse();
      assertThat(second.code())
          .as("P4：拒在**命令期**（submission.result=rejected）")
          .isEqualTo("REJECTED");
      assertThat(second.message())
          .as("★ P4：具名拒 —— 文案点出撞的是哪个条目 id")
          .contains("INFO 条目 id 已存在")
          .contains("msg-" + dmAId + "-" + dmBId + "-" + tick(shell));
      assertThat(head(shell)).as("★★ P4：被拒 = 零 revision").isEqualTo(beforeSecond);

      ToolResult inboxB = inboxAs(shell, dmBId);
      assertThat(count(inboxB)).as("P4：B 仍然只有第一条").isEqualTo(1L);
      assertThat(inboxB.message()).contains("第一封").doesNotContain("第二封");
    }
  }

  // ── P5 ────────────────────────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>P5 跨 tick 可再发</b>：推进一 tick 之后，A 再发给 B ⇒ 成功，且新条目的 tick 是**新的那个**（世界 tick 不得记未来、也不得复用旧 id）。
   */
  @Test
  void p5_theNextTickAllowsAnotherMessage() {
    try (Shell shell = startShell(seedStoreWithDecisionMakers())) {
      authorize(shell, dmAId, dmBId);
      assertThat(sendAs(shell, dmAId, dmBId, null, "第 0 tick").success())
          .as("前提：tick 0 发成功")
          .isTrue();
      long tickBefore = tick(shell);

      advanceOneTick(shell);
      assertThat(tick(shell)).as("前提：时间真的推进了").isEqualTo(tickBefore + 1L);

      ToolResult next = sendAs(shell, dmAId, dmBId, null, "下一 tick");
      assertThat(next.success()).as("★ P5：跨 tick 可再发；实得 %s", next.message()).isTrue();
      assertThat(next.message())
          .as("★ P5：新条目的 id 尾段 = 新 tick（世界 tick 不得记未来 / 不得复用旧 id）")
          .contains("msg-" + dmAId + "-" + dmBId + "-" + tick(shell));

      ToolResult inboxB = inboxAs(shell, dmBId);
      assertThat(count(inboxB)).as("P5：B 两条都收得到").isEqualTo(2L);
      assertThat(inboxB.message()).contains("第 0 tick").contains("下一 tick");
    }
  }

  // ── P6 ────────────────────────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>P6 不泄漏名册（I4）</b>：把 A 能看到/能拿到的**每一处字符串**扫一遍 —— 工具面（名字 + 描述 + JSON
   * schema）、发信返回、收件箱返回、以及各条错误文案 —— 其中**不得出现未被授权者的决策人 id**。
   *
   * <p>★ 为什么"结构上必然"还要测：名册在 {@code GaState.decisionMakers}、其读口是 GM-only，A 只能从自己的 {@code accessLimit}
   * 知道**被授权的那一个 id**。但"不泄漏"是**性质**不是**机制**：任何一次"顺手把候选 收件人列出来"的改动都会让它失效，而失效时**没有异常**（只是模型多知道了一个
   * id）。故这里逐处扫。
   *
   * <p>★ <b>如实记的口径</b>：A **自己填**的那个 id 会回显在错误文案里（例如 A 发给 C 被拒时文案含 C 的 id）——
   * 那是**回显**不是泄漏，本用例对这处只断言"文案点的是 A 自己填的那个 id"，并把"未被授权者的 id 从不出现在 工具面与正常返回里"单独钉死。
   */
  @Test
  void p6_theRosterNeverLeaksIntoAnyDecisionMakerFacingString() {
    try (Shell shell = startShell(seedStoreWithDecisionMakers())) {
      authorize(shell, dmAId, dmBId);

      // ① 工具面：名字 + 描述 + JSON schema（A 的模型能看到的全部字符串）
      List<String> faceStrings = new ArrayList<>();
      for (AgentTool tool : shell.toolsFor(SimosToolSource.Role.DECISION_AGENT)) {
        faceStrings.add(tool.name());
        faceStrings.add(tool.description());
        faceStrings.add(String.valueOf(tool.jsonSchema()));
      }
      assertThat(faceStrings)
          .as("★★ P6：决策人面的名字/描述/schema 里不得出现未被授权者的 id（I4）")
          .noneMatch(text -> text.contains(dmCId) || text.contains(dmDId));

      // ② 正常返回：发信回执 + 自己的收件箱
      ToolResult sent = sendAs(shell, dmAId, dmBId, "只给 B", "只给 B 的正文");
      assertThat(sent.success()).isTrue();
      assertThat(sent.message())
          .as("P6：发信回执只提收件人（A 自己填的），不提任何别人")
          .contains(dmBId)
          .doesNotContain(dmCId)
          .doesNotContain(dmDId);

      ToolResult inboxA = inboxAs(shell, dmAId);
      assertThat(inboxA.message())
          .as("P6：空收件箱的返回里不得出现任何未被授权者的 id")
          .doesNotContain(dmCId)
          .doesNotContain(dmDId);

      // ③ 未授权拒的文案：点的是 **A 自己填的** 那一个 id（回显，不是名册）
      ToolResult denied = sendAs(shell, dmAId, dmCId, null, "给 C");
      assertThat(denied.success()).isFalse();
      assertThat(denied.message())
          .as("★ P6：拒因回显的是 A 自己填的 id（这是回显），且不提**别的**未被授权者")
          .contains(dmCId)
          .doesNotContain(dmDId);

      // ④ 收信之后：B 的 id 出现在信里是**收发双方自己写入的事实**，不是名册；未被授权者仍不得出现。
      assertThat(sendAs(shell, dmBId, dmAId, null, "回信").success()).isTrue();
      ToolResult inboxAAfter = inboxAs(shell, dmAId);
      assertThat(inboxAAfter.message())
          .as("P6：信里出现的对方 id = 收发事实；未被授权者仍不出现")
          .contains(dmBId)
          .doesNotContain(dmCId)
          .doesNotContain(dmDId);
    }
  }

  // ── P7 + P7b ──────────────────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>P7 可见性</b>：第三方 C **看不到** A↔B 的信（空列表），而 **GM 看得见全部**；同一条用例里再钉 ★★ <b>P7b 零授权的决策人 inbox
   * 可用</b>（空列表，**不是拒**）+ 它的 {@code send} 对谁都具名拒 —— ★ 但**分两组钉**：发给**别人** ⇒ {@code
   * RESOURCE_DENIED}（配置错：够不着 {@code peer:<对方>}），发给**自己** ⇒ {@code BAD_REQUEST}（用法错：{@code to}
   * 该填另一个决策人；理由全文见方法内那段注释与 {@code GaMessageSendTool} 类注）。
   *
   * <p>★ P7b 为什么是必要的判别力：{@code peer} 是新命名空间，决策人的基础可达面本来不含它；若没补 {@code peer:<自己>}，{@code inbox}
   * 会被**自己的资源声明**拒掉 —— 那种坏法在"零授权 DM"身上才暴露 （被授权的 DM 靠 {@code accessLimit.peer} 恰好也有自己那一条时看不出来）。
   *
   * <p>★★ <b>为什么"发给自己"要单独一组</b>：{@code peer:<自己>} 是**人人皆有**的基础可达面 ⇒ 资源断言在 {@code to == 自己} 时
   * 全都过。那不是"未授权够不着"，而是"用法错"。把它留在"对任何 to 都 {@code RESOURCE_DENIED}"的同一条断言里，等于要求实现把 **两种坏法**折成同一个码 ——
   * 而运行流正是靠这个区分告诉模型"改资源"还是"改参数"（{@code DecisionAgentRunner:1029/:1139}）。
   *
   * <p>★ GM 侧的口径如实写：{@code inbox} 是**决策人专属工具**（GM 没有 {@code decision-maker:} 身份），GM 的读法是 **无过滤地读
   * info 覆盖层**（设计书 §4.2「GM 读全部」）；{@code GaResolver} 不认 {@code ga:message.*} 地址 ⇒ 这也是 GUI
   * 之外唯一的全量读法。本用例按那个读法读，见 {@link #messagesInOverlay}.
   */
  @Test
  void p7_thirdPartySeesNothingAndGmSeesEverything_andP7bZeroGrantsStillAllowInbox() {
    try (Shell shell = startShell(seedStoreWithDecisionMakers())) {
      authorize(shell, dmAId, dmBId);
      assertThat(sendAs(shell, dmAId, dmBId, null, "机密").success()).isTrue();
      assertThat(sendAs(shell, dmBId, dmAId, null, "回机密").success()).isTrue();
      // ★ P7b 两条（甲组的拒 + 乙组的自发自收拒）都必须是**世界零变化**（被拒的调用不得烧 revision）。
      long headBeforeP7b = head(shell);

      // ── P7 第三方看不见 ──
      ToolResult inboxC = inboxAs(shell, dmCId);
      assertThat(inboxC.success())
          .as("★ P7b：零授权的 C 的 inbox **可用**（空列表，不是拒）—— 基础可达面里确实有 peer:<自己>")
          .isTrue();
      assertThat(count(inboxC)).as("★ P7：第三方 C 看不到 A↔B 的信").isZero();
      assertThat(inboxC.message())
          .as("P7：C 的返回里不得出现 A/B 的 id")
          .doesNotContain(dmAId)
          .doesNotContain(dmBId);

      // ── P7 GM 看得见全部 ──
      List<SdInfoEntry> overlay = messagesInOverlay(shell);
      assertThat(overlay).as("★ P7：GM 看得见全部两条（无过滤读 info 覆盖层）").hasSize(2);
      assertThat(overlay)
          .as("★ P7：两条的可见性轴 = {from, to}（I5：零新可见性机制），GM 不靠 tags")
          .allSatisfy(
              entry ->
                  assertThat(entry.tags().stream().map(DecisionMakerId::value).toList())
                      .containsExactlyInAnyOrder(dmAId, dmBId));

      // ── P7b（甲组）：零授权 DM 发给**别人** ⇒ 资源级具名拒（"未授权够不着"）──
      //   ★ 这一组逐字不变：C→A / C→B 判的是**配置坏法** —— peer:<对方> 不在 C 的可达面内，
      //     修法是"改资源/改配置"（让 GM 授权），不是"改参数"。
      for (String target : List.of(dmAId, dmBId)) {
        ToolResult denied = sendAs(shell, dmCId, target, null, "C 想说话");
        assertThat(denied.success()).as("★ P7b：零授权的 C 发给 %s 必须拒", target).isFalse();
        assertThat(denied.code())
            .as("★ P7b：而且必须是**资源级具名拒**（不是「这个工具不存在」这类别的坏法）")
            .isEqualTo(ToolCallAuthorizer.RESOURCE_DENIED);
        assertThat(denied.message()).contains("peer:" + target).contains("不在调用者的可达面内");
      }

      // ── P7b（乙组）：零授权 DM 发给**自己** ⇒ 也必须拒 —— ★ 但拒因是**另一种坏法**：BAD_REQUEST ──
      //   ★★ 为什么这两组必须**分开钉**（不是放宽，是把两种坏法分开）：to == 自己 **不是**"够不着某块资源"
      //   —— peer:<自己> 是**人人皆有**的基础可达面（设计书 §4.1 给每个决策人补的自指域，inbox 与"以自己
      //   名义"的断言都要用它）⇒ 上面那两条资源断言在 to == 自己 时**全都过**。放过去就等于给每个决策人
      //   开了一条**免 GM 授权**的写通道（用户原话「只有 GM 授权…才能使用」当场失效），还会每 tick 烧一条
      //   revision（msg-<自己>-<自己>-<tick>），并破 §10.4 的行为契约（自发自收的信 to == 自己 ⇒ 恰好落进
      //   自己 inbox 的那道筛子）。故 send 在资源断言**之前**具名拒，码取 BAD_REQUEST。
      //   ★ 判别力就在这里：RESOURCE_DENIED = **配置错**（改资源：让 GM 再授权一次），BAD_REQUEST = **用法
      //     错**（改参数：to 该填另一个决策人）。两者共用一个码，"改参数"与"改配置"这两种修法就分不开了 ——
      //     既有运行流正是靠这个区分给模型回灌（DecisionAgentRunner:1029 / :1139 逐字）。
      //   ★ 因此乙组**只**断言"必须拒 + 码是 BAD_REQUEST + 文案点出'不能给自己发消息'" ——
      //     ★ 刻意**不**断言它含 peer: 或"不在调用者的可达面内"：那两样是甲组（资源面）的措辞，
      //     按定义在这里**不该**成立（该守卫在资源断言之前 ⇒ 拒因与调用者的可达面无关）。
      ToolResult selfDenied = sendAs(shell, dmCId, dmCId, null, "C 想说话");
      assertThat(selfDenied.success())
          .as("★★ P7b：零授权的 C 发给自己也必须拒（★ 不是放宽：peer:<自己> 是人人皆有的基面）")
          .isFalse();
      assertThat(selfDenied.code())
          .as("★★ P7b：拒因是**用法错**（BAD_REQUEST）而不是资源够不着（RESOURCE_DENIED）—— 修法是改参数 to")
          .isEqualTo("BAD_REQUEST");
      assertThat(selfDenied.message())
          .as("★ P7b：文案必须点出『不能给自己发消息』这类语义（模型据此改参数）")
          .contains("不能给自己发消息");
      assertThat(selfDenied.message())
          .as("★ P7b：用法错的文案里不得混进资源面的措辞（那会误导模型去改配置）")
          .doesNotContain("不在调用者的可达面内");
      assertThat(head(shell)).as("★ P7b：发给自己被拒 ⇒ 世界零变化（零 revision）").isEqualTo(headBeforeP7b);
    }
  }

  // ── P8 ────────────────────────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>P8 旧件彻底没了</b>：{@code simos.sd.report} / {@code simos.sd.reports} <b>不在任何桶、也不在 catalog 的
   * type 表</b>（工具面 −2），且本批之后 <b>零 {@code sd:doc.report} 写入</b>（新消息全落 {@code ga:message.*}）。
   *
   * <p>★ 为什么"零写入"要真读覆盖层而不是只看工具面：工具面删了就没人**能**写，但旧档里可能还有、也可能会有人 用别的方式（例如 {@code
   * simos.command.submit} + {@code ga.PutInfo}）写回那个地址族。本用例在真发过信之后读 覆盖层的**地址键**，逐条确认没有 {@code
   * sd:doc.report} 前缀（I9/I10）。
   */
  @Test
  void p8_theOldReportFamilyIsGoneFromEveryFaceAndWritesNothing() {
    try (Shell shell = startShell(seedStoreWithDecisionMakers())) {
      List<String> gmFace = namesOf(shell.toolsFor(SimosToolSource.Role.GM));
      List<String> dmFace = namesOf(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT));

      for (String old : OLD_REPORT_FAMILY) {
        assertThat(gmFace).as("★★ P8：%s 不得在 GM 桶（工具面 −2）", old).doesNotContain(old);
        assertThat(dmFace).as("★★ P8：%s 不得在决策人桶（工具面 −2）", old).doesNotContain(old);
      }

      // catalog（命令类型面）同样不得再有那两条 —— 它们不是"藏起来了"，是整族退役。
      ToolResult catalog = gmCall(shell, "simos.command.catalog", Map.of());
      assertThat(catalog.success()).as("前提：catalog 读得到").isTrue();
      assertThat(catalog.message())
          .as("★ P8：catalog 的 type 表里不得出现已退役的上报族")
          .doesNotContain("simos.sd.report");

      // 真发一条信，再读覆盖层的**地址键**：不得有 sd:doc.report*，且必须真的有 ga:message.*。
      authorize(shell, dmAId, dmBId);
      assertThat(sendAs(shell, dmAId, dmBId, null, "新族").success()).isTrue();

      List<String> addresses = infoAddresses(shell);
      assertThat(addresses)
          .as("★★ P8：零 sd:doc.report 写入（旧地址族不再被产生 —— I9/I10）")
          .noneMatch(address -> address.startsWith("sd:doc.report"));
      assertThat(addresses)
          .as("★ P8：新消息真的落在 ga:message.* 地址族上")
          .anyMatch(address -> address.startsWith("ga:message."));
      assertThat(addresses)
          .as("★ P8：新消息的地址族里带上收发双方（地址是定位方式，身份是 id）")
          .anyMatch(address -> address.contains(dmAId) && address.contains(dmBId));
    }
  }

  // ── D4（控制方批准的偏离，行为侧钉住） ──────────────────────────────────────────────────────

  /**
   * ★★ <b>D4（控制方 2026-10-14 批准的实现偏离）</b>：{@code allowedTools} **非空**的决策人被授权时， 授权命令**顺带**把两条消息工具并进它的
   * {@code allowedTools} —— 否则 {@code whitelistFor(dm)} = 全局 ∩ {@code allowedTools}
   * 会让它"**授权了却用不了**"（只会拿到 {@code PERMISSION_DENIED 不在 allowed-tools 白名单内}）， 与用户原话「只有 GM
   * 授权…才能使用新的通讯工具」相悖。
   *
   * <p>★ 为什么必须测：这条偏离的**代价**是授权命令现在写两样东西（{@code accessLimit.peer} + {@code allowedTools}），
   * 而且它的失效形态**恰好是"静默不可用"**（授权看起来成功了、决策人却发不出信）。判决书 §10 表 D4 批准的正是 "填名字"这一侧；本用例把"授权之后真的能用"钉成可执行事实。
   *
   * <p>★ 反过来的那一侧（**空** {@code allowedTools} 一个字不碰）由本类其余用例覆盖：A/B/C 的 {@code allowedTools}
   * 都是空集，它们授权后能用（A/B）、零授权仍能读（C）—— 若实现改成"给空集也填名字"， C 的 inbox 会从"可用"变成"白名单里只有两条消息工具"（仍然可用）⇒
   * 那一侧不产生判据差异，故**如实记**在 test-ledger 的未验证项里。
   */
  @Test
  void d4_aDecisionMakerWithANonEmptyAllowedToolsListCanActuallySendAfterBeingAuthorized() {
    try (Shell shell = startShell(seedStoreWithDecisionMakers())) {
      assertThat(decisionMaker(shell, dmDId).allowedTools())
          .as("前提：D 的 allowedTools **非空**（否则本用例退化成 A 的那种情形，测不到 D4）")
          .containsExactly("simos.command.catalog");

      authorize(shell, dmAId, dmDId);

      assertThat(decisionMaker(shell, dmDId).allowedTools())
          .as("★ D4：授权顺带把两条消息工具并进 D 的 allowedTools（否则它授权了也用不了）")
          .contains(SEND, INBOX);
      ToolResult sent = sendAs(shell, dmDId, dmAId, null, "D 也能发");
      assertThat(sent.success())
          .as("★★ D4 的可执行形态：allowedTools 非空的决策人被授权后**真的发得出信**；实得 %s", sent.message())
          .isTrue();
      assertThat(count(inboxAs(shell, dmAId))).as("D4：A 收得到").isEqualTo(1L);
    }
  }

  // ── N5（负向：身份不可伪造） ────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>N5 的靶子（I6：发件人不可伪造）</b>：A 在载荷里**自报** {@code from = B} 想冒充 B ⇒ 要么被拒， 要么**记成
   * A**（两种都合规，用户裁定见设计书 §6.2 N5）。本实现是后者：{@code from} 从 {@code ToolContext.identity()} 派生，载荷里根本没有这个键。
   *
   * <p>★ 断言两处**都要**对：① 收件人在 inbox 里看到的 {@code from} 是 A；② 覆盖层里那条记录的值也是 A ——
   * 只看①会被"视图层改回去了、落盘还是假的"这种实现骗过。
   *
   * <p>★★ <b>为什么还要第二形态（判别力所在，2026-10-14 变异实测逼出来的）</b>：第一形态的载荷把 {@code from} 冒充成**收件人自己**（{@code
   * from == to == B}）—— 而 {@code send} 里那条"不能给自己发消息"的守卫在任何写之前就具名拒了它 （见类注）。 ★ 于是"要么拒、要么记成
   * A"这**两种都合规**的宽容写法配上这个载荷，对"采信载荷里的 {@code from}" 这个变异**零判别力**：实测把 {@code from}
   * 改成从载荷取（有则用载荷、无则回落身份），**全类 11 条全绿**。 ⇒ 本用例补第二形态：{@code A} 冒充 {@code B} 但**发给 A 自己**（{@code to=A,
   * from=B}）—— 冒充者与被冒充者不是同一个人， 于是"拒"与"记成 A"不再重合：诚实实现里 {@code to == 调用者} ⇒ 仍被那道守卫拒；变异实现里 {@code
   * sender=B, to=A} ⇒ 两条资源断言（{@code peer:<B>} 是 A 被授权的那一个、{@code peer:<A>} 是自指域）**全过** ⇒ 它会真的落一条"来自
   * B"的假信。★ 这一条不是放宽，恰恰是把 N5 的判别力补上。
   */
  @Test
  void n5_theSenderCannotBeForgedThroughThePayload() {
    try (Shell shell = startShell(seedStoreWithDecisionMakers())) {
      authorize(shell, dmAId, dmBId);

      Map<String, Object> forged = new LinkedHashMap<>();
      forged.put("to", dmBId);
      forged.put("body", "我是 B（自称）");
      // ★ 载荷里塞满"想冒充"的键：from 是工具**根本不读**的那个（schema 里没有它）。
      forged.put("from", dmBId);
      forged.put("sender", dmBId);
      forged.put("identity", dmBId);

      ToolResult result = dmCall(shell, dmAId, SEND, forged);

      if (result.success()) {
        // 形态②：收下了，但必须**记成 A**。
        ToolResult inboxB = inboxAs(shell, dmBId);
        assertThat(inboxB.message())
            .as("★★ N5：冒充信在 B 眼里必须来自 **A**（from 从身份派生，不采信载荷）")
            .contains(dmAId)
            .doesNotContain("\"from\":\"" + dmBId + "\"");
        assertThat(messagesInOverlay(shell))
            .as("★★ N5：落盘的 value.from 也必须是 A（只改视图层不算）")
            .singleElement()
            .satisfies(
                entry ->
                    assertThat(String.valueOf(entry.value()))
                        .as("N5：条目值里的 from 必须是调用者自己的 id")
                        .contains("\"from\":\"" + dmAId + "\"")
                        .doesNotContain("\"from\":\"" + dmBId + "\""));
      } else {
        // 形态①：直接拒 —— 同样合规（两个形态都不许"记成 B"）。
        assertThat(result.message()).as("N5：若拒，拒因必须可读").isNotBlank();
      }

      // ── ★★ N5 第二形态：A 冒充 B，但**发给 A 自己**（`to=A, from=B`）—— 判别力所在，理由见方法 javadoc ──
      Map<String, Object> forgedToSelf = new LinkedHashMap<>();
      forgedToSelf.put("to", dmAId);
      forgedToSelf.put("body", "我冒充 B 写给我自己");
      forgedToSelf.put("from", dmBId);
      ToolResult forgedSelf = dmCall(shell, dmAId, SEND, forgedToSelf);
      if (forgedSelf.success()) {
        // 收下了 ⇒ 同第一形态：**必须记成 A**（变异实现会在这里落一条 from=B 的假信）。
        assertThat(messagesInOverlay(shell))
            .as("★★ N5（第二形态）：若收下了，落盘的 from 必须是**调用者 A**（采信载荷 ⇒ 这里变成 B）")
            .noneMatch(
                entry -> String.valueOf(entry.value()).contains("\"from\":\"" + dmBId + "\""));
        assertThat(inboxAs(shell, dmAId).message())
            .as("★★ N5（第二形态）：A 自己的 inbox 里不得冒出「来自 B」的信（那封信 B 从没发过）")
            .doesNotContain("\"from\":\"" + dmBId + "\"");
      } else {
        assertThat(forgedSelf.message()).as("N5（第二形态）：若拒，拒因必须可读").isNotBlank();
      }

      assertThat(messagesInOverlay(shell))
          .as("★★ N5 的不变式（第一、第二两种形态都成立）：世界里**没有任何**一条消息自称来自 B" + "（诚实实现里 B 只能出现在收件人位置）")
          .noneMatch(entry -> String.valueOf(entry.value()).contains("\"from\":\"" + dmBId + "\""));
    }
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  /** 两段式夹具：先建世界与 4 个决策人并关壳，让第二次 {@code Shell.start} 能在装配期读到决策人清单。 */
  private Path seedStoreWithDecisionMakers() {
    Path store = tempDir.resolve("dmmsg-" + System.nanoTime());
    try (Shell shell = startShell(store)) {
      shell.coreSimos().bootstrapGenesis(SmallWorld.state(mapId()));
      createDecisionMaker(shell, dmAId, "[]");
      createDecisionMaker(shell, dmBId, "[]");
      // ★ C 零授权（P7/P7b）；D 的 allowedTools **非空**（D4 的靶子）。
      createDecisionMaker(shell, dmCId, "[]");
      createDecisionMaker(shell, dmDId, "[\"simos.command.catalog\"]");
    }
    return store;
  }

  /** 经真 GM 工具建一个决策人（与生产同一条命令路径，不直写状态）。 */
  private static void createDecisionMaker(Shell shell, String id, String allowedToolsJson) {
    ToolResult result =
        gmCall(
            shell,
            "ga.CreateDecisionMaker",
            Map.of(
                "payloadJson",
                "{\"id\":\"" + id + "\",\"allowedTools\":" + allowedToolsJson + ",\"cadence\":1}"));
    assertThat(result.success()).as("夹具：建决策人 %s 必须成功；实得 %s", id, result.message()).isTrue();
  }

  /** GM 授权 / 关闸（一次调用 = 双向）。 */
  private static void authorize(Shell shell, String a, String b) {
    assertThat(grant(shell, a, b, true).success()).as("夹具：授权 %s ↔ %s 必须成功", a, b).isTrue();
  }

  private static ToolResult grant(Shell shell, String a, String b, boolean allowed) {
    return gmCall(
        shell,
        PEER,
        Map.of(
            "payloadJson",
            "{\"dmA\":\"" + a + "\",\"dmB\":\"" + b + "\",\"allowed\":" + allowed + "}"));
  }

  private ToolResult sendAs(Shell shell, String from, String to, String subject, String body) {
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("to", to);
    if (subject != null) {
      args.put("subject", subject);
    }
    args.put("body", body);
    return dmCall(shell, from, SEND, args);
  }

  private ToolResult inboxAs(Shell shell, String dmId) {
    return dmCall(shell, dmId, INBOX, Map.of());
  }

  /** 以某个决策人的身份经**唯一入口**（{@code ToolCallAuthorizer}）调一条决策人面工具。 */
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

  /** GM 面命令调用（自动放行，身份 = 真 GM）。 */
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

  /** 推进一 tick（GM 的通用写；走真审批链的**自动放行**那一侧）。 */
  private static void advanceOneTick(Shell shell) {
    long from = tick(shell);
    ToolResult advanced = gmCall(shell, "simos.advance", Map.of("from", from, "to", from + 1L));
    assertThat(advanced.success()).as("夹具：推进一 tick 必须成功；实得 %s", advanced.message()).isTrue();
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
            "agent:dm-messaging-test",
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

  /** 某个决策人身上**被 GM 授权的对端**（{@code accessLimit.peer} 的前缀集）。 */
  private static Set<String> peersOf(Shell shell, String id) {
    return decisionMaker(shell, id)
        .accessLimit()
        .prefixesByNamespace()
        .getOrDefault("peer", Set.of());
  }

  /**
   * ★ <b>GM 的读法</b>（设计书 §4.2「GM 读全部」）：{@code inbox} 是决策人专属工具（GM 没有 {@code decision-maker:} 身份），而
   * {@code GaResolver} 不认 {@code ga:message.*} 地址 ⇒ GM 侧唯一的全量读法 就是**无过滤地读 {@code ga.info()}
   * 覆盖层**（GUI 的 info 面同理）。这里按那个读法读，键 = {@code "message"}。
   */
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

  /** 覆盖层的**地址键**（P8 用它证"零 sd:doc.report 写入"）。 */
  private static List<String> infoAddresses(Shell shell) {
    return List.copyOf(gaState(shell).info().keySet());
  }

  /** 返回体里的 {@code count}（inbox 的最小形状之一）。 */
  private static long count(ToolResult inbox) {
    try {
      return SimosObjectMapper.create().readTree(inbox.message()).path("count").asLong();
    } catch (JsonProcessingException e) {
      throw new AssertionError("inbox 的返回必须是 JSON: " + inbox.message(), e);
    }
  }

  private static List<String> namesOf(List<AgentTool> tools) {
    return tools.stream().map(AgentTool::name).toList();
  }

  /**
   * 决策人面工厂：action 走 {@code gmToolAuthorizer()}（自动放行；**身份与权限仍是 DM 的**，由 {@code callerFor} 现算）。★ 不用
   * {@code shell.toolAuthorizer()}：那条链要人点审批。
   */
  private static DecisionCallerFactory decisionFactory(Shell shell) {
    return DecisionCallerFactory.forDecisionFace(
        shell.gmToolAuthorizer(),
        ToolBuckets.namesOf(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT)));
  }

  /** 真决策人桶的注册表（与生产同源）。 */
  private static ToolRegistry decisionRegistry(Shell shell) {
    ToolRegistry registry = new ToolRegistry();
    registry.registerAll(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT));
    return registry;
  }
}
