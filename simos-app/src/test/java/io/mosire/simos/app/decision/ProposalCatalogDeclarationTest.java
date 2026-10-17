package io.mosire.simos.app.decision;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.time.CalendarService;
import io.mosire.simos.app.tools.SimosToolSource;
import io.mosire.simos.ga.spi.DecisionProposable;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>批 K ①「让声明成为唯一真相」的三条结构性守卫</b>（设计书 §3.2 / §3.3；验收判据 <b>V1</b> / <b>V4</b>，外加 C1
 * 在<b>真装配</b>上的那次读回）。
 *
 * <table>
 *   <caption>本类三条用例对应的判据</caption>
 *   <tr><th>用例</th><th>判据</th></tr>
 *   <tr><td>{@link #catalogMembersAreExactlyTheProposableImplementersOnBothFaces}</td><td><b>V1</b>：目录成员 ⇔
 *       工具面上 {@link DecisionProposable} 的实现者（逐条、两档各一次、不重不漏）</td></tr>
 *   <tr><td>{@link #everyProposableImplementerOnTheRealFacesDeclaresDm}</td><td><b>C1 的真装配读回</b>：凡实现者
 *       {@code toolRoles()} 必含 DM —— <b>直接断言</b>，不是"靠审计抛"</td></tr>
 *   <tr><td>{@link #theCatalogSourceCarriesNoToolNamesAndConstructsNoTool}</td><td><b>V4</b>：{@code
 *       ProposalCatalog} 源码里工具名字面量 0 处、{@code new XxxTool(} 0 处</td></tr>
 * </table>
 *
 * <p>★★ <b>为什么 V1 与"审计已经查过一遍"不重复</b>：装配期那次（{@code ProposalDeclarationAudit} 的 C2）是 <b>fail-closed
 * 的运行时判据</b> —— 它保证"起得来就一致"，但它的存在本身<b>没有被任何用例守着</b>：把 C2 删掉，真壳照样启动。 本类把同一件事写成<b>可执行规格</b>（并且 V1
 * 的比对对象是<b>真装配的两个工具面</b>），于是"删掉 C2"或"声明与装配分叉"两种漂移 都会当场红。
 *
 * <p>★★ <b>与 {@code ToolClassAuditAssemblyTest} 的分工</b>：那一类守的是<b>三分类</b>（roles × gate 的派生），本类守的是
 * <b>可提议名单</b>（implements 声明 × 工具面的派生）。两者共用同一句口径 —— 用户 2026-10-15：「这玩意不是在程序代码上已经 写死了吗……为啥额外要张表？」⇒
 * <b>凡是"第二处声明"，两处都要有守卫。</b>
 */
class ProposalCatalogDeclarationTest {

  /**
   * ★ 目录条数锚点（增删一条都要显式改本行并说明为什么）：2026-10-15 用户裁定「甲」<b>只补 DM 标签、不加成员</b> ⇒ 目录仍是 <b>16</b> 条（7 条裁定甲 +
   * 9 条批 3b 世界写）。★ 与 {@code ProposalCatalogPlanOnlyScanTest.CATALOG_SIZE} 是同一份实测值。
   */
  private static final int CATALOG_SIZE = 16;

  /** 兼容构造器的 initiator（只用于建目录，不落盘）。 */
  private static final String INITIATOR = "test:k-declaration";

  /** {@code new XxxTool(...)} 形状（工具类构造；★ 不匹配 {@code new ToolContext(} / {@code new Entry(}）。 */
  private static final Pattern TOOL_CONSTRUCTION = Pattern.compile("new\\s+[A-Z]\\w*Tool\\s*\\(");

  /** 块注释（本仓类注大量引用 {@code new XxxTool(...)} ⇒ 不剥注释这条判据会假红）。 */
  private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

  private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

  @TempDir Path tempDir;

  // ────────────────────────── V1：目录成员 ⇔ 面上的实现者 ──────────────────────────

  /**
   * ★★ <b>V1：{@code ProposalCatalog} 的成员**恰好**是工具面上实现 {@link DecisionProposable} 的工具 —— 两个档各比一次，
   * 逐条、不重不漏</b>。
   *
   * <p>★ <b>为什么"两档各一次"是必须的</b>：目录实例有<b>两个</b>（GM 档与决策人档各一个），它们的成员今天相同（同一个唯一构造点），
   * 但"相同"这件事本身<b>正是要钉的结论</b>：谁把某一档的目录改成从别处构造（比如只收一半工具），另一档照样绿 ⇒ 只有两档都比才知道。
   *
   * <p>★ <b>三条"不重不漏"的腿</b>（缺一条这条判据就是假的）：
   *
   * <ol>
   *   <li>目录非空且条数 == 实测锚点（防"空 == 空"恒真、防目录被清空后循环空转）；
   *   <li>唯一构造点里的<b>每一条</b>都真的实现了接口 —— 目录按 {@code instanceof} 派生 ⇒ 谁把一条<b>没实现接口</b>的工具塞进
   *       构造点，它会被目录<b>静默丢掉</b>（声明与装配分叉，无症状）；
   *   <li>逐档：面上实现者的名字集合 == 目录成员的名字集合（{@code containsExactlyInAnyOrderElementsOf}，两个方向一起管）。
   * </ol>
   *
   * <p>★ <b>如实记一条边界</b>：本用例读的是<b>生产那个唯一构造点</b> + <b>真装配的两个工具面</b>，不是 Shell 内部持有的那一个 {@code
   * ProposalCatalog} 实例（{@code Shell} 没暴露访问口）。"Shell 用的是不是这一份"由装配期的 C2 在真实例上钉死（★ 它读的 正是 {@code
   * toolSource.proposalCatalog()}）。
   */
  @Test
  void catalogMembersAreExactlyTheProposableImplementersOnBothFaces() {
    try (Shell shell = startShell()) {
      ProposalCatalog catalog = catalogOf(shell);
      List<String> members = catalog.toolNames();
      assertThat(members).as("★ 扫描面自证：目录非空（空 == 空 会让下面的逐条比对恒真）").isNotEmpty();
      assertThat(members)
          .as("★★ 目录条数锚点（增删一条都要显式改本行并说明为什么）：裁定「甲」只补 DM 标签、不加成员 ⇒ 仍 %d 条", CATALOG_SIZE)
          .hasSize(CATALOG_SIZE);
      assertThat(members).as("★ 目录按工具名索引 ⇒ 不许重名（重名在构造器里已经 fail-closed）").doesNotHaveDuplicates();

      List<AgentTool> proposable =
          SimosToolSource.decisionProposalTools(
              shell.coreSimos(),
              CalendarService.defaults(),
              shell.queryService(),
              INITIATOR,
              shell.config().mapId());
      assertThat(proposable).as("★ 唯一构造点的条数 == 目录条数（成员只能来自它）").hasSize(CATALOG_SIZE);
      for (AgentTool tool : proposable) {
        assertThat(tool)
            .as(
                "★★ V1：唯一构造点里的 %s 必须实现 DecisionProposable —— 否则目录按 instanceof 派生会**静默漏掉**它"
                    + "（「声明了装配面」与「声明了自己可被提议」分叉，无症状）",
                tool.name())
            .isInstanceOf(DecisionProposable.class);
      }

      for (SimosToolSource.Role role :
          List.of(SimosToolSource.Role.GM, SimosToolSource.Role.DECISION_AGENT)) {
        Set<String> implementers = implementersOn(shell.toolsFor(role));
        assertThat(implementers)
            .as(
                "★★ V1（%s 档）：工具面上实现 DecisionProposable 的工具必须**恰好**是目录成员 —— "
                    + "少一条 = 取不到实例（预览/执行整条路是空的）；多一条 = 声明了可提议却静默进不去",
                role)
            .containsExactlyInAnyOrderElementsOf(members);
      }
    }
  }

  // ────────────────────────── C1 的真装配读回 ──────────────────────────

  /**
   * ★★ <b>C1 在真装配上的读回：凡实现 {@link DecisionProposable} 的工具，{@code toolRoles()} 必含 {@link
   * ToolRole#DM}</b>（用户口径「没有 DM 标签的工具不该出现在提议名单里」）。
   *
   * <p>★★ <b>为什么这条不能只靠"审计会抛"</b>：装配期抛是<b>运行时</b>判据，它在今天恒不触发（16 条实现者都含 DM）⇒
   * "没有红"既可能是"判据在正常工作"，也可能是"判据被删了"。本用例把同一句话写成<b>对真装配工具面逐个实例的直接断言</b>， 于是它是一条独立的、可执行的规格。★ 与 {@code
   * ProposalDeclarationAuditTest}（合成违反者）分工互补：那边证明判据<b>有判别力</b>， 这边证明真装配面<b>确实满足</b>判据。
   *
   * <p>★ <b>非空自证</b>：每档都要扫到 {@value #CATALOG_SIZE} 条实现者 —— 否则"凡实现者"是一个在 0 条上恒真的空话 （这正是本仓"空 ==
   * 空"那类假绿的形状）。
   */
  @Test
  void everyProposableImplementerOnTheRealFacesDeclaresDm() {
    try (Shell shell = startShell()) {
      for (SimosToolSource.Role role :
          List.of(SimosToolSource.Role.GM, SimosToolSource.Role.DECISION_AGENT)) {
        List<AgentTool> face = shell.toolsFor(role);
        Set<String> implementers = implementersOn(face);
        assertThat(implementers)
            .as(
                "★ 扫描面自证：%s 档实现 DecisionProposable 的必须是实测的 %d 条（0 条 ⇒ 下面的断言是空转）",
                role, CATALOG_SIZE)
            .hasSize(CATALOG_SIZE);
        for (AgentTool tool : face) {
          if (!(tool instanceof DecisionProposable)) {
            continue;
          }
          assertThat(rolesOf(tool))
              .as(
                  "★★ C1：%s 实现了 DecisionProposable（= 声明自己可被决策人 propose）⇒ toolRoles() 必须含 DM。"
                      + "★ 用户口径（2026-10-15）：「有 DM 标签的工具」才谈得上 DM 提议；"
                      + "要么给它的 toolRoles() 补 DM，要么让它别再实现 DecisionProposable —— 两条路都在工具类里",
                  tool.name())
              .contains(ToolRole.DM);
        }
      }
    }
  }

  // ────────────────────────── V4：源码扫描（0 处名单） ──────────────────────────

  /**
   * ★★ <b>V4：{@code ProposalCatalog} 的**代码**里工具名字面量 0 处、{@code new XxxTool(} 0 处</b>（形制照 {@code
   * ProposalCatalogPlanOnlyScanTest} / {@code DecisionPacketToolGateScanTest}）。
   *
   * <p>★ <b>它守的是什么</b>：名单的<b>唯一拼写点</b>必须是工具自己的 {@code implements DecisionProposable}（不变量 K1）。 只要有人在
   * {@code ProposalCatalog} 里重新写下一批工具名（或 {@code new} 一批工具）当"第二处清单"，本用例当场红 ——
   * 而那种漂移<b>本来是无症状的</b>（旧手写清单里那 7 条 GM-only 就是这么混进去的）。
   *
   * <p>★★ <b>为什么必须"剥注释后再判"</b>：本类的 javadoc 逐字引用了 {@code new XxxTool(...)} 作为<b>反面教材</b>，
   * 又在一处行内注释里点了 {@code simos.gov.issueMoney}；不剥注释 ⇒ 判据被自己的说明文打成假红。 ★
   * 因此下面有<b>三条自证</b>，缺一条这条扫描就有可能是"扫空气"：
   *
   * <ol>
   *   <li>剥注释这一步是<b>承重的</b>：原文本（含注释）里逐字写着 {@code new XxxTool(} 与那个工具名，剥掉之后一条都不剩 —— 既证明读的是这个文件，也证明"0
   *       处"不是靠注释被删掉才成立的；
   *   <li>剥注释器本身正确：拿<b>合成样本</b>当场走一遍（不依赖生产文本，防"剥注释把代码也剥没了"）；
   *   <li>扫描面（目录成员）非空且 == 实测条数；抽到的字符串字面量非空。
   * </ol>
   *
   * <p>★ <b>工具名单从哪来</b>：不写死 —— 从真装配的目录成员取（16 条）。工具改名后本扫描自动跟着新名字扫，不会因为改名而失效。
   */
  @Test
  void theCatalogSourceCarriesNoToolNamesAndConstructsNoTool() throws IOException {
    Path sourceFile =
        Paths.get(
            "src",
            "main",
            "java",
            "io",
            "mosire",
            "simos",
            "app",
            "decision",
            "ProposalCatalog.java");
    assertThat(Files.isRegularFile(sourceFile))
        .as("★ 扫描根必须存在（否则下面的「0 处命中」会伪装成通过）：%s", sourceFile.toAbsolutePath())
        .isTrue();
    String raw = Files.readString(sourceFile);
    String code = stripComments(raw);

    assertThat(raw)
        .as(
            "★ 自证之一：原文本（含注释）里逐字写着 new XxxTool( —— 它是本类的反面教材；"
                + "⇒ 下面代码里的「0 处」只有在剥注释之后才可能成立，剥注释不是摆设")
        .contains("new XxxTool(");
    assertThat(raw)
        .as("★ 自证之二：原文本的**注释**里点了一个工具名（simos.gov.issueMoney）—— 剥掉之后它必须消失")
        .contains("simos.gov.issueMoney");
    assertThat(code).as("★ 自证之二（续）：剥注释后的代码里不许再有那个注释里的工具名").doesNotContain("simos.gov.issueMoney");
    assertThat(stripComments("a /* new FooTool( */ b // new BarTool(\nc"))
        .as("★ 自证之三：剥注释器对合成样本正确（块注释 + 行注释都剥、代码留着）")
        .isEqualTo("a  b \nc");
    assertThat(TOOL_CONSTRUCTION.matcher(code).find())
        .as("★★ V4：ProposalCatalog 的**代码**里 new XxxTool( 必须 0 处（实例只能来自工具面的唯一构造点）")
        .isFalse();

    try (Shell shell = startShell()) {
      List<String> members = catalogOf(shell).toolNames();
      assertThat(members)
          .as("★ 扫描器自证之四：扫描面（= 目录成员）非空且是实测条数（否则下面的循环空转 = 判据归零）")
          .isNotEmpty()
          .hasSize(CATALOG_SIZE);
      List<String> literals = stringLiteralsOf(code);
      assertThat(literals).as("★ 扫描器自证之五：真的从代码里抽到了字符串字面量（抽到 0 条 ⇒ 下面的字面量判据是空转）").isNotEmpty();
      for (String toolName : members) {
        assertThat(code)
            .as(
                "★★ V4：ProposalCatalog 的代码里出现了工具名 %s —— 名单只能从工具面派生（instanceof），"
                    + "**不许**有第二处手写清单（不变量 K1）",
                toolName)
            .doesNotContain(toolName);
        assertThat(literals)
            .as("★★ V4：工具名 %s 被写成了字符串字面量（第二处声明的另一种形态）", toolName)
            .noneMatch(literal -> literal.contains(toolName));
      }
    }
  }

  // ────────────────────────── 小件 ──────────────────────────

  /** ★ 真装配（端口全 0 ⇒ OS 随机分配；store 用 JUnit 临时目录）。 */
  private Shell startShell() {
    return Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0, 0));
  }

  /**
   * 生产那个<b>唯一构造点</b>建出来的目录（兼容构造器；见 {@link ProposalCatalog} 的类注：它不是第二条声明路径 —— 可提议族的实例只在 {@code
   * SimosToolSource.decisionProposalTools} 里 {@code new} 一次）。
   */
  private ProposalCatalog catalogOf(Shell shell) {
    return new ProposalCatalog(
        shell.coreSimos(),
        CalendarService.defaults(),
        shell.queryService(),
        INITIATOR,
        shell.config().mapId());
  }

  /** 一个工具面上实现 {@link DecisionProposable} 的工具名（保序、去重）。 */
  private static Set<String> implementersOn(List<AgentTool> face) {
    Set<String> names = new LinkedHashSet<>();
    for (AgentTool tool : face) {
      if (tool instanceof DecisionProposable) {
        names.add(tool.name());
      }
    }
    return names;
  }

  /**
   * {@code toolRoles()} 的读口（★ 与 {@code ToolClassAuditAssemblyTest.rolesOf} 同法）：真装配面上的每条工具都必须实现
   * {@link SimosTool}（否则它过不了装配期的角色审计）⇒ 这里先**显式断言**再转，免得失败退化成一次裸 {@link
   * ClassCastException}（"哪条工具、为什么"就丢了）。
   */
  private static Set<ToolRole> rolesOf(AgentTool tool) {
    assertThat(tool)
        .as("★ 真装配面里的 %s 必须实现 SimosTool（否则它过不了装配期的角色审计）", tool.name())
        .isInstanceOf(SimosTool.class);
    return ((SimosTool) tool).toolRoles();
  }

  /** 剥掉块注释与行注释（★ 只用于"工具名/构造点"这类 token 判据，不解析字符串字面量里的注释符）。 */
  private static String stripComments(String source) {
    return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(source).replaceAll("")).replaceAll("");
  }

  /** 抽源码里的字符串字面量（{@code "..."}，含转义处理）。 */
  private static List<String> stringLiteralsOf(String source) {
    List<String> literals = new ArrayList<>();
    Matcher matcher = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(source);
    while (matcher.find()) {
      literals.add(matcher.group(1));
    }
    return literals;
  }
}
