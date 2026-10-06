package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.stock.DeductionReason;
import io.mosire.simos.economy.api.stock.HouseholdPeriodicAdjustment;
import io.mosire.simos.economy.api.stock.PeriodicHouseholdAdjustmentId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.slf4j.Logger;

/**
 * ★★ {@code economy.UpsertHouseholdPeriodicAdjustment}（P4a，GM-only）：<b>按 id 全量 upsert 一条周期家户库存扣增规则</b>。
 *
 * <pre>{@code
 * {"id":"army-pay-1",
 *  "payer":"hh-unit:u-1",
 *  "payee"?: "hh-unit:u-2",                 // 缺省 / null = 明确 sink
 *  "goodsPerCycle"?: {"grain":10},          // 可空；逐值 > 0
 *  "moneyPerCycle"?: {"silver":5},          // 可空；逐值 > 0
 *  "reason":"military_salary",              // DeductionReason 规范字面量（小写）
 *  "periodDays":3, "phaseDay":0, "startsOnDay":0,
 *  "expiresOnDay"?: 30,                     // 缺省 / null = 永久；给了必须 >= startsOnDay
 *  "policySource":"gm:p4a"}
 * }</pre>
 *
 * <p>★★ <b>全量替换，不静默合并</b>：同 id 已存在 ⇒ 整条旧规则被新规则取代；字段缺省 = 按本命令的显式语义
 * （{@code payee}/{@code expiresOnDay} 空 = sink/永久），不是"沿用旧值"。规则构造期守卫判字段不变量；
 * {@code EconomyData} 构造期再判"键 == 值内 id"。
 *
 * <p>★ <b>GM-only</b>：实现 {@link GmOnlyCommand} ⇒ 排除出令白名单 / RegisterEffect / 决策人目录；GM 的
 * {@code simos.command.submit} 仍可直接调用（P4b 才做窄工具）。
 */
public final class EconomyUpsertPeriodicAdjustmentHandler
    implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "economy.UpsertHouseholdPeriodicAdjustment";

  private static final Logger LOG = EconomyLog.enterprise();

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    parse(TYPE, payloadJson); // 形状校验；本命令没有可声明的格资源目标
    return List.of();
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data();
    try {
      HouseholdPeriodicAdjustment rule = parse(TYPE, payloadJson);
      Map<PeriodicHouseholdAdjustmentId, HouseholdPeriodicAdjustment> adjustments =
          new LinkedHashMap<>(base.periodicAdjustments());
      HouseholdPeriodicAdjustment previous = adjustments.put(rule.id(), rule);
      EconomyData projected = base.withPeriodicAdjustments(adjustments);
      LOG.info(
          "event=PERIODIC_ADJUSTMENT_UPSERTED rule={} payer={} payee={} reason={} periodDays={}"
              + " phaseDay={} startsOnDay={} expiresOnDay={} replaced={}",
          rule.id().value(),
          rule.payer().value(),
          rule.payee().map(HouseholdId::value).orElse("<sink>"),
          rule.reason().value(),
          rule.periodDays(),
          rule.phaseDay(),
          rule.startsOnDay(),
          rule.expiresOnDay().isPresent() ? rule.expiresOnDay().getAsLong() : "<never>",
          previous != null);
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, projected));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** 形状/边界解析（{@code targetPaths} 与 {@code handle} 共用；错 ⇒ 抛具名载荷错）。 */
  static HouseholdPeriodicAdjustment parse(String command, String payloadJson) {
    JsonNode payload = EconomyCommandPayloads.parseObject(command, payloadJson);
    PeriodicHouseholdAdjustmentId id =
        PeriodicHouseholdAdjustmentId.parse(EconomyCommandPayloads.requireText(command, payload, "id"));
    HouseholdId payer =
        HouseholdId.parse(EconomyCommandPayloads.requireText(command, payload, "payer"));
    Optional<HouseholdId> payee =
        payload.hasNonNull("payee")
            ? Optional.of(
                HouseholdId.parse(EconomyCommandPayloads.requireText(command, payload, "payee")))
            : Optional.empty();
    Map<CommodityId, Long> goods =
        EconomyCommandPayloads.optionalCommodityMap(command, payload, "goodsPerCycle", true);
    Map<CurrencyId, Long> money =
        EconomyCommandPayloads.optionalCurrencyMap(command, payload, "moneyPerCycle", true);
    DeductionReason reason =
        DeductionReason.parse(
            EconomyCommandPayloads.requireText(command, payload, "reason"));
    long periodDays = EconomyCommandPayloads.requireLong(command, payload, "periodDays");
    long phaseDay = EconomyCommandPayloads.requireLong(command, payload, "phaseDay");
    long startsOnDay = EconomyCommandPayloads.requireLong(command, payload, "startsOnDay");
    OptionalLong expiresOnDay =
        payload.hasNonNull("expiresOnDay")
            ? OptionalLong.of(EconomyCommandPayloads.requireLong(command, payload, "expiresOnDay"))
            : OptionalLong.empty();
    String policySource =
        EconomyCommandPayloads.requireText(command, payload, "policySource");
    return new HouseholdPeriodicAdjustment(
        id,
        payer,
        payee,
        goods,
        money,
        reason,
        periodDays,
        phaseDay,
        startsOnDay,
        expiresOnDay,
        policySource);
  }
}
