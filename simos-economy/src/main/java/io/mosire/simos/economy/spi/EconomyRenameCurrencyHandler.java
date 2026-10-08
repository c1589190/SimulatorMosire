package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.economy.api.money.CurrencyDef;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ {@code economy.RenameCurrency}（A1 2026-10-08；约束设计书 §3.1-3）：<b>只改币种的显示名</b> —— 世界的账一个字都不动 （不变量
 * <b>I16</b> / 判据 <b>F1</b> / 负向用例 <b>M5</b>）。
 *
 * <pre>{@code
 * {"govUnitId":"u-central",   // 必填：该币种的发行 GOV（决策人侧由身份派生，GM 侧显式给）
 *  "currencyId":"copper",     // 必填：币种 id（不可变身份；本命令**改不动**它）
 *  "displayName":"黄铜",      // 必填：新显示名（非空白、不要求唯一）
 *  "reason"?: "…"}            // 可选：审计文本（只进日志）
 * }</pre>
 *
 * <p>★★ <b>为什么"只改词表的一个字段"必须是独立命令</b>：改名的语义边界就是"只改给人看的名字"。若把它做成 {@code DefineCurrency} 的一个分支（或允许改
 * id），调用方就有了"顺手换身份"的口子 —— 而余额/流水/债务/市场/订单的键全是 {@code CurrencyId}（铁律 1：ID 是身份）⇒ 换 id
 * 会让全世界的账对不上。本命令的载荷里<b>没有</b> id 之外的键，也没有 scale： 换精度会改变"最小单位"的含义（1 银 = 1000 还是 100
 * 毫），那是另一种语义变更，不在改名里。
 *
 * <p>★★ <b>权限不得放大（M6）</b>：只有<b>该币种的发行政府</b>能改它的显示名 —— 别的 GOV 改名 ⇒ 具名拒（{@code not-issuer}）。 这条判据与
 * {@code gov.issueMoney} 同源（都在 {@code Government.issuable} 上判），于是"谁能改这个名字"和"谁能发这种钱" 不可能漂成两套。
 *
 * <p>★ <b>GM-only</b>（同 {@link EconomyDefineCurrencyHandler}）：决策人侧的受控入口是窄工具 {@code
 * simos.gov.renameCurrency}（身份派生 + 只能自己的 GOV + GM 审批链）。
 */
public final class EconomyRenameCurrencyHandler
    implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "economy.RenameCurrency";

  private static final LogChannel LOG = EventLog.channel(EconomyLog.command());

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    parse(payloadJson); // 形状校验；本命令没有格资源目标（词表是世界级的）。
    return List.of();
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data();
    Rename rename;
    try {
      rename = parse(payloadJson);
    } catch (IllegalArgumentException e) {
      return rejected("bad-payload", null, EconomyCommandPayloads.logReason(e.getMessage()));
    }
    try {
      if (base.meta().isEmpty()) {
        return rejected("economy-not-activated", rename, "economy 切片尚未激活（先 economy.Seed 播种）");
      }
      CurrencyId currencyId = new CurrencyId(rename.currencyId());
      CurrencyDef current = base.currencies().get(currencyId);
      if (current == null) {
        return rejected(
            "currency-not-defined", rename, "币种 " + rename.currencyId() + " 在本世界的词表里没有定义");
      }
      GovernmentId governmentId = GovernmentIds.ofUnit(rename.govUnitId());
      Government government = base.governments().get(governmentId);
      if (government == null) {
        return rejected(
            "government-not-registered",
            rename,
            "GOV 单位未登记为政府（先 economy.RegisterGovernment）: " + governmentId.value());
      }
      if (!government.issuable().contains(currencyId)) {
        // ★ M6：越权改名 —— 只有发行这种钱的 GOV 能改它的显示名（说不出"这是我发的钱"就不许改）。
        return rejected(
            "not-issuer",
            rename,
            "政府 "
                + governmentId.value()
                + " 不发行币种 "
                + rename.currencyId()
                + "（issuable="
                + government.issuable()
                + "；只有发行政府能改它的显示名）");
      }
      if (current.displayName().equals(rename.displayName())) {
        return rejected(
            "display-name-unchanged", rename, "新显示名与现值逐字相同（" + current.displayName() + "），无需改名");
      }

      Map<CurrencyId, CurrencyDef> currencies = new LinkedHashMap<>(base.currencies());
      currencies.put(currencyId, current.withDisplayName(rename.displayName()));
      // ★★ 只写 currencies 这一张表：工具表 / 政府表 / 任何账都不在这条 revision 里（I16 的结构性保证）。
      EconomyData projected = base.withCurrencies(currencies);
      LOG.info(
          LogEvent.of(
              "CURRENCY_RENAMED",
              EconomyLogSource.ECONOMY_MONEY,
              "currency",
              currencyId.value(),
              "displayNameBefore",
              current.displayName(),
              "displayNameAfter",
              rename.displayName(),
              "scale",
              current.scale(),
              "government",
              governmentId.value(),
              "govUnit",
              rename.govUnitId(),
              "reasonLength",
              rename.reason() == null ? 0 : rename.reason().length()));
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, projected));
    } catch (IllegalArgumentException e) {
      return rejected(
          "contract-violation", rename, EconomyCommandPayloads.logReason(e.getMessage()));
    }
  }

  /** 具名拒：业务拒绝 = INFO（发生了什么 + 具名拒因），字段级细节 = DEBUG（为什么）。 */
  private static HandlerOutcome.Rejected rejected(String reason, Rename rename, String message) {
    LOG.info(
        LogEvent.of(
            "CURRENCY_RENAME_REJECTED",
            EconomyLogSource.ECONOMY_MONEY,
            "reason",
            reason,
            "currency",
            rename == null ? "" : rename.currencyId(),
            "govUnit",
            rename == null ? "" : rename.govUnitId()));
    LOG.debug(
        LogEvent.of(
            "CURRENCY_RENAME_REJECTED_DETAIL",
            EconomyLogSource.ECONOMY_MONEY,
            "reason",
            reason,
            "displayName",
            rename == null ? "" : rename.displayName(),
            "message",
            message));
    return new HandlerOutcome.Rejected(TYPE + " 具名拒（" + reason + "）: " + message);
  }

  /** 形状/边界解析。 */
  private static Rename parse(String payloadJson) {
    JsonNode payload = EconomyCommandPayloads.parseObject(TYPE, payloadJson);
    String govUnitId = EconomyCommandPayloads.requireText(TYPE, payload, "govUnitId");
    String currencyId = EconomyCommandPayloads.requireText(TYPE, payload, "currencyId");
    String displayName = EconomyCommandPayloads.requireText(TYPE, payload, "displayName");
    String reason =
        payload.hasNonNull("reason")
            ? EconomyCommandPayloads.requireText(TYPE, payload, "reason")
            : null;
    return new Rename(govUnitId, currencyId, displayName, reason);
  }

  /** 一条改名：币种 id（定位身份）+ 新显示名 + 可选审计文本。 */
  private record Rename(String govUnitId, String currencyId, String displayName, String reason) {

    Rename {
      Objects.requireNonNull(govUnitId, "govUnitId");
      Objects.requireNonNull(currencyId, "currencyId");
      Objects.requireNonNull(displayName, "displayName");
      if (govUnitId.isBlank()) {
        throw new IllegalArgumentException("govUnitId 不得为空白");
      }
      if (currencyId.isBlank()) {
        throw new IllegalArgumentException("currencyId 不得为空白");
      }
      if (displayName.isBlank()) {
        throw new IllegalArgumentException("displayName 不得为空白（说不出名字就给 id 同字）");
      }
      if (reason != null && reason.isBlank()) {
        throw new IllegalArgumentException("reason 给了就必须非空白");
      }
    }
  }
}
