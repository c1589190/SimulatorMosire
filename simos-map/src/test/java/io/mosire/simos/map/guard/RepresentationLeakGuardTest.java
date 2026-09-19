package io.mosire.simos.map.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★ P1 的**表示法不泄漏**护栏（spec §七.4）：{@code simos-map} 之外**没有任何**代码直读 {@code HexCell.terrain} ——地形只能经
 * {@code GameMap.terrainAt(hex)}（或 {@code terrainIndex()}）拿。
 *
 * <p>病灶形态：块是 map 模块的**内部表示**；若下游（unit/app/core）继续写 {@code cell.terrain()} 或 {@code
 * HexCell::terrain}，一旦块/逐格的形状再变，它们就会**静默**对着旧形状编译不过或语义漂移。grep 断言是这条唯一验得了的形态。
 *
 * <p>★ **故意违规自证**（形态 1）：{@link #theScannerActuallyCatchesAPlant()} 往临时目录写一处直读，断言扫描器真的报出来 ——
 * 没有它，扫描路径写错会表现为"零命中恒绿"。
 */
class RepresentationLeakGuardTest {

  private static final List<String> FORBIDDEN = List.of(".terrain()", "HexCell::terrain");

  @Test
  void noDirectTerrainReadsOutsideSimosMap() {
    List<Path> files = new ArrayList<>();
    for (String module :
        List.of("simos-util", "simos-social", "simos-unit", "simos-core", "simos-app")) {
      files.addAll(javaFilesUnder(repoRoot().resolve(module + "/src/main")));
      files.addAll(javaFilesUnder(repoRoot().resolve(module + "/src/test")));
    }
    assertThat(files).as("被扫描的源文件数 > 0（空 = 路径写错，护栏恒真）").isNotEmpty();

    List<String> hits = findForbiddenReads(files);
    assertThat(hits).as("simos-map 之外不许直读 HexCell.terrain（地形走 GameMap.terrainAt）").isEmpty();
  }

  /** ★ 故意违规：扫描器对一处 {@code cell.terrain()} 必须报出来（否则它是装饰）。 */
  @Test
  void theScannerActuallyCatchesAPlant(@TempDir Path tempDir) throws IOException {
    Path plant = tempDir.resolve("Plant.java");
    Files.writeString(
        plant,
        "package p;\n"
            + "class Plant {\n"
            + "  String f(Object cell) { return cell.terrain(); }\n"
            + "}\n");

    List<String> hits = findForbiddenReads(List.of(plant));

    assertThat(hits).as("扫描器必须报出这处故意违规").hasSize(1);
    assertThat(hits.get(0)).contains("Plant.java").contains("terrain()");
  }

  /** 命中 = 仓库相对路径 + 行号 + 行内容；注释行剔除（注释里谈形状不是直读）。 */
  private static List<String> findForbiddenReads(List<Path> files) {
    List<String> hits = new ArrayList<>();
    for (Path file : files) {
      List<String> lines;
      try {
        lines = Files.readAllLines(file);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i);
        if (isCommentLine(line)) {
          continue;
        }
        for (String token : FORBIDDEN) {
          if (line.contains(token)) {
            hits.add(file + ":" + (i + 1) + ": " + line.strip());
            break;
          }
        }
      }
    }
    return hits;
  }

  private static boolean isCommentLine(String line) {
    String t = line.stripLeading();
    return t.startsWith("*") || t.startsWith("/*") || t.startsWith("*/") || t.startsWith("//");
  }

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
    return fail("找不到 simos-parent 仓库根（从 " + start + " 向上）：表示法泄漏扫描无法进行");
  }

  /** 认**自己的** artifactId 是 {@code simos-parent} 的 pom（模块 pom 的 parent 块里也有这个名字）。 */
  private static boolean isRepoRootPom(String pomText) {
    int own = pomText.indexOf("<artifactId>simos-parent</artifactId>");
    int parentEnd = pomText.indexOf("</parent>");
    return own >= 0 && own > parentEnd;
  }

  private static List<Path> javaFilesUnder(Path dir) {
    try (Stream<Path> stream = Files.walk(dir)) {
      return stream.filter(p -> p.toString().endsWith(".java")).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
