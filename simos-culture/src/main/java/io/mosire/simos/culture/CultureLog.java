package io.mosire.simos.culture;

import io.mosire.simos.util.log.EventLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ★★ <b>{@code simos-culture} 模块日志门面</b>（D-031 spec §一.9：每模块一个 {@code XxxLog}，logger 名唯一拼写点；形态照
 * {@code ProductionLog} / {@code GovLog}）。
 *
 * <p><b>唯一拼写点</b>：本模块新增日志的 logger 名都从这里取，调用方不得自己拼字符串。分类如下（全部挂在 {@value #ROOT_LOGGER_NAME} 之下 ⇒
 * 一个开关可整体升降级）：
 *
 * <table border="1">
 *   <caption>分类与用途</caption>
 *   <tr><th>取用方法</th><th>logger 名</th><th>记什么</th></tr>
 *   <tr><td>{@link #command()}</td><td>{@code .command}</td><td>文化配置命令 handler（设置家户文化 / 增改删规则；成功/拒绝 INFO、契约 ERROR）</td></tr>
 *   <tr><td>{@link #codec()}</td><td>{@code .codec}</td><td>{@code CultureCodec} 编解码/变更集施加</td></tr>
 *   <tr><td>{@link #aptitude()}</td><td>{@code .aptitude}</td><td>熟练度合成服务（缺技能/零劳动 INFO、跨切片一致性故障 ERROR、逐 unit TRACE）</td></tr>
 * </table>
 *
 * <p><b>级别约定</b>：INFO = 命令成功/具名拒绝/合法中性具名理由；DEBUG = 汇总读数；TRACE = 逐条明细。★ <b>契约/跨切片一致性故障 =
 * ERROR，不降级</b>（业务拒绝与"合法中性"才是 INFO）。
 *
 * <p><b>纪律</b>：不记密钥、不记载荷明文；只记稳定 id、数量、原因档、汇总值。日志只读、不写状态、不改公式；日志失败不影响结算；行一律以 {@code event=...
 * key=value} 起（{@link #kv} 是唯一拼接点）。
 */
public final class CultureLog {

  /** culture 日志根名（整体升降级的唯一开关）。 */
  public static final String ROOT_LOGGER_NAME = "io.mosire.simos.culture";

  public static final String COMMAND_LOGGER_NAME = ROOT_LOGGER_NAME + ".command";
  public static final String CODEC_LOGGER_NAME = ROOT_LOGGER_NAME + ".codec";
  public static final String APTITUDE_LOGGER_NAME = ROOT_LOGGER_NAME + ".aptitude";
  private static final Logger COMMAND = LoggerFactory.getLogger(COMMAND_LOGGER_NAME);
  private static final Logger CODEC = LoggerFactory.getLogger(CODEC_LOGGER_NAME);
  private static final Logger APTITUDE = LoggerFactory.getLogger(APTITUDE_LOGGER_NAME);

  private CultureLog() {}

  /** 文化配置命令 handler（设置家户文化 / 增改删规则；成功/拒绝 INFO、契约 ERROR）。 */
  public static Logger command() {
    return COMMAND;
  }

  /** {@code CultureCodec} 编解码与变更集施加。 */
  public static Logger codec() {
    return CODEC;
  }

  /** 熟练度合成服务（缺技能/零劳动 INFO、跨切片一致性故障 ERROR、逐 unit TRACE）。 */
  public static Logger aptitude() {
    return APTITUDE;
  }

  /**
   * 结构化行拼接：{@code kv("cultureId", "latium")} ⇒ {@code cultureId=latium}。
   *
   * <p>实现在 util 的 {@link EventLog#kv}（本门面只留 logger 命名空间）。
   */
  public static String kv(Object... keyValues) {
    return EventLog.kv(keyValues);
  }
}
