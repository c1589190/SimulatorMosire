package io.mosire.simos.app.decision;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import io.mosire.simos.app.time.CalendarService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>P1 结构性守卫（2026-10-15 测试面）：凡在 {@link ProposalCatalog} 清单里的工具，其提交路径必须先返回待提交命令批</b> ——源码扫描，形制照
 * {@code DecisionPacketToolGateScanTest}。
 *
 * <h2>它守的是什么失效形态（★ 本仓已重复三次的那个死角）</h2>
 *
 * <p>{@code DecisionEffectExecutor} 的契约（其类注 + {@code DecisionEffectExecutor:46-50}）：目标工具的 {@code
 * preview=false} 路径<b>直接 submitBatch</b>；执行器为了"N 条 call 落一条 revision + outcome 同批回写"，用内部参数 {@code
 * planOnly=true} 让工具<b>在提交前把手里的命令批交回来</b>。⇒ <b>catalog 的每一条成员都必须支持它</b>。
 *
 * <p>2026-10-15 实测：现有 7 条里有 <b>4 条</b>（{@code GovRecruitTool} / {@code GovRetireStaffTool} / {@code
 * GovSelectExamineesTool} / {@code GovDispatchTeamTool}）收了参数却在 {@code apply} 里丢掉 ⇒ 执行器"规划"阶段
 * <b>真落盘</b>，又不回传 {@code plannedCommands} ⇒ 报错、GM 重试 ⇒ <b>世界被写两次</b>。这个缺陷长期存活的机制是 <b>门控 +
 * 零覆盖</b>：唯一跑 {@code gm.packet.execute} 的 e2e 默认跳过，而 {@code simos-gov/src/test} 里 {@code planOnly}
 * 零覆盖。
 *
 * <h2>为什么必须是「扫源码」而不是「跑一遍看行为」</h2>
 *
 * <p>行为用例只能证明"这一次调用没写盘"；而<b>下一条进清单的工具</b>漏了 {@code planOnly} 时，没有任何行为用例会红。本扫描器把
 * <b>清单本身</b>当扫描面（{@code catalog.requireTool(name).getClass()} → 源码文件），于是"进清单"这件事自动带上这条判据 —— 新工具漏
 * {@code planOnly} ⇒ 当场红，不需要有人记得来加一条用例。
 *
 * <h2>三条判据（逐条都在源码文本上）</h2>
 *
 * <ol>
 *   <li><b>有守卫</b>：源码里必须有 {@code if (planOnly)} / {@code if (request.planOnly())} 形状的分支；
 *   <li><b>守卫真的交批</b>：该分支块里必须有 {@code return} 且调用 {@code planOnly(...)}（即返回待提交命令批，而不是 "假装成功"的 ok）；
 *   <li><b>守卫在提交之前</b>：若该文件里有提交点（{@code submitBatchWithReceipt} / {@code submitBatch(} / {@code
 *       submitOne(} / {@code submitCommand(} / {@code .submit(}），守卫的下标必须小于第一个提交点的下标（先交批、后提交 ——
 *       反过来就是"已经写完了再返回"）。
 * </ol>
 *
 * <p>★ 扫描面自证：扫到的条数必须等于 {@code ProposalCatalog.toolNames()} 的条数（"空 == 空"恒真是本仓踩过的陷阱）； 且清单条数写死为实测值
 * 16（清单被清空/改小 ⇒ 本扫描器当场红，不会静默空转）。
 */
class ProposalCatalogPlanOnlyScanTest {

  @TempDir Path tempDir;

  /** 清单条数实测值（2026-10-15：7 条原有 + 3b 的 9 条 = 16）。 */
  private static final int CATALOG_SIZE = 16;

  /** 守卫形状：{@code if (planOnly) {} / if (request.planOnly()) {}}。 */
  private static final Pattern GUARD =
      Pattern.compile("if\\s*\\(\\s*(?:request\\.)?planOnly(?:\\(\\))?\\s*\\)\\s*\\{");

  /**
   * "把待提交命令批交回去"的三种既有形状（★ 不发明第四种，也不给某一条开后门）：
   *
   * <ul>
   *   <li>{@code GovBatchViews.planOnly(batch)} —— gov 域 13 条；
   *   <li>{@code UnitBatchViews.plannedCommandsView(batch)} —— unit 域的 raiseUnit / levyRegion；
   *   <li>{@code planOnly(type, payload)} —— {@code AbstractHouseholdGmTool} 的 social 分册。
   * </ul>
   */
  private static final Pattern PLANNED_RETURN =
      Pattern.compile("(?:planOnly|plannedCommandsView)\\s*\\(");

  /** 注释里的 {@code {@code ...}} 引用会假命中提交点 token（本仓类注大量引用 {@code submitBatchWithReceipt}）⇒ 先剥注释。 */
  private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

  private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

  /** 提交点 token（命中第一个即"这条工具的提交路径"）。 */
  private static final List<String> SUBMIT_TOKENS =
      List.of("submitBatchWithReceipt", "submitBatch(", "submitOne(", "submitCommand(", ".submit(");

  @Test
  void everyCatalogMemberGuardsPlanOnlyBeforeItsSubmitPathInSource() throws IOException {
    try (Shell shell = Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0, 0))) {
      ProposalCatalog catalog =
          new ProposalCatalog(
              shell.coreSimos(),
              CalendarService.defaults(),
              shell.queryService(),
              "test:planonly-scan",
              shell.config().mapId());
      List<String> members = catalog.toolNames();
      System.out.println("[SCAN] catalog(" + members.size() + ")=" + members);
      assertThat(members)
          .as("★ 扫描面自证：清单条数必须是实测值 %d（清单被清空 ⇒ 下面的循环空转 = 判据归零）", CATALOG_SIZE)
          .hasSize(CATALOG_SIZE);
      assertThat(members)
          .as("★ catalog 里必须有那 4 条历史违反者（它们是这条判据的靶子）")
          .contains(
              "simos.gov.recruit",
              "simos.gov.retireStaff",
              "simos.gov.selectExaminees",
              "simos.gov.dispatchTeam");

      Map<String, String> failures = new LinkedHashMap<>();
      int scanned = 0;
      for (String name : members) {
        Class<?> toolClass = catalog.requireTool(name).getClass();
        Path sourceFile = sourceOf(toolClass);
        String source = Files.readString(sourceFile);
        // ★ 命名判据：解出来的文件必须是这条工具自己的（改错文件 ⇒ 当场红，而不是安静地扫空气）。
        assertThat(source)
            .as("★ 命名判据：%s 的源码（%s）里必须逐字写着它自己的 NAME", name, sourceFile)
            .contains("\"" + name + "\"");
        // ★ 剥注释后再判"提交点在哪"：本仓类注大量引用 submitBatchWithReceipt，不剥会把注释当提交点（假红）。
        String code = stripComments(source);

        Matcher guard = GUARD.matcher(code);
        if (!guard.find()) {
          failures.put(name, "源码里没有 if (planOnly) / if (request.planOnly()) 守卫 —— " + sourceFile);
          continue;
        }
        int guardIndex = guard.start();
        String block = braceBlock(code, guard.end() - 1);
        if (!block.contains("return")) {
          failures.put(name, "planOnly 守卫的分支里没有 return（没有把手里的批交回执行器）");
          continue;
        }
        if (!PLANNED_RETURN.matcher(block).find()) {
          failures.put(
              name, "planOnly 守卫的分支里没有返回待提交命令批（缺 planOnly(...)/plannedCommandsView(...) 调用）");
          continue;
        }
        int submitIndex = firstSubmitIndex(code);
        if (submitIndex >= 0 && guardIndex > submitIndex) {
          failures.put(
              name,
              "planOnly 守卫排在提交点之后（守卫@" + guardIndex + " > 提交@" + submitIndex + "）—— 写完才返回 = 双写");
          continue;
        }
        System.out.println(
            "[SCAN] "
                + name
                + " ok class="
                + toolClass.getSimpleName()
                + " guard@"
                + guardIndex
                + " submit@"
                + submitIndex
                + " file="
                + sourceFile);
        scanned++;
      }
      assertThat(failures).as("★★ P1 结构守卫：catalog 成员的提交路径必须先返回待提交命令批（漏一条 ⇒ 执行器规划期真落盘）").isEmpty();
      assertThat(scanned)
          .as("★ 扫描面自证：必须逐条扫完全部 %d 条（少一条 = 判据有洞）", CATALOG_SIZE)
          .isEqualTo(CATALOG_SIZE);
    }
  }

  // ────────────────────────────── 扫描小件 ──────────────────────────────

  /**
   * 工具类 → 它在仓里的源码文件。
   *
   * <p>★ 不写第二份"工具 → 文件"清单（那会立刻过期）：由 {@code getPackageName()} 拼路径，在 {@code simos-app/}
   * 的<b>兄弟模块</b>里找唯一命中（surefire 的工作目录 = {@code simos-app/}，与既有扫描器同法）。
   */
  private static Path sourceOf(Class<?> toolClass) throws IOException {
    Path repo = Paths.get("..").toAbsolutePath().normalize();
    String packagePath = toolClass.getPackageName().replace('.', '/');
    String fileName = toolClass.getSimpleName() + ".java";
    List<Path> hits = new ArrayList<>();
    try (Stream<Path> modules = Files.list(repo)) {
      for (Path module : modules.filter(Files::isDirectory).toList()) {
        Path candidate =
            module
                .resolve("src")
                .resolve("main")
                .resolve("java")
                .resolve(packagePath)
                .resolve(fileName);
        if (Files.isRegularFile(candidate)) {
          hits.add(candidate);
        }
      }
    }
    assertThat(hits)
        .as("★ %s 的源码必须唯一命中（0 个 = 扫描面失效；>1 个 = 重名类，判据指向不明）", toolClass.getName())
        .hasSize(1);
    return hits.get(0);
  }

  /** 从 {@code {} 开始切出配对的方法/分支块（含两端花括号）。 */
  private static String braceBlock(String source, int openIndex) {
    assertThat(source.charAt(openIndex)).as("★ braceBlock 必须从 '{' 开始").isEqualTo('{');
    int depth = 0;
    for (int i = openIndex; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          return source.substring(openIndex, i + 1);
        }
      }
    }
    throw new AssertionError("花括号不配对");
  }

  /** 文件里第一个提交点的下标（没有则 -1）。 */
  private static int firstSubmitIndex(String source) {
    int best = -1;
    for (String token : SUBMIT_TOKENS) {
      int index = source.indexOf(token);
      if (index >= 0 && (best < 0 || index < best)) {
        best = index;
      }
    }
    return best;
  }

  /** 剥掉块注释与行注释（字符串字面量里的 {@code //} 不做特殊处理：本判据只关心 token 的相对位置）。 */
  private static String stripComments(String source) {
    return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(source).replaceAll("")).replaceAll("");
  }
}
