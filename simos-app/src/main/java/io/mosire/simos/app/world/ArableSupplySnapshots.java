package io.mosire.simos.app.world;

import io.mosire.simos.economy.api.land.LandHolding;
import io.mosire.simos.economy.land.LandComponentLedger;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.HexLand;
import io.mosire.simos.map.LandComponent;
import io.mosire.simos.map.LandTypeArea;
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
import java.util.TreeMap;

/**
 * ★★★ <b>D-032 L4：Map 土地组件树 ↔ economy 瞬态 {@link LandComponentLedger} 的组合根适配</b>。
 *
 * <p>Map component 的 {@code owner} 属性是 LAND 所有权的唯一持久权威。app 在每次 advance 起点把 {@code
 * map.hexes[].land()} 交给 {@link LandComponentLedger#from(Map)}；日循环只在账本上改；轮末以 Map 现有 ARABLE
 * component 为基底，按最终 owner 总量净差配对调用 {@link LandComponentOperations#transferQuantity} 写回工作 GameMap， 使
 * CROP/STAGE/PROGRESS 等 component 属性随被转移的组件保留，并把 {@code MapChangeSet} 与
 * economy/social/actor/production 的变更集放进同一个 revision。
 *
 * <p>失败语义（全部具名 fail-closed）：map 切片缺失、Map component 缺 owner、账本复核失败、未知 hex、 目标 owner 没有可转移源
 * component、当前/目标总量不等、买卖配对不足、写回后 owner 总量不等于目标。
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
   * 把最终瞬态聚合账本写回 Map 工作副本：逐 hex 以 Map 现有 ARABLE component 为基底做 owner 净差配对，只调用 {@link
   * LandComponentOperations#transferQuantity} 转移差额；不按 owner 聚合重建，不合并不同状态 component。
   *
   * <p>失败前置校验 + 写后逐项复核：任何失败都具名 fail-closed，不返回半成品，原 map 不变。
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
      Map<String, Long> target = ledger.ownerQuantitiesAt(hex);
      LandTypeArea area = cell.land().byType().get(LandTypeId.ARABLE);
      if ((area == null || area.components().isEmpty()) && target.isEmpty()) {
        continue; // 没有 arable component 也没有目标 owner ⇒ 保持原 cell（其它土地类型/字段原样）
      }
      if (area == null || area.components().isEmpty()) {
        throw new IllegalStateException(
            prefix
                + "land-writeback-source-missing: hex="
                + hex
                + " 目标 owner="
                + target.keySet()
                + "，但 Map 没有可转移的 ARABLE component");
      }
      Map<String, Long> current = arableOwnerQuantities(cell.land(), hex, prefix);
      long currentTotal = totalQuantity(current, hex, prefix, "current");
      long targetTotal = totalQuantity(target, hex, prefix, "target");
      if (currentTotal != targetTotal) {
        throw new IllegalStateException(
            prefix
                + "land-writeback-quantity-mismatch: hex="
                + hex
                + " current="
                + currentTotal
                + " target="
                + targetTotal
                + " (毫亩)");
      }
      HexLand next = transferNetDifferences(cell.land(), hex, current, target, prefix);
      Map<String, Long> actual = arableOwnerQuantities(next, hex, prefix);
      if (!actual.equals(target)) {
        throw new IllegalStateException(
            prefix
                + "land-writeback-owner-total-mismatch: hex="
                + hex
                + " target="
                + target
                + " actual="
                + actual);
      }
      if (!next.equals(cell.land())) {
        hexes.put(hex, cell.withLand(next));
      }
    }
    return map.withHexes(hexes);
  }

  /**
   * 按 owner id 稳定序把 current→target 的净差配成“卖方→买方”转移，只在差额上调用 {@code transferQuantity}。
   *
   * <p>正差 = 卖方（当前多于目标），负差 = 买方（当前少于目标）；两侧都按 owner 字典序，最低卖方配最低买方， 直到差额清零。任何一侧仍有剩余 ⇒ 具名 fail-closed。
   */
  private static HexLand transferNetDifferences(
      HexLand land,
      HexCoord hex,
      Map<String, Long> current,
      Map<String, Long> target,
      String prefix) {
    TreeMap<String, Long> sellers = new TreeMap<>();
    TreeMap<String, Long> buyers = new TreeMap<>();
    TreeMap<String, Long> owners = new TreeMap<>();
    owners.putAll(current);
    owners.putAll(target);
    for (String owner : owners.keySet()) {
      long currentQuantity = current.getOrDefault(owner, 0L);
      long targetQuantity = target.getOrDefault(owner, 0L);
      long difference;
      try {
        difference = Math.subtractExact(currentQuantity, targetQuantity);
      } catch (ArithmeticException e) {
        throw new IllegalStateException(
            prefix + "land-writeback-owner-difference-overflow: hex=" + hex + " owner=" + owner, e);
      }
      if (difference > 0L) {
        sellers.put(owner, difference);
      } else if (difference < 0L) {
        buyers.put(owner, Math.negateExact(difference));
      }
    }

    HexLand next = land;
    while (!sellers.isEmpty() && !buyers.isEmpty()) {
      String seller = sellers.firstKey();
      String buyer = buyers.firstKey();
      long quantity = Math.min(sellers.get(seller), buyers.get(buyer));
      try {
        next =
            LandComponentOperations.transferQuantity(
                next, hex, LandTypeId.ARABLE, seller, buyer, quantity);
      } catch (RuntimeException e) {
        throw new IllegalStateException(
            prefix
                + "land-writeback-transfer-failed: hex="
                + hex
                + " seller="
                + seller
                + " buyer="
                + buyer
                + " quantity="
                + quantity
                + ": "
                + e.getMessage(),
            e);
      }
      reduce(sellers, seller, quantity);
      reduce(buyers, buyer, quantity);
    }
    if (!sellers.isEmpty()) {
      String seller = sellers.firstKey();
      throw new IllegalStateException(
          prefix
              + "land-writeback-insufficient-buyers: hex="
              + hex
              + " seller="
              + seller
              + " remaining="
              + sellers.get(seller));
    }
    if (!buyers.isEmpty()) {
      String buyer = buyers.firstKey();
      throw new IllegalStateException(
          prefix
              + "land-writeback-insufficient-sellers: hex="
              + hex
              + " buyer="
              + buyer
              + " remaining="
              + buyers.get(buyer));
    }
    return next;
  }

  /** 从 Map 现有 ARABLE component 的 owner 属性求每 owner 数量；owner 读取只经 transfer/ownerOf 拼写点。 */
  private static Map<String, Long> arableOwnerQuantities(
      HexLand land, HexCoord hex, String prefix) {
    TreeMap<String, Long> quantities = new TreeMap<>();
    LandTypeArea area = land.byType().get(LandTypeId.ARABLE);
    if (area == null) {
      return quantities;
    }
    for (LandComponent component : area.components()) {
      final String owner;
      try {
        owner = LandComponentOperations.ownerOf(component);
      } catch (RuntimeException e) {
        throw new IllegalStateException(
            prefix
                + "land-writeback-owner-invalid: hex="
                + hex
                + " component="
                + component.id().value()
                + ": "
                + e.getMessage(),
            e);
      }
      if (owner.isBlank()) {
        throw new IllegalStateException(
            prefix
                + "land-writeback-owner-missing: hex="
                + hex
                + " component="
                + component.id().value());
      }
      try {
        quantities.merge(
            owner, component.quantityMilliMu(), (left, right) -> Math.addExact(left, right));
      } catch (ArithmeticException e) {
        throw new IllegalStateException(
            prefix + "land-writeback-owner-total-overflow: hex=" + hex + " owner=" + owner, e);
      }
    }
    return quantities;
  }

  /** 求 owner→数量表总和，溢出具名 fail-closed。 */
  private static long totalQuantity(
      Map<String, Long> quantities, HexCoord hex, String prefix, String side) {
    long total = 0L;
    for (Map.Entry<String, Long> entry : quantities.entrySet()) {
      try {
        total = Math.addExact(total, entry.getValue());
      } catch (ArithmeticException e) {
        throw new IllegalStateException(
            prefix
                + "land-writeback-total-overflow: hex="
                + hex
                + " side="
                + side
                + " owner="
                + entry.getKey(),
            e);
      }
    }
    return total;
  }

  /** 扣减已配对数量；归零时删除键。 */
  private static void reduce(TreeMap<String, Long> quantities, String owner, long amount) {
    long remaining = quantities.get(owner) - amount;
    if (remaining == 0L) {
      quantities.remove(owner);
    } else {
      quantities.put(owner, remaining);
    }
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
