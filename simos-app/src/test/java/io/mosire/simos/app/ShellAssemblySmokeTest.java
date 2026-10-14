package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.simos.app.tools.SimosToolSource;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.ga.tools.ToolRoleAudit;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>真 {@link Shell} 装配冒烟</b>（规划 §29.2 的落地）：把「本批门禁里至少有一条<b>会真的构造出组合根</b>的检查」变成 <b>仓内、常规 {@code
 * test} 阶段、永不 skip</b> 的回归护栏。
 *
 * <p>★ <b>为什么必须是这一条</b>（本仓刚付过的学费）：架构调整轮出过两个<b>真生产缺陷</b>，四道静态门禁 （{@code compile}/{@code
 * spotless}/{@code spotbugs}/{@code validate}）一道都没拦住 ——
 *
 * <ol>
 *   <li>{@code SimosSdReportsTool.roles()} 被写成 {@code {GM}}，而它注册在 DM 桶 ⇒ 组合根装配当场抛 （{@code
 *       Shell.start(...)} 里那两个 {@code SimosToolSource} 的构造期）⇒ <b>从 {@code 3ee4aea5} 起 5 个提交里 GUI
 *       根本起不来</b>（{@code simos-app} 基线 445 红，其中 378 同此一因）； ★ <b>该工具已于 2026-10-14
 *       随旧上报族整族删除</b>（决策人互致消息批），故下面的变异自证改钉**当前**的边界锚点 （见 {@link #PEER} / {@link
 *       #MESSAGE_SEND}）——历史那一例只作留痕，不再是可执行的变异；
 *   <li>{@code api.js:535} 引用已删除的函数 ⇒ {@code window.SimosaApi} 整个不发布（前端面，本用例不管）。
 * </ol>
 *
 * <p>★ <b>判据不是"编译过"，是"装配期自审真的跑过且没抛"</b>：这道防线在装配链上有两个调用点，本用例都会走到 —— ① {@code SimosToolSource:568}
 * 的<b>单桶</b> {@link ToolRoleAudit#requireFace}（建桶时：桶里每条工具都必须声明该桶角色）；② {@code Shell:1194} 的<b>双桶</b>
 * {@link ToolRoleAudit#requireFaces}（装配完成处：工具名在 GM 桶 ⇔ {@code roles()} 含 {@code GM}；在决策人桶 ⇔ 含
 * {@code DM}，双向全量，不一致<b>当场抛</b>）。 本用例<b>真的调 {@link Shell#start(ShellConfig)}</b>（不是 {@code new
 * Shell(...)} 的一半装配），所以两道 check 必然被走到；能执行到 {@code start()} 之后的第一行断言，就是"它们跑过了、没抛"的证据。 ★
 * <b>当前</b>这条因果由本批的变异自证钉死：把 {@code GaMessageSendTool.roles()} 改宽成 {@code Set.of(ToolRole.GM,
 * ToolRole.DM)}（而它只注册在 DM 桶）⇒ {@code Shell.start()} 里的双桶自审当场抛、本用例红。 ★ 变异命令、红在哪条断言、还原后的 md5 逐条记在本批
 * {@code test-ledger.md}（本机留痕，不在版本库里）。
 *
 * <p>★ <b>为什么断言这么窄</b>：本文件<b>不是</b>功能验收用例（那是 {@code ShellSmokeTest}/{@code *EndToEndTest}
 * 的事），它只钉装配面的三件事 —— ① 组合根真的构造得出来；② 两桶与 {@code roles()} 声明逐条一致； ③ 几条<b>不许为了修绿而放宽</b>的桶归属锚点（见 {@link
 * #GM_ONLY_READS} 与两个条数常量）。
 *
 * <p>★ <b>为什么不建世界、不跑 tick、不走 HTTP 业务面</b>：本用例只做 {@code start → toolsFor(两桶) → close} （实测约 1 秒）。端口全
 * {@code 0} ⇒ OS 随机分配，<b>绝不占 5711/5715/5713</b>；store 用 JUnit 临时目录 （{@code @TempDir}
 * 在用例结束后递归清理，<b>不往仓里写一个字节</b>），并在 {@code close()} 之后显式删掉。
 *
 * <p>★ <b>工作目录说明</b>：surefire 的 CWD = {@code simos-app}（不是仓根），所以仓根相对的 {@code
 * config/worldgen/*.json} / {@code config/llm-providers.json}
 * 在本用例里都是"不存在"。这<b>不影响</b>本用例：那两处都是<b>惰性读</b>（worldgen 由 {@code simos.worldgen.initialize} 调用时才
 * fail-closed）或<b>容缺</b>（llm 仓库默认配置只是兜底种子，"两者都没有则什么都不迁"），装配期没有一处依赖它们的内容。 ★ 2026-10-13 取缔 Skill 系统后
 * {@code config/skills} 已被删除，本段的例子随之收缩为上面两处 （容缺判据本身不变：装配期本就不依赖任何种子目录的内容）。
 */
class ShellAssemblySmokeTest {

  /**
   * ★★ <b>2026-10-14 决策人互致消息：正向锚点整组换掉</b> —— 旧锚点 {@code simos.sd.reports}（"装配面里唯一一条两桶都在的 sd
   * 读口"）**已随旧上报族整族删除**，而本批三条新工具**没有一条是两桶共享**：
   *
   * <ul>
   *   <li>{@code ga.SetDecisionMakerPeer} —— <b>GM-only</b>（配权是 GM 的活，设计书 §4.4 类1）；
   *   <li>{@code simos.ga.message.send} / {@code simos.ga.message.inbox} ——
   *       <b>DM-only</b>（决策人互致消息，设计书 §4.2/§4.3，{@code roles()={DM}}）。
   * </ul>
   *
   * ⇒ 正向锚点从"一条工具在两桶"改成<b>"三条工具各自的桶归属（GM-only ⇔ DM-only 的极性）"</b>：它同样挡"为了修绿把 {@code roles()}
   * 或挂桶改宽"，而且是本批**边界本身**的可执行形态（{@code send} 进 GM 桶、或 {@code peer} 进 DM 桶 ⇒ 当场红）。 ★
   * 写成字面量（不引用生产常量）：本文件是**独立规格**，改名要有人显式改这里。
   */
  private static final String PEER = "ga.SetDecisionMakerPeer";

  private static final String MESSAGE_SEND = "simos.ga.message.send";

  private static final String MESSAGE_INBOX = "simos.ga.message.inbox";

  /**
   * ★ <b>GM-only 读口（7 条）</b>：它们是「为了修绿而放宽装配」的锚点 —— 谁把其中任何一条挪进决策人桶 （或从 GM 桶摘掉），本用例当场红，而不是静默变成权限面变化。
   *
   * <p>★ 2026-10-13 急停协议 +1：{@code simos.army.stopReport}（急停原因系统）里带 {@code payload.hex} —— 那是
   * <b>视野之外</b>的军队位置（中央视角信息），按 D-002「中央不设全视野」**只能**进 GM 桶。
   */
  private static final List<String> GM_ONLY_READS =
      List.of(
          "simos.map.path",
          "simos.map.block",
          "simos.map.overlaps",
          "simos.sd.decision-makers",
          "simos.sd.directives",
          "simos.economy.ownership",
          "simos.army.stopReport");

  /**
   * ★ <b>两桶条数锚点</b>（2026-10-13 实测值：GM 桶 160 / 决策人桶 42）。工具面增删一条都会在这里红 ——
   * 那时应当<b>显式</b>改本行并说明为什么，而不是把断言删掉/放宽。
   *
   * <p>★★ <b>本次显式改动（2026-10-13 取缔 Skill 系统）</b>：按本类上面的纪律，这里逐字写明为什么改 —— 原值 161 / 43 是 2026-10-13
   * 早先的实测值；本轮用户裁定「现有项目的 Skill 等直接取缔」，运行时 Skill 机制整条删除， 工具面**恰好减去 Skill 读工具一条**，而被删那条工具声明 `roles() =
   * {DM, GM}` ⇒ **两个桶各减一条** ⇒ 161 → 160、43 → 42。 ★ 减的**不是**任意一条：`SimosToolsTest` 的 `hasSize(160)` /
   * `hasSize(42)` 与 `McpServerTest` / `McpPortTopologyTest` / `DecisionFaceGolden` 的名单同轮精确同步（设计书
   * §4.1 / §6.2），四处一致才算绿。
   *
   * <p>★★ <b>2026-10-13 急停协议再改一次</b>（同样逐字写明为什么）：新增 3 条工具且 **roles() 只有 GM** （{@code
   * simos.army.stopReport} 读 / {@code simos.army.contactWatch} 窄写 / {@code simos.emergency.advance}
   * 通用写）⇒ **GM 桶 160 → 163**；决策人桶一条都不进 ⇒ **42 不动**。★ 与 `SimosToolsTest`、`McpServerTest`、
   * `McpPortTopologyTest` 同轮精确同步（`DecisionFaceGolden` 因"三条都不进决策人面"而**有意不动**）。
   *
   * <p>★★ <b>2026-10-14 AgentLib Docs/Workspace 接入</b>：GM 面 +10（7 条 {@code doc_*} + 3 条工作区）⇒ <b>163
   * → 173</b>；决策人面 +6（{@code doc_list/read/query/append/create/edit}）⇒ <b>42 → 48</b>。 ★
   * 本轮**不再是"决策人面不动"**：这是头一次往决策人面加工具，故两个锚点同时改。实测来源：真壳两桶计数。
   *
   * <p>★★ <b>2026-10-14 上游 R8（控制方裁定 §11.3）：两个锚点各 +2 ⇒ GM 173 → 175、决策人 48 → 50</b> —— {@code
   * doc_guide} / {@code workspace_info} 两条"能力自述"工具都是纯读（{@code ResourceManifest.NONE} + {@code
   * spec(DEFAULT,false,false)}）⇒ {@code {GM, DM}} + {@code gate=ALLOW}（{@code DM_FREE}）。 ★
   * 实测来源：本条改前真跑，红在「Expected size: 173 but was: 175」（GM 桶）。
   *
   * <p>★★ <b>2026-10-14 决策人互致消息：两个锚点都**不动**（各是净 0，但成员换了）</b> —— GM 面 −{@code
   * simos.sd.reports}（共享读）+{@code ga.SetDecisionMakerPeer}（GM 窄写）⇒ 仍 <b>173</b>；决策人面 −{@code
   * simos.sd.report} −{@code simos.sd.reports} +{@code simos.ga.message.send} +{@code
   * simos.ga.message.inbox} ⇒ 仍 <b>48</b>。★ 正因为"条数不变"，换名字这件事**只能**由上面的极性锚点 （{@link #PEER} / {@link
   * #MESSAGE_SEND} / {@link #MESSAGE_INBOX}）与 `SimosToolsTest` 的 DM 桶 `contains`/`doesNotContain`
   * 钉住 —— 只数条数会漏掉这种成员替换。
   *
   * <p>★★ <b>2026-10-14 GM⇄DM 信件 + 推演确认批（A）+ 决策自由化（B）：GM 175 → 178、决策人 50 → 54</b> —— GM 面
   * +3（{@code simos.gm.inbox} 读 / {@code simos.gm.message.send} 写 / {@code simos.gm.round} 读，三条
   * {@code roles()} 只有 GM）；决策人面 +4（决策族 4 → 8 条：改名 3 条不计增，净新增 revise / drop / reopen 三条 + {@code
   * simos.ga.adjudication.ack} 一条）。 ★ 实测来源：本条真跑，红在「GM 桶条数锚点 … Expected size: 175 but was:
   * 178」与「决策人桶条数锚点 … Expected size: 50 but was: 54」（★ 设计书 §7 预测的是 DM 50→51，实测 54 ⇒ 以实测为准）。
   */
  private static final int GM_BUCKET_SIZE = 178;

  private static final int DECISION_BUCKET_SIZE = 54;

  /** 八个 codec（map / social / unit / economy / actor / ga / gov / army）。 */
  private static final int MODULE_COUNT = 8;

  @TempDir Path tempDir;

  @Test
  void realShellAssemblesAuditsBothToolFacesAndClosesCleanly() throws IOException {
    Path store = Files.createDirectories(tempDir.resolve("store"));
    // ★ 端口全 0 ⇒ OS 随机分配（guarded：绝不占 5711/5715/5713 这三个本仓服务端口）。
    ShellConfig config = ShellConfig.defaults(store).withPorts(0, 0, 0);

    try (Shell shell = Shell.start(config)) {
      // ★★ 判据：能执行到这一行 = 上一行 start() 没有抛 = 装配完成处的 ToolRoleAudit.requireFaces
      //   （Shell:1194）**真的跑过了**。这是本用例的核心，下面各条都是它的窄化锚点。
      assertThat(shell.registeredModuleCount())
          .as("八个 codec 全部注册（map/social/unit/economy/actor/ga/gov/army）")
          .isEqualTo(MODULE_COUNT);
      assertThat(shell.boundGuiPort()).as("GUI 口已绑上（port=0 ⇒ OS 分配，>0）").isPositive();
      assertThat(shell.boundMcpPort()).as("MCP 口已绑上（port=0 ⇒ OS 分配，>0）").isPositive();

      List<AgentTool> gm = shell.toolsFor(SimosToolSource.Role.GM);
      List<AgentTool> decision = shell.toolsFor(SimosToolSource.Role.DECISION_AGENT);
      Set<String> gmNames = namesOf(gm);
      Set<String> decisionNames = namesOf(decision);

      // ★ 对**导出的**两桶再走一遍同一道校验：让「两桶 ⇔ roles() 声明」这条不变量在本文件里被具名断言，
      //   而不是只藏在 Shell 内部的一行调用里。★ 它**不能**替代 Shell 自己那次调用 ——「Shell.start 里真的调了」
      //   由上一行的 start() 跑通承担，并由变异自证钉死（roles() 改坏 ⇒ start() 当场抛，见本批报告）。
      assertThatCode(() -> ToolRoleAudit.requireFaces(gm, decision))
          .as("两桶 ⇔ SimosTool.roles() 声明必须逐条一致（设计书 §4.4 / I-G3），不一致当场抛")
          .doesNotThrowAnyException();

      // ① 正向：本批三条新工具的桶归属 —— 授权命令是 GM 的活（GM-only），两条消息工具是决策人的活（DM-only）。
      //    ★ 这就是"桶归属 ⇔ roles() 声明"那道装配自审在**本批边界上**的可执行形态：
      //      谁把 message.send 挪进 GM 桶（或把 peer 挪进 DM 桶）⇒ 这里当场红，而不是静默变成权限面变化。
      assertThat(gmNames).as("%s 必须在 GM 桶（GM 配权）", PEER).contains(PEER);
      assertThat(decisionNames)
          .as("反证：%s 是 GM-only 配权命令，**不许**进决策人桶（决策人不得给自己开闸）", PEER)
          .doesNotContain(PEER);
      assertThat(decisionNames)
          .as("%s / %s 必须在决策人桶（决策人互致消息）", MESSAGE_SEND, MESSAGE_INBOX)
          .contains(MESSAGE_SEND, MESSAGE_INBOX);
      assertThat(gmNames)
          .as(
              "反证：%s / %s 是决策人面工具，**不许**进 GM 桶（GM 不是决策人、没有 decision-maker: 身份）",
              MESSAGE_SEND, MESSAGE_INBOX)
          .doesNotContain(MESSAGE_SEND, MESSAGE_INBOX);

      // ② 反证：6 条 GM-only 读口必须 GM=true 且 DM=false（防"为了修绿而放宽"）。
      for (String name : GM_ONLY_READS) {
        assertThat(gmNames).as("反证：%s 是 GM-only 读口，必须在 GM 桶", name).contains(name);
        assertThat(decisionNames).as("反证：%s 是 GM-only 读口，**不许**进决策人桶", name).doesNotContain(name);
      }

      // ③ 条数锚点：任何工具增删都会在这里红。
      assertThat(gm).as("GM 桶条数锚点（增删工具须显式改本行）").hasSize(GM_BUCKET_SIZE);
      assertThat(decision).as("决策人桶条数锚点（增删工具须显式改本行）").hasSize(DECISION_BUCKET_SIZE);
      assertThat(gmNames).as("GM 桶无重名工具").hasSize(gm.size());
      assertThat(decisionNames).as("决策人桶无重名工具").hasSize(decision.size());
    }

    // ④ close() 真的收尾：库文件在盘上（端口随 close 释放，由 Shell.close 的次序保证）。
    Path dbFile = store.resolve(CoreSimos.DB_FILE_NAME);
    assertThat(dbFile).as("close() 之后库文件仍在磁盘上").exists();

    // ⑤ 清理：store 是临时目录，用完即删（不往仓里留任何东西）。
    deleteRecursively(store);
    assertThat(store).as("store 临时目录已清理").doesNotExist();
  }

  /** 两桶的工具名（{@link LinkedHashSet} ⇒ 可读且能查重）。 */
  private static Set<String> namesOf(List<AgentTool> tools) {
    Set<String> names = new LinkedHashSet<>();
    for (AgentTool tool : tools) {
      names.add(tool.name());
    }
    return names;
  }

  /** 递归删除（深度优先；临时 store 的清理用，见判据⑤）。 */
  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }
}
