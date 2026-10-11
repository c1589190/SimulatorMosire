package io.mosire.simos.economy.testing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.event.Level;

/**
 * ★★ <b>测试专用的日志采集器（opt-in）</b>—— 把某个 logger 名的事件行收进内存，供"具名事件 + 逐值字段"断言用。
 *
 * <p>★★ <b>为什么本模块要自建一个</b>：{@code simos-app} / {@code simos-core} 的**测试**类路径上有 {@code
 * log4j-core}（那边的 {@code AppLogCapture} 就是借它实现的），而 {@code simos-economy} 的测试类路径上**只有 {@code
 * slf4j-api}**（实测：只有 app / core 两个 pom 声明了 log4j；给本模块加测试依赖要改 {@code pom.xml}，不在测试批的授权写入面内）。 ⇒ 本类 +
 * {@link TestSlf4jProvider}（+ {@code src/test/resources/META-INF/services} 的注册文件）就是本模块的等价装置。
 *
 * <p>★★ <b>缺省零影响（这一点是硬要求）</b>：没有任何 {@link #attach} 时，{@link TestSlf4jProvider} 的 logger 对
 * **所有级别**都返回 {@code false} —— 与"类路径上没有 provider 时 SLF4J 的 NOP 实现"逐值同行为（{@code EventLog.emit} 先问
 * {@code isXEnabled}，假 ⇒ 连字符串都不渲染）。⇒ 本装置不会改变任何既有用例的行为，只在显式 attach 的那个 logger 上开一扇窗。
 *
 * <p>★ <b>用法</b>：{@code try (TestLogCapture log =
 * TestLogCapture.attach(EconomyLog.TRACE_LOGGER_NAME)) { … }}， 之后用 {@link #lines()} 读（{@code
 * event=NAME k=v …} 的原始行）；{@link #close()} 摘掉采集槽并还原。
 */
public final class TestLogCapture implements AutoCloseable {

  /** 采集槽：logger 名 → （级别阈值 + 已收行）。★ 线程安全：经济结算的并行 worker 也可能发射事件。 */
  private static final Map<String, Sink> SINKS = new ConcurrentHashMap<>();

  private final String loggerName;
  private final Sink sink;

  private TestLogCapture(String loggerName, Sink sink) {
    this.loggerName = loggerName;
    this.sink = sink;
  }

  /** 采集某个 logger 的全部级别（TRACE 及以上）。 */
  public static TestLogCapture attach(String loggerName) {
    return attach(loggerName, Level.TRACE);
  }

  /** 采集某个 logger 的 {@code threshold} 及以上级别（例：只要 INFO 就给 {@link Level#INFO}）。 */
  public static TestLogCapture attach(String loggerName, Level threshold) {
    if (loggerName == null || loggerName.isBlank()) {
      throw new IllegalArgumentException("TestLogCapture 的 loggerName 不得为空白");
    }
    Objects.requireNonNull(threshold, "threshold");
    Sink sink = new Sink(threshold);
    Sink previous = SINKS.put(loggerName, sink);
    if (previous != null) {
      throw new IllegalStateException("同一个 logger 名已有采集槽（先 close 上一个）: " + loggerName);
    }
    return new TestLogCapture(loggerName, sink);
  }

  /** 已采集到的原始行（快照；按发生序）。 */
  public List<String> lines() {
    return sink.snapshot();
  }

  /** 已采集到的行数（不复制）。 */
  public int size() {
    return sink.lines.size();
  }

  /** 名字（日志/断言消息用）。 */
  public String loggerName() {
    return loggerName;
  }

  @Override
  public void close() {
    SINKS.remove(loggerName, sink);
  }

  // ── 供 TestSlf4jProvider 调用（包内可见，不是公共 API） ─────────────────────────────

  /** 该 logger 在该级别上是否开着（没有采集槽 ⇒ 恒 false）。 */
  static boolean enabled(String loggerName, Level level) {
    Sink sink = SINKS.get(loggerName);
    return sink != null && level.toInt() >= sink.threshold.toInt();
  }

  /** 记一行（只由 provider 调用）。 */
  static void record(String loggerName, Level level, String line) {
    Sink sink = SINKS.get(loggerName);
    if (sink != null && level.toInt() >= sink.threshold.toInt()) {
      sink.lines.add(level.name() + "|" + line);
    }
  }

  private static final class Sink {

    private final Level threshold;
    private final List<String> lines = Collections.synchronizedList(new ArrayList<>());

    private Sink(Level threshold) {
      this.threshold = threshold;
    }

    private List<String> snapshot() {
      synchronized (lines) {
        return List.copyOf(lines);
      }
    }
  }
}
