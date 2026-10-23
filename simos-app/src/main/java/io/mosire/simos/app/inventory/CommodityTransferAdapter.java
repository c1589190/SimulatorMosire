package io.mosire.simos.app.inventory;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.change.ActorChangeSet;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.inventory.InventoryBackendKind;
import io.mosire.simos.economy.api.inventory.InventoryLot;
import io.mosire.simos.economy.api.inventory.InventoryLotId;
import io.mosire.simos.economy.api.inventory.InventoryLotKind;
import io.mosire.simos.economy.api.inventory.InventoryLotMutation;
import io.mosire.simos.economy.api.inventory.InventoryTransferPlan;
import io.mosire.simos.economy.api.inventory.InventoryTransferPort;
import io.mosire.simos.economy.api.inventory.InventoryTransferReceipt;
import io.mosire.simos.economy.api.inventory.InventoryTransferRequest;
import io.mosire.simos.map.hex.HexCoord;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** COMMODITY lot → actor 家户商品余额写口的适配器。 */
public final class CommodityTransferAdapter implements InventoryTransferAdapter {

  @Override
  public InventoryLotKind kind() {
    return InventoryLotKind.COMMODITY;
  }

  @Override
  public InventoryTransferPlan plan(
      InventoryTransferContext context, InventoryTransferRequest request) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(request, "request");

    InventoryLot lot = requireSourceLot(context, request.sourceLotId());
    if (lot.itemKind() != InventoryLotKind.COMMODITY) {
      throw new IllegalArgumentException(
          InventoryTransferPort.REASON_KIND_UNSUPPORTED + ": lot kind=" + lot.itemKind());
    }
    if (lot.owner().equals(request.targetOwner())) {
      throw new IllegalArgumentException(
          InventoryTransferPort.REASON_TARGET_OWNER_SAME + ": owner=" + lot.owner().value());
    }

    CommodityId commodity;
    try {
      commodity = CommodityId.parse(lot.itemTypeId());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          InventoryTransferPort.REASON_KIND_UNSUPPORTED
              + ": invalid commodity itemTypeId="
              + lot.itemTypeId(),
          e);
    }
    HouseholdAccountKey sourceKey = new HouseholdAccountKey(lot.owner());
    HouseholdInventory sourceInventory = context.actorData().accounts().get(sourceKey);
    if (sourceInventory == null) {
      throw new IllegalArgumentException(
          InventoryTransferPort.REASON_LOT_MISSING
              + ": no actor inventory for source owner="
              + lot.owner().value());
    }

    long balance = sourceInventory.balances().getOrDefault(commodity, 0L);
    long frozen = sourceInventory.frozenBalances().getOrDefault(commodity, 0L);
    long available = balance - frozen;
    if (request.quantityMilli() > balance) {
      throw new IllegalArgumentException(
          InventoryTransferPort.REASON_QUANTITY_EXCEEDS_AVAILABLE
              + ": commodity="
              + commodity.value()
              + " requested="
              + request.quantityMilli()
              + " balance="
              + balance);
    }
    if (request.quantityMilli() > available) {
      throw new IllegalArgumentException(
          InventoryTransferPort.REASON_FROZEN_SHORTFALL
              + ": commodity="
              + commodity.value()
              + " requested="
              + request.quantityMilli()
              + " available="
              + available
              + " frozen="
              + frozen);
    }

    HexCoord targetLocation =
        request
            .targetLocation()
            .orElseGet(
                () ->
                    lot.location()
                        .orElseThrow(
                            () ->
                                new IllegalArgumentException(
                                    InventoryTransferPort.REASON_LOCATION_MISSING
                                        + ": sourceLotId="
                                        + lot.lotId().value())));

    HouseholdAccountKey targetKey = new HouseholdAccountKey(request.targetOwner());
    HouseholdInventory targetInventory = context.actorData().accounts().get(targetKey);

    Map<CommodityId, Long> sourceBalances = new LinkedHashMap<>(sourceInventory.balances());
    sourceBalances.put(commodity, balance - request.quantityMilli());
    HouseholdInventory nextSource =
        new HouseholdInventory(
            sourceKey,
            sourceBalances,
            sourceInventory.money(),
            sourceInventory.frozenBalances(),
            sourceInventory.frozenMoney());

    Map<CommodityId, Long> targetBalances =
        targetInventory == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(targetInventory.balances());
    targetBalances.put(
        commodity,
        Math.addExact(targetBalances.getOrDefault(commodity, 0L), request.quantityMilli()));
    HouseholdInventory nextTarget =
        targetInventory == null
            ? new HouseholdInventory(targetKey, targetBalances)
            : new HouseholdInventory(
                targetKey,
                targetBalances,
                targetInventory.money(),
                targetInventory.frozenBalances(),
                targetInventory.frozenMoney());

    ActorData base = context.actorData();
    ActorData target = base.withInventory(nextSource).withInventory(nextTarget);
    ActorChangeSet actorChangeSet = ActorChangeSet.between(base, target);

    InventoryLotId targetLotId =
        InventoryLotId.forCommodity(request.targetOwner(), targetLocation, commodity);
    Set<String> reads = new LinkedHashSet<>();
    reads.add(context.actorAccountAddress(lot.owner()));
    reads.add(context.actorAccountAddress(request.targetOwner()));
    Set<String> writes = new LinkedHashSet<>(reads);

    InventoryLotMutation mutation =
        new InventoryLotMutation.CommodityBalanceMutation(
            lot.owner(), request.targetOwner(), commodity, request.quantityMilli());
    InventoryTransferReceipt receipt =
        new InventoryTransferReceipt(
            request.transferId(),
            request.sourceLotId(),
            request.quantityMilli(),
            List.of(targetLotId),
            InventoryBackendKind.ACTOR_GOODS,
            true);
    return new InventoryTransferPlan(
        request.transferId(),
        request.sourceLotId(),
        request.quantityMilli(),
        List.of(mutation),
        Map.of(InventoryTransferPlan.ACTOR_MODULE_KEY, actorChangeSet),
        reads,
        writes,
        receipt);
  }

  private static InventoryLot requireSourceLot(
      InventoryTransferContext context, InventoryLotId sourceLotId) {
    try {
      return context.view().require(sourceLotId);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          InventoryTransferPort.REASON_LOT_MISSING + ": " + sourceLotId.value(), e);
    }
  }
}
