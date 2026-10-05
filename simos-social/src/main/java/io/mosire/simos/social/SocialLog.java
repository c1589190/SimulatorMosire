package io.mosire.simos.social;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ★★ <b>social 系统日志门面</b>（2026-10-09 家户/人口架构 §6；形态照 {@code EconomyLog}）。
 *
 * <p><b>唯一拼写点</b>：所有 social 日志的 logger 名都从这里取，调用方不得自己拼字符串。分类如下（全部挂在 {@value #ROOT_LOGGER_NAME} 之下 ⇒
 * 一个开关可整体升降级）：
 *
 * <table border="1">
 *   <caption>分类与用途</caption>
 *   <tr><th>取用方法</th><th>logger 名</th><th>记什么</th></tr>
 *   <tr><td>{@link #household()}</td><td>{@code .household}</td><td>家户创建、位置、画像、成员增删、转移、率设置</td></tr>
 *   <tr><td>{@link #population()}</td><td>{@code .population}</td><td>逐家户出生/死亡/调整汇总</td></tr>
 *   <tr><td>{@link #event()}</td><td>{@code .event}</td><td>逐事件落账</td></tr>
 *   <tr><td>{@link #workOrder()}</td><td>{@code .workorder}</td><td>Social 工单受理：汇总/成功/拒收/幂等命中（reason+source 必记）</td></tr>
 *   <tr><td>{@link #trace()}</td><td>{@code .trace}</td><td><b>逐批次/逐移出移入明细</b></td></tr>
 * </table>
 *
 * <p><b>级别约定</b>（消费者按级别裁剪）：
 *
 * <ul>
 *   <li><b>INFO</b>：生命周期与汇总（建户、位置/画像、成员增删、出生/死亡/转移汇总、守恒检查、工单受理汇总）；
 *   <li><b>DEBUG</b>：池/对账/守恒的中间读数（为什么这条事件被接受/拒绝）；
 *   <li><b>TRACE</b>：逐条明细（逐批次、逐移出移入、工单逐操作）。
 * </ul>
 *
 * <p><b>打开方式</b>：应用配置（{@code simos-app/src/main/resources/log4j2.xml}）把 {@value #ROOT_LOGGER_NAME}
 * 默认设为 INFO；完整调试：
 *
 * <pre>{@code
 * ./mvnw ... -Dsimos.social.logLevel=DEBUG          # 池/对账/守恒
 * ./mvnw ... -Dsimos.social.logLevel=DEBUG \
 *            -Dsimos.social.traceLevel=TRACE        # 再开逐条明细
 * }</pre>
 *
 * <p><b>纪律</b>：不记密钥、不记载荷明文；只记稳定 id、数量、原因档、汇总值。日志不参与状态、不改公式；日志失败不得影响结算。
 */
public final class SocialLog {

  /** social 日志根名（整体升降级的唯一开关）。 */
  public static final String ROOT_LOGGER_NAME = "io.mosire.simos.social";

  public static final String HOUSEHOLD_LOGGER_NAME = ROOT_LOGGER_NAME + ".household";
  public static final String POPULATION_LOGGER_NAME = ROOT_LOGGER_NAME + ".population";
  public static final String EVENT_LOGGER_NAME = ROOT_LOGGER_NAME + ".event";
  public static final String WORK_ORDER_LOGGER_NAME = ROOT_LOGGER_NAME + ".workorder";
  public static final String TRACE_LOGGER_NAME = ROOT_LOGGER_NAME + ".trace";

  private static final Logger HOUSEHOLD = LoggerFactory.getLogger(HOUSEHOLD_LOGGER_NAME);
  private static final Logger POPULATION = LoggerFactory.getLogger(POPULATION_LOGGER_NAME);
  private static final Logger EVENT = LoggerFactory.getLogger(EVENT_LOGGER_NAME);
  private static final Logger WORK_ORDER = LoggerFactory.getLogger(WORK_ORDER_LOGGER_NAME);
  private static final Logger TRACE = LoggerFactory.getLogger(TRACE_LOGGER_NAME);

  private SocialLog() {}

  /** 家户生命周期：创建、位置、画像、成员增删、转移、率设置。 */
  public static Logger household() {
    return HOUSEHOLD;
  }

  /** 人口：逐家户出生/死亡/调整汇总。 */
  public static Logger population() {
    return POPULATION;
  }

  /** 逐事件落账（带类型、家户、年龄段、人数、原因）。 */
  public static Logger event() {
    return EVENT;
  }

  /** Social 工单受理：汇总 / 成功 / 拒收 / 幂等命中（reason + source 必记；逐操作明细也走这里）。 */
  public static Logger workOrder() {
    return WORK_ORDER;
  }

  /** 逐批次/逐移出移入明细。 */
  public static Logger trace() {
    return TRACE;
  }

  /**
   * 结构化行拼接：{@code kv("id", "hh-1", "count", 3)} ⇒ {@code id=hh-1 count=3}。
   *
   * <p>只在日志真的会输出时由调用方调用（配合 {@code isDebugEnabled()}/{@code isTraceEnabled()} 守卫），
   * 或接受一次字符串分配。奇数个参数是拼写错误 ⇒ 当场抛（不静默丢字段）。
   */
  public static String kv(Object... keyValues) {
    if (keyValues.length % 2 != 0) {
      throw new IllegalArgumentException("SocialLog.kv 需要偶数个 key/value: " + keyValues.length);
    }
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < keyValues.length; i += 2) {
      if (i > 0) {
        text.append(' ');
      }
      text.append(keyValues[i]).append('=').append(keyValues[i + 1]);
    }
    return text.toString();
  }
}
