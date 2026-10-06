package io.mosire.simos.map;

import io.mosire.simos.util.log.EventLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ★★ <b>map 模块日志门面</b>（P1.3 全项目 Log 骨架，形态照 {@code EconomyLog} / {@code SocialLog} / {@code
 * UnitLog}，2026-10-09 用户裁定"Log 必须加"）。
 *
 * <p><b>唯一拼写点</b>：map 模块<b>新增</b>日志的 logger 名都从这里取，调用方不得自己拼字符串。分类如下（全部挂在 {@value #ROOT_LOGGER_NAME}
 * 之下 ⇒ 一个开关可整体升降级）：
 *
 * <table border="1">
 *   <caption>分类与用途</caption>
 *   <tr><th>取用方法</th><th>logger 名</th><th>记什么</th></tr>
 *   <tr><td>{@link #edit()}</td><td>{@code .edit}</td><td>区域/地形/连通性/通路组的语义写口（create/update/delete/merge/split/reassign/set）</td></tr>
 *   <tr><td>{@link #generate()}</td><td>{@code .generate}</td><td>地图生成器生命周期与产物汇总</td></tr>
 *   <tr><td>{@link #codec()}</td><td>{@code .codec}</td><td>MapCodec 编解码、旧形状迁移与变更集施加（只记元信息，不记 JSON 原文）</td></tr>
 *   <tr><td>{@link #resolve()}</td><td>{@code .resolve}</td><td>MapResolver 空候选与装配故障诊断（不逐次记成功查询）</td></tr>
 *   <tr><td>{@link #trace()}</td><td>{@code .trace}</td><td><b>逐格/逐边/逐区域明细</b></td></tr>
 * </table>
 *
 * <p><b>级别约定</b>（消费者按级别裁剪）：
 *
 * <ul>
 *   <li><b>INFO</b>：生命周期——一条 map 写命令作用于哪个区域/多少格；生成器开始/结束；
 *   <li><b>DEBUG</b>：写口/生成器的汇总读数与阶段边界（数量、原因档）；
 *   <li><b>TRACE</b>：逐格 hex、逐边 EdgeRef、逐区域 id 明细。
 * </ul>
 *
 * <p><b>打开方式</b>：应用配置（{@code simos-app/src/main/resources/log4j2.xml}）把 {@value #ROOT_LOGGER_NAME}
 * 默认设为 INFO；完整调试：
 *
 * <pre>{@code
 * ./mvnw ... -Dsimos.map.logLevel=DEBUG        # 阶段/汇总
 * ./mvnw ... -Dsimos.map.logLevel=DEBUG \
 *            -Dsimos.map.traceLevel=TRACE      # 再开逐条明细
 * }</pre>
 *
 * <p><b>纪律</b>：不记密钥、不记载荷明文；只记稳定 id、数量、原因档、汇总值。日志只读、不写状态、不改公式； 日志失败不影响结算；行一律以 {@code event=...
 * key=value} 起（{@link #kv} 是唯一拼接点）。
 */
public final class MapLog {

  /** map 日志根名（整体升降级的唯一开关）。 */
  public static final String ROOT_LOGGER_NAME = "io.mosire.simos.map";

  public static final String EDIT_LOGGER_NAME = ROOT_LOGGER_NAME + ".edit";
  public static final String GENERATE_LOGGER_NAME = ROOT_LOGGER_NAME + ".generate";
  public static final String CODEC_LOGGER_NAME = ROOT_LOGGER_NAME + ".codec";
  public static final String RESOLVE_LOGGER_NAME = ROOT_LOGGER_NAME + ".resolve";
  public static final String TRACE_LOGGER_NAME = ROOT_LOGGER_NAME + ".trace";
  private static final Logger EDIT = LoggerFactory.getLogger(EDIT_LOGGER_NAME);
  private static final Logger GENERATE = LoggerFactory.getLogger(GENERATE_LOGGER_NAME);
  private static final Logger CODEC = LoggerFactory.getLogger(CODEC_LOGGER_NAME);
  private static final Logger RESOLVE = LoggerFactory.getLogger(RESOLVE_LOGGER_NAME);
  private static final Logger TRACE = LoggerFactory.getLogger(TRACE_LOGGER_NAME);

  private MapLog() {}

  /** 区域/地形/连通性/通路组的语义写口（create/update/delete/merge/split/reassign/set）。 */
  public static Logger edit() {
    return EDIT;
  }

  /** 地图生成器生命周期与产物汇总。 */
  public static Logger generate() {
    return GENERATE;
  }

  /** MapCodec 编解码、旧形状迁移与变更集施加（只记元信息，不记 JSON 原文）。 */
  public static Logger codec() {
    return CODEC;
  }

  /** MapResolver 空候选与装配故障诊断（不逐次记成功查询）。 */
  public static Logger resolve() {
    return RESOLVE;
  }

  /** <b>逐格/逐边/逐区域明细</b>。 */
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
