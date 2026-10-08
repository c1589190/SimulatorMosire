package io.mosire.simos.app;

import io.mosire.simos.core.command.AdvanceTime;
import io.mosire.simos.app.world.WorldRegistry;
import io.mosire.simos.core.store.SqliteStore;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.core.timeline.Timeline;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 2026-10-09 选项 A（债务引用拆表）的**版本无关**对照探针（/tmp，不进仓库）。
 *
 * <p>它在「改动前 classpath」与「改动后 classpath」上跑同一份字节，靠反射兼容两版的组件形状
 * （改前：{@code HouseholdEconomy.debts}；改后：{@code EconomyData.householdDebtRefs} + {@code EconomyData.debtsOf}）。
 *
 * <pre>
 * perf  &lt;single|segment&gt; &lt;days&gt; &lt;segmentDays&gt; &lt;label&gt;   # 时间 + 变更集字节（同世界同时长）
 * refs  &lt;storePath&gt; &lt;targetTick&gt; &lt;segmentDays&gt; &lt;label&gt;   # 全经济状态规范摘要（跨版本可比）
 * </pre>
 */
public final class PerfProbe {

  private static final BranchId MAIN = new BranchId("main");

  /** 热身天数（两种 kind 同口径；见 perf 的注释）。 */
  private static final long WARMUP_DAYS = 30L;

  public static void main(String[] args) throws Exception {
    String mode = args.length > 0 ? args[0] : "perf";
    if ("perf".equals(mode)) {
      long warmup = args.length > 5 ? Long.parseLong(args[5]) : WARMUP_DAYS;
      perf(args[1], Integer.parseInt(args[2]), Integer.parseInt(args[3]), args[4], warmup);
      return;
    }
    if ("perfstore".equals(mode)) {
      perfOnStore(Path.of(args[1]), args[2], Integer.parseInt(args[3]), Integer.parseInt(args[4]), args[5]);
      return;
    }
    if ("refs".equals(mode)) {
      refs(Path.of(args[1]), Long.parseLong(args[2]), Integer.parseInt(args[3]), args[4]);
      return;
    }
    throw new IllegalArgumentException("未知 mode: " + mode);
  }

  // ── ① 时间与字节：同世界（THREE_POWERS 真创世）、同时长 ────────────────────────────────────

  private static void perf(String kind, int days, int segmentDays, String label, long warmupDays)
      throws Exception {
    Path store = Files.createTempDirectory("debtref-perf-" + kind);
    ShellConfig config =
        ShellConfig.defaults(store).withPorts(0, 0, 0).withWorldId(WorldRegistry.THREE_POWERS);
    System.out.println("== PERF kind=" + kind + " label=" + label + " days=" + days + " warmupDays=" + warmupDays + " store=" + store);
    long wallMs;
    int revisions;
    long warmupTick = 30L;
    try (Shell shell = Shell.start(config)) {
      ShellMain.seedGenesisIfEmpty(shell);
      // ★ 对照口径：先用**分段**推进 30 天做热身（跳过创世头几天的稀薄状态，让债/家户都已成形），
      //   再量接下来的 `days` 天 —— 两种 kind 用的是同一份热身口径与同一个世界。
      for (long done = 0; done < warmupDays; done += segmentDays) {
        advance(shell, (int) Math.min(segmentDays, warmupDays - done));
      }
      long t0 = System.nanoTime();
      // ★ 测量窗口内**不做回放**（tick 由本地游标推进）：否则每个单步都会额外付一次"重放整条链"的成本，
      //   把落盘大小的差异稀释掉 —— 对照口径是"推进本身的墙钟"，不是"推进 + 我自己的读数"。
      long day = warmupDays;
      if ("single".equals(kind)) {
        for (int i = 0; i < days; i++) {
          advanceFrom(shell, day, day + 1);
          day += 1L;
        }
      } else {
        for (int done = 0; done < days; done += segmentDays) {
          int step = Math.min(segmentDays, days - done);
          advanceFrom(shell, day, day + step);
          day += step;
        }
      }
      wallMs = (System.nanoTime() - t0) / 1_000_000L;
      revisions = head(shell) > 0 ? (int) (head(shell) - 1L - (warmupDays > 0 ? 0 : 0)) : 0;
    }
    System.out.printf("   [时间] 墙钟=%d ms，%d 天 ⇒ %.1f ms/天（kind=%s）%n", wallMs, days, (double) wallMs / days, kind);
    long[] sizes = changeSetBytes(store, warmupDays);
    System.out.printf(
        "   [变更集] AdvanceTime revision=%d，平均 %.0f KB/天；economy.classes 平均 %.0f KB/天；"
            + "economy.householdDebtRefs 平均 %.0f KB/天%n",
        sizes[0], sizes[1] / 1024.0, sizes[2] / 1024.0, sizes[3] / 1024.0);
    System.out.printf(
        "   PROBE-KV kind=%s label=%s days=%d wallMs=%d msPerDay=%.1f revisions=%d avgTotalKB=%.0f"
            + " avgClassesKB=%.0f avgRefsKB=%.0f%n",
        kind,
        label,
        days,
        wallMs,
        (double) wallMs / days,
        sizes[0],
        sizes[1] / 1024.0,
        sizes[2] / 1024.0,
        sizes[3] / 1024.0);
  }

   /** 在**已存在**的世界上量推进（同世界对照：两份逐字节相同的 store 副本，各推同样天数）。 */
  private static void perfOnStore(Path store, String kind, int days, int segmentDays, String label)
      throws Exception {
    ShellConfig config =
        ShellConfig.defaults(store).withPorts(0, 0, 0).withWorldId(WorldRegistry.THREE_POWERS);
    long startTick;
    long wallMs;
    try (Shell shell = Shell.start(config)) {
      startTick = tick(shell);
      System.out.println(
          "== PERFSTORE kind=" + kind + " label=" + label + " store=" + store + " startTick=" + startTick
              + " days=" + days);
      long t0 = System.nanoTime();
      long day = startTick;
      if ("single".equals(kind)) {
        for (int i = 0; i < days; i++) {
          advanceFrom(shell, day, day + 1);
          day += 1L;
        }
      } else {
        for (int done = 0; done < days; done += segmentDays) {
          int step = Math.min(segmentDays, days - done);
          advanceFrom(shell, day, day + step);
          day += step;
        }
      }
      wallMs = (System.nanoTime() - t0) / 1_000_000L;
    }
    System.out.printf(
        "   [时间] 墙钟=%d ms，%d 天 ⇒ %.1f ms/天（kind=%s，tick %d→%d）%n",
        wallMs, days, (double) wallMs / days, kind, startTick, startTick + days);
    long[] sizes = changeSetBytes(store, startTick);
    System.out.printf(
        "   [变更集] 新增 AdvanceTime revision=%d，平均 %.0f KB/天；economy.classes 平均 %.0f KB/天；"
            + "economy.householdDebtRefs 平均 %.0f KB/天%n",
        sizes[0], sizes[1] / 1024.0, sizes[2] / 1024.0, sizes[3] / 1024.0);
    System.out.printf(
        "   PROBE-KV perfstore kind=%s label=%s days=%d wallMs=%d msPerDay=%.1f avgTotalKB=%.0f"
            + " avgClassesKB=%.0f avgRefsKB=%.0f%n",
        kind,
        label,
        days,
        wallMs,
        (double) wallMs / days,
        sizes[1] / 1024.0,
        sizes[2] / 1024.0,
        sizes[3] / 1024.0);
  }

  /** {@code [0]=AdvanceTime revision 数, [1]=平均总字节/天, [2]=economy.classes 平均字节/天, [3]=引用组件平均字节/天}。 */
  private static long[] changeSetBytes(Path store, long warmupDays) throws Exception {
    long count = 0;
    long total = 0;
    long classes = 0;
    long refs = 0;
    try (SqliteStore sqlite = SqliteStore.open(store.resolve("simos.db"))) {
      Timeline timeline = new Timeline(sqlite, 1L);
      for (RevisionRow row : timeline.listRevisions(MAIN)) {
        if (!"core.AdvanceTime".equals(row.commandType()) || row.timestamp().tick() <= warmupDays) {
          continue; // 只统计**测量段**的 revision（热身那 30 天不算）
        }
        String json = row.changesetJson();
        count++;
        total += json.length();
        classes += componentBytes(json, "classes");
        refs += componentBytes(json, "householdDebtRefs");
      }
    }
    if (count == 0) {
      return new long[] {0, 0, 0, 0};
    }
    return new long[] {count, total / count, classes / count, refs / count};
  }

  /**
   * 变更集 JSON 里 economy 模块某个组件的**字节数**（正则从 {@code "组件名"} 的键起截到匹配的收尾 —— 只用于读数，
   * 不参与任何状态）。
   */
  private static long componentBytes(String json, String component) {
    int moduleAt = json.indexOf("\"economy\"");
    if (moduleAt < 0) {
      return 0;
    }
    String key = "\"" + component + "\"";
    int at = json.indexOf(key, moduleAt);
    if (at < 0) {
      return 0;
    }
    int start = json.indexOf(':', at + key.length());
    if (start < 0) {
      return 0;
    }
    int i = start + 1;
    int depth = 0;
    boolean inString = false;
    boolean escape = false;
    while (i < json.length()) {
      char ch = json.charAt(i);
      if (inString) {
        if (escape) {
          escape = false;
        } else if (ch == '\\') {
          escape = true;
        } else if (ch == '"') {
          inString = false;
        }
      } else if (ch == '"') {
        inString = true;
      } else if (ch == '{' || ch == '[') {
        depth++;
      } else if (ch == '}' || ch == ']') {
        depth--;
        if (depth == 0) {
          return i - start;
        }
      }
      i++;
    }
    return 0;
  }

  // ── ② 全经济状态规范摘要：跨版本可比（把"引用住在哪里"归一成同一行）──────────────────────

  private static void refs(Path store, long targetTick, int segmentDays, String label) throws Exception {
    ShellConfig config =
        ShellConfig.defaults(store).withPorts(0, 0, 0).withWorldId(WorldRegistry.THREE_POWERS);
    long headBefore;
    long tickBefore;
    try (Shell shell = Shell.start(config)) {
      headBefore = head(shell);
      tickBefore = tick(shell);
      if (tickBefore < targetTick) {
        for (long done = tickBefore; done < targetTick; done += segmentDays) {
          advance(shell, (int) Math.min(segmentDays, targetTick - done));
        }
      }
      long headAfter = head(shell);
      if (tickBefore >= targetTick) {
        // ★ 这一刻就是"旧档读回"：store 由另一版字节写出，本次只读不写。
        System.out.println("   [读取] store 已到 tick=" + tickBefore + "（head=" + headAfter + "）⇒ 只读回放，不推进");
      }
      SimulationState state = sim(shell);
      EconomyData data = eco(state);
      String dump = canonicalEconomyDump(data);
      String digest = sha256(dump);
      System.out.println("   [读数] label=" + label + " tick=" + tick(shell) + " head=" + head(shell));
      Map<String, List<String>> all = refsOfAll(data);
      int pairs = 0;
      for (List<String> refs : all.values()) {
        pairs += refs.size();
      }
      System.out.println("   [读数] 家户=" + data.classes().size() + " 合同=" + data.debtContracts().size()
          + " 有引用的家户=" + all.size() + " 引用对=" + pairs);
      System.out.println("   [摘要] " + digest);
      System.out.println("   PROBE-KV refs label=" + label + " tick=" + tick(shell) + " digest=" + digest);
      Files.writeString(
          Path.of("/tmp/debtref/refs-" + label + ".txt"), digest + "\n" + dump, StandardCharsets.UTF_8);
    }
  }

  /** 全经济状态的规范文本（跨版本可比：行内 {@code debts} 与独立 {@code householdDebtRefs} 都归一成 refs 行）。 */
  private static String canonicalEconomyDump(EconomyData data) {
    StringBuilder out = new StringBuilder();
    // ① 除引用以外的全部组件（EconomyData 的 householdDebtRefs 组件跳过，见下）。
    for (RecordComponent rc : EconomyData.class.getRecordComponents()) {
      if ("householdDebtRefs".equals(rc.getName())) {
        continue;
      }
      out.append("component ").append(rc.getName()).append(" = ");
      appendValue(out, invoke(rc.getAccessor(), data));
      out.append('\n');
    }
    // ② 引用行（唯一权威 = 债合同表；两版都从各自的状态读点取）：
    Map<String, List<String>> byHousehold = refsOfAll(data);
    out.append("household-refs = ").append(byHousehold).append('\n');
    return out.toString();
  }

  /** 每个家户 → 其作为债务人的合同 id（保序）；版本无关：优先 {@code EconomyData.debtsOf}，否则读行内 {@code debts}。 */
  private static Map<String, List<String>> refsOfAll(EconomyData data) {
    Map<String, List<String>> out = new TreeMap<>();
    Method debtsOf = findMethod(EconomyData.class, "debtsOf", HouseholdId.class);
    Method rowDebts = findMethod(data.classes().values().iterator().hasNext()
        ? data.classes().values().iterator().next().getClass() : Object.class, "debts");
    for (Map.Entry<HouseholdId, ?> entry : sortedByName(data.classes(), HouseholdId::value).entrySet()) {
      List<String> refs = new ArrayList<>();
      if (debtsOf != null) {
        Object value = invoke(debtsOf, data, entry.getKey());
        for (Object ref : (List<?>) value) {
          refs.add(String.valueOf(invoke(findMethod(ref.getClass(), "value"), ref)));
        }
      } else if (rowDebts != null) {
        Object value = invoke(rowDebts, entry.getValue());
        for (Object ref : (List<?>) value) {
          refs.add(String.valueOf(invoke(findMethod(ref.getClass(), "value"), ref)));
        }
      }
      Collections.sort(refs);
      out.put(entry.getKey().value(), refs);
    }
    return out;
  }

  private static <K, V> Map<K, V> sortedByName(Map<K, V> map, java.util.function.Function<K, String> name) {
    Map<String, Map.Entry<K, V>> sorted = new TreeMap<>();
    for (Map.Entry<K, V> entry : map.entrySet()) {
      sorted.put(name.apply(entry.getKey()), entry);
    }
    Map<K, V> out = new LinkedHashMap<>();
    for (Map.Entry<String, Map.Entry<K, V>> entry : sorted.entrySet()) {
      out.put(entry.getValue().getKey(), entry.getValue().getValue());
    }
    return out;
  }

  private static void appendValue(StringBuilder out, Object value) {
    if (value == null) {
      out.append("null");
      return;
    }
    if (value instanceof Map<?, ?> map) {
      out.append('{');
      boolean first = true;
      Map<String, Object> sorted = new TreeMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        sorted.put(String.valueOf(entry.getKey()), entry.getValue());
      }
      for (Map.Entry<String, Object> entry : sorted.entrySet()) {
        if (!first) {
          out.append(", ");
        }
        first = false;
        out.append(entry.getKey()).append('=');
        appendValue(out, entry.getValue());
      }
      out.append('}');
      return;
    }
    if (value instanceof Iterable<?> list) {
      out.append('[');
      boolean first = true;
      for (Object item : list) {
        if (!first) {
          out.append(", ");
        }
        first = false;
        appendValue(out, item);
      }
      out.append(']');
      return;
    }
    if (value instanceof Optional<?> optional) {
      out.append("Optional[");
      appendValue(out, optional.orElse(null));
      out.append(']');
      return;
    }
    if (value.getClass().isRecord()) {
      out.append(value.getClass().getSimpleName()).append('(');
      boolean first = true;
      for (RecordComponent rc : value.getClass().getRecordComponents()) {
        // ★ 跨版本归一：改前 HouseEconomy 行里那份 debts 不进摘要（引用统一由 household-refs 那一行表达）。
        if ("HouseholdEconomy".equals(value.getClass().getSimpleName())
            && "debts".equals(rc.getName())) {
          continue;
        }
        if (!first) {
          out.append(", ");
        }
        first = false;
        out.append(rc.getName()).append('=');
        appendValue(out, invoke(rc.getAccessor(), value));
      }
      out.append(')');
      return;
    }
    out.append(value);
  }

  private static Method findMethod(Class<?> type, String name, Class<?>... params) {
    try {
      return type.getMethod(name, params);
    } catch (NoSuchMethodException e) {
      return null;
    }
  }

  private static Object invoke(Method method, Object target, Object... args) {
    if (method == null) {
      throw new IllegalStateException("探针要的方法不存在（版本不匹配）");
    }
    try {
      return method.invoke(target, args);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("探针反射调用失败: " + method, e);
    }
  }

  private static String sha256(String text) throws Exception {
    MessageDigest sha = MessageDigest.getInstance("SHA-256");
    byte[] out = sha.digest(text.getBytes(StandardCharsets.UTF_8));
    StringBuilder hex = new StringBuilder();
    for (byte b : out) {
      hex.append(String.format("%02x", b));
    }
    return hex.toString();
  }

  // ── 时间小件 ──────────────────────────────────────────────────────────────────────────

  private static void advance(Shell shell, int days) {
    long day = tick(shell);
    advanceFrom(shell, day, day + days);
  }

  /** 显式给起点（测量窗口内不读状态，避免把"回放"算进推进耗时）。 */
  private static void advanceFrom(Shell shell, long fromTick, long toTick) {
    String id = UUID.randomUUID().toString();
    shell.advanceAndDrain(
        new AdvanceTime(
            id,
            id,
            "probe:debtref",
            MAIN,
            new RevisionId(head(shell)),
            new TimeRange(SimosTimestamp.of(fromTick), Optional.of(SimosTimestamp.of(toTick)))));
  }

  private static long head(Shell shell) {
    return shell.coreSimos().head(MAIN).orElseThrow().value();
  }

  private static SimulationState sim(Shell shell) {
    return shell.coreSimos().replay(new StateRef(MAIN, new RevisionId(head(shell))));
  }

  private static long tick(Shell shell) {
    return sim(shell).meta().timestamp().tick();
  }

  private static EconomyData eco(SimulationState state) {
    return ((EconomySnapshot) state.module("economy").orElseThrow()).data();
  }

  private PerfProbe() {}
}
