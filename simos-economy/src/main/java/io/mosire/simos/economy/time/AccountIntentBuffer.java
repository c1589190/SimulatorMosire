package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.transfer.Transfer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * ★★ <b>线程本地账户意向缓冲</b>（R1 并行内核）：一个 worker 在<b>一个分区</b>内的全部增量 / 冻结 / 转移意向。
 *
 * <p>★★ <b>它为什么必须是"线程本地"的</b>：并行推进的正确性来自结构 —— worker 只读 {@link AccountSnapshot}、 只写自己的缓冲；共享账户 Map 在
 * worker 期间<b>没有写者</b>。缓冲不共享、不发布、worker 结束后由协调器取走 {@link #drainIntents()} 交给 {@link
 * SettlementExecutor#commit}。★ 这里<b>不用</b> {@code
 * java.lang.ThreadLocal}（那是把可变状态藏进线程，无法静态审计）；缓冲是显式对象，生命周期一眼可见。
 *
 * <p>★★ <b>本地读一致性</b>：{@link #goods} 等读口 = 快照值 + 本地增量 + 转移影子（{@code recordTransfer} 只影响本地读、 不额外产出增量
 * —— 转移的落账由唯一写口 {@code applyTransfer} 负责，两者不能叠加成两笔）。于是同一 worker 内 "先扣后加、再判断"看到的是自己的因果链；跨 worker
 * 的冲突由分区规则（同一账户必落同一分区）与提交期校验兜底。
 *
 * <p>★ <b>顺序</b>：每个意向在创建时从本缓冲的单调计数器取 `intraIndex`；分区内实体必须按 canonical 升序遍历 （{@link PartitionPlan}
 * 保证）⇒ 意向生成序是内容的纯函数。<b>绝不允许</b>按线程到达序/线程 id/随机 UUID 生成。
 *
 * <p>★★ <b>M8：冻结写只允许协调阶段</b> —— {@link #on} / {@link #onHexPartition} 建的是 worker 缓冲 （{@code
 * freezeAllowed=false}），调 {@link #freezeGoods} / {@link #freezeMoney} 当场抛；只有 {@link
 * #forCoordinator} 建的缓冲允许写冻结。原因是冻结是绝对值：worker 本地 {@code frozenGoods} 看不到
 * 另一分区同阶段放置的冻结，两条各自从快照派生的绝对值会互相覆盖且两处都不报错。
 */
public final class AccountIntentBuffer {

  private final AccountSnapshot snapshot;
  private final SettlementStage stage;
  private final int partitionIndex;
  private final int partitionCount;

  /**
   * ★★ <b>M8：本缓冲是否允许写冻结</b>。分区 worker 的缓冲恒为 {@code false}（{@link #on} / {@link
   * #onHexPartition}）；只有协调器用 {@link #forCoordinator} 建的那一种为 {@code true}。
   *
   * <p>★ 理由：冻结是<b>绝对值</b>，而 worker 的本地 {@code frozenGoods} 只看得到"本缓冲 + 快照" ——
   * 两个分区各自从同一快照派生冻结、各自发一条绝对值意向，提交时后一条会静默覆盖前一条（两处都不报错）。 ⇒ 冻结只允许协调阶段在全局可用量校验后写；worker 里调 {@code
   * freezeGoods/freezeMoney} 当场抛。
   */
  private final boolean freezeAllowed;

  /**
   * ★★ <b>本分区拥有哪些账户</b>：默认 = 账户 canonical 串哈希到本 {@code partitionIndex}（到货那类按账户分区的阶段）； {@link
   * #onHexPartition} 时 = 账户所在格哈希到本分区（消费/投入/收获/借粮那类按 hex 分区的阶段）。
   *
   * <p>★ 这条谓词是"worker 只写自己分区"的<b>结构守卫</b>：换分区依据时只换谓词一处，不再散落 if。
   */
  private final Predicate<AccountPartitionKey> ownsKey;

  /** 本 worker 触碰过的账（键序 = 首次触达序；最终提交序由 {@link CommitOrder} 决定，不靠它）。 */
  private final LinkedHashMap<AccountPartitionKey, LocalAccount> locals = new LinkedHashMap<>();

  /** 已直接产出的意向（转移 / 冻结）。 */
  private final List<OrderedAccountIntent> emitted = new ArrayList<>();

  private int counter;

  private AccountIntentBuffer(
      AccountSnapshot snapshot,
      SettlementStage stage,
      int partitionIndex,
      int partitionCount,
      Predicate<AccountPartitionKey> ownsKey,
      boolean freezeAllowed) {
    this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    this.stage = Objects.requireNonNull(stage, "stage");
    if (partitionIndex < 0) {
      throw new IllegalArgumentException("partitionIndex 不得为负: " + partitionIndex);
    }
    if (partitionCount < 1 || partitionIndex >= partitionCount) {
      throw new IllegalArgumentException(
          "partitionIndex 必须 ∈ [0, partitionCount): " + partitionIndex + " / " + partitionCount);
    }
    this.partitionIndex = partitionIndex;
    this.partitionCount = partitionCount;
    this.ownsKey = Objects.requireNonNull(ownsKey, "ownsKey");
    this.freezeAllowed = freezeAllowed;
  }

  /**
   * 产出点：worker 从分区计划拿到 {@code partitionIndex} 与 {@code partitionCount} 后调用（快照由协调器传入， worker 不自造）。★
   * 带上 {@code partitionCount} 是为了在缓冲内部**结构性拒绝**"worker 写别的分区账户"。
   *
   * <p>★ M8：worker 缓冲<b>不允许冻结写</b>（{@link #freezeGoods} / {@link #freezeMoney} 当场抛）； 冻结请走 {@link
   * #forCoordinator}。
   */
  public static AccountIntentBuffer on(
      AccountSnapshot snapshot, SettlementStage stage, int partitionIndex, int partitionCount) {
    return new AccountIntentBuffer(
        snapshot,
        stage,
        partitionIndex,
        partitionCount,
        key -> key.partitionIndex(partitionCount) == partitionIndex,
        false);
  }

  /**
   * ★★ <b>按 hex 分区的产出点</b>（消费/投入/收获/借粮）：账户归哪个分区由<b>它所在格</b>的 canonical 串 （{@code
   * HexCoord.toString()}，与 {@link PartitionPlan} 的 hex 键逐字同源）决定 —— 同一格的多个主体
   * （农场、作坊、城镇家户）因此必然落同一分区、串行执行；跨格不可能写同一本账（冲突表的结论）。
   *
   * <p>★ 谓词只读账户的 {@code location}：不含线程 id / 到达序 / 随机源。★ M8：同样不允许冻结写。
   */
  public static AccountIntentBuffer onHexPartition(
      AccountSnapshot snapshot, SettlementStage stage, int partitionIndex, int partitionCount) {
    return new AccountIntentBuffer(
        snapshot,
        stage,
        partitionIndex,
        partitionCount,
        key -> {
          io.mosire.simos.map.hex.HexCoord location = snapshot.locationOf(key.household());
          if (location == null) {
            throw new IllegalStateException(
                "家户未登记位置，无法按格分区（账户身份与位置解耦后，位置索引是分区唯一读口）: "
                    + key.canonical());
          }
          return AccountPartitionKey.partitionIndexOf(location.toString(), partitionCount)
              == partitionIndex;
        },
        false);
  }

  /**
   * ★★ <b>协调器缓冲（M8：唯一允许写冻结的产出点）</b> —— 冻结的绝对值必须在全局可用量校验后由协调器算好， 再经 {@link AccountSession#commit}
   * 的单线程提交器落账；同一账户同一轴只允许一条意向（提交器会判死重复）。
   *
   * <p>★ 本工厂不代替 owner 守卫：调用方仍须是创建该 {@link AccountSnapshot} 的 {@link AccountSession} 的协调器线程； 提交时会再过
   * {@link AccountSession#checkCoordinatorThread()}。
   */
  static AccountIntentBuffer forCoordinator(
      AccountSnapshot snapshot, SettlementStage stage, int partitionIndex, int partitionCount) {
    return new AccountIntentBuffer(
        snapshot,
        stage,
        partitionIndex,
        partitionCount,
        key -> key.partitionIndex(partitionCount) == partitionIndex,
        true);
  }

  /** 这个缓冲服务的分区号（提交序的第二段）。 */
  public int partitionIndex() {
    return partitionIndex;
  }

  /** 余额读口：快照 + 本地增量 + 转移影子。 */
  public long goods(AccountPartitionKey key, CommodityId commodity) {
    LocalAccount local = localOrNull(key);
    return snapshot.goods(key, commodity)
        + localGoodsDelta(local, commodity)
        + localShadowGoods(local, commodity);
  }

  /** 货币余额读口：快照 + 本地增量 + 转移影子。 */
  public long money(AccountPartitionKey key, CurrencyId currency) {
    LocalAccount local = localOrNull(key);
    return snapshot.money(key, currency)
        + localMoneyDelta(local, currency)
        + localShadowMoney(local, currency);
  }

  /** 冻结读口：本地放置过 ⇒ 取本地目标值（绝对值语义）；否则取快照。 */
  public long frozenGoods(AccountPartitionKey key, CommodityId commodity) {
    LocalAccount local = localOrNull(key);
    if (local != null && local.frozenGoods.containsKey(commodity)) {
      return local.frozenGoods.get(commodity);
    }
    return snapshot.frozenGoods(key, commodity);
  }

  /** 货币冻结读口。 */
  public long frozenMoney(AccountPartitionKey key, CurrencyId currency) {
    LocalAccount local = localOrNull(key);
    if (local != null && local.frozenMoney.containsKey(currency)) {
      return local.frozenMoney.get(currency);
    }
    return snapshot.frozenMoney(key, currency);
  }

  /** ★ 借记商品：本地先判"扣完不得低于冻结额"，再记负增量（提交器会对着活表重判一遍）。 */
  public void debitGoods(AccountPartitionKey key, CommodityId commodity, long amount) {
    requirePositive(amount, "debitGoods");
    long available = goods(key, commodity);
    long frozen = frozenGoods(key, commodity);
    if (available - amount < frozen) {
      throw new IllegalStateException(
          "本地意向把商品扣到冻结额以下（冻结只表达已明确的占用）：账户="
              + key.canonical()
              + " 商品="
              + commodity
              + " 余额="
              + available
              + " 冻结="
              + frozen
              + " 扣减="
              + amount);
    }
    LocalAccount local = local(key);
    local.goods.merge(commodity, -amount, Math::addExact);
  }

  /** ★ 贷记商品（收方不判上界；账必须存在）。 */
  public void creditGoods(AccountPartitionKey key, CommodityId commodity, long amount) {
    requirePositive(amount, "creditGoods");
    snapshot.requireAccount(key);
    LocalAccount local = local(key);
    local.goods.merge(commodity, amount, Math::addExact);
  }

  /** ★ 借记货币：同一套方向（余额不足 ⇒ 抛；本批没有发行源，透支不是货币）。 */
  public void debitMoney(AccountPartitionKey key, CurrencyId currency, long amount) {
    requirePositive(amount, "debitMoney");
    long available = money(key, currency);
    long frozen = frozenMoney(key, currency);
    if (available - amount < frozen) {
      throw new IllegalStateException(
          "本地意向把货币扣到冻结额以下（或扣成负数 —— 透支 = 发行，本批无发行源）：账户="
              + key.canonical()
              + " 币种="
              + currency
              + " 余额="
              + available
              + " 冻结="
              + frozen
              + " 扣减="
              + amount);
    }
    LocalAccount local = local(key);
    local.money.merge(currency, -amount, Math::addExact);
  }

  /** ★ 贷记货币。 */
  public void creditMoney(AccountPartitionKey key, CurrencyId currency, long amount) {
    requirePositive(amount, "creditMoney");
    snapshot.requireAccount(key);
    LocalAccount local = local(key);
    local.money.merge(currency, amount, Math::addExact);
  }

  /** ★ 放置商品冻结（绝对值语义，{@code amount == 0} = 解冻）；★ M8：只允许协调器缓冲写（见 {@link #freezeAllowed}）。 */
  public void freezeGoods(AccountPartitionKey key, CommodityId commodity, long amount) {
    requireCoordinatorFreeze("freezeGoods");
    requireNonNegative(amount, "freezeGoods");
    long balance = goods(key, commodity);
    if (amount > balance) {
      throw new IllegalStateException(
          "冻结额不得超过余额：账户="
              + key.canonical()
              + " 商品="
              + commodity
              + " 余额="
              + balance
              + " 冻结="
              + amount);
    }
    LocalAccount local = local(key);
    local.frozenGoods.put(commodity, amount);
    emitted.add(
        FreezeIntent.goods(key, stage, partitionIndex, nextIntraIndex(), commodity, amount));
  }

  /** ★ 释放商品冻结（= 置 0）；★ M8：只允许协调器缓冲写。 */
  public void releaseGoods(AccountPartitionKey key, CommodityId commodity) {
    freezeGoods(key, commodity, 0L);
  }

  /** ★ 放置货币冻结（绝对值语义，{@code amount == 0} = 解冻）；★ M8：只允许协调器缓冲写（见 {@link #freezeAllowed}）。 */
  public void freezeMoney(AccountPartitionKey key, CurrencyId currency, long amount) {
    requireCoordinatorFreeze("freezeMoney");
    requireNonNegative(amount, "freezeMoney");
    long balance = money(key, currency);
    if (amount > balance) {
      throw new IllegalStateException(
          "冻结额不得超过余额：账户="
              + key.canonical()
              + " 币种="
              + currency
              + " 余额="
              + balance
              + " 冻结="
              + amount);
    }
    LocalAccount local = local(key);
    local.frozenMoney.put(currency, amount);
    emitted.add(FreezeIntent.money(key, stage, partitionIndex, nextIntraIndex(), currency, amount));
  }

  /** ★ 释放货币冻结（= 置 0）；★ M8：只允许协调器缓冲写。 */
  public void releaseMoney(AccountPartitionKey key, CurrencyId currency) {
    freezeMoney(key, currency, 0L);
  }

  /**
   * ★★ <b>M8：冻结写只允许协调阶段</b> —— worker 分区缓冲调用即抛，消息给出正确入口。
   *
   * <p>这不是性能守卫，是结构守卫：本地 {@code frozenGoods} 看不到别的分区同阶段放置的冻结，绝对值意向在提交器里
   * 互相覆盖时两处都不会报错。协调器必须先把全局可用量与各分区需求收齐、合并成一条绝对值，再用 {@link #forCoordinator} 产出。
   */
  private void requireCoordinatorFreeze(String operation) {
    if (!freezeAllowed) {
      throw new IllegalStateException(
          operation
              + " 只允许协调阶段写（M8：worker 分区缓冲的本地 frozenGoods 看不到另一分区同阶段放置的冻结；"
              + "各自从快照派生绝对值会在提交期静默互相覆盖）。冻结必须由协调器全局校验后合并成一条绝对值，"
              + "并用 AccountIntentBuffer.forCoordinator(...) 产出。本缓冲：stage="
              + stage
              + " partition="
              + partitionIndex
              + "/"
              + partitionCount);
    }
  }

  /**
   * ★ 记录一条转移意向：只影响本地读（影子），<b>不</b>额外产出账户增量 —— 落账由唯一写口 {@code EconomySettlement.applyTransfer} 负责。
   *
   * <p>★ 本地先按付方腿做一次"扣得动 + 不花冻结"的预判（与两遍式写口的第一遍同一条判据），付款能力不足在这里就炸， 而不是等到提交阶段。
   */
  public void recordTransfer(Transfer transfer) {
    Objects.requireNonNull(transfer, "transfer");
    AccountPartitionKey fromKey = snapshot.requireActorKey(transfer.from());
    AccountPartitionKey toKey = snapshot.requireActorKey(transfer.to());
    requireOwnPartition(fromKey); // ★ 付方账户必须落在本 worker 的分区（收方可以跨区，由提交器协调）
    requireSameLocation(fromKey, transfer, "付方");
    requireSameLocation(toKey, transfer, "收方");
    // 影子记进两端：先校验付方扣得动（与 applyTransfer 的第一遍同向）。
    for (Map.Entry<CommodityId, Long> leg : transfer.goods().entrySet()) {
      long amount = leg.getValue();
      if (amount <= 0L) {
        continue;
      }
      if (goods(fromKey, leg.getKey()) - amount < frozenGoods(fromKey, leg.getKey())) {
        throw new IllegalStateException(
            "转移意向的付方商品腿扣不动（余额不足或会花掉冻结）：账户="
                + fromKey.canonical()
                + " 商品="
                + leg.getKey()
                + " 现有="
                + goods(fromKey, leg.getKey())
                + " 冻结="
                + frozenGoods(fromKey, leg.getKey())
                + " 扣减="
                + amount
                + "；转移="
                + transfer);
      }
    }
    for (Map.Entry<CurrencyId, Long> leg : transfer.money().entrySet()) {
      long amount = leg.getValue();
      if (amount <= 0L) {
        continue;
      }
      if (money(fromKey, leg.getKey()) - amount < frozenMoney(fromKey, leg.getKey())) {
        throw new IllegalStateException(
            "转移意向的付方货币腿扣不动（余额不足或会花掉冻结）：账户="
                + fromKey.canonical()
                + " 币种="
                + leg.getKey()
                + " 现有="
                + money(fromKey, leg.getKey())
                + " 冻结="
                + frozenMoney(fromKey, leg.getKey())
                + " 扣减="
                + amount
                + "；转移="
                + transfer);
      }
    }
    LocalAccount from = local(fromKey);
    // ★ 收方允许跨分区：只为本地读记影子（不产出增量、不进 drainIntents），故不走 requireOwnPartition。
    LocalAccount to = localShadow(toKey);
    for (Map.Entry<CommodityId, Long> leg : transfer.goods().entrySet()) {
      long amount = leg.getValue();
      if (amount <= 0L) {
        continue;
      }
      from.shadowGoods.merge(leg.getKey(), -amount, Math::addExact);
      to.shadowGoods.merge(leg.getKey(), amount, Math::addExact);
    }
    for (Map.Entry<CurrencyId, Long> leg : transfer.money().entrySet()) {
      long amount = leg.getValue();
      if (amount <= 0L) {
        continue;
      }
      from.shadowMoney.merge(leg.getKey(), -amount, Math::addExact);
      to.shadowMoney.merge(leg.getKey(), amount, Math::addExact);
    }
    emitted.add(TransferIntent.of(stage, partitionIndex, nextIntraIndex(), transfer));
  }

  /** ★★ <b>交出本分区全部意向</b>（worker 结束后由协调器取走）：按 {@link CommitOrder} 升序冻结返回；未触碰的账不产出 空增量。 */
  public List<OrderedAccountIntent> drainIntents() {
    List<OrderedAccountIntent> intents = new ArrayList<>(emitted);
    for (Map.Entry<AccountPartitionKey, LocalAccount> entry : locals.entrySet()) {
      LocalAccount local = entry.getValue();
      if (local.goods.isEmpty() && local.money.isEmpty()) {
        continue;
      }
      if (local.firstIntraIndex < 0) {
        throw new IllegalStateException("账户增量没有生成序号（缓冲内部错误）: " + entry.getKey());
      }
      intents.add(
          AccountDelta.of(
              entry.getKey(),
              stage,
              partitionIndex,
              local.firstIntraIndex,
              local.goods,
              local.money));
    }
    intents.sort(null);
    return Collections.unmodifiableList(intents);
  }

  /** 本分区已触碰的账户键（只读；只服务诊断/审计）。 */
  public List<AccountPartitionKey> touchedKeys() {
    return Collections.unmodifiableList(new ArrayList<>(locals.keySet()));
  }

  private LocalAccount local(AccountPartitionKey key) {
    requireOwnPartition(key);
    snapshot.requireAccount(key); // 缺账 ⇒ 当场抛（禁止静默造账 / 把缺账当 0）
    LocalAccount local = locals.get(key);
    if (local == null) {
      local = new LocalAccount(nextIntraIndex());
      locals.put(key, local);
    }
    return local;
  }

  /** 只服务转移收方的本地影子（允许跨分区；不产出增量，故 drainIntents 会跳过它）。 */
  private LocalAccount localShadow(AccountPartitionKey key) {
    snapshot.requireAccount(key);
    LocalAccount local = locals.get(key);
    if (local == null) {
      local = new LocalAccount(nextIntraIndex());
      locals.put(key, local);
    }
    return local;
  }

  private LocalAccount localOrNull(AccountPartitionKey key) {
    return locals.get(Objects.requireNonNull(key, "key"));
  }

  private int nextIntraIndex() {
    return counter++;
  }

  private static long localGoodsDelta(LocalAccount local, CommodityId commodity) {
    return local == null ? 0L : local.goods.getOrDefault(commodity, 0L);
  }

  private static long localMoneyDelta(LocalAccount local, CurrencyId currency) {
    return local == null ? 0L : local.money.getOrDefault(currency, 0L);
  }

  private static long localShadowGoods(LocalAccount local, CommodityId commodity) {
    return local == null ? 0L : local.shadowGoods.getOrDefault(commodity, 0L);
  }

  private static long localShadowMoney(LocalAccount local, CurrencyId currency) {
    return local == null ? 0L : local.shadowMoney.getOrDefault(currency, 0L);
  }

  /** ★ 结构守卫：本缓冲只准触碰"归自己分区"的账户（分区函数与 {@link PartitionPlan} 逐字同一处）。 */
  private void requireOwnPartition(AccountPartitionKey key) {
    if (!ownsKey.test(key)) {
      throw new IllegalStateException(
          "worker 试图写别的分区的账户（同一账户的意向必须落在同一分区）：账户="
              + key.canonical()
              + "，本分区="
              + partitionIndex
              + " / "
              + partitionCount
              + "（按 hex 分区的阶段看账户 location 的格键）");
    }
  }

  private static void requirePositive(long amount, String operation) {
    if (amount <= 0L) {
      throw new IllegalArgumentException(operation + " 的金额必须 > 0: " + amount);
    }
  }

  private static void requireNonNegative(long amount, String operation) {
    if (amount < 0L) {
      throw new IllegalArgumentException(operation + " 的冻结额不得为负: " + amount);
    }
  }

  private void requireSameLocation(AccountPartitionKey key, Transfer transfer, String side) {
    io.mosire.simos.map.hex.HexCoord registered = snapshot.locationOf(key.household());
    if (registered == null || !registered.equals(transfer.location())) {
      throw new IllegalStateException(
          "转移的 location 与"
              + side
              + "账户登记位置不一致（说不清落进哪一本账）：账户="
              + key.canonical()
              + "，转移 location="
              + transfer.location()
              + "；转移="
              + transfer);
    }
  }

  /** 一本账的本地累加器（商品/货币增量 + 转移影子 + 冻结目标 + 首个序号）。 */
  private static final class LocalAccount {

    private final LinkedHashMap<CommodityId, Long> goods = new LinkedHashMap<>();
    private final LinkedHashMap<CurrencyId, Long> money = new LinkedHashMap<>();
    private final LinkedHashMap<CommodityId, Long> shadowGoods = new LinkedHashMap<>();
    private final LinkedHashMap<CurrencyId, Long> shadowMoney = new LinkedHashMap<>();
    private final LinkedHashMap<CommodityId, Long> frozenGoods = new LinkedHashMap<>();
    private final LinkedHashMap<CurrencyId, Long> frozenMoney = new LinkedHashMap<>();
    private final int firstIntraIndex;

    private LocalAccount(int firstIntraIndex) {
      this.firstIntraIndex = firstIntraIndex;
    }
  }
}
