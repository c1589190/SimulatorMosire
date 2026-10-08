package io.mosire.simos.economy;

import io.mosire.simos.util.log.LogOrigin;
import io.mosire.simos.util.log.LogOriginKind;

/**
 * ★★ <b>economy 模块日志来源表</b>（2026-10-23 用户裁定：各模块独立维护 enum + 中文说明）。
 *
 * <p>表项 id 是 economy 的稳定短 id（发布后不改）；粗分类按<b>工作性质</b>记：
 *
 * <ul>
 *   <li><b>TICK</b>：日/阶段推进与结算（{@code day} 上下文，事件必带 {@code day}）；
 *   <li><b>SYSTEM</b>：命令面与编解码/解析——命令入口的工具/人工交互由 app 层自记 {@code interaction}（用户裁定 origin 不跨模块传、不改
 *       SPI），本模块只记自己的执行，故为 {@link LogOriginKind#SYSTEM}。
 * </ul>
 */
public enum EconomyLogSource implements LogOrigin {
  ECONOMY_DAY(
      "economy-day",
      "日推进与日账：EconomyDayStepper/ProductionLedger 的日边界与日账汇总（tick 算法，必带 day）",
      LogOriginKind.TICK),
  ECONOMY_SETTLEMENT(
      "economy-settlement", "主结算链：阶段边界/收获/饿死/类别迁移/清算/人口回写（tick 算法，必带 day）", LogOriginKind.TICK),
  ECONOMY_MARKET("economy-market", "市场开市/订单/成交/信用成交/未成交（tick 算法，必带 day）", LogOriginKind.TICK),
  ECONOMY_DEBT("economy-debt", "债务合同建立/计息/偿还/核销与政府发债（tick 算法，必带 day）", LogOriginKind.TICK),
  ECONOMY_MIGRATION("economy-migration", "生产方式变迁与家户迁移（tick 算法，必带 day）", LogOriginKind.TICK),
  ECONOMY_ENTRY("economy-entry", "候选预设进入/试用/接受/拒绝（tick 结算面，必带 day）", LogOriginKind.TICK),
  ECONOMY_ORGANIZATION(
      "economy-organization", "自动生产组织/租佃/经营者进退（tick 算法，必带 day）", LogOriginKind.TICK),
  ECONOMY_POPULATION(
      "economy-population", "出生/死亡/人口回写/劳动缩放/自然需求注入（tick 算法，必带 day）", LogOriginKind.TICK),
  ECONOMY_POPULATION_WRITE(
      "economy-population-write", "人口/自然需求回写原语（方法签名无 day 上下文，按纪律归 system）", LogOriginKind.SYSTEM),
  ECONOMY_DEBT_STATE(
      "economy-debt-state", "债务减本/状态迁移/减免/逐笔偿还裁决（方法签名无 day 上下文，按纪律归 system）", LogOriginKind.SYSTEM),
  ECONOMY_OPERATOR_STATE(
      "economy-operator-state",
      "经营者关账状态迁移（advance 方法签名无 day 上下文，按纪律归 system）",
      LogOriginKind.SYSTEM),
  ECONOMY_OUTPUT_CREDIT(
      "economy-output-credit",
      "产出入账主体解析（creditOutput 方法签名无 day 上下文，按纪律归 system）",
      LogOriginKind.SYSTEM),
  ECONOMY_COMMAND("economy-command", "经济写命令处理：登记/设参/调整/候选进入/借贷（命令面）", LogOriginKind.SYSTEM),
  ECONOMY_CODEC("economy-codec", "EconomyCodec 编解码与变更集施加", LogOriginKind.SYSTEM),
  ECONOMY_RESOLVE("economy-resolve", "EconomyResolver 候选装配与空结果诊断", LogOriginKind.SYSTEM),
  ECONOMY_OUTPUT_QUANTITY(
      "economy-output-quantity", "产品产出数量覆盖：GM 设置/清除/回落默认（命令面）", LogOriginKind.SYSTEM),
  ECONOMY_PRODUCTION_EFFICIENCY(
      "economy-production-efficiency",
      "单 tick 单产业生产框架：修正注入、有效规模、产出数量公式（tick 算法，必带 day）",
      LogOriginKind.TICK),
  ECONOMY_ARBITRAGE(
      "economy-arbitrage", "家户套利活动：保留价派生、收益率排序、买卖意向与拒绝理由（tick 算法，必带 day）", LogOriginKind.TICK),
  ECONOMY_MONEY(
      "economy-money", "货币身份与发行命令：定义币种/改名/发行审计记录（命令面；改名不动账，发行只记审计）", LogOriginKind.SYSTEM),
  /**
   * ★★ <b>A2a（2026-10-08）：外汇面</b>—— 市场轮里的 FX 撮合、官方/实际汇率读数、政府外汇窗口的报价与具名拒、 异币支付的具名拒（{@code
   * MarketUnfilledReason.CURRENCY_MISMATCH}），以及设置官方汇率的命令。
   *
   * <p>★ 逐笔成交/逐笔拒在 TRACE（{@code simos.economy.traceLevel} 单独开），窗口触顶/见底/无对手方在 INFO（用户 2026-10-23
   * 裁定：业务拒绝 = INFO）—— 外汇是"政策价 vs 市场价"的现场，拒绝一条都不能静默。
   */
  ECONOMY_FX(
      "economy-fx", "外汇面：FX 撮合/官方与实际汇率读数/政府外汇窗口报价与具名拒/异币支付具名拒（tick 算法，必带 day）", LogOriginKind.TICK),
  /**
   * ★★ <b>B2（2026-10-08）：市场区的持久状态与命令面</b>—— 区定义 / 逐格改划（退让·覆盖） / 合并 / 区内官方汇率覆盖，以及它们的具名拒。
   *
   * <p>★ 命令面事件全部是 {@code SYSTEM}（方法签名无 day 上下文）；区定义/改划/合并各 INFO 一条"发生了什么 + 具名计数"， 拒绝路径 INFO（业务拒绝 =
   * INFO，用户 2026-10-23 裁定）+ DEBUG 一条"为什么"（§一.9）。
   */
  ECONOMY_MARKET_ZONE(
      "economy-market-zone", "市场区持久状态与命令面：区定义/逐格改划/合并/区内官方汇率覆盖及其具名拒（命令面）", LogOriginKind.SYSTEM);

  private final String id;
  private final String description;
  private final LogOriginKind kind;

  EconomyLogSource(String id, String description, LogOriginKind kind) {
    this.id = id;
    this.description = description;
    this.kind = kind;
  }

  @Override
  public String id() {
    return id;
  }

  @Override
  public String description() {
    return description;
  }

  @Override
  public LogOriginKind kind() {
    return kind;
  }
}
