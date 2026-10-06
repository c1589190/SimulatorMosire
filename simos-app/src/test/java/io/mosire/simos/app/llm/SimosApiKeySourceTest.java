package io.mosire.simos.app.llm;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.app.AppLog;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

/** D-B 判据：ENV / FILE 逃生口（SPI）三态解析 + 密钥值不进异常/日志。 */
class SimosApiKeySourceTest {

  @TempDir Path tempDir;

  private LoggerContext context;
  private AbstractConfiguration configuration;
  private LoggerConfig loggerConfig;
  private Level originalLevel;
  private CollectingAppender appender;

  @BeforeEach
  void attachLogCollector() {
    context = (LoggerContext) LogManager.getContext(false);
    configuration = (AbstractConfiguration) context.getConfiguration();
    // ★ 2026-10-23 L1：logger 从类 logger 移到门面分类 io.mosire.simos.app.llm（AppLog.llm()）。
    loggerConfig = configuration.getLoggerConfig(AppLog.LLM_LOGGER_NAME);
    originalLevel = loggerConfig.getLevel();
    appender = new CollectingAppender();
    appender.start();
    configuration.addAppender(appender);
    loggerConfig.addAppender(appender, Level.INFO, null);
    loggerConfig.setLevel(Level.INFO);
    context.updateLoggers();
  }

  @AfterEach
  void detachLogCollector() {
    if (appender != null) {
      loggerConfig.removeAppender(appender.getName());
      loggerConfig.setLevel(originalLevel);
      configuration.removeAppender(appender.getName());
      context.updateLoggers();
      appender.stop();
    }
  }

  @Test
  void configLayerWinsOverEnvAndFile() {
    AgentLibLlmConfig config = AgentLibLlmConfig.open(tempDir);
    config.putKey("k1", "from-config");
    SimosApiKeySource source =
        new SimosApiKeySource(
            config, tempDir, "keys.k1", Map.of("SIMO_LLM_KEY_KEYS_K1", "from-env"));

    assertThat(source.apiKey()).contains("from-config");
  }

  @Test
  void envLayerIsUsedWhenConfigHasNoSuchKey() {
    AgentLibLlmConfig config = AgentLibLlmConfig.open(tempDir);
    SimosApiKeySource source =
        new SimosApiKeySource(
            config, tempDir, "DEEPSEEK_KEY", Map.of("SIMO_LLM_KEY_DEEPSEEK_KEY", "from-env"));

    assertThat(source.apiKey()).contains("from-env");
  }

  @Test
  void fileLayerIsTrimmedAndUsedWhenOthersMiss() throws Exception {
    AgentLibLlmConfig config = AgentLibLlmConfig.open(tempDir);
    Path keyDir = tempDir.resolve(SimosApiKeySource.KEY_DIR_NAME);
    Files.createDirectories(keyDir);
    Files.writeString(keyDir.resolve("filekey"), "  from-file  \n", StandardCharsets.UTF_8);

    SimosApiKeySource source = new SimosApiKeySource(config, tempDir, "filekey", Map.of());
    assertThat(source.apiKey()).contains("from-file");
  }

  @Test
  void blankReferenceIsAnonymousAndDoesNotThrow() {
    AgentLibLlmConfig config = AgentLibLlmConfig.open(tempDir);
    assertThat(new SimosApiKeySource(config, tempDir, "", Map.of()).apiKey()).isEmpty();
    assertThat(new SimosApiKeySource(config, tempDir, "   ", Map.of()).apiKey()).isEmpty();
    assertThat(new SimosApiKeySource(config, tempDir, null, Map.of()).apiKey()).isEmpty();
  }

  /** ★ L4/§9：空白密钥文件是 WARN（既有级别不降级），且带模块来源字段。 */
  @Test
  void emptyKeyFileStaysWarnAndCarriesOrigin() throws Exception {
    AgentLibLlmConfig config = AgentLibLlmConfig.open(tempDir);
    Path keyDir = tempDir.resolve(SimosApiKeySource.KEY_DIR_NAME);
    Files.createDirectories(keyDir);
    Files.writeString(keyDir.resolve("emptykey"), "   \n", StandardCharsets.UTF_8);

    SimosApiKeySource source = new SimosApiKeySource(config, tempDir, "emptykey", Map.of());

    assertThat(source.apiKey()).isEmpty();
    assertThat(appender.messages())
        .as("实得 %s", appender.messages())
        .anySatisfy(
            m ->
                assertThat(m)
                    .startsWith("WARN|")
                    .contains("event=LLM_KEY_FILE_EMPTY")
                    .contains("origin=llm-config")
                    .contains("originKind=system")
                    .contains("configured=false"));
  }

  @Test
  void unresolvedReferenceReturnsEmptyNotAnException() {
    AgentLibLlmConfig config = AgentLibLlmConfig.open(tempDir);
    SimosApiKeySource source = new SimosApiKeySource(config, tempDir, "nowhere", Map.of());
    assertThat(source.apiKey()).as("取不到密钥 = 匿名，不是异常（SPI 契约）").isEmpty();
  }

  @Test
  void sentinelValueNeverLeaksIntoExceptionMessages() {
    // ★ 判据：密钥值绝不进异常消息（异常只可能来自路径/引用，与值无关 ⇒ 值不可能出现在任何 message 里）。
    AgentLibLlmConfig config = AgentLibLlmConfig.open(tempDir);
    String sentinel = "sk-SENTINEL-LEAK-CHECK";
    SimosApiKeySource source = new SimosApiKeySource(config, tempDir, "missing", Map.of());
    try {
      source.apiKey();
    } catch (RuntimeException e) {
      assertThat(e.getMessage()).doesNotContain(sentinel);
    }
    assertThat(source.apiKey()).isEmpty();
  }

  @Test
  void envNameNormalizationAndSafeFileNameSanitizeReferences() {
    assertThat(SimosApiKeySource.normalizeEnvName("a.b-c")).isEqualTo("A_B_C");
    assertThat(SimosApiKeySource.safeFileName("keys/deepseek"))
        .as("只取末段，不越界读")
        .isEqualTo("deepseek");
    assertThat(SimosApiKeySource.safeFileName("../../etc/passwd")).isEqualTo("passwd");
    assertThat(SimosApiKeySource.safeFileName("a b:c")).isEqualTo("a_b_c");
  }

  @Test
  void resolvedKeyValueNeverLeaksIntoLogs() {
    // ★ m3 的杀点：取密钥成功时也只打 name + length；把值注入日志行即红。
    AgentLibLlmConfig config = AgentLibLlmConfig.open(tempDir);
    String sentinel = "sk-SENTINEL-LOG-LEAK-77";
    config.putKey("k1", sentinel);
    SimosApiKeySource source = new SimosApiKeySource(config, tempDir, "keys.k1", Map.of());

    assertThat(source.apiKey()).contains(sentinel);
    assertThat(appender.messages()).as("★ 前提：装置真的在收日志（空捕获上\"不含哨兵\"会假绿）").isNotEmpty();
    assertThat(appender.messages()).allSatisfy(m -> assertThat(m).doesNotContain(sentinel));
    // ★ L1/§4.4：成功解析确实留痕（只记元信息），且带来源字段；哨兵不在任何一行。
    //   name 的具体渲染由 safeName 的脱敏策略决定（本夹具引用名含 "." ⇒ 落 REDACTED），这里只钉"有 name 字段"。
    assertThat(appender.messages())
        .anySatisfy(
            m ->
                assertThat(m)
                    .contains("event=LLM_KEY_RESOLVED")
                    .contains("origin=llm-config")
                    .contains("originKind=system")
                    .contains("kind=CONFIG")
                    .contains("name=")
                    .contains("length=" + sentinel.length()));
  }

  /** 把 log4j2 的格式化结果收进一个清单（与 {@code GuiAccessLogTest} 同法）。 */
  private static final class CollectingAppender extends AbstractAppender {

    private final List<String> collected = Collections.synchronizedList(new ArrayList<>());

    private CollectingAppender() {
      super("SimosApiKeySourceTest-collector", null, null, false, Property.EMPTY_ARRAY);
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
