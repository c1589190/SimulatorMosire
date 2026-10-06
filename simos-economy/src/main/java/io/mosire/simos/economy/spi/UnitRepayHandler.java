package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.time.DebtContractBook;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * ★★ <b>{@code economy.UnitRepay}（P2-D）：单位向放贷方偿还的债务腿</b>。载荷：
 *
 * <pre>{@code
 * {"unitId":"u-prov",
 *  "borrowerHousehold":"hh-gov-u-prov",   // 借款单位国库家户（= 债务人）
 *  "lenderHousehold":"hh-0_0-urban-landlord",
 *  "unit":"money"|"grain",
 *  "amount":1200,
 *  "debtId":"debtc-…",                     // 可选：同 (借款人,放贷方,unit) 有多条未结清合同时必须给
 *  "reason":"…"}                           // 可选审计文本
 * }</pre>
 *
 * <p>★★ <b>合同身份</b>：先按 {@code debtId}（给了就用它，并逐值核对 debtor/creditor/unit）；没给则在 {@code debtContracts}
 * 里按 {@code (borrowerHousehold, lenderHousehold, unit)} 找<b>恰一条</b> {@code principal > 0} 的合同——0 条
 * ⇒ 具名拒"没有未结清债务"；多条 ⇒ 具名拒并列出现有合同 id，要求调用方用 {@code debtId} 指明 （不按插入序猜、不合并不同 terms）。
 *
 * <p>★★ <b>唯一写口</b>：{@link DebtContractBook#reduce} 负责本金下溢守卫、清 0 转 {@code SETTLED} （{@code
 * DEFAULTED} 不洗白）。本命令不碰账户。
 *
 * <p>★★ <b>资金走家户账户，但不在本命令里</b>（同 {@code UnitBorrow} 的架构约束）：完整调用必须是同一批 （{@code
 * CoreSimos.submitBatch}）：
 *
 * <pre>
 * ① actor.TransferAccounts  {from:{household:borrowerHousehold}, to:{household:lenderHousehold},
 *                             goods/money: 偿还额}    // 钱/粮真的换手
 * ② economy.UnitRepay       {…本载荷…}                 // 债务本金下降
 * </pre>
 *
 * <p>★ 先①后②：余额不足由 {@code actor.TransferAccounts} 具名拒、整批零 revision； <b>只提本命令</b>会把债记小却没付钱。
 *
 * <p>★★ <b>GM-only</b>（同 {@code UnitBorrow}）：{@code targetPaths} 返回空表；GM 的 {@code
 * simos.command.submit} 可调。
 */
public final class UnitRepayHandler implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "economy.UnitRepay";

  private static final Logger LOG = EconomyLog.debt();

  /** 粮的商品 id（{@link EconomyVocabulary} 的唯一拼写点）。 */
  private static final CommodityId GRAIN = CommodityId.parse(EconomyVocabulary.GRAIN_COMMODITY_ID);

  /** 银的货币 id（{@link MoneyVocabulary} 的唯一拼写点）。 */
  private static final CurrencyId SILVER = MoneyVocabulary.SILVER_CURRENCY;

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    parse(EconomyCommandPayloads.parseObject(TYPE, payloadJson));
    return List.of();
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data();
    try {
      JsonNode payload = EconomyCommandPayloads.parseObject(TYPE, payloadJson);
      return new HandlerOutcome.Applied(project(base, payload));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** 纯函数项目：解析 → 找合同 → 本金减少 → 变更集。 */
  private static EconomyChangeSet project(EconomyData base, JsonNode payload) {
    Request request = parse(payload);
    if (request.amount() < 1L) {
      throw new IllegalArgumentException(TYPE + " 的 amount 必须 ≥ 1: " + request.amount());
    }
    requireHousehold(base, request.borrowerHousehold(), "borrowerHousehold");
    requireHousehold(base, request.lenderHousehold(), "lenderHousehold");
    if (request.borrowerHousehold().equals(request.lenderHousehold())) {
      throw new IllegalArgumentException(
          TYPE
              + " 的 borrowerHousehold 与 lenderHousehold 不得相同: "
              + request.borrowerHousehold().value());
    }
    DebtUnit debtUnit = debtUnit(request.unit());
    DebtContract contract = resolveContract(base, request, debtUnit);
    if (request.amount() > contract.principal()) {
      throw new IllegalArgumentException(
          TYPE
              + " 的 amount 超过未结清本金（不超付、不找零）: amount="
              + request.amount()
              + "，本金="
              + contract.principal()
              + "，合同="
              + contract.id().value());
    }

    LinkedHashMap<DebtContractId, DebtContract> debts = new LinkedHashMap<>(base.debtContracts());
    DebtContract reduced = DebtContractBook.reduce(debts, contract.id(), request.amount());
    EconomyData projected = base.withDebtContracts(debts);
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "UNIT_REPAY",
                EconomyLogSource.ECONOMY_COMMAND,
                "unit",
                request.unitId(),
                "borrowerHousehold",
                request.borrowerHousehold().value(),
                "lenderHousehold",
                request.lenderHousehold().value(),
                "unitKey",
                debtUnit.key(),
                "amount",
                request.amount(),
                "contract",
                reduced.id().value(),
                "remainingPrincipal",
                reduced.principal(),
                "status",
                reduced.status(),
                "reasonLength",
                request.reason() == null ? 0 : request.reason().length()));
    return EconomyChangeSet.between(base, projected);
  }

  /** 合同解析：{@code debtId} 优先；否则 (debtor, creditor, unit) 必须恰一条未结清。 */
  private static DebtContract resolveContract(
      EconomyData base, Request request, DebtUnit debtUnit) {
    if (!request.debtId().isEmpty()) {
      DebtContractId id = DebtContractId.parse(request.debtId());
      DebtContract contract = base.debtContracts().get(id);
      if (contract == null) {
        throw new IllegalArgumentException(TYPE + " 找不到债务合同: " + id.value());
      }
      if (!contract.debtor().equals(request.borrowerHousehold())
          || !contract.creditor().equals(request.lenderHousehold())
          || !contract.unit().equals(debtUnit)) {
        throw new IllegalArgumentException(
            TYPE
                + " 的 debtId 与借款人/放贷方/计量标的不一致: debtId="
                + id.value()
                + " 合同="
                + contract.debtor().value()
                + "→"
                + contract.creditor().value()
                + " unit="
                + contract.unit().key()
                + "；载荷="
                + request.borrowerHousehold().value()
                + "→"
                + request.lenderHousehold().value()
                + " unit="
                + debtUnit.key());
      }
      if (contract.principal() <= 0L) {
        throw new IllegalArgumentException(TYPE + " 的合同已无未结清本金（没有可偿还的债务）: " + id.value());
      }
      return contract;
    }
    List<DebtContract> candidates = new ArrayList<>();
    for (DebtContract contract : base.debtContracts().values()) {
      if (contract.debtor().equals(request.borrowerHousehold())
          && contract.creditor().equals(request.lenderHousehold())
          && contract.unit().equals(debtUnit)
          && contract.principal() > 0L) {
        candidates.add(contract);
      }
    }
    candidates.sort(java.util.Comparator.comparing(candidate -> candidate.id().value()));
    if (candidates.isEmpty()) {
      throw new IllegalArgumentException(
          TYPE
              + " 没有未结清债务: "
              + request.borrowerHousehold().value()
              + "→"
              + request.lenderHousehold().value()
              + " unit="
              + debtUnit.key());
    }
    if (candidates.size() > 1) {
      List<String> ids = new ArrayList<>();
      for (DebtContract candidate : candidates) {
        ids.add(candidate.id().value());
      }
      throw new IllegalArgumentException(
          TYPE + " 在同一 (借款人,放贷方,unit) 下找到多条未结清合同，必须用 debtId 指明（不按插入序猜、不合并不同 terms）: " + ids);
    }
    return candidates.get(0);
  }

  private static void requireHousehold(EconomyData base, HouseholdId household, String field) {
    if (!base.classes().containsKey(household)) {
      throw new IllegalArgumentException(
          TYPE
              + " 的 "
              + field
              + " 不是已知家户（先 social.CreateHousehold / economy.RegisterGovernment）: "
              + household.value());
    }
  }

  private static DebtUnit debtUnit(String unit) {
    return switch (unit) {
      case "money" -> new DebtUnit.Money(SILVER);
      case "grain" -> new DebtUnit.Commodity(GRAIN);
      default ->
          throw new IllegalArgumentException(
              TYPE + " 的 unit 只认 \"money\"/\"grain\"（不做别名/大小写归一）: " + unit);
    };
  }

  private static Request parse(JsonNode payload) {
    String unitId = EconomyCommandPayloads.requireText(TYPE, payload, "unitId");
    HouseholdId borrower =
        HouseholdId.parse(EconomyCommandPayloads.requireText(TYPE, payload, "borrowerHousehold"));
    HouseholdId lender =
        HouseholdId.parse(EconomyCommandPayloads.requireText(TYPE, payload, "lenderHousehold"));
    String unit = EconomyCommandPayloads.requireText(TYPE, payload, "unit");
    debtUnit(unit);
    long amount = EconomyCommandPayloads.requireLong(TYPE, payload, "amount");
    String debtId = EconomyCommandPayloads.optionalText(TYPE, payload, "debtId", "");
    String reason = EconomyCommandPayloads.optionalText(TYPE, payload, "reason", "");
    return new Request(unitId, borrower, lender, unit, amount, debtId, reason);
  }

  private record Request(
      String unitId,
      HouseholdId borrowerHousehold,
      HouseholdId lenderHousehold,
      String unit,
      long amount,
      String debtId,
      String reason) {}
}
