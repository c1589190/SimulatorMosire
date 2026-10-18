package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MarketSettlement.MarketRound;

import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.model.DebtContract;
import java.util.Map;

final class MarketRoundWitherBook {

  private MarketRoundWitherBook() {}

  static MarketRound newRoundForWither(
      MarketRoundCore source,
      MarketRoundCore.DeferredPaymentConfig config,
      Map<DebtContractId, DebtContract> debts) {
    MarketRound next =
        new MarketRound(
            source.day,
            source.householdEconomies,
            source.householdGoods,
            source.householdMoney,
            source.householdFrozenGoods,
            source.householdFrozenMoney,
            source.unmetToday,
            source.householdOfActor,
            source.industries,
            source.units,
            source.assetShares,
            source.relations,
            source.laborCommitments,
            source.shipments,
            source.ledger,
            source.operatorConditions,
            source.index,
            source.householdDemands,
            source.regulation,
            config,
            debts,
            source.marketExcludedHouseholds);
    next.arbitrage = source.arbitrage;
    next.fx = source.fx;
    next.govMandates = source.govMandates;
    next.portEnforcement = source.portEnforcement;
    next.portTax = source.portTax;
    next.procurementPriority = source.procurementPriority;
    next.inheritLandStateFrom(source);
    return next;
  }
}
