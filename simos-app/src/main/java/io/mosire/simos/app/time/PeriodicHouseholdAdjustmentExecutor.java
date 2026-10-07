package io.mosire.simos.app.time;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.actor.model.AvailableStock;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.stock.HouseholdPeriodicAdjustment;
import io.mosire.simos.economy.api.stock.HouseholdStockDeduction;
import io.mosire.simos.economy.api.stock.PeriodicHouseholdAdjustmentId;
import io.mosire.simos.economy.time.AccountSession;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
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
 * 改写任何经济持久状态。日志走 {@link AppLog#time()} / {@link AppLog#trace()}，不新开门面。
 */
public final class PeriodicHouseholdAdjustmentExecutor {

  private static final Logger LOG = AppLog.time();
  private static final Logger TRACE = AppLog.trace();

  private PeriodicHouseholdAdjustmentExecutor() {}

  /**
   * 旧签名（兼容，P4a 调用点/夹具照旧）：只执行 {@code EconomyData.periodicAdjustments} 持久规则表；语义逐字保留，
   * 现委托给“无额外瞬态规则”的内部同一实现。★ Z7b 起旧调用方没有预算账本 ⇒ 按“原始请求 = 逐腿授权”执行，逐腿缺口与 PARTIAL 读数照常报出。
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
    return applyLedgerRules(fullAuthorization(rules.values()), accounts, day);
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
    return applyLedgerRules(fullAuthorization(merged.values()), accounts, day);
  }

  /**
   * ★★ <b>Z3c 旧兼容入口：只执行给定规则集合</b>（不与 {@code EconomyData.periodicAdjustments} 合并）——没有预算授权
   * 账本的调用方按“原始请求 = 逐腿授权”执行。<b>预算路径请走 {@link #applyBudgeted}</b>：它把“原始请求 + 逐腿授权”一起带进执行器。
   */
  public static Report applyExplicit(
      Collection<HouseholdPeriodicAdjustment> rules, AccountSession accounts, long day) {
    Objects.requireNonNull(rules, "rules");
    Objects.requireNonNull(accounts, "accounts");
    for (HouseholdPeriodicAdjustment rule : rules) {
      Objects.requireNonNull(rule, "rules 不得含 null");
    }
    return applyLedgerRules(fullAuthorization(rules), accounts, day);
  }

  /**
   * ★★ <b>Z7b：预算逐腿账本入口</b> —— 每条 {@link BudgetedRule} 带着<b>原始请求</b>（rule 的两张腿表）与<b>逐腿授权</b>
   * （authorizedGoods/authorizedMoney）一起交给执行器；到期判据、逐腿 {@code min(授权, 可用)}、PARTIAL/SKIPPED 与 gap
   * 语义全部复用唯一执行体，不复制第二套。
   *
   * <p>★ 被预算裁到全 0 授权的规则也<b>照常进入本入口</b>（记 {@code SKIPPED budget-authorized-zero} 或 PARTIAL），不再在
   * 预算桥里提前消失 —— D4“原始请求丢失”的修复落点。
   */
  public static Report applyBudgeted(
      Collection<BudgetedRule> rules, AccountSession accounts, long day) {
    Objects.requireNonNull(rules, "rules");
    Objects.requireNonNull(accounts, "accounts");
    for (BudgetedRule rule : rules) {
      Objects.requireNonNull(rule, "rules 不得含 null");
    }
    return applyLedgerRules(rules, accounts, day);
  }

  /** 旧入口的缺省授权：没有预算账本时“逐腿授权 = 原始请求”（仍然只有一条逐腿执行体）。 */
  private static List<BudgetedRule> fullAuthorization(
      Collection<HouseholdPeriodicAdjustment> rules) {
    List<BudgetedRule> authorized = new ArrayList<>(rules.size());
    for (HouseholdPeriodicAdjustment rule : rules) {
      if (rule != null) {
        authorized.add(BudgetedRule.full(rule));
      }
    }
    return authorized;
  }

  /**
   * 唯一执行体：把给定逐腿账本里当天到期者按 id 升序执行。所有公开入口都委托到这里 ⇒ due、逐腿 min(授权,可用)、 PARTIAL、shortfall、gap 语义只有一份实现。
   */
  private static Report applyLedgerRules(
      Collection<BudgetedRule> rules, AccountSession accounts, long day) {
    List<BudgetedRule> due = new ArrayList<>();
    for (BudgetedRule rule : rules) {
      if (rule != null && isDue(rule.rule(), day)) {
        due.add(rule);
      }
    }
    if (due.isEmpty()) {
      return Report.empty(day);
    }
    // ★ 一天内多规则按 id.value() 升序执行；顺序是内容的纯函数，1/N 两条推进路径完全一致。
    due.sort(Comparator.comparing(entry -> entry.rule().id().value()));

    Map<CommodityId, Long> paidGoods = new LinkedHashMap<>();
    Map<CurrencyId, Long> paidMoney = new LinkedHashMap<>();
    Map<CommodityId, Long> shortfallGoods = new LinkedHashMap<>();
    Map<CurrencyId, Long> shortfallMoney = new LinkedHashMap<>();
    List<RuleReadout> readouts = new ArrayList<>(due.size());
    List<String> gaps = new ArrayList<>();
    int executed = 0;
    int partial = 0;
    for (BudgetedRule entry : due) {
      RuleReadout readout = executeOne(accounts, entry, day);
      readouts.add(readout);
      mergeAdd(shortfallGoods, readout.shortfallGoods());
      mergeAdd(shortfallMoney, readout.shortfallMoney());
      if (readout.status() == RuleReadout.Status.EXECUTED) {
        executed++;
        mergeAdd(paidGoods, readout.paidGoods());
        mergeAdd(paidMoney, readout.paidMoney());
      } else if (readout.status() == RuleReadout.Status.PARTIAL) {
        partial++;
        // ★ PARTIAL 的实付同样进当日汇总（旧实现只在 EXECUTED 时汇总，会把部分支付从读账里漏掉）。
        mergeAdd(paidGoods, readout.paidGoods());
        mergeAdd(paidMoney, readout.paidMoney());
      } else {
        gaps.add(entry.rule().id().value() + ": " + readout.gap());
      }
    }
    int skipped = due.size() - executed - partial;
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "PERIODIC_ADJUSTMENT_DAY",
                AppLogSource.DAILY_LOOP,
                "day",
                day,
                "due",
                due.size(),
                "executed",
                executed,
                "partial",
                partial,
                "skipped",
                skipped,
                "paidGoods",
                paidGoods,
                "paidMoney",
                paidMoney,
                "shortfallGoods",
                shortfallGoods,
                "shortfallMoney",
                shortfallMoney));
    return new Report(
        day,
        due.size(),
        executed,
        partial,
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

  /** 单条规则：账户存在性 → 逐腿授权/可用量 → 构造 deduction → 调服务；返回本规则读数（并完成日志）。 */
  private static RuleReadout executeOne(AccountSession accounts, BudgetedRule budgeted, long day) {
    HouseholdPeriodicAdjustment rule = budgeted.rule();
    Map<CommodityId, Long> requestedGoods = rule.goodsPerCycle();
    Map<CurrencyId, Long> requestedMoney = rule.moneyPerCycle();
    HouseholdId payer = rule.payer();
    if (accounts.householdKeyOf(payer) == null || accounts.householdAccount(payer) == null) {
      return skip(budgeted, "payer-account-missing", requestedGoods, requestedMoney, day);
    }
    Optional<HouseholdId> payee = rule.payee();
    if (payee.isPresent()
        && (accounts.householdKeyOf(payee.get()) == null
            || accounts.householdAccount(payee.get()) == null)) {
      return skip(budgeted, "payee-account-missing", requestedGoods, requestedMoney, day);
    }

    AccountSession.ActorAccount payerAccount = accounts.householdAccount(payer);
    if (payerAccount == null) {
      return skip(budgeted, "payer-account-missing", requestedGoods, requestedMoney, day);
    }
    HouseholdInventory inventory =
        new HouseholdInventory(
            new HouseholdAccountKey(payer),
            payerAccount.goods(),
            payerAccount.money(),
            payerAccount.frozenGoods(),
            payerAccount.frozenMoney());

    Map<CommodityId, Long> authorizedGoods = budgeted.authorizedGoods();
    Map<CommodityId, Long> paidGoods = new LinkedHashMap<>();
    Map<CommodityId, Long> shortfallGoods = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> leg : requestedGoods.entrySet()) {
      long requested = leg.getValue();
      long authorized = authorizedGoods.getOrDefault(leg.getKey(), 0L);
      long available = AvailableStock.available(inventory, leg.getKey());
      long paid = Math.min(authorized, available);
      if (paid > 0L) {
        paidGoods.put(leg.getKey(), paid);
      }
      long shortfall = requested - paid;
      // ★ 缺额为 0 的腿不进读数：读数的键只在"真的少了"时存在（与 deduction 腿 > 0 同一口径）。
      if (shortfall > 0L) {
        shortfallGoods.put(leg.getKey(), shortfall);
      }
      traceLeg(day, rule, "goods", leg.getKey(), requested, authorized, available, paid, shortfall);
    }
    Map<CurrencyId, Long> authorizedMoney = budgeted.authorizedMoney();
    Map<CurrencyId, Long> paidMoney = new LinkedHashMap<>();
    Map<CurrencyId, Long> shortfallMoney = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> leg : requestedMoney.entrySet()) {
      long requested = leg.getValue();
      long authorized = authorizedMoney.getOrDefault(leg.getKey(), 0L);
      long available = AvailableStock.available(inventory, leg.getKey());
      long paid = Math.min(authorized, available);
      if (paid > 0L) {
        paidMoney.put(leg.getKey(), paid);
      }
      long shortfall = requested - paid;
      if (shortfall > 0L) {
        shortfallMoney.put(leg.getKey(), shortfall);
      }
      traceLeg(day, rule, "money", leg.getKey(), requested, authorized, available, paid, shortfall);
    }

    if (paidGoods.isEmpty() && paidMoney.isEmpty()) {
      // ★★ Z7b：全零授权与"国库无可用腿"要具名分开 —— 前者是预算裁掉（budget-authorized-zero），
      //   后者才是 no-payable-leg；两者都不算契约异常（由预算缺口/国库缺口读数承接）。
      String gap = budgeted.lacksAllAuthorization() ? "budget-authorized-zero" : "no-payable-leg";
      return skip(budgeted, gap, shortfallGoods, shortfallMoney, day);
    }

    String detail = rule.policySource() + ":" + rule.id().value();
    HouseholdStockDeduction deduction =
        payee.isPresent()
            ? HouseholdStockDeduction.transfer(
                payer, payee.get(), paidGoods, paidMoney, rule.reason(), detail)
            : HouseholdStockDeduction.sink(payer, paidGoods, paidMoney, rule.reason(), detail);
    try {
      StockDeductionService.deduct(
          accounts, deduction, day); // 默认阶段 = SettlementStage.TAX_AND_UPKEEP
    } catch (IllegalArgumentException defensiveReject) {
      // ★ 前置检查已保证账户存在且各腿 ≤ 可用量；服务仍拒（理论上不可达的状态损坏/新守卫）⇒ 记 gap、继续。
      return skip(
          budgeted,
          "service-rejected: " + defensiveReject.getMessage(),
          requestedGoods,
          requestedMoney,
          day);
    }

    boolean partial = !shortfallGoods.isEmpty() || !shortfallMoney.isEmpty();
    RuleReadout.Status status = partial ? RuleReadout.Status.PARTIAL : RuleReadout.Status.EXECUTED;
    String gap = partial ? "partial-payment" : "";
    logRule(budgeted, status, gap, paidGoods, paidMoney, shortfallGoods, shortfallMoney, day);
    return new RuleReadout(
        rule.id(),
        status,
        gap,
        requestedGoods,
        requestedMoney,
        authorizedGoods,
        authorizedMoney,
        paidGoods,
        paidMoney,
        shortfallGoods,
        shortfallMoney);
  }

  /**
   * 跳过一条规则：缺额读数按“原始请求全额未付”记（账户缺失时无法知道可用量，这是唯一不猜的记法）。★ requested/authorized
   * 两组字段照常带出，读口能看见“缺口相对哪份原始请求、被授权了多少”。
   */
  private static RuleReadout skip(
      BudgetedRule budgeted,
      String gap,
      Map<CommodityId, Long> shortfallGoods,
      Map<CurrencyId, Long> shortfallMoney,
      long day) {
    logRule(
        budgeted,
        RuleReadout.Status.SKIPPED,
        gap,
        Map.of(),
        Map.of(),
        shortfallGoods,
        shortfallMoney,
        day);
    return new RuleReadout(
        budgeted.rule().id(),
        RuleReadout.Status.SKIPPED,
        gap,
        budgeted.rule().goodsPerCycle(),
        budgeted.rule().moneyPerCycle(),
        budgeted.authorizedGoods(),
        budgeted.authorizedMoney(),
        Map.of(),
        Map.of(),
        shortfallGoods,
        shortfallMoney);
  }

  /** 一条规则的统一日志（EXECUTED/PARTIAL/SKIPPED 同一形状：业务拒绝 = INFO，见 AGENTS §一.9）。 */
  private static void logRule(
      BudgetedRule budgeted,
      RuleReadout.Status status,
      String gap,
      Map<CommodityId, Long> paidGoods,
      Map<CurrencyId, Long> paidMoney,
      Map<CommodityId, Long> shortfallGoods,
      Map<CurrencyId, Long> shortfallMoney,
      long day) {
    HouseholdPeriodicAdjustment rule = budgeted.rule();
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "PERIODIC_ADJUSTMENT_RULE",
                AppLogSource.DAILY_LOOP,
                "day",
                day,
                "rule",
                rule.id().value(),
                "status",
                status.name(),
                "gap",
                gap == null ? "" : gap,
                "payer",
                rule.payer().value(),
                "payee",
                rule.payee().map(HouseholdId::value).orElse("<sink>"),
                "reason",
                rule.reason().value(),
                "requestedGoods",
                rule.goodsPerCycle(),
                "requestedMoney",
                rule.moneyPerCycle(),
                "authorizedGoods",
                budgeted.authorizedGoods(),
                "authorizedMoney",
                budgeted.authorizedMoney(),
                "paidGoods",
                paidGoods,
                "paidMoney",
                paidMoney,
                "shortfallGoods",
                shortfallGoods,
                "shortfallMoney",
                shortfallMoney));
  }

  /** 逐腿 TRACE（默认关闭；打开后逐腿对账 requested/authorized/available/paid/shortfall）。 */
  private static void traceLeg(
      long day,
      HouseholdPeriodicAdjustment rule,
      String dimension,
      Object asset,
      long requested,
      long authorized,
      long available,
      long paid,
      long shortfall) {
    if (!TRACE.isTraceEnabled()) {
      return;
    }
    EventLog.channel(TRACE)
        .trace(
            LogEvent.of(
                "PERIODIC_ADJUSTMENT_LEG",
                AppLogSource.DAILY_LOOP,
                "day",
                day,
                "rule",
                rule.id().value(),
                "dimension",
                dimension,
                "asset",
                asset,
                "requested",
                requested,
                "authorized",
                authorized,
                "available",
                available,
                "paid",
                paid,
                "shortfall",
                shortfall));
  }

  /** 逐键相加（保序：先出现的键在前；值非 null 且 > 0，两个来源都如此）。 */
  private static <K> void mergeAdd(Map<K, Long> target, Map<K, Long> addition) {
    for (Map.Entry<K, Long> entry : addition.entrySet()) {
      target.merge(entry.getKey(), entry.getValue(), Long::sum);
    }
  }

  /**
   * ★★ <b>Z7b：一条“原始请求 + 逐腿授权”的预算执行账本</b>。{@code rule} 是原始请求（两条腿表逐值保留）， {@code
   * authorizedGoods/authorizedMoney} 是预算桥按类别顺序/上限算出的逐腿授权（只含 &gt; 0 腿，逐值 ≤ 原始请求）。
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "compact constructor 经 authorizeGoods/authorizeMoney 逐表拷贝并冻结（Collections.unmodifiableMap）；SpotBugs 不跨辅助方法识别")
  public record BudgetedRule(
      HouseholdPeriodicAdjustment rule,
      Map<CommodityId, Long> authorizedGoods,
      Map<CurrencyId, Long> authorizedMoney) {

    public BudgetedRule {
      Objects.requireNonNull(rule, "rule");
      authorizedGoods = authorizeGoods(rule, authorizedGoods);
      authorizedMoney = authorizeMoney(rule, authorizedMoney);
    }

    /** 旧入口（无预算账本）的缺省授权：逐腿授权 = 原始请求。 */
    static BudgetedRule full(HouseholdPeriodicAdjustment rule) {
      Objects.requireNonNull(rule, "rule");
      return new BudgetedRule(rule, rule.goodsPerCycle(), rule.moneyPerCycle());
    }

    /** 原始请求的每一条腿授权都为 0（用于把 SKIPPED 具名成 {@code budget-authorized-zero}）。 */
    public boolean lacksAllAuthorization() {
      for (CommodityId leg : rule.goodsPerCycle().keySet()) {
        if (authorizedGoods.getOrDefault(leg, 0L) > 0L) {
          return false;
        }
      }
      for (CurrencyId leg : rule.moneyPerCycle().keySet()) {
        if (authorizedMoney.getOrDefault(leg, 0L) > 0L) {
          return false;
        }
      }
      return true;
    }

    /** 授权商品腿：只认原始请求里有的腿、逐值 ∈ [0, requested]、只留 &gt; 0（坏数据 fail-closed）。 */
    private static Map<CommodityId, Long> authorizeGoods(
        HouseholdPeriodicAdjustment rule, Map<CommodityId, Long> authorized) {
      Objects.requireNonNull(authorized, "authorizedGoods（无授权给空表）");
      Map<CommodityId, Long> copy = new LinkedHashMap<>();
      for (Map.Entry<CommodityId, Long> entry : authorized.entrySet()) {
        CommodityId leg = entry.getKey();
        Long value = entry.getValue();
        if (leg == null || value == null) {
          throw new IllegalArgumentException("authorizedGoods 不得含 null 键/值");
        }
        Long requested = rule.goodsPerCycle().get(leg);
        if (requested == null) {
          throw new IllegalArgumentException("authorizedGoods 的腿不在原始请求里: " + leg);
        }
        if (value < 0L || value > requested) {
          throw new IllegalArgumentException(
              "authorizedGoods 必须 ∈ [0, requested]（逐腿）: "
                  + leg
                  + "="
                  + value
                  + " requested="
                  + requested);
        }
        if (value > 0L) {
          copy.put(leg, value);
        }
      }
      return Collections.unmodifiableMap(copy);
    }

    /** 授权货币腿：同 {@link #authorizeGoods} 的逐值口径。 */
    private static Map<CurrencyId, Long> authorizeMoney(
        HouseholdPeriodicAdjustment rule, Map<CurrencyId, Long> authorized) {
      Objects.requireNonNull(authorized, "authorizedMoney（无授权给空表）");
      Map<CurrencyId, Long> copy = new LinkedHashMap<>();
      for (Map.Entry<CurrencyId, Long> entry : authorized.entrySet()) {
        CurrencyId leg = entry.getKey();
        Long value = entry.getValue();
        if (leg == null || value == null) {
          throw new IllegalArgumentException("authorizedMoney 不得含 null 键/值");
        }
        Long requested = rule.moneyPerCycle().get(leg);
        if (requested == null) {
          throw new IllegalArgumentException("authorizedMoney 的腿不在原始请求里: " + leg);
        }
        if (value < 0L || value > requested) {
          throw new IllegalArgumentException(
              "authorizedMoney 必须 ∈ [0, requested]（逐腿）: "
                  + leg
                  + "="
                  + value
                  + " requested="
                  + requested);
        }
        if (value > 0L) {
          copy.put(leg, value);
        }
      }
      return Collections.unmodifiableMap(copy);
    }
  }

  /**
   * 一条规则的执行读数。★★ <b>逐腿四组读数</b>：{@code requestedGoods/requestedMoney}（原始请求）、 {@code
   * authorizedGoods/authorizedMoney}（预算逐腿授权）、{@code paid*}（实际落账）、{@code shortfall*}（缺额 = 原始请求 −
   * 实付；只记 &gt; 0 腿）。
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "compact constructor 经 freezeGoods/freezeMoney 逐表拷贝并冻结（Collections.unmodifiableMap）；SpotBugs 不跨辅助方法识别")
  public record RuleReadout(
      PeriodicHouseholdAdjustmentId ruleId,
      Status status,
      String gap,
      Map<CommodityId, Long> requestedGoods,
      Map<CurrencyId, Long> requestedMoney,
      Map<CommodityId, Long> authorizedGoods,
      Map<CurrencyId, Long> authorizedMoney,
      Map<CommodityId, Long> paidGoods,
      Map<CurrencyId, Long> paidMoney,
      Map<CommodityId, Long> shortfallGoods,
      Map<CurrencyId, Long> shortfallMoney) {

    public RuleReadout {
      Objects.requireNonNull(ruleId, "ruleId");
      Objects.requireNonNull(status, "status");
      gap = gap == null ? "" : gap;
      requestedGoods = freezeGoods(requestedGoods);
      requestedMoney = freezeMoney(requestedMoney);
      authorizedGoods = freezeGoods(authorizedGoods);
      authorizedMoney = freezeMoney(authorizedMoney);
      paidGoods = freezeGoods(paidGoods);
      paidMoney = freezeMoney(paidMoney);
      shortfallGoods = freezeGoods(shortfallGoods);
      shortfallMoney = freezeMoney(shortfallMoney);
    }

    /**
     * 执行状态（Z7b 冻结语义）：
     *
     * <ul>
     *   <li>{@code EXECUTED} = 原始请求的每条腿都足额落账；
     *   <li>{@code PARTIAL} = 至少一腿 {@code paid > 0}，且至少一条原始腿有缺口（含 {@code no-payable-leg} 的部分支付）；
     *   <li>{@code SKIPPED} = 没有任何腿落账（含 {@code no-payable-leg} 与 {@code budget-authorized-zero}，gap
     *       逐条具名）。
     * </ul>
     */
    public enum Status {
      EXECUTED,
      PARTIAL,
      SKIPPED
    }

    public boolean executed() {
      return status == Status.EXECUTED;
    }

    /** 有腿付、有原始腿缺。 */
    public boolean partiallyExecuted() {
      return status == Status.PARTIAL;
    }
  }

  /** 当天总读数（{@code rules} 保序 = id.value() 升序；空表 == 当天没有到期规则）。 */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "compact constructor 经 freezeGoods/freezeMoney 逐表拷贝并冻结（Collections.unmodifiableMap）、rules/gaps 走 List.copyOf；SpotBugs 不跨辅助方法识别")
  public record Report(
      long day,
      int due,
      int executed,
      int partial,
      int skipped,
      Map<CommodityId, Long> paidGoods,
      Map<CurrencyId, Long> paidMoney,
      Map<CommodityId, Long> shortfallGoods,
      Map<CurrencyId, Long> shortfallMoney,
      List<RuleReadout> rules,
      List<String> gaps) {

    public Report {
      if (due < 0
          || executed < 0
          || partial < 0
          || skipped < 0
          || executed + partial + skipped != due) {
        throw new IllegalArgumentException(
            "Report 计数不自洽: due="
                + due
                + " executed="
                + executed
                + " partial="
                + partial
                + " skipped="
                + skipped);
      }
      paidGoods = freezeGoods(paidGoods);
      paidMoney = freezeMoney(paidMoney);
      shortfallGoods = freezeGoods(shortfallGoods);
      shortfallMoney = freezeMoney(shortfallMoney);
      rules = List.copyOf(rules);
      gaps = List.copyOf(gaps);
    }

    public static Report empty(long day) {
      return new Report(
          day, 0, 0, 0, 0, Map.of(), Map.of(), Map.of(), Map.of(), List.of(), List.of());
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
