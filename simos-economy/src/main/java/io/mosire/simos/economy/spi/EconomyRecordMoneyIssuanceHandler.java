package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import io.mosire.simos.economy.api.money.MoneyIssuanceKind;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ {@code economy.RecordMoneyIssuance}（A1 2026-10-08；约束设计书 §3.1-3）：<b>一条显式发行/创世注资的审计记录</b> —— 只写
 * {@code moneyIssuances} 这一张表，<b>不</b>动任何余额。
 *
 * <pre>{@code
 * {"governmentId":"world-silver", // 必填（二选一）：精确政府 id —— **世界级政府**（不是任何 GOV 单位）只能走这一路
 *  "govUnitId":"u-central",       // 必填（二选一）：GOV 单位 id ⇒ 派生政府 id gov-unit-<govUnitId>
 *  "currency":"copper",         // 必填：币种（必须在该政府的 issuable 里）
 *  "amountMilli":100_000,       // 必填：金额（最小币值；> 0；方向由 kind 表达，不用负号）
 *  "kind"?: "FISCAL_ISSUE",     // 可选：缺省 FISCAL_ISSUE（运行期发行）；创世注资给 INITIAL_ENDOWMENT / GENESIS_ENDOWMENT
 *  "reason"?: "…"}              // 可选：审计文本（进记录；缺省给本命令类型）
 * }</pre>
 *
 * <p>★★ <b>寻址（W5-fix6 追加，唯一拼写点 {@code EconomyCommandPayloads.requireGovernmentAddress}）</b>：改前本命令只认
 * {@code govUnitId}（⇒ {@code gov-unit-<id>}），而世界级政府 {@code world-silver} <b>不是任何 GOV 单位</b> ⇒
 * 它<b>永远够不着</b> —— 创世国库注资那笔钱因此没法记在真正的发行主体名下（W5 验收报告 §3-D4）。现在两条地址恰给一个：{@code governmentId} 精确
 * id、{@code govUnitId} 派生 id（两者都给 ⇒ 具名拒，见助手方法）。
 *
 * <p>★★ <b>它是"裸审计原语"：审计与余额是同一批里的两条命令</b>。账户余额住在 {@code actor} 切片，而按铁律 3/4 一条命令只写 一个命名空间 ⇒ {@code
 * 国库余额增加} 是 {@code actor.AdjustAccounts} 的活，本命令只负责"账本上记下这次发行"。两者由组合工具 （{@code simos.gov.issueMoney}
 * / 创世 {@code GovWorldBootstrap}）在<b>同一批</b>提交 ⇒ 一批 = 一条 revision = 原子（{@link
 * io.mosire.simos.core.CoreSimos#submitBatch}）⇒ 不可能出现"记了发行但没人收到钱" 或反过来的半截状态。
 *
 * <p>★★ <b>与 {@code actor.AdjustAccounts} 的分工（重要）</b>：那条命令是"任意增减账"的裸原语，本身<b>不留发行痕迹</b>；
 * 本命令是它的审计对侧。只提交本命令 = 凭空多一条"发过钱"的记录而余额没动（{@code MoneyStock} 的守恒读数会当场不平） ⇒ 故本命令不打 {@code DmUsableCommand}（排除出令白名单 / {@code RegisterEffect} / 决策人命令目录三条路）， 只能由 GM 或窄工具在受控批里提交 —— 与 {@code
 * actor.AdjustAccounts} 的既有定性（"给组合工具用的裸账目原语"）同制。
 *
 * <p>★★ <b>"谁能发"只在 {@code Government.issuable} 上判一次</b>（权限不得放大 / M6）：不是该 GOV 发行的币种 ⇒ 具名拒 （{@code
 * currency-not-issuable}）。这与 {@code MoneyIssuance.requireIssuerOf} / {@code EconomyData} 构造期的
 * "一币一发行人"守卫同源。
 *
 * <p>★ <b>确定性 id</b>：{@code gov-issue-<政府>-<日>-<币种>-<序号>}（序号 = 基态里同前缀记录数 + 1）—— <b>不</b>用随机
 * UUID：发行记录会进快照/dump，随机 id 会让"同代码跑两遍逐值相同"（判据 F7）当场不成立。
 */
public final class EconomyRecordMoneyIssuanceHandler
    implements CommandHandler, CommandTargets {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "economy.RecordMoneyIssuance";

  /** 发行记录 id 的确定性前缀（{@code |} 换成 {@code :}，避开账户/编码分段符）。 */
  private static final String ID_PREFIX = "gov-issue-";

  private static final LogChannel LOG = EventLog.channel(EconomyLog.command());

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    parse(payloadJson); // 形状校验；本命令没有格资源目标（发行是世界级事实）。
    return List.of();
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data();
    Issuance issuance;
    try {
      issuance = parse(payloadJson);
    } catch (IllegalArgumentException e) {
      return rejected("bad-payload", null, EconomyCommandPayloads.logReason(e.getMessage()));
    }
    try {
      if (base.meta().isEmpty()) {
        return rejected("economy-not-activated", issuance, "economy 切片尚未激活（先 economy.Seed 播种）");
      }
      GovernmentId governmentId = issuance.governmentId();
      Government government = base.governments().get(governmentId);
      if (government == null) {
        return rejected(
            "government-not-registered",
            issuance,
            "GOV 单位未登记为政府（先 economy.RegisterGovernment）: " + governmentId.value());
      }
      CurrencyId currency = new CurrencyId(issuance.currency());
      if (!base.currencies().containsKey(currency)) {
        return rejected(
            "currency-not-defined", issuance, "币种 " + currency.value() + " 在本世界的词表里没有定义");
      }
      if (!government.issuable().contains(currency)) {
        // ★ M6：越权发行 —— 说不出"这是我发的钱"就不许发。
        return rejected(
            "currency-not-issuable",
            issuance,
            "政府 "
                + governmentId.value()
                + " 不发行币种 "
                + currency.value()
                + "（issuable="
                + government.issuable()
                + "）");
      }

      long day = state.meta().timestamp().tick();
      long period = base.meta().map(EconomyMeta::currentCycleNumber).orElse(1L);
      String reason = issuance.reason() == null ? "gm:" + TYPE : issuance.reason();
      MoneyIssuanceId id = nextId(base, governmentId, day, currency);
      MoneyIssuanceRecord record =
          new MoneyIssuanceRecord(
              id,
              governmentId,
              day,
              period,
              currency,
              issuance.amountMilli(),
              issuance.kind(),
              reason);
      Map<MoneyIssuanceId, MoneyIssuanceRecord> records =
          new LinkedHashMap<>(base.moneyIssuances());
      records.put(id, record);
      EconomyData projected = base.withMoneyIssuances(records);
      LOG.info(
          LogEvent.of(
              "MONEY_ISSUANCE_RECORDED",
              EconomyLogSource.ECONOMY_MONEY,
              "issuance",
              id.value(),
              "government",
              governmentId.value(),
              "govUnit",
              issuance.address(),
              "currency",
              currency.value(),
              "amountMilli",
              issuance.amountMilli(),
              "kind",
              issuance.kind().name(),
              "day",
              day,
              "period",
              period,
              "recordsTotal",
              records.size()));
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, projected));
    } catch (IllegalArgumentException e) {
      return rejected(
          "contract-violation", issuance, EconomyCommandPayloads.logReason(e.getMessage()));
    }
  }

  /**
   * ★ 确定性发行 id：{@code gov-issue-<政府>-<日>-<币种>-<序号>} —— 序号 = 基态里同前缀记录数 + 1。
   *
   * <p>★ 为什么带序号：同一天同一政府同一币种可以合法发多次（每次一条记录），而 {@code MoneyIssuanceRecord} 的 id 是键 ⇒ 不能只按 (政府, 日, 币种)
   * 派生（那会让第二次覆盖第一次）。序号取自基态（同前缀计数）⇒ 纯函数、可重放、不随机。
   */
  private static MoneyIssuanceId nextId(
      EconomyData base, GovernmentId governmentId, long day, CurrencyId currency) {
    String prefix =
        ID_PREFIX
            + governmentId.value().replace('|', ':')
            + "-"
            + day
            + "-"
            + currency.value()
            + "-";
    long sequence = 0L;
    for (MoneyIssuanceId existing : base.moneyIssuances().keySet()) {
      if (existing.value().startsWith(prefix)) {
        sequence++;
      }
    }
    return new MoneyIssuanceId(prefix + (sequence + 1L));
  }

  /** 具名拒：业务拒绝 = INFO（发生了什么 + 具名拒因），字段级细节 = DEBUG（为什么）。 */
  private static HandlerOutcome.Rejected rejected(
      String reason, Issuance issuance, String message) {
    LOG.info(
        LogEvent.of(
            "MONEY_ISSUANCE_REJECTED",
            EconomyLogSource.ECONOMY_MONEY,
            "reason",
            reason,
            "government",
            issuance == null ? "" : issuance.address(),
            "currency",
            issuance == null ? "" : issuance.currency(),
            "kind",
            issuance == null ? "" : issuance.kind().name()));
    LOG.debug(
        LogEvent.of(
            "MONEY_ISSUANCE_REJECTED_DETAIL",
            EconomyLogSource.ECONOMY_MONEY,
            "reason",
            reason,
            "amountMilli",
            issuance == null ? -1L : issuance.amountMilli(),
            "message",
            message));
    return new HandlerOutcome.Rejected(TYPE + " 具名拒（" + reason + "）: " + message);
  }

  /** 形状/边界解析。 */
  private static Issuance parse(String payloadJson) {
    JsonNode payload = EconomyCommandPayloads.parseObject(TYPE, payloadJson);
    // ★★ W5-fix6：两条地址恰给一个（governmentId 精确 / govUnitId 派生）——唯一拼写点在助手方法里。
    GovernmentId governmentId = EconomyCommandPayloads.requireGovernmentAddress(TYPE, payload);
    String address =
        payload.hasNonNull("governmentId")
            ? "governmentId:" + governmentId.value()
            : "govUnitId:" + govUnitIdOf(payload);
    String currency = EconomyCommandPayloads.requireText(TYPE, payload, "currency");
    long amountMilli = EconomyCommandPayloads.requireLong(TYPE, payload, "amountMilli");
    String kindText =
        payload.hasNonNull("kind")
            ? EconomyCommandPayloads.requireText(TYPE, payload, "kind")
            : MoneyIssuanceKind.FISCAL_ISSUE.name();
    MoneyIssuanceKind kind;
    try {
      kind = MoneyIssuanceKind.valueOf(kindText.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      // ★ 枚举自己就是"有哪些类别"的唯一权威（不手抄两份清单：加一个类别时这里不会静默说谎）。
      throw new IllegalArgumentException(
          "kind 只认 " + java.util.Arrays.toString(MoneyIssuanceKind.values()) + ": " + kindText);
    }
    if (kind == MoneyIssuanceKind.WITHDRAWAL || kind == MoneyIssuanceKind.MINT_PRODUCTION) {
      // ★ 这两类的生产者不是本命令：WITHDRAWAL 由回笼路径写（TreasuryWithdrawal），MINT_PRODUCTION 由 MintSettlement 写
      //   （id 由 (政府, 日, 币种) 确定性派生）。手写它们 = 伪造一条与真生产路径无关的审计 ⇒ 具名拒。
      throw new IllegalArgumentException(
          "kind " + kind.name() + " 不是本命令可写的类别（回笼走回笼路径、铸币产出走 MintSettlement 的确定性 id）");
    }
    String reason =
        payload.hasNonNull("reason")
            ? EconomyCommandPayloads.requireText(TYPE, payload, "reason")
            : null;
    return new Issuance(governmentId, address, currency, amountMilli, kind, reason);
  }

  /** 只给日志用的 govUnitId 文本（形状已由 {@code requireGovernmentAddress} 判过）。 */
  private static String govUnitIdOf(JsonNode payload) {
    return payload.hasNonNull("govUnitId") ? payload.get("govUnitId").asText() : "";
  }

  /** 一条发行：发行主体（已解析的政府身份 + 地址原文，后者只进日志）+ 币种 + 金额 + 类别 + 可选审计文本（构造期判完边界）。 */
  private record Issuance(
      GovernmentId governmentId,
      String address,
      String currency,
      long amountMilli,
      MoneyIssuanceKind kind,
      String reason) {

    Issuance {
      Objects.requireNonNull(governmentId, "governmentId");
      Objects.requireNonNull(address, "address");
      Objects.requireNonNull(currency, "currency");
      Objects.requireNonNull(kind, "kind");
      if (address.isBlank()) {
        throw new IllegalArgumentException("发行主体地址不得为空白");
      }
      if (currency.isBlank()) {
        throw new IllegalArgumentException("currency 不得为空白");
      }
      if (amountMilli <= 0L) {
        throw new IllegalArgumentException("amountMilli 必须 > 0（方向由 kind 表达，不用负号）: " + amountMilli);
      }
      if (reason != null && reason.isBlank()) {
        throw new IllegalArgumentException("reason 给了就必须非空白");
      }
    }
  }
}
