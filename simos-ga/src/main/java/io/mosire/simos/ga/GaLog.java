package io.mosire.simos.ga;

import io.mosire.simos.util.log.EventLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ★★ <b>ga 模块日志门面</b>（形态照 {@code SdLog} / {@code AppLog} / {@code EconomyLog}，2026-10-09 用户裁定"Log
 * 必须加" + 2026-10-23 各模块独立门面）。
 *
 * <p><b>唯一拼写点</b>：ga 模块<b>新增</b>日志的 logger 名都从这里取，调用方不得自己拼字符串。分类如下（全部挂在 {@value #ROOT_LOGGER_NAME}
 * 之下 ⇒ 一个开关可整体升降级）：
 *
 * <table border="1">
 *   <caption>分类与用途</caption>
 *   <tr><th>取用方法</th><th>logger 名</th><th>记什么</th></tr>
 *   <tr><td>{@link #decision()}</td><td>{@code .decision}</td><td>决策人运行流（一轮开始/结束/预算中止/逐次工具调用/开场快照）</td></tr>
 *   <tr><td>{@link #tool()}</td><td>{@code .tool}</td><td>工具面的每一次实际执行（工具名 + 结果码 + 时刻；GM 口留痕与日志共用一个装饰器）</td></tr>
 *   <tr><td>{@link #approval()}</td><td>{@code .approval}</td><td>审批控制面（待裁决清单、裁决结果、未接入）</td></tr>
 *   <tr><td>{@link #trace()}</td><td>{@code .trace}</td><td>逐轮逐工具的明细（默认关；与 decision 同级但可单独升降）</td></tr>
 * </table>
 *
 * <p><b>级别约定</b>（消费者按级别裁剪）：
 *
 * <ul>
 *   <li><b>INFO</b>：一轮开始/结束、生命周期式事件；
 *   <li><b>DEBUG</b>：逐次工具调用（成功与否 + 码）、原因档；
 *   <li><b>TRACE</b>：更细的逐条明细（本门面已备好 {@link #trace()} 出口，供后续批次使用）；
 *   <li><b>WARN</b>：不接受降级的异常档（预算中止、快照跳过）。
 * </ul>
 *
 * <p><b>打开方式</b>：应用配置（{@code simos-app/src/main/resources/log4j2.xml}）把 {@value #ROOT_LOGGER_NAME}
 * 默认设为 INFO；完整调试：
 *
 * <pre>{@code
 * ./mvnw ... -Dsimos.ga.logLevel=DEBUG        # 逐次工具调用
 * ./mvnw ... -Dsimos.ga.logLevel=DEBUG \
 *            -Dsimos.ga.traceLevel=TRACE      # 再开逐条明细
 * }</pre>
 *
 * <p>★ <b>与 app 的关系</b>：决策人运行流从 {@code simos-app} 迁入本模块（B2a）后，它的事件从 {@code
 * io.mosire.simos.app.decision} 挪到 {@value #ROOT_LOGGER_NAME} ⇒ 开关也随之从 {@code simos.app.logLevel}
 * 换成 {@code simos.ga.logLevel}（app 侧未迁走的装配/结算点仍走 {@code AppLog}）。
 *
 * <p><b>纪律</b>：不记密钥、不记载荷明文；只记稳定 id、数量、原因档、汇总值。日志只读、不写状态、不改公式； 日志失败不影响结算；行一律以 {@code event=...
 * key=value} 起（{@link #kv} 是唯一拼接点）。
 */
public final class GaLog {

  /** ga 日志根名（整体升降级的唯一开关）。 */
  public static final String ROOT_LOGGER_NAME = "io.mosire.simos.ga";

  public static final String DECISION_LOGGER_NAME = ROOT_LOGGER_NAME + ".decision";
  public static final String TOOL_LOGGER_NAME = ROOT_LOGGER_NAME + ".tool";
  public static final String APPROVAL_LOGGER_NAME = ROOT_LOGGER_NAME + ".approval";
  public static final String TRACE_LOGGER_NAME = ROOT_LOGGER_NAME + ".trace";
  private static final Logger DECISION = LoggerFactory.getLogger(DECISION_LOGGER_NAME);
  private static final Logger TOOL = LoggerFactory.getLogger(TOOL_LOGGER_NAME);
  private static final Logger APPROVAL = LoggerFactory.getLogger(APPROVAL_LOGGER_NAME);
  private static final Logger TRACE = LoggerFactory.getLogger(TRACE_LOGGER_NAME);

  private GaLog() {}

  /** 决策人运行流（一轮开始/结束/预算中止/逐次工具调用/开场快照）。 */
  public static Logger decision() {
    return DECISION;
  }

  /**
   * 工具面的每一次**实际执行**（工具名 + 结果码 + 时刻；见 {@link io.mosire.simos.ga.tools.RecordingToolSource}）。
   *
   * <p>★ 2026-10-12 B2b：{@code RecordingToolSource} 自 app 迁入本模块 ⇒ 它的事件从 {@code AppLog.tool()}
   * （{@code io.mosire.simos.app.tool}）挪到这里；开关随之从 {@code -Dsimos.app.logLevel} 换成 {@code
   * -Dsimos.ga.logLevel}（**只影响输出的 logger 名与开关，不改任何状态/载荷/拒绝码**）。
   */
  public static Logger tool() {
    return TOOL;
  }

  /**
   * 审批控制面（待裁决清单的读 / 裁决 / 未接入）。
   *
   * <p>★ 2026-10-12 B2b：{@code simos.gm.approvals} / {@code simos.gm.approve} 两条工具自 app 迁入本模块 ⇒
   * 它们的事件从 {@code AppLog.approval()}（{@code io.mosire.simos.app.approval}）挪到这里（同 {@link #tool()}
   * 的处置）。
   */
  public static Logger approval() {
    return APPROVAL;
  }

  /** 逐轮逐工具的明细（默认关；与 {@link #decision()} 同级但可单独升降）。 */
  public static Logger trace() {
    return TRACE;
  }

  /**
   * 结构化行拼接：{@code kv("dm", "dm-fra", "llmCalls", 3)} ⇒ {@code dm=dm-fra llmCalls=3}。
   *
   * <p>只在日志真的会输出时由调用方调用（配合 {@code isDebugEnabled()}/{@code isTraceEnabled()} 守卫），
   * 或接受一次字符串分配。奇数个参数是拼写错误 ⇒ 当场抛（不静默丢字段）。
   */
  public static String kv(Object... keyValues) {
    // ★ 2026-10-09：实现收口到 util 的 EventLog.kv（本门面只留 logger 命名空间）。
    return EventLog.kv(keyValues);
  }
}
