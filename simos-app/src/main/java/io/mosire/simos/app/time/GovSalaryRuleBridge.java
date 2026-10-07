package io.mosire.simos.app.time;

import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.app.household.GovernmentServiceLaborBridge;
import io.mosire.simos.economy.EconomyCommodities;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.api.stock.DeductionReason;
import io.mosire.simos.economy.api.stock.HouseholdPeriodicAdjustment;
import io.mosire.simos.economy.api.stock.PeriodicHouseholdAdjustmentId;
import io.mosire.simos.gov.GovBudgetPolicy;
import io.mosire.simos.gov.GovOfficialSalaryRule;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.slf4j.Logger;

/**
 * ★★ <b>官吏工资规则桥（Z3c，设计书 §9）</b>：按每个 GOV 的 {@link GovOfficialSalaryRule}（每承诺小时粮/银）与 {@code
 * GOV_SERVICE} 承诺劳动，现算当日的 {@link HouseholdPeriodicAdjustment}（reason = {@link
 * DeductionReason#ADMIN_SALARY}），交给 P4a 的无状态执行器逐腿 {@code min(可用,请求)} 支付。
 *
 * <pre>
 * 逐 GOV（{@code GovState.offices()} 键按 id 升序）：
 *   policy   = govState.budgetPolicyOrDefault(gov)
 *   rate     = policy.officialSalaryRule()          // 0/0 = 明确不发薪，不是缺口
 *   payer    = GovernmentHouseholds.of(govUnitId)   // hh-gov-&lt;govUnitId&gt; 国库
 *   逐承诺家户（GovernmentServiceLaborBridge.committedLaborByHousehold，按 allocation id 首现序）：
 *     grain  = ⌊laborMilli × grainMilliPerCommittedHour ÷ 1000⌋
 *     silver = ⌊laborMilli × silverMilliPerCommittedHour ÷ 1000⌋
 *     两腿都为 0 且速率非 0 ⇒ 具名 zeroAmounts 读数（四舍五入到一个毫以下），不生成规则；
 *     至少一腿 &gt; 0 ⇒ 规则 id = gov-salary:&lt;govUnitId&gt;:&lt;householdId&gt;，
 *                    period=1、phase=0、starts=1、expires 空（每日到期；设计书 §9 的"照周期先例"）。
 * </pre>
 *
 * <p>★★ <b>发放周期冻结：每日一次（periodDays=1, phaseDay=0, startsOnDay=1）</b>。理由与可行性报告 Q5-A 的推荐一致， 且与 {@code
 * GovBudgetPolicy} 的 min/cap 周期（Z3c 预算执行器冻结为"每 tick/每日"）对齐；不引入新的持久进度状态。
 *
 * <p>★★ <b>边界与失败语义</b>：
 *
 * <ul>
 *   <li><b>只读</b>：承诺劳动与国库账户都不在这里改；规则是瞬态派生件，不写 {@code EconomyData}（同军俸桥）；
 *   <li><b>绝不静默 0</b>：速率非 0 但折算到 0 ⇒ 逐户进 {@link Report#zeroAmounts()}；国库不足/预算帽不足由执行器与预算桥各自具名；
 *   <li><b>自转</b>（承诺持有者恰是 {@code hh-gov-&lt;gov&gt;}）⇒ 具名 gap，不生成规则（0 人口家户本不应有正承诺，防御性第二判）；
 *   <li><b>算术溢出</b> ⇒ ERROR {@code GOV_SALARY_CONTRACT_VIOLATION} + {@link
 *       IllegalStateException}（fail-closed，不截断）。
 * </ul>
 *
 * <p>★ <b>与 C7/供给桥不冲突</b>：工资只读承诺与国库；承诺本身、劳动队列、{@code GOV_SERVICE} 的不可缩语义都不在这里触碰。
 */
public final class GovSalaryRuleBridge {

  /** 工资规则 id 前缀（与军俸 {@code army-pay:} 分开；唯一拼写点）。 */
  public static final String RULE_ID_PREFIX = "gov-salary:";

  /** 规则审计来源前缀（{@code salary:&lt;govUnitId&gt;}；唯一拼写点）。 */
  public static final String POLICY_SOURCE_PREFIX = "salary:";

  /** 每日发薪周期（冻结；见类注）。 */
  public static final long DAILY_PERIOD_DAYS = 1L;

  /** 首次可发薪日（冻结：从世界第 1 天起，承诺存在的每一天都到期）。 */
  public static final long STARTS_ON_DAY = 1L;

  /** 每承诺小时的换算分母（laborMilli × ratePerHour ÷ 1000 = 毫商品）。 */
  private static final long LABOR_MILLI_PER_HOUR = 1000L;

  private static final Logger LOG = AppLog.time();

  private GovSalaryRuleBridge() {}

  /**
   * 现算当日工资规则（不筛到期：{@code periodDays=1} 使每条规则在 {@code day ≥ 1} 都到期）。
   *
   * @param govState GOV 源状态（预算政策 = 工资规则的唯一权威；{@code offices} 键 = 要发薪的 GOV 集合）
   * @param units 单位状态（校验 GOV 单位 + 取 {@link GovernmentFormation}）
   * @param economy 经济状态（{@code GOV_SERVICE} 承诺 = 工资唯一的劳动口径）
   * @param day 世界日（只进日志/契约故障上下文）
   */
  public static Report deriveReport(
      GovState govState, UnitState units, EconomyData economy, long day) {
    Objects.requireNonNull(govState, "govState");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(economy, "economy");
    List<UnitId> ordered = new ArrayList<>(govState.offices().keySet());
    ordered.sort(Comparator.comparing(UnitId::value));
    List<HouseholdPeriodicAdjustment> rules = new ArrayList<>();
    List<String> gaps = new ArrayList<>();
    List<String> zeroAmounts = new ArrayList<>();
    Map<UnitId, Long> committedByGov = new LinkedHashMap<>();
    int governments = 0;
    int committedHouseholds = 0;
    long committedLaborTotal = 0L;
    for (UnitId govUnitId : ordered) {
      Unit unit = units.units().get(govUnitId);
      if (unit == null) {
        continue; // GovDaily.settle 会为同一损坏状态抛具名 ERROR；这里不重复报，也不猜。
      }
      if (!(unit.module().orElse(null) instanceof GovernmentFormation)) {
        continue; // 同上：GOV 编制缺失由 GovDaily.settle 判死。
      }
      governments++;
      Map<HouseholdId, Long> committed =
          GovernmentServiceLaborBridge.committedLaborByHousehold(economy, govUnitId, day);
      long govTotal = 0L;
      try {
        for (long laborMilli : committed.values()) {
          govTotal = Math.addExact(govTotal, laborMilli);
        }
      } catch (ArithmeticException e) {
        throw contractFailure("committed-labor-overflow", day, govUnitId, e.getMessage());
      }
      committedByGov.put(govUnitId, govTotal);
      try {
        committedLaborTotal = Math.addExact(committedLaborTotal, govTotal);
      } catch (ArithmeticException e) {
        throw contractFailure("committed-labor-total-overflow", day, govUnitId, e.getMessage());
      }
      committedHouseholds += committed.size();
      GovBudgetPolicy policy = govState.budgetPolicyOrDefault(govUnitId);
      GovOfficialSalaryRule rate = policy.officialSalaryRule();
      if (rate.grainMilliPerCommittedHour() == 0L && rate.silverMilliPerCommittedHour() == 0L) {
        continue; // 0/0 = 政策明确不发薪（不是静默：规则来源就在 source state 里）。
      }
      HouseholdId payer;
      try {
        payer = GovernmentHouseholds.of(govUnitId.value());
      } catch (IllegalArgumentException invalidReference) {
        throw contractFailure("bad-gov-unit-id", day, govUnitId, invalidReference.getMessage());
      }
      for (Map.Entry<HouseholdId, Long> entry : committed.entrySet()) {
        HouseholdId household = entry.getKey();
        long laborMilli = entry.getValue();
        if (laborMilli <= 0L) {
          continue; // 桥只收 > 0；这里是防御性口径，不把 0 承诺变成一条发生额。
        }
        if (household.equals(payer)) {
          gaps.add(
              "unit=" + govUnitId.value() + " household=" + household.value() + " gap=self-payer");
          continue;
        }
        long grain;
        long silver;
        try {
          grain =
              Math.floorDiv(
                  Math.multiplyExact(laborMilli, rate.grainMilliPerCommittedHour()),
                  LABOR_MILLI_PER_HOUR);
          silver =
              Math.floorDiv(
                  Math.multiplyExact(laborMilli, rate.silverMilliPerCommittedHour()),
                  LABOR_MILLI_PER_HOUR);
        } catch (ArithmeticException e) {
          throw contractFailure(
              "salary-overflow",
              day,
              govUnitId,
              "household="
                  + household.value()
                  + " laborMilli="
                  + laborMilli
                  + "："
                  + e.getMessage());
        }
        if (grain <= 0L && silver <= 0L) {
          zeroAmounts.add(
              "unit="
                  + govUnitId.value()
                  + " household="
                  + household.value()
                  + " laborMilli="
                  + laborMilli
                  + " grainMilliPerCommittedHour="
                  + rate.grainMilliPerCommittedHour()
                  + " silverMilliPerCommittedHour="
                  + rate.silverMilliPerCommittedHour()
                  + " reason=rate-times-labor-below-one-milli");
          continue;
        }
        Map<CommodityId, Long> goods = new LinkedHashMap<>();
        if (grain > 0L) {
          goods.put(EconomyCommodities.GRAIN, grain);
        }
        Map<CurrencyId, Long> money = new LinkedHashMap<>();
        if (silver > 0L) {
          money.put(MoneyVocabulary.SILVER_CURRENCY, silver);
        }
        try {
          rules.add(
              new HouseholdPeriodicAdjustment(
                  PeriodicHouseholdAdjustmentId.parse(
                      RULE_ID_PREFIX + govUnitId.value() + ":" + household.value()),
                  payer,
                  Optional.of(household),
                  goods,
                  money,
                  DeductionReason.ADMIN_SALARY,
                  DAILY_PERIOD_DAYS,
                  0L,
                  STARTS_ON_DAY,
                  OptionalLong.empty(),
                  POLICY_SOURCE_PREFIX + govUnitId.value()));
        } catch (IllegalArgumentException rejectedRule) {
          gaps.add(
              "unit="
                  + govUnitId.value()
                  + " household="
                  + household.value()
                  + " gap=rule-rejected detail="
                  + rejectedRule.getMessage());
        }
      }
    }
    return new Report(
        governments,
        committedHouseholds,
        committedLaborTotal,
        rules,
        gaps,
        zeroAmounts,
        committedByGov);
  }

  /**
   * 推导读数：{@code governments} = 扫到的 GOV 单位数；{@code committedHouseholds} = 有 {@code GOV_SERVICE}
   * 承诺的家户数； {@code rules} 保序 = GOV id 升序 → 承诺家户首现序；{@code gaps} = 结构性缺口（自转/坏 id/规则被拒）；{@code
   * zeroAmounts} = 速率非 0 但折算 &lt; 1 毫的具名读数；{@code committedLaborMilliByGov} 保序不可变。
   */
  public record Report(
      int governments,
      int committedHouseholds,
      long committedLaborMilliTotal,
      List<HouseholdPeriodicAdjustment> rules,
      List<String> gaps,
      List<String> zeroAmounts,
      Map<UnitId, Long> committedLaborMilliByGov) {

    public Report {
      if (governments < 0 || committedHouseholds < 0 || committedLaborMilliTotal < 0L) {
        throw new IllegalArgumentException(
            "GovSalaryRuleBridge.Report 计数/劳动量不得为负: "
                + governments
                + "/"
                + committedHouseholds
                + "/"
                + committedLaborMilliTotal);
      }
      List<HouseholdPeriodicAdjustment> rulesCopy = new ArrayList<>(rules.size());
      for (HouseholdPeriodicAdjustment rule : rules) {
        if (rule == null) {
          throw new IllegalArgumentException("Report.rules 不得含 null");
        }
        rulesCopy.add(rule);
      }
      rules = Collections.unmodifiableList(rulesCopy); // ★ 保序冻在赋值处
      gaps = Collections.unmodifiableList(new ArrayList<>(gaps));
      zeroAmounts = Collections.unmodifiableList(new ArrayList<>(zeroAmounts));
      Map<UnitId, Long> committedCopy = new LinkedHashMap<>();
      for (Map.Entry<UnitId, Long> entry : committedLaborMilliByGov.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException("committedLaborMilliByGov 键/值不得为 null");
        }
        committedCopy.put(entry.getKey(), entry.getValue());
      }
      committedLaborMilliByGov = Collections.unmodifiableMap(committedCopy);
    }

    /** 空读数（没有 offices / 没有承诺）。 */
    public static Report empty() {
      return new Report(0, 0, 0L, List.of(), List.of(), List.of(), Map.of());
    }
  }

  /** 工资桥契约故障：具名 ERROR（不降级）+ {@link IllegalStateException}（fail-closed）。 */
  private static IllegalStateException contractFailure(
      String reason, long day, UnitId govUnitId, String detail) {
    EventLog.channel(LOG)
        .error(
            LogEvent.of(
                "GOV_SALARY_CONTRACT_VIOLATION",
                AppLogSource.DAILY_LOOP,
                "reason",
                reason,
                "day",
                day,
                "unit",
                govUnitId.value(),
                "detail",
                detail == null ? "-" : detail));
    return new IllegalStateException(
        "gov 工资规则桥契约故障: " + reason + "（unit=" + govUnitId.value() + "，detail=" + detail + "）");
  }
}
