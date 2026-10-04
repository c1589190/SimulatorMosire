package io.mosire.simos.actor.ops;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.model.AvailableStock;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.map.hex.HexCoord;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>actor 账户的迁移/转移原语</b>（P1.2 后端行政命令）：纯函数进 {@link ActorData}、出新 {@link ActorData}， 不做任何状态写入；命令
 * handler 只负责解析载荷、调这里、再由 {@code ActorChangeSet.between} 派生变更集（铁律 5）。
 *
 * <p>★★ <b>两条命令的分工</b>：
 *
 * <ul>
 *   <li>{@link #transfer}（{@code actor.TransferAccounts}）：<b>两个不同账户</b>之间的显式转移 —— 从 {@code
 *       (fromOwner, fromHex)} 扣、往 {@code (toOwner, toHex)} 加；源必须存在且可支配量足够，
 *       目标缺失时按正增量新建。它更新的是<b>同一批资产的位置/归属</b>，冻结额不动。
 *   <li>{@link #move}（{@code actor.MoveAccount}）：<b>按 owner 搬整本账</b> —— owner 不变，账键从 {@code (owner,
 *       fromHex)} 改成 {@code (owner, toHex)}；余额与冻结额<b>整本随行</b>；目标已存在 ⇒ 逐商品/逐币种/逐冻结键<b>精确相加</b>，任何一处
 *       {@code long} 溢出 ⇒ 整条拒绝。
 * </ul>
 *
 * <p>★★ <b>为什么溢出必须拒绝而不是截断</b>：余额与冻结额是 {@code long} 定点整数，溢出会静默把一笔巨款变成负数或回绕； 本仓对"静默付 0 /
 * 静默丢字段"同族事故的处置就是 fail-closed。故相加一律走 {@link Math#addExact(long, long)}， 除零/溢出都以 {@link
 * IllegalArgumentException} 面世，由命令边界折成具名拒因。
 *
 * <p>★ <b>冻结语义</b>：两条操作都只搬运/相加冻结额，<b>绝不</b>把冻结额清零（{@code GoodsAccount} 的 {@code 0 ≤ 冻结 ≤ 余额}
 * 由构造期把守；源扣减不侵占冻结额由 {@link AvailableStock} 的唯一算法判）。
 */
public final class AccountOperations {

  private AccountOperations() {}

  /**
   * 两个账户之间的显式转移：源扣、目标加，源账户必须存在；目标缺失 ⇒ 新建（余额=转入量、冻结表空）。
   *
   * @param goods 正的商品转移量（键序 = 载荷序；不得为 0/负）
   * @param money 正的货币转移量（同上）
   * @throws IllegalArgumentException 源账不存在 / 源可支配量不足 / 源与目标键相同 / 目标相加溢出
   */
  public static ActorData transfer(
      ActorData base,
      ActorRef fromOwner,
      HexCoord fromHex,
      ActorRef toOwner,
      HexCoord toHex,
      Map<CommodityId, Long> goods,
      Map<CurrencyId, Long> money) {
    Objects.requireNonNull(base, "base");
    GoodsAccountKey fromKey = new GoodsAccountKey(fromOwner, fromHex);
    GoodsAccountKey toKey = new GoodsAccountKey(toOwner, toHex);
    if (fromKey.equals(toKey)) {
      throw new IllegalArgumentException("actor.TransferAccounts 的源账户与目标账户相同: " + fromKey);
    }
    GoodsAccount source = base.accounts().get(fromKey);
    if (source == null) {
      throw new IllegalArgumentException("源账户不存在: " + fromKey);
    }

    Map<CommodityId, Long> sourceBalances = new LinkedHashMap<>(source.balances());
    for (Map.Entry<CommodityId, Long> entry : goods.entrySet()) {
      CommodityId commodity = entry.getKey();
      long amount = entry.getValue();
      long available = AvailableStock.available(source, commodity);
      if (available < amount) {
        throw new IllegalArgumentException(
            "源账户可支配不足：账户="
                + fromKey
                + "，商品="
                + commodity
                + "，请求="
                + amount
                + "，可支配="
                + available
                + "（可支配 = 余额 − 冻结）");
      }
      sourceBalances.put(commodity, source.balances().getOrDefault(commodity, 0L) - amount);
    }

    Map<CurrencyId, Long> sourceMoney = new LinkedHashMap<>(source.money());
    for (Map.Entry<CurrencyId, Long> entry : money.entrySet()) {
      CurrencyId currency = entry.getKey();
      long amount = entry.getValue();
      long available = AvailableStock.available(source, currency);
      if (available < amount) {
        throw new IllegalArgumentException(
            "源账户可支配不足：账户="
                + fromKey
                + "，货币="
                + currency
                + "，请求="
                + amount
                + "，可支配="
                + available
                + "（可支配 = 余额 − 冻结）");
      }
      sourceMoney.put(currency, source.money().getOrDefault(currency, 0L) - amount);
    }

    Map<GoodsAccountKey, GoodsAccount> next = new LinkedHashMap<>(base.accounts());
    // 五参写回：两张冻结表原样带过。
    next.put(
        fromKey,
        new GoodsAccount(
            fromKey, sourceBalances, sourceMoney, source.frozenBalances(), source.frozenMoney()));

    GoodsAccount target = base.accounts().get(toKey);
    if (target == null) {
      // 目标缺失 ⇒ 按转入量新建（冻结表空）；键由值派生，走 withAccount 的同一个拼写点。
      next.put(
          toKey,
          new GoodsAccount(
              toKey, new LinkedHashMap<>(goods), new LinkedHashMap<>(money), Map.of(), Map.of()));
    } else {
      Map<CommodityId, Long> targetBalances = new LinkedHashMap<>(target.balances());
      for (Map.Entry<CommodityId, Long> entry : goods.entrySet()) {
        CommodityId commodity = entry.getKey();
        long sum =
            addExact(
                targetBalances.getOrDefault(commodity, 0L),
                entry.getValue(),
                "商品",
                commodity.toString(),
                toKey);
        targetBalances.put(commodity, sum);
      }
      Map<CurrencyId, Long> targetMoney = new LinkedHashMap<>(target.money());
      for (Map.Entry<CurrencyId, Long> entry : money.entrySet()) {
        CurrencyId currency = entry.getKey();
        long sum =
            addExact(
                targetMoney.getOrDefault(currency, 0L),
                entry.getValue(),
                "货币",
                currency.toString(),
                toKey);
        targetMoney.put(currency, sum);
      }
      next.put(
          toKey,
          new GoodsAccount(
              toKey, targetBalances, targetMoney, target.frozenBalances(), target.frozenMoney()));
    }
    return base.withAccounts(next);
  }

  /**
   * 按 owner 把整本账从 {@code (owner, fromHex)} 搬到 {@code (owner, toHex)}：余额与冻结额随行； 目标已存在 ⇒
   * 逐键精确相加（商品余额、货币余额、商品冻结、货币冻结四张表都合并），溢出拒。
   *
   * @throws IllegalArgumentException 源账不存在 / 源目标位置相同 / 目标四张表相加溢出
   */
  public static ActorData move(ActorData base, ActorRef owner, HexCoord fromHex, HexCoord toHex) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(fromHex, "fromHex");
    Objects.requireNonNull(toHex, "toHex");
    GoodsAccountKey fromKey = new GoodsAccountKey(owner, fromHex);
    GoodsAccountKey toKey = new GoodsAccountKey(owner, toHex);
    if (fromKey.equals(toKey)) {
      throw new IllegalArgumentException("actor.MoveAccount 的源位置与目标位置相同: " + fromHex);
    }
    GoodsAccount source = base.accounts().get(fromKey);
    if (source == null) {
      throw new IllegalArgumentException("源账户不存在: " + fromKey);
    }
    GoodsAccount target = base.accounts().get(toKey);
    Map<GoodsAccountKey, GoodsAccount> next = new LinkedHashMap<>(base.accounts());
    if (target == null) {
      // 目标缺失：整本账换键，四张表原样（不是复制一部分）。
      next.remove(fromKey);
      next.put(
          toKey,
          new GoodsAccount(
              toKey,
              source.balances(),
              source.money(),
              source.frozenBalances(),
              source.frozenMoney()));
      return base.withAccounts(next);
    }

    Map<CommodityId, Long> balances = new LinkedHashMap<>(target.balances());
    for (Map.Entry<CommodityId, Long> entry : source.balances().entrySet()) {
      CommodityId commodity = entry.getKey();
      balances.put(
          commodity,
          addExact(
              balances.getOrDefault(commodity, 0L),
              entry.getValue(),
              "商品余额",
              commodity.toString(),
              toKey));
    }
    Map<CurrencyId, Long> money = new LinkedHashMap<>(target.money());
    for (Map.Entry<CurrencyId, Long> entry : source.money().entrySet()) {
      CurrencyId currency = entry.getKey();
      money.put(
          currency,
          addExact(
              money.getOrDefault(currency, 0L),
              entry.getValue(),
              "货币余额",
              currency.toString(),
              toKey));
    }
    Map<CommodityId, Long> frozenBalances = new LinkedHashMap<>(target.frozenBalances());
    for (Map.Entry<CommodityId, Long> entry : source.frozenBalances().entrySet()) {
      CommodityId commodity = entry.getKey();
      frozenBalances.put(
          commodity,
          addExact(
              frozenBalances.getOrDefault(commodity, 0L),
              entry.getValue(),
              "商品冻结额",
              commodity.toString(),
              toKey));
    }
    Map<CurrencyId, Long> frozenMoney = new LinkedHashMap<>(target.frozenMoney());
    for (Map.Entry<CurrencyId, Long> entry : source.frozenMoney().entrySet()) {
      CurrencyId currency = entry.getKey();
      frozenMoney.put(
          currency,
          addExact(
              frozenMoney.getOrDefault(currency, 0L),
              entry.getValue(),
              "货币冻结额",
              currency.toString(),
              toKey));
    }
    next.put(toKey, new GoodsAccount(toKey, balances, money, frozenBalances, frozenMoney));
    next.remove(fromKey);
    return base.withAccounts(next);
  }

  /** 一处具名的 {@code Math.addExact}：溢出 ⇒ 拒绝，绝不截断/回绕。 */
  private static long addExact(
      long current, long delta, String dimension, String asset, GoodsAccountKey target) {
    try {
      return Math.addExact(current, delta);
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException(
          "目标账户相加溢出（拒绝截断）：账户="
              + target
              + "，"
              + dimension
              + "="
              + asset
              + "，现有="
              + current
              + "，转入="
              + delta,
          e);
    }
  }
}
