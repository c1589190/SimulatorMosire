package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>账户会话的只读快照</b>（R1 并行内核；P2-A §13.3 起账户主体统一为家户）。
 *
 * <p>worker 唯一允许持有的账户视图；四张表（商品/货币 × 余额/冻结）在构造时逐值拷贝、按 canonical key
 * 排序后冻结，之后任何线程读它都不会看到写。
 *
 * <p>★ <b>放行的语义</b>：快照是"本阶段开始那一刻"的余额，不随后续提交变化。worker 的本地增量必须自己叠加
 * （见 {@link AccountIntentBuffer#goods}），而最终校验在提交器里对着<b>提交时的活表</b>重做。
 *
 * <p>★ <b>顺序</b>：账户按家户 id 升序、位置索引按家户 id 升序 —— 迭代序是内容的纯函数。
 */
public final class AccountSnapshot {

  /** 一本账的不可变投影（四张保序表）。 */
  public record SnapshotAccount(
      Map<CommodityId, Long> goods,
      Map<CurrencyId, Long> money,
      Map<CommodityId, Long> frozenGoods,
      Map<CurrencyId, Long> frozenMoney) {

    public SnapshotAccount {
      goods = freezeGoods(goods, "SnapshotAccount.goods");
      money = freezeMoney(money, "SnapshotAccount.money");
      frozenGoods = freezeGoods(frozenGoods, "SnapshotAccount.frozenGoods");
      frozenMoney = freezeMoney(frozenMoney, "SnapshotAccount.frozenMoney");
    }
  }

  private final Map<AccountPartitionKey, SnapshotAccount> accounts;
  private final Map<HouseholdId, AccountPartitionKey> householdIndex;
  private final Map<ActorRef, AccountPartitionKey> householdActorIndex;
  /** 家户登记位置（分区与转移 location 的派生读口；<b>不是</b>账户身份）。 */
  private final Map<HouseholdId, HexCoord> locations;

  private AccountSnapshot(
      Map<AccountPartitionKey, SnapshotAccount> accounts,
      Map<HouseholdId, HexCoord> locations) {
    List<Map.Entry<AccountPartitionKey, SnapshotAccount>> accountEntries =
        new ArrayList<>(Objects.requireNonNull(accounts, "accounts").entrySet());
    accountEntries.sort(Comparator.comparing(entry -> entry.getKey().canonical()));
    Map<AccountPartitionKey, SnapshotAccount> frozenAccounts = new LinkedHashMap<>();
    Map<HouseholdId, AccountPartitionKey> households = new LinkedHashMap<>();
    for (Map.Entry<AccountPartitionKey, SnapshotAccount> entry : accountEntries) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("AccountSnapshot.accounts 的键与值都不得为 null");
      }
      frozenAccounts.put(entry.getKey(), entry.getValue());
      households.put(entry.getKey().household(), entry.getKey());
    }
    this.accounts = Collections.unmodifiableMap(frozenAccounts);
    this.householdIndex = Collections.unmodifiableMap(households);

    Map<ActorRef, AccountPartitionKey> actorToHousehold = new LinkedHashMap<>();
    for (AccountPartitionKey key : households.values()) {
      ActorRef actor = HouseholdActors.of(key.household());
      AccountPartitionKey previous = actorToHousehold.putIfAbsent(actor, key);
      if (previous != null && !previous.equals(key)) {
        throw new IllegalStateException("同一家户 actor 在快照里有多本账（装配错误）: " + actor);
      }
    }
    this.householdActorIndex = Collections.unmodifiableMap(actorToHousehold);

    List<Map.Entry<HouseholdId, HexCoord>> locationEntries =
        new ArrayList<>(Objects.requireNonNull(locations, "locations").entrySet());
    locationEntries.sort(Comparator.comparing(entry -> entry.getKey().value()));
    Map<HouseholdId, HexCoord> frozenLocations = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, HexCoord> entry : locationEntries) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("AccountSnapshot.locations 的键与值都不得为 null");
      }
      frozenLocations.put(entry.getKey(), entry.getValue());
    }
    this.locations = Collections.unmodifiableMap(frozenLocations);
  }

  /** 由 {@link AccountSession} 的协调器线程构造（package-private：worker 只能收到已有的快照）。 */
  static AccountSnapshot of(
      Map<AccountPartitionKey, SnapshotAccount> accounts, Map<HouseholdId, HexCoord> locations) {
    return new AccountSnapshot(accounts, locations);
  }

  /** 全部账户键（只读、canonical 升序）。 */
  public Set<AccountPartitionKey> accountKeys() {
    return accounts.keySet();
  }

  /** 取一本账；没有 ⇒ 抛（"没有这本账"与"余额为 0"是两件事，禁止静默当 0）。 */
  public SnapshotAccount requireAccount(AccountPartitionKey key) {
    SnapshotAccount account = accounts.get(Objects.requireNonNull(key, "key"));
    if (account == null) {
      throw new IllegalStateException("账户快照里没有这本账（禁止把缺账当余额 0）: " + key.canonical());
    }
    return account;
  }

  /** 取一本账；没有 ⇒ null。 */
  public SnapshotAccount accountOrNull(AccountPartitionKey key) {
    return accounts.get(Objects.requireNonNull(key, "key"));
  }

  public long goods(AccountPartitionKey key, CommodityId commodity) {
    return requireAccount(key).goods().getOrDefault(commodity, 0L);
  }

  public long money(AccountPartitionKey key, CurrencyId currency) {
    return requireAccount(key).money().getOrDefault(currency, 0L);
  }

  public long frozenGoods(AccountPartitionKey key, CommodityId commodity) {
    return requireAccount(key).frozenGoods().getOrDefault(commodity, 0L);
  }

  public long frozenMoney(AccountPartitionKey key, CurrencyId currency) {
    return requireAccount(key).frozenMoney().getOrDefault(currency, 0L);
  }

  /** 家户账户键；未登记 ⇒ null（调用方按 fail-closed 处理）。 */
  public AccountPartitionKey householdKey(HouseholdId household) {
    return householdIndex.get(Objects.requireNonNull(household, "household"));
  }

  /** ★ 家户索引的键集合（只读、按 {@code HouseholdId.value()} 升序）。 */
  public Set<HouseholdId> householdIndexKeySet() {
    return householdIndex.keySet();
  }

  /** 家户登记位置（分区/转移 location 读口）；未登记 ⇒ null。 */
  public HexCoord locationOf(HouseholdId household) {
    return locations.get(Objects.requireNonNull(household, "household"));
  }

  /**
   * 家户 actor → 账户键；非 {@code HOUSEHOLD} actor / 未登记 ⇒ null。
   *
   * <p>★★ P2-A：账户主体只有家户 —— 非家户主体一律 null（由调用方具名拒绝或具名缺口，绝不静默造账）。
   */
  public AccountPartitionKey actorKeyOrNull(ActorRef actor) {
    Objects.requireNonNull(actor, "actor");
    if (actor.kind() != ActorKind.HOUSEHOLD) {
      return null;
    }
    HouseholdId household;
    try {
      household = HouseholdActors.householdOf(actor);
    } catch (RuntimeException notAHouseholdActor) {
      return null;
    }
    return householdIndex.get(household);
  }

  /** 家户 actor 的账户键；没有 ⇒ 抛（fail-closed）。 */
  public AccountPartitionKey requireActorKey(ActorRef actor) {
    AccountPartitionKey key = actorKeyOrNull(actor);
    if (key == null) {
      throw new IllegalStateException(
          "账户快照里没有这个家户主体的账（账户主体只有家户；非家户 actor 必须先在结算侧解析到家户）: " + actor);
    }
    return key;
  }

  private static Map<CommodityId, Long> freezeGoods(Map<CommodityId, Long> source, String field) {
    if (source == null) {
      throw new IllegalArgumentException(field + " 不得为 null（没有那一腿请给空表）");
    }
    List<Map.Entry<CommodityId, Long>> entries = new ArrayList<>(source.entrySet());
    entries.sort(Comparator.comparing(entry -> entry.getKey().value()));
    Map<CommodityId, Long> frozen = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : entries) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(field + " 的键与值都不得为 null: " + entry.getKey());
      }
      frozen.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(frozen);
  }

  private static Map<CurrencyId, Long> freezeMoney(Map<CurrencyId, Long> source, String field) {
    if (source == null) {
      throw new IllegalArgumentException(field + " 不得为 null（没有那一腿请给空表）");
    }
    List<Map.Entry<CurrencyId, Long>> entries = new ArrayList<>(source.entrySet());
    entries.sort(Comparator.comparing(entry -> entry.getKey().value()));
    Map<CurrencyId, Long> frozen = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> entry : entries) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(field + " 的键与值都不得为 null: " + entry.getKey());
      }
      frozen.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(frozen);
  }
}
