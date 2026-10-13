package io.mosire.simos.army;

import io.mosire.simos.util.log.EventLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ★★ <b>army 模块日志门面</b>（P1.3 全项目 Log 骨架，形态照 {@code EconomyLog} / {@code SocialLog} / {@code
 * UnitLog}，2026-10-09 用户裁定"Log 必须加"）。
 *
 * <p><b>唯一拼写点</b>：army 模块<b>新增</b>日志的 logger 名都从这里取，调用方不得自己拼字符串。分类如下（全部挂在 {@value
 * #ROOT_LOGGER_NAME} 之下 ⇒ 一个开关可整体升降级）：
 *
 * <table border="1">
 *   <caption>分类与用途</caption>
 *   <tr><th>取用方法</th><th>logger 名</th><th>记什么</th></tr>
 *   <tr><td>{@link #combat()}</td><td>{@code .combat}</td><td>交战记录/阶段追加/投骰判定</td></tr>
 *   <tr><td>{@link #armies()}</td><td>{@code .armies}</td><td>军队身份（{@code armies} 表）的建档/主子改派与具名拒绝</td></tr>
 *   <tr><td>{@link #contact()}</td><td>{@code .contact}</td><td>两军相遇判据（逐日判定 / 命中上报 / 跳过原因）</td></tr>
 *   <tr><td>{@link #codec()}</td><td>{@code .codec}</td><td>ArmyCodec 编解码/变更集施加</td></tr>
 *   <tr><td>{@link #resolve()}</td><td>{@code .resolve}</td><td>ArmyResolver 候选装配/空结果</td></tr>
 *   <tr><td>{@link #trace()}</td><td>{@code .trace}</td><td><b>逐阶段/逐结局明细</b></td></tr>
 * </table>
 *
 * <p><b>级别约定</b>（消费者按级别裁剪）：
 *
 * <ul>
 *   <li><b>INFO</b>：交战生命周期——记录/阶段/判定落账与一切具名拒绝；
 *   <li><b>DEBUG</b>：候选项/判定详情的汇总读数与跳过原因；
 *   <li><b>TRACE</b>：逐阶段、逐 outcome 明细。
 * </ul>
 *
 * <p><b>打开方式</b>：应用配置（{@code simos-app/src/main/resources/log4j2.xml}）把 {@value #ROOT_LOGGER_NAME}
 * 默认设为 INFO；完整调试：
 *
 * <pre>{@code
 * ./mvnw ... -Dsimos.army.logLevel=DEBUG        # 阶段/汇总
 * ./mvnw ... -Dsimos.army.logLevel=DEBUG \
 *            -Dsimos.army.traceLevel=TRACE      # 再开逐条明细
 * }</pre>
 *
 * <p><b>纪律</b>：不记密钥、不记载荷明文；只记稳定 id、数量、原因档、汇总值。日志只读、不写状态、不改公式； 日志失败不影响结算；行一律以 {@code event=...
 * key=value} 起（{@link #kv} 是唯一拼接点）。
 */
public final class ArmyLog {

  /** army 日志根名（整体升降级的唯一开关）。 */
  public static final String ROOT_LOGGER_NAME = "io.mosire.simos.army";

  public static final String COMBAT_LOGGER_NAME = ROOT_LOGGER_NAME + ".combat";
  public static final String ARMIES_LOGGER_NAME = ROOT_LOGGER_NAME + ".armies";

  /**
   * ★ 2026-10-13 急停协议新增：<b>两军相遇判据</b>（{@code ArmyTimeParticipant} 的逐日判定与命中上报）。
   *
   * <p>与 {@link #ARMIES_LOGGER_NAME} 分开的理由：一个是<b>命令面</b>（谁建了军队、谁改了主子）、一个是<b>推进面</b>（这一天哪两军在哪相遇） ——
   * 现场调"急停为什么没停"时只需要后者，混在一个 logger 里会让开关失去分辨力。
   */
  public static final String CONTACT_LOGGER_NAME = ROOT_LOGGER_NAME + ".contact";

  public static final String CODEC_LOGGER_NAME = ROOT_LOGGER_NAME + ".codec";
  public static final String RESOLVE_LOGGER_NAME = ROOT_LOGGER_NAME + ".resolve";
  public static final String TRACE_LOGGER_NAME = ROOT_LOGGER_NAME + ".trace";
  private static final Logger COMBAT = LoggerFactory.getLogger(COMBAT_LOGGER_NAME);
  private static final Logger ARMIES = LoggerFactory.getLogger(ARMIES_LOGGER_NAME);
  private static final Logger CONTACT = LoggerFactory.getLogger(CONTACT_LOGGER_NAME);
  private static final Logger CODEC = LoggerFactory.getLogger(CODEC_LOGGER_NAME);
  private static final Logger RESOLVE = LoggerFactory.getLogger(RESOLVE_LOGGER_NAME);
  private static final Logger TRACE = LoggerFactory.getLogger(TRACE_LOGGER_NAME);

  private ArmyLog() {}

  /** 交战记录/阶段追加/投骰判定。 */
  public static Logger combat() {
    return COMBAT;
  }

  /**
   * <b>军队身份</b>（{@code armies} 表）的建档/主子改派与具名拒绝（2026-10-12 批次 3d-1）。
   *
   * <p>★ 迁入前这两条命令（{@code sd.CreateArmy} / {@code sd.SetArmyMasterGov}）用的是 {@code SdLog.nation()}
   * （{@code io.mosire.simos.sd.nation}）——军队身份搬进 army 后，来源表随之换家（同一条纪律的另一面：logger 名的唯一拼写点在本模块）。
   */
  public static Logger armies() {
    return ARMIES;
  }

  /**
   * <b>两军相遇判据</b>（2026-10-13 急停协议）：{@code ArmyTimeParticipant} 逐日算各军位置、判"同格或相邻"、命中即上报。
   *
   * <p>★ 命中是 INFO（"这一天这两军相遇了"是**事实**，也是急停的原因）；跳过与汇总读数是 DEBUG。
   */
  public static Logger contact() {
    return CONTACT;
  }

  /** ArmyCodec 编解码、变更集施加与旧形状迁移。 */
  public static Logger codec() {
    return CODEC;
  }

  /** ArmyResolver 候选装配与空结果诊断。 */
  public static Logger resolve() {
    return RESOLVE;
  }

  /** <b>逐阶段/逐结局明细</b>。 */
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
