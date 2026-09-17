package io.mosire.simos.util.verify;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * test scope 的极简仓源扫描助手（M4 计划 Task 1 Step 6，R15 的装置）：从 surefire 的工作目录 （模块的 {@code
 * basedir}）向上找仓根，再按相对路径枚举 {@code .java} 文件。
 *
 * <p>★ 为什么放 test scope：它只服务护栏用例（R15 在 simos-util、R1 在 simos-core 各带一份， **不做共享**——test scope
 * 的类不跨模块可见，为此造一条 {@code test-jar} 依赖是真的耦合）。
 *
 * <p>★ 只提供扫描能力，**不写任何被扫的串**——扫描护栏自己的源文件必须对 needle 呈中性， 否则护栏恒红或恒绿。
 */
final class RepoSourceScan {

  private static final Path REPO_ROOT = findRepoRoot();

  private RepoSourceScan() {}

  /**
   * 枚举仓根下若干相对目录里的全部 {@code .java} 文件（升序、去重由调用方保证传参不重叠）。
   *
   * <p>★ 跳过 {@code target/} 与隐藏目录（{@code .git}、{@code .superpowers} 等）：前者有生成源码的副本，
   * 后者不是构建输入——扫进去只会制造假命中。
   */
  static List<Path> javaFilesUnder(String... relativeDirs) throws IOException {
    List<Path> files = new ArrayList<>();
    for (String relativeDir : relativeDirs) {
      // ★ normalize() 是必须的：resolve(".") 会留下 "." 这个名字元素，而 isScannableJava 的隐藏目录
      // 过滤（startsWith(".")）会把**所有**文件滤掉——R15 就成了恒绿的装饰（m3 轮实测：stub 在源里、
      // 扫描 0 命中）。归一化后 "." 被折掉，其余相对路径不受影响。
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

  /** 相对仓根的路径（报错信息与断言消息用它，不暴露绝对路径）。 */
  static String relative(Path file) {
    return REPO_ROOT.relativize(file).toString();
  }

  /**
   * ★ 只对**被扫目录之下**的相对部分做过滤，不碰绝对路径前缀。本仓在 git worktree 里构建时绝对路径会带上 {@code .claude/worktrees/…}
   * 这样的隐藏段——若按绝对路径的每个名字元素判 {@code startsWith(".")}，会把**所有** 文件滤掉、扫描 0 命中，护栏反而恒绿（同型缺陷先在 simos-core 的
   * R1 装置上实测到，本文件是后补的同款修复）。
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
   * ★ 判"仓根"的口径：这个 pom 自己**就是** simos-parent（自身 artifactId 是它、且没有 {@code <parent>} 段），而不是引用它的模块
   * pom。计划原文写"找到**含 simos-parent 的** pom.xml"——模块 pom 的 {@code <parent>} 段同样含这个串，从模块目录向上走会停在**模块
   * pom** 上，R15 就只扫到一个模块， "全仓 0 处"成了假绿。故按本口径落地（执行期校正，理由如上）。
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
