package io.mosire.simos.app.inventory;

import io.mosire.simos.economy.api.inventory.InventoryBackendKind;
import io.mosire.simos.economy.api.inventory.InventoryLot;
import io.mosire.simos.economy.api.inventory.InventoryLotId;
import io.mosire.simos.economy.api.inventory.InventoryLotKind;
import io.mosire.simos.economy.api.inventory.InventoryLotMutation;
import io.mosire.simos.economy.api.inventory.InventoryTransferPlan;
import io.mosire.simos.economy.api.inventory.InventoryTransferPort;
import io.mosire.simos.economy.api.inventory.InventoryTransferReceipt;
import io.mosire.simos.economy.api.inventory.InventoryTransferRequest;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.HexLand;
import io.mosire.simos.map.LandComponent;
import io.mosire.simos.map.LandComponentId;
import io.mosire.simos.map.LandTypeArea;
import io.mosire.simos.map.LandTypeId;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.ops.LandComponentOperations;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** LAND lot → map 土地 component 写口的适配器。 */
public final class LandLotTransferAdapter implements InventoryTransferAdapter {

  @Override
  public InventoryLotKind kind() {
    return InventoryLotKind.LAND;
  }

  @Override
  public InventoryTransferPlan plan(
      InventoryTransferContext context, InventoryTransferRequest request) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(request, "request");

    InventoryLot lot = requireSourceLot(context, request.sourceLotId());
    if (lot.itemKind() != InventoryLotKind.LAND) {
      throw new IllegalArgumentException(
          InventoryTransferPort.REASON_KIND_UNSUPPORTED + ": lot kind=" + lot.itemKind());
    }
    if (lot.owner().equals(request.targetOwner())) {
      throw new IllegalArgumentException(
          InventoryTransferPort.REASON_TARGET_OWNER_SAME + ": owner=" + lot.owner().value());
    }
    HexCoord location =
        lot.location()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        InventoryTransferPort.REASON_LOCATION_MISSING
                            + ": sourceLotId="
                            + lot.lotId().value()));
    if (request.targetLocation().isPresent() && !request.targetLocation().get().equals(location)) {
      throw new IllegalArgumentException(
          InventoryTransferPort.REASON_TARGET_LOCATION_MISMATCH
              + ": landHex="
              + location
              + " requested="
              + request.targetLocation().get());
    }

    LandTypeId landType;
    try {
      landType = LandTypeId.parse(lot.itemTypeId());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          InventoryTransferPort.REASON_KIND_UNSUPPORTED
              + ": invalid land itemTypeId="
              + lot.itemTypeId(),
          e);
    }
    LandComponentId sourceComponentId;
    try {
      sourceComponentId = request.sourceLotId().landComponentId();
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          InventoryTransferPort.REASON_LOT_MISSING + ": " + request.sourceLotId().value(), e);
    }

    HexCell sourceCell = context.gameMap().hexes().get(location);
    if (sourceCell == null) {
      throw new IllegalArgumentException(
          InventoryTransferPort.REASON_LOT_MISSING + ": no map hex=" + location);
    }
    LandComponent sourceComponent =
        LandComponentOperations.componentOf(sourceCell.land(), landType, sourceComponentId);
    if (sourceComponent == null) {
      throw new IllegalArgumentException(
          InventoryTransferPort.REASON_LOT_MISSING
              + ": no land component="
              + sourceComponentId.value()
              + " at "
              + location);
    }

    final String landOwner;
    try {
      landOwner = LandComponentOperations.ownerOf(sourceComponent);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          InventoryTransferPort.REASON_SOURCE_OWNER_MISMATCH
              + ": invalid owner attr for component="
              + sourceComponentId.value(),
          e);
    }
    if (!landOwner.equals(lot.owner().value())) {
      throw new IllegalArgumentException(
          InventoryTransferPort.REASON_SOURCE_OWNER_MISMATCH
              + ": lotOwner="
              + lot.owner().value()
              + " componentOwner="
              + landOwner
              + " component="
              + sourceComponentId.value());
    }
    if (request.quantityMilli() > sourceComponent.quantityMilliMu()) {
      throw new IllegalArgumentException(
          InventoryTransferPort.REASON_QUANTITY_EXCEEDS_AVAILABLE
              + ": component="
              + sourceComponentId.value()
              + " requested="
              + request.quantityMilli()
              + " available="
              + sourceComponent.quantityMilliMu());
    }

    HexLand nextLand;
    try {
      nextLand =
          LandComponentOperations.transferFromComponent(
              sourceCell.land(),
              location,
              landType,
              sourceComponentId,
              request.targetOwner().value(),
              request.quantityMilli());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          InventoryTransferPort.REASON_QUANTITY_EXCEEDS_AVAILABLE
              + ": U3 transfer rejected component="
              + sourceComponentId.value(),
          e);
    }

    Map<HexCoord, HexCell> nextHexes = new LinkedHashMap<>(context.gameMap().hexes());
    nextHexes.put(location, sourceCell.withLand(nextLand));
    GameMap baseMap = context.gameMap();
    GameMap targetMap = baseMap.withHexes(nextHexes);
    MapChangeSet mapChangeSet = MapChangeSet.between(baseMap, targetMap);

    List<InventoryLotId> targetLotIds = targetLandLotIds(nextLand, landType, request);
    Set<String> reads = new LinkedHashSet<>();
    reads.add(context.mapLandAddress(location));
    Set<String> writes = new LinkedHashSet<>(reads);
    InventoryLotMutation mutation =
        new InventoryLotMutation.LandComponentMutation(
            location, landType, sourceComponentId, request.targetOwner(), request.quantityMilli());
    InventoryTransferReceipt receipt =
        new InventoryTransferReceipt(
            request.transferId(),
            request.sourceLotId(),
            request.quantityMilli(),
            targetLotIds,
            InventoryBackendKind.MAP_LAND,
            true);
    return new InventoryTransferPlan(
        request.transferId(),
        request.sourceLotId(),
        request.quantityMilli(),
        List.of(mutation),
        Map.of(InventoryTransferPlan.MAP_MODULE_KEY, mapChangeSet),
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

  private static List<InventoryLotId> targetLandLotIds(
      HexLand land, LandTypeId landType, InventoryTransferRequest request) {
    LandTypeArea area = land.byType().get(landType);
    if (area == null) {
      return List.of();
    }
    List<InventoryLotId> result = new ArrayList<>();
    for (LandComponent component : area.components()) {
      if (LandComponentOperations.ownerOf(component).equals(request.targetOwner().value())) {
        result.add(InventoryLotId.forLand(component.id()));
      }
    }
    return result;
  }
}
