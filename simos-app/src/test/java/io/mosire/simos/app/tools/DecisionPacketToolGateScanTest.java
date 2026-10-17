package io.mosire.simos.app.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.ga.tools.ToolBuckets;
import io.mosire.simos.ga.tools.read.DecisionDocsTool;
import io.mosire.simos.ga.tools.read.DecisionResultsTool;
import io.mosire.simos.ga.tools.read.GaMessageInboxTool;
import io.mosire.simos.ga.tools.read.GmPacketTool;
import io.mosire.simos.ga.tools.read.GmPacketsTool;
import io.mosire.simos.ga.tools.read.GmRoundTool;
import io.mosire.simos.ga.tools.write.AdjudicationAckTool;
import io.mosire.simos.ga.tools.write.ConfirmPacketTool;
import io.mosire.simos.ga.tools.write.DropCallTool;
import io.mosire.simos.ga.tools.write.GaMessageSendTool;
import io.mosire.simos.ga.tools.write.MyPacketTool;
import io.mosire.simos.ga.tools.write.PacketIntentTool;
import io.mosire.simos.ga.tools.write.ProposeCallTool;
import io.mosire.simos.ga.tools.write.ReopenPacketTool;
import io.mosire.simos.ga.tools.write.ReviseCallTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>§6.1 N10（I2 的机械判据）：决策人面的工具源码扫描 —— {@code gate()} 出现 {@code Ask} ⇒ 当场红； {@code toolRoles()}
 * 不是 {@code {DM}} ⇒ 当场红</b>。
 *
 * <p>★ <b>为什么必须是「扫源码」而不是「跑一遍看行为」</b>（设计书 §6.1 N10 的原话："源码扫描当场红"）：行为用例只能证明 "这一条路径上没走到 Ask"；而 {@code
 * gate()} 是一段**声明**，它的坏法恰恰是"在某个分支下返回 As k" ——那个分支可能只在无人值守/未绑定 provider 时走到（{@code
 * AskKind.SENSITIVE} 的类注：「**永远到人**」， 无人值守时每调一次阻塞上限 5 分钟后 {@code APPROVAL_DENIED}）。⇒
 * 只有把**源码文本**当判据，才能在装配期就把它钉死。
 *
 * <p>★★ <b>它守的是什么失效形态（G8 的原话）</b>：本批之前 {@code propose} / {@code packet.intent} / {@code
 * packet.submit} 是 {@code Ask + SENSITIVE} ⇒ 决策-提交流程在无人值守时**根本走不完**。用户同日裁定"决策-提交流程属 DM 无条件执行" ⇒
 * 这一族工具的 {@code gate()} 只能有 {@code ALLOW}。★ 这条扫描器就是那条裁定的可执行形态。
 *
 * <p>★ <b>形制照 {@code SimosToolsTest} 的既有扫描器</b>（{@code
 * everyNarrowWriteToolClassIsWiredIntoTheGmBucket} / {@code
 * everyToolClassOnDiskIsRegisteredInSomeBucket}）：按**相对模块目录**定位源码（surefire 的工作目录是 {@code
 * simos-app/}）⇒ 主树与 worktree 里都成立；★ 并**先断言扫描面非空**（"空 == 空"恒真是本仓踩过的陷阱）。
 */
class DecisionPacketToolGateScanTest {

  @TempDir Path tempDir;

  /** 决策包七件套 + 推演确认：**必须** {@code gate()=ALLOW}、{@code toolRoles()={DM}}（设计书 §5 类3 DM_FREE）。 */
  private static final List<String> DM_FREE_TOOLS =
      List.of(
          ProposeCallTool.NAME,
          ReviseCallTool.NAME,
          DropCallTool.NAME,
          PacketIntentTool.NAME,
          MyPacketTool.NAME,
          ConfirmPacketTool.NAME,
          ReopenPacketTool.NAME,
          AdjudicationAckTool.NAME,
          // ★ 2026-10-14 批 A：DM→GM 发信也是类3（写的是自己的疑问，产物要经 GM 裁决才生效）
          GaMessageSendTool.NAME,
          GaMessageInboxTool.NAME);

  /**
   * 决策人面的**读**工具（类3 的另一半：{@code ALLOW} + {@code {DM}}）。★ 它们不在"七件套"里，但 I2 的判据 （"DM 面工具里没有一个 {@code
   * gate().Ask}"）覆盖它们，故一并扫。
   */
  private static final List<String> DM_READ_TOOLS =
      List.of(DecisionResultsTool.NAME, DecisionDocsTool.NAME);

  /** 这十条的实现类（相对 {@code simos-ga/src/main/java/...} 的文件名）—— 扫描面由**显式清单**给出，不靠目录推断。 */
  private static final Map<String, String> TOOL_CLASSES = new LinkedHashMap<>();

  static {
    TOOL_CLASSES.put(ProposeCallTool.NAME, "write/ProposeCallTool.java");
    TOOL_CLASSES.put(ReviseCallTool.NAME, "write/ReviseCallTool.java");
    TOOL_CLASSES.put(DropCallTool.NAME, "write/DropCallTool.java");
    TOOL_CLASSES.put(PacketIntentTool.NAME, "write/PacketIntentTool.java");
    TOOL_CLASSES.put(MyPacketTool.NAME, "write/MyPacketTool.java");
    TOOL_CLASSES.put(ConfirmPacketTool.NAME, "write/ConfirmPacketTool.java");
    TOOL_CLASSES.put(ReopenPacketTool.NAME, "write/ReopenPacketTool.java");
    TOOL_CLASSES.put(AdjudicationAckTool.NAME, "write/AdjudicationAckTool.java");
    TOOL_CLASSES.put(GaMessageSendTool.NAME, "write/GaMessageSendTool.java");
    TOOL_CLASSES.put(GaMessageInboxTool.NAME, "read/GaMessageInboxTool.java");
    TOOL_CLASSES.put(DecisionResultsTool.NAME, "read/DecisionResultsTool.java");
    TOOL_CLASSES.put(DecisionDocsTool.NAME, "read/DecisionDocsTool.java");
  }

  /**
   * ★★ <b>N10 主体：逐文件的 {@code gate()} / {@code toolRoles()} 源码扫描</b>。
   *
   * <p>三条判据，每条都在**源码文本**上：
   *
   * <ol>
   *   <li>{@code gate()} 方法体里不得出现 {@code Ask}（{@code ToolGate.Ask} / {@code AskKind}）⇒ 类3 只能是
   *       ALLOW；
   *   <li>{@code gate()} 方法体里必须有 {@code ToolGate.ALLOW}（★ 防"把 gate() 整个删掉" ⇒ 删掉后默认是什么？ 默认不是 ALLOW
   *       而是走审批链的缺省 ⇒ 必须显式写出来）；
   *   <li>{@code toolRoles()} 方法体里必须是 {@code Set.of(ToolRole.DM)}（**恰好一条** DM）—— 出现 {@code
   *       ToolRole.GM} 或出现第二个角色 ⇒ 当场红。
   * </ol>
   */
  @Test
  void everyDecisionFaceToolDeclaresAllowAndDmOnlyInSource() throws IOException {
    Path gaMain = gaMainJava();
    System.out.println(
        "[SCAN] cwd="
            + Paths.get(".").toAbsolutePath()
            + " root="
            + gaMain.toAbsolutePath()
            + " isDir="
            + Files.isDirectory(gaMain));
    assertThat(Files.isDirectory(gaMain))
        .as("★ 扫描根必须存在（否则下面的'扫到 0 条'会伪装成通过）：%s", gaMain.toAbsolutePath())
        .isTrue();

    int scanned = 0;
    int explicitGateCount = 0;
    for (Map.Entry<String, String> entry : TOOL_CLASSES.entrySet()) {
      String toolName = entry.getKey();
      Path file = gaMain.resolve(entry.getValue());
      assertThat(Files.isRegularFile(file))
          .as("★ 扫描面必须命中（%s 的源码不在 %s ⇒ 扫描器静默 = 判据失效）", toolName, entry.getValue())
          .isTrue();
      String source = Files.readString(file);
      assertThat(source)
          .as("★ 命名判据：%s 的源码里必须逐字写着它自己的 NAME（改错文件时当场红）", toolName)
          .contains("\"" + toolName + "\"");

      // ★★ N10 第 1 条：gate() **里**不得出现 Ask。★ 分「显式覆写」与「走接口缺省」两种合法形态：
      //   · 显式覆写（写工具，如 propose/confirm/...）⇒ 必须逐字写着 ToolGate.ALLOW；
      //   · 完全没有 gate()（纯读工具，如 MyPacketTool / DecisionResultsTool / DecisionDocsTool /
      //     GaMessageInboxTool）⇒ 走 `AgentTool.gate()` 的**接口缺省**，而那一条实测就是 `ToolGate.ALLOW`
      //     （`javap -c io.mosire.agentlib.tool.AgentTool` ⇒ `getstatic ToolGate.ALLOW; areturn`）。
      //   ★ 两种形态都满足"没有 Ask"；本条**不**要求每个类都显式写一遍 —— 那会变成第二处真相
      //     （SimosTool 的类注原话：「审批链按**装配**决定…把它写进接口 = 第二处真相」）。
      //   ★★ 变异靶子：把任一显式 gate() 的 `ToolGate.ALLOW` 改成 `new ToolGate.Ask(...)` ⇒ 本循环当场红。
      String gateBody = optionalMethodBody(source, "gate");
      if (gateBody != null) {
        assertThat(gateBody)
            .as("★★ N10（I2）：%s 的 gate() 里出现了 Ask —— 决策-提交流程属 DM 无条件执行（用户裁定），只能是 ALLOW", toolName)
            .doesNotContain("Ask");
        assertThat(gateBody)
            .as("★ N10：%s 显式覆写了 gate() ⇒ 必须逐字返回 ToolGate.ALLOW", toolName)
            .contains("ToolGate.ALLOW");
        explicitGateCount++;
      }

      String rolesBody = methodBody(source, "roles");
      assertThat(rolesBody)
          .as("★★ N10：%s 的 toolRoles() 必须是 {DM}（进 GM 桶 = 决策人面被放大）", toolName)
          .contains("ToolRole.DM")
          .doesNotContain("ToolRole.GM")
          .doesNotContain("ToolRole.SYSTEM");
      assertThat(countOccurrences(rolesBody, "ToolRole."))
          .as("★★ N10：%s 的 toolRoles() 必须**恰好一条**角色（多一条 = 可达面被静默放大）", toolName)
          .isEqualTo(1);

      scanned++;
    }
    assertThat(scanned)
        .as("★ 扫描器自证：必须真的扫到全部 %d 条（扫到 0/漏文件 = 「空 == 空」陷阱）", TOOL_CLASSES.size())
        .isEqualTo(TOOL_CLASSES.size());
    // ★ 扫描器自证之二：**显式 gate() 的那一批**也要有预期条数——否则"gate() 全被删掉"会让本扫描器静默变成空转
    //   （接口缺省仍是 ALLOW ⇒ 行为不变、扫描器也全绿 ⇒ 判据归零）。写死实测值，删一个当场红。
    assertThat(explicitGateCount)
        .as("★ 扫描器自证：必须有 %d 条**显式**覆写 gate() 的工具（实测值；全被删掉 ⇒ 本扫描器空转）", 8)
        .isEqualTo(8);
    assertThat(TOOL_CLASSES.keySet())
        .as("★ 清单自洽：DM_FREE_TOOLS ∪ DM_READ_TOOLS 必须与扫描表逐条相同（加了工具却忘了进扫描面 ⇒ 这里红）")
        .containsExactlyInAnyOrderElementsOf(
            Stream.concat(DM_FREE_TOOLS.stream(), DM_READ_TOOLS.stream()).distinct().toList());
  }

  /**
   * ★★ <b>N10 的行为侧对照（防止"源码扫过了、装配却没挂上"）</b>：这一族工具必须**真的**在决策人桶里、且**不在** GM 桶。
   *
   * <p>★ 为什么源码扫描之外还要这一条：扫描证的是"声明是对的"，装配证的是"声明真的被用上了"。两者之间那条缝 （{@code toolRoles()} 写对但忘了 {@code
   * addDecisionReads}/{@code addDecisionWrites}）**不会有任何报错**， 只会让工具静默消失（{@code DecisionFaceGolden}
   * 的金名单相等断言也会红，但那条的失败信息不如这里点名清楚）。
   */
  @Test
  void everyDecisionFaceToolIsInTheDecisionBucketAndNotInTheGmBucket() {
    // ★ store 用 @TempDir（绝对路径）：装配期要挂工作区，相对路径会被 registry 具名拒
    //   （实测：「挂载的物理落点必须是绝对路径」）。
    try (Shell shell = Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0, 0))) {
      Set<String> decisionFace =
          ToolBuckets.namesOf(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT));
      List<String> gmFace =
          shell.toolsFor(SimosToolSource.Role.GM).stream().map(AgentTool::name).toList();

      for (String name : Stream.concat(DM_FREE_TOOLS.stream(), DM_READ_TOOLS.stream()).toList()) {
        assertThat(decisionFace).as("★★ N10：%s 必须在决策人桶里（声明对了但没挂桶 = 静默消失）", name).contains(name);
        assertThat(gmFace).as("★★ N10：%s **不许**进 GM 桶（决策人面被放大 = I2 破）", name).doesNotContain(name);
      }
    }
  }

  // ────────────────────────────── 扫描小件 ──────────────────────────────

  /**
   * {@code simos-ga} 工具的源码根（surefire 工作目录 = {@code simos-app/}）。
   *
   * <p>★ 必须是**工具类所在的那个包目录**（{@code .../io/mosire/simos/ga/tools}），不是 {@code src/main/java} ——否则
   * {@code resolve("write/XxxTool.java")} 谁也命中不了（本类第一版就踩了这个，真跑红在"扫描面必须命中"）。
   */
  private static Path gaMainJava() {
    return Paths.get(
        "..", "simos-ga", "src", "main", "java", "io", "mosire", "simos", "ga", "tools");
  }

  /**
   * 抽一个方法的**方法体**（从签名后的第一个 {@code {} 到配对的 {@code }}}）。
   *
   * <p>★ 判据是"这个方法体里有没有 X"，所以必须**真的**切出方法体：整文件扫会扫到类注里的解释文字 （本仓的类注里恰好大量出现 {@code Ask}
   * 这个词——正是为了解释"为什么不能是 Ask"）⇒ 整文件扫会**假红**。
   */
  private static String methodBody(String source, String methodName) {
    Matcher signature =
        Pattern.compile(
                "(?:public|private|protected)\\s+[\\w<>,.\\[\\] ]+\\s+"
                    + Pattern.quote(methodName)
                    + "\\s*\\([^)]*\\)\\s*\\{")
            .matcher(source);
    assertThat(signature.find()).as("★ 源码里必须有 %s(...) 的方法体（找不到 = 判据扫的是空气）", methodName).isTrue();
    int open = source.indexOf('{', signature.start());
    int depth = 0;
    for (int i = open; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          return source.substring(open, i + 1);
        }
      }
    }
    throw new AssertionError(methodName + " 的方法体括号不配对");
  }

  /** 同 {@link #methodBody}，但方法不存在时返回 {@code null}（用于"显式覆写 vs 走接口缺省"两种形态）。 */
  private static String optionalMethodBody(String source, String methodName) {
    Matcher signature =
        Pattern.compile(
                "(?:public|private|protected)\\s+[\\w<>,.\\[\\] ]+\\s+"
                    + Pattern.quote(methodName)
                    + "\\s*\\([^)]*\\)\\s*\\{")
            .matcher(source);
    if (!signature.find()) {
      return null;
    }
    int open = source.indexOf('{', signature.start());
    int depth = 0;
    for (int i = open; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          return source.substring(open, i + 1);
        }
      }
    }
    throw new AssertionError(methodName + " 的方法体括号不配对");
  }

  // ══════════════════════════════════════════════════════════════════════════════════════════
  // ★★ 2026-10-15 批 J（§3.9.4）：GM 三条读口的文案 —— "PENDING = 当前提交 · 可多次 · 以最后一次为准 ·
  //    已裁决之后不能再改"
  // ══════════════════════════════════════════════════════════════════════════════════════════

  /** ★ §3.9.4 的**四条**要点，逐条一个不可省的子串（三处文案必须逐字同源）。 */
  private static final Map<String, String> REQUIRED_PHRASES = new LinkedHashMap<>();

  static {
    REQUIRED_PHRASES.put("PENDING = 该决策人当前的提交", "PENDING = 该决策人当前的提交");
    REQUIRED_PHRASES.put("同一 tick 内可提交很多次", "很多次");
    REQUIRED_PHRASES.put("GM 应以最后一次为准", "以最后一次为准");
    REQUIRED_PHRASES.put("已裁决之后不能再改", "之后不能再改");
  }

  /** GM 侧三条读口的实现类（相对 {@code simos-ga/src/main/java/...}）。 */
  private static final Map<String, String> GM_READ_TOOL_CLASSES = new LinkedHashMap<>();

  static {
    GM_READ_TOOL_CLASSES.put(GmPacketTool.NAME, "read/GmPacketTool.java");
    GM_READ_TOOL_CLASSES.put(GmPacketsTool.NAME, "read/GmPacketsTool.java");
    GM_READ_TOOL_CLASSES.put(GmRoundTool.NAME, "read/GmRoundTool.java");
  }

  /**
   * ★★ <b>§3.9.4 / 不变量 I13·I14 的文案判据：GM 三条读口的 {@code description()} 必须写明 「PENDING = 当前提交 · 同 tick
   * 可多次提交 · GM 以最后一次为准 · 已裁决之后不能再改」</b>。
   *
   * <p>★★ <b>为什么文案是判据、不是修辞</b>（设计书 §3.9.4 把它列为"必须改"）：GM 是<b>唯一的驱动者</b>，它读到的 {@code description()}
   * 就是它判断"这个 DM 还会不会再改"的<b>全部依据</b>。★ 旧文案把 PENDING 描述成定稿 ⇒ GM 会在 DM 还没说完时就推演（设计书 §3.9.1「gm
   * 应当取最后一轮提交的决策」直接被违反），而<b>没有任何一层会报错</b>。
   *
   * <p>★ <b>为什么扫源码而不是调 {@code description()}</b>：与 {@link
   * #everyDecisionFaceToolDeclaresAllowAndDmOnlyInSource} 同一理由（形制也照它）——{@code description()}
   * 是一段<b>声明</b>，它的坏法正是"某个分支下换一句话"，而装配期读一次 只能证"这一条路径上那句话对"。★
   * 扫源码还能顺带钉住<b>三处逐字同源</b>（本仓为"第二处真相"付过代价）。
   *
   * <p>★★ <b>判别力</b>：四条要点各一个子串 ⇒ 删任意一条当场红；把三处里任意一处改回旧口径（旧文案讲的是 「已提交 ⇒ 定稿，先
   * reopen」，那两句现在读的是「以最后一次为准」与「很多次」）⇒ 当场红。
   */
  @Test
  void theThreeGmReadFacesDescribePendingAsTheLatestSubmissionNotAsALock() throws IOException {
    Path gaMain = gaMainJava();
    assertThat(Files.isDirectory(gaMain))
        .as("★ 扫描根必须存在（否则下面的「扫到 0 条」会伪装成通过）：%s", gaMain.toAbsolutePath())
        .isTrue();

    int scanned = 0;
    for (Map.Entry<String, String> entry : GM_READ_TOOL_CLASSES.entrySet()) {
      String toolName = entry.getKey();
      Path file = gaMain.resolve(entry.getValue());
      assertThat(Files.isRegularFile(file))
          .as("★ 扫描面必须命中（%s 的源码不在 %s ⇒ 扫描器静默 = 判据失效）", toolName, entry.getValue())
          .isTrue();
      String source = Files.readString(file);
      assertThat(source)
          .as("★ 命名判据：%s 的源码里必须逐字写着它自己的 NAME（改错文件时当场红）", toolName)
          .contains("\"" + toolName + "\"");

      String description = methodBody(source, "description");
      for (Map.Entry<String, String> phrase : REQUIRED_PHRASES.entrySet()) {
        assertThat(description)
            .as(
                "★★ §3.9.4：%s 的 description() 必须写明「%s」（缺这条 ⇒ GM 会把 PENDING 当定稿，"
                    + "在 DM 还没说完时就推演；设计书 §3.9.1「取最后一轮提交的决策」被违反而**无任何一层报错**）",
                toolName, phrase.getKey())
            .contains(phrase.getValue());
      }
      scanned++;
    }
    assertThat(scanned)
        .as("★ 扫描器自证：必须真的扫到全部 %d 条 GM 读口（扫到 0/漏文件 = 「空 == 空」陷阱）", GM_READ_TOOL_CLASSES.size())
        .isEqualTo(GM_READ_TOOL_CLASSES.size());
  }

  private static int countOccurrences(String haystack, String needle) {
    int count = 0;
    int index = haystack.indexOf(needle);
    while (index >= 0) {
      count++;
      index = haystack.indexOf(needle, index + needle.length());
    }
    return count;
  }
}
