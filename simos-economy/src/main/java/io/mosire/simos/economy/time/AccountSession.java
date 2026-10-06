package io.mosire.simos.economy.time;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * ★★ <b>结算会话的唯一账户表</b>（S1 / P1.4-P1.5 / R1 线程安全内核；P2-A §13.3 起账户主体统一为家户）。
 *
 * <p>★★ <b>形状变化（如实记）</b>：改前是按 {@link AccountPartitionKey}（{@code (ActorRef, HexCoord)}）索引的 "家户账 +
 * 经营者账"双轨表 —— 庄园/作坊/商号的 operator 也各持一本会话账。现在：
 *
 * <ul>
 *   <li><b>唯一主体 = 家户</b>：键 = {@link HouseholdId}（{@link AccountPartitionKey} 就是它）；
 *   <li><b>没有 operator 索引/视图</b>：一切账户读写都走家户地图；组织角色（庄园/作坊/商号）必须先由调用方 解析到组织者/经营者家户，解析不到 ⇒
 *       具名拒绝（本类不提供"静默跳过"的旁路）；
 *   <li><b>位置不参与身份</b>：{@code registerHousehold} 收一份登记位置，只服务分区与转移 location 的核对。
 * </ul>
 *
 * <p>★ <b>R1 的线程安全分工</b>：活表只有协调器线程可读写；worker 只拿 {@link AccountSnapshot} 与本地 {@link
 * AccountIntentBuffer}；唯一写口是 {@link #commit(Collection)}。
 */
public final class AccountSession {

  /** 一本账的四张活表（商品/货币 × 余额/冻结）；活表本体只有本类与 {@link AccountSession#commit} 能写。 */
  public static final class ActorAccount {

    private final AccountPartitionKey key;
    private final LinkedHashMap<CommodityId, Long> goods = new LinkedHashMap<>();
    private final LinkedHashMap<CurrencyId, Long> money = new LinkedHashMap<>();
    private final LinkedHashMap<CommodityId, Long> frozenGoods = new LinkedHashMap<>();
    private final LinkedHashMap<CurrencyId, Long> frozenMoney = new LinkedHashMap<>();

    /** ★★ M1：四张活表的只读视图（活 = 随账本更新；写 = 当场抛）。 */
    private final Map<CommodityId, Long> goodsView = Collections.unmodifiableMap(goods);

    private final Map<CurrencyId, Long> moneyView = Collections.unmodifiableMap(money);
    private final Map<CommodityId, Long> frozenGoodsView = Collections.unmodifiableMap(frozenGoods);
    private final Map<CurrencyId, Long> frozenMoneyView = Collections.unmodifiableMap(frozenMoney);

    private ActorAccount(AccountPartitionKey key) {
      this.key = Objects.requireNonNull(key, "key");
    }

    public AccountPartitionKey key() {
      return key;
    }

    /** 家户身份（账户主体）。 */
    public HouseholdId household() {
      return key.household();
    }

    public Map<CommodityId, Long> goods() {
      return goodsView;
    }

    public Map<CurrencyId, Long> money() {
      return moneyView;
    }

    public Map<CommodityId, Long> frozenGoods() {
      return frozenGoodsView;
    }

    public Map<CurrencyId, Long> frozenMoney() {
      return frozenMoneyView;
    }

    void replaceGoods(Map<CommodityId, Long> value) {
      goods.clear();
      goods.putAll(value);
    }

    void replaceMoney(Map<CurrencyId, Long> value) {
      money.clear();
      money.putAll(value);
    }

    void replaceFrozenGoods(Map<CommodityId, Long> value) {
      frozenGoods.clear();
      frozenGoods.putAll(value);
    }

    void replaceFrozenMoney(Map<CurrencyId, Long> value) {
      frozenMoney.clear();
      frozenMoney.putAll(value);
    }
  }

  /** 唯一存储：家户 → 账（只准协调器线程访问）。 */
  private final LinkedHashMap<AccountPartitionKey, ActorAccount> accounts = new LinkedHashMap<>();

  /** 家户视图索引（家户 id → 账户键）；键与 {@link #accounts} 的键一一对应。 */
  private final LinkedHashMap<HouseholdId, AccountPartitionKey> householdIndex =
      new LinkedHashMap<>();

  /** 家户登记位置（分区/转移 location 读口；<b>不是</b>账户身份）。 */
  private final LinkedHashMap<HouseholdId, HexCoord> householdLocations = new LinkedHashMap<>();

  private final Thread coordinatorThread;

  private AccountSession() {
    this.coordinatorThread = Thread.currentThread();
  }

  public static AccountSession empty() {
    return new AccountSession();
  }

  /** ★ owner 线程守卫：活表 / 索引 / 注册 / 落回 / 提交只允许创建本会话的线程访问。 */
  public void checkCoordinatorThread() {
    if (Thread.currentThread() != coordinatorThread) {
      throw new IllegalStateException(
          "账户会话的活表只允许协调器线程（"
              + coordinatorThread.getName()
              + "）访问；当前线程="
              + Thread.currentThread().getName()
              + "。并行 worker 必须走 AccountSnapshot + AccountIntentBuffer，不得直接读写共享账户 Map");
    }
  }

  /** 取（必要时建）一本家户账；本方法只服务"该家户应有账"的路径，不会静默返回 null。 */
  public ActorAccount account(HouseholdId household) {
    checkCoordinatorThread();
    AccountPartitionKey key =
        new AccountPartitionKey(Objects.requireNonNull(household, "household"));
    return accounts.computeIfAbsent(key, ActorAccount::new);
  }

  /** 取一本家户账；没有 ⇒ null（查询口径；只准协调器线程）。 */
  public ActorAccount accountOrNull(HouseholdId household) {
    checkCoordinatorThread();
    return accounts.get(new AccountPartitionKey(Objects.requireNonNull(household, "household")));
  }

  /** 全部账（**活视图**，只读外层；顺序 = 插入序；只准协调器线程）。 */
  public Map<AccountPartitionKey, ActorAccount> accounts() {
    checkCoordinatorThread();
    return Collections.unmodifiableMap(accounts);
  }

  /** ★★ worker 的只读快照：协调器在阶段开始前取一次，交给并行 worker。 */
  public AccountSnapshot snapshot() {
    checkCoordinatorThread();
    Map<AccountPartitionKey, AccountSnapshot.SnapshotAccount> snapshotAccounts =
        new LinkedHashMap<>();
    List<Map.Entry<AccountPartitionKey, ActorAccount>> entries =
        new ArrayList<>(accounts.entrySet());
    entries.sort(Map.Entry.comparingByKey());
    for (Map.Entry<AccountPartitionKey, ActorAccount> entry : entries) {
      ActorAccount account = entry.getValue();
      snapshotAccounts.put(
          entry.getKey(),
          new AccountSnapshot.SnapshotAccount(
              account.goods, account.money, account.frozenGoods, account.frozenMoney));
    }
    return AccountSnapshot.of(snapshotAccounts, new LinkedHashMap<>(householdLocations));
  }

  /**
   * 登记家户账（账户 + 位置 + 四张表）；同一家户重复登记同键 ⇒ 幂等，不同内容 ⇒ 覆盖（载入是一次性的）。
   *
   * @param location 家户登记位置（只服务分区与转移 location 核对；账户身份与它无关）
   */
  public void registerHousehold(
      HouseholdId household,
      HexCoord location,
      Map<CommodityId, Long> goods,
      Map<CurrencyId, Long> money,
      Map<CommodityId, Long> frozenGoods,
      Map<CurrencyId, Long> frozenMoney) {
    checkCoordinatorThread();
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(location, "location");
    AccountPartitionKey key = new AccountPartitionKey(household);
    AccountPartitionKey previous = householdIndex.putIfAbsent(household, key);
    if (previous != null && !previous.equals(key)) {
      throw new IllegalStateException("家户 " + household + " 已有账户键 " + previous + "，不能改登记为 " + key);
    }
    HexCoord previousLocation = householdLocations.putIfAbsent(household, location);
    if (previousLocation != null && !previousLocation.equals(location)) {
      throw new IllegalStateException(
          "家户 " + household + " 已登记位置 " + previousLocation + "，不能改登记为 " + location);
    }
    ActorAccount account = accounts.computeIfAbsent(key, ActorAccount::new);
    account.replaceGoods(goods == null ? Map.of() : goods);
    account.replaceMoney(money == null ? Map.of() : money);
    account.replaceFrozenGoods(frozenGoods == null ? Map.of() : frozenGoods);
    account.replaceFrozenMoney(frozenMoney == null ? Map.of() : frozenMoney);
  }

  /** 家户账户键（载入时登记过才有）。 */
  public AccountPartitionKey householdKeyOf(HouseholdId household) {
    checkCoordinatorThread();
    return householdIndex.get(Objects.requireNonNull(household, "household"));
  }

  /** 家户登记位置（载入时登记过才有）。 */
  public HexCoord householdLocationOf(HouseholdId household) {
    checkCoordinatorThread();
    return householdLocations.get(Objects.requireNonNull(household, "household"));
  }

  /**
   * 家户 actor → 账户键；非 {@code HOUSEHOLD} actor / 未登记 ⇒ null。
   *
   * <p>★★ 非家户主体不再持账（P2-A §13.3）：本方法对它们一律 null，绝不静默造一本"永不命中"的占位账。
   */
  public AccountPartitionKey actorKeyOrNull(ActorRef actor) {
    checkCoordinatorThread();
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

  /** 家户账户（载入时登记过才有）。 */
  public ActorAccount householdAccount(HouseholdId household) {
    checkCoordinatorThread();
    AccountPartitionKey key = householdIndex.get(Objects.requireNonNull(household, "household"));
    return key == null ? null : accounts.get(key);
  }

  // ── 单线程稳定提交（R1 的核心）────────────────────────────────────────────────────────

  /**
   * ★★ <b>稳定提交一批意向</b>（只允许协调器线程；唯一落账入口）。
   *
   * <pre>
   * 1. SettlementExecutor.commit 按 (stage, partitionIndex, canonicalKey, intraIndex) 排序；
   * 2. TransferIntent → EconomySettlement.applyTransfer（两遍式唯一写口，两端必须是已登记家户）；
   * 3. AccountDelta    → 逐腿校验"余额不成为负、扣完不低于冻结"后并入活表；
   * 4. FreezeIntent   → 校验 0 ≤ amount ≤ 余额 后置冻结额。
   * </pre>
   *
   * <p>★ 任一条校验失败 ⇒ 抛出（整批推进失败；调用方按既有语义丢弃本批），不做"跳过失败条"的静默兜底。
   */
  public void commit(Collection<? extends OrderedAccountIntent> intents) {
    checkCoordinatorThread();
    Objects.requireNonNull(intents, "intents");
    Map<ActorRef, HouseholdId> householdOfActor = householdOfActor();
    SettlementExecutor.commit(
        intents,
        new SettlementExecutor.IntentSink() {
          @Override
          public void applyTransfer(TransferIntent intent) {
            Transfer transfer = intent.transfer();
            boolean fromKnown = actorKeyOrNull(transfer.from()) != null;
            boolean toKnown = actorKeyOrNull(transfer.to()) != null;
            if (!fromKnown || !toKnown) {
              throw new IllegalStateException("账户主体只有家户：转移两端必须先解析到已登记家户账（不得静默跳过）：转移=" + transfer);
            }
            EconomySettlement.applyTransfer(
                householdGoods(),
                householdMoney(),
                householdFrozenGoods(),
                householdFrozenMoney(),
                householdOfActor,
                transfer);
          }

          @Override
          public void applyDelta(AccountDelta delta) {
            ActorAccount account = requireLiveAccount(delta.accountKey());
            for (Map.Entry<CommodityId, Long> leg : delta.goods().entrySet()) {
              long before = account.goods().getOrDefault(leg.getKey(), 0L);
              long after = addExact(before, leg.getValue(), account.key(), "商品", leg.getKey());
              if (after < 0L) {
                throw new IllegalStateException(
                    "账户增量把商品余额扣成负数（透支是信用，不是库存）：账户="
                        + account.key().canonical()
                        + " 商品="
                        + leg.getKey()
                        + " 余额="
                        + before
                        + " 增量="
                        + leg.getValue());
              }
              long frozen = account.frozenGoods().getOrDefault(leg.getKey(), 0L);
              if (after < frozen) {
                throw new IllegalStateException(
                    "账户增量会花掉已冻结的商品：账户="
                        + account.key().canonical()
                        + " 商品="
                        + leg.getKey()
                        + " 余额="
                        + after
                        + " 冻结="
                        + frozen);
              }
              putOrRemove(account.goods, leg.getKey(), after);
            }
            for (Map.Entry<CurrencyId, Long> leg : delta.money().entrySet()) {
              long before = account.money().getOrDefault(leg.getKey(), 0L);
              long after = addExact(before, leg.getValue(), account.key(), "货币", leg.getKey());
              if (after < 0L) {
                throw new IllegalStateException(
                    "账户增量把货币扣成负数（透支 = 发行，本批无发行源）：账户="
                        + account.key().canonical()
                        + " 币种="
                        + leg.getKey()
                        + " 余额="
                        + before
                        + " 增量="
                        + leg.getValue());
              }
              long frozen = account.frozenMoney().getOrDefault(leg.getKey(), 0L);
              if (after < frozen) {
                throw new IllegalStateException(
                    "账户增量会花掉已冻结的货币：账户="
                        + account.key().canonical()
                        + " 币种="
                        + leg.getKey()
                        + " 余额="
                        + after
                        + " 冻结="
                        + frozen);
              }
              putOrRemove(account.money, leg.getKey(), after);
            }
          }

          /** ★ M8：本批已见过的冻结轴（账户 canonical + 商品/币种）。 */
          private final Set<String> frozenAxes = new LinkedHashSet<>();

          @Override
          public void applyFreeze(FreezeIntent intent) {
            ActorAccount account = requireLiveAccount(intent.accountKey());
            if (intent instanceof FreezeIntent.Goods goodsFreeze) {
              requireSingleFreeze(
                  frozenAxes, account.key().canonical() + " 商品=" + goodsFreeze.commodity());
              long balance = account.goods().getOrDefault(goodsFreeze.commodity(), 0L);
              if (goodsFreeze.amount() > balance) {
                throw new IllegalStateException(
                    "冻结额不得超过余额：账户="
                        + account.key().canonical()
                        + " 商品="
                        + goodsFreeze.commodity()
                        + " 余额="
                        + balance
                        + " 冻结="
                        + goodsFreeze.amount());
              }
              account.frozenGoods.put(goodsFreeze.commodity(), goodsFreeze.amount());
            } else if (intent instanceof FreezeIntent.Money moneyFreeze) {
              requireSingleFreeze(
                  frozenAxes, account.key().canonical() + " 币种=" + moneyFreeze.currency());
              long balance = account.money().getOrDefault(moneyFreeze.currency(), 0L);
              if (moneyFreeze.amount() > balance) {
                throw new IllegalStateException(
                    "冻结额不得超过余额：账户="
                        + account.key().canonical()
                        + " 币种="
                        + moneyFreeze.currency()
                        + " 余额="
                        + balance
                        + " 冻结="
                        + moneyFreeze.amount());
              }
              account.frozenMoney.put(moneyFreeze.currency(), moneyFreeze.amount());
            } else {
              throw new IllegalStateException("未知的冻结意向: " + intent.getClass().getName());
            }
          }
        });
  }

  /** ★★ M8：同一批提交里，同一账户同一轴的冻结意向只允许一条。 */
  private static void requireSingleFreeze(Set<String> seen, String axis) {
    if (!seen.add(axis)) {
      throw new IllegalStateException(
          "同一批提交里同一账户同轴的冻结意向出现多条（绝对值冻结只允许协调器全局合并成一条，" + "禁止按提交序后写覆盖）：" + axis);
    }
  }

  /** 活表查账（提交器专用；缺账 ⇒ 抛 —— 并行阶段要求相关主体都已登记）。 */
  private ActorAccount requireLiveAccount(AccountPartitionKey key) {
    ActorAccount account = accounts.get(key);
    if (account == null) {
      throw new IllegalStateException("账户会话里没有这本账（并行提交不得静默跳过）: " + key.canonical());
    }
    return account;
  }

  /** 家户 actor → 家户身份（唯一写口 {@code applyTransfer} 的入参形状；键按 canonical 升序构造）。 */
  private Map<ActorRef, HouseholdId> householdOfActor() {
    List<Map.Entry<ActorRef, HouseholdId>> entries = new ArrayList<>();
    for (HouseholdId household : householdIndex.keySet()) {
      entries.add(Map.entry(HouseholdActors.of(household), household));
    }
    entries.sort(java.util.Comparator.comparing(entry -> entry.getKey().toString()));
    Map<ActorRef, HouseholdId> result = new LinkedHashMap<>();
    for (Map.Entry<ActorRef, HouseholdId> entry : entries) {
      result.put(entry.getKey(), entry.getValue());
    }
    return result;
  }

  private static long addExact(
      long before, long delta, AccountPartitionKey key, String axis, Object id) {
    try {
      return Math.addExact(before, delta);
    } catch (ArithmeticException overflow) {
      throw new IllegalStateException(
          "账户" + axis + "增量溢出（坏数据，拒绝回绕）：账户=" + key.canonical() + " " + axis + "=" + id, overflow);
    }
  }

  private static <K> void putOrRemove(Map<K, Long> table, K key, long amount) {
    if (amount <= 0L) {
      table.remove(key); // 归零即去键（与旧路径 setStock/setMoney 的"空表纯形态"逐字一致）
    } else {
      table.put(key, amount);
    }
  }

  // ── 四个家户兼容活视图（只准协调器线程）──────────────────────────────────────────────────

  /** 家户商品活视图（结算代码的入参形状；键 = HouseholdId）。 */
  public Map<HouseholdId, Map<CommodityId, Long>> householdGoods() {
    checkCoordinatorThread();
    return new AccountView<>(
        this,
        accounts,
        householdIndex,
        account -> account.goods(),
        (account, value) -> account.replaceGoods(value));
  }

  /** 家户货币活视图。 */
  public Map<HouseholdId, Map<CurrencyId, Long>> householdMoney() {
    checkCoordinatorThread();
    return new AccountView<>(
        this,
        accounts,
        householdIndex,
        account -> account.money(),
        (account, value) -> account.replaceMoney(value));
  }

  /** 家户商品冻结活视图。 */
  public Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods() {
    checkCoordinatorThread();
    return new AccountView<>(
        this,
        accounts,
        householdIndex,
        account -> account.frozenGoods(),
        (account, value) -> account.replaceFrozenGoods(value));
  }

  /** 家户货币冻结活视图。 */
  public Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney() {
    checkCoordinatorThread();
    return new AccountView<>(
        this,
        accounts,
        householdIndex,
        account -> account.frozenMoney(),
        (account, value) -> account.replaceFrozenMoney(value));
  }

  /** ★★ <b>通用协调器视图</b>（索引：视图键 → 账户键；值：只读活表；替换：整体写回）。键未知 ⇒ 抛。 */
  private static final class AccountView<K, V> extends AbstractMap<K, Map<V, Long>> {

    private final AccountSession owner;
    private final Map<AccountPartitionKey, ActorAccount> accountStore;
    private final Map<K, AccountPartitionKey> index;
    private final Function<ActorAccount, Map<V, Long>> reader;
    private final Replacer<V> writer;

    AccountView(
        AccountSession owner,
        Map<AccountPartitionKey, ActorAccount> accountStore,
        Map<K, AccountPartitionKey> index,
        Function<ActorAccount, Map<V, Long>> reader,
        Replacer<V> writer) {
      this.owner = Objects.requireNonNull(owner, "owner");
      this.accountStore = accountStore;
      this.index = index;
      this.reader = reader;
      this.writer = writer;
    }

    @Override
    public Map<V, Long> get(Object key) {
      owner.checkCoordinatorThread();
      ActorAccount account = accountOrNull(key);
      return account == null ? null : reader.apply(account);
    }

    @Override
    public boolean containsKey(Object key) {
      owner.checkCoordinatorThread();
      return accountOrNull(key) != null;
    }

    @Override
    public Map<V, Long> put(K key, Map<V, Long> value) {
      owner.checkCoordinatorThread();
      Objects.requireNonNull(value, "value");
      ActorAccount account = requireAccount(key);
      Map<V, Long> previous = new LinkedHashMap<>(reader.apply(account));
      writer.replace(account, value);
      return previous;
    }

    @Override
    @SuppressFBWarnings(
        value = "SE_BAD_FIELD",
        justification =
            "entrySet 返回的 Map.Entry 是视图即时代理：SimpleEntry 的 Serializable 是 JDK 附带；匿名 entry 捕获的会话/账户视图按设计不参与 Java 序列化，序列化这些 entry 不是契约 ⇒ 误报")
    public Set<Entry<K, Map<V, Long>>> entrySet() {
      owner.checkCoordinatorThread();
      return new AbstractSet<>() {
        @Override
        public Iterator<Entry<K, Map<V, Long>>> iterator() {
          owner.checkCoordinatorThread();
          Iterator<Map.Entry<K, AccountPartitionKey>> delegate = index.entrySet().iterator();
          return new Iterator<>() {
            @Override
            public boolean hasNext() {
              owner.checkCoordinatorThread();
              return delegate.hasNext();
            }

            @Override
            public Entry<K, Map<V, Long>> next() {
              owner.checkCoordinatorThread();
              if (!delegate.hasNext()) {
                throw new NoSuchElementException();
              }
              Map.Entry<K, AccountPartitionKey> raw = delegate.next();
              ActorAccount account = accountStore.get(raw.getValue());
              return new SimpleEntry<>(raw.getKey(), reader.apply(account)) {
                @Override
                public Map<V, Long> setValue(Map<V, Long> value) {
                  owner.checkCoordinatorThread();
                  Map<V, Long> previous = new LinkedHashMap<>(getValue());
                  writer.replace(account, value);
                  return previous;
                }
              };
            }
          };
        }

        @Override
        public int size() {
          owner.checkCoordinatorThread();
          return index.size();
        }
      };
    }

    private ActorAccount accountOrNull(Object key) {
      AccountPartitionKey accountKey = index.get(key);
      return accountKey == null ? null : accountStore.get(accountKey);
    }

    private ActorAccount requireAccount(Object key) {
      ActorAccount account = accountOrNull(key);
      if (account == null) {
        throw new IllegalArgumentException("账户视图里没有这个键（拒绝静默造一本新账）: " + key);
      }
      return account;
    }
  }

  /** 内层表替换器。 */
  private interface Replacer<V> {
    void replace(ActorAccount account, Map<V, Long> value);
  }

  /** 供 {@code OwnershipBooks} 等载入方对账：家户索引键集合（只读、保序）。 */
  public Set<HouseholdId> registeredHouseholds() {
    checkCoordinatorThread();
    return Collections.unmodifiableSet(new LinkedHashSet<>(householdIndex.keySet()));
  }
}
