package io.mosire.simos.economy.time;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** D3 §2.5.1：既有债务/不可用权益的只读快照。 */
public record ExistingClaims(
    List<DebtClaimFact> debts, Set<ResourceClaimKey> unavailableResourceClaims) {
  public ExistingClaims {
    Objects.requireNonNull(debts, "ExistingClaims.debts 不得为 null（无债给空表）");
    debts = List.copyOf(debts);
    Objects.requireNonNull(
        unavailableResourceClaims, "ExistingClaims.unavailableResourceClaims 不得为 null");
    LinkedHashSet<ResourceClaimKey> claims = new LinkedHashSet<>();
    for (ResourceClaimKey claim : unavailableResourceClaims) {
      Objects.requireNonNull(claim, "ExistingClaims.unavailableResourceClaims 不得含 null");
      claims.add(claim);
    }
    unavailableResourceClaims = Collections.unmodifiableSet(claims);
  }
}
