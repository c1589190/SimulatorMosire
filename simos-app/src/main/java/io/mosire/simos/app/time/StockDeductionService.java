package io.mosire.simos.app.time;

import io.mosire.simos.actor.model.AvailableStock;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.stock.HouseholdStockDeduction;
import io.mosire.simos.economy.time.AccountDelta;
import io.mosire.simos.economy.time.AccountPartitionKey;
import io.mosire.simos.economy.time.AccountSession;
import io.mosire.simos.economy.time.SettlementStage;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;

/**
 * ★★ <b>{@code AccountSession} 结算路径的共享家户库存扣除服务</b>（2026-10-09 用户裁定"通用扣除接口"的会话侧落点）： {@code
 * JurisdictionDailyTax} 与 {@code GovernmentUpkeepOracle} 不再各自拼 {@link AccountDelta} + 直接 {@code
 * AccountSession.commit}，而是"构造 {@link HouseholdStockDeduction} + 调本服务"。
 *
 * <p>★★ <b>为什么在 app 而不是 economy 模块</b>（实现裁定，如实记）：本服务要同时看见 <b>economy 的 {@code
 * AccountSession}</b>（唯一落账口）与 <b>actor 的 {@code HouseholdInventory} / {@code
 * AvailableStock}</b>（唯一"余额 − 冻结" 算法）。{@code simos-economy} 的 enforcer 只允许 {@code
 * actor-api}（契约），看不见 {@code simos-actor} 的实现类型 ⇒ 把服务塞进 economy 就只能自己再写一遍减法，正是本次要消灭的"第二处拼写点"。app
 * 是唯一同时认识两片的组合根（五条铁律 3/4），故落在这里。
 *
 * <p>★★ <b>一条 {@link HouseholdStockDeduction} 的完整语义（整批原子）</b>：
 *
 * <ol>
 *   <li><b>被扣家户</b>：必须在会话里<b>已登记</b>（{@code householdKeyOf != null}）⇒ 否则具名"家户不存在"；登记过但没有账户 ⇒
 *       具名"账户不存在" （防御性，登记与账户 1:1）；
 *   <li><b>逐腿足量</b>：<b>先</b> {@code 余额 < 请求} ⇒ "余额不足"；<b>再</b> {@code 可支配 = 余额 − 冻结 < 请求} ⇒
 *       "侵占冻结"（走 {@link AvailableStock} 唯一算法）；
 *   <li><b>收款方</b>：{@code toHousehold} 缺席 = 明确 sink（日志 {@code to=<sink>}）；给出 ⇒
 *       原子转移，收款家户必须<b>已登记</b> 且有账（本服务<b>不</b>在结算中途造账 —— 会话登记是装载期的事）；
 *   <li><b>整批先校验、后一次 commit</b>：所有条目在影子副本上按列表序顺序演算（前一条的收款后一条看得见），<b>全部通过</b>才把逐户净增量 一次交给 {@link
 *       AccountSession#commit}。⇒ 任一违例在 commit 之前就抛出，<b>不允许部分生效</b>；
 *   <li><b>提交序确定</b>：净增量按家户 canonical 升序产出一条 {@link AccountDelta}（{@code intraIndex} = 该序），与 1/4/8
 *       线程的稳定提交 序同源。
 * </ol>
 *
 * <p>★★ <b>可用量只有一处算法</b>：影子账户用 {@link HouseholdInventory} 视图承载，减法走 {@link
 * AvailableStock#available}；本类 <b>不</b>内联 {@code balances - frozen}。冻结表原样带过（扣减只动余额）。
 *
 * <p>★ <b>日志</b>（AGENTS §一.9）：TRACE = 逐条 {@code event=HOUSEHOLD_STOCK_DEDUCTED household=… reason=…
 * detailLength=…} （与 actor 命令侧同一条事件名，便于跨模块 grep）；批量调用 INFO 一条"发生了什么 + 具名计数"；单条调用 DEBUG 一条；拒绝路径
 * INFO（用户规则：被拒绝一律 INFO）。⇒ 默认 INFO 下税 / 俸禄不逐户刷屏（阶段级 INFO 由 {@code TAX_DAILY_END} / {@code
 * GOV_DAILY_END} 负责）。
 */
public final class StockDeductionService {

  /** 本批税 / 行政俸禄 / 军俸都发生在日结算之后 ⇒ 沿用既有提交阶段（见 {@link SettlementStage#TAX_AND_UPKEEP}）。 */
  public static final SettlementStage DEFAULT_STAGE = SettlementStage.TAX_AND_UPKEEP;

  private static final Logger LOG = AppLog.time();

  private static final Logger TRACE = AppLog.trace();

  private StockDeductionService() {}

  /** 单条扣除（多条请走 {@link #deductAll}）。 */
  public static void deduct(AccountSession accounts, HouseholdStockDeduction deduction, long day) {
    Objects.requireNonNull(deduction, "deduction");
    deductAll(accounts, List.of(deduction), DEFAULT_STAGE, day);
  }

  /** 一批扣除：整批先校验、后一次 commit（不允许部分生效）。 */
  public static void deductAll(
      AccountSession accounts, Collection<HouseholdStockDeduction> deductions, long day) {
    deductAll(accounts, deductions, DEFAULT_STAGE, day);
  }

  /**
   * 一批扣除（显式提交阶段）：整批先校验、后一次 {@link AccountSession#commit}。
   *
   * @param deductions 扣除条目（列表序 = 应用序；不得为空、不得含 null）
   * @param day 本次扣除所属的世界日（只进日志字段；不参与任何判定/落账）
   * @throws IllegalArgumentException 家户不存在 / 账户不存在 / 余额不足 / 侵占冻结 / 自转 / 收款相加溢出
   */
  public static void deductAll(
      AccountSession accounts,
      Collection<HouseholdStockDeduction> deductions,
      SettlementStage stage,
      long day) {
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(deductions, "deductions");
    Objects.requireNonNull(stage, "stage");
    accounts.checkCoordinatorThread();
    if (deductions.isEmpty()) {
      throw reject("家户库存扣除批次不得为空", day);
    }
    // ★ 影子副本：本次调用演练出的终值（活表只读，commit 之前一个字节都不改）。
    Map<HouseholdId, HouseholdInventory> shadow = new LinkedHashMap<>();
    List<String> reasons = new ArrayList<>();
    for (HouseholdStockDeduction deduction : deductions) {
      Objects.requireNonNull(deduction, "deductions 的元素不得为 null");
      HouseholdInventory source = shadowInventory(accounts, shadow, deduction.household(), day);
      deduct(shadow, source, deduction, day);
      deduction
          .toHousehold()
          .ifPresent(recipient -> credit(accounts, shadow, recipient, deduction, day));
      reasons.add(deduction.reason().value());
      if (TRACE.isTraceEnabled()) {
        EventLog.channel(TRACE)
            .trace(
                LogEvent.of(
                    "HOUSEHOLD_STOCK_DEDUCTED",
                    AppLogSource.DAILY_LOOP,
                    "day",
                    day,
                    "household",
                    deduction.household().value(),
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
      }
    }
    List<AccountDelta> deltas = netDeltas(accounts, shadow, stage, day);
    if (!deltas.isEmpty()) {
      accounts.commit(deltas);
    }
    // ★ 日志级别（AGENTS §一.9 的"逐笔 vs 阶段"分工）：单条扣除的逐笔事实在 TRACE；这里只在批量（如军队俸禄计划）
    //   补一条 INFO"发生了什么 + 具名计数"；单条调用补一条 DEBUG（税 / 俸禄的阶段级 INFO 由各自 phase 负责：
    //   TAX_DAILY_END / GOV_DAILY_END / GOV_ADMIN_ADVANCE_END）。⇒ 默认 INFO 下不会逐户刷屏。
    if (deductions.size() > 1) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "HOUSEHOLD_STOCK_DEDUCTED",
                  AppLogSource.DAILY_LOOP,
                  "day",
                  day,
                  "entries",
                  deductions.size(),
                  "households",
                  shadow.size(),
                  "reasons",
                  new LinkedHashSet<>(reasons),
                  "netAccounts",
                  deltas.size()));
    } else if (LOG.isDebugEnabled()) {
      HouseholdStockDeduction only = deductions.iterator().next();
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "HOUSEHOLD_STOCK_DEDUCTED",
                  AppLogSource.DAILY_LOOP,
                  "day",
                  day,
                  "household",
                  only.household().value(),
                  "reason",
                  only.reason().value(),
                  "detailLength",
                  only.detail() == null ? 0 : only.detail().length(),
                  "to",
                  only.toHousehold().map(HouseholdId::value).orElse("<sink>"),
                  "netAccounts",
                  deltas.size()));
    }
  }

  /** 扣减被扣家户（在影子上）：逐腿两条具名拒（先余额、再冻结），完成后把新账写进影子（冻结表原样带过、余额 0 保留）。 */
  private static void deduct(
      Map<HouseholdId, HouseholdInventory> shadow,
      HouseholdInventory source,
      HouseholdStockDeduction deduction,
      long day) {
    Map<CommodityId, Long> balances = new LinkedHashMap<>(source.balances());
    for (Map.Entry<CommodityId, Long> leg : deduction.goods().entrySet()) {
      balances.put(
          leg.getKey(),
          requireAvailableGoods(source, leg.getKey(), leg.getValue(), deduction.household(), day));
    }
    Map<CurrencyId, Long> money = new LinkedHashMap<>(source.money());
    for (Map.Entry<CurrencyId, Long> leg : deduction.money().entrySet()) {
      money.put(
          leg.getKey(),
          requireAvailableMoney(source, leg.getKey(), leg.getValue(), deduction.household(), day));
    }
    shadow.put(
        deduction.household(),
        new HouseholdInventory(
            source.key(), balances, money, source.frozenBalances(), source.frozenMoney()));
  }

  /** 收款腿：收款家户必须已登记且有账（不在结算中途造账）；只加余额、冻结原样带过，溢出即拒。 */
  private static void credit(
      AccountSession accounts,
      Map<HouseholdId, HouseholdInventory> shadow,
      HouseholdId recipient,
      HouseholdStockDeduction deduction,
      long day) {
    if (recipient.equals(deduction.household())) {
      throw reject("扣除的收款家户不得等于被扣家户（自转不是一条发生额）: " + recipient, day);
    }
    HouseholdInventory target = shadowInventory(accounts, shadow, recipient, day);
    Map<CommodityId, Long> balances = new LinkedHashMap<>(target.balances());
    for (Map.Entry<CommodityId, Long> leg : deduction.goods().entrySet()) {
      balances.put(
          leg.getKey(),
          addExact(
              balances.getOrDefault(leg.getKey(), 0L),
              leg.getValue(),
              recipient,
              "商品",
              leg.getKey().toString(),
              day));
    }
    Map<CurrencyId, Long> money = new LinkedHashMap<>(target.money());
    for (Map.Entry<CurrencyId, Long> leg : deduction.money().entrySet()) {
      money.put(
          leg.getKey(),
          addExact(
              money.getOrDefault(leg.getKey(), 0L),
              leg.getValue(),
              recipient,
              "货币",
              leg.getKey().toString(),
              day));
    }
    shadow.put(
        recipient,
        new HouseholdInventory(
            target.key(), balances, money, target.frozenBalances(), target.frozenMoney()));
  }

  /**
   * 影子账户：已登记的家户 → 当前活表的只读 {@link HouseholdInventory} 视图（首次触碰时从活表抄一份，之后看影子）。
   *
   * <p>★ 只读活表、不写活表：影子的所有变更都留在本方法的内存表里，直到一次 {@code commit}。
   */
  private static HouseholdInventory shadowInventory(
      AccountSession accounts,
      Map<HouseholdId, HouseholdInventory> shadow,
      HouseholdId household,
      long day) {
    HouseholdInventory cached = shadow.get(household);
    if (cached != null) {
      return cached;
    }
    if (accounts.householdKeyOf(household) == null) {
      throw reject("家户不存在：账户会话里没有登记该家户（拒绝在结算中途造账）: household=" + household.value(), day);
    }
    AccountSession.ActorAccount live = accounts.householdAccount(household);
    if (live == null) {
      throw reject("账户不存在：家户已登记但没有账户（状态损坏）: household=" + household.value(), day);
    }
    HouseholdInventory view =
        new HouseholdInventory(
            new HouseholdAccountKey(household),
            live.goods(),
            live.money(),
            live.frozenGoods(),
            live.frozenMoney());
    shadow.put(household, view);
    return view;
  }

  /** 商品腿的足量判据：<b>先</b>余额、<b>再</b>可支配（{@link AvailableStock} 的唯一算法）；返回扣除后的新余额。 */
  private static long requireAvailableGoods(
      HouseholdInventory inventory,
      CommodityId commodity,
      long amount,
      HouseholdId household,
      long day) {
    return requireAvailable(
        household,
        "商品",
        commodity,
        inventory.balances().getOrDefault(commodity, 0L),
        inventory.frozenBalances().getOrDefault(commodity, 0L),
        AvailableStock.available(inventory, commodity),
        amount,
        day);
  }

  /** 货币腿：口径与商品腿逐条同款（同一个算式，两张表各走对应重载）。 */
  private static long requireAvailableMoney(
      HouseholdInventory inventory,
      CurrencyId currency,
      long amount,
      HouseholdId household,
      long day) {
    return requireAvailable(
        household,
        "货币",
        currency,
        inventory.money().getOrDefault(currency, 0L),
        inventory.frozenMoney().getOrDefault(currency, 0L),
        AvailableStock.available(inventory, currency),
        amount,
        day);
  }

  /** 两条具名拒（顺序刻意：先"余额不足"、再"侵占冻结"，两条拒因指向不同的纠正动作）。 */
  private static long requireAvailable(
      HouseholdId household,
      String dimension,
      Object asset,
      long balance,
      long frozen,
      long available,
      long amount,
      long day) {
    if (balance < amount) {
      throw reject(
          "余额不足：household="
              + household
              + "，"
              + dimension
              + " "
              + asset
              + " 余额="
              + balance
              + "，请求="
              + amount,
          day);
    }
    if (available < amount) {
      throw reject(
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
              + "（可支配 = 余额 − 冻结，冻结部分不可动用）",
          day);
    }
    return balance - amount;
  }

  /**
   * 影子终值 vs 活表原值 → 逐户净增量（按家户 canonical 升序，{@code intraIndex} = 该序）。
   *
   * <p>★ 净增量是<b>一次</b> commit 的形状：同一家户在批次里出现多次（或被扣又被收款）只产出一条 delta，避免 {@code
   * SettlementExecutor.commit} 的并列提交键。
   */
  private static List<AccountDelta> netDeltas(
      AccountSession accounts,
      Map<HouseholdId, HouseholdInventory> shadow,
      SettlementStage stage,
      long day) {
    List<HouseholdId> ordered = new ArrayList<>(shadow.keySet());
    ordered.sort(Comparator.comparing(HouseholdId::value));
    List<AccountDelta> deltas = new ArrayList<>(ordered.size());
    int intraIndex = 0;
    for (HouseholdId household : ordered) {
      AccountSession.ActorAccount live = accounts.householdAccount(household);
      if (live == null) {
        throw reject("账户不存在：家户已登记但没有账户（状态损坏）: household=" + household.value(), day);
      }
      HouseholdInventory target = shadow.get(household);
      Map<CommodityId, Long> goodsDelta = goodsDelta(live, target);
      Map<CurrencyId, Long> moneyDelta = moneyDelta(live, target);
      if (goodsDelta.isEmpty() && moneyDelta.isEmpty()) {
        continue; // 净零：不产生一条"什么都没发生"的提交
      }
      deltas.add(
          AccountDelta.of(
              new AccountPartitionKey(household), stage, 0, intraIndex, goodsDelta, moneyDelta));
      intraIndex++;
    }
    return deltas;
  }

  /** 商品净增量：键的并集（保序：活表键序在前、影子新增键接后），只保留非 0。 */
  private static Map<CommodityId, Long> goodsDelta(
      AccountSession.ActorAccount live, HouseholdInventory target) {
    Map<CommodityId, Long> delta = new LinkedHashMap<>();
    Set<CommodityId> keys = new LinkedHashSet<>(live.goods().keySet());
    keys.addAll(target.balances().keySet());
    for (CommodityId key : keys) {
      long value = target.balances().getOrDefault(key, 0L) - live.goods().getOrDefault(key, 0L);
      if (value != 0L) {
        delta.put(key, value);
      }
    }
    return delta;
  }

  /** 货币净增量：口径与 {@link #goodsDelta} 逐条同款。 */
  private static Map<CurrencyId, Long> moneyDelta(
      AccountSession.ActorAccount live, HouseholdInventory target) {
    Map<CurrencyId, Long> delta = new LinkedHashMap<>();
    Set<CurrencyId> keys = new LinkedHashSet<>(live.money().keySet());
    keys.addAll(target.money().keySet());
    for (CurrencyId key : keys) {
      long value = target.money().getOrDefault(key, 0L) - live.money().getOrDefault(key, 0L);
      if (value != 0L) {
        delta.put(key, value);
      }
    }
    return delta;
  }

  /** 收款相加的 {@link Math#addExact}：溢出 ⇒ 拒绝，绝不截断/回绕。 */
  private static long addExact(
      long current, long delta, HouseholdId recipient, String dimension, String asset, long day) {
    try {
      return Math.addExact(current, delta);
    } catch (ArithmeticException overflow) {
      throw reject(
          "收款账户相加溢出（拒绝截断）：收款家户="
              + recipient
              + "，"
              + dimension
              + "="
              + asset
              + "，现有="
              + current
              + "，转入="
              + delta,
          day);
    }
  }

  /** 具名拒的唯一样子：先 INFO 记"为什么"（用户规则：被拒绝一律 INFO），再抛 {@link IllegalArgumentException}（拒绝路径不静默）。 */
  private static IllegalArgumentException reject(String message, long day) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "HOUSEHOLD_STOCK_DEDUCTION_REJECTED",
                AppLogSource.DAILY_LOOP,
                "day",
                day,
                "reason",
                logReason(message)));
    return new IllegalArgumentException(message);
  }

  /**
   * ★ <b>日志安全的拒绝理由</b>（照 L2 的 {@code logReason} 形态）：只保留可读前缀——截到第一个 JSON 起始符/换行；{@code payload ...}
   * 这一类原始文本消息再截到冒号，避免把载荷原文带进日志。截断只影响日志文本，不影响异常本身。
   */
  private static String logReason(String message) {
    if (message == null || message.isBlank()) {
      return "unknown";
    }
    String text = message.strip();
    int cut = text.length();
    for (char marker : new char[] {'{', '[', '\n', '\r'}) {
      int at = text.indexOf(marker);
      if (at >= 0 && at < cut) {
        cut = at;
      }
    }
    if (text.startsWith("payload ")) {
      int colon = text.indexOf(':');
      if (colon >= 0 && colon < cut) {
        cut = colon;
      }
    }
    String reason = text.substring(0, cut).strip();
    return reason.isEmpty() ? "unknown" : reason;
  }
}
