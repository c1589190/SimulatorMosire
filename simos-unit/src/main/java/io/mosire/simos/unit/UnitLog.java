package io.mosire.simos.unit;

import io.mosire.simos.util.log.EventLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ★★ <b>unit 系统日志门面</b>（2026-10-09 S3a / 家户人口架构 §6；形态照 {@code SocialLog} / {@code EconomyLog}）。
 *
 * <p><b>唯一拼写点</b>：所有 unit 日志的 logger 名都从这里取，调用方不得自己拼字符串。分类如下（全部挂在 {@value #ROOT_LOGGER_NAME} 之下 ⇒
 * 一个开关可整体升降级）：
 *
 * <table border="1">
 *   <caption>分类与用途</caption>
 *   <tr><th>取用方法</th><th>logger 名</th><th>记什么</th></tr>
 *   <tr><td>{@link #household()}</td><td>{@code .household}</td><td>unit 侧家户容纳生命周期：整体替换、加入、移出</td></tr>
 *   <tr><td>{@link #command()}</td><td>{@code .command}</td><td>单位/编制/公务/路线写命令处理（成功与具名拒绝）</td></tr>
 *   <tr><td>{@link #advance()}</td><td>{@code .advance}</td><td>每 tick 推进：在途移动/整支搬运/回归重规划</td></tr>
 *   <tr><td>{@link #codec()}</td><td>{@code .codec}</td><td>UnitCodec 编解码与变更集施加（只记元信息，不记 JSON 原文）</td></tr>
 *   <tr><td>{@link #resolve()}</td><td>{@code .resolve}</td><td>UnitResolver 空候选与装配故障诊断（不逐次记成功查询）</td></tr>
 *   <tr><td>{@link #trace()}</td><td>{@code .trace}</td><td>逐项明细（逐家户 id、逐 unit 列表、逐单位移动）</td></tr>
 * </table>
 *
 * <p><b>级别约定</b>（消费者按级别裁剪）：
 *
 * <ul>
 *   <li><b>INFO</b>：生命周期事件——命令写口成功/被拒（{@code *_APPLIED} / {@code *_REJECTED}）、推进 START/END；
 *   <li><b>DEBUG</b>：对账/判据（家户位置 ↔ unit 列表一致性、字段级拒绝理由、逐单位跳过原因）；
 *   <li><b>TRACE</b>：逐项明细（逐家户 id、逐 unit 列表、逐单位移动）。
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
  public static final String COMMAND_LOGGER_NAME = ROOT_LOGGER_NAME + ".command";
  public static final String ADVANCE_LOGGER_NAME = ROOT_LOGGER_NAME + ".advance";
  public static final String CODEC_LOGGER_NAME = ROOT_LOGGER_NAME + ".codec";
  public static final String RESOLVE_LOGGER_NAME = ROOT_LOGGER_NAME + ".resolve";
  public static final String TRACE_LOGGER_NAME = ROOT_LOGGER_NAME + ".trace";

  private static final Logger HOUSEHOLD = LoggerFactory.getLogger(HOUSEHOLD_LOGGER_NAME);
  private static final Logger COMMAND = LoggerFactory.getLogger(COMMAND_LOGGER_NAME);
  private static final Logger ADVANCE = LoggerFactory.getLogger(ADVANCE_LOGGER_NAME);
  private static final Logger CODEC = LoggerFactory.getLogger(CODEC_LOGGER_NAME);
  private static final Logger RESOLVE = LoggerFactory.getLogger(RESOLVE_LOGGER_NAME);
  private static final Logger TRACE = LoggerFactory.getLogger(TRACE_LOGGER_NAME);

  private UnitLog() {}

  /** unit 侧家户容纳生命周期：整体替换、加入、移出。 */
  public static Logger household() {
    return HOUSEHOLD;
  }

  /** 单位/编制/公务/路线写命令处理：成功 {@code *_APPLIED} 与具名拒绝 {@code *_REJECTED}。 */
  public static Logger command() {
    return COMMAND;
  }

  /** 每 tick 推进：在途移动/整支搬运/回归重规划（START/END/跳过判据）。 */
  public static Logger advance() {
    return ADVANCE;
  }

  /** UnitCodec 编解码与变更集施加（只记元信息，不记 JSON 原文）。 */
  public static Logger codec() {
    return CODEC;
  }

  /** UnitResolver 空候选与装配故障诊断（不逐次记成功查询）。 */
  public static Logger resolve() {
    return RESOLVE;
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
