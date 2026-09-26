package io.mosire.simos.ledger.model;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.AccountId;
import io.mosire.simos.economy.api.id.CommodityId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 账本账户（增量 2 spec §3 逐字）：**库存 + 货币**两本账，外加"已被订单/生产锁定"的预留量。
 *
 * <p>★ **库存与货币余额始终以 ledger 为准**（设计稿 §2）：市场/生产只引用 {@link AccountId}，不另存余额。 国库 = 政府持有的账户（{@code owner
 * = ActorRef(GOVERNMENT, …)}），**不在 government 切片另存**。
 *
 * <p>★ **量纲**：{@code goods}/{@code reserved} 按**最小计量单位**（整数）；{@code money}/{@code reservedMoney}
 * 按**最小币值**（整数）。**禁 {@code double} 决定钱/粮**（spec §〇）。
 *
 * <p>★ 不变量（spec §3 逐字，全部在构造期判）：{@code goods} 逐商品 {@code ≥ 0}；{@code reserved ≤ goods} （逐商品，含"goods
 * 里没有这个商品 ⇒ 不得预留"）；{@code money ≥ 0}；{@code reservedMoney ≤ money}。 "预留得负"虽不在原文，但负预留会让上一条恒真 ⇒
 * 一并判掉（同一条不变量的下界）。
 *
 * <p>★ 两张表都**保序不可变**：{@code LinkedHashMap} + {@code Collections.unmodifiableMap}，**绝不用 {@code
 * Map.copyOf}**——迭代序不是内容的纯函数，会产出不同字节。冻结那一步**写在字段赋值处**（SpotBugs 的 {@code EI_EXPOSE_REP}
 * 不做跨过程分析，只认它看得见的包装）。
 *
 * @param id 稳定身份
 * @param owner 账户持有人（种类 + 稳定 id；国库是 {@code GOVERNMENT} 那一档）
 * @param goods 库存（最小计量单位）；键与值都不得为 null，逐值 ≥ 0
 * @param reserved 已被订单/生产锁定的部分；**逐商品 ≤ {@code goods}**
 * @param money 货币（最小币值）；不得为负
 * @param reservedMoney 已被锁定的货币；**≤ {@code money}**
 */
public record Account(
    AccountId id,
    ActorRef owner,
    Map<CommodityId, Long> goods,
    Map<CommodityId, Long> reserved,
    long money,
    long reservedMoney) {

  public Account {
    if (id == null) {
      throw new IllegalArgumentException("Account.id 不得为 null");
    }
    if (owner == null) {
      throw new IllegalArgumentException("Account.owner 不得为 null");
    }
    if (goods == null) {
      throw new IllegalArgumentException("Account.goods 不得为 null");
    }
    if (reserved == null) {
      throw new IllegalArgumentException("Account.reserved 不得为 null");
    }
    Map<CommodityId, Long> goodsCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : goods.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("Account.goods 的键与值都不得为 null: " + entry.getKey());
      }
      goodsCopy.put(entry.getKey(), entry.getValue());
    }
    goods = Collections.unmodifiableMap(goodsCopy); // ★ 冻在赋值处（EI_EXPOSE_REP 只认它看得见的）
    Map<CommodityId, Long> reservedCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : reserved.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("Account.reserved 的键与值都不得为 null: " + entry.getKey());
      }
      reservedCopy.put(entry.getKey(), entry.getValue());
    }
    reserved = Collections.unmodifiableMap(reservedCopy); // ★ 冻在赋值处，同上
    for (Map.Entry<CommodityId, Long> entry : goods.entrySet()) {
      if (entry.getValue() < 0) {
        throw new IllegalArgumentException(
            "Account.goods 的库存不得为负：商品 " + entry.getKey() + " = " + entry.getValue());
      }
    }
    for (Map.Entry<CommodityId, Long> entry : reserved.entrySet()) {
      long locked = entry.getValue();
      if (locked < 0) {
        throw new IllegalArgumentException(
            "Account.reserved 不得为负：商品 " + entry.getKey() + " = " + locked);
      }
      long stock = goods.getOrDefault(entry.getKey(), 0L);
      if (locked > stock) {
        throw new IllegalArgumentException(
            "Account.reserved 不得超过 goods：商品 "
                + entry.getKey()
                + " 的 reserved="
                + locked
                + " > goods="
                + stock);
      }
    }
    if (money < 0) {
      throw new IllegalArgumentException("Account.money 不得为负: " + money);
    }
    if (reservedMoney < 0) {
      throw new IllegalArgumentException("Account.reservedMoney 不得为负: " + reservedMoney);
    }
    if (reservedMoney > money) {
      throw new IllegalArgumentException(
          "Account.reservedMoney 不得超过 money：reservedMoney=" + reservedMoney + " > money=" + money);
    }
  }
}
