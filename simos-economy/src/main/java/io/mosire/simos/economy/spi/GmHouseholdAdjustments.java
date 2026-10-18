package io.mosire.simos.economy.spi;

import static io.mosire.simos.economy.spi.EconomyGmAdjustments.COMMAND;
import static io.mosire.simos.economy.spi.EconomyGmAdjustments.Change;
import static io.mosire.simos.economy.spi.EconomyGmAdjustments.FORGIVE_DEBT;
import static io.mosire.simos.economy.spi.EconomyGmAdjustments.Projection;
import static io.mosire.simos.economy.spi.GmAdjustmentSupport.parseDebtId;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.time.DebtContractBook;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** GM 调整拆分 helper（由 EconomyGmAdjustments 抽取；行为不变）。 */
final class GmHouseholdAdjustments {

  private GmHouseholdAdjustments() {}

  /** {@code forgiveDebt}：{@code debtContractId} + 可选 {@code amount}（缺省 = 全额本金）。 */
  static Projection forgive(EconomyData base, JsonNode parameters, String reason, long day) {
    String label = COMMAND + "." + FORGIVE_DEBT;
    DebtContractId id =
        parseDebtId(label, EconomyCommandPayloads.requireText(label, parameters, "debtContractId"));
    DebtContract existing = base.debtContracts().get(id);
    if (existing == null) {
      throw new IllegalArgumentException(label + " 债务合同不存在: " + id.value());
    }
    if (existing.principal() <= 0L) {
      throw new IllegalArgumentException(label + " 合同本金已为 0，无可减免: " + id.value());
    }
    long amount = existing.principal();
    JsonNode amountNode = parameters.get("amount");
    if (amountNode != null && !amountNode.isNull()) {
      amount = EconomyCommandPayloads.requireLong(label, parameters, "amount");
    }
    if (amount <= 0L) {
      throw new IllegalArgumentException(label + " 的 amount 必须 > 0: " + amount);
    }
    if (amount > existing.principal()) {
      throw new IllegalArgumentException(
          label + " 的 amount 超过本金（不可下溢）：amount=" + amount + "，本金=" + existing.principal());
    }
    Map<DebtContractId, DebtContract> contracts = new LinkedHashMap<>(base.debtContracts());
    // ★ 唯一写口：只减/清本金（部分减免保留原状态、全额 ⇒ FORGIVEN），不碰粮/钱库存。
    DebtContractBook.forgive(contracts, id, amount, reason);
    EconomyData projected = base.withDebtContracts(contracts);
    EconomyChangeSet changeSet = EconomyChangeSet.between(base, projected);
    return new Projection(
        FORGIVE_DEBT,
        reason,
        day,
        projected,
        changeSet,
        List.of(
            new Change("debtContracts", id.value(), existing, projected.debtContracts().get(id))));
  }
}
