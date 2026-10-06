package io.mosire.simos.economy.time;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>账户增量意向</b>（R1 并行内核）：worker 对某本账的**带符号**净变动（商品 / 货币）。
 *
 * <p>★★ <b>为什么是"增量"而不是"新的余额表"</b>：
 *
 * <ol>
 *   <li>多线程各自算自己的分区，增量天然可并行；"新余额"必须先把别人的增量读进来（就回到了共享可变状态）；
 *   <li>提交器按稳定序把增量逐条并进账户，每一条都做"余额不得为负 / 不得花冻结"的校验，失败即整批拒绝（不产生半截 revision）—— 与 {@code
 *       EconomySettlement.applyTransfer} 的两遍式语义同源。
 * </ol>
 *
 * <p>★ <b>两张表都按 canonical key 排序后冻结</b>：同一本账的求和顺序因此是内容的纯函数（1/4/8 线程同序）， 且<strong>绝不用</strong>
 * {@code Map.copyOf}（迭代序不是内容的纯函数）。
 *
 * <p>★ <b>提交序</b>：{@link #order()} 的 {@code canonicalKey} 恒等于账户分区键的 canonical 串；{@code intraIndex}
 * 由产出它的 {@link AccountIntentBuffer} 分配（同一分区的意向计数器；增量取"首次触碰该账"的序号）。
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification =
        "compact constructor 经 freezeSortedGoods/freezeSortedMoney 防御性拷贝并按 canonical key 冻结；SpotBugs 不跨辅助方法识别")
public record AccountDelta(
    CommitOrder order, Map<CommodityId, Long> goods, Map<CurrencyId, Long> money)
    implements OrderedAccountIntent {

  public AccountDelta {
    Objects.requireNonNull(order, "AccountDelta.order 不得为 null");
    goods = freezeSortedGoods(goods);
    money = freezeSortedMoney(money);
  }

  /** 产出点（{@link AccountIntentBuffer} 的唯一入口）：规范串由账户键给出，避免调用方手拼。 */
  public static AccountDelta of(
      AccountPartitionKey key,
      SettlementStage stage,
      int partitionIndex,
      int intraIndex,
      Map<CommodityId, Long> goods,
      Map<CurrencyId, Long> money) {
    Objects.requireNonNull(key, "key");
    return new AccountDelta(
        new CommitOrder(stage, partitionIndex, key.canonical(), intraIndex), goods, money);
  }

  @Override
  public AccountPartitionKey accountKey() {
    return AccountPartitionKey.parseCanonical(order.canonicalKey());
  }

  public boolean isEmpty() {
    return goods.isEmpty() && money.isEmpty();
  }

  public long goodsOf(CommodityId commodity) {
    return goods.getOrDefault(commodity, 0L);
  }

  public long moneyOf(CurrencyId currency) {
    return money.getOrDefault(currency, 0L);
  }

  /**
   * ★★ <b>合并同账户的另一份增量</b>（同一 worker 内使用；跨 worker 由分区规则禁止）。
   *
   * <p>★ <b>M3：返回值是 canonical 排序后的冻结副本</b> —— 即使两张入参表的迭代序不同，本方法也经规范构造器 （{@code
   * freezeSortedGoods}/{@code freezeSortedMoney}）重新按 id 升序冻结；调用方拿到的键序是内容的纯函数。 ★
   * 它<b>不是账户写入口</b>：唯一落账路径仍是 {@link AccountSession#commit}（会话侧也已删除 {@code intentBuffer}
   * 这个第二缓冲入口，worker 缓冲只从 {@code AccountIntentBuffer.on/onHexPartition} 产出）。
   */
  public AccountDelta merge(AccountDelta other) {
    Objects.requireNonNull(other, "other");
    if (!order.canonicalKey().equals(other.order.canonicalKey())) {
      throw new IllegalArgumentException(
          "只能合并同一账户的增量: " + order.canonicalKey() + " vs " + other.order.canonicalKey());
    }
    Map<CommodityId, Long> mergedGoods = new LinkedHashMap<>(goods);
    for (Map.Entry<CommodityId, Long> entry : other.goods.entrySet()) {
      mergedGoods.merge(entry.getKey(), entry.getValue(), Math::addExact);
    }
    Map<CurrencyId, Long> mergedMoney = new LinkedHashMap<>(money);
    for (Map.Entry<CurrencyId, Long> entry : other.money.entrySet()) {
      mergedMoney.merge(entry.getKey(), entry.getValue(), Math::addExact);
    }
    // ★ M3：规范构造器不可绕过 —— 它重新按 canonical id 升序冻结；这里不需要也不允许再手拼一张未排序的表。
    return new AccountDelta(order, mergedGoods, mergedMoney);
  }

  private static Map<CommodityId, Long> freezeSortedGoods(Map<CommodityId, Long> source) {
    List<Map.Entry<CommodityId, Long>> entries = copyEntries(source, "AccountDelta.goods");
    entries.sort(Comparator.comparing(entry -> entry.getKey().value()));
    Map<CommodityId, Long> sorted = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : entries) {
      sorted.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(sorted);
  }

  private static Map<CurrencyId, Long> freezeSortedMoney(Map<CurrencyId, Long> source) {
    List<Map.Entry<CurrencyId, Long>> entries = copyEntries(source, "AccountDelta.money");
    entries.sort(Comparator.comparing(entry -> entry.getKey().value()));
    Map<CurrencyId, Long> sorted = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> entry : entries) {
      sorted.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(sorted);
  }

  private static <K> List<Map.Entry<K, Long>> copyEntries(Map<K, Long> source, String field) {
    if (source == null) {
      throw new IllegalArgumentException(field + " 不得为 null（没有那一腿请给空表）");
    }
    List<Map.Entry<K, Long>> entries = new ArrayList<>(source.size());
    for (Map.Entry<K, Long> entry : source.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(field + " 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() == 0L) {
        continue; // 0 不是一条发生额，落库/求和都不带它
      }
      entries.add(Map.entry(entry.getKey(), entry.getValue()));
    }
    return entries;
  }
}
