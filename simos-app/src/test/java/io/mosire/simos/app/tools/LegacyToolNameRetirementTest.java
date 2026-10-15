package io.mosire.simos.app.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.access.DecisionFaceGolden;
import io.mosire.simos.ga.spi.SimosTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>批 L ② 的 L4 判据：「旧名一律不留」</b>（设计书 §4 L4 + 测试面任务书 §1.2）。
 *
 * <p>★ <b>为什么单独立一个类</b>：这条判据要同时钉三个面，而它们分属三处不同的机制 ——
 *
 * <ol>
 *   <li><b>真装配面</b>（运行时）：两个桶的并集里不许有任何一条旧工具名，且每条旧名对应的**新名**必须在场（否则"旧名不在"可能只是 因为整条工具没了 —— 那是空 == 空）；
 *   <li><b>金名单</b>（字面量形制）：照 {@code DecisionCallerFactoryTest} 的 {@code doesNotContain} 形制，对决策人面金名单做
 *       反向断言；
 *   <li>★★ <b>测试源码文本</b>（本批最贵的教训）：<b>改工具名不扫 {@code src/test} 的字面量 ⇒ 门控用例悄悄引用旧名，而它被门控 ⇒ 从不跑 ⇒
 *       没人被告知</b>（实测：{@code RealLlmGovScenarioTest} 有 10 处旧名，而它被
 *       {@code @EnabledIfEnvironmentVariable} 门控，在门禁里**一次都不跑**）。⇒ 只钉"跑起来的面"治不了这个死角，必须**扫源码文本**。
 * </ol>
 *
 * <p>★ <b>命令类型不在本判据里</b>（{@code gov.CreateNation} / {@code gov.RecordDiplomaticEvent} / {@code
 * gov.SetDiplomaticRelation} / {@code ga.PutInfo} 这些**命令类型**今天仍然叫这个名字 —— 铁律 2 的口径 + 用户「正常语义不用」）。 ⇒
 * 本类只在**工具名**这一维断言；"同名两义"的逐处判据见台账 §1。
 */
class LegacyToolNameRetirementTest {

  /**
   * ★★ <b>19 条旧工具名</b>（批 L ② 的全部改名对象；顺序与 {@link #RENAMED_TOOL_NAMES} 一一对应）。
   *
   * <p>★ 三组：9 条 {@code simos.sd.*}（设计书 §1.1）· 7 条 {@code sd.*}（§5.3）· 3 条无前缀旧名（§2 裁定 N2/N3）。
   */
  private static final List<String> OLD_TOOL_NAMES =
      List.of(
          // ── ① 9 条 simos.sd.*（设计书 §1.1 / 裁定 N1）────────────────────────────
          "simos.sd.decision-maker",
          "simos.sd.decision-makers",
          "simos.sd.diplomacy",
          "simos.sd.diplomatic-events",
          "simos.sd.directives",
          "simos.sd.verdicts",
          "simos.sd.run-decision-makers",
          "simos.sd.record-diplomatic-event",
          "simos.sd.set-diplomatic-relation",
          // ── ② 7 条 sd.*（设计书 §5.3 + §5.3 的映射表）──────────────────────────
          "sd.VoidAdjudication",
          "sd.AdjudicateTick",
          "sd.SetArmyMasterGov",
          "sd.CreateArmy",
          "sd.RejectDirective",
          "sd.DecisionResults",
          "sd.DecisionDocs",
          // ── ③ 3 条无前缀旧名（设计书 §2 裁定 N2/N3；★ 只在**工具名**位置）──────────
          "gov.CreateNation",
          "gov.RecordDiplomaticEvent",
          "gov.SetDiplomaticRelation");

  /**
   * ★★ <b>与 {@link #OLD_TOOL_NAMES} 逐位对应的 19 条新名</b>（装配面的配对证据）。
   *
   * <p>★★ <b>两个例外如实写在这里</b>（不是漏配）：{@code sd.CreateArmy} / {@code sd.SetArmyMasterGov} 的"新名" {@code
   * simos.ga.createArmy} / {@code simos.ga.setArmyMasterGov} **曾经**是 {@code NAME}
   * 常量的字面量、**从来不是**运行时的工具名 —— 那两个类**没有覆写 {@code toolName()}** ⇒ {@code name()} 返回的是命令类型（{@code
   * army.CreateArmy} / {@code army.SetArmyMasterGov}）。⇒ 配对断言写的是**工具面上真实可见的那个名字**（见 impl-ledger §1.3
   * / §6 待裁定 #1）。
   *
   * <p>★★ <b>2026-10-15 批 N ① 回代码核（原文说错，此处改正）</b>：上面那两个常量<b>已不存在</b> —— 批 N 把 {@code
   * CreateArmyTool} / {@code SetArmyMasterGovTool} 的死 {@code NAME} 常量**删除**了（与真实工具名不一致 + 全仓零符号引用）。
   * ⇒ 今天写到这两个类时，**没有任何 {@code NAME} 常量可读**：它们的工具名一律由 {@code commandType()}（= {@code
   * COMMAND_TYPE}）给， 也就是下面这 19 条里的 {@code army.SetArmyMasterGov} / {@code army.CreateArmy} 两条。
   */
  private static final List<String> RENAMED_TOOL_NAMES =
      List.of(
          "simos.ga.decision-maker",
          "simos.ga.decision-makers",
          "simos.ga.diplomacy",
          "simos.ga.diplomatic-events",
          "simos.ga.directives",
          "simos.ga.verdicts",
          "simos.ga.run-decision-makers",
          "simos.gov.recordDiplomaticEvent",
          "simos.gov.setDiplomaticRelation",
          "simos.ga.voidAdjudication",
          "simos.ga.adjudicateTick",
          // ★ 无 NAME 常量（2026-10-15 批 N ① 已删）：真实工具名是命令类型（详见 RENAMED_TOOL_NAMES 的 javadoc）
          "army.SetArmyMasterGov",
          "army.CreateArmy",
          "simos.ga.rejectDirective",
          "simos.ga.decisionResults",
          "simos.ga.decisionDocs",
          "simos.gov.createNation",
          "simos.gov.recordDiplomaticEvent",
          "simos.gov.setDiplomaticRelation");

  /** 本类自己的文件名 —— 源码扫描**唯一**的豁免（它持有的正是这些旧名，作为反向断言的**数据**）。 */
  private static final String SELF_FILE_NAME = "LegacyToolNameRetirementTest.java";

  /** 源码扫描面下界（自证"扫描器真的扫到了东西"，防"零命中 == 空 == 空"）。2026-10-15 实测 471。 */
  private static final int MIN_SCANNED_FILES = 300;

  @TempDir Path tempDir;

  // ────────────────────────── ① 真装配面：旧名一个不许留，新名逐条在场 ──────────────────────────

  /**
   * ★★ <b>L4-a：两个桶的并集里，19 条旧工具名一条都不许有；且每条旧名的新名必须在场</b>。
   *
   * <p>★ <b>为什么要"配对"</b>：只断言"旧名不在"是**半边判据** —— 把整条工具删掉也能让它绿。配上"新名在场"之后，这条断言才说得出
   * "是**改名**了，不是**删掉**了"。★ 唯一的两处例外（{@code sd.CreateArmy} / {@code sd.SetArmyMasterGov}，配对到工具面上真实的
   * {@code army.*}）已在 {@link #RENAMED_TOOL_NAMES} 写明。
   */
  @Test
  void noOldToolNameSurvivesOnEitherFace() {
    try (Shell shell = startShell()) {
      Set<String> gmFace = namesOf(shell.toolsFor(SimosToolSource.Role.GM));
      Set<String> decisionFace = namesOf(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT));
      Set<String> union = new LinkedHashSet<>(gmFace);
      union.addAll(decisionFace);

      // ★ 扫描面自证：并集非空（否则下面两条都是"空 == 空"）
      assertThat(union).as("★ 自证：真装配的并集必须非空").hasSizeGreaterThan(150);

      assertThat(union)
          .as("★★ L4-a：19 条旧工具名**一条都不许**出现在任一桶或并集里（旧名一律不留）")
          .doesNotContain(
              "simos.sd.decision-maker",
              "simos.sd.decision-makers",
              "simos.sd.diplomacy",
              "simos.sd.diplomatic-events",
              "simos.sd.directives",
              "simos.sd.verdicts",
              "simos.sd.run-decision-makers",
              "simos.sd.record-diplomatic-event",
              "simos.sd.set-diplomatic-relation",
              "sd.VoidAdjudication",
              "sd.AdjudicateTick",
              "sd.SetArmyMasterGov",
              "sd.CreateArmy",
              "sd.RejectDirective",
              "sd.DecisionResults",
              "sd.DecisionDocs",
              "gov.CreateNation",
              "gov.RecordDiplomaticEvent",
              "gov.SetDiplomaticRelation");

      // ★ 配对：改名 ≠ 删掉 —— 每条旧名对应的新名必须在并集里（逐条报出是哪一条缺了）。
      for (int i = 0; i < OLD_TOOL_NAMES.size(); i++) {
        String oldName = OLD_TOOL_NAMES.get(i);
        String newName = RENAMED_TOOL_NAMES.get(i);
        assertThat(union)
            .as("★★ L4-a 配对：%s 必须已改名为 %s（新名不在 = 那不是改名、是删掉）", oldName, newName)
            .contains(newName);
      }
    }
  }

  // ────────────────────────── ② 金名单形制：doesNotContain ──────────────────────────

  /**
   * ★ <b>L4-b：决策人面金名单里也不许有旧名</b> —— 照 {@code DecisionCallerFactoryTest} 的 {@code doesNotContain} 形制
   * （设计书 §4 L4 点名的形制）。
   *
   * <p>★ 它与 L4-a 的分工：L4-a 读**真装配的桶**（装配错了才红），本条读**测试侧金名单**（规格被写回旧名就红）—— 两侧独立，缺一条
   * 就会出现"规格与装配一起漂移"而无人告知。
   */
  @Test
  void theDecisionFaceGoldenListCarriesNoOldToolName() {
    assertThat(DecisionFaceGolden.ALL).as("★ 自证：金名单非空").hasSizeGreaterThan(40);
    assertThat(DecisionFaceGolden.ALL)
        .as("★ L4-b：决策人面金名单（独立规格）必须用新名 —— 旧名一律不留")
        .doesNotContain(
            "simos.sd.decision-maker",
            "simos.sd.decision-makers",
            "simos.sd.diplomacy",
            "simos.sd.diplomatic-events",
            "simos.sd.directives",
            "simos.sd.verdicts",
            "simos.sd.run-decision-makers",
            "simos.sd.record-diplomatic-event",
            "simos.sd.set-diplomatic-relation",
            "sd.VoidAdjudication",
            "sd.AdjudicateTick",
            "sd.SetArmyMasterGov",
            "sd.CreateArmy",
            "sd.RejectDirective",
            "sd.DecisionResults",
            "sd.DecisionDocs",
            "gov.CreateNation",
            "gov.RecordDiplomaticEvent",
            "gov.SetDiplomaticRelation");
  }

  // ────────────────────────── ③ ★★ 死角根治：扫测试源码文本 ──────────────────────────

  /**
   * ★★ <b>L4-c（死角根治）：{@code src/test} 的源码文本里，16 条旧**工具名**字面量一个都不许再出现</b>。
   *
   * <p>★★ <b>为什么必须扫源码，而不是只扫"跑起来的面"</b>（本批最贵的教训，实测）：门控用例被 {@code @EnabledIfEnvironmentVariable} 挡住 ⇒
   * **在门禁里一次都不跑** ⇒ 它引用的旧工具名**永无人告知**。现场证据：{@code RealLlmGovScenarioTest} 有 10 处 {@code
   * simos.sd.*}（4 处在 {@code DECISION_ALLOWED_TOOLS} 夹具、6 处在**发给真模型的 任务简报**里）。⇒
   * 只钉运行时面治不了这个死角；扫文本才治得了。
   *
   * <p>★ <b>豁免只有一个</b>：本类自己（{@link #SELF_FILE_NAME}）—— 它持有的正是这些旧名，作为**反向断言的据**。除此之外**任何**文件
   * 引用旧工具名都当场红，包括门控用例。
   *
   * <p>★ <b>只扫"带双引号的字面量"</b>：{@code gov.CreateNation} 这类**命令类型**今天仍叫这个名字（不在 16 条里，天然不命中）；而
   * 注释/javadoc 里的旧名（{@code {@code sd.PutInfo}} 一类历史留痕）**不带引号** ⇒ 也不命中。这正是"工具名位置改、命令类型位置不改"的 自动分界。
   */
  @Test
  void noTestSourceStillQuotesAnOldToolName() throws IOException {
    List<Path> roots = testSourceRoots();
    List<String> files = new ArrayList<>();
    for (Path root : roots) {
      if (!Files.isDirectory(root)) {
        continue;
      }
      try (Stream<Path> walk = Files.walk(root)) {
        walk.filter(Files::isRegularFile)
            .filter(LegacyToolNameRetirementTest::isSourceFile)
            .forEach(path -> files.add(path.toString()));
      }
    }
    // ★ 扫描器自证：扫到的文件数必须够多（"零命中"若是因 pathspec/根写错而扫到 0 个文件，这里先红）
    assertThat(files)
        .as("★ 扫描器自证：测试源码扫描面必须非空（实测 %d 个 .java/.cjs/.js）", MIN_SCANNED_FILES)
        .hasSizeGreaterThanOrEqualTo(MIN_SCANNED_FILES);

    List<String> offenders = new ArrayList<>();
    for (String file : files) {
      if (SELF_FILE_NAME.equals(Paths.get(file).getFileName().toString())) {
        continue; // ★ 唯一豁免：本类（反向断言的数据源）
      }
      for (String oldName : oldToolNameLiteralsIn(Files.readString(Paths.get(file)))) {
        offenders.add(file + " :: \"" + oldName + "\"");
      }
    }
    assertThat(offenders)
        .as(
            "★★ L4-c：src/test 源码里不许再出现 16 条旧**工具名**字面量（★ 门控用例也算 —— 它们从不跑，"
                + "只靠运行时判据永远发现不了）。命中清单 = 旧名 + 文件:旧名")
        .isEmpty();

    // ★ 判别力自证（同一判别函数正反两向）：注入一条旧名必须被报出来，且**命令类型**与注释不被误伤。
    assertThat(oldToolNameLiteralsIn("x(\"simos.sd.verdicts\");"))
        .as("★ 扫描器有牙自证：注入旧工具名字面量必须被扫出")
        .containsExactly("simos.sd.verdicts");
    assertThat(oldToolNameLiteralsIn("x(\"gov.CreateNation\");"))
        .as("★ 不许误伤：gov.CreateNation 是**命令类型**（今天仍叫这个名），不在 16 条工具名里")
        .isEmpty();
    assertThat(oldToolNameLiteralsIn(" * 历史留痕：{@code sd.PutInfo} 当年叫这个（不带引号）"))
        .as("★ 不许误伤：注释/javadoc 里的旧名不带双引号 ⇒ 不命中")
        .isEmpty();
  }

  // ────────────────────────────── 助手 ──────────────────────────────

  private Shell startShell() {
    return Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0, 0));
  }

  private static Set<String> namesOf(List<AgentTool> tools) {
    Map<String, AgentTool> byName = new LinkedHashMap<>();
    for (AgentTool tool : tools) {
      assertThat(tool).as("★ 真装配面里的 %s 必须实现 SimosTool", tool.name()).isInstanceOf(SimosTool.class);
      byName.put(tool.name(), tool);
    }
    return byName.keySet();
  }

  /** ★ 扫描面的根：本模块的整棵 {@code src/test} + 全部兄弟模块的 {@code src/test/java}（自动发现，不手写清单）。 */
  private static List<Path> testSourceRoots() throws IOException {
    List<Path> roots = new ArrayList<>();
    Path repoRoot = Paths.get("..").toAbsolutePath().normalize();
    try (Stream<Path> modules = Files.list(repoRoot)) {
      for (Path module : modules.toList()) {
        if (!Files.isDirectory(module)) {
          continue;
        }
        Path testRoot = module.resolve("src").resolve("test");
        if (Files.isDirectory(testRoot)) {
          roots.add(testRoot);
        }
      }
    }
    return roots;
  }

  private static boolean isSourceFile(Path path) {
    String name = path.getFileName().toString();
    return name.endsWith(".java") || name.endsWith(".cjs") || name.endsWith(".js");
  }

  /**
   * ★ 判别函数（唯一）：一段源码文本里出现的**旧工具名字面量**（只认带双引号的 —— 命令类型与注释因此天然不命中）。
   *
   * <p>★★ 生产侧只扫 {@link #OLD_TOOL_NAMES} 里那 16 条**纯工具名**；三条无前缀旧名（{@code gov.CreateNation} 等）**不进
   * 本扫描**，因为它们在 {@code src/test} 里同时是**合法且必须保留**的命令类型（同名两义，见台账 §1 的逐处判据）。
   */
  private static List<String> oldToolNameLiteralsIn(String source) {
    List<String> hits = new ArrayList<>();
    for (String oldName : SCANNED_TOOL_NAME_LITERALS) {
      if (source.contains("\"" + oldName + "\"")) {
        hits.add(oldName);
      }
    }
    return hits;
  }

  /** 源码扫描用的 16 条（= {@link #OLD_TOOL_NAMES} 的前 16 条：9 条 {@code simos.sd.*} + 7 条 {@code sd.*}）。 */
  private static final List<String> SCANNED_TOOL_NAME_LITERALS = OLD_TOOL_NAMES.subList(0, 16);
}
