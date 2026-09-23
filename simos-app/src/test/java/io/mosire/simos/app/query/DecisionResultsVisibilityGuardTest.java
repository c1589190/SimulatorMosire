package io.mosire.simos.app.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ★★ **决策结果的"归属"可见性判据只许有一份实现**（第 3 波第 3 步的结构性护栏）。
 *
 * <p>判据本体 = 「INFO 条目的 {@code tags} 含调用者自己」——它是"这条结果与谁有关"的**唯一**表述，实现在 {@link
 * RedactingQueryService#decisionResults}。本类用**源码扫描**钉住"没有第二份"。
 *
 * <p>★
 * **为什么反射不够、必须扫源码**：反射只能证明"当前这个类长这样"，证明不了"别处没有另一份"。而"顺手在工具里再筛一道"这种第二份实现**不会报错、也不会红任何行为用例**——两份会各自显得自洽、慢慢漂移，正是
 * {@code RedactingQueryService} 类注里反复记的那一类。先例：{@code
 * RegressionGuardsTest.R1_thereIsExactlyOneFieldDelta} （同形的"全仓恰一份"扫描）。
 *
 * <p>★ **扫描底座**：从 surefire 工作目录（模块根 {@code simos-app/}）向上找 {@code artifactId == simos-parent} 的
 * {@code pom.xml}（**找不到就 fail**——默默跳过就是恒真的假护栏）；只读各模块的 {@code src/main}（不碰 {@code target}），
 * 只数**非注释行**；并带"扫到的文件数 &gt; 0 且含关键文件"的自证，防路径写错导致零命中恒真。
 *
 * <p>★
 * **诚实边界**：判据是**逐行子串**匹配，故"把表达式拆到两行"或"用完全不同的写法实现同一条规则"它抓不到；它抓的是本仓既有形态里最可能出现的那个复制动作（照抄那一行）。这条边界写在这里，不假装护栏是完备的。
 */
class DecisionResultsVisibilityGuardTest {

  /**
   * 归属判据的**代码形态**（{@code SdInfoEntry} 的 tags 成员判定）。全仓 {@code src/main} 里只许出现**一次**，且落在 {@link
   * RedactingQueryService}。
   */
  private static final String OWNERSHIP_TOKEN = "tags().contains(";

  /**
   * **文档可见性的第二轴**（Docs，2026-09-23）的代码形态：{@code SdInfoEntry.affiliations} 的成员判定。同样**只许一处**， 同样落在
   * {@link RedactingQueryService}（{@code docs} 走的那个 {@code visible} 私有方法）。
   *
   * <p>★ 与 {@link #OWNERSHIP_TOKEN} 是**一条规则的两支**：文档可见 = tags 命中 ∪ 归属命中（并集）。两支都只此一处 ⇒
   * 决策结果子页/文档子页/两个读工具拿到的可见集合不可能漂移。
   */
  private static final String AFFILIATION_TOKEN = "affiliations().contains(";

  /** 唯一合法落点（FQN → 仓库相对路径）。 */
  private static final String RULE_OWNER =
      "simos-app/src/main/java/io/mosire/simos/app/query/RedactingQueryService.java";

  @Test
  void theTagsOwnershipRuleIsImplementedExactlyOnceAndOnlyInRedactingQueryService() {
    List<Path> sources = allMainSources();

    assertThat(sources)
        .as("扫描必须非空且含关键文件（扫到 0 个 = 路径写错，护栏恒真）")
        .hasSizeGreaterThanOrEqualTo(20)
        .anyMatch(path -> path.getFileName().toString().equals("RedactingQueryService.java"))
        .anyMatch(path -> path.getFileName().toString().equals("DecisionResultsTool.java"));

    assertThat(occurrences(sources, OWNERSHIP_TOKEN))
        .as(
            "★ 归属判据（%s）全仓 src/main 恰一份，且只在 RedactingQueryService——"
                + "工具（如 DecisionResultsTool）或任何别处不得再筛一道（第二份不会报错、只会漂移）",
            OWNERSHIP_TOKEN)
        .containsExactly(entry(RULE_OWNER, 1L));
  }

  /**
   * ★★ **文档可见性的"第二轴"（归属）同样只许有一份实现**（Docs，2026-09-23）。
   *
   * <p>判据本体 = 「INFO 条目的 {@code affiliations} 含调用者自己的归属」，规则 = {@code tags 命中 ∪ 归属命中}。实现在 {@link
   * RedactingQueryService#docs}。与上面那条同源的理由：GUI 的文档子页与决策人读工具都走同一个方法，各写一份时两边都不报错、只会漂移。
   */
  @Test
  void theAffiliationsVisibilityRuleIsImplementedExactlyOnceAndOnlyInRedactingQueryService() {
    List<Path> sources = allMainSources();

    assertThat(sources)
        .as("扫描必须非空且含关键文件（扫到 0 个 = 路径写错，护栏恒真）")
        .hasSizeGreaterThanOrEqualTo(20)
        .anyMatch(path -> path.getFileName().toString().equals("RedactingQueryService.java"));

    assertThat(occurrences(sources, AFFILIATION_TOKEN))
        .as(
            "★ 归属轴判据（%s）全仓 src/main 恰一份，且只在 RedactingQueryService——" + "工具或别处不得再筛一道（第二份不会报错、只会漂移）",
            AFFILIATION_TOKEN)
        .containsExactly(entry(RULE_OWNER, 1L));
  }

  /** 逐文件数某 token 的**非注释行**出现次数（只保留命中过的文件，便于 {@code containsExactly} 比全貌）。 */
  private static Map<String, Long> occurrences(List<Path> sources, String token) {
    Map<String, Long> hits = new LinkedHashMap<>();
    for (Path file : sources) {
      long count = 0;
      for (String line : fileLines(file)) {
        if (isCommentLine(line)) {
          continue;
        }
        for (int i = line.indexOf(token); i >= 0; i = line.indexOf(token, i + 1)) {
          count++;
        }
      }
      if (count > 0) {
        hits.put(relative(file), count);
      }
    }
    return hits;
  }

  // ── 源码扫描的底座 ────────────────────────────────────────────────────────────

  /** 全仓各模块 {@code src/main} 下的全部 {@code .java}（不进入 {@code target}）。 */
  private static List<Path> allMainSources() {
    List<Path> sources = new ArrayList<>();
    try (Stream<Path> modules = Files.list(repoRoot())) {
      for (Path module : modules.filter(Files::isDirectory).sorted().toList()) {
        if (!Files.isRegularFile(module.resolve("pom.xml"))) {
          continue;
        }
        Path main = module.resolve("src/main");
        if (!Files.isDirectory(main)) {
          continue;
        }
        try (Stream<Path> walk = Files.walk(main)) {
          walk.filter(path -> path.toString().endsWith(".java")).sorted().forEach(sources::add);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return sources;
  }

  /** surefire 工作目录是模块根，仓库根要向上找；**找不到就 fail**（默默跳过 = 恒真）。 */
  private static Path repoRoot() {
    Path start = Path.of("").toAbsolutePath();
    for (Path dir = start; dir != null; dir = dir.getParent()) {
      Path pom = dir.resolve("pom.xml");
      if (!Files.isRegularFile(pom)) {
        continue;
      }
      try {
        if (isRepoRootPom(Files.readString(pom))) {
          return dir;
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    throw new AssertionError("找不到 simos-parent 仓库根（从 " + start + " 向上）：源码扫描无法进行");
  }

  /**
   * 只认**自己的** artifactId 是 {@code simos-parent} 的 pom——模块 pom 的 {@code <parent>}
   * 块里也有这个名字，不区分就会把模块根误判成仓库根。
   */
  private static boolean isRepoRootPom(String pomText) {
    int own = pomText.indexOf("<artifactId>simos-parent</artifactId>");
    int parentEnd = pomText.indexOf("</parent>");
    return own >= 0 && own > parentEnd;
  }

  private static List<String> fileLines(Path file) {
    try {
      return Files.readAllLines(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** 行级去注释（与 {@code RegressionGuardsTest} 同一形状）：以注释标记开头的行整行剔除。 */
  private static boolean isCommentLine(String line) {
    String trimmed = line.stripLeading();
    return trimmed.startsWith("//")
        || trimmed.startsWith("*")
        || trimmed.startsWith("/*")
        || trimmed.startsWith("*/");
  }

  private static String relative(Path file) {
    return repoRoot().relativize(file.toAbsolutePath()).toString().replace('\\', '/');
  }
}
