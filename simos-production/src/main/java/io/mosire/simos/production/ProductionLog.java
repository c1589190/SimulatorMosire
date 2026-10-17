package io.mosire.simos.production;

import io.mosire.simos.util.log.EventLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ★★ <b>{@code simos-production} 模块日志门面</b>（D-031 spec §一.9：每模块一个 {@code XxxLog}，logger 名唯一拼写点； 形态照
 * {@code GovLog} / {@code EconomyLog}）。
 *
 * <p><b>唯一拼写点</b>：本模块新增日志的 logger 名都从这里取，调用方不得自己拼字符串。分类如下（全部挂在 {@value #ROOT_LOGGER_NAME} 之下 ⇒
 * 一个开关可整体升降级）：
 *
 * <table border="1">
 *   <caption>分类与用途</caption>
 *   <tr><th>取用方法</th><th>logger 名</th><th>记什么</th></tr>
 *   <tr><td>{@link #command()}</td><td>{@code .command}</td><td>生产配置命令 handler（方案/生产方式定义写入、播种、清区；成功/拒绝 INFO、契约 ERROR）</td></tr>
 *   <tr><td>{@link #skill()}</td><td>{@code .skill}</td><td>跨地区生产技能目录查询（未命中 ⇒ empty；不解析 id 猜种类）</td></tr>
 *   <tr><td>{@link #codec()}</td><td>{@code .codec}</td><td>{@code ProductionCodec} 编解码/变更集施加</td></tr>
 *   <tr><td>{@link #runtime()}</td><td>{@code .runtime}</td><td>★ D-032：试算/执行/周期关账/经验增长与衰减/解锁/效率契约故障</td></tr>
 * </table>
 *
 * <p><b>级别约定</b>：INFO = 命令成功/具名拒绝；DEBUG = 汇总读数；TRACE = 逐条明细（含技能目录 miss）。
 *
 * <p><b>纪律</b>：不记密钥、不记载荷明文；只记稳定 id、数量、原因档、汇总值。日志只读、不写状态、不改公式；日志失败不影响结算； 行一律以 {@code event=...
 * key=value} 起（{@link #kv} 是唯一拼接点）。
 */
public final class ProductionLog {

  /** production 日志根名（整体升降级的唯一开关）。 */
  public static final String ROOT_LOGGER_NAME = "io.mosire.simos.production";

  public static final String COMMAND_LOGGER_NAME = ROOT_LOGGER_NAME + ".command";
  public static final String SKILL_LOGGER_NAME = ROOT_LOGGER_NAME + ".skill";
  public static final String CODEC_LOGGER_NAME = ROOT_LOGGER_NAME + ".codec";
  public static final String RUNTIME_LOGGER_NAME = ROOT_LOGGER_NAME + ".runtime";
  private static final Logger COMMAND = LoggerFactory.getLogger(COMMAND_LOGGER_NAME);
  private static final Logger SKILL = LoggerFactory.getLogger(SKILL_LOGGER_NAME);
  private static final Logger CODEC = LoggerFactory.getLogger(CODEC_LOGGER_NAME);
  private static final Logger RUNTIME = LoggerFactory.getLogger(RUNTIME_LOGGER_NAME);

  private ProductionLog() {}

  /** 生产配置命令 handler（方案/生产方式定义写入、播种、清区；成功/拒绝 INFO、契约 ERROR）。 */
  public static Logger command() {
    return COMMAND;
  }

  /** 跨地区生产技能目录查询（未命中 ⇒ empty；不解析 id 猜种类）。 */
  public static Logger skill() {
    return SKILL;
  }

  /** {@code ProductionCodec} 编解码与变更集施加。 */
  public static Logger codec() {
    return CODEC;
  }

  /**
   * ★★ <b>D-032 生产运行时</b>（试算 / 执行 / 周期关账 / 经验增长与衰减 / 解锁 / 效率契约故障）。
   *
   * <p>★ 级别约定（I15）：业务拒绝 = INFO 具名理由；契约/跨切片一致性故障 = ERROR 不降级；逐笔明细 = TRACE。 关账/衰减一类的"正常但重要"读数 =
   * DEBUG。
   */
  public static Logger runtime() {
    return RUNTIME;
  }

  /**
   * 结构化行拼接：{@code kv("technologyId", "bronze")} ⇒ {@code technologyId=bronze}。
   *
   * <p>实现在 util 的 {@link EventLog#kv}（本门面只留 logger 命名空间）。
   */
  public static String kv(Object... keyValues) {
    return EventLog.kv(keyValues);
  }
}
