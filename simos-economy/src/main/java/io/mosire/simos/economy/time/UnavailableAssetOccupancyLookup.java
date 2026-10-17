package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.util.state.RevisionId;
import java.util.Optional;

/**
 * ★★ <b>D-032 D4-C fail-closed 资产占用适配器</b>（D4 §7.0）：D4-I 真实实现未接时始终返回 {@link Optional#empty()}。
 *
 * <p>★ 无状态、只读。调用方必须将它解释为 {@code asset-occupancy-unavailable}，不得读成零占用或回退整池扫描。
 */
public final class UnavailableAssetOccupancyLookup implements AssetOccupancyLookup {

  @Override
  public Optional<AssetOccupancySnapshot> occupancyOf(
      ProductionUnitId unit, RevisionId expectedRevision, long fromTick, long toTickExclusive) {
    return Optional.empty();
  }
}
