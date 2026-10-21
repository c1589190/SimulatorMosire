package io.mosire.simos.economy.land;

import io.mosire.simos.economy.api.land.LandArableSupply;
import io.mosire.simos.economy.api.land.LandHolding;
import io.mosire.simos.economy.api.land.LandHoldingKey;
import io.mosire.simos.economy.api.land.LandTransfer;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * ★★★ <b>economy 会话内的标量 LAND 工作态</b>（D-032 RA1b2a，2026-10-19）。
 *
 * <p>★★ <b>它是什么</b>：把 app 注入的瞬态 arable 快照（{@link LandArableSupply}）与持久 {@code landHoldings}
 * 装成一份不可变的状态视图，并提供三件 S1/S2/S7 共用的纯操作：
 *
 * <ul>
 *   <li>{@link #from(LandArableSupply, Map)}：从瞬态 arable 快照 + 持有表构造；
 *   <li>{@link #usableQuantity(HexCoord, HouseholdId, Map)}：持有量减去本轮已取得量（部分取得按数量扣，不按整 key 排除）；
 *   <li>{@link #transfer(LandTransfer)}：返回转移后的新状态，校验与每 hex 守恒仍只走 {@link LandBook}。
 * </ul>
 *
 * <p>★★ <b>不是第二份权威</b>：本类不持有可写状态，也不定义新的守恒公式；{@link LandBook} 是唯一校验/转移实现。 arable 总量来自 Map
 * 快照，持有表仍是本批唯一持久 LAND 所有权数量表。
 *
 * <p>★ <b>接线范围（RA1b2c1）</b>：S7 债务清算的土地腿在本类上逐笔 {@link #transfer(LandTransfer)}，再写回会话 {@code
 * landHoldings} 工作副本；持久写仍必须经 {@code EconomyData} 的构造器与原子写口。
 */
public final class LandLedgerState {

  private final LandBook book;
  private final Map<LandHoldingKey, LandHolding> holdingsByKey;

  private LandLedgerState(LandBook book) {
    this.book = Objects.requireNonNull(book, "book");
    TreeMap<LandHoldingKey, LandHolding> sorted = new TreeMap<>();
    for (LandHolding holding : book.holdings()) {
      sorted.put(holding.key(), holding);
    }
    this.holdingsByKey = Collections.unmodifiableNavigableMap(sorted);
  }

  /**
   * 从固定可耕总量与持有表构造。
   *
   * <p>★ 键必须与值内 {@link LandHolding#key()} 一致；重复键、未知 hex、非正数量与每 hex 总量不等都由 {@link LandBook#from}
   * 具名拒绝。
   */
  public static LandLedgerState from(
      LandArableSupply arableSupply, Map<LandHoldingKey, LandHolding> landHoldings) {
    Objects.requireNonNull(arableSupply, "LandLedgerState.from 的 arableSupply 不得为 null");
    Objects.requireNonNull(landHoldings, "LandLedgerState.from 的 landHoldings 不得为 null");
    TreeMap<LandHoldingKey, LandHolding> sorted = new TreeMap<>();
    for (Map.Entry<LandHoldingKey, LandHolding> entry : landHoldings.entrySet()) {
      LandHoldingKey key = Objects.requireNonNull(entry.getKey(), "landHoldings 的键不得为 null");
      LandHolding holding = Objects.requireNonNull(entry.getValue(), "landHoldings 的值不得为 null");
      if (!key.equals(holding.key())) {
        throw new IllegalArgumentException(
            "landHoldings 的键必须与 LandHolding.key() 一致：键=" + key + "，行内 key=" + holding.key());
      }
      sorted.put(key, holding);
    }
    return new LandLedgerState(LandBook.from(arableSupply, sorted.values()));
  }

  /** app 从 Map 构造的每 hex 可耕总量瞬态快照（不从持有反推）。 */
  public LandArableSupply arableSupply() {
    return book.arableSupply();
  }

  /** 某户在某 hex 的持有量；该 hex 已知但无持有时返回 0（未知 hex 由账本具名拒绝）。 */
  public long holdingOf(HexCoord hex, HouseholdId owner) {
    return book.holdingOf(hex, owner).orElse(0L);
  }

  /**
   * ★★ <b>本轮可用量</b>：{@code max(0, holdingOf - acquiredThisRound.getOrDefault(key, 0))}。
   *
   * <p>★ 部分取得按数量扣：本轮买/抵债取得 30 亩而原持有 100 亩 ⇒ 可用 70 亩；不是"整 key 排除"。 取得量必须为非负；负数视为调用方契约故障。
   *
   * @param acquiredThisRound 本轮取得数量表（键 = {@code (hex, owner)}）；{@code null} 视为没有取得
   */
  public long usableQuantity(
      HexCoord hex, HouseholdId owner, Map<LandHoldingKey, Long> acquiredThisRound) {
    Objects.requireNonNull(hex, "LandLedgerState.usableQuantity 的 hex 不得为 null");
    Objects.requireNonNull(owner, "LandLedgerState.usableQuantity 的 owner 不得为 null");
    long holding = holdingOf(hex, owner);
    if (acquiredThisRound == null || acquiredThisRound.isEmpty()) {
      return holding;
    }
    Long acquiredValue = acquiredThisRound.get(new LandHoldingKey(hex, owner));
    if (acquiredValue == null) {
      return holding;
    }
    long acquired = acquiredValue;
    if (acquired < 0L) {
      throw new IllegalArgumentException(
          "acquiredThisRound 的数量不得为负: key=" + new LandHoldingKey(hex, owner) + " -> " + acquired);
    }
    return acquired >= holding ? 0L : holding - acquired;
  }

  /** 施加一笔同 hex、任意数量的转移，返回新状态；原状态不变。 */
  public LandLedgerState transfer(LandTransfer transfer) {
    return new LandLedgerState(book.apply(transfer));
  }

  /** 全部持有的稳定 key→holding 视图（不可变、按 {@link LandHoldingKey} 自然序，无 0 行）。 */
  public Map<LandHoldingKey, LandHolding> holdingsByKey() {
    return holdingsByKey;
  }

  /** 底层纯账本（只读；供未来阶段共用同一份校验/查询实现）。 */
  public LandBook landBook() {
    return book;
  }
}
