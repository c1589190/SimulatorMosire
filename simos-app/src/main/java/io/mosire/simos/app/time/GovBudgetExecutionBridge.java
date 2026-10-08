package io.mosire.simos.app.time;

import io.mosire.simos.actor.model.AvailableStock;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.app.household.GovernmentHouseholdResolver;
import io.mosire.simos.app.household.GovernmentServiceLaborBridge;
import io.mosire.simos.economy.EconomyCommodities;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.api.stock.HouseholdPeriodicAdjustment;
import io.mosire.simos.economy.time.AccountSession;
import io.mosire.simos.gov.GovAdministrationPlan;
import io.mosire.simos.gov.GovBudgetCategory;
import io.mosire.simos.gov.GovBudgetLine;
import io.mosire.simos.gov.GovBudgetPolicy;
import io.mosire.simos.gov.GovDaily;
import io.mosire.simos.gov.GovEfficiency;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
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
 * ★★ <b>Z3c 国库预算执行桥（P4c 口径）</b>：按每个 GOV 的 {@link GovBudgetPolicy#orderedCategories()} 顺序，在同一本
 * 国库（{@code hh-gov-&lt;govUnitId&gt;} 的 actor 账户）上为 <b>行政俸禄（ADMIN_STIPEND）/ 军俸（MILITARY_STIPEND）/
 * 行政工资（ADMIN_SALARY）</b> 三类现算需求做限额分配，再把限额落到既有两条执行路径：
 *
 * <ul>
 *   <li><b>行政俸禄</b>：{@link GovDaily#settle} 的 {@code PaymentOracle} 换成 {@link #upkeepOracle}（逐资源上限
 *       = 本桥算出的 ADMIN_STIPEND 限额）；
 *   <li><b>军俸 / 行政工资</b>：{@link MilitaryPayRuleBridge} 与 {@link GovSalaryRuleBridge} 的当日规则按类别限额算出
 *       <b>逐腿授权</b>（{@code min(请求, 剩余限额)}），与<b>原始请求</b>一起装进 {@code BudgetedRule} 交给 {@link
 *       PeriodicHouseholdAdjustmentExecutor#applyBudgeted}；持久规则（{@code
 *       EconomyData.periodicAdjustments}）不在预算 类别内，由调用方在预算类别之后单独执行。
 * </ul>
 *
 * <p>★★ <b>Z7b：原始请求不得在桥里丢失</b> —— 零授权腿/全零授权规则也照常进 {@code applyBudgeted}（执行器记 PARTIAL 或 {@code
 * SKIPPED budget-authorized-zero}），避免 run6 day122“军俸粮腿从未到达执行器、状态误报 EXECUTED”的 D4 形态。
 *
 * <p>★★ <b>预算周期冻结 = 1 tick（每日）</b>（与工资 {@code periodDays=1}、{@code GovDaily} 日结算对齐）；{@code
 * minPerCycle}/{@code capPerCycle} 是"每日"口径。设计书未冻结更细的周期，本区在台账里记明。
 *
 * <p>★★ <b>min/cap 到具体腿的口径（冻结）</b>：
 *
 * <ol>
 *   <li>★★ <b>A2b（2026-10-08；不变量 I24 / 负向用例 M7）：三条资源腿的价格口径 = 单一币种（本币 = {@code silver}）</b> ——
 *       货币腿<b>只有</b> {@link #BOOK_CURRENCY} 这一种，算式具名为 {@link ResourceVector#valueInBookCurrency()}
 *       （改前叫无名 {@code value()}，读的人看不出"哪个币种的口径"）。★ <b>商品腿（grain/cloth）仍按"毫"1:1 计入</b>：
 *       世界没有价格表（{@link GovBudgetLine} 的 javadoc 把量纲定为"与国库计价单位一致"），本区取最保守的 1:1、不引入伪价格 ——
 *       这条是**商品↔货币**的既有口径，与"跨币种"是两件事；<b>跨币种既不相加也不折算</b> （I17：世界上没有汇率，故"能分别算就分别算、不能分别算就不算"）；
 *   <li>★★ <b>国库里的其他币种一律不进本算式</b>（既不按 1:1 加进来，也不折算）：{@link #treasuryAvailable} 逐币种列出被排除的 币种与金额并发
 *       <b>DEBUG 日志 {@code GOV_BUDGET_FOREIGN_CURRENCY_EXCLUDED}</b>（"显式声明单一币种口径"必须能被读到，
 *       §3.6）；该口径的直接后果是 <b>fail-closed</b> 的：国库只有铜、没有银 ⇒ 本币可用 = 0 ⇒ 类别拿不到额度 （{@code
 *       treasury-available-zero}），本桥<b>不</b>拿铜当银花；
 *   <li>★★ <b>请求侧若出现非本币的货币腿 ⇒ 具名拒收</b>（{@code unknown-currency}，见 {@link
 *       #resourceVectorOf(HouseholdPeriodicAdjustment, long, UnitId)}）—— 不静默丢腿、不按 1:1 折进本币帽；
 *   <li>{@code capPerCycle} = 本类别每日"价值"上限；{@code minPerCycle} = 本类别每日"至少留"的保障额（下限）：
 *       先为<b>所有更低优先级类别</b>预留 {@code min(该类 min, 该类请求值, 该类 cap)}，剩余才轮到当前类别；
 *   <li>每类按固定腿序 grain → cloth → silver 分配限额（与 {@code GovDaily} 的资源序一致）；
 *   <li>{@code 可用不足}/{@code 帽不足}/{@code 下限未达} 都进具名缺口读数与 {@code ADMIN_BUDGET_SHORTFALL} 信号；
 *       本桥<b>不自动注资、不自动改计划/税率</b>。
 * </ol>
 *
 * <p>★ <b>边界（铁律 3/4）</b>：本类只读 {@link AccountSession} 的市价（可用 = 余额 − 冻结，唯一算法 {@link
 * AvailableStock}）、只产限额与瞬态规则；账户落账仍唯一走 {@link StockDeductionService}（由 P4a 执行器调用）。
 * 承诺劳动与劳动队列一个字不改（C7）。
 *
 * <p>★ <b>旧档中性默认</b>：没有预算源状态 ⇒ {@link GovBudgetPolicy#neutral()}（空类别表）⇒ 三类都不自动付，并发 {@link
 * GovDaily#KIND_ADMIN_PLAN_MISSING} 信号；这是设计书 §4.1"预算空 = 不自动付"的落地，不静默继续旧行为。
 */
public final class GovBudgetExecutionBridge {

  /** 预算周期（tick）；{@code min/cap} 是"每日"口径。 */
  public static final long BUDGET_CYCLE_DAYS = 1L;

  private static final CommodityId GRAIN = EconomyCommodities.GRAIN;

  private static final CommodityId CLOTH = EconomyCommodities.CLOTH;

  /**
   * ★★ <b>本桥"价值"口径的唯一币种（本币 = 银）</b>（A2b / I24 / M7）—— 预算帽的货币腿<b>只</b>认它： 国库里的其他币种既不按 1:1
   * 加进来、也不折算（世界没有汇率），只逐币种具名列进 {@code GOV_BUDGET_FOREIGN_CURRENCY_EXCLUDED} 日志。★ 它是 {@link
   * MoneyVocabulary} 的 {@code SILVER_CURRENCY}（不是就地拼的字符串）。
   */
  private static final CurrencyId BOOK_CURRENCY = MoneyVocabulary.SILVER_CURRENCY;

  private static final Logger LOG = AppLog.time();

  private GovBudgetExecutionBridge() {}

  /**
   * 现算并执行当日的预算分配（纯规划 + 返回可执行件；真正落账由调用方按返回的 oracle/规则表完成）。
   *
   * @param govState GOV 源状态（预算政策 = 唯一权威）
   * @param units 单位状态（GOV 单位/编制/有效位置）
   * @param social 社会状态（军俸桥解析收款家户用）
   * @param economy 经济状态（单位/承诺/家户行）
   * @param accounts 当日唯一账户会话（读国库可用量；执行时也会写同一会话）
   * @param efficiencyByUnit 当日已有的一份效率结果（空缺/服务零告警的判据；与 GovDaily 同源）
   * @param supplyByUnit 当日承诺→两维供给桥结果（空缺告警的供给判据）
   * @param day 世界日
   * @param daysInYear 当日的历法年天数（365/366；行政俸禄折算与 {@link GovDaily} 同源）
   */
  public static DayBudget plan(
      GovState govState,
      UnitState units,
      SocialData social,
      EconomyData economy,
      AccountSession accounts,
      Map<UnitId, GovEfficiency.Efficiency> efficiencyByUnit,
      Map<UnitId, GovernmentServiceLaborBridge.Supply> supplyByUnit,
      long day,
      long daysInYear) {
    Objects.requireNonNull(govState, "govState");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(social, "social");
    Objects.requireNonNull(economy, "economy");
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(efficiencyByUnit, "efficiencyByUnit");
    Objects.requireNonNull(supplyByUnit, "supplyByUnit");

    MilitaryPayRuleBridge.Report militaryReport =
        MilitaryPayRuleBridge.deriveReport(units, social, economy, day);
    GovSalaryRuleBridge.Report salaryReport =
        GovSalaryRuleBridge.deriveReport(govState, units, economy, day);

    Map<UnitId, ResourceVector> militaryDemand = new LinkedHashMap<>();
    for (HouseholdPeriodicAdjustment rule : militaryReport.rules()) {
      if (!PeriodicHouseholdAdjustmentExecutor.isDue(rule, day)) {
        continue;
      }
      UnitId gov = govOfPayer(rule.payer());
      if (gov == null) {
        continue; // 军俸桥的 payer 必是 GovernmentHouseholds.of(masterGov) 造出来的；异常行由桥自己的 gap 承接。
      }
      try {
        addDemand(militaryDemand, gov, resourceVectorOf(rule, day, gov));
      } catch (ArithmeticException e) {
        throw contractFailure("military-demand-overflow", day, gov, e.getMessage());
      }
    }
    Map<UnitId, ResourceVector> salaryDemand = new LinkedHashMap<>();
    Map<String, SalaryBook> salaryBook = new LinkedHashMap<>();
    for (HouseholdPeriodicAdjustment rule : salaryReport.rules()) {
      if (!PeriodicHouseholdAdjustmentExecutor.isDue(rule, day)) {
        continue; // 工资规则冻结为 period=1/starts=1（每日到期）；非到期日不参与当日账本。
      }
      UnitId gov = govOfPayer(rule.payer());
      if (gov == null) {
        continue; // 同军俸：工资桥的 payer 恒为政府家户；异常行由工资桥 gap 承接。
      }
      ResourceVector requested = resourceVectorOf(rule, day, gov);
      salaryBook.put(
          rule.id().value(),
          new SalaryBook(gov, rule.payee().orElseThrow(), requested, ResourceVector.EMPTY));
      try {
        addDemand(salaryDemand, gov, requested);
      } catch (ArithmeticException e) {
        throw contractFailure("salary-demand-overflow", day, gov, e.getMessage());
      }
    }

    List<UnitId> ordered = new ArrayList<>(govState.offices().keySet());
    ordered.sort(Comparator.comparing(UnitId::value));
    Map<UnitId, GovBudgetPlan> plans = new LinkedHashMap<>();
    List<GovDaily.SignalDraft> alerts = new ArrayList<>();
    Map<UnitId, HexCoord> seatByGov = new LinkedHashMap<>();
    for (UnitId gov : ordered) {
      Unit unit = units.units().get(gov);
      if (unit == null) {
        continue; // GovDaily.settle 会为同一损坏状态抛具名 ERROR。
      }
      if (!(unit.module().orElse(null) instanceof GovernmentFormation formation)) {
        continue; // 同上：GOV 编制缺失由 GovDaily.settle 判死。
      }
      try {
        HexCoord at = units.effectivePosition(gov, SimosTimestamp.of(day)).orElse(null);
        if (at != null) {
          seatByGov.put(gov, at);
        }
        GovBudgetPolicy policy = govState.budgetPolicyOrDefault(gov);
        ResourceVector available = treasuryAvailable(accounts, unit, gov, day);
        Map<GovBudgetCategory, ResourceVector> demands = new LinkedHashMap<>();
        demands.put(
            GovBudgetCategory.ADMIN_STIPEND,
            at == null
                ? ResourceVector.EMPTY
                : resourceVectorOf(GovDaily.assessUpkeep(formation, daysInYear)));
        demands.put(
            GovBudgetCategory.MILITARY_STIPEND,
            militaryDemand.getOrDefault(gov, ResourceVector.EMPTY));
        demands.put(
            GovBudgetCategory.ADMIN_SALARY, salaryDemand.getOrDefault(gov, ResourceVector.EMPTY));
        Map<GovBudgetCategory, CategoryAllocation> allocations =
            allocate(policy, demands, available);
        GovBudgetPlan plan = new GovBudgetPlan(gov, available, allocations);
        plans.put(gov, plan);
        if (at != null) {
          GovEfficiency.Efficiency efficiency = efficiencyByUnit.get(gov);
          GovernmentServiceLaborBridge.Supply supply = supplyByUnit.get(gov);
          long committed = salaryReport.committedLaborMilliByGov().getOrDefault(gov, 0L);
          alerts.addAll(
              planAlerts(
                  gov,
                  at,
                  govState.administrationPlanOrDefault(gov),
                  policy,
                  demands,
                  allocations,
                  efficiency,
                  supply,
                  committed,
                  salaryReport,
                  day));
        }
        logPlan(gov, at, policy, available, demands, allocations, day);
      } catch (ArithmeticException e) {
        throw contractFailure("arithmetic-overflow", day, gov, e.getMessage());
      }
    }

    CapResult capped = capBudgetedRules(plans, militaryReport, salaryReport, salaryBook, day);
    return new DayBudget(
        plans,
        capped.rules(),
        capped.budgetedLedgerRules(),
        alerts,
        seatByGov,
        salaryBook,
        capped.ruleGovById(),
        militaryReport,
        salaryReport);
  }

  // ---------------------------------------------------------------------------------------------
  // 分配算法
  // ---------------------------------------------------------------------------------------------

  /** 按类别顺序分配"价值"限额；返回每个已配置类别的请求/限额/缺口明细（保序不可变）。 */
  private static Map<GovBudgetCategory, CategoryAllocation> allocate(
      GovBudgetPolicy policy,
      Map<GovBudgetCategory, ResourceVector> demands,
      ResourceVector available) {
    List<GovBudgetLine> lines = policy.orderedCategories();
    Map<GovBudgetCategory, CategoryAllocation> allocations = new LinkedHashMap<>();
    ResourceVector remaining = available;
    for (int index = 0; index < lines.size(); index++) {
      GovBudgetLine line = lines.get(index);
      ResourceVector requested = demands.getOrDefault(line.category(), ResourceVector.EMPTY);
      long requestedValue = requested.valueInBookCurrency();
      long capEffective =
          line.capPerCycle() == Long.MAX_VALUE
              ? requestedValue
              : Math.min(line.capPerCycle(), requestedValue);
      long floorEffective = Math.min(line.minPerCycle(), capEffective);
      long lowerReserve = 0L;
      for (int lower = index + 1; lower < lines.size(); lower++) {
        GovBudgetLine later = lines.get(lower);
        ResourceVector laterRequested =
            demands.getOrDefault(later.category(), ResourceVector.EMPTY);
        long laterRequestedValue = laterRequested.valueInBookCurrency();
        long laterCapEffective =
            later.capPerCycle() == Long.MAX_VALUE
                ? laterRequestedValue
                : Math.min(later.capPerCycle(), laterRequestedValue);
        lowerReserve =
            Math.addExact(lowerReserve, Math.min(later.minPerCycle(), laterCapEffective));
      }
      long spendable = Math.max(0L, remaining.valueInBookCurrency() - lowerReserve);
      long authorizedValue = Math.min(capEffective, spendable);
      ResourceVector authorized = takeLegs(requested, remaining, authorizedValue);
      remaining = remaining.minus(authorized);
      long floorUnmet = Math.max(0L, floorEffective - authorizedValue);
      long capLimited = requestedValue - capEffective;
      long treasuryLimited = capEffective - authorizedValue;
      allocations.put(
          line.category(),
          new CategoryAllocation(
              line.category(),
              requested,
              authorized,
              line.minPerCycle(),
              line.capPerCycle(),
              lowerReserve,
              floorUnmet,
              capLimited,
              treasuryLimited));
    }
    return allocations;
  }

  /** 固定腿序 grain → cloth → silver，从 {@code remaining} 里取不超过 {@code valueLimit} 的价值（请求/库存双重上限）。 */
  private static ResourceVector takeLegs(
      ResourceVector requested, ResourceVector remaining, long valueLimit) {
    long left = valueLimit;
    long grain = Math.min(Math.min(requested.grainMilli(), remaining.grainMilli()), left);
    left -= grain;
    long cloth = Math.min(Math.min(requested.clothMilli(), remaining.clothMilli()), left);
    left -= cloth;
    long silver = Math.min(Math.min(requested.silverMilli(), remaining.silverMilli()), left);
    return new ResourceVector(grain, cloth, silver);
  }

  /** 对每个类别现算"未配置但确有需求"的名单（计划未设告警用）。 */
  private static List<GovBudgetCategory> unbudgetedWithDemand(
      GovBudgetPolicy policy, Map<GovBudgetCategory, ResourceVector> demands) {
    Set<GovBudgetCategory> configured = new LinkedHashSet<>();
    for (GovBudgetLine line : policy.orderedCategories()) {
      configured.add(line.category());
    }
    List<GovBudgetCategory> unbudgeted = new ArrayList<>();
    for (GovBudgetCategory category :
        List.of(
            GovBudgetCategory.ADMIN_STIPEND,
            GovBudgetCategory.MILITARY_STIPEND,
            GovBudgetCategory.ADMIN_SALARY)) {
      ResourceVector demand = demands.getOrDefault(category, ResourceVector.EMPTY);
      if (!demand.isEmpty() && !configured.contains(category)) {
        unbudgeted.add(category);
      }
    }
    return unbudgeted;
  }

  /** 每个 GOV 的计划告警：计划未设/全 0、服务流量 0、岗位空缺、预算缺口、工资桥结构缺口。 */
  private static List<GovDaily.SignalDraft> planAlerts(
      UnitId gov,
      HexCoord at,
      GovAdministrationPlan administrationPlan,
      GovBudgetPolicy policy,
      Map<GovBudgetCategory, ResourceVector> demands,
      Map<GovBudgetCategory, CategoryAllocation> allocations,
      GovEfficiency.Efficiency efficiency,
      GovernmentServiceLaborBridge.Supply supply,
      long committedLaborMilli,
      GovSalaryRuleBridge.Report salaryReport,
      long day) {
    List<GovDaily.SignalDraft> alerts = new ArrayList<>();
    boolean administrationPlanZero =
        administrationPlan.securityPlannedLaborMilli() == 0L
            && administrationPlan.paperworkPlannedLaborMilli() == 0L;
    boolean budgetPolicyEmpty = policy.orderedCategories().isEmpty();
    boolean budgetPolicyAllZero =
        !budgetPolicyEmpty
            && policy.orderedCategories().stream()
                .allMatch(line -> line.minPerCycle() == 0L && line.capPerCycle() == 0L);
    List<GovBudgetCategory> unbudgeted = unbudgetedWithDemand(policy, demands);
    if (administrationPlanZero
        || budgetPolicyEmpty
        || budgetPolicyAllZero
        || !unbudgeted.isEmpty()) {
      Map<String, Long> evidence = new LinkedHashMap<>();
      evidence.put("administrationPlanZero", administrationPlanZero ? 1L : 0L);
      evidence.put("budgetPolicyEmpty", budgetPolicyEmpty ? 1L : 0L);
      evidence.put("budgetPolicyAllZero", budgetPolicyAllZero ? 1L : 0L);
      evidence.put("unbudgetedCategories", (long) unbudgeted.size());
      evidence.put("securityPlannedLaborMilli", administrationPlan.securityPlannedLaborMilli());
      evidence.put("paperworkPlannedLaborMilli", administrationPlan.paperworkPlannedLaborMilli());
      alerts.add(
          new GovDaily.SignalDraft(
              at,
              GovDaily.KIND_ADMIN_PLAN_MISSING,
              1L,
              evidence,
              "编制/预算计划未设或全 0：administrationPlanZero="
                  + administrationPlanZero
                  + " budgetPolicyEmpty="
                  + budgetPolicyEmpty
                  + " budgetPolicyAllZero="
                  + budgetPolicyAllZero
                  + " unbudgeted="
                  + unbudgeted
                  + "（只告警，不自动改计划/税率）"));
    }
    if (committedLaborMilli == 0L) {
      Map<String, Long> evidence = new LinkedHashMap<>();
      evidence.put("committedLaborMilli", 0L);
      evidence.put("committedHouseholds", 0L);
      alerts.add(
          new GovDaily.SignalDraft(
              at,
              GovDaily.KIND_ADMIN_SERVICE_FLOW_ZERO,
              1L,
              evidence,
              "政府服务流量为 0：该 GOV 没有任何 GOV_SERVICE 承诺劳动（只告警，不自动招募）"));
    }
    if (efficiency != null && supply != null) {
      // ★★ Z7d-1：空缺判据用**承诺**（职位），不用有效供给 —— 饥饿 cap 出来的 0 不是"岗位空缺无法填"；
      //   有效供给 vs 承诺的缺口另走 GOV_SERVICE_UNDERFED INFO + simos.gov.info 的 underfed 字段。
      boolean securityVacancy =
          efficiency.securityDemandLaborMilli() > 0L && supply.committedSecurityLaborMilli() == 0L;
      boolean paperworkVacancy =
          efficiency.paperworkDemandLaborMilli() > 0L
              && supply.committedPaperworkLaborMilli() == 0L;
      if (securityVacancy || paperworkVacancy) {
        Map<String, Long> evidence = new LinkedHashMap<>();
        evidence.put("securityDemandLaborMilli", efficiency.securityDemandLaborMilli());
        evidence.put("securitySupplyLaborMilli", supply.securityLaborMilli());
        evidence.put("securityCommittedLaborMilli", supply.committedSecurityLaborMilli());
        evidence.put("paperworkDemandLaborMilli", efficiency.paperworkDemandLaborMilli());
        evidence.put("paperworkSupplyLaborMilli", supply.paperworkLaborMilli());
        evidence.put("paperworkCommittedLaborMilli", supply.committedPaperworkLaborMilli());
        evidence.put(
            "vacancyDimensions", (securityVacancy ? 1L : 0L) + (paperworkVacancy ? 1L : 0L));
        alerts.add(
            new GovDaily.SignalDraft(
                at,
                GovDaily.KIND_ADMIN_VACANCY,
                1L,
                evidence,
                "岗位空缺无法填：需求 > 0 的维度承诺供给 = 0（security="
                    + securityVacancy
                    + " paperwork="
                    + paperworkVacancy
                    + "；V1 无自动招募，只告警）"));
      }
    }
    long requestedTotal = 0L;
    long authorizedTotal = 0L;
    long capLimitedTotal = 0L;
    long treasuryLimitedTotal = 0L;
    long floorUnmetTotal = 0L;
    List<String> shortfallCategories = new ArrayList<>();
    for (CategoryAllocation allocation : allocations.values()) {
      if (!allocation.shortfall()) {
        continue;
      }
      requestedTotal = Math.addExact(requestedTotal, allocation.requested().valueInBookCurrency());
      authorizedTotal =
          Math.addExact(authorizedTotal, allocation.authorized().valueInBookCurrency());
      capLimitedTotal = Math.addExact(capLimitedTotal, allocation.capLimitedValue());
      treasuryLimitedTotal = Math.addExact(treasuryLimitedTotal, allocation.treasuryLimitedValue());
      floorUnmetTotal = Math.addExact(floorUnmetTotal, allocation.floorUnmetValue());
      shortfallCategories.add(
          allocation.category()
              + "(requested="
              + allocation.requested().valueInBookCurrency()
              + ",authorized="
              + allocation.authorized().valueInBookCurrency()
              + ",cap="
              + allocation.capPerCycle()
              + ",min="
              + allocation.minPerCycle()
              + ")");
    }
    if (!shortfallCategories.isEmpty()) {
      Map<String, Long> evidence = new LinkedHashMap<>();
      evidence.put("requestedValue", requestedTotal);
      evidence.put("authorizedValue", authorizedTotal);
      evidence.put("shortfallValue", requestedTotal - authorizedTotal);
      evidence.put("capLimitedValue", capLimitedTotal);
      evidence.put("treasuryLimitedValue", treasuryLimitedTotal);
      evidence.put("floorUnmetValue", floorUnmetTotal);
      evidence.put("categories", (long) shortfallCategories.size());
      alerts.add(
          new GovDaily.SignalDraft(
              at,
              GovDaily.KIND_ADMIN_BUDGET_SHORTFALL,
              1L,
              evidence,
              "国库预算不足/未达下限（按预算顺序分配）：" + shortfallCategories + "（只告警，不自动注资/调计划/调税）"));
    }
    List<String> salaryGaps = new ArrayList<>();
    for (String gap : salaryReport.gaps()) {
      if (gap.startsWith("unit=" + gov.value() + " ")) {
        salaryGaps.add(gap);
      }
    }
    if (!salaryGaps.isEmpty()) {
      EventLog.channel(LOG)
          .error(
              LogEvent.of(
                  "GOV_SALARY_CONTRACT_GAP",
                  AppLogSource.DAILY_LOOP,
                  "day",
                  day,
                  "unit",
                  gov.value(),
                  "count",
                  salaryGaps.size(),
                  "first",
                  salaryGaps.get(0)));
      Map<String, Long> evidence = new LinkedHashMap<>();
      evidence.put("gaps", (long) salaryGaps.size());
      evidence.put("unit", 1L);
      alerts.add(
          new GovDaily.SignalDraft(
              at,
              GovDaily.KIND_ADMIN_CONTRACT,
              1L,
              evidence,
              "官吏工资契约异常：" + salaryGaps.get(0) + "（ERROR，不自动修复）"));
    }
    if (!alerts.isEmpty()) {
      List<String> kinds = new ArrayList<>(alerts.size());
      for (GovDaily.SignalDraft alert : alerts) {
        kinds.add(alert.kind());
      }
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "GOV_BUDGET_ALERT",
                  AppLogSource.DAILY_LOOP,
                  "day",
                  day,
                  "unit",
                  gov.value(),
                  "count",
                  alerts.size(),
                  "kinds",
                  kinds));
    }
    return alerts;
  }

  // ---------------------------------------------------------------------------------------------
  // 规则裁剪
  // ---------------------------------------------------------------------------------------------

  /** 把当日军俸/工资规则按类别的已授权限额逐腿裁剪（id 升序 = 确定性分配序）。 */
  private static CapResult capBudgetedRules(
      Map<UnitId, GovBudgetPlan> plans,
      MilitaryPayRuleBridge.Report militaryReport,
      GovSalaryRuleBridge.Report salaryReport,
      Map<String, SalaryBook> salaryBook,
      long day) {
    List<RuleRef> refs = new ArrayList<>();
    for (HouseholdPeriodicAdjustment rule : militaryReport.rules()) {
      if (PeriodicHouseholdAdjustmentExecutor.isDue(rule, day)) {
        refs.add(new RuleRef(rule, GovBudgetCategory.MILITARY_STIPEND));
      }
    }
    for (HouseholdPeriodicAdjustment rule : salaryReport.rules()) {
      if (PeriodicHouseholdAdjustmentExecutor.isDue(rule, day)) {
        refs.add(new RuleRef(rule, GovBudgetCategory.ADMIN_SALARY));
      }
    }
    refs.sort(Comparator.comparing(ref -> ref.rule().id().value()));
    Map<UnitId, Map<GovBudgetCategory, ResourceVector>> remaining = new LinkedHashMap<>();
    for (GovBudgetPlan plan : plans.values()) {
      Map<GovBudgetCategory, ResourceVector> perCategory = new LinkedHashMap<>();
      for (Map.Entry<GovBudgetCategory, CategoryAllocation> entry : plan.allocations().entrySet()) {
        perCategory.put(entry.getKey(), entry.getValue().authorized());
      }
      remaining.put(plan.gov(), perCategory);
    }
    List<HouseholdPeriodicAdjustment> cappedRules = new ArrayList<>();
    List<PeriodicHouseholdAdjustmentExecutor.BudgetedRule> budgetedLedgerRules = new ArrayList<>();
    Map<String, UnitId> ruleGovById = new LinkedHashMap<>();
    for (RuleRef ref : refs) {
      UnitId gov = govOfPayer(ref.rule().payer());
      if (gov == null) {
        EventLog.channel(LOG)
            .error(
                LogEvent.of(
                    "GOV_BUDGET_CONTRACT_GAP",
                    AppLogSource.DAILY_LOOP,
                    "day",
                    day,
                    "rule",
                    ref.rule().id().value(),
                    "reason",
                    "payer-not-government-household"));
        continue;
      }
      ResourceVector authorizedTotal =
          plans.containsKey(gov) ? plans.get(gov).authorized(ref.category()) : ResourceVector.EMPTY;
      if (!plans.containsKey(gov)) {
        EventLog.channel(LOG)
            .error(
                LogEvent.of(
                    "GOV_BUDGET_CONTRACT_GAP",
                    AppLogSource.DAILY_LOOP,
                    "day",
                    day,
                    "rule",
                    ref.rule().id().value(),
                    "reason",
                    "gov-not-in-active-budget-plan",
                    "gov",
                    gov.value()));
      }
      Map<GovBudgetCategory, ResourceVector> perCategory =
          remaining.computeIfAbsent(gov, ignored -> new LinkedHashMap<>());
      ResourceVector availableForCategory =
          perCategory.computeIfAbsent(ref.category(), ignored -> authorizedTotal);
      ResourceVector requested = resourceVectorOf(ref.rule(), day, gov);
      ResourceVector taken = minVector(requested, availableForCategory);
      perCategory.put(ref.category(), availableForCategory.minus(taken));
      ruleGovById.put(ref.rule().id().value(), gov);
      if (ref.category() == GovBudgetCategory.ADMIN_SALARY) {
        SalaryBook book = salaryBook.get(ref.rule().id().value());
        if (book != null) {
          book.authorized = taken;
        }
      }
      // ★★ Z7b：逐腿账本对**每一条到期规则**都建（含全零授权）——原始请求不再被提前丢掉；执行器据此判
      //   EXECUTED/PARTIAL/SKIPPED 与逐腿 requested/authorized/paid/shortfall。
      budgetedLedgerRules.add(
          new PeriodicHouseholdAdjustmentExecutor.BudgetedRule(
              ref.rule(), authorizedGoodsOf(taken), authorizedMoneyOf(taken)));
      // ★ 兼容读口/旧调用方：保留“裁剪后、零腿不落”的旧形状；生产执行路径已改用上面的逐腿账本。
      if (!taken.isEmpty()) {
        cappedRules.add(cappedRule(ref.rule(), taken));
      }
    }
    return new CapResult(cappedRules, budgetedLedgerRules, ruleGovById);
  }

  /** 授权向量的商品腿 → 只含 &gt; 0 腿的保序表（与 {@link #cappedRule} 同一口径）。 */
  private static Map<CommodityId, Long> authorizedGoodsOf(ResourceVector taken) {
    Map<CommodityId, Long> goods = new LinkedHashMap<>();
    if (taken.grainMilli() > 0L) {
      goods.put(GRAIN, taken.grainMilli());
    }
    if (taken.clothMilli() > 0L) {
      goods.put(CLOTH, taken.clothMilli());
    }
    return goods;
  }

  /** 授权向量的货币腿 → 只含 &gt; 0 腿的保序表。 */
  private static Map<CurrencyId, Long> authorizedMoneyOf(ResourceVector taken) {
    Map<CurrencyId, Long> money = new LinkedHashMap<>();
    if (taken.silverMilli() > 0L) {
      money.put(BOOK_CURRENCY, taken.silverMilli());
    }
    return money;
  }

  /** 用限额向量重建一条规则（id/周期/reason/policySource 逐值保留，只改两条腿）。 */
  private static HouseholdPeriodicAdjustment cappedRule(
      HouseholdPeriodicAdjustment rule, ResourceVector taken) {
    Map<CommodityId, Long> goods = new LinkedHashMap<>();
    if (taken.grainMilli() > 0L) {
      goods.put(GRAIN, taken.grainMilli());
    }
    if (taken.clothMilli() > 0L) {
      goods.put(CLOTH, taken.clothMilli());
    }
    Map<CurrencyId, Long> money = new LinkedHashMap<>();
    if (taken.silverMilli() > 0L) {
      money.put(BOOK_CURRENCY, taken.silverMilli());
    }
    return new HouseholdPeriodicAdjustment(
        rule.id(),
        rule.payer(),
        rule.payee(),
        goods,
        money,
        rule.reason(),
        rule.periodDays(),
        rule.phaseDay(),
        rule.startsOnDay(),
        rule.expiresOnDay(),
        rule.policySource());
  }

  // ---------------------------------------------------------------------------------------------
  // 返回的当日执行件
  // ---------------------------------------------------------------------------------------------

  /** {@link #plan} 的完整产物：国库 oracle + 已裁剪规则 + 告警 + 供执行后读账的账本。 */
  public static final class DayBudget {

    private final Map<UnitId, GovBudgetPlan> plans;
    private final List<HouseholdPeriodicAdjustment> budgetedRules;
    private final List<PeriodicHouseholdAdjustmentExecutor.BudgetedRule> budgetedLedgerRules;
    private final List<GovDaily.SignalDraft> alerts;
    private final Map<UnitId, HexCoord> seatByGov;
    private final Map<String, SalaryBook> salaryBook;
    private final Map<String, UnitId> ruleGovById;
    private final MilitaryPayRuleBridge.Report militaryReport;
    private final GovSalaryRuleBridge.Report salaryReport;

    private DayBudget(
        Map<UnitId, GovBudgetPlan> plans,
        List<HouseholdPeriodicAdjustment> budgetedRules,
        List<PeriodicHouseholdAdjustmentExecutor.BudgetedRule> budgetedLedgerRules,
        List<GovDaily.SignalDraft> alerts,
        Map<UnitId, HexCoord> seatByGov,
        Map<String, SalaryBook> salaryBook,
        Map<String, UnitId> ruleGovById,
        MilitaryPayRuleBridge.Report militaryReport,
        GovSalaryRuleBridge.Report salaryReport) {
      this.plans = Collections.unmodifiableMap(new LinkedHashMap<>(plans));
      this.budgetedRules = Collections.unmodifiableList(new ArrayList<>(budgetedRules));
      this.budgetedLedgerRules = Collections.unmodifiableList(new ArrayList<>(budgetedLedgerRules));
      this.alerts = Collections.unmodifiableList(new ArrayList<>(alerts));
      this.seatByGov = Collections.unmodifiableMap(new LinkedHashMap<>(seatByGov));
      this.salaryBook = Collections.unmodifiableMap(new LinkedHashMap<>(salaryBook));
      this.ruleGovById = Collections.unmodifiableMap(new LinkedHashMap<>(ruleGovById));
      this.militaryReport = Objects.requireNonNull(militaryReport, "militaryReport");
      this.salaryReport = Objects.requireNonNull(salaryReport, "salaryReport");
    }

    /** 预算类别限额外的 {@link GovDaily.PaymentOracle}（行政俸禄逐资源限额在此生效）。 */
    public GovDaily.PaymentOracle upkeepOracle(GovernmentUpkeepOracle delegate) {
      return new BudgetedUpkeepOracle(plans, delegate);
    }

    /**
     * <b>旧兼容读口</b>：已按预算限额裁剪、零腿不落的规则表（旧调用方/既有测试用）。★ <b>生产执行不要再用它</b>——它丢了 原始请求与逐腿授权（D4 根因）；执行请走
     * {@link #budgetedLedgerRules()} + {@code applyBudgeted}。
     */
    public List<HouseholdPeriodicAdjustment> budgetedRules() {
      return budgetedRules;
    }

    /**
     * ★★ <b>Z7b 逐腿账本</b>：当日军俸 + 工资的<b>每一条到期规则</b>（含全零授权）都带着原始请求与逐腿授权交给执行器。交给 {@link
     * PeriodicHouseholdAdjustmentExecutor#applyBudgeted}；执行器不再只吃裁剪后的规则。
     */
    public List<PeriodicHouseholdAdjustmentExecutor.BudgetedRule> budgetedLedgerRules() {
      return budgetedLedgerRules;
    }

    /** 规划期告警（计划未设/服务零/空缺/预算缺口/工资桥结构缺口）。 */
    public List<GovDaily.SignalDraft> alerts() {
      return alerts;
    }

    public MilitaryPayRuleBridge.Report militaryReport() {
      return militaryReport;
    }

    public GovSalaryRuleBridge.Report salaryReport() {
      return salaryReport;
    }

    /** 单个 GOV 的当日预算计划（诊断/Z3c-2 读口预留；未在 offices 中 ⇒ null）。 */
    public GovBudgetPlan plan(UnitId gov) {
      return plans.get(gov);
    }

    /**
     * 执行后由调用方折 1~N 条 {@code ADMIN_CONTRACT} 信号：只把账户缺失/服务拒绝这类<b>契约异常</b>入信号； {@code
     * no-payable-leg}（国库不足）与 {@code budget-authorized-zero}（预算逐腿裁到 0）都不算契约异常（已由预算缺口/工资缺口读数承接）。
     */
    public List<GovDaily.SignalDraft> executionContractAlerts(
        PeriodicHouseholdAdjustmentExecutor.Report report, long day) {
      Map<UnitId, List<String>> byGov = new LinkedHashMap<>();
      for (String gap : report.gaps()) {
        if (gap.contains("no-payable-leg") || gap.contains("budget-authorized-zero")) {
          continue;
        }
        int separator = gap.indexOf(": ");
        String ruleId = separator > 0 ? gap.substring(0, separator) : gap;
        UnitId gov = ruleGovById.get(ruleId);
        if (gov == null) {
          EventLog.channel(LOG)
              .error(
                  LogEvent.of(
                      "GOV_BUDGET_CONTRACT_GAP",
                      AppLogSource.DAILY_LOOP,
                      "day",
                      day,
                      "rule",
                      ruleId,
                      "reason",
                      "gap-rule-not-in-budget-execution"));
          continue;
        }
        byGov.computeIfAbsent(gov, ignored -> new ArrayList<>()).add(gap);
      }
      List<GovDaily.SignalDraft> contractAlerts = new ArrayList<>();
      for (Map.Entry<UnitId, List<String>> entry : byGov.entrySet()) {
        UnitId gov = entry.getKey();
        EventLog.channel(LOG)
            .error(
                LogEvent.of(
                    "GOV_BUDGET_EXECUTION_CONTRACT_GAP",
                    AppLogSource.DAILY_LOOP,
                    "day",
                    day,
                    "unit",
                    gov.value(),
                    "count",
                    entry.getValue().size(),
                    "first",
                    entry.getValue().get(0)));
        HexCoord at = seatByGov.get(gov);
        if (at == null) {
          continue; // 无有效位置 = 无信号落点（GovDaily 对无座席同样是"不产信号"口径）；ERROR 已在上面记名。
        }
        Map<String, Long> evidence = new LinkedHashMap<>();
        evidence.put("gaps", (long) entry.getValue().size());
        evidence.put("unit", 1L);
        contractAlerts.add(
            new GovDaily.SignalDraft(
                at,
                GovDaily.KIND_ADMIN_CONTRACT,
                1L,
                evidence,
                "预算执行契约异常：" + entry.getValue().get(0) + "（ERROR，不自动修复）"));
      }
      return contractAlerts;
    }

    /**
     * 工资执行后的具名读账：逐户请求/限额/实付/缺口 + 每 GOV 一条 INFO；返回可折进跨日汇总的 {@link SalaryTotals}。<b>绝不静默 0</b>：请求
     * &gt; 0 而实付 0 的规则必有一条具名原因。
     */
    public SalaryTotals logSalaryExecution(
        PeriodicHouseholdAdjustmentExecutor.Report report, long day) {
      Map<String, PeriodicHouseholdAdjustmentExecutor.RuleReadout> readouts = new LinkedHashMap<>();
      for (PeriodicHouseholdAdjustmentExecutor.RuleReadout readout : report.rules()) {
        readouts.put(readout.ruleId().value(), readout);
      }
      Map<UnitId, SalaryGovTotals> byGov = new LinkedHashMap<>();
      SalaryTotals totals = new SalaryTotals();
      for (Map.Entry<String, SalaryBook> entry : salaryBook.entrySet()) {
        String ruleId = entry.getKey();
        SalaryBook book = entry.getValue();
        PeriodicHouseholdAdjustmentExecutor.RuleReadout readout = readouts.get(ruleId);
        ResourceVector paid =
            readout == null
                ? ResourceVector.EMPTY
                : resourceVectorOf(readout.paidGoods(), readout.paidMoney());
        ResourceVector shortfall = book.requested.minus(paid);
        ResourceVector budgetShortfall = book.requested.minus(book.authorized);
        String gapReason = salaryGapReason(book, readout, paid);
        SalaryGovTotals govTotals =
            byGov.computeIfAbsent(book.gov, ignored -> new SalaryGovTotals());
        govTotals.households++;
        govTotals.requestedGrain += book.requested.grainMilli();
        govTotals.requestedSilver += book.requested.silverMilli();
        govTotals.paidGrain += paid.grainMilli();
        govTotals.paidSilver += paid.silverMilli();
        govTotals.shortfallGrain += shortfall.grainMilli();
        govTotals.shortfallSilver += shortfall.silverMilli();
        totals.households++;
        totals.paidGrainMilli = Math.addExact(totals.paidGrainMilli, paid.grainMilli());
        totals.paidSilverMilli = Math.addExact(totals.paidSilverMilli, paid.silverMilli());
        totals.shortfallGrainMilli =
            Math.addExact(totals.shortfallGrainMilli, shortfall.grainMilli());
        totals.shortfallSilverMilli =
            Math.addExact(totals.shortfallSilverMilli, shortfall.silverMilli());
        totals.budgetShortfallGrainMilli =
            Math.addExact(totals.budgetShortfallGrainMilli, budgetShortfall.grainMilli());
        totals.budgetShortfallSilverMilli =
            Math.addExact(totals.budgetShortfallSilverMilli, budgetShortfall.silverMilli());
        if (shortfall.isNotEmpty()) {
          if (paid.isEmpty()) {
            totals.noPayHouseholds++;
          }
          EventLog.channel(LOG)
              .debug(
                  LogEvent.of(
                      "GOV_ADMIN_SALARY_SHORTFALL",
                      AppLogSource.DAILY_LOOP,
                      "day",
                      day,
                      "unit",
                      book.gov.value(),
                      "household",
                      book.household.value(),
                      "requestedGrain",
                      book.requested.grainMilli(),
                      "requestedSilver",
                      book.requested.silverMilli(),
                      "authorizedGrain",
                      book.authorized.grainMilli(),
                      "authorizedSilver",
                      book.authorized.silverMilli(),
                      "paidGrain",
                      paid.grainMilli(),
                      "paidSilver",
                      paid.silverMilli(),
                      "shortfallGrain",
                      shortfall.grainMilli(),
                      "shortfallSilver",
                      shortfall.silverMilli(),
                      "reason",
                      gapReason));
        }
      }
      for (Map.Entry<UnitId, SalaryGovTotals> entry : byGov.entrySet()) {
        SalaryGovTotals govTotals = entry.getValue();
        EventLog.channel(LOG)
            .info(
                LogEvent.of(
                    "GOV_ADMIN_SALARY_DAY",
                    AppLogSource.DAILY_LOOP,
                    "day",
                    day,
                    "unit",
                    entry.getKey().value(),
                    "households",
                    govTotals.households,
                    "requestedGrain",
                    govTotals.requestedGrain,
                    "requestedSilver",
                    govTotals.requestedSilver,
                    "paidGrain",
                    govTotals.paidGrain,
                    "paidSilver",
                    govTotals.paidSilver,
                    "shortfallGrain",
                    govTotals.shortfallGrain,
                    "shortfallSilver",
                    govTotals.shortfallSilver));
      }
      return totals;
    }

    /**
     * ★★ <b>Z7d-2：逐户 ADMIN_SALARY 缺口读数</b>（逃亡驱动的唯一输入之一）——按 {@code SalaryBook} 的 {@code requested −
     * paid} 逐腿算缺口，按家户 id 升序合并（同一户多条规则 ⇒ 逐腿相加）。只含缺口 > 0 的家户； 返回值不是"第二个工资账"，只是当日执行读数的逐户切片。
     *
     * <p>★ 与 {@link #logSalaryExecution} 共用同一份 {@code salaryBook} 与 {@link
     * PeriodicHouseholdAdjustmentExecutor.Report}，不重算请求/授权/实付。
     */
    public Map<HouseholdId, ResourceVector> salaryShortfallByHousehold(
        PeriodicHouseholdAdjustmentExecutor.Report report) {
      Objects.requireNonNull(report, "report（工资执行报告；没有执行也要显式给空报告）");
      Map<String, PeriodicHouseholdAdjustmentExecutor.RuleReadout> readouts = new LinkedHashMap<>();
      for (PeriodicHouseholdAdjustmentExecutor.RuleReadout readout : report.rules()) {
        readouts.put(readout.ruleId().value(), readout);
      }
      List<Map.Entry<String, SalaryBook>> entries = new ArrayList<>(salaryBook.entrySet());
      entries.sort(Comparator.comparing(entry -> entry.getValue().household.value()));
      Map<HouseholdId, ResourceVector> merged = new LinkedHashMap<>();
      for (Map.Entry<String, SalaryBook> entry : entries) {
        SalaryBook book = entry.getValue();
        PeriodicHouseholdAdjustmentExecutor.RuleReadout readout = readouts.get(entry.getKey());
        ResourceVector paid =
            readout == null
                ? ResourceVector.EMPTY
                : resourceVectorOf(readout.paidGoods(), readout.paidMoney());
        ResourceVector shortfall = book.requested.minus(paid);
        if (shortfall.isEmpty()) {
          continue;
        }
        ResourceVector previous = merged.get(book.household);
        if (previous == null) {
          merged.put(book.household, shortfall);
        } else {
          merged.put(
              book.household,
              new ResourceVector(
                  Math.addExact(previous.grainMilli(), shortfall.grainMilli()),
                  Math.addExact(previous.clothMilli(), shortfall.clothMilli()),
                  Math.addExact(previous.silverMilli(), shortfall.silverMilli())));
        }
      }
      return Collections.unmodifiableMap(merged);
    }

    /** 工资缺口的具名原因（账户缺失/服务拒绝/预算限额/国库可用不足/已付）。 */
    private static String salaryGapReason(
        SalaryBook book,
        PeriodicHouseholdAdjustmentExecutor.RuleReadout readout,
        ResourceVector paid) {
      if (readout == null) {
        return book.authorized.isEmpty() ? "budget-authorized-zero" : "rule-not-executed";
      }
      String gap = readout.gap();
      if (gap != null) {
        if (gap.contains("payer-account-missing")) {
          return "payer-account-missing";
        }
        if (gap.contains("payee-account-missing")) {
          return "payee-account-missing";
        }
        if (gap.contains("service-rejected")) {
          return "service-rejected";
        }
      }
      if (paid.isEmpty() && !book.requested.isEmpty()) {
        return book.authorized.isEmpty() ? "budget-authorized-zero" : "treasury-available-zero";
      }
      if (paid.valueInBookCurrency() < book.requested.valueInBookCurrency()) {
        return book.authorized.valueInBookCurrency() < book.requested.valueInBookCurrency()
            ? "budget-or-treasury-limited"
            : "treasury-drained-before-execution";
      }
      return "paid";
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 值类型与工具
  // ---------------------------------------------------------------------------------------------

  /**
   * 三资源腿的"毫"向量（grain/cloth/本币；价值口径见类注与 {@link #valueInBookCurrency()}）。
   *
   * <p>★ 货币腿<b>只有一种币</b>（{@link #BOOK_CURRENCY}）：{@code silverMilli} 这个名字是既有的线格式字段名，语义 = "本币腿"。
   * 国库里的其他币种<b>不进</b>本向量（{@link #treasuryAvailable} 会逐币种具名列出来）。
   */
  public record ResourceVector(long grainMilli, long clothMilli, long silverMilli) {

    public static final ResourceVector EMPTY = new ResourceVector(0L, 0L, 0L);

    public ResourceVector {
      if (grainMilli < 0L || clothMilli < 0L || silverMilli < 0L) {
        throw new IllegalArgumentException(
            "ResourceVector 三腿必须 ≥ 0: " + grainMilli + "/" + clothMilli + "/" + silverMilli);
      }
    }

    /**
     * ★★ <b>"价值"合计（毫；A2b 具名为单一币种口径，I24 / M7）</b>：{@code 商品腿(grain+cloth) + 货币腿(本币)}，三腿都是"毫"。
     *
     * <p>★★ <b>为什么名字里必须有币种</b>：改前它叫无名 {@code value()}，读的人<b>看不出</b>这是"哪个币种的口径" —— 而这条算式正是 M7
     * 点名的求和点之一（"跨币种直接相加"）。现在：① 货币腿只可能是 {@link #BOOK_CURRENCY}（{@link #bookCurrency()} 当场可读， {@link
     * #treasuryAvailable} 只往这一腿装本币）；② 商品腿按类注的"无价格表 ⇒ 毫 1:1"口径，与币种无关；③ 任何<b>其他</b>币种的
     * 余额/请求都<b>不进</b>这条算式（请求侧非本币 ⇒ 具名拒 {@code unknown-currency}；国库侧非本币 ⇒ 具名排除日志）。 ⇒ 这不再是"默认 1:1
     * 相加"，而是**写明的单一币种口径**。
     *
     * <p>★ 溢出 ⇒ {@code ArithmeticException}（调用方折契约 ERROR）。
     */
    public long valueInBookCurrency() {
      return Math.addExact(Math.addExact(grainMilli, clothMilli), silverMilli);
    }

    /** 本向量的货币腿是哪一种币（{@code valueInBookCurrency()} 的口径；只有一个答案 ⇒ 不是"默认 1:1"）。 */
    public CurrencyId bookCurrency() {
      return BOOK_CURRENCY;
    }

    public boolean isEmpty() {
      return grainMilli == 0L && clothMilli == 0L && silverMilli == 0L;
    }

    public boolean isNotEmpty() {
      return !isEmpty();
    }

    ResourceVector minus(ResourceVector other) {
      return new ResourceVector(
          grainMilli - other.grainMilli,
          clothMilli - other.clothMilli,
          silverMilli - other.silverMilli);
    }
  }

  /** 一个类别当日的请求/授权/缺口读数（诊断用；不落状态）。 */
  public record CategoryAllocation(
      GovBudgetCategory category,
      ResourceVector requested,
      ResourceVector authorized,
      long minPerCycle,
      long capPerCycle,
      long lowerReserveValue,
      long floorUnmetValue,
      long capLimitedValue,
      long treasuryLimitedValue) {

    public CategoryAllocation {
      Objects.requireNonNull(category, "category");
      Objects.requireNonNull(requested, "requested");
      Objects.requireNonNull(authorized, "authorized");
      if (minPerCycle < 0L || capPerCycle < 0L) {
        throw new IllegalArgumentException("min/cap 不得为负: " + minPerCycle + "/" + capPerCycle);
      }
      if (authorized.grainMilli() > requested.grainMilli()
          || authorized.clothMilli() > requested.clothMilli()
          || authorized.silverMilli() > requested.silverMilli()) {
        throw new IllegalArgumentException(
            "authorized 不得超过 requested（逐腿）: " + requested + " -> " + authorized);
      }
      if (lowerReserveValue < 0L
          || floorUnmetValue < 0L
          || capLimitedValue < 0L
          || treasuryLimitedValue < 0L) {
        throw new IllegalArgumentException("CategoryAllocation 缺口读数不得为负");
      }
    }

    /** 请求 > 授权（任一条腿）即为有缺口。 */
    public boolean shortfall() {
      return authorized.grainMilli() < requested.grainMilli()
          || authorized.clothMilli() < requested.clothMilli()
          || authorized.silverMilli() < requested.silverMilli();
    }
  }

  /** 单个 GOV 当日预算计划（国库可用量 + 每个已配置类别的分配读数）。 */
  public record GovBudgetPlan(
      UnitId gov,
      ResourceVector availableAtPlan,
      Map<GovBudgetCategory, CategoryAllocation> allocations) {

    public GovBudgetPlan {
      Objects.requireNonNull(gov, "gov");
      Objects.requireNonNull(availableAtPlan, "availableAtPlan");
      Objects.requireNonNull(allocations, "allocations");
      Map<GovBudgetCategory, CategoryAllocation> copy = new LinkedHashMap<>();
      for (Map.Entry<GovBudgetCategory, CategoryAllocation> entry : allocations.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException("allocations 键/值不得为 null");
        }
        if (entry.getKey() != entry.getValue().category()) {
          throw new IllegalArgumentException("allocations 键必须等于类别: " + entry.getKey());
        }
        copy.put(entry.getKey(), entry.getValue());
      }
      allocations = Collections.unmodifiableMap(copy); // ★ 保序冻在赋值处
    }

    /** 某类别的计划；未配置 ⇒ null。 */
    public CategoryAllocation allocation(GovBudgetCategory category) {
      return allocations.get(category);
    }

    /** 某类别的授权向量；未配置 ⇒ 空向量（= 不付）。 */
    public ResourceVector authorized(GovBudgetCategory category) {
      CategoryAllocation allocation = allocations.get(category);
      return allocation == null ? ResourceVector.EMPTY : allocation.authorized();
    }
  }

  /** 工资执行的跨日汇总读数（只进日志，不进状态）。 */
  public static final class SalaryTotals {
    private long households;
    private long paidGrainMilli;
    private long paidSilverMilli;
    private long shortfallGrainMilli;
    private long shortfallSilverMilli;
    private long budgetShortfallGrainMilli;
    private long budgetShortfallSilverMilli;
    private long noPayHouseholds;

    public long households() {
      return households;
    }

    public long paidGrainMilli() {
      return paidGrainMilli;
    }

    public long paidSilverMilli() {
      return paidSilverMilli;
    }

    public long shortfallGrainMilli() {
      return shortfallGrainMilli;
    }

    public long shortfallSilverMilli() {
      return shortfallSilverMilli;
    }

    public long budgetShortfallGrainMilli() {
      return budgetShortfallGrainMilli;
    }

    public long budgetShortfallSilverMilli() {
      return budgetShortfallSilverMilli;
    }

    public long noPayHouseholds() {
      return noPayHouseholds;
    }
  }

  /** 行政俸禄 oracle：把 GovDaily 的逐资源请求先裁到预算授权量，再委托既有国库扣款。 */
  private static final class BudgetedUpkeepOracle implements GovDaily.PaymentOracle {

    private final GovernmentUpkeepOracle delegate;
    private final Map<UnitId, Map<GovDaily.GovResource, Long>> remaining = new LinkedHashMap<>();

    private BudgetedUpkeepOracle(
        Map<UnitId, GovBudgetPlan> plans, GovernmentUpkeepOracle delegate) {
      this.delegate = Objects.requireNonNull(delegate, "delegate");
      for (GovBudgetPlan plan : plans.values()) {
        CategoryAllocation allocation = plan.allocation(GovBudgetCategory.ADMIN_STIPEND);
        if (allocation == null) {
          continue;
        }
        Map<GovDaily.GovResource, Long> resources = new LinkedHashMap<>();
        ResourceVector authorized = allocation.authorized();
        if (authorized.grainMilli() > 0L) {
          resources.put(new GovDaily.Commodity(GRAIN), authorized.grainMilli());
        }
        if (authorized.clothMilli() > 0L) {
          resources.put(new GovDaily.Commodity(CLOTH), authorized.clothMilli());
        }
        if (authorized.silverMilli() > 0L) {
          resources.put(new GovDaily.Money(BOOK_CURRENCY), authorized.silverMilli());
        }
        if (!resources.isEmpty()) {
          remaining.put(plan.gov(), resources);
        }
      }
    }

    @Override
    public long pay(
        UnitId unitId, HexCoord at, GovDaily.GovResource resource, long requested, long day) {
      if (requested <= 0L) {
        return 0L;
      }
      Map<GovDaily.GovResource, Long> resources = remaining.get(unitId);
      if (resources == null) {
        return 0L;
      }
      Long cap = resources.get(resource);
      if (cap == null || cap <= 0L) {
        return 0L;
      }
      long capped = Math.min(cap, requested);
      long paid = delegate.pay(unitId, at, resource, capped, day);
      if (paid < 0L || paid > capped) {
        throw new IllegalStateException("预算 oracle 委托的实付越界: paid=" + paid + " capped=" + capped);
      }
      resources.put(resource, cap - paid);
      return paid;
    }
  }

  /** 工资规则裁剪账本（请求/授权；执行后读账用）。 */
  private static final class SalaryBook {
    private final UnitId gov;
    private final HouseholdId household;
    private final ResourceVector requested;
    private ResourceVector authorized = ResourceVector.EMPTY;

    private SalaryBook(
        UnitId gov, HouseholdId household, ResourceVector requested, ResourceVector authorized) {
      this.gov = Objects.requireNonNull(gov, "gov");
      this.household = Objects.requireNonNull(household, "household");
      this.requested = Objects.requireNonNull(requested, "requested");
      this.authorized = Objects.requireNonNull(authorized, "authorized");
    }
  }

  private record RuleRef(HouseholdPeriodicAdjustment rule, GovBudgetCategory category) {}

  private record CapResult(
      List<HouseholdPeriodicAdjustment> rules,
      List<PeriodicHouseholdAdjustmentExecutor.BudgetedRule> budgetedLedgerRules,
      Map<String, UnitId> ruleGovById) {}

  /** 每 GOV 的工资汇总（logSalaryExecution 内部用）。 */
  private static final class SalaryGovTotals {
    private long households;
    private long requestedGrain;
    private long requestedSilver;
    private long paidGrain;
    private long paidSilver;
    private long shortfallGrain;
    private long shortfallSilver;
  }

  /** 行政定额评估 → 三腿向量。 */
  private static ResourceVector resourceVectorOf(GovDaily.UpkeepAssessment assessment) {
    return new ResourceVector(
        assessment.grainMilli(), assessment.clothMilli(), assessment.moneyMilli());
  }

  /** 把一条规则折算成三腿请求向量；不认识的资产 ⇒ 具名契约 ERROR（不静默丢腿）。 */
  private static ResourceVector resourceVectorOf(
      HouseholdPeriodicAdjustment rule, long day, UnitId gov) {
    for (CommodityId commodity : rule.goodsPerCycle().keySet()) {
      if (!commodity.equals(GRAIN) && !commodity.equals(CLOTH)) {
        throw contractFailure(
            "unknown-commodity", day, gov, "rule=" + rule.id().value() + " commodity=" + commodity);
      }
    }
    for (CurrencyId currency : rule.moneyPerCycle().keySet()) {
      if (!currency.equals(BOOK_CURRENCY)) {
        throw contractFailure(
            "unknown-currency", day, gov, "rule=" + rule.id().value() + " currency=" + currency);
      }
    }
    return new ResourceVector(
        rule.goodsPerCycle().getOrDefault(GRAIN, 0L),
        rule.goodsPerCycle().getOrDefault(CLOTH, 0L),
        rule.moneyPerCycle().getOrDefault(BOOK_CURRENCY, 0L));
  }

  /** 执行读数（已落账的腿）→ 三腿向量。 */
  private static ResourceVector resourceVectorOf(
      Map<CommodityId, Long> paidGoods, Map<CurrencyId, Long> paidMoney) {
    return new ResourceVector(
        paidGoods.getOrDefault(GRAIN, 0L),
        paidGoods.getOrDefault(CLOTH, 0L),
        paidMoney.getOrDefault(BOOK_CURRENCY, 0L));
  }

  /**
   * 国库可用量（余额 − 冻结；唯一算法 {@link AvailableStock}）；缺账/解析不出政府家户 ⇒ 契约 ERROR fail-closed。
   *
   * <p>★★ <b>A2b（I24 / M7）：本币口径 = {@link #BOOK_CURRENCY} 单一币种</b> ——
   *
   * <ul>
   *   <li>货币腿<b>只装</b>本币（{@code AvailableStock.available(view, BOOK_CURRENCY)}）；国库里<b>其他任何币种</b>
   *       既不按 1:1 加进来、也不折算（世界没有汇率）⇒ 逐币种具名列进 <b>DEBUG {@code
   *       GOV_BUDGET_FOREIGN_CURRENCY_EXCLUDED}</b>（"显式声明单一币种口径"必须能被读到，设计书 §3.6）；
   *   <li>后果是 fail-closed 的：国库只有外币 ⇒ 本币可用 = 0 ⇒ 类别拿不到额度（{@code treasury-available-zero}），
   *       <b>不</b>拿外币当本币花。
   * </ul>
   */
  private static ResourceVector treasuryAvailable(
      AccountSession accounts, Unit unit, UnitId gov, long day) {
    HouseholdId treasury;
    try {
      treasury = GovernmentHouseholdResolver.requireGovernmentHousehold(unit, gov.value());
    } catch (IllegalArgumentException e) {
      throw contractFailure("treasury-resolution", day, gov, e.getMessage());
    }
    AccountSession.ActorAccount account = accounts.householdAccount(treasury);
    if (account == null) {
      throw contractFailure("treasury-account-missing", day, gov, "treasury=" + treasury.value());
    }
    HouseholdInventory view =
        new HouseholdInventory(
            new HouseholdAccountKey(treasury),
            account.goods(),
            account.money(),
            account.frozenGoods(),
            account.frozenMoney());
    logForeignCurrenciesExcluded(view, gov, day);
    return new ResourceVector(
        AvailableStock.available(view, GRAIN),
        AvailableStock.available(view, CLOTH),
        AvailableStock.available(view, BOOK_CURRENCY));
  }

  /**
   * ★★ <b>A2b（I24 / M7）：把"国库里被本币口径排除掉的币种"逐币种具名记下来</b>（DEBUG，不写任何状态）。
   *
   * <p>★ 只发"确实持有"的币种（缺币种 ≠ 余额 0 的条目一律不发，避免日日刷屏）；本币不在排除表里。 ★ 一条都不持有时也发一条 {@code excluded=[]}
   * 的读数？<b>不发</b>：那会让"没有外币"与"没跑过本方法"在日志里长得一样 —— 需要知道本方法跑过的调用点看同一轮的计划日志。
   */
  private static void logForeignCurrenciesExcluded(HouseholdInventory view, UnitId gov, long day) {
    if (!LOG.isDebugEnabled()) {
      return;
    }
    Map<String, Long> excluded = new java.util.TreeMap<>();
    for (Map.Entry<CurrencyId, Long> entry : view.money().entrySet()) {
      long amount = entry.getValue() + view.frozenMoney().getOrDefault(entry.getKey(), 0L);
      if (!entry.getKey().equals(BOOK_CURRENCY) && amount != 0L) {
        excluded.merge(entry.getKey().value(), amount, Long::sum);
      }
    }
    if (excluded.isEmpty()) {
      return;
    }
    EventLog.channel(LOG)
        .debug(
            LogEvent.of(
                "GOV_BUDGET_FOREIGN_CURRENCY_EXCLUDED",
                AppLogSource.DAILY_LOOP,
                "day",
                day,
                "gov",
                gov.value(),
                "bookCurrency",
                BOOK_CURRENCY.value(),
                "excluded",
                excluded,
                "note",
                "预算帽 = 单一币种口径：其他币种既不相加也不折算（世界无汇率）"));
  }

  /** 按 GOV 累加需求向量（键序 = 首次出现序）。 */
  private static void addDemand(
      Map<UnitId, ResourceVector> demands, UnitId gov, ResourceVector addition) {
    ResourceVector current = demands.getOrDefault(gov, ResourceVector.EMPTY);
    demands.put(
        gov,
        new ResourceVector(
            Math.addExact(current.grainMilli(), addition.grainMilli()),
            Math.addExact(current.clothMilli(), addition.clothMilli()),
            Math.addExact(current.silverMilli(), addition.silverMilli())));
  }

  private static ResourceVector minVector(ResourceVector left, ResourceVector right) {
    return new ResourceVector(
        Math.min(left.grainMilli(), right.grainMilli()),
        Math.min(left.clothMilli(), right.clothMilli()),
        Math.min(left.silverMilli(), right.silverMilli()));
  }

  /** 政府家户 id → GOV 单位 id；不是政府家户 ⇒ null（不猜）。 */
  private static UnitId govOfPayer(HouseholdId payer) {
    Optional<String> reference = GovernmentHouseholds.unitRefOf(payer);
    return reference.map(UnitId::parse).orElse(null);
  }

  private static void logPlan(
      UnitId gov,
      HexCoord at,
      GovBudgetPolicy policy,
      ResourceVector available,
      Map<GovBudgetCategory, ResourceVector> demands,
      Map<GovBudgetCategory, CategoryAllocation> allocations,
      long day) {
    if (!LOG.isDebugEnabled()) {
      return;
    }
    Map<String, Long> requested = new LinkedHashMap<>();
    Map<String, Long> authorized = new LinkedHashMap<>();
    for (Map.Entry<GovBudgetCategory, CategoryAllocation> entry : allocations.entrySet()) {
      requested.put(entry.getKey().name(), entry.getValue().requested().valueInBookCurrency());
      authorized.put(entry.getKey().name(), entry.getValue().authorized().valueInBookCurrency());
    }
    EventLog.channel(LOG)
        .debug(
            LogEvent.of(
                "GOV_BUDGET_PLAN",
                AppLogSource.DAILY_LOOP,
                "day",
                day,
                "unit",
                gov.value(),
                "hex",
                at,
                "order",
                policy.orderedCategories().stream().map(line -> line.category().name()).toList(),
                "availableGrain",
                available.grainMilli(),
                "availableCloth",
                available.clothMilli(),
                "availableSilver",
                available.silverMilli(),
                "demands",
                demands,
                "requestedValue",
                requested,
                "authorizedValue",
                authorized));
  }

  /** 预算桥契约故障：具名 ERROR（不降级）+ {@link IllegalStateException}（fail-closed）。 */
  private static IllegalStateException contractFailure(
      String reason, long day, UnitId gov, String detail) {
    EventLog.channel(LOG)
        .error(
            LogEvent.of(
                "GOV_BUDGET_CONTRACT_VIOLATION",
                AppLogSource.DAILY_LOOP,
                "reason",
                reason,
                "day",
                day,
                "unit",
                gov.value(),
                "detail",
                detail == null ? "-" : detail));
    return new IllegalStateException(
        "gov 预算执行桥契约故障: " + reason + "（unit=" + gov.value() + "，detail=" + detail + "）");
  }
}
