package io.mosire.simos.actor.ops;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorLog;
import io.mosire.simos.actor.ActorLogSource;
import io.mosire.simos.actor.model.AvailableStock;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * ★★ <b>家户账户的转移原语</b>（P1.2 后端行政命令；P2-A §13.3 起账户主体统一为家户）：纯函数进 {@link ActorData}、出新 {@link
 * ActorData}，不做任何状态写入；命令 handler 只负责解析载荷、调这里、再由 {@code ActorChangeSet.between} 派生变更集（铁律 5）。
 *
 * <p>★★ <b>P2-A 的形状变化（如实记）</b>：改前有 {@code transfer}（两个 {@code (owner,hex)} 账户）与 {@code move} （按
 * owner 把整本账从一格搬到另一格）两条。现在：
 *
 * <ul>
 *   <li><b>只有 {@link #transfer}</b>：{@code (fromHousehold, toHousehold)} 两本账之间的显式转移；源必须存在且可支配量足够，
 *       目标缺失时按正增量新建；
 *   <li><b>{@code move} 整条退役</b>：账户键不再带 {@code HexCoord}，位置从 {@code Household.location} 派生 ⇒
 *       "把账搬到另一格"这件事在模型里不再存在（家户搬家，账自动跟走）。
 * </ul>
 *
 * <p>★★ <b>为什么溢出必须拒绝而不是截断</b>：余额与冻结额是 {@code long} 定点整数，溢出会静默把一笔巨款变成负数或回绕； 本仓对"静默付 0 /
 * 静默丢字段"同族事故的处置就是 fail-closed。故相加一律走 {@link Math#addExact(long, long)}， 除零/溢出都以 {@link
 * IllegalArgumentException} 面世，由命令边界折成具名拒因。
 *
 * <p>★ <b>冻结语义</b>：只搬运/相加冻结额，<b>绝不</b>把冻结额清零（{@code HouseholdInventory} 的 {@code 0 ≤ 冻结 ≤ 余额}
 * 由构造期把守；源扣减不侵占冻结额由 {@link AvailableStock} 的唯一算法判）。
 */
public final class AccountOperations {

  private static final Logger LOG = ActorLog.account();

  private static final Logger TRACE = ActorLog.trace();

  private AccountOperations() {}

  /**
   * 两个家户账户之间的显式转移：源扣、目标加，源账户必须存在；目标缺失 ⇒ 新建（余额=转入量、冻结表空）。
   *
   * @param goods 正的商品转移量（键序 = 载荷序；不得为 0/负）
   * @param money 正的货币转移量（同上）
   * @throws IllegalArgumentException 源账不存在 / 源可支配量不足 / 源与目标家户相同 / 目标相加溢出
   */
  public static ActorData transfer(
      ActorData base,
      HouseholdId fromHousehold,
      HouseholdId toHousehold,
      Map<CommodityId, Long> goods,
      Map<CurrencyId, Long> money) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(fromHousehold, "fromHousehold");
    Objects.requireNonNull(toHousehold, "toHousehold");
    HouseholdAccountKey fromKey = new HouseholdAccountKey(fromHousehold);
    HouseholdAccountKey toKey = new HouseholdAccountKey(toHousehold);
    if (fromKey.equals(toKey)) {
      throw new IllegalArgumentException("actor.TransferAccounts 的源账户与目标账户相同: " + fromKey);
    }
    HouseholdInventory source = base.accounts().get(fromKey);
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

    Map<HouseholdAccountKey, HouseholdInventory> next = new LinkedHashMap<>(base.accounts());
    // 五参写回：两张冻结表原样带过。
    next.put(
        fromKey,
        new HouseholdInventory(
            fromKey, sourceBalances, sourceMoney, source.frozenBalances(), source.frozenMoney()));

    HouseholdInventory target = base.accounts().get(toKey);
    if (target == null) {
      // 目标缺失 ⇒ 按转入量新建（冻结表空）；键由值派生，走 withInventory 的同一个拼写点。
      if (LOG.isDebugEnabled()) {
        EventLog.channel(LOG)
            .debug(
                LogEvent.of(
                    "ACTOR_TRANSFER_TARGET_ABSENT",
                    ActorLogSource.ACTOR_ACCOUNT,
                    "from",
                    fromKey,
                    "to",
                    toKey,
                    "goods",
                    goods.size(),
                    "money",
                    money.size()));
      }
      next.put(
          toKey,
          new HouseholdInventory(
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
          new HouseholdInventory(
              toKey, targetBalances, targetMoney, target.frozenBalances(), target.frozenMoney()));
    }
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "ACTOR_ACCOUNTS_TRANSFERRED",
                ActorLogSource.ACTOR_ACCOUNT,
                "from",
                fromKey,
                "to",
                toKey,
                "goods",
                goods.size(),
                "money",
                money.size()));
    if (TRACE.isTraceEnabled()) {
      for (Map.Entry<CommodityId, Long> entry : goods.entrySet()) {
        EventLog.channel(TRACE)
            .trace(
                LogEvent.of(
                    "ACTOR_TRANSFER_GOODS",
                    ActorLogSource.ACTOR_ACCOUNT,
                    "from",
                    fromKey,
                    "to",
                    toKey,
                    "commodity",
                    entry.getKey(),
                    "amount",
                    entry.getValue()));
      }
      for (Map.Entry<CurrencyId, Long> entry : money.entrySet()) {
        EventLog.channel(TRACE)
            .trace(
                LogEvent.of(
                    "ACTOR_TRANSFER_MONEY",
                    ActorLogSource.ACTOR_ACCOUNT,
                    "from",
                    fromKey,
                    "to",
                    toKey,
                    "currency",
                    entry.getKey(),
                    "amount",
                    entry.getValue()));
      }
    }
    return base.withInventories(next);
  }

  /** 一处具名的 {@code Math.addExact}：溢出 ⇒ 拒绝，绝不截断/回绕。 */
  private static long addExact(
      long current, long delta, String dimension, String asset, HouseholdAccountKey target) {
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
