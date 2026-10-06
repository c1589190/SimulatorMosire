package io.mosire.simos.app.logging;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.approval.ApprovalChannel;
import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.access.LoggingApprovalChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
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
 * ★★ <b>L4/§9 app 审批链日志验收</b>（2026-10-23 L1 新增 {@link LoggingApprovalChannel}）：
 *
 * <ul>
 *   <li>pending / approved / denied / timeout 四个可观测点各一条 <b>INFO</b>，带 {@code origin=approval} +
 *       {@code originKind=interaction} 与 approvalId/tool/caller/classKey；
 *   <li>{@code summary}/{@code digest} 是载荷面，绝不进日志；
 *   <li>app 模块开关（{@code io.mosire.simos.app} 级别）关掉后审批日志消失、打开后回来。
 * </ul>
 *
 * <p>装置住 app 测试类路径（log4j-core）；{@link #logLinesAreActuallyCaptured} 是前提断言——空捕获上的"不含哨兵"会假绿。
 */
class ApprovalLoggingTest {

  private static final String APPENDER_NAME = "approval-logging-capture";
  private static final String APPROVAL_ID = "ap-log-1";
  private static final String SUMMARY_CANARY = "PLAINTEXT-SUMMARY-CANARY-3f11";
  private static final String DIGEST_CANARY = "PLAINTEXT-DIGEST-CANARY-9c02";

  private LoggerContext context;
  private AbstractConfiguration configuration;
  private Level originalLevel;
  private CollectingAppender appender;
  private FakeChannel delegate;
  private LoggingApprovalChannel channel;

  @BeforeEach
  void installCapture() {
    context = (LoggerContext) LogManager.getContext(false);
    configuration = (AbstractConfiguration) context.getConfiguration();
    originalLevel = configuration.getLoggerConfig(AppLog.ROOT_LOGGER_NAME).getLevel();

    appender = new CollectingAppender();
    appender.start();
    configuration.addAppender(appender);
    setAppLevel(Level.INFO);

    delegate = new FakeChannel();
    channel = new LoggingApprovalChannel(delegate);
  }

  @AfterEach
  void removeCapture() {
    Configurator.setLevel(AppLog.ROOT_LOGGER_NAME, originalLevel);
    context.updateLoggers();
    configuration.getLoggerConfig(AppLog.ROOT_LOGGER_NAME).removeAppender(APPENDER_NAME);
    configuration.removeAppender(APPENDER_NAME);
    context.updateLoggers();
    appender.stop();
  }

  @Test
  void logLinesAreActuallyCaptured() {
    channel.publish(request(APPROVAL_ID));

    assertThat(appender.messages())
        .as("★ 捕获为空 ⇒ 整套装置失效，其余断言全部无意义。实得 %s", appender.messages())
        .isNotEmpty();
  }

  @Test
  void allFourApprovalTouchpointsAreLoggedAsInfoWithInteractionOrigin() {
    channel.publish(request(APPROVAL_ID));

    delegate.decision = ApprovalDecision.DENY;
    assertThat(channel.await(APPROVAL_ID, Duration.ofSeconds(1))).contains(ApprovalDecision.DENY);

    delegate.decision = ApprovalDecision.APPROVE_ONCE;
    assertThat(channel.await("ap-log-2", Duration.ofSeconds(1)))
        .contains(ApprovalDecision.APPROVE_ONCE);

    delegate.timedOut = true;
    assertThat(channel.await("ap-log-3", Duration.ofSeconds(1))).isEmpty();

    assertEvent(
        appender.messages(),
        "APPROVAL_PENDING",
        "approvalId=" + APPROVAL_ID,
        "tool=simos.command.submit",
        "caller=" + AccessToken.DEFAULT.name(),
        "classKey=simos.command.submit",
        "origin=approval",
        "originKind=interaction");
    assertEvent(
        appender.messages(),
        "APPROVAL_DENIED",
        "approvalId=" + APPROVAL_ID,
        "decision=DENY",
        "origin=approval",
        "originKind=interaction");
    assertEvent(
        appender.messages(),
        "APPROVAL_APPROVED",
        "approvalId=ap-log-2",
        "decision=APPROVE_ONCE",
        "origin=approval",
        "originKind=interaction");
    assertEvent(
        appender.messages(),
        "APPROVAL_TIMEOUT",
        "approvalId=ap-log-3",
        "timeoutMs=1000",
        "origin=approval",
        "originKind=interaction");
    assertThat(appender.messages())
        .as("四个可观测点都必须是 INFO（不是 DEBUG/WARN）")
        .filteredOn(line -> line.contains("event=APPROVAL_"))
        .isNotEmpty()
        .allSatisfy(line -> assertThat(line).startsWith("INFO|"));
  }

  /** ★ 密钥/载荷纪律：审批请求自报的 summary/digest 可能含参数与文本，绝不进日志。 */
  @Test
  void requestSummaryAndDigestNeverAppearInLogs() {
    channel.publish(request(APPROVAL_ID));
    delegate.decision = ApprovalDecision.APPROVE_ONCE;
    channel.await(APPROVAL_ID, Duration.ofSeconds(1));

    assertThat(appender.messages()).as("前提：先得真收到日志").isNotEmpty();
    assertThat(appender.messages())
        .allSatisfy(
            line -> {
              assertThat(line).doesNotContain(SUMMARY_CANARY);
              assertThat(line).doesNotContain(DIGEST_CANARY);
            });
  }

  /** ★ 开关控制：app 模块级别 WARN ⇒ INFO 审批日志消失；调回 INFO ⇒ 回来（判别力：改坏级别即红）。 */
  @Test
  void appLevelSwitchHidesAndRevealsApprovalEvents() {
    setAppLevel(Level.WARN);
    appender.clear();
    channel.publish(request(APPROVAL_ID));
    delegate.decision = ApprovalDecision.DENY;
    channel.await(APPROVAL_ID, Duration.ofSeconds(1));
    assertThat(appender.messages())
        .as("app 级别 WARN ⇒ INFO 审批事件必须消失")
        .noneSatisfy(line -> assertThat(line).contains("event=APPROVAL_"));

    setAppLevel(Level.INFO);
    appender.clear();
    channel.publish(request("ap-log-4"));
    assertEvent(appender.messages(), "APPROVAL_PENDING", "approvalId=ap-log-4");
  }

  private void setAppLevel(Level level) {
    Configurator.setLevel(AppLog.ROOT_LOGGER_NAME, level);
    LoggerConfig current = configuration.getLoggerConfig(AppLog.ROOT_LOGGER_NAME);
    if (!current.getAppenders().containsKey(APPENDER_NAME)) {
      current.addAppender(appender, null, null);
    }
    context.updateLoggers();
  }

  private static ApprovalRequest request(String id) {
    long now = System.currentTimeMillis();
    return new ApprovalRequest(
        id,
        "simos.command.submit",
        "simos.command.submit",
        SUMMARY_CANARY,
        DIGEST_CANARY,
        now,
        now + 60_000L,
        AccessToken.DEFAULT.name(),
        "agent:approval-log-test",
        AskKind.SENSITIVE,
        null);
  }

  private static void assertEvent(List<String> lines, String event, String... fragments) {
    assertThat(lines)
        .as("必须至少有一条 event=%s（实得 %s）", event, lines)
        .anySatisfy(
            line -> {
              assertThat(line).contains("event=" + event);
              for (String fragment : fragments) {
                assertThat(line).contains(fragment);
              }
            });
  }

  /** 只记录审批通道交互的假委托者（不改变判定语义的验证：返回值逐字透传）。 */
  private static final class FakeChannel implements ApprovalChannel {

    private ApprovalDecision decision = ApprovalDecision.APPROVE_ONCE;
    private boolean timedOut;

    @Override
    public String name() {
      return "fake-approval";
    }

    @Override
    public boolean available() {
      return true;
    }

    @Override
    public void publish(ApprovalRequest request) {}

    @Override
    public Optional<ApprovalDecision> await(String id, Duration timeout) {
      return timedOut ? Optional.empty() : Optional.of(decision);
    }
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
