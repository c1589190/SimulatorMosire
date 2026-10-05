package io.mosire.simos.unit;

import io.mosire.simos.util.log.EventLog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ★★ <b>unit 系统日志门面</b>（2026-10-09 S3a / 家户人口架构 §6；形态照 {@code SocialLog} / {@code EconomyLog}）。
 *
 * <p><b>唯一拼写点</b>：所有 unit 日志的 logger 名都从这里取，调用方不得自己拼字符串。分类如下（全部挂在
 * {@value #ROOT_LOGGER_NAME} 之下 ⇒ 一个开关可整体升降级）：
 *
 * <table border="1">
 *   <caption>分类与用途</caption>
 *   <tr><th>取用方法</th><th>logger 名</th><th>记什么</th></tr>
 *   <tr><td>{@link #household()}</td><td>{@code .household}</td><td>unit 侧家户容纳生命周期：整体替换、加入、移出</td></tr>
 *   <tr><td>{@link #trace()}</td><td>{@code .trace}</td><td>逐项明细（逐家户 id、逐 unit 列表）</td></tr>
 * </table>
 *
 * <p><b>级别约定</b>（消费者按级别裁剪）：
 *
 * <ul>
 *   <li><b>INFO</b>：生命周期事件——{@code UNIT_HOUSEHOLDS_SET} / {@code UNIT_HOUSEHOLD_ASSIGN} /
 *       {@code UNIT_HOUSEHOLD_DETACH}；
 *   <li><b>DEBUG</b>：对账（家户位置 ↔ unit 列表一致性的汇总读数）；
 *   <li><b>TRACE</b>：逐项明细（逐家户 id、逐 unit 列表）。
 * </ul>
 *
 * <p><b>打开方式</b>：应用配置（{@code simos-app/src/main/resources/log4j2.xml}）把 {@value #ROOT_LOGGER_NAME}
 * 默认设为 INFO；完整调试：
 *
 * <pre>{@code
 * ./mvnw ... -Dsimos.unit.logLevel=DEBUG          # 对账/汇总
 * ./mvnw ... -Dsimos.unit.logLevel=DEBUG \
 *            -Dsimos.unit.traceLevel=TRACE        # 再开逐项明细
 * }</pre>
 *
 * <p><b>纪律</b>：不记密钥、不记载荷明文；只记稳定 id、数量、原因档、汇总值。日志不参与状态、不改公式；日志失败不得影响命令提交。
 */
public final class UnitLog {

  /** unit 日志根名（整体升降级的唯一开关）。 */
  public static final String ROOT_LOGGER_NAME = "io.mosire.simos.unit";

  public static final String HOUSEHOLD_LOGGER_NAME = ROOT_LOGGER_NAME + ".household";
  public static final String TRACE_LOGGER_NAME = ROOT_LOGGER_NAME + ".trace";

  private static final Logger HOUSEHOLD = LoggerFactory.getLogger(HOUSEHOLD_LOGGER_NAME);
  private static final Logger TRACE = LoggerFactory.getLogger(TRACE_LOGGER_NAME);

  private UnitLog() {}

  /** unit 侧家户容纳生命周期：整体替换、加入、移出。 */
  public static Logger household() {
    return HOUSEHOLD;
  }

  /** 逐项明细（逐家户 id、逐 unit 列表）。 */
  public static Logger trace() {
    return TRACE;
  }

  /**
   * 结构化行拼接：{@code kv("unit", "u-1", "count", 3)} ⇒ {@code unit=u-1 count=3}。
   *
   * <p>奇数个参数是拼写错误 ⇒ 当场抛（不静默丢字段）；调用方对 TRACE 级应先 {@code isTraceEnabled()} 守卫。
   */
  public static String kv(Object... keyValues) {
    // ★ 2026-10-09：实现收口到 util 的 EventLog.kv（本门面只留 logger 命名空间）。
    return EventLog.kv(keyValues);
  }
}
