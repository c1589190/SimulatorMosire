package io.mosire.simos.app.gui;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.AbstractConfiguration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * GUI 访问日志的验收（M10）：**一行一请求**，含方法 / 路径 / 状态码 / 耗时 / 远端，且**密钥纪律**——不带查询串、不带其值。
 *
 * <p>★ **装置真的在收**由 {@link #logLinesAreActuallyCaptured()} 当前提断言（空捕获上"不含哨兵"会假绿，core 的 {@code
 * CommandBusLoggingTest} 已记过这个坑）。★ **删掉那行 {@code LOG.info} 的变异体在此红**。
 */
class GuiAccessLogTest {

  /** 埋进查询串的哨兵：一旦出现在任何访问日志里，本用例就红。 */
  private static final String CANARY = "PLAINTEXT-QUERY-CANARY-91b2";

  @TempDir Path tempDir;

  private Shell shell;
  private HttpClient client;
  private int port;

  private LoggerContext context;
  private AbstractConfiguration configuration;
  private LoggerConfig loggerConfig;
  private Level originalLevel;
  private CollectingAppender appender;

  @BeforeEach
  void start() {
    shell = Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0, 0, 0));
    port = shell.boundGuiPort();
    client = HttpClient.newHttpClient();

    context = (LoggerContext) LogManager.getContext(false);
    configuration = (AbstractConfiguration) context.getConfiguration();
    loggerConfig = configuration.getLoggerConfig(GuiServer.class.getName());
    originalLevel = loggerConfig.getLevel();
    appender = new CollectingAppender();
    appender.start();
    configuration.addAppender(appender);
    loggerConfig.addAppender(appender, Level.INFO, null);
    // ★ 只挂 appender 不抬 level ⇒ 一条都收不到（CommandBusLoggingTest 探针实测）
    loggerConfig.setLevel(Level.INFO);
    context.updateLoggers();
  }

  @AfterEach
  void stop() {
    if (appender != null) {
      loggerConfig.removeAppender(appender.getName());
      loggerConfig.setLevel(originalLevel);
      configuration.removeAppender(appender.getName());
      context.updateLoggers();
      appender.stop();
    }
    if (shell != null) {
      shell.close();
    }
  }

  /**
   * ★★ **前提断言**：装置真的在收日志；否则本类其余断言都可能在空捕获上假绿。
   *
   * <p>★ **必须 `awaitAccessLines` 而非裸读**（2026-09-21 控制器修）：访问日志在 `finally` 里写，与客户端拿到响应之间有一个极短窗口 ——
   * 本类其余三条用例都用了 `awaitAccessLines`，只有这条裸读，于是它**是全类唯一会随机红的那条**（全量 `clean verify` 负载下实测 一次：`Tests
   * run: 4, Failures: 1`，且控制台日志里那行 `access GET / -> 200` 确实在断言之后才出现）。裸读**不是判据更强，只是更脆**。
   */
  @Test
  void logLinesAreActuallyCaptured() throws Exception {
    get("/");

    assertThat(awaitAccessLines(1))
        .as("★ 捕获为空 ⇒ 整套装置失效，其余断言全部无意义。实得 %s", appender.messages())
        .isNotEmpty();
  }

  @Test
  void everyRequestProducesOneAccessLineWithMethodPathStatusAndRemote() throws Exception {
    assertThat(get("/").statusCode()).isEqualTo(200);
    assertThat(get("/api/state").statusCode()).isEqualTo(200);

    List<String> lines = awaitAccessLines(2);
    assertThat(lines).as("两次请求 ⇒ 两行访问日志，实得 %s", lines).hasSize(2);
    assertThat(lines).anySatisfy(l -> assertThat(l).contains("access GET / -> 200"));
    assertThat(lines).anySatisfy(l -> assertThat(l).contains("access GET /api/state -> 200"));
    assertThat(lines)
        .allSatisfy(
            l -> assertThat(l).contains("ms remote=").doesNotContain("remote=-")); // 远端必须真的取到
  }

  @Test
  void notFoundAndWrongMethodAreLoggedWithTheirRealStatus() throws Exception {
    assertThat(get("/nope").statusCode()).isEqualTo(404);
    assertThat(post("/api/state", "{}").statusCode()).isEqualTo(405);

    List<String> lines = awaitAccessLines(2);
    assertThat(lines).anySatisfy(l -> assertThat(l).contains("access GET /nope -> 404"));
    assertThat(lines).anySatisfy(l -> assertThat(l).contains("access POST /api/state -> 405"));
  }

  /** ★ **密钥纪律**：查询串（可能含敏感值）绝不进日志——只记路径。 */
  @Test
  void accessLogNeverCarriesTheQueryStringOrItsValues() throws Exception {
    assertThat(get("/api/state?token=" + CANARY + "&secret=" + CANARY).statusCode()).isEqualTo(200);

    List<String> lines = awaitAccessLines(1);
    assertThat(lines).as("前提：先得真收到访问日志").isNotEmpty();
    assertThat(lines).allSatisfy(line -> assertThat(line).doesNotContain(CANARY));
    assertThat(lines).allSatisfy(line -> assertThat(line).doesNotContain("?"));
    assertThat(lines).anySatisfy(line -> assertThat(line).contains("access GET /api/state -> 200"));
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────

  private List<String> accessLines() {
    return appender.messages().stream().filter(line -> line.contains("access ")).toList();
  }

  /** 等访问日志攒到 {@code expected} 行（日志在 finally 写出，与客户端拿到响应之间有一个极短窗口）。 */
  private List<String> awaitAccessLines(int expected) {
    long deadline = System.nanoTime() + 2_000_000_000L;
    List<String> lines = accessLines();
    while (lines.size() < expected && System.nanoTime() < deadline) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      lines = accessLines();
    }
    return lines;
  }

  private HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** 把 log4j2 的格式化结果收进一个清单（与 core 的 {@code CommandBusLoggingTest} 同法）。 */
  private static final class CollectingAppender extends AbstractAppender {

    private final List<String> collected = Collections.synchronizedList(new ArrayList<>());

    private CollectingAppender() {
      super("GuiAccessLogTest-collector", null, null, false, Property.EMPTY_ARRAY);
    }

    @Override
    public void append(LogEvent event) {
      collected.add(event.getLevel() + "|" + event.getMessage().getFormattedMessage());
    }

    private List<String> messages() {
      synchronized (collected) {
        return List.copyOf(collected);
      }
    }
  }
}
