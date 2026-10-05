package io.mosire.simos.economy.time;

import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Objects;

/**
 * ★★ <b>账户分区键 = 家户身份</b>（P2-A §13.3：账户主体统一为家户，一本账一个家户）。
 *
 * <p>★★ <b>形状变化（如实记）</b>：改前是 {@code (ActorRef, HexCoord)} —— 同一主体可在多格各有一本账，
 * {@code ESTATE}/{@code WORKSHOP} 之类非家户主体也持账。现在：
 *
 * <ul>
 *   <li><b>唯一主体 = 家户</b>：键就是 {@link HouseholdId}；actor 只是家户身份的读法（{@code HouseholdActors.of}），
 *       不再参与账户身份；
 *   <li><b>没有 {@code HexCoord}</b>：位置由 {@code ClassRow.view().hex()} / 家户登记位置派生（会话内部只为
 *       分区与转移 location 保留一张“家户 → 格”索引，<b>不是</b>账户身份）。
 * </ul>
 *
 * <p>★ <b>规范串 = 家户 id 本身</b>（{@link HouseholdId#value()}）；{@link #parseCanonical(String)} 是它的逆。提交序
 * {@link CommitOrder#canonicalKey()} 只带这个串 —— 与 {@code HouseholdAccountKey.toString()} 同源。
 *
 * <p>★ <b>确定性分区函数</b>：{@code floorMod(canonical.hashCode(), partitionCount)}（1/4/8 线程同一函数）。
 */
public record AccountPartitionKey(HouseholdId household) implements Comparable<AccountPartitionKey> {

  public AccountPartitionKey {
    Objects.requireNonNull(household, "AccountPartitionKey.household 不得为 null");
  }

  /** 规范串 = 家户 id（账户键的唯一读法）。 */
  public String canonical() {
    return household.value();
  }

  @Override
  public String toString() {
    return canonical();
  }

  /** canonical 字典序：分区内序、求和序、tie-break 的唯一序。 */
  @Override
  public int compareTo(AccountPartitionKey other) {
    Objects.requireNonNull(other, "other");
    return canonical().compareTo(other.canonical());
  }

  /** ★★ <b>确定性分区函数</b>：{@code floorMod(canonical.hashCode(), partitionCount)}。 */
  public int partitionIndex(int partitionCount) {
    return partitionIndexOf(canonical(), partitionCount);
  }

  /** 规范串版分区函数（{@link PartitionPlan} 对 hex/区键复用它，保证 1/4/8 线程同一函数）。 */
  public static int partitionIndexOf(String canonical, int partitionCount) {
    Objects.requireNonNull(canonical, "canonical");
    if (canonical.isBlank()) {
      throw new IllegalArgumentException("分区键的规范串不得为空白");
    }
    if (partitionCount < 1) {
      throw new IllegalArgumentException("分区数必须 ≥ 1: " + partitionCount);
    }
    return Math.floorMod(canonical.hashCode(), partitionCount);
  }

  /** {@link #canonical()} 的逆（提交序只带规范串、提交时还原账户键这一条路）。 */
  public static AccountPartitionKey parseCanonical(String canonical) {
    return new AccountPartitionKey(HouseholdId.parse(canonical));
  }
}
