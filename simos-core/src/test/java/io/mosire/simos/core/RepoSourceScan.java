package io.mosire.simos.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * test scope 的极简仓源扫描助手（M4 计划 Task 4 Step 4，R1 的装置）：从 surefire 的工作目录（模块的 {@code
 * basedir}）向上找仓根，再按相对路径枚举 {@code .java} 文件。
 *
 * <p>★ 为什么 core 侧**自带一份**、不复用 simos-util 的同名类：test scope 的类不跨模块可见，为此造一条 {@code test-jar}
 * 依赖是真的耦合（core 的测试会依赖 util 的测试产物），代价大于 20 行重复（M4 计划"任务地图" 节的两条取舍；M2 的 {@code RegressionGuardsTest}
 * 已是独立先例）。
 *
 * <p>★ 只提供扫描能力，**不写任何被扫的串**——扫描护栏自己的源文件必须对 needle 呈中性，否则护栏恒红或恒绿。
 */
final class RepoSourceScan {

  private static final Path REPO_ROOT = findRepoRoot();

  private RepoSourceScan() {}

  /**
   * 枚举仓根下若干相对目录里的全部 {@code .java} 文件（升序、去重由调用方保证传参不重叠）。
   *
   * <p>★ 跳过 {@code target/} 与隐藏目录（{@code .git}、{@code .superpowers} 等）：前者有生成源码的副本，
   * 后者不是构建输入——扫进去只会制造假命中。★ {@code normalize()} 是必须的：{@code resolve(".")} 会留下 "."
   * 这个名字元素，而隐藏目录过滤（{@code startsWith(".")}）会把**所有**文件滤掉（R15 那份 m3 轮实测）。
   */
  static List<Path> javaFilesUnder(String... relativeDirs) throws IOException {
    List<Path> files = new ArrayList<>();
    for (String relativeDir : relativeDirs) {
      Path dir = REPO_ROOT.resolve(relativeDir).normalize();
      if (!Files.isDirectory(dir)) {
        throw new IllegalArgumentException("不是目录（或不存在）：" + dir);
      }
      try (Stream<Path> stream = Files.walk(dir)) {
        stream.filter(path -> isScannableJava(dir, path)).sorted().forEach(files::add);
      }
    }
    return files;
  }

  /** 原样读全文——扫描护栏要的是逐字节内容，不做任何归一化。 */
  static String rawContent(Path file) throws IOException {
    return Files.readString(file);
  }

  /**
   * 仓根下的一个文件（护栏要读**清单本体**时用，例如根 {@code pom.xml} 的 {@code <module>} 集合）。
   *
   * <p>★ **仍然对 needle 中性**：本方法只报路径、不读内容、不判任何串——"扫描护栏自己的源文件必须对 needle 呈中性"这条不变。
   * 之前没有它时，护栏只能把"本仓有哪些模块"**手抄一份**，而手抄的那份**没有任何东西钉住它**（Task 10 的病灶，与 simos-util 的同款）。
   */
  static Path repoFile(String relativePath) {
    return REPO_ROOT.resolve(relativePath).normalize();
  }

  /** 相对仓根的路径（报错信息与断言消息用它，不暴露绝对路径）。 */
  static String relative(Path file) {
    return REPO_ROOT.relativize(file).toString();
  }

  /**
   * ★ 只对**被扫目录之下**的相对部分做过滤，不碰绝对路径前缀。本仓在 git worktree 里构建时绝对路径会带上 {@code .claude/worktrees/…}
   * 这样的隐藏段——若按绝对路径的每个名字元素判 {@code startsWith(".")}，会把**所有** 文件滤掉、扫描 0 命中，护栏反而恒绿（R1 首跑实测：actual=[]，4
   * 个实现者一个都没扫到）。
   */
  private static boolean isScannableJava(Path rootDir, Path path) {
    if (!path.toString().endsWith(".java")) {
      return false;
    }
    for (Path part : rootDir.relativize(path)) {
      String segment = part.toString();
      if (segment.equals("target") || segment.startsWith(".")) {
        return false;
      }
    }
    return true;
  }

  private static Path findRepoRoot() {
    Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    while (dir != null) {
      Path pom = dir.resolve("pom.xml");
      if (Files.isRegularFile(pom) && isParentPom(pom)) {
        return dir;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException(
        "从 " + System.getProperty("user.dir") + " 向上没找到 simos-parent 本尊的 pom.xml（仓根）");
  }

  /**
   * ★ 判"仓根"的口径：这个 pom 自己**就是** simos-parent（自身 artifactId 是它、且没有 {@code <parent>} 段）， 而不是引用它的模块
   * pom——模块 pom 的 {@code <parent>} 段同样含 {@code simos-parent} 这个串，从模块目录向上走 会停在模块 pom
   * 上，扫描就只覆盖一个模块（util 侧 R15 轮的执行期校正，此处同口径落地）。
   */
  private static boolean isParentPom(Path pom) {
    try {
      String content = Files.readString(pom);
      return content.contains("<artifactId>simos-parent</artifactId>")
          && !content.contains("<parent>");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
