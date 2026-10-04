package io.mosire.simos.calendar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ★★ <b>calendar 模块日志门面</b>（P1.3 全项目 Log 骨架，形态照 {@code EconomyLog} / {@code SocialLog} / {@code
 * UnitLog}，2026-10-09 用户裁定"Log 必须加"）。
 *
 * <p><b>唯一拼写点</b>：calendar 模块<b>新增</b>日志的 logger 名都从这里取，调用方不得自己拼字符串。分类如下（全部挂在 {@value
 * #ROOT_LOGGER_NAME} 之下 ⇒ 一个开关可整体升降级）：
 *
 * <table border="1">
 *   <caption>分类与用途</caption>
 *   <tr><th>取用方法</th><th>logger 名</th><th>记什么</th></tr>
 *   <tr><td>{@link #clock()}</td><td>{@code .clock}</td><td>历法时钟绑定（CalendarClock.of/julianDefault）</td></tr>
 *   <tr><td>{@link #season()}</td><td>{@code .season}</td><td>季节系统配置（ZonedSeasonSystem 构造）</td></tr>
 *   <tr><td>{@link #trace()}</td><td>{@code .trace}</td><td><b>逐次季节查询明细</b>（默认关；纯计算，慎开）</td></tr>
 * </table>
 *
 * <p><b>级别约定</b>（消费者按级别裁剪）：
 *
 * <ul>
 *   <li><b>INFO</b>：历法/季节系统绑定生命周期（无状态模块，仅构造期一次）；
 *   <li><b>DEBUG</b>：绑定参数与配置档（anchor、boundary、tropicalModel）；
 *   <li><b>TRACE</b>：逐次 seasonOf 查询（每格每天，默认关，慎开）。
 * </ul>
 *
 * <p><b>打开方式</b>：应用配置（{@code simos-app/src/main/resources/log4j2.xml}）把 {@value #ROOT_LOGGER_NAME}
 * 默认设为 INFO；完整调试：
 *
 * <pre>{@code
 * ./mvnw ... -Dsimos.calendar.logLevel=DEBUG        # 阶段/汇总
 * ./mvnw ... -Dsimos.calendar.logLevel=DEBUG \
 *            -Dsimos.calendar.traceLevel=TRACE      # 再开逐条明细
 * }</pre>
 *
 * <p><b>纪律</b>：不记密钥、不记载荷明文；只记稳定 id、数量、原因档、汇总值。日志只读、不写状态、不改公式； 日志失败不影响结算；行一律以 {@code event=...
 * key=value} 起（{@link #kv} 是唯一拼接点）。
 */
public final class CalendarLog {

  /** calendar 日志根名（整体升降级的唯一开关）。 */
  public static final String ROOT_LOGGER_NAME = "io.mosire.simos.calendar";

  public static final String CLOCK_LOGGER_NAME = ROOT_LOGGER_NAME + ".clock";
  public static final String SEASON_LOGGER_NAME = ROOT_LOGGER_NAME + ".season";
  public static final String TRACE_LOGGER_NAME = ROOT_LOGGER_NAME + ".trace";
  private static final Logger CLOCK = LoggerFactory.getLogger(CLOCK_LOGGER_NAME);
  private static final Logger SEASON = LoggerFactory.getLogger(SEASON_LOGGER_NAME);
  private static final Logger TRACE = LoggerFactory.getLogger(TRACE_LOGGER_NAME);

  private CalendarLog() {}

  /** 历法时钟绑定（CalendarClock.of/julianDefault）。 */
  public static Logger clock() {
    return CLOCK;
  }

  /** 季节系统配置（ZonedSeasonSystem 构造）。 */
  public static Logger season() {
    return SEASON;
  }

  /** <b>逐次季节查询明细</b>（默认关；纯计算，慎开）。 */
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
    if (keyValues.length % 2 != 0) {
      throw new IllegalArgumentException("CalendarLog.kv 需要偶数个 key/value: " + keyValues.length);
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
