package io.mosire.simos.app.logging;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.Shell;
import io.mosire.simos.app.ShellConfig;
import java.nio.file.Files;
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
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>L4/§9 app 组合根生命周期日志验收</b>：{@link Shell#start} 的装配事件（{@code SHELL_ASSEMBLED}）必须是 INFO、带
 * {@code origin=shell-lifecycle} + {@code originKind=system}，且随 app 模块开关升降级。
 *
 * <p>装置住 app 测试类路径（log4j-core）；{@link #logLinesAreActuallyCaptured} 是前提断言。
 */
class AppShellLoggingTest {

  private static final String APPENDER_NAME = "app-shell-logging-capture";

  @TempDir Path tempDir;

  private LoggerContext context;
  private AbstractConfiguration configuration;
  private Level originalLevel;
  private CollectingAppender appender;
  private Shell shell;

  @BeforeEach
  void installCapture() {
    context = (LoggerContext) LogManager.getContext(false);
    configuration = (AbstractConfiguration) context.getConfiguration();
    originalLevel = configuration.getLoggerConfig(AppLog.ROOT_LOGGER_NAME).getLevel();
    appender = new CollectingAppender();
    appender.start();
    configuration.addAppender(appender);
    setAppLevel(Level.INFO);
  }

  @AfterEach
  void removeCapture() {
    if (shell != null) {
      shell.close();
    }
    Configurator.setLevel(AppLog.ROOT_LOGGER_NAME, originalLevel);
    context.updateLoggers();
    configuration.getLoggerConfig(AppLog.ROOT_LOGGER_NAME).removeAppender(APPENDER_NAME);
    configuration.removeAppender(APPENDER_NAME);
    context.updateLoggers();
    appender.stop();
  }

  @Test
  void logLinesAreActuallyCaptured() {
    shell = Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0, 0));

    assertThat(appender.messages()).as("★ 捕获为空 ⇒ 整套装置失效。实得 %s", appender.messages()).isNotEmpty();
  }

  @Test
  void shellAssemblyIsLoggedAsInfoWithSystemOrigin() {
    shell = Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0, 0));

    assertThat(appender.messages())
        .as("实得 %s", appender.messages())
        .anySatisfy(
            line ->
                assertThat(line)
                    .startsWith("INFO|")
                    .contains("event=SHELL_ASSEMBLED")
                    .contains("origin=shell-lifecycle")
                    .contains("originKind=system")
                    .contains("store=")
                    .contains("codec=")
                    .contains("handler=")
                    .contains("participant=")
                    .contains("tool=")
                    .contains("mapId="));
  }

  /** ★ 开关控制：app 根级别 WARN ⇒ 装配 INFO 消失；调回 INFO ⇒ 回来。 */
  @Test
  void appLevelSwitchHidesAndRevealsShellAssembly() throws Exception {
    setAppLevel(Level.WARN);
    appender.clear();
    shell = Shell.start(ShellConfig.defaults(tempDir).withPorts(0, 0, 0));
    assertThat(appender.messages())
        .as("app 根级别 WARN ⇒ SHELL_ASSEMBLED 必须消失")
        .noneSatisfy(line -> assertThat(line).contains("event=SHELL_ASSEMBLED"));

    setAppLevel(Level.INFO);
    appender.clear();
    Path secondStore = Files.createDirectories(tempDir.resolve("second"));
    Shell second = Shell.start(ShellConfig.defaults(secondStore).withPorts(0, 0, 0));
    try {
      assertThat(appender.messages())
          .as("调回 INFO ⇒ 装配事件回来")
          .anySatisfy(line -> assertThat(line).contains("event=SHELL_ASSEMBLED"));
    } finally {
      second.close();
    }
  }

  private void setAppLevel(Level level) {
    Configurator.setLevel(AppLog.ROOT_LOGGER_NAME, level);
    LoggerConfig current = configuration.getLoggerConfig(AppLog.ROOT_LOGGER_NAME);
    if (!current.getAppenders().containsKey(APPENDER_NAME)) {
      current.addAppender(appender, null, null);
    }
    context.updateLoggers();
  }

  /** 采集 appender：记 {@code level|message}，不碰状态。 */
  private static final class CollectingAppender extends AbstractAppender {

    private final List<String> collected = Collections.synchronizedList(new ArrayList<>());

    private CollectingAppender() {
      super(APPENDER_NAME, null, null, false, Property.EMPTY_ARRAY);
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

    private void clear() {
      collected.clear();
    }
  }
}
