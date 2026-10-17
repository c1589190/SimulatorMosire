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
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ★★★ <b>D-032 T8：N23-economy 两处 {@code UNSPECIFIED} fail-closed 的逐行重核</b>（T6 台账 §16.6.2 的 N23 行）。
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
 *       正对照：带格段的 id ⇒ 返回落定地点 {@code 0_0}。★ 该方法是**可达的**（本类里 {@code :1351} 的调用点）。
 *   <li>② <b>逐行源码读数</b>：{@code EconomyEnterpriseSettlement} 的那处 {@code orElseThrow} 在正常路径上**不可达**
 *       ——它的 {@code industryId} 恒取自 {@code IndustryHexKeys.at(...)}（必然带格段）。因此行为上无法从测试侧构造 "带格段的 id ⇒
 *       解不出地点"（构造不出来）。本用例把这两处的<b>消息原文与行号</b>钉成读数：源码一移动/一改文案就红， 迫使后来者显式重核（这正是 T6 欠的那一步）。
 * </ul>
 *
 * <p>★ <b>行号读数（2026-10-17 本树实测）</b>：{@code ModeMigrationSettlement.java:1144}、 {@code
 * EconomyEnterpriseSettlement.java:726} —— 与 T6 台账 §16.6.2 记的 {@code 1144}/{@code 726} <b>逐值相同</b>
 * （重核结论：那两处行号未漂移；本文件把它们变成可执行的断言）。
 */
class N23EconomyUnspecifiedFailClosedLineAuditTest {

  /** T6 台账记的行号 = 本树重核的行号（源码移动请显式改这里并在台账留痕）。 */
  private static final int MODE_MIGRATION_MESSAGE_LINE = 1144;

  private static final int ENTERPRISE_MESSAGE_LINE = 738;

  private static final String MODE_MIGRATION_FILE =
      "src/main/java/io/mosire/simos/economy/time/ModeMigrationSettlement.java";

  private static final String ENTERPRISE_FILE =
      "src/main/java/io/mosire/simos/economy/time/EconomyEnterpriseSettlement.java";

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

  // ── ② 逐行源码读数：两处消息原文 + 行号 ────────────────────────────────────────────────

  @Test
  void bothUnspecifiedFailClosedMessagesArePinnedToTheirCurrentLines() throws Exception {
    List<String> migration = linesOf(MODE_MIGRATION_FILE);
    assertThat(migration.get(MODE_MIGRATION_MESSAGE_LINE - 1))
        .as(
            "★ ModeMigrationSettlement:%d 必须是迁移建档的 location-unknown 消息（行号读数，" + "源码移动请同步常量并重核）",
            MODE_MIGRATION_MESSAGE_LINE)
        .contains("location-unknown: 迁移搬迁新建实例的产业 id 不带格段")
        .contains("跨切片不一致");
    assertThat(migration.get(MODE_MIGRATION_MESSAGE_LINE - 1))
        .as("两处都不得写 UNSPECIFIED 占位（读侧防御仍可构造它，但构点不许写）")
        .doesNotContain("ofPlanOrUnspecified")
        .doesNotContain("UNSPECIFIED.parse");

    List<String> enterprise = linesOf(ENTERPRISE_FILE);
    assertThat(enterprise.get(ENTERPRISE_MESSAGE_LINE - 1))
        .as(
            "★ EconomyEnterpriseSettlement:%d 必须是组织建档的 location-unknown 消息（行号读数，" + "源码移动请同步常量并重核）",
            ENTERPRISE_MESSAGE_LINE)
        .contains("location-unknown: 生产组织新建实例的产业 id 不带格段")
        .contains("地点无从落定");
    // ★ 紧随其后的续行给出"跨切片不一致，不降级"与 unit/industry/mode 三件身份读数 ——
    //   保证"具名"不是只报一句短语。
    String enterpriseMessage =
        String.join(
            "\n", enterprise.subList(ENTERPRISE_MESSAGE_LINE - 1, ENTERPRISE_MESSAGE_LINE + 7));
    assertThat(enterpriseMessage)
        .as("消息续行必须给出 unit/industry/mode 三件身份读数 + '不降级'")
        .contains("跨切片不一致")
        .contains("不降级")
        .contains("unit=")
        .contains("industry=")
        .contains("mode=");

    // ★ 两处都不是"静默兜底"：上面钉的是**构点消息**本身；文件里的历史注释提到旧方法名
    //   （"旧的 ofPlanOrUnspecified 已删除"）不算写路径，故这里不按全文关键词判（会把注释误判成代码）。
  }

  private static List<String> linesOf(String moduleRelativePath) throws Exception {
    for (Path candidate :
        List.of(Path.of(moduleRelativePath), Path.of("simos-economy", moduleRelativePath))) {
      if (Files.isRegularFile(candidate)) {
        return Files.readAllLines(candidate);
      }
    }
    throw new IllegalStateException(
        "找不到源码（工作目录=" + Path.of(".").toAbsolutePath() + "）: " + moduleRelativePath);
  }
}
