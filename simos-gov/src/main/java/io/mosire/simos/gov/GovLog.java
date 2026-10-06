package io.mosire.simos.gov;

import io.mosire.simos.util.log.EventLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ★★ <b>gov 模块日志门面</b>（P1.3 全项目 Log 骨架，形态照 {@code EconomyLog} / {@code SocialLog} / {@code
 * UnitLog}，2026-10-09 用户裁定"Log 必须加"）。
 *
 * <p><b>唯一拼写点</b>：gov 模块<b>新增</b>日志的 logger 名都从这里取，调用方不得自己拼字符串。分类如下（全部挂在 {@value #ROOT_LOGGER_NAME}
 * 之下 ⇒ 一个开关可整体升降级）：
 *
 * <table border="1">
 *   <caption>分类与用途</caption>
 *   <tr><th>取用方法</th><th>logger 名</th><th>记什么</th></tr>
 *   <tr><td>{@link #daily()}</td><td>{@code .daily}</td><td>GOV 日结算（供给/治安/文书/俸禄支付）</td></tr>
 *   <tr><td>{@link #demand()}</td><td>{@code .demand}</td><td>逐格行政需求与效率读数</td></tr>
 *   <tr><td>{@link #codec()}</td><td>{@code .codec}</td><td>GovCodec 编解码/变更集施加</td></tr>
 *   <tr><td>{@link #trace()}</td><td>{@code .trace}</td><td><b>逐单位/逐格明细</b></td></tr>
 * </table>
 *
 * <p><b>级别约定</b>（消费者按级别裁剪）：
 *
 * <ul>
 *   <li><b>INFO</b>：日结算生命周期（START/END）——处理的 office 数、应付款、信号；
 *   <li><b>DEBUG</b>：需求/效率/欠俸的汇总读数、无座跳过与判据；
 *   <li><b>TRACE</b>：逐 office、逐 due、逐 signal 明细。
 * </ul>
 *
 * <p><b>打开方式</b>：应用配置（{@code simos-app/src/main/resources/log4j2.xml}）把 {@value #ROOT_LOGGER_NAME}
 * 默认设为 INFO；完整调试：
 *
 * <pre>{@code
 * ./mvnw ... -Dsimos.gov.logLevel=DEBUG        # 阶段/汇总
 * ./mvnw ... -Dsimos.gov.logLevel=DEBUG \
 *            -Dsimos.gov.traceLevel=TRACE      # 再开逐条明细
 * }</pre>
 *
 * <p><b>纪律</b>：不记密钥、不记载荷明文；只记稳定 id、数量、原因档、汇总值。日志只读、不写状态、不改公式； 日志失败不影响结算；行一律以 {@code event=...
 * key=value} 起（{@link #kv} 是唯一拼接点）。
 */
public final class GovLog {

  /** gov 日志根名（整体升降级的唯一开关）。 */
  public static final String ROOT_LOGGER_NAME = "io.mosire.simos.gov";

  public static final String DAILY_LOGGER_NAME = ROOT_LOGGER_NAME + ".daily";
  public static final String DEMAND_LOGGER_NAME = ROOT_LOGGER_NAME + ".demand";
  public static final String CODEC_LOGGER_NAME = ROOT_LOGGER_NAME + ".codec";
  public static final String TRACE_LOGGER_NAME = ROOT_LOGGER_NAME + ".trace";
  private static final Logger DAILY = LoggerFactory.getLogger(DAILY_LOGGER_NAME);
  private static final Logger DEMAND = LoggerFactory.getLogger(DEMAND_LOGGER_NAME);
  private static final Logger CODEC = LoggerFactory.getLogger(CODEC_LOGGER_NAME);
  private static final Logger TRACE = LoggerFactory.getLogger(TRACE_LOGGER_NAME);

  private GovLog() {}

  /** GOV 日结算（供给/治安/文书/俸禄支付）。 */
  public static Logger daily() {
    return DAILY;
  }

  /** 逐格行政需求与效率读数。 */
  public static Logger demand() {
    return DEMAND;
  }

  /** GovCodec 编解码与变更集施加。 */
  public static Logger codec() {
    return CODEC;
  }

  /** <b>逐单位/逐格明细</b>。 */
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
