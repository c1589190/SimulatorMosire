package io.mosire.simos.culture;

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
 * ★★ <b>culture 的人口写口边界守卫</b>（D-031 spec §11 V3 N3；铁律 3）。
 *
 * <p>N3「culture 试图写人口 ⇒ 不可达/测试红」的可执行形态：culture 只许看见 {@code simos-social-api} 的稳定 {@code
 * HouseholdId}，<b>不许</b> import social 实现包、不许出现任何人口写口类型（{@code SocialChangeSet} / {@code
 * SocialData} / 各人口 handler）。结构上写不了人口，而不是"约定不写"。
 */
class CultureBoundaryGuardTest {

  private static final Path CULTURE_MAIN = Path.of("src", "main", "java");
  private static final Path CULTURE_POM = Path.of("pom.xml");

  /** 社会实现包里的类型名（出现在 culture main 即视为写人口）。 */
  private static final List<String> FORBIDDEN_SOCIAL_TOKENS =
      List.of(
          "io.mosire.simos.social.change.SocialChangeSet",
          "io.mosire.simos.social.SocialData",
          "io.mosire.simos.social.SocialSnapshot",
          "io.mosire.simos.social.household.Household",
          "MovePopulationLotsHandler",
          "AdjustHouseholdPopulationHandler",
          "AddHouseholdMembersHandler",
          "RemoveHouseholdMembersHandler",
          "TransferHouseholdMembersHandler",
          "SetHouseholdLocationHandler",
          "SubmitHouseholdWorkOrderHandler");

  @Test
  void cultureMainNeverImportsTheSocialImplementationPackage() {
    List<Path> files = javaFilesUnder(CULTURE_MAIN);
    assertThat(files).as("★ 先证明扫描不是静默返回空").hasSizeGreaterThan(15);

    List<String> violations = new ArrayList<>();
    for (Path file : files) {
      for (String line : readLines(file)) {
        String trimmed = line.strip();
        if (!trimmed.startsWith("import ")) {
          continue;
        }
        if (trimmed.contains("io.mosire.simos.social.")
            && !trimmed.contains("io.mosire.simos.social.api.")) {
          violations.add(file + " :: " + trimmed);
        }
      }
    }
    assertThat(violations)
        .as("culture 只许依赖 social-api（HouseholdId），不得 import social 实现（N3）")
        .isEmpty();
  }

  @Test
  void cultureMainContainsNoPopulationWriteTypeReferences() {
    List<Path> files = javaFilesUnder(CULTURE_MAIN);
    List<String> hits = new ArrayList<>();
    for (Path file : files) {
      String text = readText(file);
      for (String token : FORBIDDEN_SOCIAL_TOKENS) {
        if (text.contains(token)) {
          hits.add(file + " :: " + token);
        }
      }
    }
    assertThat(hits).as("culture 不得引用任何人口写口/实现类型").isEmpty();
  }

  @Test
  void culturePomDoesNotDeclareTheSocialImplementationDependency() {
    String pom = readText(CULTURE_POM);
    assertThat(pom)
        .as("先证明读取不是静默落空（否则下面的否定断言恒真）")
        .contains("simos-social-api")
        .contains("simos-production");
    assertThat(pom)
        .as("culture 不声明 simos-social 实现依赖（social 实现仍在 enforcer 禁列）")
        .doesNotContain("<artifactId>simos-social</artifactId>");
  }

  // ── 扫描小工具 ────────────────────────────────────────────────────────────────────

  private static List<Path> javaFilesUnder(Path root) {
    try (Stream<Path> files = Files.walk(root)) {
      return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<String> readLines(Path file) {
    try {
      return Files.readAllLines(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String readText(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
