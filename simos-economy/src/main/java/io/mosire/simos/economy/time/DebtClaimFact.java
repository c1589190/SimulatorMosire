package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import java.util.Objects;

/** D3 §2.5.1：既有债务的只读事实；不是收入、不自动豁免。 */
public record DebtClaimFact(
    DebtContractId debt, CurrencyId currency, long principalMilli, long dueTick) {
  public DebtClaimFact {
    Objects.requireNonNull(debt, "DebtClaimFact.debt 不得为 null");
    Objects.requireNonNull(currency, "DebtClaimFact.currency 不得为 null");
    if (principalMilli < 0L) {
      throw new IllegalArgumentException("DebtClaimFact.principalMilli 不得为负: " + principalMilli);
    }
    if (dueTick < 0L) {
      throw new IllegalArgumentException("DebtClaimFact.dueTick 不得为负: " + dueTick);
    }
  }
}
