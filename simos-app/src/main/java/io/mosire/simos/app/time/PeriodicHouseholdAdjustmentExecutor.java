package io.mosire.simos.app.time;

import io.mosire.simos.actor.model.AvailableStock;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.stock.HouseholdPeriodicAdjustment;
import io.mosire.simos.economy.api.stock.HouseholdStockDeduction;
import io.mosire.simos.economy.api.stock.PeriodicHouseholdAdjustmentId;
import io.mosire.simos.economy.time.AccountSession;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;

/**
 * ★★ <b>周期家户库存扣增执行器（P4a；无状态）</b>：给定规则表、账户会话与世界日，挑出当天到期的规则、按可用量 <b>部分支付</b>，并复用 {@link
 * StockDeductionService}（{@code TAX_AND_UPKEEP} 阶段）落账。
 *
 * <p>★★ <b>到期判据只有规则字段与绝对世界日</b>：
 *
 * <pre>
 * due(rule, day) := day &gt;= startsOnDay
 *                &amp;&amp; (expiresOnDay 为空 || day &lt;= expiresOnDay)
 *                &amp;&amp; ((day - startsOnDay) % periodDays == phaseDay)
 * </pre>
 *
 * <p>没有 {@code lastPaidTick}、没有进度状态 ⇒ 同一份状态在“一次推 N 天”与“N 次推 1 天”两条路径下同日到期， 最终账户逐值相等（1×N == N×1
 * 由调用方集成验证）。
 *
 * <p>★★ <b>失败语义（C+PARTIAL）</b>：
 *
 * <ul>
 *   <li>payer/payee 账户未登记 ⇒ 具名 gap、跳过该规则，不阻断其它规则；
 *   <li>逐腿 {@code paid = min(requested, available)}；{@code paid ≤ 0} 的腿不进 deduction，缺额只进读数；
 *   <li>所有腿都无可支付量 ⇒ 不调服务；
 *   <li>服务因防御性原因拒绝（具名 {@link IllegalArgumentException}）⇒ 记 gap、继续；不把一次 advance 打红。
 * </ul>
 *
 * <p>★ 本类只读 {@code EconomyData.periodicAdjustments} 与 {@code AccountSession}；规则不落账户，也不在日结算里
 * 改写任何经济持久状态。日志走 {@link EconomyLog#settlement()} / {@link EconomyLog#trace()}，不新开门面。
 */
public final class PeriodicHouseholdAdjustmentExecutor {

  private static final Logger LOG = EconomyLog.settlement();
  private static final Logger TRACE = EconomyLog.trace();

  private PeriodicHouseholdAdjustmentExecutor() {}

  /**
   * 旧签名（兼容，P4a 调用点/夹具照旧）：只执行 {@code EconomyData.periodicAdjustments} 持久规则表；语义逐字保留，
   * 现委托给“无额外瞬态规则”的内部同一实现。
   *
   * @param rules 规则表（{@code EconomyData.periodicAdjustments()}；键 == 值内 id 已由状态构造期把守）
   * @param accounts 唯一账户会话（协调器线程；本方法就地调 {@link StockDeductionService#deduct}）
   * @param day 绝对世界日（与 {@code startsOnDay}/{@code phaseDay} 同量纲）
   * @return 当天读数（汇总 + 逐规则；空规则 ⇒ 全 0）
   */
  public static Report applyDue(
      Map<PeriodicHouseholdAdjustmentId, HouseholdPeriodicAdjustment> rules,
      AccountSession accounts,
      long day) {
    Objects.requireNonNull(rules, "rules");
    Objects.requireNonNull(accounts, "accounts");
    return applyDueRules(rules.values(), accounts, day);
  }

  /**
   * ★★ <b>P4b 新入口：额外瞬态规则</b>（军俸政策每日派生的规则）与 {@code economy} 的持久规则合并后按同一套 due/部分支付语义执行。
   *
   * <p>★★ <b>同 id 冲突策略 = 持久规则优先</b>：{@code extraRules} 里与持久规则同 id 的条目被<b>忽略（不抛）</b>。理由：
   * 持久规则是命令面显式注册的权威；extraRules 是每天重算的派生件（军俸桥接）， 让派生件覆盖会把 GM 的显式规则在某个 unitId 撞名时悄悄改写。忽略是静默的，但方向
   * fail-closed（保留显式权威），且派生的同名规则下一轮仍会生成、不存在“丢一条政策”的累积漂移。 ★ {@code extraRules} <b>内部</b>重复 id 是编程错误
   * ⇒ 具名 {@link IllegalArgumentException}（同一份推导不该生成两条同 id 规则）。
   *
   * <p>★ 本方法只读 {@code economy} 与 {@code extraRules}，不把瞬态规则写进 {@code EconomyData}；账户落账仍唯一走 {@link
   * StockDeductionService}。
   *
   * @param economy 经济状态（持久规则表 + 元信息；{@code periodicAdjustments} 的键 == 值内 id 由状态构造期把守）
   * @param extraRules 额外瞬态规则（如 {@code MilitaryPayRuleBridge.derive(...)} 的产物；可为空集合，不得含 null）
   * @param accounts 唯一账户会话（协调器线程；本方法就地调 {@link StockDeductionService#deduct}）
   * @param day 绝对世界日（与 {@code startsOnDay}/{@code phaseDay} 同量纲）
   * @return 当天读数（持久 + 瞬态合并后的汇总 + 逐规则；空规则 ⇒ 全 0）
   */
  public static Report applyDue(
      EconomyData economy,
      Collection<HouseholdPeriodicAdjustment> extraRules,
      AccountSession accounts,
      long day) {
    Objects.requireNonNull(economy, "economy");
    Objects.requireNonNull(extraRules, "extraRules");
    Objects.requireNonNull(accounts, "accounts");
    Map<PeriodicHouseholdAdjustmentId, HouseholdPeriodicAdjustment> merged = new LinkedHashMap<>();
    for (HouseholdPeriodicAdjustment rule : economy.periodicAdjustments().values()) {
      if (rule != null) {
        merged.put(rule.id(), rule);
      }
    }
    Set<PeriodicHouseholdAdjustmentId> extraIds = new LinkedHashSet<>();
    for (HouseholdPeriodicAdjustment extra : extraRules) {
      Objects.requireNonNull(extra, "extraRules 不得含 null");
      if (!extraIds.add(extra.id())) {
        throw new IllegalArgumentException("extraRules 出现重复 id（同一份推导不得生成两条同 id 规则）: " + extra.id());
      }
      // ★ 持久规则优先：显式注册的权威不被派生件覆盖（见 javadoc）。
      if (!merged.containsKey(extra.id())) {
        merged.put(extra.id(), extra);
      }
    }
    return applyDueRules(merged.values(), accounts, day);
  }

  /** 唯一执行体：把给定规则集合里当天到期者按 id 升序执行。旧/新两个公开入口都委托到这里 ⇒ due、部分支付、shortfall、gap 语义只有一份实现（“其余语义逐字复用”）。 */
  private static Report applyDueRules(
      Collection<HouseholdPeriodicAdjustment> rules, AccountSession accounts, long day) {
    List<HouseholdPeriodicAdjustment> due = new ArrayList<>();
    for (HouseholdPeriodicAdjustment rule : rules) {
      if (rule != null && isDue(rule, day)) {
        due.add(rule);
      }
    }
    if (due.isEmpty()) {
      return Report.empty(day);
    }
    // ★ 一天内多规则按 id.value() 升序执行；顺序是内容的纯函数，1/N 两条推进路径完全一致。
    due.sort(Comparator.comparing(rule -> rule.id().value()));

    Map<CommodityId, Long> paidGoods = new LinkedHashMap<>();
    Map<CurrencyId, Long> paidMoney = new LinkedHashMap<>();
    Map<CommodityId, Long> shortfallGoods = new LinkedHashMap<>();
    Map<CurrencyId, Long> shortfallMoney = new LinkedHashMap<>();
    List<RuleReadout> readouts = new ArrayList<>(due.size());
    List<String> gaps = new ArrayList<>();
    int executed = 0;
    for (HouseholdPeriodicAdjustment rule : due) {
      RuleReadout readout = executeOne(accounts, rule, day);
      readouts.add(readout);
      mergeAdd(shortfallGoods, readout.shortfallGoods());
      mergeAdd(shortfallMoney, readout.shortfallMoney());
      if (readout.status() == RuleReadout.Status.EXECUTED) {
        executed++;
        mergeAdd(paidGoods, readout.paidGoods());
        mergeAdd(paidMoney, readout.paidMoney());
      } else {
        gaps.add(rule.id().value() + ": " + readout.gap());
      }
    }
    int skipped = due.size() - executed;
    LOG.info(
        "event=PERIODIC_ADJUSTMENT_DAY day={} due={} executed={} skipped={} paidGoods={} paidMoney={}"
            + " shortfallGoods={} shortfallMoney={}",
        day,
        due.size(),
        executed,
        skipped,
        paidGoods,
        paidMoney,
        shortfallGoods,
        shortfallMoney);
    return new Report(
        day,
        due.size(),
        executed,
        skipped,
        paidGoods,
        paidMoney,
        shortfallGoods,
        shortfallMoney,
        readouts,
        gaps);
  }

  /** 严格按类注的到期判据；{@code periodDays}/{@code phaseDay} 不变量已在规则构造期钉死。 */
  public static boolean isDue(HouseholdPeriodicAdjustment rule, long day) {
    Objects.requireNonNull(rule, "rule");
    if (day < rule.startsOnDay()) {
      return false;
    }
    if (rule.expiresOnDay().isPresent() && day > rule.expiresOnDay().getAsLong()) {
      return false;
    }
    return (day - rule.startsOnDay()) % rule.periodDays() == rule.phaseDay();
  }

  /** 单条规则：账户存在性 → 逐腿可用量 → 构造 deduction → 调服务；返回本规则读数（并完成日志）。 */
  private static RuleReadout executeOne(
      AccountSession accounts, HouseholdPeriodicAdjustment rule, long day) {
    HouseholdId payer = rule.payer();
    if (accounts.householdKeyOf(payer) == null || accounts.householdAccount(payer) == null) {
      return skip(rule, "payer-account-missing", rule.goodsPerCycle(), rule.moneyPerCycle(), day);
    }
    Optional<HouseholdId> payee = rule.payee();
    if (payee.isPresent()
        && (accounts.householdKeyOf(payee.get()) == null
            || accounts.householdAccount(payee.get()) == null)) {
      return skip(rule, "payee-account-missing", rule.goodsPerCycle(), rule.moneyPerCycle(), day);
    }

    AccountSession.ActorAccount payerAccount = accounts.householdAccount(payer);
    if (payerAccount == null) {
      return skip(rule, "payer-account-missing", rule.goodsPerCycle(), rule.moneyPerCycle(), day);
    }
    HouseholdInventory inventory =
        new HouseholdInventory(
            new HouseholdAccountKey(payer),
            payerAccount.goods(),
            payerAccount.money(),
            payerAccount.frozenGoods(),
            payerAccount.frozenMoney());

    Map<CommodityId, Long> paidGoods = new LinkedHashMap<>();
    Map<CommodityId, Long> shortfallGoods = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> leg : rule.goodsPerCycle().entrySet()) {
      long requested = leg.getValue();
      long available = AvailableStock.available(inventory, leg.getKey());
      long paid = Math.min(requested, available);
      if (paid > 0L) {
        paidGoods.put(leg.getKey(), paid);
      }
      long shortfall = requested - paid;
      // ★ 缺额为 0 的腿不进读数：读数的键只在"真的少了"时存在（与 deduction 腿 > 0 同一口径）。
      if (shortfall > 0L) {
        shortfallGoods.put(leg.getKey(), shortfall);
      }
      traceLeg(day, rule, "goods", leg.getKey(), requested, available, paid, shortfall);
    }
    Map<CurrencyId, Long> paidMoney = new LinkedHashMap<>();
    Map<CurrencyId, Long> shortfallMoney = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> leg : rule.moneyPerCycle().entrySet()) {
      long requested = leg.getValue();
      long available = AvailableStock.available(inventory, leg.getKey());
      long paid = Math.min(requested, available);
      if (paid > 0L) {
        paidMoney.put(leg.getKey(), paid);
      }
      long shortfall = requested - paid;
      if (shortfall > 0L) {
        shortfallMoney.put(leg.getKey(), shortfall);
      }
      traceLeg(day, rule, "money", leg.getKey(), requested, available, paid, shortfall);
    }

    if (paidGoods.isEmpty() && paidMoney.isEmpty()) {
      return skip(rule, "no-payable-leg", shortfallGoods, shortfallMoney, day);
    }

    String detail = rule.policySource() + ":" + rule.id().value();
    HouseholdStockDeduction deduction =
        payee.isPresent()
            ? HouseholdStockDeduction.transfer(
                payer, payee.get(), paidGoods, paidMoney, rule.reason(), detail)
            : HouseholdStockDeduction.sink(payer, paidGoods, paidMoney, rule.reason(), detail);
    try {
      StockDeductionService.deduct(accounts, deduction); // 默认阶段 = SettlementStage.TAX_AND_UPKEEP
    } catch (IllegalArgumentException defensiveReject) {
      // ★ 前置检查已保证账户存在且各腿 ≤ 可用量；服务仍拒（理论上不可达的状态损坏/新守卫）⇒ 记 gap、继续。
      return skip(
          rule,
          "service-rejected: " + defensiveReject.getMessage(),
          rule.goodsPerCycle(),
          rule.moneyPerCycle(),
          day);
    }

    LOG.info(
        "event=PERIODIC_ADJUSTMENT_RULE day={} rule={} status=EXECUTED payer={} payee={} reason={}"
            + " paidGoods={} paidMoney={} shortfallGoods={} shortfallMoney={}",
        day,
        rule.id().value(),
        payer.value(),
        payee.map(HouseholdId::value).orElse("<sink>"),
        rule.reason().value(),
        paidGoods,
        paidMoney,
        shortfallGoods,
        shortfallMoney);
    return new RuleReadout(
        rule.id(),
        RuleReadout.Status.EXECUTED,
        "",
        paidGoods,
        paidMoney,
        shortfallGoods,
        shortfallMoney);
  }

  /** 跳过一条规则：缺额读数按“请求全额未付”记（账户缺失时无法知道可用量，这是唯一不猜的记法）。 */
  private static RuleReadout skip(
      HouseholdPeriodicAdjustment rule,
      String gap,
      Map<CommodityId, Long> shortfallGoods,
      Map<CurrencyId, Long> shortfallMoney,
      long day) {
    LOG.info(
        "event=PERIODIC_ADJUSTMENT_RULE day={} rule={} status=SKIPPED gap={} payer={} payee={} reason={}"
            + " paidGoods={} paidMoney={} shortfallGoods={} shortfallMoney={}",
        day,
        rule.id().value(),
        gap,
        rule.payer().value(),
        rule.payee().map(HouseholdId::value).orElse("<sink>"),
        rule.reason().value(),
        Map.of(),
        Map.of(),
        shortfallGoods,
        shortfallMoney);
    return new RuleReadout(
        rule.id(),
        RuleReadout.Status.SKIPPED,
        gap,
        Map.of(),
        Map.of(),
        shortfallGoods,
        shortfallMoney);
  }

  /** 逐腿 TRACE（默认关闭；打开后逐腿对账 requested/available/paid/shortfall）。 */
  private static void traceLeg(
      long day,
      HouseholdPeriodicAdjustment rule,
      String dimension,
      Object asset,
      long requested,
      long available,
      long paid,
      long shortfall) {
    if (!TRACE.isTraceEnabled()) {
      return;
    }
    TRACE.trace(
        "event=PERIODIC_ADJUSTMENT_LEG day={} rule={} dimension={} asset={} requested={} available={}"
            + " paid={} shortfall={}",
        day,
        rule.id().value(),
        dimension,
        asset,
        requested,
        available,
        paid,
        shortfall);
  }

  /** 逐键相加（保序：先出现的键在前；值非 null 且 > 0，两个来源都如此）。 */
  private static <K> void mergeAdd(Map<K, Long> target, Map<K, Long> addition) {
    for (Map.Entry<K, Long> entry : addition.entrySet()) {
      target.merge(entry.getKey(), entry.getValue(), Long::sum);
    }
  }

  /** 一条规则的执行读数。 */
  public record RuleReadout(
      PeriodicHouseholdAdjustmentId ruleId,
      Status status,
      String gap,
      Map<CommodityId, Long> paidGoods,
      Map<CurrencyId, Long> paidMoney,
      Map<CommodityId, Long> shortfallGoods,
      Map<CurrencyId, Long> shortfallMoney) {

    public RuleReadout {
      Objects.requireNonNull(ruleId, "ruleId");
      Objects.requireNonNull(status, "status");
      gap = gap == null ? "" : gap;
      paidGoods = freezeGoods(paidGoods);
      paidMoney = freezeMoney(paidMoney);
      shortfallGoods = freezeGoods(shortfallGoods);
      shortfallMoney = freezeMoney(shortfallMoney);
    }

    /** 执行状态：EXECUTED = 至少一腿实际落账；SKIPPED = 未调服务或服务防御性拒绝。 */
    public enum Status {
      EXECUTED,
      SKIPPED
    }

    public boolean executed() {
      return status == Status.EXECUTED;
    }
  }

  /** 当天总读数（{@code rules} 保序 = id.value() 升序；空表 == 当天没有到期规则）。 */
  public record Report(
      long day,
      int due,
      int executed,
      int skipped,
      Map<CommodityId, Long> paidGoods,
      Map<CurrencyId, Long> paidMoney,
      Map<CommodityId, Long> shortfallGoods,
      Map<CurrencyId, Long> shortfallMoney,
      List<RuleReadout> rules,
      List<String> gaps) {

    public Report {
      if (due < 0 || executed < 0 || skipped < 0 || executed + skipped != due) {
        throw new IllegalArgumentException(
            "Report 计数不自洽: due=" + due + " executed=" + executed + " skipped=" + skipped);
      }
      paidGoods = freezeGoods(paidGoods);
      paidMoney = freezeMoney(paidMoney);
      shortfallGoods = freezeGoods(shortfallGoods);
      shortfallMoney = freezeMoney(shortfallMoney);
      rules = List.copyOf(rules);
      gaps = List.copyOf(gaps);
    }

    public static Report empty(long day) {
      return new Report(day, 0, 0, 0, Map.of(), Map.of(), Map.of(), Map.of(), List.of(), List.of());
    }
  }

  private static Map<CommodityId, Long> freezeGoods(Map<CommodityId, Long> values) {
    Objects.requireNonNull(values, "goods");
    Map<CommodityId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : values.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0L) {
        throw new IllegalArgumentException(
            "goods 读数的键/值必须非 null 且 > 0: " + entry.getKey() + "=" + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy);
  }

  private static Map<CurrencyId, Long> freezeMoney(Map<CurrencyId, Long> values) {
    Objects.requireNonNull(values, "money");
    Map<CurrencyId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> entry : values.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0L) {
        throw new IllegalArgumentException(
            "money 读数的键/值必须非 null 且 > 0: " + entry.getKey() + "=" + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy);
  }
}
