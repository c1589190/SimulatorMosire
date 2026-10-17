package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.model.ProductionWageQuote;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** D3 §2.5.1.0 / D2 §8.2：D2 cycle Prospect 到下一 tick 可交付收入的唯一只读请求。 */
public record NextTickIncomeProjectionRequest(
    HouseholdId household,
    long nextTick,
    long cycleStartTick,
    long cycleEndTick,
    Map<ProductionRoleId, Long> expectedNextTickLaborMilli,
    Optional<ProductionWageQuote> quote,
    OptionalLong targetLaborMilliPerDay,
    Map<CommodityId, Long> cycleProductShareMilli) {

  public NextTickIncomeProjectionRequest {
    Objects.requireNonNull(household, "NextTickIncomeProjectionRequest.household 不得为 null");
    Objects.requireNonNull(
        expectedNextTickLaborMilli,
        "survival-income-projection-missing: expectedNextTickLaborMilli 不得为 null");
    LinkedHashMap<ProductionRoleId, Long> labor = new LinkedHashMap<>();
    expectedNextTickLaborMilli.forEach(
        (role, milli) -> {
          Objects.requireNonNull(role, "expectedNextTickLaborMilli 不得含 null 角色");
          Objects.requireNonNull(milli, "expectedNextTickLaborMilli 不得含 null 数量");
          if (milli < 0L) {
            throw new SurvivalOutlookException(
                "survival-input-invalid", "expectedNextTickLaborMilli 不得为负: " + role.value());
          }
          if (milli > 0L) {
            labor.put(role, milli);
          }
        });
    expectedNextTickLaborMilli = Collections.unmodifiableMap(labor);
    quote = quote == null ? Optional.empty() : quote;
    targetLaborMilliPerDay =
        targetLaborMilliPerDay == null ? OptionalLong.empty() : targetLaborMilliPerDay;
    Objects.requireNonNull(
        cycleProductShareMilli,
        "survival-income-projection-missing: cycleProductShareMilli 不得为 null");
    LinkedHashMap<CommodityId, Long> shares = new LinkedHashMap<>();
    cycleProductShareMilli.forEach(
        (commodity, milli) -> {
          Objects.requireNonNull(commodity, "cycleProductShareMilli 不得含 null 商品");
          Objects.requireNonNull(milli, "cycleProductShareMilli 不得含 null 数量");
          if (milli < 0L) {
            throw new SurvivalOutlookException(
                "survival-input-invalid", "cycleProductShareMilli 不得为负: " + commodity.value());
          }
          if (milli > 0L) {
            shares.put(commodity, milli);
          }
        });
    cycleProductShareMilli = Collections.unmodifiableMap(shares);
  }
}
