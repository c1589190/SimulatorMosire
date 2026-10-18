package io.mosire.simos.economy.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.production.contract.ProductionLocation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>D-032 T8：N23-economy 两处 {@code UNSPECIFIED} fail-closed 的源码审计</b>（T6 台账 §16.6.2 的 N23 行）。
 *
 * <p>★★ <b>被核的两处</b>（T6 记的是"经济侧两处建档 fail-closed"，当时标为"未逐行重核断言文本"）：
 *
 * <ol>
 *   <li>{@link ModeMigrationSettlement} 的 {@code requireInstanceLocation}：迁移搬迁新建实例时，产业 id 解不出格段 ⇒
 *       契约故障（不写 {@code UNSPECIFIED} 占位）。
 *   <li>{@link EconomyEnterpriseSettlement} 的 {@code organizeOne}：生产组织新建实例时，同一判据（内联 {@code
 *       ProductionLocation.ofPlan(...).orElseThrow(...)}）。
 * </ol>
 *
 * <p>★★ <b>本文件的判定方式（两处各自的最强可执行形态）</b>：
 *
 * <ul>
 *   <li>① <b>行为</b>：反射调 {@code
 *       ModeMigrationSettlement.requireInstanceLocation}（私有静态）——给一个<b>不带格段</b>的 {@link IndustryId}
 *       ⇒ 具名 {@link IllegalStateException}（消息含 {@code location-unknown} + 产业 id + {@code 不降级}）；
 *       正对照：带格段的 id ⇒ 返回落定地点 {@code 0_0}。★ 该方法是**可达的**（本类里有实际调用点）。
 *   <li>② <b>源码消息文本扫描</b>：{@code EconomyEnterpriseSettlement} 的那处 {@code orElseThrow} 在正常路径上**不可达**
 *       ——它的 {@code industryId} 恒取自 {@code IndustryHexKeys.at(...)}（必然带格段）。因此行为上无法从测试侧构造 "带格段的 id ⇒
 *       解不出地点"（构造不出来）。本用例改扫 {@code time} 目录下全部 {@code *.java}，要求两段消息文本各自**恰好命中一次**，并核命中处的 局部上下文仍然保留
 *       fail-closed 语义；这样源码机械移动不再红，而消息丢失/重复/降级仍红。
 * </ul>
 *
 * <p>★★ <b>P0.5 拆分后由行号 pin 迁移为唯一消息文本扫描；源码移动不再红，消息丢失/重复/降级仍红。</b>
 */
class N23EconomyUnspecifiedFailClosedLineAuditTest {

  private static final String TIME_SOURCE_DIRECTORY = "src/main/java/io/mosire/simos/economy/time";

  /** 迁移 location-unknown 消息的首段原文；实际文件与行号由全目录扫描自动发现。 */
  private static final String MIGRATION_MESSAGE_PREFIX = "location-unknown: 迁移搬迁新建实例的产业 id 不带格段";

  /** 生产组织 location-unknown 消息的首段原文；实际文件与行号由全目录扫描自动发现。 */
  private static final String ENTERPRISE_MESSAGE_PREFIX = "location-unknown: 生产组织新建实例的产业 id 不带格段";

  /** 组织消息的局部上下文窗口：命中行 + 紧随其后的 7 行。 */
  private static final int MESSAGE_CONTEXT_LINES = 8;

  // ── ① 行为：ModeMigrationSettlement.requireInstanceLocation ─────────────────────────────

  @Test
  void theMigrationConstructionPointRejectsAnIndustryWithoutHexByName() throws Exception {
    Method require =
        ModeMigrationSettlement.class.getDeclaredMethod(
            "requireInstanceLocation", IndustryId.class);
    require.setAccessible(true);

    // 正对照：带格段的产业 id ⇒ 地点落定 0_0（证明"解得出"这一支真的走得通）。
    IndustryId withHex = IndustryHexKeys.id("farm", 0, 0);
    ProductionLocation resolved = (ProductionLocation) require.invoke(null, withHex);
    assertThat(resolved.isSpecified()).as("正对照：地点已落定").isTrue();
    assertThat(resolved.value()).as("正对照：逐值 = 0_0").isEqualTo("0_0");

    // 判据：不带格段 ⇒ 具名契约故障（不是 UNSPECIFIED 占位、不是 null、不是静默跳过）。
    IndustryId noHex = new IndustryId("no-hex-plan");
    InvocationTargetException thrown =
        catchThrowableOfType(() -> require.invoke(null, noHex), InvocationTargetException.class);
    assertThat(thrown).as("必须抛（fail-closed）").isNotNull();
    assertThat(thrown.getCause())
        .as("★★ 契约故障是 IllegalStateException（ERROR 不降级）")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("location-unknown")
        .hasMessageContaining("no-hex-plan")
        .hasMessageContaining("不降级")
        .hasMessageContaining("不带格段");
  }

  // ── ② 源码审计：两段消息文本各唯一 + 局部上下文不降级 ──────────────────────────────────

  @Test
  void bothUnspecifiedFailClosedMessagesAreUniqueAndNonDegradingInSource() throws Exception {
    List<SourceHit> migrationHits = scanJavaSources(MIGRATION_MESSAGE_PREFIX);
    assertThat(migrationHits)
        .as("迁移 location-unknown 消息文本在 time/*.java 中必须恰好命中一次（防止静默丢失或重复造第二写点）")
        .hasSize(1);

    SourceHit migration = migrationHits.get(0);
    assertThat(migration.line())
        .as(
            "★ %s:%d 必须是迁移建档的 location-unknown 消息（自动发现，源码移动不再红）",
            migration.sourceFile(), migration.lineNumber())
        .contains(MIGRATION_MESSAGE_PREFIX)
        .contains("跨切片不一致")
        .doesNotContain("ofPlanOrUnspecified")
        .doesNotContain("UNSPECIFIED.parse");

    List<SourceHit> enterpriseHits = scanJavaSources(ENTERPRISE_MESSAGE_PREFIX);
    assertThat(enterpriseHits)
        .as("组织 location-unknown 消息文本在 time/*.java 中必须恰好命中一次（防止静默丢失或重复造第二写点）")
        .hasSize(1);

    SourceHit enterprise = enterpriseHits.get(0);
    assertThat(enterprise.line())
        .as(
            "★ %s:%d 必须是组织建档的 location-unknown 消息（自动发现，源码移动不再红）",
            enterprise.sourceFile(), enterprise.lineNumber())
        .contains(ENTERPRISE_MESSAGE_PREFIX)
        .contains("地点无从落定");
    // ★ 紧随其后的续行给出"跨切片不一致，不降级"与 unit/industry/mode 三件身份读数 ——
    //   保证"具名"不是只报一句短语；窗口有界，不能拿全文件关键词冒充局部消息。
    assertThat(enterprise.context())
        .as("消息续行必须给出 unit/industry/mode 三件身份读数 + '跨切片不一致' + '不降级'")
        .contains("跨切片不一致")
        .contains("不降级")
        .contains("unit=")
        .contains("industry=")
        .contains("mode=");

    // ★ 两处都不是"静默兜底"：上面钉的是**构点消息**本身；文件里的历史注释提到旧方法名
    //   （"旧的 ofPlanOrUnspecified 已删除"）不算写路径，故这里不按全文关键词判（会把注释误判成代码）。
  }

  private static List<SourceHit> scanJavaSources(String messagePrefix) throws Exception {
    Path sourceDirectory = timeSourceDirectory();
    List<Path> sourceFiles;
    try (Stream<Path> paths = Files.walk(sourceDirectory)) {
      sourceFiles =
          paths
              .filter(Files::isRegularFile)
              .filter(path -> path.getFileName().toString().endsWith(".java"))
              .sorted()
              .toList();
    }
    assertThat(sourceFiles)
        .as("源码扫描器必须真的看到 time 目录下的 *.java（防止路径错误造成假零命中）: %s", sourceDirectory)
        .isNotEmpty();

    List<SourceHit> hits = new ArrayList<>();
    for (Path sourceFile : sourceFiles) {
      List<String> lines = Files.readAllLines(sourceFile);
      for (int index = 0; index < lines.size(); index++) {
        String line = lines.get(index);
        int occurrences = occurrencesOf(line, messagePrefix);
        if (occurrences == 0) {
          continue;
        }
        int contextEnd = Math.min(lines.size(), index + MESSAGE_CONTEXT_LINES);
        String context = String.join("\n", lines.subList(index, contextEnd));
        for (int occurrence = 0; occurrence < occurrences; occurrence++) {
          hits.add(new SourceHit(sourceFile, index + 1, line, context));
        }
      }
    }
    return hits;
  }

  private static int occurrencesOf(String text, String messagePrefix) {
    int count = 0;
    int fromIndex = 0;
    while ((fromIndex = text.indexOf(messagePrefix, fromIndex)) >= 0) {
      count++;
      fromIndex += messagePrefix.length();
    }
    return count;
  }

  private static Path timeSourceDirectory() {
    for (Path candidate :
        List.of(Path.of(TIME_SOURCE_DIRECTORY), Path.of("simos-economy", TIME_SOURCE_DIRECTORY))) {
      if (Files.isDirectory(candidate)) {
        return candidate.toAbsolutePath();
      }
    }
    throw new IllegalStateException(
        "找不到 time 源码目录（工作目录=" + Path.of(".").toAbsolutePath() + "）: " + TIME_SOURCE_DIRECTORY);
  }

  private record SourceHit(Path sourceFile, int lineNumber, String line, String context) {}
}
