package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.map.hex.HexCoord;
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
 * ★★ <b>结算会话的唯一账户表</b>（S1 / P1.4-P1.5 / R1 线程安全内核）：把所有"会话工作副本"收敛到 <b>一个按 {@link
 * AccountPartitionKey}（= {@code (ActorRef, HexCoord)}）索引的对象</b>上，取代旧的四张会话地图 （householdGoods /
 * householdMoney / operatorGoods / operatorMoney）与四张 frozen 表。
 *
 * <p>★★ <b>R1 的线程安全分工（本类最重要的契约）</b>：
 *
 * <pre>
 * ┌─────────────────────── 协调器线程（唯一 owner）───────────────────────┐
 * │ 活表：ActorAccount 的四张 LinkedHashMap（商品/货币 × 余额/冻结）     │
 * │   · 只有本线程可读写（{@link #checkCoordinatorThread()} 结构上把死） │
 * │   · 唯一写口：日结算的既有单线程路径 + {@link #commit} 的单线程提交器 │
 * ├──────────────────────── worker（1..N，只读）─────────────────────────┤
 * │ {@link #snapshot()} 的不可变投影 + AccountIntentBuffer.on/onHexPartition 的线程本地增量 │
 * │   · worker 拿不到活表；意向 = AccountDelta / TransferIntent / FreezeIntent │
 * └──────────────────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>★ <b>为什么 owner 线程守卫是"结构"而不是"装饰"</b>：它让"worker 直接写共享 Map"这条错误路径在第一次访问就 抛，而不是靠代码评审记得。★ 它不是
 * {@code synchronized}（没有锁、没有等待、没有把整段日结算包起来），也 <b>不是</b> {@code ThreadLocal}（活表是显式对象、owner
 * 是显式字段，不藏全局可变状态）。
 *
 * <p>★ <b>提交</b>：{@link #commit(Collection)} 只允许协调器线程调用，内部委托 {@link SettlementExecutor#commit} 按
 * {@code (stage, partitionIndex, canonicalKey, intraIndex)} 稳定序落账； 转移一条不差地走 {@code
 * EconomySettlement.applyTransfer}（两遍式唯一写口），增量的余额 / 冻结校验与 {@code validateApplyTransfer} 同向。
 *
 * <p>★ <b>兼容视图</b>：{@link #householdGoods()} 等八个访问器返回<b>协调器专用视图</b>（{@code AbstractMap}）—— 旧
 * 日结算代码可以继续以"键 → 内层表"的形状读写，而存储仍只有一处。★ 内层表是<b>只读活视图</b>（{@code Collections.unmodifiableMap}），视图的
 * {@code put}（整体替换内层表）只允许协调器线程调用；worker 即使被协调器递到视图， 任何读写入口都会在 owner 守卫处当场抛（M1）。迭代序 = 索引的插入序（{@code
 * LinkedHashMap}，确定性）。
 */
public final class AccountSession {

  /** 一本账的四张活表（商品/货币 × 余额/冻结）；活表本体只有本类与 {@link AccountSession#commit} 能写，外部只能拿只读视图。 */
  public static final class ActorAccount {

    private final AccountPartitionKey key;
    private final LinkedHashMap<CommodityId, Long> goods = new LinkedHashMap<>();
    private final LinkedHashMap<CurrencyId, Long> money = new LinkedHashMap<>();
    private final LinkedHashMap<CommodityId, Long> frozenGoods = new LinkedHashMap<>();
    private final LinkedHashMap<CurrencyId, Long> frozenMoney = new LinkedHashMap<>();

    /**
     * ★★ <b>M1：四张活表的只读视图</b>（活 = 随账本更新；不可写 = 不构成第二条写路径）。
     *
     * <p>它们是 {@code Collections.unmodifiableMap} 包装，写操作（{@code put}/{@code remove}/{@code
     * clear}/{@code merge}） 一律当场抛 {@link UnsupportedOperationException}；活表本体仍是 private，只有本类与 {@code
     * AccountSession} 的提交器能写。 worker 即使拿到 {@link ActorAccount}，也只能读、不能绕过 {@link
     * AccountSession#commit} 直写。
     */
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

    /** 商品余额（**只读活视图**；写路径只有 {@link AccountSession#commit}）。 */
    public Map<CommodityId, Long> goods() {
      return goodsView;
    }

    /** 货币余额（只读活视图）。 */
    public Map<CurrencyId, Long> money() {
      return moneyView;
    }

    /** 商品冻结（只读活视图）。 */
    public Map<CommodityId, Long> frozenGoods() {
      return frozenGoodsView;
    }

    /** 货币冻结（只读活视图）。 */
    public Map<CurrencyId, Long> frozenMoney() {
      return frozenMoneyView;
    }

    /** 整体替换商品余额（结算里少数"换一张新内层表"的旧路径用；保持同一本账对象）。 */
    void replaceGoods(Map<CommodityId, Long> value) {
      goods.clear();
      goods.putAll(value);
    }

    /** 整体替换货币余额。 */
    void replaceMoney(Map<CurrencyId, Long> value) {
      money.clear();
      money.putAll(value);
    }

    /** 整体替换商品冻结。 */
    void replaceFrozenGoods(Map<CommodityId, Long> value) {
      frozenGoods.clear();
      frozenGoods.putAll(value);
    }

    /** 整体替换货币冻结。 */
    void replaceFrozenMoney(Map<CurrencyId, Long> value) {
      frozenMoney.clear();
      frozenMoney.putAll(value);
    }
  }

  /** 唯一存储：{@code (actor, location)} → 账（只准协调器线程访问）。 */
  private final LinkedHashMap<AccountPartitionKey, ActorAccount> accounts = new LinkedHashMap<>();

  /** 家户视图索引（家户 id → 账户键）；由载入方按 {@code ClassRow.view().hex()} 登记。 */
  private final LinkedHashMap<HouseholdId, AccountPartitionKey> householdIndex =
      new LinkedHashMap<>();

  /** 经营者视图索引（actor → 账户键）；由载入方按 {@code IndustryHexKeys.hexKeyOf(industry.id())} 登记。 */
  private final LinkedHashMap<ActorRef, AccountPartitionKey> operatorIndex = new LinkedHashMap<>();

  /** 家户 actor → 家户身份反查（由 {@link #registerHousehold} 维护；快照/提交都要按 actor 定位家户账）。 */
  private final LinkedHashMap<ActorRef, HouseholdId> householdByActor = new LinkedHashMap<>();

  /**
   * ★★ <b>协调器线程</b>（本会话的唯一 owner）：构造于哪个线程，活表与索引就只服务那个线程。
   *
   * <p>★ worker 线程拿到的只能是 {@link AccountSnapshot}（不可变）与自己的 {@link AccountIntentBuffer} （线程本地）——
   * 不许碰活表，故这条守卫是"多线程只能产意向"的结构化保证。
   */
  private final Thread coordinatorThread;

  private AccountSession() {
    this.coordinatorThread = Thread.currentThread();
  }

  public static AccountSession empty() {
    return new AccountSession();
  }

  /**
   * ★ <b>owner 线程守卫</b>：活表 / 索引 / 注册 / 落回 / 提交只允许创建本会话的线程访问。
   *
   * <p>★ 违反 ⇒ 当场抛（不静默、不降级成"看起来能跑但结果不可重放"）。并行 worker 请用 {@link #snapshot()} 与 {@link
   * AccountIntentBuffer#on(AccountSnapshot, SettlementStage, int, int)} / {@link
   * AccountIntentBuffer#onHexPartition(AccountSnapshot, SettlementStage, int, int)}（M3：会话不代建缓冲）。
   */
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

  /** 取（必要时建）一本账；本方法只服务"该主体应有账"的路径，不会静默返回 null。 */
  public ActorAccount account(ActorRef actor, HexCoord location) {
    checkCoordinatorThread();
    AccountPartitionKey key = new AccountPartitionKey(actor, location);
    return accounts.computeIfAbsent(key, ActorAccount::new);
  }

  /** 取一本账；没有 ⇒ null（查询口径；只准协调器线程）。 */
  public ActorAccount accountOrNull(ActorRef actor, HexCoord location) {
    checkCoordinatorThread();
    return accounts.get(new AccountPartitionKey(actor, location));
  }

  /** 全部账（**活视图**，只读外层；顺序 = 插入序；只准协调器线程）。 */
  public Map<AccountPartitionKey, ActorAccount> accounts() {
    checkCoordinatorThread();
    return Collections.unmodifiableMap(accounts);
  }

  /**
   * ★★ <b>worker 的只读快照</b>：协调器在阶段开始前取一次，交给 {@link SettlementExecutor#execute} 的 worker。
   * 快照是不可变投影，worker 读它<b>不需要</b>也不允许碰本对象的活表。
   */
  public AccountSnapshot snapshot() {
    checkCoordinatorThread();
    Map<AccountPartitionKey, AccountSnapshot.SnapshotAccount> snapshotAccounts =
        new LinkedHashMap<>();
    // 按 canonical 升序构造（AccountSnapshot 内部还会再排一次；这里先排，保证输入的确定性）。
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
    return AccountSnapshot.of(
        snapshotAccounts, new LinkedHashMap<>(householdIndex), new LinkedHashMap<>(operatorIndex));
  }

  // ★ M3：原 public AccountSession.intentBuffer(...) 已删除（main 零调用的第二缓冲入口）。
  //   worker 缓冲的唯一官方产出点是 AccountIntentBuffer.on / onHexPartition（由 SettlementExecutor.execute
  //   的调用方在拿到 AccountSnapshot 后显式创建）；本会话只交 snapshot，不代建缓冲。

  /** 登记家户账（视图索引 + 账户；同一家户重复登记同键 ⇒ 幂等，不同键 ⇒ 抛）。 */
  public void registerHousehold(
      HouseholdId household,
      ActorRef actor,
      HexCoord location,
      Map<CommodityId, Long> goods,
      Map<CurrencyId, Long> money,
      Map<CommodityId, Long> frozenGoods,
      Map<CurrencyId, Long> frozenMoney) {
    checkCoordinatorThread();
    Objects.requireNonNull(household, "household");
    AccountPartitionKey key = new AccountPartitionKey(actor, location);
    AccountPartitionKey previous = householdIndex.putIfAbsent(household, key);
    if (previous != null && !previous.equals(key)) {
      throw new IllegalStateException("家户 " + household + " 已有账户键 " + previous + "，不能改登记为 " + key);
    }
    HouseholdId previousHousehold = householdByActor.putIfAbsent(actor, household);
    if (previousHousehold != null && !previousHousehold.equals(household)) {
      throw new IllegalStateException(
          "家户 actor " + actor + " 已登记给 " + previousHousehold + "，不能改登记给 " + household);
    }
    ActorAccount account = accounts.computeIfAbsent(key, ActorAccount::new);
    account.replaceGoods(goods);
    account.replaceMoney(money);
    account.replaceFrozenGoods(frozenGoods);
    account.replaceFrozenMoney(frozenMoney);
  }

  /** 登记经营者账（视图索引 + 账户）；同一 actor 重复登记不同格 ⇒ 抛（当前世界一主体一格）。 */
  public void registerOperator(
      ActorRef actor,
      HexCoord location,
      Map<CommodityId, Long> goods,
      Map<CurrencyId, Long> money,
      Map<CommodityId, Long> frozenGoods,
      Map<CurrencyId, Long> frozenMoney) {
    checkCoordinatorThread();
    Objects.requireNonNull(actor, "actor");
    AccountPartitionKey key = new AccountPartitionKey(actor, location);
    AccountPartitionKey previous = operatorIndex.putIfAbsent(actor, key);
    if (previous != null && !previous.equals(key)) {
      throw new IllegalStateException("经营者 " + actor + " 已有账户键 " + previous + "，不能改登记为 " + key);
    }
    ActorAccount account = accounts.computeIfAbsent(key, ActorAccount::new);
    account.replaceGoods(goods);
    account.replaceMoney(money);
    account.replaceFrozenGoods(frozenGoods);
    account.replaceFrozenMoney(frozenMoney);
  }

  /** 家户账户键（载入时登记过才有）。 */
  public AccountPartitionKey householdKeyOf(HouseholdId household) {
    checkCoordinatorThread();
    return householdIndex.get(Objects.requireNonNull(household, "household"));
  }

  /** 经营者账户键（载入时登记过才有）。 */
  public AccountPartitionKey operatorKeyOf(ActorRef actor) {
    checkCoordinatorThread();
    return operatorIndex.get(Objects.requireNonNull(actor, "actor"));
  }

  /** 任一主体（家户 / 经营者）的账户键；没有 ⇒ null。 */
  public AccountPartitionKey actorKeyOrNull(ActorRef actor) {
    checkCoordinatorThread();
    HouseholdId household = householdByActor.get(Objects.requireNonNull(actor, "actor"));
    AccountPartitionKey householdKey = household == null ? null : householdIndex.get(household);
    AccountPartitionKey operator = operatorIndex.get(actor);
    if (householdKey != null && operator != null && !householdKey.equals(operator)) {
      throw new IllegalStateException("同一个 actor 同时登记了家户账与经营者账（装配错误）: " + actor);
    }
    return householdKey != null ? householdKey : operator;
  }

  /** 家户账户（载入时登记过才有）。 */
  public ActorAccount householdAccount(HouseholdId household) {
    checkCoordinatorThread();
    AccountPartitionKey key = householdIndex.get(household);
    return key == null ? null : accounts.get(key);
  }

  /** 经营者账户（载入时登记过才有）。 */
  public ActorAccount operatorAccount(ActorRef actor) {
    checkCoordinatorThread();
    AccountPartitionKey key = operatorIndex.get(actor);
    return key == null ? null : accounts.get(key);
  }

  // ── 单线程稳定提交（R1 的核心）────────────────────────────────────────────────────────

  /**
   * ★★ <b>稳定提交一批意向</b>（只允许协调器线程；唯一落账入口）。
   *
   * <pre>
   * 1. SettlementExecutor.commit 按 (stage, partitionIndex, canonicalKey, intraIndex) 排序；
   * 2. TransferIntent → EconomySettlement.applyTransfer（两遍式唯一写口）；
   * 3. AccountDelta    → 逐腿校验"余额不成为负、扣完不低于冻结"后并入活表；
   * 4. FreezeIntent   → 校验 0 ≤ amount ≤ 余额 后置冻结额。
   * </pre>
   *
   * <p>★ 任一条校验失败 ⇒ 抛出（整批推进失败；调用方按 {@code TimeAdvance} 的既有语义丢弃本批）。本方法不做 "跳过失败条"的静默兜底。
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
              throw new IllegalStateException(
                  "并行提交路径要求转移两端都在账户会话里（未登记的账不能在会话内静默跳过）：转移=" + transfer);
            }
            EconomySettlement.applyTransfer(
                householdGoods(),
                householdMoney(),
                operatorGoods(),
                operatorMoney(),
                householdFrozenGoods(),
                householdFrozenMoney(),
                operatorFrozenGoods(),
                operatorFrozenMoney(),
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

  /**
   * ★★ <b>M8：同一批提交里，同一账户同一轴的冻结意向只允许一条</b>。
   *
   * <p>冻结是<b>绝对值</b>语义（{@code Frozen = amount}）：两条绝对值意向落在同一账户同一轴时，后提交者会静默覆盖前者的占用 ——
   * 两个分区各自从快照派生一条时，两处都不会报错，最终只冻结了一个分区的量。本守卫把这条接缝判死：冻结必须由<b>协调器</b> 在全局可用量校验后合并成一条绝对值再提交（R3
   * 接入跨区挂冻时同款）。
   */
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
    List<Map.Entry<ActorRef, HouseholdId>> entries = new ArrayList<>(householdByActor.entrySet());
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

  // ── 八个兼容活视图（只准协调器线程）────────────────────────────────────────────────────

  /** 家户商品活视图（旧结算代码的入参形状；键 = HouseholdId）。 */
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

  /** 经营者商品活视图（旧结算代码的入参形状；键 = ActorRef）。 */
  public Map<ActorRef, Map<CommodityId, Long>> operatorGoods() {
    checkCoordinatorThread();
    return new AccountView<>(
        this,
        accounts,
        operatorIndex,
        account -> account.goods(),
        (account, value) -> account.replaceGoods(value));
  }

  /** 经营者货币活视图。 */
  public Map<ActorRef, Map<CurrencyId, Long>> operatorMoney() {
    checkCoordinatorThread();
    return new AccountView<>(
        this,
        accounts,
        operatorIndex,
        account -> account.money(),
        (account, value) -> account.replaceMoney(value));
  }

  /** 经营者商品冻结活视图。 */
  public Map<ActorRef, Map<CommodityId, Long>> operatorFrozenGoods() {
    checkCoordinatorThread();
    return new AccountView<>(
        this,
        accounts,
        operatorIndex,
        account -> account.frozenGoods(),
        (account, value) -> account.replaceFrozenGoods(value));
  }

  /** 经营者货币冻结活视图。 */
  public Map<ActorRef, Map<CurrencyId, Long>> operatorFrozenMoney() {
    checkCoordinatorThread();
    return new AccountView<>(
        this,
        accounts,
        operatorIndex,
        account -> account.frozenMoney(),
        (account, value) -> account.replaceFrozenMoney(value));
  }

  /**
   * ★★ <b>通用协调器视图</b>（索引：视图键 → 账户键；值：只读活表；替换：整体写回）。键未知 ⇒ 抛（不静默造账）。
   *
   * <p>★ <b>M1 的两道守卫</b>：① 内层值来自 {@link ActorAccount} 的 {@code Collections.unmodifiableMap}
   * 只读视图，写内层表当场抛；② 本类<b>每一个入口</b>（读、写、迭代、{@code setValue}）都先过 {@link
   * AccountSession#checkCoordinatorThread()}，worker 即使被协调器递到视图对象，也在第一次调用时抛 —— 不能借它绕过 {@link
   * AccountSession#commit} 直写活账本。
   */
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

  /** 内层表替换器（避免装箱泛型函数双参形态）。 */
  private interface Replacer<V> {
    void replace(ActorAccount account, Map<V, Long> value);
  }

  /** 供 {@code OwnershipBooks} 等载入方构造索引时使用：家户索引键集合（只读、保序）。 */
  public Set<HouseholdId> registeredHouseholds() {
    checkCoordinatorThread();
    return Collections.unmodifiableSet(new LinkedHashSet<>(householdIndex.keySet()));
  }

  /** 供载入方对账：经营者索引键集合（只读、保序）。 */
  public Set<ActorRef> registeredOperators() {
    checkCoordinatorThread();
    return Collections.unmodifiableSet(new LinkedHashSet<>(operatorIndex.keySet()));
  }
}
