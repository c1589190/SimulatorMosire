package io.mosire.simos.app.tools.gov;

import io.mosire.simos.actor.model.AvailableStock;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.household.GovernmentServiceLaborBridge;
import io.mosire.simos.app.household.HouseholdUnitConsistency;
import io.mosire.simos.app.time.GovServiceFlowFeed;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.economy.EconomyCommodities;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.gov.GovServiceFlow;
import io.mosire.simos.gov.tools.GovToolViews;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.state.SimulationState;
import java.util.Map;
import java.util.Optional;

/**
 * {@link GovToolViews} 的宿主实现（组合根）：**逐条转调既有唯一实现** —— 视图形状（{@code ApiViews}）、两条供给桥 （{@code
 * GovernmentServiceLaborBridge} / {@code HouseholdUnitConsistency}）、tick 流量表（{@code
 * GovServiceFlowFeed}）、 可支配量（{@code AvailableStock}）与人口劳动（{@code social.householdLaborMilli} +
 * 本类持有的历法时钟）。
 *
 * <p>★ 劳动量/选人（要历法时钟的那一族）不在本类：见 {@code AppGovToolManpower}（同一条"装配件留组合根"的口径）。
 */
public final class AppGovToolViews implements GovToolViews {

  @Override
  public Map<String, Object> economyGovernment(SimulationState state) {
    return ApiViews.economyGovernment(state);
  }

  @Override
  public Map<String, Long> staffHouseholdProjection(SimulationState state, long tick) {
    return HouseholdUnitConsistency.staffHouseholdProjection(
        ToolSupport.economyData(state),
        io.mosire.simos.economy.EconomyProductionDefinitions.stateOf(state).instances(),
        ToolSupport.socialData(state),
        ToolSupport.unitState(state),
        tick);
  }

  @Override
  public Supply supply(
      SimulationState state, UnitId govUnitId, GovernmentFormation formation, long tick) {
    GovernmentServiceLaborBridge.Supply supply =
        GovernmentServiceLaborBridge.supply(
            ToolSupport.economyData(state),
            io.mosire.simos.economy.EconomyProductionDefinitions.stateOf(state).instances(),
            govUnitId,
            formation,
            tick);
    return new Supply(
        supply.securityLaborMilli(),
        supply.paperworkLaborMilli(),
        supply.committedSecurityLaborMilli(),
        supply.committedPaperworkLaborMilli(),
        supply.underfedHouseholds());
  }

  @Override
  public Map<HouseholdId, Long> committedLaborByHousehold(
      SimulationState state, UnitId govUnitId, long tick) {
    return GovernmentServiceLaborBridge.committedLaborByHousehold(
        ToolSupport.economyData(state),
        io.mosire.simos.economy.EconomyProductionDefinitions.stateOf(state).instances(),
        govUnitId,
        tick);
  }

  @Override
  public Optional<Map<UnitId, GovServiceFlow>> serviceFlow(String mapId, long tick) {
    return GovServiceFlowFeed.last(mapId, tick);
  }

  @Override
  public String serviceFlowUnavailableReason() {
    return GovServiceFlowFeed.UNAVAILABLE_REASON;
  }

  @Override
  public TreasuryAvailable treasuryAvailable(SimulationState state, String householdId) {
    HouseholdInventory inventory =
        ApiViews.actorData(state)
            .accounts()
            .get(new HouseholdAccountKey(HouseholdId.parse(householdId)));
    if (inventory == null) {
      return null;
    }
    return new TreasuryAvailable(
        AvailableStock.available(inventory, EconomyCommodities.GRAIN),
        AvailableStock.available(inventory, EconomyCommodities.CLOTH),
        AvailableStock.available(inventory, MoneyVocabulary.SILVER_CURRENCY));
  }

  @Override
  public long availableSilver(SimulationState state, String householdId) {
    return AvailableStock.available(
        ApiViews.actorData(state), HouseholdId.parse(householdId), MoneyVocabulary.SILVER_CURRENCY);
  }
}
