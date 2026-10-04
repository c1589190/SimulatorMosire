package io.mosire.simos.sd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ★★ <b>sd 模块日志门面</b>（P1.3 全项目 Log 骨架，形态照 {@code EconomyLog} / {@code SocialLog} / {@code
 * UnitLog}，2026-10-09 用户裁定"Log 必须加"）。
 *
 * <p><b>唯一拼写点</b>：sd 模块<b>新增</b>日志的 logger 名都从这里取，调用方不得自己拼字符串。分类如下（全部挂在 {@value #ROOT_LOGGER_NAME}
 * 之下 ⇒ 一个开关可整体升降级）：
 *
 * <table border="1">
 *   <caption>分类与用途</caption>
 *   <tr><th>取用方法</th><th>logger 名</th><th>记什么</th></tr>
 *   <tr><td>{@link #decision()}</td><td>{@code .decision}</td><td>决策通道/裁决/令/判决/决策人运行流</td></tr>
 *   <tr><td>{@link #nation()}</td><td>{@code .nation}</td><td>国家/军队/决策人生命周期（create/delete/seed）</td></tr>
 *   <tr><td>{@link #combat()}</td><td>{@code .combat}</td><td>交战记录/阶段/伤亡</td></tr>
 *   <tr><td>{@link #diplomacy()}</td><td>{@code .diplomacy}</td><td>外交关系与外交事件</td></tr>
 *   <tr><td>{@link #time()}</td><td>{@code .time}</td><td>sd 时间推进（effect 触发、combat stage 推进）</td></tr>
 *   <tr><td>{@link #trace()}</td><td>{@code .trace}</td><td><b>逐条 effect/combat/令/判决明细</b></td></tr>
 * </table>
 *
 * <p><b>级别约定</b>（消费者按级别裁剪）：
 *
 * <ul>
 *   <li><b>INFO</b>：生命周期事件（建国/建交战/出令/判决/裁决/外交落账）；
 *   <li><b>DEBUG</b>：阶段池/触发原因/推进汇总；
 *   <li><b>TRACE</b>：逐条 effect、逐阶段、逐条令/判决明细。
 * </ul>
 *
 * <p><b>打开方式</b>：应用配置（{@code simos-app/src/main/resources/log4j2.xml}）把 {@value #ROOT_LOGGER_NAME}
 * 默认设为 INFO；完整调试：
 *
 * <pre>{@code
 * ./mvnw ... -Dsimos.sd.logLevel=DEBUG        # 阶段/汇总
 * ./mvnw ... -Dsimos.sd.logLevel=DEBUG \
 *            -Dsimos.sd.traceLevel=TRACE      # 再开逐条明细
 * }</pre>
 *
 * <p><b>纪律</b>：不记密钥、不记载荷明文；只记稳定 id、数量、原因档、汇总值。日志只读、不写状态、不改公式； 日志失败不影响结算；行一律以 {@code event=...
 * key=value} 起（{@link #kv} 是唯一拼接点）。
 */
public final class SdLog {

  /** sd 日志根名（整体升降级的唯一开关）。 */
  public static final String ROOT_LOGGER_NAME = "io.mosire.simos.sd";

  public static final String DECISION_LOGGER_NAME = ROOT_LOGGER_NAME + ".decision";
  public static final String NATION_LOGGER_NAME = ROOT_LOGGER_NAME + ".nation";
  public static final String COMBAT_LOGGER_NAME = ROOT_LOGGER_NAME + ".combat";
  public static final String DIPLOMACY_LOGGER_NAME = ROOT_LOGGER_NAME + ".diplomacy";
  public static final String TIME_LOGGER_NAME = ROOT_LOGGER_NAME + ".time";
  public static final String TRACE_LOGGER_NAME = ROOT_LOGGER_NAME + ".trace";
  private static final Logger DECISION = LoggerFactory.getLogger(DECISION_LOGGER_NAME);
  private static final Logger NATION = LoggerFactory.getLogger(NATION_LOGGER_NAME);
  private static final Logger COMBAT = LoggerFactory.getLogger(COMBAT_LOGGER_NAME);
  private static final Logger DIPLOMACY = LoggerFactory.getLogger(DIPLOMACY_LOGGER_NAME);
  private static final Logger TIME = LoggerFactory.getLogger(TIME_LOGGER_NAME);
  private static final Logger TRACE = LoggerFactory.getLogger(TRACE_LOGGER_NAME);

  private SdLog() {}

  /** 决策通道/裁决/令/判决/决策人运行流。 */
  public static Logger decision() {
    return DECISION;
  }

  /** 国家/军队/决策人生命周期（create/delete/seed）。 */
  public static Logger nation() {
    return NATION;
  }

  /** 交战记录/阶段/伤亡。 */
  public static Logger combat() {
    return COMBAT;
  }

  /** 外交关系与外交事件。 */
  public static Logger diplomacy() {
    return DIPLOMACY;
  }

  /** sd 时间推进（effect 触发、combat stage 推进）。 */
  public static Logger time() {
    return TIME;
  }

  /** <b>逐条 effect/combat/令/判决明细</b>。 */
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
      throw new IllegalArgumentException("SdLog.kv 需要偶数个 key/value: " + keyValues.length);
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
