package io.mosire.simos.economy.testing;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.ILoggerFactory;
import org.slf4j.IMarkerFactory;
import org.slf4j.Logger;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.AbstractLogger;
import org.slf4j.helpers.BasicMDCAdapter;
import org.slf4j.helpers.BasicMarkerFactory;
import org.slf4j.spi.MDCAdapter;
import org.slf4j.spi.SLF4JServiceProvider;

/**
 * ★★ <b>simos-economy 测试类路径上的最小 SLF4J 提供者</b>——只为 {@link TestLogCapture} 服务。
 *
 * <p>★★ <b>缺省零影响</b>（本装置存在的唯一前提）：没有 {@link TestLogCapture#attach} 时，{@link TestLogger} 的全部 {@code
 * isXEnabled()} 恒 {@code false} ⇒ {@code EventLog.emit} 连字符串都不渲染 —— 与"类路径上没有 provider 时 SLF4J 的 NOP
 * 实现"**逐值同行为**（该模块测试类路径上原本就没有 provider：只有 {@code simos-app}/{@code simos-core} 声明了 {@code
 * log4j-core}，见 {@code pom.xml}）。⇒ 既有用例的日志面一个字都不变。
 *
 * <p>★ <b>注册方式</b>：{@code
 * src/test/resources/META-INF/services/org.slf4j.spi.SLF4JServiceProvider}（SLF4J 2.x 走 {@code
 * ServiceLoader}）。★ 本类**只**在 {@code src/test} 里，绝不进生产代码。
 */
public final class TestSlf4jProvider implements SLF4JServiceProvider {

  /** SLF4J 要求"提供的 API 版本"≥ 当前 API；给一个足够高的值即可（不参与任何判据）。 */
  private static final String REQUESTED_API_VERSION = "2.0.99";

  private final ILoggerFactory loggerFactory = new TestLoggerFactory();

  private final IMarkerFactory markerFactory = new BasicMarkerFactory();

  private final MDCAdapter mdcAdapter = new BasicMDCAdapter();

  @Override
  public ILoggerFactory getLoggerFactory() {
    return loggerFactory;
  }

  @Override
  public IMarkerFactory getMarkerFactory() {
    return markerFactory;
  }

  @Override
  public MDCAdapter getMDCAdapter() {
    return mdcAdapter;
  }

  @Override
  public String getRequestedApiVersion() {
    return REQUESTED_API_VERSION;
  }

  @Override
  public void initialize() {
    // 无需初始化：级别由 TestLogCapture 的采集槽决定（没有槽 ⇒ 全关）。
  }

  /** 逐名字缓存 logger（与 SLF4J 自带实现同形）。 */
  private static final class TestLoggerFactory implements ILoggerFactory {

    private final Map<String, Logger> byName = new ConcurrentHashMap<>();

    @Override
    public Logger getLogger(String name) {
      return byName.computeIfAbsent(name, TestLogger::new);
    }
  }

  /**
   * 最小 logger：级别问答全部委托 {@link TestLogCapture#enabled}（没有采集槽 ⇒ 恒 false）， 落行委托 {@link
   * TestLogCapture#record}。
   *
   * <p>★ 为什么继承 {@link AbstractLogger}：它把 50 多个 {@code trace/debug/info/…} 重载都实现好了，本类只需给 10 个 {@code
   * isXEnabled} + 2 个抽象钩子。
   */
  private static final class TestLogger extends AbstractLogger {

    private static final long serialVersionUID = 1L;

    private TestLogger(String name) {
      this.name = name;
    }

    @Override
    public boolean isTraceEnabled() {
      return TestLogCapture.enabled(name, Level.TRACE);
    }

    @Override
    public boolean isTraceEnabled(Marker marker) {
      return isTraceEnabled();
    }

    @Override
    public boolean isDebugEnabled() {
      return TestLogCapture.enabled(name, Level.DEBUG);
    }

    @Override
    public boolean isDebugEnabled(Marker marker) {
      return isDebugEnabled();
    }

    @Override
    public boolean isInfoEnabled() {
      return TestLogCapture.enabled(name, Level.INFO);
    }

    @Override
    public boolean isInfoEnabled(Marker marker) {
      return isInfoEnabled();
    }

    @Override
    public boolean isWarnEnabled() {
      return TestLogCapture.enabled(name, Level.WARN);
    }

    @Override
    public boolean isWarnEnabled(Marker marker) {
      return isWarnEnabled();
    }

    @Override
    public boolean isErrorEnabled() {
      return TestLogCapture.enabled(name, Level.ERROR);
    }

    @Override
    public boolean isErrorEnabled(Marker marker) {
      return isErrorEnabled();
    }

    @Override
    protected String getFullyQualifiedCallerName() {
      return TestLogger.class.getName();
    }

    @Override
    protected void handleNormalizedLoggingCall(
        Level level,
        Marker marker,
        String messagePattern,
        Object[] arguments,
        Throwable throwable) {
      // 事件行由 EventLog 渲染成"单参数消息"（logger.info(String)）⇒ 这里只做最朴素的占位替换，
      // 保证"逐值字段"原样可读（本装置不追求格式化器的全部语义）。
      String line = messagePattern;
      if (arguments != null && arguments.length > 0) {
        line = String.valueOf(messagePattern) + " " + java.util.Arrays.toString(arguments);
      }
      TestLogCapture.record(name, level, line);
    }
  }
}
