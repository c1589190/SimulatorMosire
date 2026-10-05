package io.mosire.simos.app;

import io.mosire.simos.util.log.EventLog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ★★ <b>app 模块日志门面</b>（P1.3 全项目 Log 骨架，形态照 {@code EconomyLog} / {@code SocialLog} / {@code
 * UnitLog}，2026-10-09 用户裁定"Log 必须加"）。
 *
 * <p><b>唯一拼写点</b>：app 模块<b>新增</b>日志的 logger 名都从这里取，调用方不得自己拼字符串。分类如下（全部挂在 {@value #ROOT_LOGGER_NAME}
 * 之下 ⇒ 一个开关可整体升降级）：
 *
 * <table border="1">
 *   <caption>分类与用途</caption>
 *   <tr><th>取用方法</th><th>logger 名</th><th>记什么</th></tr>
 *   <tr><td>{@link #shell()}</td><td>{@code .shell}</td><td>组合根启动/关闭/GUI 服务生命周期</td></tr>
 *   <tr><td>{@link #tool()}</td><td>{@code .tool}</td><td>GM/决策人工具入口与结局（命令类型、工具名）</td></tr>
 *   <tr><td>{@link #time()}</td><td>{@code .time}</td><td>组合根时间参与者（人口-经济/产权/历法）</td></tr>
 *   <tr><td>{@link #decision()}</td><td>{@code .decision}</td><td>决策人运行流（一轮开始/结束/裁剪）</td></tr>
 *   <tr><td>{@link #trace()}</td><td>{@code .trace}</td><td><b>逐工具调用/逐轮明细</b></td></tr>
 * </table>
 *
 * <p><b>级别约定</b>（消费者按级别裁剪）：
 *
 * <ul>
 *   <li><b>INFO</b>：组合根生命周期与每轮发生了什么；
 *   <li><b>DEBUG</b>：工具入口/时间参与者的阶段汇总与原因档；
 *   <li><b>TRACE</b>：逐工具调用、逐决策轮明细。
 * </ul>
 *
 * <p><b>打开方式</b>：应用配置（{@code simos-app/src/main/resources/log4j2.xml}）把 {@value #ROOT_LOGGER_NAME}
 * 默认设为 INFO；完整调试：
 *
 * <pre>{@code
 * ./mvnw ... -Dsimos.app.logLevel=DEBUG        # 阶段/汇总
 * ./mvnw ... -Dsimos.app.logLevel=DEBUG \
 *            -Dsimos.app.traceLevel=TRACE      # 再开逐条明细
 * }</pre>
 *
 * <p><b>纪律</b>：不记密钥、不记载荷明文；只记稳定 id、数量、原因档、汇总值。日志只读、不写状态、不改公式； 日志失败不影响结算；行一律以 {@code event=...
 * key=value} 起（{@link #kv} 是唯一拼接点）。
 */
public final class AppLog {

  /** app 日志根名（整体升降级的唯一开关）。 */
  public static final String ROOT_LOGGER_NAME = "io.mosire.simos.app";

  public static final String SHELL_LOGGER_NAME = ROOT_LOGGER_NAME + ".shell";
  public static final String TOOL_LOGGER_NAME = ROOT_LOGGER_NAME + ".tool";
  public static final String TIME_LOGGER_NAME = ROOT_LOGGER_NAME + ".time";
  public static final String DECISION_LOGGER_NAME = ROOT_LOGGER_NAME + ".decision";
  public static final String TRACE_LOGGER_NAME = ROOT_LOGGER_NAME + ".trace";
  private static final Logger SHELL = LoggerFactory.getLogger(SHELL_LOGGER_NAME);
  private static final Logger TOOL = LoggerFactory.getLogger(TOOL_LOGGER_NAME);
  private static final Logger TIME = LoggerFactory.getLogger(TIME_LOGGER_NAME);
  private static final Logger DECISION = LoggerFactory.getLogger(DECISION_LOGGER_NAME);
  private static final Logger TRACE = LoggerFactory.getLogger(TRACE_LOGGER_NAME);

  private AppLog() {}

  /** 组合根启动/关闭/GUI 服务生命周期。 */
  public static Logger shell() {
    return SHELL;
  }

  /** GM/决策人工具入口与结局（命令类型、工具名）。 */
  public static Logger tool() {
    return TOOL;
  }

  /** 组合根时间参与者（人口-经济/产权/历法）。 */
  public static Logger time() {
    return TIME;
  }

  /** 决策人运行流（一轮开始/结束/裁剪）。 */
  public static Logger decision() {
    return DECISION;
  }

  /** <b>逐工具调用/逐轮明细</b>。 */
  public static Logger trace() {
    return TRACE;
  }

  /**
   * 结构化行拼接：{@code kv("id", "r1", "hexes", 12)} ⇒ {@code id=r1 hexes=12}。
   *
   * <p>只在日志真的会输出时由调用方调用（配合 {@code isDebugEnabled()}/{@code isTraceEnabled()} 守卫），
   * 或接受一次字符串分配。奇数个参数是拼写错误 ⇒ 当场抛（不静默丢字段）。
   */
  public static String kv(Object... keyValues) {
    // ★ 2026-10-09：实现收口到 util 的 EventLog.kv（本门面只留 logger 命名空间）。
    return EventLog.kv(keyValues);
  }
}
