package io.mosire.simos.ga.spi;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * ★★ <b>判据 D：本层的"名字/清单"型常量只有<b>一处</b>声明 —— 源码扫描</b>（形制照 {@code DecisionPacketToolGateScanTest} /
 * {@code ProposalCatalogPlanOnlyScanTest}）。
 *
 * <h2>它守的是哪种坏法</h2>
 *
 * <p>行为用例只能证明"这一条路径上用到的串是对的"；而<b>唯一拼写点</b>要守的恰恰是"别处又写了一份"——那一份<b>今天可能恰好
 * 相同</b>（所以行为全绿），明天改名时它不会跟着动。⇒ 只有把<b>源码文本</b>当判据。
 *
 * <p>三条判据：
 *
 * <ol>
 *   <li>{@code ToolResources} 的命名空间与 {@code GM_ID} 的<b>字面量</b>在本层各只出现<b>一次</b> （{@code
 *       "map"/"social"/"unit"/"ga"/"economy"/"actor"/"army"/"peer"/"gm"/"main"}）——多一处就是第二处声明；
 *   <li><b>旧名 {@code "sd"} 一次都不出现</b>（2026-10-14 批次 F 改名的负向）；
 *   <li>路径语法（{@code /region/} / {@code /hex/} / {@code _}）在本层<b>不被重拼</b>（唯一出处是 util 的 {@code
 *       ResourcePaths}），以及<b>四个端口在本层没有实现</b>（契约层长出实现 = 把 core/领域拖进来，破 I-G2）。
 * </ol>
 *
 * <h2>★★ 扫描器自证（本仓今天实测过三个"静默 0"变种，故 0 命中必须先证"扫得到"）</h2>
 *
 * <p>本类用<b>文件名清单</b>而不是 {@code git grep}（后者默认不搜未跟踪文件；本批新增的正是未跟踪文件）、也不用通配 pathspec。 扫描面 =
 * 目录列举（{@link Files#list}），并逐条自证：
 *
 * <ol>
 *   <li>扫到的文件数 = <b>实测值 13</b>（DecisionProposable 随设计 §7.3 退役 ⇒ 14 → 13），且含已知样本（{@code
 *       ToolResources.java} 等）⇒ 扫描面非空；
 *   <li>同一管线在<b>剥注释前</b>比<b>剥注释后</b>多命中一次 {@code "actor"}（javadoc 里那句 {@code {@code "actor"}}）⇒
 *       既证明"真的读到了内容"，也证明"剥注释真的在剥"；若剥注释失效，两处都是 2，第一条断言当场红；
 *   <li>换字面量（{@code "committed"}）、换形状（{@code implements SimosTool}）各有一个<b>已知命中</b>的样本 ⇒ 后面那些"恰好一次 /
 *       零次"的断言不是空转。
 * </ol>
 */
class SpiSpellingPointScanTest {

  /** 本模块主源根（surefire 的工作目录 = 模块目录 {@code simos-ga-spi/}）。 */
  private static final Path MAIN_JAVA =
      Paths.get("src", "main", "java", "io", "mosire", "simos", "ga", "spi");

  /** 扫描面条数（实测值；主源被清空/挪走 ⇒ 本扫描器当场红，不会静默空转）。 */
  private static final int MAIN_SOURCE_COUNT = 13;

  /** 唯一拼写点字面量：八个命名空间 + GM_ID + 缺省分支。 */
  private static final List<String> SINGLE_SPELLING_LITERALS =
      List.of("map", "social", "unit", "ga", "economy", "actor", "army", "peer", "gm", "main");

  /** 路径语法的固定段（唯一出处是 {@code simos-util} 的 {@code ResourcePaths}）。 */
  private static final List<String> PATH_SYNTAX_PIECES = List.of("/region/", "/hex/", "_");

  /** 端口：实现必须在装配侧（app / ga），不在契约层。 */
  private static final List<String> PORTS =
      List.of(
          "ToolReadPort",
          "ToolWritePort",
          "DecisionMakerExistencePort",
          "DecisionMakerAffiliationPort");

  private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
  private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");
  private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

  // ────────────────────────── ① 唯一拼写点 ──────────────────────────

  /**
   * ★★ <b>判据 A + D：命名空间与 {@code GM_ID} 的字面量在本层各只有一处，且都在 {@code ToolResources.java}</b>。
   *
   * <p>守的坏法：{@code ToolResources} 的常量注写着它们是"全仓唯一拼写点，出现在三处，全部引用本常量、<b>不抄字面量</b>"。 谁在别的类里再抄一份 {@code
   * "peer"} / {@code "gm"}，两份今天相同、改名时静默分叉——而权限判定按命名空间表态， 分叉的后果是"那一维永远够不着"或"表态落在没人用的键上"。
   */
  @Test
  void theNamespaceAndGmIdLiteralsAreSpelledExactlyOnceInThisLayer() throws IOException {
    Map<String, String> raw = mainSources();
    assertThat(raw.keySet())
        .as("★ 扫描器自证：扫描面必须是实测的 %d 个主源文件，且含已知样本", MAIN_SOURCE_COUNT)
        .hasSize(MAIN_SOURCE_COUNT)
        .contains(
            "ToolResources.java",
            "ToolResults.java",
            "ToolWritePort.java",
            "SimosToolAdapter.java");

    Map<String, String> code = stripComments(raw);

    // ★ 扫描器自证：同一管线"剥注释前 2 次 / 剥注释后 1 次"（javadoc 里那句 {@code "actor"} 是注释，不是声明）
    assertThat(countLiteral(raw, "actor"))
        .as("★ 扫描器自证：原始文本里 \"actor\" 出现两次（javadoc 一处 + 常量一处）⇒ 管线确实读到了内容")
        .isEqualTo(2);
    assertThat(countLiteral(code, "actor"))
        .as("★ 扫描器自证：剥注释后恰好一次 ⇒ 剥注释真的在剥（否则 javadoc 的引用会被当成第二处声明）")
        .isEqualTo(1);
    // ★ 扫描器自证：换字面量、换文件，同一管线照样命中
    assertThat(filesWithLiteral(code, "committed"))
        .as("★ 扫描器自证：提取器在另一个文件上也命中（\"committed\" 只在 ToolResults）")
        .containsExactly("ToolResults.java");

    for (String literal : SINGLE_SPELLING_LITERALS) {
      assertThat(countLiteral(code, literal))
          .as("★★ 判据 D：字面量 \"%s\" 在本层只能出现一次（第二处声明会静默漂移）", literal)
          .isEqualTo(1);
      assertThat(filesWithLiteral(code, literal))
          .as("★★ 判据 D：\"%s\" 的唯一一处必须在 ToolResources.java（别处抄一份 = 第二处拼写点）", literal)
          .containsExactly("ToolResources.java");
    }
  }

  /**
   * ★★ <b>判据 A 的负向（源码侧）：旧名 {@code sd} 作为字面量一次都不出现</b>（2026-10-14 批次 F）。
   *
   * <p>★ 这条与 {@code ToolResourcesSpellingTest} 的行为断言是一对：那边证"产出的串不是 {@code sd:…}"， 这边证"本层根本没有 {@code
   * "sd"} 这个字面量"（未来的新助手也抄不到旧名）。
   */
  @Test
  void theRetiredSdNameIsNotALiteralAnywhereInThisLayer() throws IOException {
    Map<String, String> raw = mainSources();
    Map<String, String> code = stripComments(raw);

    assertThat(countLiteral(code, "ga"))
        .as("★ 扫描器自证：同一条管线在本扫描面上确实能命中字面量（否则下面的 0 命中是假的）")
        .isEqualTo(1);
    assertThat(countLiteral(code, "sd"))
        .as("★★ 判据 A 负向：旧名 sd 不得作为字面量出现在本层（改名留尾巴 = 权限围栏与资源断言各说各话）")
        .isZero();
    assertThat(countLiteral(raw, "sd")).as("★★ 判据 A 负向：连注释里也没有（本层不该再教人用旧名）").isZero();
  }

  // ────────────────────────── ② 路径语法与端口的唯一出处 ──────────────────────────

  /**
   * ★★ <b>判据 D：路径语法（{@code /region/} / {@code /hex/} / {@code _}）在本层不被重拼</b>。
   *
   * <p>守的坏法：{@code ResourcePaths} 的类注写着"这些字符串是权限围栏的输入，拼错一段不会抛任何异常，只会让该看见的看不见" ⇒
   * 语法必须有<b>一个</b>三方都够得着的出处（util）。本层只做"把路径包成 {@code ResourceId}"，一旦有人在这里手拼 （{@code mapId + "/hex/"
   * + q + "_" + r}），就多了一处会静默漂移的拼写点。
   */
  @Test
  void thePathSyntaxIsNotRespunInThisLayer() throws IOException {
    Map<String, String> code = stripComments(mainSources());

    assertThat(countLiteral(code, "ga")).as("★ 扫描器自证：管线能命中字面量（0 命中断言的前提）").isEqualTo(1);
    for (String piece : PATH_SYNTAX_PIECES) {
      assertThat(countLiteral(code, piece))
          .as("★★ 判据 D：路径段 \"%s\" 不得在本层被重拼（唯一出处 = util 的 ResourcePaths）", piece)
          .isZero();
    }
  }

  /**
   * ★★ <b>判据 C：四个端口在本层<b>没有实现</b>（契约层只装契约）</b>。
   *
   * <p>守的坏法：{@code ToolWritePort} 的类注写着"本文件里<b>没有</b>任何实现"——实现属 B2 的 {@code simos-ga} （它才允许依赖
   * core）。在这里落地一份实现，等于把 core 类型（{@code CommandEnvelope}/{@code CommandResult}…） 拖进本模块 ⇒ {@code map
   * → ga-spi → core → map(test)} <b>当场成环</b>（Maven 建图期直接拒）， 而enforcer 只看 pom、看不见"多了一个实现类"。
   */
  @Test
  void noPortIsImplementedInsideTheContractLayer() throws IOException {
    Map<String, String> code = stripComments(mainSources());

    // ★ 扫描器自证："implements X" 这个形状在本扫描面上扫得到（已知样本 = SimosToolAdapter）
    assertThat(filesMatching(code, implementsOrExtends("SimosTool")))
        .as("★ 扫描器自证：`implements X` 在这条管线上确实能命中（否则下面的 0 命中 = 假绿）")
        .containsExactly("SimosToolAdapter.java");

    for (String port : PORTS) {
      assertThat(filesMatching(code, implementsOrExtends(port)))
          .as("★★ 判据 C：%s 的实现不许住在契约层（实现在装配侧；这里落地会把 core 拖进来，I-G2 成环）", port)
          .isEmpty();
    }
  }

  // ────────────────────────── 扫描小件 ──────────────────────────

  /** 本模块主源（文件名 → 文本）。★ 先断言扫描根存在——否则"扫到 0 条"会伪装成通过。 */
  private static Map<String, String> mainSources() throws IOException {
    assertThat(Files.isDirectory(MAIN_JAVA))
        .as("★ 扫描根必须存在（surefire 工作目录 = 模块目录）：%s", MAIN_JAVA.toAbsolutePath())
        .isTrue();
    Map<String, String> sources = new LinkedHashMap<>();
    try (Stream<Path> files = Files.list(MAIN_JAVA)) {
      for (Path file :
          files.filter(path -> path.getFileName().toString().endsWith(".java")).sorted().toList()) {
        sources.put(file.getFileName().toString(), Files.readString(file));
      }
    }
    System.out.println(
        "[SCAN] root="
            + MAIN_JAVA.toAbsolutePath()
            + " files="
            + sources.size()
            + " "
            + sources.keySet());
    return sources;
  }

  /**
   * 剥掉块注释与行注释。
   *
   * <p>★ 必须剥：本仓的类注里大量出现 {@code {@code "actor"}} 这类<b>引用</b>（javadoc 里的字面量），不剥就会
   * 把它们当成"第二处声明"（假红）；反过来，剥注释若失效，{@link
   * #theNamespaceAndGmIdLiteralsAreSpelledExactlyOnceInThisLayer} 的第一条自证断言（剥前 2 / 剥后 1）当场红。
   */
  private static Map<String, String> stripComments(Map<String, String> sources) {
    Map<String, String> stripped = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : sources.entrySet()) {
      stripped.put(
          entry.getKey(),
          LINE_COMMENT
              .matcher(BLOCK_COMMENT.matcher(entry.getValue()).replaceAll(""))
              .replaceAll(""));
    }
    return stripped;
  }

  /** 整份扫描面上"内容恰好等于 literal"的字符串字面量出现次数。 */
  private static int countLiteral(Map<String, String> sources, String literal) {
    int total = 0;
    for (String text : sources.values()) {
      total += countLiteral(text, literal);
    }
    return total;
  }

  /** 一份文本里"内容恰好等于 literal"的字符串字面量出现次数。 */
  private static int countLiteral(String text, String literal) {
    Matcher matcher = STRING_LITERAL.matcher(text);
    int count = 0;
    while (matcher.find()) {
      if (literal.equals(matcher.group(1))) {
        count++;
      }
    }
    return count;
  }

  /** 哪些文件里出现了该字面量（用于"唯一一处 / 零处"的定位）。 */
  private static List<String> filesWithLiteral(Map<String, String> sources, String literal) {
    List<String> hits = new ArrayList<>();
    for (Map.Entry<String, String> entry : sources.entrySet()) {
      if (countLiteral(entry.getValue(), literal) > 0) {
        hits.add(entry.getKey());
      }
    }
    return hits;
  }

  /** 哪些文件里有 {@code implements/extends <type>}（用于"零实现"的定位）。 */
  private static List<String> filesMatching(Map<String, String> sources, Pattern pattern) {
    List<String> hits = new ArrayList<>();
    for (Map.Entry<String, String> entry : sources.entrySet()) {
      if (pattern.matcher(entry.getValue()).find()) {
        hits.add(entry.getKey());
      }
    }
    return hits;
  }

  /** {@code implements/extends <type>} 的形状（类型名前后加词边界；{} / ; 之前不跨行外推）。 */
  private static Pattern implementsOrExtends(String type) {
    return Pattern.compile("(?:implements|extends)\\s+[^{;]*\\b" + Pattern.quote(type) + "\\b");
  }
}
