package io.mosire.simos.app.logging;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.plugin.ToolSource;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.gm.GmToolUsage;
import io.mosire.simos.app.gm.RecordingToolSource;
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
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>L4/§9 app 工具调用面日志验收</b>（2026-10-23 L1 新增 {@code RecordingToolSource}）：
 *
 * <ul>
 *   <li>被拒工具调用 {@code TOOL_CALL_REJECTED} 必须是 <b>INFO</b>（用户 2026-10-23：「被拒绝肯定走 INFO」）， 带 {@code
 *       origin=tool-call} + {@code originKind=interaction} + tool/caller/code；
 *   <li>成功调用 {@code TOOL_CALL_START/END} 是 DEBUG；
 *   <li><b>载荷纪律</b>：{@code ToolContext.arguments()} 是模型/用户输入，绝不进日志；
 *   <li>app 模块开关（{@code AppLog.tool()} 分类）关掉后工具事件消失、打开后回来。
 * </ul>
 *
 * <p>装置住 app 测试类路径（log4j-core）；{@link #logLinesAreActuallyCaptured} 是前提断言。
 */
class AppToolLoggingTest {

  private static final String APPENDER_NAME = "app-tool-logging-capture";
  private static final String ARGS_CANARY = "PLAINTEXT-ARGS-CANARY-5e07";
  private static final String TOOL_NAME = "simos.fake.reject";

  private LoggerContext context;
  private AbstractConfiguration configuration;
  private Level originalLevel;
  private CollectingAppender appender;

  @BeforeEach
  void installCapture() {
    context = (LoggerContext) LogManager.getContext(false);
    configuration = (AbstractConfiguration) context.getConfiguration();
    originalLevel = configuration.getLoggerConfig(AppLog.TOOL_LOGGER_NAME).getLevel();
    appender = new CollectingAppender();
    appender.start();
    configuration.addAppender(appender);
    setToolLevel(Level.INFO);
  }

  @AfterEach
  void removeCapture() {
    Configurator.setLevel(AppLog.TOOL_LOGGER_NAME, originalLevel);
    context.updateLoggers();
    configuration.getLoggerConfig(AppLog.TOOL_LOGGER_NAME).removeAppender(APPENDER_NAME);
    configuration.removeAppender(APPENDER_NAME);
    context.updateLoggers();
    appender.stop();
  }

  @Test
  void logLinesAreActuallyCaptured() {
    execute(ToolResult.error("E_DENIED", "领域侧拒绝"));

    assertThat(appender.messages())
        .as("★ 捕获为空 ⇒ 整套装置失效，其余断言全部无意义。实得 %s", appender.messages())
        .isNotEmpty();
  }

  @Test
  void rejectedToolCallIsLoggedAsInfoWithInteractionOriginAndWithoutArguments() {
    ToolResult result = execute(ToolResult.error("E_DENIED", "领域侧拒绝"));

    assertThat(result.success()).isFalse();
    assertThat(appender.messages())
        .as("实得 %s", appender.messages())
        .anySatisfy(
            line ->
                assertThat(line)
                    .startsWith("INFO|")
                    .contains("event=TOOL_CALL_REJECTED")
                    .contains("tool=" + TOOL_NAME)
                    .contains("caller=" + AccessToken.DEFAULT.name())
                    .contains("success=false")
                    .contains("code=E_DENIED")
                    .contains("origin=tool-call")
                    .contains("originKind=interaction"));
    assertThat(appender.messages())
        .as("★ 工具参数（模型/用户输入）绝不进日志")
        .allSatisfy(line -> assertThat(line).doesNotContain(ARGS_CANARY));
  }

  @Test
  void successfulToolCallIsLoggedAsDebugWithInteractionOrigin() {
    setToolLevel(Level.DEBUG);
    appender.clear();
    execute(ToolResult.ok("ok"));

    assertEvent(
        appender.messages(),
        "DEBUG",
        "TOOL_CALL_START",
        "tool=" + TOOL_NAME,
        "origin=tool-call",
        "originKind=interaction");
    assertEvent(
        appender.messages(),
        "DEBUG",
        "TOOL_CALL_END",
        "tool=" + TOOL_NAME,
        "success=true",
        "code=",
        "origin=tool-call",
        "originKind=interaction");
  }

  /** ★ 开关控制：app.tool 分类 WARN ⇒ INFO 拒绝消失；调回 INFO ⇒ 回来。 */
  @Test
  void appLevelSwitchHidesAndRevealsToolRejection() {
    setToolLevel(Level.WARN);
    appender.clear();
    execute(ToolResult.error("E_DENIED", "领域侧拒绝"));
    assertThat(appender.messages())
        .as("app.tool 级别 WARN ⇒ INFO 工具拒绝必须消失")
        .noneSatisfy(line -> assertThat(line).contains("event=TOOL_CALL_REJECTED"));

    setToolLevel(Level.INFO);
    appender.clear();
    execute(ToolResult.error("E_DENIED", "领域侧拒绝"));
    assertEvent(
        appender.messages(),
        "INFO",
        "TOOL_CALL_REJECTED",
        "tool=" + TOOL_NAME,
        "origin=tool-call",
        "originKind=interaction");
  }

  private ToolResult execute(ToolResult result) {
    AgentTool tool =
        new AgentTool() {
          @Override
          public String name() {
            return TOOL_NAME;
          }

          @Override
          public ToolResult execute(ToolContext context) {
            return result;
          }
        };
    ToolSource source =
        new ToolSource() {
          @Override
          public String id() {
            return "fake-tool-source";
          }

          @Override
          public List<AgentTool> listTools() {
            return List.of(tool);
          }

          @Override
          public AutoCloseable onChange(Runnable listener) {
            return () -> {};
          }
        };
    ToolContext context =
        new ToolContext(
            AccessToken.DEFAULT,
            AgentPermissionSet.system(),
            Map.of("canary", ARGS_CANARY),
            Map.of("canary", ARGS_CANARY));
    return RecordingToolSource.record(source, new GmToolUsage())
        .listTools()
        .get(0)
        .execute(context);
  }

  private void setToolLevel(Level level) {
    Configurator.setLevel(AppLog.TOOL_LOGGER_NAME, level);
    LoggerConfig current = configuration.getLoggerConfig(AppLog.TOOL_LOGGER_NAME);
    if (!current.getAppenders().containsKey(APPENDER_NAME)) {
      current.addAppender(appender, null, null);
    }
    context.updateLoggers();
  }

  private static void assertEvent(
      List<String> lines, String level, String event, String... fragments) {
    assertThat(lines)
        .as("必须至少有一条 %s|event=%s（实得 %s）", level, event, lines)
        .anySatisfy(
            line -> {
              assertThat(line).startsWith(level + "|");
              assertThat(line).contains("event=" + event);
              for (String fragment : fragments) {
                assertThat(line).contains(fragment);
              }
            });
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
