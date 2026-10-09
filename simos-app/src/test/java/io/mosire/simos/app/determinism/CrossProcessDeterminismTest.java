package io.mosire.simos.app.determinism;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.simos.app.world.ThreePowersWorld;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>跨进程确定性护栏</b>：同名同内容的创世数据，由 <b>N 个真正独立的 JVM</b> 各自构造、各自序列化， 落盘字节的 digest <b>必须唯一</b>。
 *
 * <p>★★ <b>为什么必须真起子进程</b>（本批要解决的核心问题）：{@code Map.of}（≥2 键）与 {@code Set.copyOf} 的迭代序 来自 {@code
 * ImmutableCollections} 的 {@code SALT = JVM 启动时的 nanoTime}。同一个 JVM 里 encode 两次用<b>同一个盐</b> ⇒
 * “{@code encode → decode → encode} 两次相等”这类护栏<b>恒真</b>，天生抓不到跨进程抖动。2026-10-10 的两个真缺陷 （{@code
 * b4bfe988} app 的 {@code Map.of} 创世表 / {@code 8337eac3} map 的 {@code Set.copyOf}）正是这样漏过所有门禁的： 同一棵树
 * 8 个 JVM 得到最多 8 个不同 digest，而当时的护栏全绿。
 *
 * <p>★ <b>装置</b>：
 *
 * <ol>
 *   <li>子进程 = {@link CrossProcessDeterminismChild}（test scope 的 {@code main}），classpath 走 surefire
 *       注入的 {@code surefire.test.class.path}（回退 {@code java.class.path}）⇒ <b>不依赖任何外部脚本、不依赖网络</b>；
 *   <li>每个子进程写<b>自己的</b> {@code @TempDir} 子目录，父进程只读产物、不比内存对象；
 *   <li>比较的字节流覆盖<b>两个曾经的抖动点</b>：{@code economy} 快照 + 创世变更集（{@code Map.of} 盐）与 {@code map} 快照（{@code
 *       Set.copyOf} 盐），外加<b>整世界 checkpoint 信封</b>与其余 6 个模块；
 *   <li>★ <b>装置自证</b>：子进程落 {@code jvm-identity.txt}（pid + nanoTime），父进程断言 N 份互不相同 ⇒ 证明“比较的确实是 N
 *       个不同进程的产物”，而不是同一个 JVM 里调了 N 次（后者正是旧护栏恒真的原因）；
 *   <li>★ <b>同进程对照</b>：子进程同时打 {@code SAME_PROCESS … once==roundTrip:true}——它就是旧护栏的形制。 本护栏断言它<b>仍然为
 *       true</b>，以此把“旧护栏在变异体下照样绿”这件事钉成可复现的对照，而不是一句注释。
 * </ol>
 *
 * <p>★ <b>时间预算</b>：N 个 JVM 串行起，每个是一次创世构造 + 10 份序列化；N 可用 {@code -Dsimos.xproc.jvms=K} 调（下界
 * <b>3</b>，低于它本护栏拒绝运行——盐只有 2 个样本时判别力不足）。 实测：{@code N=4} 串行 <b>6.6s</b>（每个子 JVM ≈1.6s）。
 *
 * <p>★ <b>失败时的诊断</b>：断言消息给出逐 JVM 的 digest、<b>逐路径的第一处差异</b>（键序 vs 值分列，并给出
 * “忽略键序后是否相等”），以及每个子进程的完整日志。★ 临时目录用 {@link CleanupMode#ON_SUCCESS} ⇒ 失败轮的证据留盘。
 */
class CrossProcessDeterminismTest {

  /** 独立 JVM 个数（下界 3）。 */
  private static final int JVMS = Integer.getInteger("simos.xproc.jvms", 4);

  /** 单个子进程的等待上限（秒）。 */
  private static final int CHILD_TIMEOUT_SECONDS =
      Integer.getInteger("simos.xproc.timeoutSeconds", 180);

  /** 比较哪些字节流——顺序即报告顺序：**两个曾经的抖动点排在最前**。 */
  private static final List<String> ARTIFACTS =
      List.of(
          "economy",
          "economy.seed-changeset",
          "map",
          "checkpoint",
          "social",
          "unit",
          "sd",
          "actor",
          "gov",
          "army");

  private static final Pattern ARTIFACT_LINE =
      Pattern.compile("^ARTIFACT (\\S+) sha256=([0-9a-f]{64}) len=(\\d+)$");

  private static final ObjectMapper PLAIN = new ObjectMapper();

  @TempDir(cleanup = CleanupMode.ON_SUCCESS)
  Path tmp;

  @Test
  void genesisBytesAreIdenticalAcrossIndependentJvms() throws Exception {
    assertThat(JVMS).as("装置下界：本护栏的判别力来自“各 JVM 的盐不同”，少于 3 个进程不足以体现").isGreaterThanOrEqualTo(3);

    long startedAt = System.nanoTime();
    List<ChildRun> runs = new ArrayList<>();
    for (int i = 0; i < JVMS; i++) {
      runs.add(runChild(i));
    }
    long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

    // ★ 装置自证①：每个子进程都必须成功（否则比较的是残局）。
    for (ChildRun run : runs) {
      assertThat(run.exitCode()).as("子 JVM #%d 必须正常退出。日志：%n%s", run.index(), run.log()).isZero();
      assertThat(run.log())
          .as("子 JVM #%d 必须打到 CHILD_OK（否则它可能没跑到序列化）", run.index())
          .contains("CHILD_OK pid=");
    }

    // ★ 装置自证②：N 份 pid/nanoTime 互不相同 ⇒ 确实是 N 个不同进程（不是同进程 N 次调用）。
    Set<String> identities = distinct(runs.stream().map(ChildRun::identity).toList());
    assertThat(identities).as("子进程身份必须两两不同（pid+nanoTime）——否则比较退化成“同进程 N 次”，本护栏即恒真").hasSize(JVMS);

    // ★ 被比较的字节流必须齐全：10 份产物一个不少，且 8 个模块都在。
    for (String name : ARTIFACTS) {
      for (ChildRun run : runs) {
        assertThat(run.digests())
            .as("子 JVM #%d 必须产出 %s（完整日志：%n%s）", run.index(), name, run.log())
            .containsKey(name);
      }
    }

    System.out.println(
        "CROSS_PROCESS 装置: jvms="
            + JVMS
            + " 串行耗时="
            + elapsedMillis
            + "ms 身份="
            + identities.size()
            + "/"
            + JVMS
            + " 唯一");
    for (ChildRun run : runs) {
      System.out.println(
          "CHILD#"
              + run.index()
              + " exit="
              + run.exitCode()
              + " artifacts="
              + run.digests().size()
              + " salt="
              + run.saltProbe().replace('\n', '|')
              + " 目录="
              + run.dir());
      for (String line : run.log().split("\n")) {
        if (line.startsWith("SAME_PROCESS ")) {
          System.out.println("CHILD#" + run.index() + " " + line);
        }
      }
    }

    // ★★ 主判据：每份字节流的跨 JVM digest 必须唯一。
    //    ★ 逐份都查完再断言（不是"红在第一份就退出"）——一次运行就能给出**完整**的抖动面，
    //      报告里因此能写清"这次变异把哪几段字节打散了"，而不是只知道第一段。
    List<String> jittering = new ArrayList<>();
    StringBuilder jitterReport = new StringBuilder();
    for (String name : ARTIFACTS) {
      List<String> digests = runs.stream().map(run -> run.digests().get(name)).toList();
      Set<String> distinctDigests = distinct(digests);
      System.out.println(
          "ARTIFACT " + name + " unique=" + distinctDigests.size() + "/" + JVMS + " " + digests);
      if (distinctDigests.size() > 1) {
        jittering.add(name);
        jitterReport
            .append("★ ")
            .append(name)
            .append(" 得到 ")
            .append(distinctDigests.size())
            .append("/")
            .append(JVMS)
            .append(" 个不同 digest（逐路径定位见下）")
            .append(System.lineSeparator())
            // 只在真的抖了的时候才算逐路径差异（绿轮不付这个代价）。
            .append(describeJitter(runs, name));
      }
    }
    assertThat(jittering)
        .as(
            "★ 跨进程字节抖动：以下字节流在 %d 个独立 JVM 上不唯一——" + "“同一状态 ⇒ 同一份字节”跨进程不成立（per-JVM 盐进了落盘字节）。%n%s",
            JVMS, jitterReport)
        .isEmpty();

    // ★ 同进程对照：旧护栏的形制在创世数据上仍然为 true ⇒ 它不是“没跑到”，而是**天生盲**。
    for (ChildRun run : runs) {
      assertThat(run.log())
          .as(
              "同进程对照（旧护栏形制）：子 JVM #%d 的 economy 同进程往返必须仍然字节稳定——"
                  + "这正是旧护栏恒真、抓不到跨进程抖动的原因；它若为 false 说明同进程往返本身也坏了",
              run.index())
          .contains("SAME_PROCESS economy once==twice:true once==roundTrip:true");
      assertThat(run.log())
          .as("同进程对照：子 JVM #%d 的创世变更集同进程往返", run.index())
          .contains("SAME_PROCESS economy.seed-changeset once==twice:true once==roundTrip:true");
    }
  }

  // ── 子进程 ──────────────────────────────────────────────────────────────────

  /** 起一个独立 JVM、等它落定、解析它的摘要行。 */
  private ChildRun runChild(int index) throws IOException, InterruptedException {
    Path dir = tmp.resolve("jvm-" + index);
    Files.createDirectories(dir);
    Path logFile = tmp.resolve("jvm-" + index + ".log");

    List<String> command =
        List.of(
            javaBinary(),
            "-cp",
            testClasspath(),
            CrossProcessDeterminismChild.class.getName(),
            dir.toString(),
            ThreePowersWorld.MAP_ID);
    ProcessBuilder builder = new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    builder.redirectOutput(logFile.toFile());
    long startedAt = System.nanoTime();
    Process process = builder.start();
    boolean finished = process.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      process.waitFor(30, TimeUnit.SECONDS);
    }
    long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
    int exitCode = finished ? process.exitValue() : -1;
    String log = Files.exists(logFile) ? Files.readString(logFile, StandardCharsets.UTF_8) : "";
    System.out.println(
        "CHILD#" + index + " 耗时=" + elapsedMillis + "ms exit=" + exitCode + " cmd=" + command);

    Map<String, String> digests = new LinkedHashMap<>();
    for (String line : log.split("\n")) {
      Matcher matcher = ARTIFACT_LINE.matcher(line.trim());
      if (matcher.matches()) {
        digests.put(matcher.group(1), matcher.group(2));
      }
    }
    return new ChildRun(
        index,
        dir,
        exitCode,
        log,
        digests,
        readIfExists(dir.resolve("jvm-identity.txt")),
        readIfExists(dir.resolve("salt-probe.txt")),
        elapsedMillis);
  }

  /** 子进程用的 java 可执行文件——与被测 JVM 同一个（{@code java.home}）。 */
  private static String javaBinary() {
    return Path.of(System.getProperty("java.home"), "bin", "java").toString();
  }

  /**
   * 子进程的 classpath。
   *
   * <p>★ surefire 默认用 {@code surefirebooter.jar} 的 manifest-only classpath，此时 {@code
   * java.class.path} 只有那个 booter jar；surefire 为此专门把**真正的测试 classpath** 注入 {@code
   * surefire.test.class.path}（见 {@code
   * org.apache.maven.surefire.booter.StartupConfiguration#writeSurefireTestClasspathProperty}）。两处都取，
   * 是为了让本装置在 surefire 下与直接 {@code java -cp} 下都能跑。
   */
  private static String testClasspath() {
    String classpath = System.getProperty("surefire.test.class.path");
    if (classpath == null || classpath.isBlank()) {
      classpath = System.getProperty("java.class.path");
    }
    return classpath;
  }

  private static String readIfExists(Path path) throws IOException {
    return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8).trim() : "(缺)";
  }

  // ── 失败诊断：逐路径定位 ─────────────────────────────────────────────────────

  /** 把“哪几个 JVM 不一样、第一处差异在哪、是不是纯键序”拼成人能读的一段。 */
  private static String describeJitter(List<ChildRun> runs, String name) {
    StringBuilder text = new StringBuilder();
    for (ChildRun run : runs) {
      text.append("  子 JVM #")
          .append(run.index())
          .append(" digest=")
          .append(run.digests().get(name))
          .append(" 盐读数=")
          .append(run.saltProbe().replace('\n', '|'))
          .append(System.lineSeparator());
    }
    ChildRun base = runs.getFirst();
    for (ChildRun run : runs) {
      if (run.index() == base.index()) {
        continue;
      }
      text.append("  #").append(base.index()).append(" vs #").append(run.index()).append("：");
      try {
        String jsonA = Files.readString(base.dir().resolve(name + ".json"), StandardCharsets.UTF_8);
        String jsonB = Files.readString(run.dir().resolve(name + ".json"), StandardCharsets.UTF_8);
        JsonNode a = PLAIN.readTree(jsonA);
        JsonNode b = PLAIN.readTree(jsonB);
        String difference = firstDifference(a, b, "$");
        text.append(difference == null ? "逐路径没有差异（？）" : difference);
        text.append("；忽略键序后相等=")
            .append(
                PLAIN
                    .writeValueAsString(canonical(a))
                    .equals(PLAIN.writeValueAsString(canonical(b))));
      } catch (Exception failure) {
        text.append("逐路径比较失败: ").append(failure);
      }
      text.append(System.lineSeparator());
    }
    return text.toString();
  }

  /** 有序遍历：返回第一处差异（键序不同 / 长度不同 / 值不同），没有则 null。 */
  private static String firstDifference(JsonNode a, JsonNode b, String path) {
    if (a.isObject() && b.isObject()) {
      List<String> keysA = names(a);
      List<String> keysB = names(b);
      if (!keysA.equals(keysB)) {
        return path + " **键序/键集不同** A=" + abbr(keysA) + " B=" + abbr(keysB);
      }
      for (String key : keysA) {
        String difference = firstDifference(a.get(key), b.get(key), path + "." + key);
        if (difference != null) {
          return difference;
        }
      }
      return null;
    }
    if (a.isArray() && b.isArray()) {
      if (a.size() != b.size()) {
        return path + " 数组长度不同 A=" + a.size() + " B=" + b.size();
      }
      for (int i = 0; i < a.size(); i++) {
        String difference = firstDifference(a.get(i), b.get(i), path + "[" + i + "]");
        if (difference != null) {
          return difference;
        }
      }
      return null;
    }
    return a.equals(b) ? null : path + " 值不同 A=" + abbr1(a) + " B=" + abbr1(b);
  }

  /** 递归按键名排序的规范化（顺序无关），用来判“纯键序差异、值差异为 0”。 */
  private static JsonNode canonical(JsonNode node) {
    if (node.isObject()) {
      ObjectNode out = PLAIN.createObjectNode();
      List<String> keys = names(node);
      Collections.sort(keys);
      for (String key : keys) {
        out.set(key, canonical(node.get(key)));
      }
      return out;
    }
    if (node.isArray()) {
      ArrayNode out = PLAIN.createArrayNode();
      for (JsonNode child : node) {
        out.add(canonical(child));
      }
      return out;
    }
    return node;
  }

  private static List<String> names(JsonNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  private static String abbr(List<String> values) {
    return values.size() <= 8
        ? values.toString()
        : values.subList(0, 8) + "…(共" + values.size() + ")";
  }

  private static String abbr1(JsonNode node) {
    String text = node.toString();
    return text.length() <= 60 ? text : text.substring(0, 60) + "…";
  }

  private static Set<String> distinct(List<String> values) {
    return new LinkedHashSet<>(values);
  }

  /** 一个子 JVM 的落定结果。 */
  private record ChildRun(
      int index,
      Path dir,
      int exitCode,
      String log,
      Map<String, String> digests,
      String identity,
      String saltProbe,
      long elapsedMillis) {}
}
