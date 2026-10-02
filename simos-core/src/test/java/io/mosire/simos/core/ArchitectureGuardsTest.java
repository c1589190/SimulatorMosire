package io.mosire.simos.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * M4 架构护栏（spec §十一 R1）：ChangeSet 的 main 源码实现者名单被钉住。
 *
 * <p>★★ <b>扫描面 == 构建面</b>（Task 10）：本类的模块清单此前停在 M4/A3 那一刻的五个模块，而扫描是**按文件路径**做的 （{@link
 * RepoSourceScan}，与 classpath 可见性无关——{@code simos-sd} 就在 core 的 test classpath 之外却一直在扫描面里）。 ⇒
 * 此后新加的三片（{@code actor} / {@code economy} / {@code ledger}）**一直没进扫描面**，各自的 {@code ChangeSet}
 * **无人守**：漏的不是判据，是**覆盖**。现在清单与根 {@code pom.xml} 由 {@link
 * #theScannedModuleListMatchesTheOneTheBuildDeclares()} 钉成同一份，再漏就是当场红。
 */
class ArchitectureGuardsTest {

  /** 全部模块的 {@code src/main}——与构建面同源（两者相等由下面那条用例把守）。 */
  private static final List<String> MODULES =
      List.of(
          "simos-util",
          "simos-map",
          "simos-calendar",
          "simos-social",
          "simos-unit",
          "simos-core",
          "simos-sd",
          "simos-actor-api",
          "simos-actor",
          "simos-economy-api",
          "simos-economy",
          "simos-gov",
          "simos-army",
          "simos-app");

  /** 根 {@code pom.xml} 里的模块声明（构建面的权威清单）。 */
  private static final Pattern MODULE_TAG = Pattern.compile("<module>([^<]+)</module>");

  /**
   * R1：main 源码里 {@code ChangeSet} 的实现者**恰 9 个**（World / Map / Social / Unit / Sd / Actor / Economy
   * / Gov / Army）——多一个少一个都是信号： 多一个可能是有人绕过 codec 在 Core 里另造状态形态；少一个说明某个模块的变更集丢了 ChangeSet
   * 身份（Featherweight 的"契约不空转"断了一根）。
   *
   * <p>★ test 侧的 3 个玩具实现者（simos-util 的 {@code RoundTripAssertionsTest} / {@code DriftTest} 里）
   * **不计入**：它们不在 main 源码里，本扫描只枚举各模块的 {@code src/main}——这条例外写在这里， 免得下一个人以为漏扫了。
   *
   * <p>★ 用 {@code containsExactly} 而不只是 {@code hasSize(9)}：个数对"一个文件重复含串、另一个实现者 恰好漏了"零判别力（M1
   * 的形态：数个数对张冠李戴零判别力）。扫描匹配的是**连续串**——声明被 google-java-format 折行时 {@code implements}
   * 与类型名仍落在同一行（实测：MapChangeSet 即折行形态，仍命中）。
   *
   * <p>★ A3（2026-09-20）：第 5 个实现者是 SDSimos 的 {@code SdChangeSet}；方法名由 {@code ...Four...} 改为 {@code
   * ...Five...}（旧名会误导）。★ Task 10（2026-09-26）：第 6~7 个是 {@code actor}/{@code economy}
   * 两片（它们**当时就没进扫描面**， 不是新增的变更集没登记），方法名同款改为 {@code ...Seven...}。★ 阶段 10a（2026-09-30）：{@code
   * simos-ledger} 退役、 {@code simos-gov} 的 {@code GovChangeSet} 进场 ⇒ 当前恰 8 个。★ 阶段
   * D4（2026-10-02）：{@code simos-army} 的 {@code ArmyChangeSet} 进场 ⇒ 当前恰 9 个。
   */
  @Test
  void changeSetHasExactlyNineMainSourceImplementors() throws IOException {
    // ★ rawContent 声明受检异常，而 lambda（Predicate）传不出去——计划草图此处编不过，
    //   故经下面的 content() 拆包成 UncheckedIOException（与 util 侧 R15 同款执行期校正）。
    List<String> hits =
        RepoSourceScan.javaFilesUnder(scanDirs()).stream()
            .filter(p -> content(p).contains("implements ChangeSet"))
            .map(RepoSourceScan::relative)
            .sorted()
            .toList();

    assertThat(hits)
        .containsExactly(
            "simos-actor/src/main/java/io/mosire/simos/actor/change/ActorChangeSet.java",
            "simos-army/src/main/java/io/mosire/simos/army/change/ArmyChangeSet.java",
            "simos-core/src/main/java/io/mosire/simos/core/state/WorldChangeSet.java",
            "simos-economy/src/main/java/io/mosire/simos/economy/change/EconomyChangeSet.java",
            "simos-gov/src/main/java/io/mosire/simos/gov/change/GovChangeSet.java",
            "simos-map/src/main/java/io/mosire/simos/map/change/MapChangeSet.java",
            "simos-sd/src/main/java/io/mosire/simos/sd/change/SdChangeSet.java",
            "simos-social/src/main/java/io/mosire/simos/social/change/SocialChangeSet.java",
            "simos-unit/src/main/java/io/mosire/simos/unit/change/UnitChangeSet.java");
  }

  /**
   * ★★ **扫描面自己不许漂移**（Task 10 补，与 {@code EconomyVocabularyGuardTest} 的同名判据同款）：{@link #MODULES}
   * 必须**恰恰等于**根 {@code pom.xml} 声明的 {@code <module>} 集合。
   *
   * <p><b>为什么这条必须有</b>：上面那条用例的判别力**全部**寄托在扫描面上——扫描面漏一个模块，那个模块的实现者 就"不存在"
   * （不会红、不会提示），而清单是**手抄**的。实证就是本类自己：{@code actor} / {@code economy} / {@code ledger}
   * 三片切片**一直**不在扫描面里，直到 Task 10 逐模块核对才看见。
   *
   * <p>★ <b>为什么钉"等于"而不是"包含"</b>：多一个（清单里有、构建面没有 ⇒ 拼错模块名，扫描其实是空的）与少一个同样是静默 失效方向；{@code
   * containsExactlyInAnyOrderElementsOf} 两个方向一起钉，顺带把清单里的**重复项**也判红。
   */
  @Test
  void theScannedModuleListMatchesTheOneTheBuildDeclares() throws IOException {
    Set<String> declared = modulesDeclaredInRootPom();

    assertThat(declared)
        .as("★ 先证明解析器不是静默返回空（'命中 0 先怀疑自己的读取'：读取坏掉时下面那条会变成恒真）")
        .contains("simos-util", "simos-actor-api", "simos-actor", "simos-app")
        .hasSizeGreaterThanOrEqualTo(
            14); // ★ 2026-10-02：simos-calendar 新增 ⇒ 14（simos-ledger 已退役；清单与根 pom 必须同步）
    assertThat(MODULES)
        .as("★★ 扫描面必须恰恰等于根 pom 的 <module> 集合——否则下一个新模块的 ChangeSet 还会静默漏掉")
        .containsExactlyInAnyOrderElementsOf(declared);
  }

  private static String[] scanDirs() {
    return MODULES.stream().map(module -> module + "/src/main").toArray(String[]::new);
  }

  /**
   * 根 {@code pom.xml} 里声明的模块名（{@code <module>…</module>} 一行一个）。
   *
   * <p>★ <b>为什么正则够用、且失败模式已被上面那条非空断言兜住</b>：这份文件是本仓自己的、形态极简（一行一模块，注释里不含该标签）， 而正则解析 XML 的经典失效是**静默 0
   * 命中**（那会让断言恒真）——故非空 + 具名模块的断言**先**跑。
   */
  private static Set<String> modulesDeclaredInRootPom() throws IOException {
    String pom = RepoSourceScan.rawContent(RepoSourceScan.repoFile("pom.xml"));
    Set<String> modules = new TreeSet<>();
    Matcher matcher = MODULE_TAG.matcher(pom);
    while (matcher.find()) {
      modules.add(matcher.group(1).trim());
    }
    return modules;
  }

  private static String content(java.nio.file.Path file) {
    try {
      return RepoSourceScan.rawContent(file);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }
}
