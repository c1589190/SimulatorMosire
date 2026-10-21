package io.mosire.simos.economy.land;

import io.mosire.simos.economy.api.land.LandArableSupply;
import io.mosire.simos.economy.api.land.LandFailureReason;
import io.mosire.simos.economy.api.land.LandHolding;
import io.mosire.simos.economy.api.land.LandHoldingKey;
import io.mosire.simos.economy.api.land.LandTransfer;
import io.mosire.simos.economy.api.land.LandViolationException;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;

/**
 * ★★★ <b>标量 LAND 的纯内存账本</b>（D-032 RA1a，2026-10-19）。
 *
 * <p>★★ <b>它守什么</b>：
 *
 * <ul>
 *   <li>{@code (hex, owner)} 是唯一键；同一户同一格只有一份标量数量，没有旧资产份额 id、旧块编号；
 *   <li>每个 hex 的持有合计恒等于 {@link LandArableSupply} 给出的固定可耕总量；
 *   <li>账本内无 0 数量行、无负数量、无未知 hex、无重复键；
 *   <li>全部加减用 {@link Math#addExact(long, long)} / {@link Math#subtractExact(long, long)}，溢出具名拒绝；
 *   <li>不可变：任何 {@link #apply} 都返回新账本，失败不会改动原账本，也不留下部分结果。
 * </ul>
 *
 * <p>★ <b>确定性</b>：持有按 {@link LandHoldingKey} 自然序（hex 自然序 → {@code HouseholdId.value()} 字符串序）枚举；
 * 批量转移先按 {@code (hex, fromOwner, toOwner, quantityMilliMu)} 规范排序再顺序施加，因此结果与输入集合的迭代序无关。
 *
 * <p>★★ <b>接线范围</b>：本类自身不碰 EconomyData/ChangeSet/Codec；RA1b2c1 起 S7 债务土地腿经 {@link LandLedgerState}
 * 调用本类完成标量转移，市场/生产仍由各自责任区接线。
 */
public final class LandBook {

  private static final Comparator<LandTransfer> TRANSFER_ORDER =
      Comparator.comparing(LandTransfer::hex)
          .thenComparing(transfer -> transfer.fromOwner().value())
          .thenComparing(transfer -> transfer.toOwner().value())
          .thenComparingLong(LandTransfer::quantityMilliMu);

  private final LandArableSupply arableSupply;
  private final NavigableMap<LandHoldingKey, Long> holdingsByKey;
  private final NavigableMap<HexCoord, Long> totalByHex;
  private final List<LandHolding> orderedHoldings;

  private LandBook(
      LandArableSupply arableSupply,
      NavigableMap<LandHoldingKey, Long> holdingsByKey,
      NavigableMap<HexCoord, Long> totalByHex,
      List<LandHolding> orderedHoldings) {
    this.arableSupply = arableSupply;
    this.holdingsByKey = Collections.unmodifiableNavigableMap(new TreeMap<>(holdingsByKey));
    this.totalByHex = Collections.unmodifiableNavigableMap(new TreeMap<>(totalByHex));
    this.orderedHoldings = List.copyOf(orderedHoldings);
  }

  /**
   * 从固定可耕总量与一组初始持有构造账本。
   *
   * <p>校验顺序固定：重复 {@code (hex, owner)} → 未知 hex → 每 hex 合计 == 可耕总量。任一步失败都抛 {@link
   * LandViolationException}，不返回半成品。
   *
   * @param arableSupply app 从 Map 构造的每 hex 可耕总量瞬态快照；不得为 null
   * @param initialHoldings 初始持有；不得为 null，元素不得为 null
   * @return 校验通过的不可变账本
   */
  public static LandBook from(
      LandArableSupply arableSupply, Collection<LandHolding> initialHoldings) {
    Objects.requireNonNull(arableSupply, "LandBook.from 的 arableSupply 不得为 null");
    Objects.requireNonNull(initialHoldings, "LandBook.from 的 initialHoldings 不得为 null");
    TreeMap<LandHoldingKey, Long> raw = new TreeMap<>();
    for (LandHolding holding : initialHoldings) {
      Objects.requireNonNull(holding, "LandBook.from 的 holding 不得为 null");
      LandHoldingKey key = holding.key();
      if (raw.containsKey(key)) {
        throw new LandViolationException(
            LandFailureReason.LAND_DUPLICATE_HOLDING,
            "重复持有 hex=" + key.hex() + " owner=" + key.owner().value());
      }
      raw.put(key, holding.quantityMilliMu());
    }
    return validated(arableSupply, raw);
  }

  /** 空账本；只有当供应表中所有可耕总量都为 0 时才合法。 */
  public static LandBook empty(LandArableSupply arableSupply) {
    return from(arableSupply, List.of());
  }

  /** 每 hex 固定可耕总量（独立权威，不从持有反推）。 */
  public LandArableSupply arableSupply() {
    return arableSupply;
  }

  /** 全部持有的稳定排序视图（不可变）。 */
  public List<LandHolding> holdings() {
    return orderedHoldings;
  }

  /** 全部 hex 的稳定排序集合（来自供应表，可能包含本账本上没有持有人的 0 可耕 hex）。 */
  public Set<HexCoord> hexes() {
    return arableSupply.arableMilliMuByHex().keySet();
  }

  /** 某户在某 hex 的持有量；该 hex 已知但没有该户时返回 empty，未知 hex 具名拒绝。 */
  public OptionalLong holdingOf(HexCoord hex, HouseholdId owner) {
    requireKnownHex(hex);
    Objects.requireNonNull(owner, "LandBook.holdingOf 的 owner 不得为 null");
    Long quantity = holdingsByKey.get(new LandHoldingKey(hex, owner));
    return quantity == null ? OptionalLong.empty() : OptionalLong.of(quantity);
  }

  /** 某 hex 的全部持有人，按 {@link LandHoldingKey} 自然序；未知 hex 具名拒绝。 */
  public List<LandHolding> holdingsOfHex(HexCoord hex) {
    requireKnownHex(hex);
    return orderedHoldings.stream().filter(holding -> holding.hex().equals(hex)).toList();
  }

  /** 某 hex 的持有总量；未知 hex 具名拒绝。 */
  public long totalOfHex(HexCoord hex) {
    requireKnownHex(hex);
    return totalByHex.getOrDefault(hex, 0L);
  }

  /** 某 hex 的固定可耕总量；未知 hex 具名拒绝。 */
  public long arableOfHex(HexCoord hex) {
    requireKnownHex(hex);
    return arableSupply.arableMilliMuByHex().get(hex);
  }

  /** 施加一笔任意数量、同 hex 的转移，返回新账本；原账本不变。 */
  public LandBook apply(LandTransfer transfer) {
    Objects.requireNonNull(transfer, "LandBook.apply 的 transfer 不得为 null");
    requireKnownHex(transfer.hex());

    LandHoldingKey fromKey = new LandHoldingKey(transfer.hex(), transfer.fromOwner());
    Long fromQuantity = holdingsByKey.get(fromKey);
    if (fromQuantity == null || fromQuantity < transfer.quantityMilliMu()) {
      throw new LandViolationException(
          LandFailureReason.LAND_INSUFFICIENT_HOLDING,
          "源家户在 hex="
              + transfer.hex()
              + " 的持有不足: 需要 "
              + transfer.quantityMilliMu()
              + "，现有 "
              + (fromQuantity == null ? 0L : fromQuantity));
    }

    long remainingFrom = subtractExact(fromQuantity, transfer.quantityMilliMu(), "转出后余额");
    LandHoldingKey toKey = new LandHoldingKey(transfer.hex(), transfer.toOwner());
    long existingTo = holdingsByKey.getOrDefault(toKey, 0L);
    long updatedTo = addExact(existingTo, transfer.quantityMilliMu(), "转入后数量");

    TreeMap<LandHoldingKey, Long> next = new TreeMap<>(holdingsByKey);
    if (remainingFrom == 0L) {
      next.remove(fromKey);
    } else {
      next.put(fromKey, remainingFrom);
    }
    next.put(toKey, updatedTo);
    return validated(arableSupply, next);
  }

  /**
   * 施加一批转移，返回最终新账本；任一笔失败则整批不产生新账本（本对象与中间账本都不可见）。
   *
   * <p>批次先按 {@link LandTransfer} 的规范序排序，因此结果与输入集合类型/迭代序无关。
   */
  public LandBook applyAll(Collection<LandTransfer> transfers) {
    Objects.requireNonNull(transfers, "LandBook.applyAll 的 transfers 不得为 null");
    List<LandTransfer> ordered = new ArrayList<>(transfers.size());
    for (LandTransfer transfer : transfers) {
      ordered.add(Objects.requireNonNull(transfer, "LandBook.applyAll 的 transfer 不得为 null"));
    }
    ordered.sort(TRANSFER_ORDER);
    LandBook current = this;
    for (LandTransfer transfer : ordered) {
      current = current.apply(transfer);
    }
    return current;
  }

  private static LandBook validated(
      LandArableSupply arableSupply, NavigableMap<LandHoldingKey, Long> rawHoldings) {
    TreeMap<HexCoord, Long> totals = new TreeMap<>();
    List<LandHolding> ordered = new ArrayList<>(rawHoldings.size());
    for (var entry : rawHoldings.entrySet()) {
      LandHoldingKey key = entry.getKey();
      Long quantity = entry.getValue();
      if (quantity == null || quantity <= 0L) {
        throw new LandViolationException(
            quantity != null && quantity < 0L
                ? LandFailureReason.LAND_NEGATIVE_QUANTITY
                : LandFailureReason.LAND_ZERO_QUANTITY,
            "账本持有量必须 > 0: hex=" + key.hex() + " owner=" + key.owner().value() + " -> " + quantity);
      }
      if (!arableSupply.containsHex(key.hex())) {
        throw new LandViolationException(
            LandFailureReason.LAND_UNKNOWN_HEX,
            "持有涉及供应表未知的 hex=" + key.hex() + " owner=" + key.owner().value());
      }
      long total = addExact(totals.getOrDefault(key.hex(), 0L), quantity, "hex 持有合计");
      totals.put(key.hex(), total);
      ordered.add(new LandHolding(key.owner(), key.hex(), quantity));
    }
    for (var entry : arableSupply.arableMilliMuByHex().entrySet()) {
      long actual = totals.getOrDefault(entry.getKey(), 0L);
      if (actual != entry.getValue()) {
        throw new LandViolationException(
            LandFailureReason.LAND_ARABLE_MISMATCH,
            "hex=" + entry.getKey() + " 持有合计 " + actual + " != 固定可耕总量 " + entry.getValue());
      }
    }
    return new LandBook(arableSupply, rawHoldings, totals, ordered);
  }

  private void requireKnownHex(HexCoord hex) {
    Objects.requireNonNull(hex, "LandBook 的 hex 不得为 null");
    if (!arableSupply.containsHex(hex)) {
      throw new LandViolationException(LandFailureReason.LAND_UNKNOWN_HEX, "未知 hex=" + hex);
    }
  }

  private static long addExact(long left, long right, String context) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException overflow) {
      throw new LandViolationException(
          LandFailureReason.LAND_OVERFLOW, context + " 溢出: " + left + " + " + right);
    }
  }

  private static long subtractExact(long left, long right, String context) {
    try {
      return Math.subtractExact(left, right);
    } catch (ArithmeticException overflow) {
      throw new LandViolationException(
          LandFailureReason.LAND_OVERFLOW, context + " 溢出: " + left + " - " + right);
    }
  }
}
