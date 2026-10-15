package io.mosire.simos.app.sim;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * ★★ <b>批 M（2026-10-15）独立跑法入口 —— 多 tick 全真世界模拟的"额外世界"</b>。
 *
 * <p>★★ <b>用户原话（逐字，2026-10-15）</b>：「先停一停，我不是说，测试class里不要塞多tick吗？要跑全真世界模拟自己额外创世界跑， 别塞进测试里！」
 *
 * <p>★ <b>它是什么</b>：把原先塞在 JUnit 测试类里的**多 tick 全真世界模拟**（≥2 段 或 ≥60 tick，判据 M1）搬成的 **一个可执行 main**（不是
 * JUnit 类 —— surefire 的缺省 include 只收「Test 前缀 / Test 后缀 / Tests / TestCase」 四种类名，本类名不匹配 ⇒
 * 门禁永远不会跑到它）。三个 case：
 *
 * <ol>
 *   <li>{@code economy-cycle-health} —— three-powers 真世界 240 天（8 段 × 30 天）+ 96 轮市场分布指纹 （原 {@code
 *       EconomyCycleHealthTest#twoFullCycles…}）；
 *   <li>{@code gov-genesis-120d} —— 小世界 120 天逐日推进（原 {@code
 *       GovGenesisZ6Test#oneHundredTwentyDays…}）；
 *   <li>{@code segment-equivalence} —— 30 天一次推进 vs 30 次逐日推进的分段等价对拍 （原 {@code
 *       SegmentEquivalenceAcrossEconomyGovSocialTest} 两条）。
 * </ol>
 *
 * <p>★★ <b>为什么隔离在这里跑</b>：每个 case 一个**自己的空 store**（默认 {@code
 * test-world/multitick/<case>/store}，每次跑前清空 ⇒ 真创世），端口一律 {@code 0}（不占任何固定端口）， **不碰** {@code
 * test-world/store}（那是 {@code simos-testworld} 服务在用的 18Lvt 副本）、更不碰 {@code
 * ~/Simos-18Lvt/world}（全真世界）。⇒ 与门禁、与在跑的服务**互不影响**。
 *
 * <p>★ <b>判据没丢</b>：三个 case 里跑的还是原来那些断言（AssertJ 原文），失败 ⇒ 打印 + 退出码非 0。 用法与耗时见 {@code
 * docs/agents/08-七-门禁与关账命令.md} 的「多 tick 全真模拟的独立跑法」一节。
 *
 * <p>用法（★ 必须经 {@code tools/multitick-sim.sh}，它负责 classpath）：
 *
 * <pre>
 * tools/multitick-sim.sh                       # 三个 case 全跑
 * tools/multitick-sim.sh economy-cycle-health   # 只跑一个
 * tools/multitick-sim.sh --store-root=/tmp/x    # 换 store 根（默认 test-world/multitick）
 * </pre>
 */
public final class MultiTickSimRunner {

  /** 缺省 store 根：★ 落在 {@code test-world/} 那一侧（仓内代码测试副本，{@code .gitignore} 排除）。 */
  private static final String DEFAULT_STORE_ROOT = "test-world/multitick";

  private MultiTickSimRunner() {}

  /** 一个 case = 一段被搬出 JUnit 的多 tick 模拟。 */
  private record SimCase(String name, String what, Body body) {}

  /** case 的跑法：给它一个自己的目录（store + 摘要都写在里面）。 */
  @FunctionalInterface
  private interface Body {
    void run(Path caseDir) throws Exception;
  }

  public static void main(String[] args) throws Exception {
    String storeRoot = DEFAULT_STORE_ROOT;
    String only = null;
    for (String arg : args) {
      if (arg.startsWith("--store-root=")) {
        storeRoot = arg.substring("--store-root=".length());
      } else if (arg.startsWith("--case=")) {
        String requested = arg.substring("--case=".length());
        // ★ `all` / `--case=`（空）= 全跑 —— 实测踩到：只认字面 case 名会把 `all` 判成"没有匹配的 case"并 rc=3
        only = (requested.isEmpty() || "all".equals(requested)) ? null : requested;
      } else if ("--help".equals(arg) || "-h".equals(arg)) {
        usage();
        return;
      } else {
        System.err.println("未知参数：" + arg);
        usage();
        System.exit(3);
      }
    }

    List<SimCase> all = List.of(economyCycleHealth(), govGenesis120d(), segmentEquivalence());
    List<SimCase> selected = new ArrayList<>();
    for (SimCase simCase : all) {
      if (only == null || only.equals(simCase.name())) {
        selected.add(simCase);
      }
    }
    if (selected.isEmpty()) {
      System.err.println(
          "没有匹配的 case：" + only + "（可选：" + all.stream().map(SimCase::name).toList() + "）");
      System.exit(3);
    }

    Path root = Path.of(storeRoot).toAbsolutePath().normalize();
    // ★ 本跑法会**清空** `<root>/<case>` —— 路径太浅（"/"、"~/x" 这种）一律拒绝，避免误删。
    if (root.getNameCount() < 2) {
      System.err.println("拒绝：--store-root 太浅（" + root + "）—— 本跑法会清空 <root>/<case>");
      System.exit(3);
    }
    Files.createDirectories(root);
    System.out.println("=== 多 tick 全真模拟 · 独立跑法 ===");
    System.out.println("store 根：" + root + "（每个 case 一个自己的空 store，跑前清空）");
    System.out.println("在跑的 case：" + selected.stream().map(SimCase::name).toList());
    System.out.println();

    int failed = 0;
    for (SimCase simCase : selected) {
      Path caseDir = root.resolve(simCase.name());
      Path storeDir = caseDir.resolve("store");
      wipe(caseDir);
      Files.createDirectories(storeDir);
      System.out.println("── case=" + simCase.name() + " —— " + simCase.what());
      long startedAt = System.nanoTime();
      try {
        simCase.body().run(caseDir);
        System.out.printf(
            Locale.ROOT,
            "[PASS] case=%s 用时=%.1fs（store=%s）%n%n",
            simCase.name(),
            secondsSince(startedAt),
            storeDir);
      } catch (Throwable failure) {
        failed++;
        System.out.printf(
            Locale.ROOT, "[FAIL] case=%s 用时=%.1fs%n", simCase.name(), secondsSince(startedAt));
        System.out.println("       " + failure);
        // ★ 断言消息里带 as(...) 的读数说明 —— 原样打出来（人要看的就是它）
        String message = failure.getMessage();
        if (message != null) {
          for (String line : message.split("\n")) {
            System.out.println("       " + line);
          }
        }
        System.out.println();
      }
    }

    System.out.println(
        "MULTITICK_SIM_RESULT="
            + (failed == 0 ? "PASS" : "FAIL")
            + " cases="
            + selected.size()
            + " failed="
            + failed);
    if (failed > 0) {
      System.exit(1);
    }
  }

  private static SimCase economyCycleHealth() {
    return new SimCase(
        "economy-cycle-health",
        "three-powers 真世界 240 天（8 段 × 30 天）+ 96 轮市场分布指纹 + 逐币种货币守恒（原 EconomyCycleHealthTest）",
        caseDir ->
            EconomyCycleHealthSim.run(caseDir.resolve("store"), caseDir.resolve("summary.txt")));
  }

  private static SimCase govGenesis120d() {
    return new SimCase(
        "gov-genesis-120d",
        "小世界 120 天逐日推进：每日一条 revision + GOV_SERVICE 承诺不缩 + 无 ADMIN_CONTRACT 危机（原 GovGenesisZ6Test）",
        caseDir -> GovGenesis120DaySim.run(caseDir.resolve("store")));
  }

  private static SimCase segmentEquivalence() {
    return new SimCase(
        "segment-equivalence",
        "30 天一次推进 == 30 次逐日推进（economy/social/gov/unit 逐字段）+ day30 工资足额（原 SegmentEquivalenceAcrossGovSocialTest）",
        SegmentEquivalenceSim::run);
  }

  /** 清空某个 case 的目录（**只删自己名下那一层**，绝不碰 store 根或别处）。 */
  private static void wipe(Path caseDir) throws IOException {
    if (!Files.exists(caseDir)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(caseDir)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  private static double secondsSince(long startedAt) {
    return (System.nanoTime() - startedAt) / 1_000_000_000.0;
  }

  private static void usage() {
    System.out.println("用法：tools/multitick-sim.sh [case] [--store-root=DIR]");
    System.out.println(
        "  case ∈ {economy-cycle-health | gov-genesis-120d | segment-equivalence | all}（缺省 all）");
    System.out.println("  --store-root 缺省 " + DEFAULT_STORE_ROOT + "（★ 只在这里面建/删目录）");
  }
}
