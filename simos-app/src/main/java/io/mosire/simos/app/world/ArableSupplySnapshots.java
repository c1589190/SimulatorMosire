package io.mosire.simos.app.world;

import io.mosire.simos.economy.api.land.LandHolding;
import io.mosire.simos.economy.land.LandComponentLedger;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.HexLand;
import io.mosire.simos.map.LandTypeId;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.ops.LandComponentOperations;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★★ <b>D-032 L4：Map 土地组件树 ↔ economy 瞬态 {@link LandComponentLedger} 的组合根适配</b>。
 *
 * <p>Map component 的 {@code owner} 属性是 LAND 所有权的唯一持久权威。app 在每次 advance 起点把 {@code
 * map.hexes[].land()} 交给 {@link LandComponentLedger#from(Map)}；日循环只在账本上改；轮末把最终 owner→quantity 聚合经
 * {@link LandComponentOperations#setArableOwners} 写回工作 GameMap，并把 {@code MapChangeSet} 与
 * economy/social/actor/production 的变更集放进同一个 revision。
 *
 * <p>失败语义（全部具名 fail-closed）：map 切片缺失、Map component 缺 owner、写回 hex 不在 Map。
 */
public final class ArableSupplySnapshots {

  private ArableSupplySnapshots() {}

  /** 从当前完整状态构造瞬态 LAND 组件账本（arable component 无 owner ⇒ fail-closed）。 */
  public static LandComponentLedger landLedgerFrom(SimulationState state, String caller) {
    Objects.requireNonNull(state, "state");
    String prefix = Objects.requireNonNull(caller, "caller") + ": ";
    GameMap map = mapOf(state, prefix);
    Map<HexCoord, HexLand> lands = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, HexCell> entry : map.hexes().entrySet()) {
      lands.put(entry.getKey(), entry.getValue().land());
    }
    try {
      return LandComponentLedger.from(lands);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(prefix + "land-ledger-invalid: " + e.getMessage(), e);
    }
  }

  /**
   * 把最终瞬态聚合账本写回 Map 工作副本：逐 hex 调 {@link LandComponentOperations#setArableOwners}，只改 arable 的
   * owner/quantity 形状，其它土地类型与属性原样保留。
   */
  public static GameMap applyLedgerToMap(GameMap map, LandComponentLedger ledger, String caller) {
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(ledger, "ledger");
    String prefix = Objects.requireNonNull(caller, "caller") + ": ";
    // ★ 写回前先按 Map 派生的 arable 总量复核最终聚合账本（每条 hex 守恒、无重复、无 0/负行）。
    try {
      ledger.replaceHoldings(ledger.holdingsOrEmpty());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          prefix + "land-writeback-ledger-invalid: " + e.getMessage(), e);
    }
    // 再校验所有持有都在 Map 里（不静默丢弃）。
    for (LandHolding holding : ledger.holdingsOrEmpty().values()) {
      if (!map.hexes().containsKey(holding.hex())) {
        throw new IllegalStateException(
            prefix
                + "land-writeback-unknown-hex: "
                + holding.hex()
                + " owner="
                + holding.owner().value());
      }
    }
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>(map.hexes());
    for (Map.Entry<HexCoord, HexCell> entry : map.hexes().entrySet()) {
      HexCoord hex = entry.getKey();
      HexCell cell = entry.getValue();
      Map<String, Long> owners = ledger.ownerQuantitiesAt(hex);
      if (owners.isEmpty() && !cell.land().byType().containsKey(LandTypeId.ARABLE)) {
        continue; // 没有 arable area 也没有 owner ⇒ 不凭空造空 area（保持其它 hex 字节不变）
      }
      HexLand next = LandComponentOperations.setArableOwners(cell.land(), hex, owners);
      if (!next.equals(cell.land())) {
        hexes.put(hex, cell.withLand(next));
      }
    }
    return map.withHexes(hexes);
  }

  private static GameMap mapOf(SimulationState state, String prefix) {
    Optional<Snapshot> snapshot = state.module("map");
    if (snapshot.isEmpty()) {
      throw new IllegalStateException(
          prefix + "land-ledger-map-missing: state 没有 map 切片，不能伪造空土地账本");
    }
    if (!(snapshot.get() instanceof MapSnapshot mapSnapshot)) {
      throw new IllegalStateException(
          prefix
              + "land-ledger-map-snapshot-type: state 的 map 切片不是 MapSnapshot: "
              + snapshot.get().getClass().getName());
    }
    return mapSnapshot.map();
  }
}
