package io.mosire.simos.social.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * S1/S2 模块边界验收（架构 §3.1/§3.2/§7 第 1、7 条，计划 §阶段 S4）：
 *
 * <ul>
 *   <li>{@code simos-social-api} 的 main 只 import {@code simos-map}、自己的 {@code social.api} 包与
 *       {@code com.fasterxml.jackson.annotation}（sealed 类型的线格式注解；2026-10-09 集成修复），
 *       不 import 任何领域模块、不 import {@code jackson-databind}；
 *   <li>{@code HouseholdId}/{@code PeopleLotId}/{@code Sex} 的定义在全仓 main 里恰一份（都在本模块）；
 *   <li>旧包 {@code io.mosire.simos.economy.api.id.HouseholdId/PeopleLotId} 与 {@code
 *       io.mosire.simos.social.population.Sex} 在 src/main + src/test 均 0 引用。
 * </ul>
 *
 * <p>★ 为什么是文本扫描：enforcer 管依赖坐标，管不了"源码里 import 了哪个包"；旧包的 0 引用也要跨模块看，
 * 单模块测试看不见。扫描器先断言"扫到了足够多的文件"，避免正则/路径坏掉时返回空而**假绿**。
 */
class SocialApiBoundaryTest {

  @Test
  void contractMainImportsOnlyMapAndItsOwnApiPackage() {
    List<Path> files = javaFilesUnder("simos-social-api/src/main/java");
    assertThat(files).as("扫描面不得为空（路径/正则坏掉时下面会假绿）").hasSizeGreaterThanOrEqualTo(13);

    List<String> violations = new ArrayList<>();
    for (Path file : files) {
      for (String line : lines(file)) {
        String trimmed = line.trim();
        if (trimmed.startsWith("import com.fasterxml.jackson.annotation.")) {
          continue; // sealed 类型分派注解：全局 changeset mapper 需要（见架构 §3.1 的 2026-10-09 修订）
        }
        if (trimmed.startsWith("import com.fasterxml.jackson")) {
          violations.add(relative(file) + ": 契约层只许 jackson-annotations，不许其它 Jackson（架构 §3.1）");
          continue;
        }
        if (!trimmed.startsWith("import io.mosire.simos.")) {
          continue;
        }
        if (trimmed.startsWith("import io.mosire.simos.map.")
            || trimmed.startsWith("import io.mosire.simos.social.api.")) {
          continue;
        }
        violations.add(relative(file) + ": " + trimmed);
      }
    }
    assertThat(violations)
        .as("social-api 只许依赖 map（HexCoord）＋自己的 api 包＋jackson-annotations；领域模块一律禁")
        .isEmpty();
  }

  @Test
  void idAndSexDefinitionsLiveExactlyOnceInTheContractModule() {
    assertThat(mainFilesContaining("record HouseholdId("))
        .as("HouseholdId 定义只在 simos-social-api")
        .containsExactly(
            Path.of(
                "simos-social-api/src/main/java/io/mosire/simos/social/api/id/HouseholdId.java"));
    assertThat(mainFilesContaining("record PeopleLotId("))
        .as("PeopleLotId 定义只在 simos-social-api")
        .containsExactly(
            Path.of(
                "simos-social-api/src/main/java/io/mosire/simos/social/api/id/PeopleLotId.java"));
    assertThat(mainFilesContaining("enum Sex {"))
        .as("Sex 词表定义只在 simos-social-api")
        .containsExactly(
            Path.of(
                "simos-social-api/src/main/java/io/mosire/simos/social/api/population/Sex.java"));

    assertThat(
            Files.exists(
                repoRoot()
                    .resolve(
                        "simos-economy-api/src/main/java/io/mosire/simos/economy/api/id/HouseholdId.java")))
        .as("旧 economy-api 的 HouseholdId.java 必须已删除（不留 deprecated 双轨）")
        .isFalse();
    assertThat(
            Files.exists(
                repoRoot()
                    .resolve(
                        "simos-economy-api/src/main/java/io/mosire/simos/economy/api/id/PeopleLotId.java")))
        .as("旧 economy-api 的 PeopleLotId.java 必须已删除")
        .isFalse();
  }

  @Test
  void legacyIdAndSexPackagesHaveZeroReferencesInMainAndTest() {
    // 把禁用串拆开拼：这个测试文件本身不得出现完整旧串，否则会把自己扫成命中。
    String economyApiId = String.join(".", "io", "mosire", "simos", "economy", "api", "id");
    String oldHousehold = economyApiId + "." + "HouseholdId";
    String oldLot = economyApiId + "." + "PeopleLotId";
    String oldSex = String.join(".", "io", "mosire", "simos", "social", "population") + "." + "Sex";

    List<Path> allJava = new ArrayList<>();
    for (String module : moduleNames()) {
      allJava.addAll(javaFilesUnder(module + "/src/main/java"));
      allJava.addAll(javaFilesUnder(module + "/src/test/java"));
    }
    assertThat(allJava).as("全仓 Java 源扫描面不得为空").hasSizeGreaterThanOrEqualTo(300);

    List<String> hits = new ArrayList<>();
    for (Path file : allJava) {
      // 本文件按设计要写出旧包名（否则无法表达"禁什么"）⇒ 扫描时排除自己；
      // 它本身不 import 旧包，真正的引用会被其它文件命中。
      if (file.getFileName().toString().equals("SocialApiBoundaryTest.java")) {
        continue;
      }
      String text = readString(file);
      if (text.contains(oldHousehold)) {
        hits.add(relative(file) + ": " + oldHousehold);
      }
      if (text.contains(oldLot)) {
        hits.add(relative(file) + ": " + oldLot);
      }
      if (text.contains(oldSex)) {
        hits.add(relative(file) + ": " + oldSex);
      }
    }
    assertThat(hits).as("旧 ID/Sex 包在 src/main/src/test 都是 0 引用").isEmpty();
  }

  private static List<Path> mainFilesContaining(String token) {
    List<Path> hits = new ArrayList<>();
    for (String module : moduleNames()) {
      for (Path file : javaFilesUnder(module + "/src/main/java")) {
        if (readString(file).contains(token)) {
          hits.add(Path.of(relative(file)));
        }
      }
    }
    hits.sort(Path::compareTo);
    return hits;
  }

  private static List<String> moduleNames() {
    try (Stream<Path> stream = Files.list(repoRoot())) {
      return stream
          .filter(Files::isDirectory)
          .map(path -> path.getFileName().toString())
          .filter(name -> name.startsWith("simos-"))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<Path> javaFilesUnder(String relativeDir) {
    Path dir = repoRoot().resolve(relativeDir);
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> stream = Files.walk(dir)) {
      return stream
          .filter(Files::isRegularFile)
          .filter(path -> path.toString().endsWith(".java"))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<String> lines(Path file) {
    return readString(file).lines().toList();
  }

  private static String readString(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String relative(Path file) {
    return repoRoot().relativize(file).toString();
  }

  private static Path repoRoot() {
    Path dir = Path.of("").toAbsolutePath();
    for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
      if (Files.isRegularFile(dir.resolve("pom.xml"))
          && Files.isDirectory(dir.resolve("simos-social-api"))) {
        return dir;
      }
    }
    throw new IllegalStateException(
        "找不到仓库根（从 " + Path.of("").toAbsolutePath() + " 向上找 pom.xml + simos-social-api）");
  }
}
