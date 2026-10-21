package io.mosire.simos.production.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>production 契约的形状守卫</b>（D-031 spec §11 V3 N2/N4）。
 *
 * <ul>
 *   <li>{@link #industryDefinitionViewHasExactlyTheFourteenDefinitionFields()}（N4）：视图字段白名单，禁运行实例字段；
 *   <li>{@link #foreignMainSourcesOnlyImportTheContractPackage()}（N2）：除 production/app 外，外部模块只许
 *       import {@code io.mosire.simos.production.contract}；economy 不得 import 具体 {@code
 *       ProductionSkillCatalog}；
 *   <li>{@link #productionMainNeverImportsEconomyImplementation()}：production 不依赖 economy 实现（无环）。
 * </ul>
 *
 * <p>★ 路径相对模块目录（surefire 的工作目录是 {@code simos-production/}），故主树与 worktree 里都成立。
 */
class ProductionContractGuardTest {

  private static final Path REPO_ROOT = Path.of("..");
  private static final Path CONTRACT_DIR =
      Path.of("src", "main", "java", "io", "mosire", "simos", "production", "contract");

  /** 明确排除：production 自己（可 import 内部包）与组合根 app（被授权装配具体目录，修正 C）。 */
  private static final Set<String> CONTRACT_IMPORT_EXEMPT = Set.of("simos-production", "simos-app");

  private static final List<String> FORBIDDEN_RUNTIME_FIELDS =
      List.of("operator", "progressDays", "capacity", "cycleLaborMilli", "cycleInputUsedMilli");

  // ── N4：定义视图字段白名单 ────────────────────────────────────────────────────────

  @Test
  void industryDefinitionViewHasExactlyTheTwentyThreeDefinitionFields() {
    // ★ D-032 迁移后组件数 14 → 23：新增 buildDays / acceptedRightKinds（B2c）、experience + availability
    //   （S1）、roles / roleShareRemainderRule / upgradesTo / mode（D1 角色级定义面）、
    //   durableCommodityCapacityPerUnit（C5a 耐久商品资本通道）。★ 这不是"放宽"：
    //   本用例仍是逐字白名单，只是白名单按裁定加宽，且下面 FORBIDDEN_RUNTIME_FIELDS 的运行实例字段禁令一字未动。
    Set<String> names = new LinkedHashSet<>();
    for (var component : IndustryDefinitionView.class.getRecordComponents()) {
      names.add(component.getName());
    }
    assertThat(names)
        .as("方案定义的 23 个字段逐字（顺序即声明序）")
        .containsExactly(
            "id",
            "name",
            "regime",
            "skill",
            "cycleDays",
            "buildDays",
            "capacityPerUnit",
            "dailyInputPerUnit",
            "dailyLaborPerUnit",
            "laborPerUnit",
            "outputPerUnit",
            "cycleInputPerUnit",
            "slots",
            "allocation",
            "moneyOutputPerUnit",
            "experience",
            "availability",
            "acceptedRightKinds",
            "roles",
            "roleShareRemainderRule",
            "upgradesTo",
            "mode",
            "durableCommodityCapacityPerUnit");

    for (String forbidden : FORBIDDEN_RUNTIME_FIELDS) {
      assertThat(declaredMemberNames(IndustryDefinitionView.class))
          .as("视图不得暴露运行实例字段 %s（N4）", forbidden)
          .doesNotContain(forbidden);
    }
  }

  // ── N2：包边界 ────────────────────────────────────────────────────────────────────

  @Test
  void foreignMainSourcesOnlyImportTheContractPackage() {
    List<String> violations = new ArrayList<>();
    int scannedFiles = 0;
    for (Path module : modulesUnderRepoRoot()) {
      String moduleName = module.getFileName().toString();
      if (CONTRACT_IMPORT_EXEMPT.contains(moduleName)) {
        continue;
      }
      Path mainJava = module.resolve("src/main/java");
      if (!Files.isDirectory(mainJava)) {
        continue;
      }
      for (Path file : javaFilesUnder(mainJava)) {
        scannedFiles++;
        for (String line : readLines(file)) {
          String trimmed = line.strip();
          if (!trimmed.startsWith("import ")) {
            continue;
          }
          if (trimmed.contains("io.mosire.simos.production.")
              && !trimmed.contains("io.mosire.simos.production.contract.")) {
            violations.add(relativize(file) + " :: " + trimmed);
          }
        }
      }
    }
    assertThat(scannedFiles).as("★ 先证明扫描不是静默返回空（'命中 0 先怀疑自己的读取'）").isGreaterThan(50);
    assertThat(violations)
        .as("除 production/app 外，外部 main 源码只许 import io.mosire.simos.production.contract.*（N2）")
        .isEmpty();
  }

  @Test
  void economyOnlyImportsTheProductionContractSurface() {
    Path economyMain = REPO_ROOT.resolve("simos-economy/src/main/java");
    assertThat(Files.isDirectory(economyMain)).as("扫描根必须存在（否则断言恒真）").isTrue();
    List<String> hits = new ArrayList<>();
    int scannedFiles = 0;
    for (Path file : javaFilesUnder(economyMain)) {
      scannedFiles++;
      for (String line : importLines(file)) {
        if (line.contains("io.mosire.simos.production.")
            && !line.contains("io.mosire.simos.production.contract.")) {
          hits.add(relativize(file) + " :: " + line);
        }
      }
    }
    assertThat(scannedFiles).as("★ 先证明扫描不是静默返回空（'命中 0 先怀疑自己的读取'）").isGreaterThan(20);
    assertThat(hits)
        .as("economy 只能经 production.contract 读定义（具体 skill 目录/工具/实现类一律不得 import）")
        .isEmpty();
  }

  // ── §9.2 边界：production → ga-spi 工具契约是已授权的精确允许面，其余 ban 不放宽 ────────────

  @Test
  void productionMayDependOnGaSpiToolContractButNeverOnGaImplementationOrEconomy() {
    Path pom = Path.of("pom.xml");
    assertThat(Files.isRegularFile(pom)).as("本模块 pom 必须存在（否则断言恒真）").isTrue();
    String text;
    try {
      text = Files.readString(pom);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    assertThat(text)
        .as("§9.2 用户纠正：production 自己维护 GM 窄工具 ⇒ 必须直接声明 simos-ga-spi")
        .contains("<artifactId>simos-ga-spi</artifactId>");
    assertThat(text)
        .as("新边生效的前提：ga-spi 不得再被 enforcer 当作违规禁列（否则新依赖当场构建失败）")
        .doesNotContain("<exclude>io.mosire:simos-ga-spi</exclude>");
    for (String stillBanned :
        List.of(
            "simos-economy",
            "simos-culture",
            "simos-core",
            "simos-app",
            "simos-ga",
            "simos-actor",
            "simos-actor-api",
            "simos-gov",
            "simos-army",
            "simos-calendar",
            "simos-social",
            "simos-social-api",
            "simos-unit")) {
      assertThat(text)
          .as("§9.2 只给 ga-spi 工具契约开口；%s 必须仍在禁列", stillBanned)
          .contains("<exclude>io.mosire:" + stillBanned + "</exclude>");
    }
  }

  @Test
  void productionMainNeverImportsEconomyImplementation() {
    Path productionMain = Path.of("src", "main", "java");
    assertThat(Files.isDirectory(productionMain)).isTrue();
    List<String> hits = new ArrayList<>();
    for (Path file : javaFilesUnder(productionMain)) {
      if (importLines(file).stream()
          .anyMatch(line -> line.contains("io.mosire.simos.economy.model."))) {
        hits.add(relativize(file));
      }
    }
    assertThat(hits).as("production 契约值类型不得引用 economy 实现类（spec §4 无环）").isEmpty();
  }

  @Test
  void contractPackageItselfOnlyUsesAllowedTypes() {
    List<String> violations = new ArrayList<>();
    for (Path file : javaFilesUnder(CONTRACT_DIR)) {
      for (String line : readLines(file)) {
        String trimmed = line.strip();
        if (trimmed.startsWith("import ") && trimmed.contains("io.mosire.simos.economy.model.")) {
          violations.add(relativize(file) + " :: " + trimmed);
        }
      }
    }
    assertThat(violations).as("契约包不得 import io.mosire.simos.economy.model.*").isEmpty();
  }

  // ── 扫描小工具 ────────────────────────────────────────────────────────────────────

  private static List<Path> modulesUnderRepoRoot() {
    List<Path> modules = new ArrayList<>();
    try (Stream<Path> entries = Files.list(REPO_ROOT)) {
      entries
          .filter(Files::isDirectory)
          .filter(path -> path.getFileName().toString().startsWith("simos-"))
          .sorted()
          .forEach(modules::add);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return modules;
  }

  private static List<Path> javaFilesUnder(Path root) {
    try (Stream<Path> files = Files.walk(root)) {
      return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Set<String> declaredMemberNames(Class<?> type) {
    Set<String> names = new LinkedHashSet<>();
    for (var method : type.getDeclaredMethods()) {
      names.add(method.getName());
    }
    for (var field : type.getDeclaredFields()) {
      names.add(field.getName());
    }
    return names;
  }

  private static List<String> readLines(Path file) {
    try {
      return Files.readAllLines(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<String> importLines(Path file) {
    return readLines(file).stream()
        .map(String::strip)
        .filter(line -> line.startsWith("import "))
        .toList();
  }

  private static String relativize(Path file) {
    return file.normalize().toString();
  }
}
