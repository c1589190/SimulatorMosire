package io.mosire.simos.app.time;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.time.ProductionSettlement.ActorEntry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>产权落账</b>（S1 阶段 4+5 Task 5）：把一天/{@code ProductionLedger} 交出来的 {@link ActorEntry} 落到 {@link
 * ActorData#accounts()} 上 —— <b>纯函数、无 IO、不写状态</b>。
 *
 * <p>★★ <b>它为什么住在 {@code simos-app}</b>（铁律 3/4）：产权条目由 <b>economy</b> 产出（{@code
 * ProductionSettlement} 算、 {@code harvest} 收进 ledger），而账本住在 <b>{@code actor}</b> 切片 ——
 * 两个切片<b>互不认识</b> ⇒ 把两者接起来的地方 只能是<b>组合根</b>（设计稿 §8.2 的原文："跨切片的协调器必须住 {@code simos-app}"）。
 *
 * <p>★★ <b>三条口径</b>（每条都有一条用例守着）：
 *
 * <ol>
 *   <li><b>账户键 = (actor, location)，从值派生</b>：{@link GoodsAccountKey} 由条目自己的两段构造，写入走 {@link
 *       ActorData#withAccount}（<b>本类不自己拼键、也不碰那张 Map</b>）—— 否则"键是 (actor, location)"
 *       这件事就有了第二个拼写点，而它写歪<b>不会报错</b>（测试会恒真）；
 *   <li><b>余额只在被写的商品上覆盖</b>：{@code 余额' = 原余额 + delta}，其余商品<b>原样带过</b> ⇒ 同一 actor 在两地的
 *       两本账、以及一本账里的多种商品**互不抹除**（{@link GoodsAccount} 是<b>整本覆盖</b>的写入口，故副本必须先拷全）；
 *   <li>★★ <b>余额不得为负</b>：{@link GoodsAccount} 的构造期守卫已经兜了一层，本类<b>再判一层并抛</b>更早、更可读的错 （消息里带 <b>actor /
 *       商品 / 当前余额 / 本次增减</b>）—— 透支是<b>信用</b>（S2 的领域），不是库存。
 * </ol>
 *
 * <p>★★ <b>为什么"入账序"要保序</b>：条目按 {@code priority} 序产生（同一份产出可能被多条规则分）⇒ <b>先付后收</b>的次序 在账面上看得见。逐条
 * {@code withAccount} 天然保序（{@code put} 保留首次插入位置），故本条不需要额外代码 —— 但它是 <b>约定</b>，写在类注里免得后来者把它改掉。
 *
 * <p>★ <b>0 余额保留</b>（{@link GoodsAccount} 的口径）：收支相抵的账户**照样留在表里**，读口因此读得到"这个人在这一格有一本 （余额 0）的账" ——
 * 那与"这个人不在这格"是两件事。
 */
public final class OwnershipBooks {

  private OwnershipBooks() {}

  /**
   * 把一批产权条目落到账本上（**逐条累加**；{@code base} 一字不改）。
   *
   * @param base 落账前的 actor 状态；不得为 null
   * @param entries 产权条目（毫单位；{@code > 0} 收、{@code < 0} 付）；不得为 null（没有条目请给空表）
   * @return 落账后的新状态（**只有被写到的账户被替换**，其余原样带过）
   * @throws IllegalStateException 某本账的余额会变成负数（见类注第 ③ 条）
   */
  public static ActorData apply(ActorData base, List<ActorEntry> entries) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(entries, "entries");
    ActorData books = base;
    for (ActorEntry entry : entries) {
      GoodsAccountKey key = new GoodsAccountKey(entry.actor(), entry.location());
      GoodsAccount account = books.accounts().get(key);
      // ★ 整本覆盖的写入口 ⇒ 必须先拷全原余额（其余商品原样带过；本账缺席就是一本空账）。
      Map<CommodityId, Long> balances =
          account == null ? new LinkedHashMap<>() : new LinkedHashMap<>(account.balances());
      long before = balances.getOrDefault(entry.commodity(), 0L);
      long after = before + entry.delta();
      if (after < 0L) {
        throw new IllegalStateException(
            "产权账余额不得为负（透支是信用，不是库存）：actor="
                + entry.actor()
                + " 格="
                + entry.location()
                + " 商品="
                + entry.commodity()
                + " 余额 "
                + before
                + " + "
                + entry.delta()
                + " = "
                + after);
      }
      balances.put(entry.commodity(), after);
      books = books.withAccount(new GoodsAccount(key, balances));
    }
    return books;
  }
}
