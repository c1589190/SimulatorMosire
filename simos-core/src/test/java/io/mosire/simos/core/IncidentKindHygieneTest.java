package io.mosire.simos.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>不变量 I5 的自动化判据（2026-10-13 急停协议 §6.3 N4）：util/core 里不得出现任何"具体突发事件种类"的字面量。</b>
 *
 * <p>设计书 §4.1 的取舍是「<b>util 出容器与时刻，模块出种类与载荷</b>」：{@code Incident.kind} 由<b>模块</b>定义 （{@code
 * "army.contact"} 只住 {@code simos-army} 的 {@code ArmyContacts.KIND}），util/core 只搬 {@code Incident}
 * 这个类型。一旦 core 里出现 {@code "army.contact".equals(kind)} 这类判断，判据就有了<b>第二个拼写点</b> ——那正是本仓反复吃过的亏。
 *
 * <p>★★ <b>为什么判"引号形式"而不是裸串</b>：裸 grep（{@code grep -rn "army.contact" simos-util/src/main
 * simos-core/src/main}） 今天有<b>一处</b>命中，但它是 {@code Incident.java} 类注里的<b>举例</b>（{@code &#64;code
 * army.contact}，不带引号）—— 举例是文档，不是拼写点。真正的"拼写点"在代码里必然是<b>带引号的字符串字面量</b>（或 {@code kind()}
 * 的比较），故判据取引号形式； 裸 grep 的那一处命中已在交账的"偏差"一节逐字记明（未改生产）。
 *
 * <p>★★ <b>扫描器自证（防"扫描器静默"假绿）</b>：同一把扫描器在 {@code simos-army/src/main/java} 上<b>必须</b>扫到那条合法字面量
 * （{@code ArmyContacts.KIND = "army.contact"}）——扫到 0 条就说明扫描器坏了（路径写错/正则写错），而不是"干净"。
 */
class IncidentKindHygieneTest {

  /** ★ 具体 kind 的形状：{@code "<模块命名空间>.<小写开头>"}（army.contact / unit.contact / population.famine …）。 */
  private static final Pattern KIND_LITERAL =
      Pattern.compile("\"(army|unit|population|ga|economy|social|gov)\\.[a-z]");

  /** 本批点名的那一条（判据归属在 Army，见 {@code ArmyContacts.KIND}）。 */
  private static final String ARMY_CONTACT = "\"army.contact\"";

  /** 契约面与编排面：util 与 core 的 main 源码（surefire 的工作目录是 {@code simos-core/}）。 */
  private static final List<Path> UTIL_AND_CORE_ROOTS =
      List.of(
          Paths.get("src", "main", "java"), Paths.get("..", "simos-util", "src", "main", "java"));

  /** 判据的归属模块（扫描器的"牙"就在这条合法字面量上）。 */
  private static final Path ARMY_ROOT = Paths.get("..", "simos-army", "src", "main", "java");

  @Test
  void coreAndUtilCarryNoConcreteIncidentKindLiteral() throws IOException {
    List<Path> sources = javaSources(UTIL_AND_CORE_ROOTS);

    assertThat(sources)
        .as("非空自证：扫到 0 个文件是「扫描器静默」陷阱，不是通过")
        .hasSizeGreaterThan(50)
        .anySatisfy(path -> assertThat(path.toString()).endsWith("Incident.java"))
        .anySatisfy(path -> assertThat(path.toString()).endsWith("TimeAdvance.java"));

    List<String> violations = new ArrayList<>();
    for (Path source : sources) {
      String text = Files.readString(source);
      Matcher matcher = KIND_LITERAL.matcher(text);
      while (matcher.find()) {
        violations.add(source + " → " + lineOf(text, matcher.start()) + " : " + matcher.group());
      }
    }

    assertThat(violations)
        .as(
            "★ I5：util/core 不得出现具体 kind 的字面量（判据的种类由模块定义，util/core 不认识"
                + "「两军相遇」是什么意思）—— 命中即「第二个拼写点」")
        .isEmpty();
    assertThat(scanFor(UTIL_AND_CORE_ROOTS, ARMY_CONTACT))
        .as("★ 点名判据：\"army.contact\" 只在 simos-army（这里必须零命中）")
        .isEmpty();
  }

  /** ★ 扫描器的牙：同一条字面量在 Army 里是**合法**的（它是 {@code KIND} 的唯一拼写点）。 */
  @Test
  void theScannerDoesFindTheKindLiteralWhereItBelongsTo() throws IOException {
    assertThat(scanFor(List.of(ARMY_ROOT), ARMY_CONTACT))
        .as("★★ 扫描器自证：Army 里必须扫得到 \"army.contact\"（扫不到 = 扫描器坏了，不是「干净」）")
        .hasSize(1)
        .allSatisfy(hit -> assertThat(hit).contains("ArmyContacts.java"));
  }

  private static List<String> scanFor(List<Path> roots, String literal) throws IOException {
    List<String> hits = new ArrayList<>();
    for (Path source : javaSources(roots)) {
      String text = Files.readString(source);
      if (text.contains(literal)) {
        hits.add(source + " → " + lineOf(text, text.indexOf(literal)));
      }
    }
    return hits;
  }

  private static List<Path> javaSources(List<Path> roots) throws IOException {
    Set<Path> sources = new LinkedHashSet<>();
    for (Path root : roots) {
      assertThat(Files.isDirectory(root)).as("扫描根必须存在（路径写错 ⇒ 静默 0 命中）: %s", root).isTrue();
      try (Stream<Path> files = Files.walk(root)) {
        files.filter(path -> path.getFileName().toString().endsWith(".java")).forEach(sources::add);
      }
    }
    return List.copyOf(sources);
  }

  private static long lineOf(String text, int offset) {
    return text.substring(0, offset).chars().filter(ch -> ch == '\n').count() + 1L;
  }
}
