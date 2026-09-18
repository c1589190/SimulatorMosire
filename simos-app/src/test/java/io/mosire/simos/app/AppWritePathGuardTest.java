package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * R1 扫描护栏（spec §十一 R1；计划 T8 Step 5）：{@code simos-app} 的 **main 源码不得出现存储/时间线写面**（{@code SqliteStore}
 * / {@code Timeline} / {@code CheckpointStore}）。铁律 2 的唯一写入口是 {@link
 * io.mosire.simos.core.CoreSimos#submit(io.mosire.simos.util.state.Command)}。
 *
 * <p>★ **扫描范围是 surefire 的工作目录**（模块根 {@code simos-app/}）⇒ {@code src/main/java} 在**主树与 git worktree
 * 里都成立**；不用绝对路径判隐藏段（worktree 的绝对路径含 {@code .claude}，按绝对路径过滤会把文件全滤掉、扫描 0 命中而恒绿——本项目实测过的形态）。
 *
 * <p>★ **非空自证**：断言扫到的文件数 ≥ 5 且含本任务的关键文件——"扫描器静默扫 0 个文件"在本项目是一个已知陷阱，不这样钉住的话 护栏会变成装饰。
 *
 * <p>★ **扫代码、不扫注释**（取代说明候选）：本仓的 {@code Shell} 类 javadoc 里**有意**写着"本类不持有 {@code SqliteStore} /
 * {@code Timeline.appendRevision} / {@code CheckpointStore}"——那是文档，不是写面。故本护栏只对 {@link
 * #stripComments(String) 去注释后的代码} 判串；注释里的形如 {@code // SqliteStore} 不触发，真正的引用（类型、类字面量、构造调用）
 * 触发。字符串字面量**不放行**（{@link #stripCommentsKeepsCodeAndDropsComments} 钉住这条边界）。
 */
class AppWritePathGuardTest {

  private static final Path MODULE_MAIN = Paths.get("src/main/java");

  /** 三个禁止出现的存储/时间线写面符号（spec §十一 R1）。 */
  private static final List<String> FORBIDDEN =
      List.of("SqliteStore", "Timeline", "CheckpointStore");

  @Test
  void appMainSourcesNeverReferenceTheStorageWritePath() throws IOException {
    List<Path> sources = mainSources();

    assertThat(sources)
        .as("扫描必须非空（surefire 工作目录是模块根）：扫到 0 个文件是『扫描器静默扫 0』陷阱，不是通过")
        .hasSizeGreaterThanOrEqualTo(5)
        .anyMatch(path -> path.getFileName().toString().equals("GuiServer.java"))
        .anyMatch(path -> path.getFileName().toString().equals("Shell.java"));

    for (Path source : sources) {
      String code = stripComments(read(source));
      for (String needle : FORBIDDEN) {
        assertThat(code)
            .as("%s 不得出现 %s（铁律 2：唯一写入口是 CoreSimos.submit）", source.getFileName(), needle)
            .doesNotContain(needle);
      }
    }
  }

  /** 去注释器的边界自证：注释里的串被去掉，代码（含字符串字面量）里的串保留。 */
  @Test
  void stripCommentsKeepsCodeAndDropsComments() {
    assertThat(stripComments("class X { // SqliteStore\n  int a = 1; }"))
        .doesNotContain("SqliteStore")
        .contains("int a = 1;");
    assertThat(stripComments("class X { /* Timeline */ int b = 2; }"))
        .doesNotContain("Timeline")
        .contains("int b = 2;");
    assertThat(stripComments("class X { Object c = CheckpointStore.class; }"))
        .contains("CheckpointStore");
    assertThat(stripComments("class X { String s = \"SqliteStore\"; }")).contains("SqliteStore");
  }

  private static List<Path> mainSources() throws IOException {
    Path root = MODULE_MAIN.toAbsolutePath().normalize();
    if (!Files.isDirectory(root)) {
      throw new IllegalStateException("不是目录（surefire 的工作目录应是模块根，相对 src/main/java 必在此）: " + root);
    }
    List<Path> sources = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(root)) {
      walk.filter(path -> path.toString().endsWith(".java")).sorted().forEach(sources::add);
    }
    return sources;
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * 去掉 Java 的三种注释（{@code //}、{@code /* *}{@code /}、文本块之外的引号不参与），保留代码与字符串/字符字面量。
   *
   * <p>只为护栏服务：不做词法级完备（不做 Unicode 转义判定的边角），但覆盖本仓 main 源码会用到的形态，并由 {@link
   * #stripCommentsKeepsCodeAndDropsComments} 钉住行为。
   */
  static String stripComments(String source) {
    StringBuilder out = new StringBuilder(source.length());
    int n = source.length();
    boolean lineComment = false;
    boolean blockComment = false;
    boolean string = false;
    boolean character = false;
    boolean textBlock = false;
    for (int i = 0; i < n; i++) {
      char c = source.charAt(i);
      char next = i + 1 < n ? source.charAt(i + 1) : '\0';
      if (lineComment) {
        if (c == '\n') {
          lineComment = false;
          out.append(c);
        }
      } else if (blockComment) {
        if (c == '*' && next == '/') {
          blockComment = false;
          i++;
        } else if (c == '\n') {
          out.append(c);
        }
      } else if (textBlock) {
        if (c == '"' && next == '"' && i + 2 < n && source.charAt(i + 2) == '"') {
          textBlock = false;
          i += 2;
        }
        out.append(c);
      } else if (string) {
        out.append(c);
        if (c == '\\' && i + 1 < n) {
          out.append(next);
          i++;
        } else if (c == '"') {
          string = false;
        }
      } else if (character) {
        out.append(c);
        if (c == '\\' && i + 1 < n) {
          out.append(next);
          i++;
        } else if (c == '\'') {
          character = false;
        }
      } else if (c == '/' && next == '/') {
        lineComment = true;
        i++;
      } else if (c == '/' && next == '*') {
        blockComment = true;
        i++;
      } else if (c == '"' && next == '"' && i + 2 < n && source.charAt(i + 2) == '"') {
        textBlock = true;
        out.append("\"\"\"");
        i += 2;
      } else if (c == '"') {
        string = true;
        out.append(c);
      } else if (c == '\'') {
        character = true;
        out.append(c);
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }
}
