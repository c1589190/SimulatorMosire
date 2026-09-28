package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>账户会话的只读快照</b>（R1 并行内核）：worker 唯一允许持有的账户视图。
 *
 * <p>★★ <b>它解决什么</b>：并行 worker 需要读余额来算意向，但<b>绝不能拿到</b> {@link AccountSession} 的活表
 * （活表只有一个协调器写者）。本类是那份活表的<b>不可变投影</b>：四张表（商品/货币 × 余额/冻结）在构造时逐值拷贝、 按 canonical key
 * 排序后冻结，之后任何线程读它都不会看到写。
 *
 * <p>★ <b>放行的语义</b>：快照是"本阶段开始那一刻"的余额，不随后续提交变化。worker 的本地增量必须自己叠加 （见 {@link
 * AccountIntentBuffer#goods}），而最终校验在提交器里对着<b>提交时的活表</b>重做 —— 两遍式的分工： 快照只服务计算，活表只服务校验与落账。
 *
 * <p>★ <b>顺序</b>：账户按 canonical key 升序、家户索引按 {@code HouseholdId.value()} 升序、经营索引按 actor canonical
 * 串升序 —— 迭代序是内容的纯函数（<b>不用</b> {@code HashMap} / {@code Map.copyOf}）。
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
  private final Map<ActorRef, AccountPartitionKey> operatorIndex;

  private AccountSnapshot(
      Map<AccountPartitionKey, SnapshotAccount> accounts,
      Map<HouseholdId, AccountPartitionKey> householdIndex,
      Map<ActorRef, AccountPartitionKey> operatorIndex) {
    // ★ 按 canonical key 排序后冻结：迭代序 = 内容的纯函数（1/4/8 线程同序）。
    List<Map.Entry<AccountPartitionKey, SnapshotAccount>> accountEntries =
        new ArrayList<>(Objects.requireNonNull(accounts, "accounts").entrySet());
    accountEntries.sort(Comparator.comparing(entry -> entry.getKey().canonical()));
    Map<AccountPartitionKey, SnapshotAccount> frozenAccounts = new LinkedHashMap<>();
    for (Map.Entry<AccountPartitionKey, SnapshotAccount> entry : accountEntries) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("AccountSnapshot.accounts 的键与值都不得为 null");
      }
      frozenAccounts.put(entry.getKey(), entry.getValue());
    }
    this.accounts = Collections.unmodifiableMap(frozenAccounts);

    List<Map.Entry<HouseholdId, AccountPartitionKey>> householdEntries =
        new ArrayList<>(Objects.requireNonNull(householdIndex, "householdIndex").entrySet());
    householdEntries.sort(Comparator.comparing(entry -> entry.getKey().value()));
    Map<HouseholdId, AccountPartitionKey> frozenHouseholds = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, AccountPartitionKey> entry : householdEntries) {
      frozenHouseholds.put(entry.getKey(), entry.getValue());
    }
    this.householdIndex = Collections.unmodifiableMap(frozenHouseholds);
    Map<ActorRef, AccountPartitionKey> actorToHousehold = new LinkedHashMap<>();
    for (AccountPartitionKey key : frozenHouseholds.values()) {
      AccountPartitionKey previous = actorToHousehold.putIfAbsent(key.actor(), key);
      if (previous != null && !previous.equals(key)) {
        throw new IllegalStateException("同一家户 actor 在快照里有多本账（装配错误）: " + key.actor());
      }
    }
    this.householdActorIndex = Collections.unmodifiableMap(actorToHousehold);

    List<Map.Entry<ActorRef, AccountPartitionKey>> operatorEntries =
        new ArrayList<>(Objects.requireNonNull(operatorIndex, "operatorIndex").entrySet());
    operatorEntries.sort(Comparator.comparing(entry -> entry.getKey().toString()));
    Map<ActorRef, AccountPartitionKey> frozenOperators = new LinkedHashMap<>();
    for (Map.Entry<ActorRef, AccountPartitionKey> entry : operatorEntries) {
      frozenOperators.put(entry.getKey(), entry.getValue());
    }
    this.operatorIndex = Collections.unmodifiableMap(frozenOperators);
  }

  /** 由 {@link AccountSession} 的协调器线程构造（package-private：worker 只能收到已有的快照，不能自己造一份 "半截"视图）。 */
  static AccountSnapshot of(
      Map<AccountPartitionKey, SnapshotAccount> accounts,
      Map<HouseholdId, AccountPartitionKey> householdIndex,
      Map<ActorRef, AccountPartitionKey> operatorIndex) {
    return new AccountSnapshot(accounts, householdIndex, operatorIndex);
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

  /** 取一本账；没有 ⇒ null（只服务"存在性判定"，用它算余额前必须显式 fail-closed）。 */
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

  /** ★ 家户索引的键集合（只读、按 {@code HouseholdId.value()} 升序）——R2 的账户活视图按它定迭代序。 */
  public Set<HouseholdId> householdIndexKeySet() {
    return householdIndex.keySet();
  }

  /** 经营者账户键；未登记 ⇒ null。 */
  public AccountPartitionKey operatorKey(ActorRef actor) {
    return operatorIndex.get(Objects.requireNonNull(actor, "actor"));
  }

  /** ★ 经营者索引的键集合（只读、按 actor canonical 升序）。 */
  public Set<ActorRef> operatorIndexKeySet() {
    return operatorIndex.keySet();
  }

  /**
   * 任一主体（家户 / 经营者）的账户键；没有 ⇒ null。
   *
   * <p>★ 一个 actor 同时出现在两张索引里是装配错误（家户 actor 与经营者 actor 的 kind 不同，正常不可能）⇒ 当场抛。
   */
  public AccountPartitionKey actorKeyOrNull(ActorRef actor) {
    AccountPartitionKey household = householdActorIndex.get(Objects.requireNonNull(actor, "actor"));
    AccountPartitionKey operator = operatorIndex.get(actor);
    if (household != null && operator != null && !household.equals(operator)) {
      throw new IllegalStateException("同一个 actor 同时登记了家户账与经营者账（装配错误）: " + actor);
    }
    return household != null ? household : operator;
  }

  /** 任一主体的账户键；没有 ⇒ 抛（fail-closed）。 */
  public AccountPartitionKey requireActorKey(ActorRef actor) {
    AccountPartitionKey key = actorKeyOrNull(actor);
    if (key == null) {
      throw new IllegalStateException("账户快照里没有这个主体的账（并行阶段要求全部相关主体都已登记）: " + actor);
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
