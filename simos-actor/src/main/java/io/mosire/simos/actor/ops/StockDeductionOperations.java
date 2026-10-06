package io.mosire.simos.actor.ops;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorLog;
import io.mosire.simos.actor.ActorLogSource;
import io.mosire.simos.actor.model.AvailableStock;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.stock.HouseholdStockDeduction;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * ★★ <b>{@code actor.DeductHouseholdStock} 的纯函数落账核</b>（P1.2 通用家户库存扣除；2026-10-09 用户裁定）： 一份 {@link
 * ActorData} 进、新 {@link ActorData} 出，不做任何状态写入；handler 只负责解析载荷、调这里、再由 {@code ActorChangeSet.between}
 * 派生变更集（铁律 5）。
 *
 * <p>★★ <b>它解决什么</b>：在此之前"从家户账上扣一笔"有三种写法（{@code actor.AdjustAccounts} 的有符号净增量、税侧直接 {@code
 * AccountSession.commit}、俸禄 oracle 自己拼负 delta），且都<b>没有 reason</b>。本类让"家户 + 库存 + reason + 可选收款方"
 * 成为唯一形状；税 / 行政俸禄 / 军队俸禄都只给政策与 reason。
 *
 * <p>★★ <b>整条原子</b>：entries 在一份工作副本上<b>按载荷序</b>顺序应用（前一条的收款后一条看得见，与"多单位重叠辖区按序征" 同款），任一条违例即抛、调用方折成整条
 * {@code Rejected} —— <b>不返回半成品</b>，"部分生效"在结构上不可能发生。
 *
 * <p>★★ <b>数值语义（逐条走 {@link AvailableStock} 的唯一算法）</b>：
 *
 * <ul>
 *   <li><b>被扣家户有账</b> ⇒ 逐商品/逐币种判：<b>先</b> {@code 余额 < 请求} ⇒ 具名"余额不足"（带余额/请求）；<b>再</b> {@code 可支配 =
 *       余额 − 冻结 < 请求} ⇒ 具名"侵占冻结"（带余额/冻结/可支配/请求）。两条拒因指向不同的纠正动作；
 *   <li><b>被扣家户没有账</b> ⇒ 拒：有主体行 ⇒ "账户不存在"；连主体行都没有 ⇒ "家户不存在"（见下）；
 *   <li><b>收款方缺席 = 明确 sink</b>（行政俸禄这种"付出即消失"的支出），日志记 {@code to=<sink>}，不静默丢；
 *   <li><b>收款方有账</b> ⇒ 只加余额、冻结表原样带过；<b>收款方没有账但主体行存在</b> ⇒ 按转入量新建（与 {@link
 *       AccountOperations#transfer} 的目标语义逐字同款）；<b>连主体行都没有</b> ⇒ 拒（拒绝为幽灵家户开账）。
 * </ul>
 *
 * <p>★ <b>余额 0 保留、冻结不侵占</b>：扣减把余额写成"减去后的绝对值"（可为 0，保留在表里），冻结表一字不动 ⇒ {@code 0 ≤ 冻结 ≤ 余额}
 * 这条类型不变量不会被本类破坏。
 *
 * <p>★ <b>溢出必须拒绝而不是截断</b>（与 {@code AccountOperations} 同一条纪律）：收款相加走 {@link Math#addExact}，溢出即抛；
 * 扣减方向只会让非负余额变小，不会下溢。
 */
public final class StockDeductionOperations {

  private static final Logger LOG = ActorLog.account();

  private static final Logger TRACE = ActorLog.trace();

  private StockDeductionOperations() {}

  /** 单条扣除的便捷入口（多条请走 {@link #deductAll}）。 */
  public static ActorData deduct(ActorData base, HouseholdStockDeduction deduction) {
    return deductAll(base, List.of(deduction));
  }

  /**
   * 顺序应用一批家户库存扣除，返回新 {@link ActorData}；任一条违例 ⇒ 抛（整条原子，调用方不落任何变更）。
   *
   * @param deductions 扣除条目（载荷序 = 应用序；不得为空）
   * @throws IllegalArgumentException 家户/账户不存在、余额不足、侵占冻结、收款相加溢出、自转
   */
  public static ActorData deductAll(ActorData base, List<HouseholdStockDeduction> deductions) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(deductions, "deductions");
    if (deductions.isEmpty()) {
      throw new IllegalArgumentException("HouseholdStockDeduction 批次不得为空");
    }
    Map<HouseholdAccountKey, HouseholdInventory> next = new LinkedHashMap<>(base.accounts());
    for (HouseholdStockDeduction deduction : deductions) {
      Objects.requireNonNull(deduction, "deductions 的元素不得为 null");
      HouseholdId household = deduction.household();
      HouseholdAccountKey key = new HouseholdAccountKey(household);
      HouseholdInventory source = next.get(key);
      if (source == null) {
        throw new IllegalArgumentException(missingSourceMessage(base, key));
      }

      Map<CommodityId, Long> balances = new LinkedHashMap<>(source.balances());
      for (Map.Entry<CommodityId, Long> leg : deduction.goods().entrySet()) {
        balances.put(leg.getKey(), requireGoods(source, leg.getKey(), leg.getValue(), household));
      }
      Map<CurrencyId, Long> money = new LinkedHashMap<>(source.money());
      for (Map.Entry<CurrencyId, Long> leg : deduction.money().entrySet()) {
        money.put(leg.getKey(), requireMoney(source, leg.getKey(), leg.getValue(), household));
      }
      // ★ 五参写回：两张冻结表原样带过（用便捷构造器会把已有冻结静默清零）。
      next.put(
          key,
          new HouseholdInventory(
              key, balances, money, source.frozenBalances(), source.frozenMoney()));

      deduction.toHousehold().ifPresent(recipient -> credit(next, base, recipient, deduction));

      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "HOUSEHOLD_STOCK_DEDUCTED",
                  ActorLogSource.ACTOR_STOCK,
                  "household",
                  household.value(),
                  "reason",
                  deduction.reason().value(),
                  "detailLength",
                  deduction.detail() == null ? 0 : deduction.detail().length(),
                  "goods",
                  deduction.goods().size(),
                  "money",
                  deduction.money().size(),
                  "to",
                  deduction.toHousehold().map(HouseholdId::value).orElse("<sink>")));
      if (TRACE.isTraceEnabled()) {
        for (Map.Entry<CommodityId, Long> leg : deduction.goods().entrySet()) {
          EventLog.channel(TRACE)
              .trace(
                  LogEvent.of(
                      "HOUSEHOLD_STOCK_DEDUCTED_GOODS",
                      ActorLogSource.ACTOR_STOCK,
                      "household",
                      household.value(),
                      "reason",
                      deduction.reason().value(),
                      "commodity",
                      leg.getKey(),
                      "amount",
                      leg.getValue()));
        }
        for (Map.Entry<CurrencyId, Long> leg : deduction.money().entrySet()) {
          EventLog.channel(TRACE)
              .trace(
                  LogEvent.of(
                      "HOUSEHOLD_STOCK_DEDUCTED_MONEY",
                      ActorLogSource.ACTOR_STOCK,
                      "household",
                      household.value(),
                      "reason",
                      deduction.reason().value(),
                      "currency",
                      leg.getKey(),
                      "amount",
                      leg.getValue()));
        }
      }
    }
    return base.withInventories(next);
  }

  /** 收款腿：有账 ⇒ 只加余额（冻结表原样带过、溢出即抛）；没有账但主体行存在 ⇒ 按转入量新建（冻结空表）； 连主体行都没有 ⇒ 拒（不成幽灵账）。 */
  private static void credit(
      Map<HouseholdAccountKey, HouseholdInventory> next,
      ActorData base,
      HouseholdId recipient,
      HouseholdStockDeduction deduction) {
    if (recipient.equals(deduction.household())) {
      throw new IllegalArgumentException("扣除的收款家户不得等于被扣家户（自转不是一条发生额）: " + recipient);
    }
    HouseholdAccountKey key = new HouseholdAccountKey(recipient);
    HouseholdInventory target = next.get(key);
    if (target == null) {
      if (!hasSubjectRow(base, recipient)) {
        throw new IllegalArgumentException("收款家户不存在：actor 切片里没有主体行也没有账户: " + recipient);
      }
      next.put(
          key,
          new HouseholdInventory(
              key,
              new LinkedHashMap<>(deduction.goods()),
              new LinkedHashMap<>(deduction.money()),
              Map.of(),
              Map.of()));
      return;
    }
    Map<CommodityId, Long> balances = new LinkedHashMap<>(target.balances());
    for (Map.Entry<CommodityId, Long> leg : deduction.goods().entrySet()) {
      balances.put(
          leg.getKey(),
          addExact(
              balances.getOrDefault(leg.getKey(), 0L),
              leg.getValue(),
              deduction,
              "商品",
              leg.getKey().toString()));
    }
    Map<CurrencyId, Long> money = new LinkedHashMap<>(target.money());
    for (Map.Entry<CurrencyId, Long> leg : deduction.money().entrySet()) {
      money.put(
          leg.getKey(),
          addExact(
              money.getOrDefault(leg.getKey(), 0L),
              leg.getValue(),
              deduction,
              "货币",
              leg.getKey().toString()));
    }
    next.put(
        key,
        new HouseholdInventory(
            key, balances, money, target.frozenBalances(), target.frozenMoney()));
  }

  /** 被扣家户没有账时的具名拒（区分"有主体行但没开账"与"连主体行都没有"）。 */
  private static String missingSourceMessage(ActorData base, HouseholdAccountKey key) {
    if (hasSubjectRow(base, key.household())) {
      return "账户不存在：家户 " + key.household() + " 在 actor 切片里有主体行、但没有账户（拒绝为扣除凭空开账）";
    }
    return "家户不存在：actor 切片里既没有主体行也没有账户: " + key.household();
  }

  /** 主体行是否存在（家户身份由 {@link HouseholdActors} 唯一派生；有账但没主体行的政府家户不算缺失）。 */
  private static boolean hasSubjectRow(ActorData base, HouseholdId household) {
    return base.actors().containsKey(HouseholdActors.of(household));
  }

  /** 商品腿的足量判据：<b>先</b>余额、<b>再</b>可支配（{@link AvailableStock} 的唯一算法）；返回扣除后的新余额。 */
  private static long requireGoods(
      HouseholdInventory inventory, CommodityId commodity, long amount, HouseholdId household) {
    return requireAvailable(
        household,
        "商品",
        commodity,
        inventory.balances().getOrDefault(commodity, 0L),
        inventory.frozenBalances().getOrDefault(commodity, 0L),
        AvailableStock.available(inventory, commodity),
        amount);
  }

  /** 货币腿：口径与 {@link #requireGoods} 逐条同款（商品与货币是两个独立身份、同一套算术）。 */
  private static long requireMoney(
      HouseholdInventory inventory, CurrencyId currency, long amount, HouseholdId household) {
    return requireAvailable(
        household,
        "货币",
        currency,
        inventory.money().getOrDefault(currency, 0L),
        inventory.frozenMoney().getOrDefault(currency, 0L),
        AvailableStock.available(inventory, currency),
        amount);
  }

  /** 两条具名拒（顺序刻意：先"余额不足"、再"侵占冻结"，两条拒因指向不同的纠正动作）。 */
  private static long requireAvailable(
      HouseholdId household,
      String dimension,
      Object asset,
      long balance,
      long frozen,
      long available,
      long amount) {
    if (balance < amount) {
      throw new IllegalArgumentException(
          "余额不足：household="
              + household
              + "，"
              + dimension
              + " "
              + asset
              + " 余额="
              + balance
              + "，请求="
              + amount);
    }
    if (available < amount) {
      throw new IllegalArgumentException(
          "侵占冻结：household="
              + household
              + "，"
              + dimension
              + " "
              + asset
              + " 余额="
              + balance
              + "，冻结="
              + frozen
              + "，可支配="
              + available
              + "，请求="
              + amount
              + "（可支配 = 余额 − 冻结，冻结部分不可动用）");
    }
    return balance - amount;
  }

  /** 收款相加的 {@link Math#addExact}：溢出 ⇒ 拒绝，绝不截断/回绕。 */
  private static long addExact(
      long current, long delta, HouseholdStockDeduction deduction, String dimension, String asset) {
    try {
      return Math.addExact(current, delta);
    } catch (ArithmeticException overflow) {
      throw new IllegalArgumentException(
          "收款账户相加溢出（拒绝截断）：household="
              + deduction.household()
              + "，收款家户="
              + deduction.toHousehold().orElseThrow()
              + "，"
              + dimension
              + "="
              + asset
              + "，现有="
              + current
              + "，转入="
              + delta,
          overflow);
    }
  }
}
