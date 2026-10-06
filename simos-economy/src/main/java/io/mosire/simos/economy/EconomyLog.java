package io.mosire.simos.economy;

import io.mosire.simos.util.log.EventLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ★★ <b>经济系统日志门面</b>（2026-10-04 用户裁定："先把经济系统全量 Log 化"）。
 *
 * <p><b>唯一拼写点</b>：所有经济日志的 logger 名都从这里取，调用方不得自己拼字符串。分类如下（全部挂在 {@value #ROOT_LOGGER_NAME} 之下 ⇒
 * 一个开关可整体升降级）：
 *
 * <table border="1">
 *   <caption>分类与用途</caption>
 *   <tr><th>取用方法</th><th>logger 名</th><th>记什么</th></tr>
 *   <tr><td>{@link #settlement()}</td><td>{@code .settlement}</td><td>日结算阶段边界、阶段聚合读数、饥饿/收获/迁移等主链事件</td></tr>
 *   <tr><td>{@link #market()}</td><td>{@code .market}</td><td>开市、订单/成交/未成交/信用成交、市场拓扑</td></tr>
 *   <tr><td>{@link #debt()}</td><td>{@code .debt}</td><td>债务合同建立/计息/偿还/核销/状态迁移</td></tr>
 *   <tr><td>{@link #migration()}</td><td>{@code .migration}</td><td>生产方式变迁计划与执行（D-022/D-023/D-024）</td></tr>
 *   <tr><td>{@link #enterprise()}</td><td>{@code .organization}</td><td>自动生产组织建立、租佃拆分、经营者归属</td></tr>
 *   <tr><td>{@link #entry()}</td><td>{@code .entry}</td><td>候选预设进入/拒绝（0-entry）</td></tr>
 *   <tr><td>{@link #population()}</td><td>{@code .population}</td><td>出生/死亡/人口回写/劳动缩放</td></tr>
 *   <tr><td>{@link #command()}</td><td>{@code .command}</td><td>经济写命令处理：登记/设参/调整/候选进入/借贷（命令面）</td></tr>
 *   <tr><td>{@link #trace()}</td><td>{@code .trace}</td><td><b>逐笔原始事件</b>：转移、成交、订单、债务/迁移的逐条明细</td></tr>
 * </table>
 *
 * <p><b>级别约定</b>（消费者按级别裁剪）：
 *
 * <ul>
 *   <li><b>INFO</b>：一天/一轮的<b>生命周期</b>与<b>发生了多少次什么</b>——一个只看 INFO 的人能回答"今天经济系统做了什么"；
 *   <li><b>DEBUG</b>：每个阶段的<b>池子/汇总/理由</b>（谁的额度是 0、市场为什么不开、资本化/清算/退出各多少条）；
 *   <li><b>TRACE</b>：<b>逐条</b>明细（每笔转移、每个成交/未成交槽、每条债务变动、每个迁移 move）——用于对账与定位； 并行分区 worker
 *       的铸造序不保证跨分区稳定（id 里的分区段仍稳定），诊断时按业务键读、不要按行号读。
 * </ul>
 *
 * <p><b>打开方式</b>：应用配置（{@code simos-app/src/main/resources/log4j2.xml}）把 {@value #ROOT_LOGGER_NAME}
 * 默认设为 INFO；完整调试：
 *
 * <pre>{@code
 * ./mvnw ... -Dsimos.economy.logLevel=DEBUG        # 阶段池子/汇总
 * ./mvnw ... -Dsimos.economy.logLevel=DEBUG \
 *            -Dsimos.economy.traceLevel=TRACE      # 再开逐笔
 * }</pre>
 *
 * <p><b>纪律</b>：不记密钥、不记载荷明文；经济日志只记稳定 id、数量、原因档、汇总值。日志本身不参与状态、不写磁盘 （由 log4j2 配置决定落点）。
 */
public final class EconomyLog {

  /** 经济日志根名（整体升降级的唯一开关）。 */
  public static final String ROOT_LOGGER_NAME = "io.mosire.simos.economy";

  public static final String SETTLEMENT_LOGGER_NAME = ROOT_LOGGER_NAME + ".settlement";
  public static final String MARKET_LOGGER_NAME = ROOT_LOGGER_NAME + ".market";
  public static final String DEBT_LOGGER_NAME = ROOT_LOGGER_NAME + ".debt";
  public static final String MIGRATION_LOGGER_NAME = ROOT_LOGGER_NAME + ".migration";
  public static final String ORGANIZATION_LOGGER_NAME = ROOT_LOGGER_NAME + ".organization";
  public static final String ENTRY_LOGGER_NAME = ROOT_LOGGER_NAME + ".entry";
  public static final String POPULATION_LOGGER_NAME = ROOT_LOGGER_NAME + ".population";
  public static final String COMMAND_LOGGER_NAME = ROOT_LOGGER_NAME + ".command";
  public static final String TRACE_LOGGER_NAME = ROOT_LOGGER_NAME + ".trace";

  private static final Logger SETTLEMENT = LoggerFactory.getLogger(SETTLEMENT_LOGGER_NAME);
  private static final Logger MARKET = LoggerFactory.getLogger(MARKET_LOGGER_NAME);
  private static final Logger DEBT = LoggerFactory.getLogger(DEBT_LOGGER_NAME);
  private static final Logger MIGRATION = LoggerFactory.getLogger(MIGRATION_LOGGER_NAME);
  private static final Logger ORGANIZATION = LoggerFactory.getLogger(ORGANIZATION_LOGGER_NAME);
  private static final Logger ENTRY = LoggerFactory.getLogger(ENTRY_LOGGER_NAME);
  private static final Logger POPULATION = LoggerFactory.getLogger(POPULATION_LOGGER_NAME);
  private static final Logger COMMAND = LoggerFactory.getLogger(COMMAND_LOGGER_NAME);
  private static final Logger TRACE = LoggerFactory.getLogger(TRACE_LOGGER_NAME);

  private EconomyLog() {}

  /** 日结算主链：阶段边界、收获/饿死/偿还/迁移等。 */
  public static Logger settlement() {
    return SETTLEMENT;
  }

  /** 市场：开市、订单、成交、未成交、信用成交。 */
  public static Logger market() {
    return MARKET;
  }

  /** 债务：建立/计息/偿还/状态迁移/核销。 */
  public static Logger debt() {
    return DEBT;
  }

  /** 生产方式变迁与家户迁移。 */
  public static Logger migration() {
    return MIGRATION;
  }

  /** 自动生产组织/租佃/经营者归属。 */
  public static Logger enterprise() {
    return ORGANIZATION;
  }

  /** 候选预设进入。 */
  public static Logger entry() {
    return ENTRY;
  }

  /** 人口：出生/死亡/人口回写/劳动缩放。 */
  public static Logger population() {
    return POPULATION;
  }

  /** 经济写命令处理：登记/设参/调整/候选进入/借贷（命令面，来源 = {@code economy-command}）。 */
  public static Logger command() {
    return COMMAND;
  }

  /** 逐笔原始事件（转移/成交槽/债务变动/迁移 move）。 */
  public static Logger trace() {
    return TRACE;
  }

  /**
   * 结构化行拼接：{@code kv("day", 3, "rows", 100)} ⇒ {@code day=3 rows=100}。
   *
   * <p>只在日志真的会输出时由调用方调用（配合 {@code isDebugEnabled()}/{@code isTraceEnabled()} 守卫），
   * 或接受一次字符串分配。奇数个参数是拼写错误 ⇒ 当场抛（不静默丢字段）。
   */
  public static String kv(Object... keyValues) {
    // ★ 2026-10-09：实现收口到 util 的 EventLog.kv（本门面只留 logger 命名空间）。
    return EventLog.kv(keyValues);
  }
}
