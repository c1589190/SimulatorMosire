package io.mosire.simos.gov;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 单个 GOV 的国库预算政策（Z2 新增源状态，设计书 §4.1 / §9 / §14.4）。
 *
 * <p>★★ <b>有序支出类别表</b>：{@link #orderedCategories()} 的<b>列表序就是优先级</b>（先到先分配）；每条带自己的 min/cap（见 {@link
 * GovBudgetLine}）。设计书默认顺序 = {@link GovBudgetCategory#ADMIN_STIPEND} → {@link
 * GovBudgetCategory#MILITARY_STIPEND} → {@link GovBudgetCategory#ADMIN_SALARY} → {@link
 * GovBudgetCategory#DEBT_SERVICE} → {@link GovBudgetCategory#OTHER}，但 V1 只把词表冻结，<b>不强制</b>五类齐全；
 * 决策人可只配一部分。
 *
 * <p>★★ <b>官吏工资规则</b>：{@link #officialSalaryRule()}（每承诺小时粮/银）。两条源状态各自独立：可以只写工资规则、不写
 * 预算表；也可以只写预算表、工资规则取 0（不发薪）。
 *
 * <p>★ <b>中性默认</b>（{@link #neutral()}）：空类别表 + {@link GovOfficialSalaryRule#zero()} =
 * <b>不自动付</b>。旧档没有该 GOV 的预算政策时由 {@link GovState#budgetPolicyOrDefault(io.mosire.simos.unit.UnitId)}
 * 返回它。
 *
 * <p>★ <b>保序不可变</b>：类别表用 {@code ArrayList} 拷贝 + 赋值处 {@code Collections.unmodifiableList} 冻结，<b>不用
 * {@code List.copyOf}</b>（保序纪律）；同一类别不得在表里出现两次（否则优先级有歧义，构造期当场拒）。
 *
 * <p>★★ <b>Z7c：上级上缴比例</b>：{@link #remittancePerMilleToSuperior()}（0..1000‰，默认 0）是本 GOV 的
 * <b>省可改可抗税</b>旋钮——周期末（关账日）按本周期实收税 × 比例上缴 {@code GovernmentFormation.superiorGov} 的国库；改成 0
 * 即"抗税"（不转移、无缺口告警）。它就在本 record 里、复用同一条 {@code gov.SetBudgetPolicy} 写口， 不新增命令/不改 handler 的权限面。旧档缺字段
 * ⇒ Jackson 给 primitive 0（= 不上缴）。
 *
 * <p>★ <b>为什么没有 unitId</b>：同 {@link GovAdministrationPlan} —— 身份是 {@link GovState#budgetPolicies()}
 * 的键。
 *
 * @param orderedCategories 有序支出类别表（非 null、表项非 null、类别不重复；可为空 = 不自动付；保序不可变）
 * @param officialSalaryRule 官吏工资规则（非 null；0/0 = 不发薪）
 * @param remittancePerMilleToSuperior 周期末上缴上级国库的实收税比例（‰；0..1000；0 = 不上缴/抗税）
 */
public record GovBudgetPolicy(
    List<GovBudgetLine> orderedCategories,
    GovOfficialSalaryRule officialSalaryRule,
    long remittancePerMilleToSuperior) {

  public GovBudgetPolicy {
    if (orderedCategories == null) {
      throw new IllegalArgumentException("orderedCategories 不得为 null（不自动付用空表）");
    }
    List<GovBudgetLine> linesCopy = new ArrayList<>(orderedCategories.size());
    Set<GovBudgetCategory> categories = new LinkedHashSet<>();
    for (GovBudgetLine line : orderedCategories) {
      if (line == null) {
        throw new IllegalArgumentException("orderedCategories 不得含 null");
      }
      if (!categories.add(line.category())) {
        throw new IllegalArgumentException("orderedCategories 的类别不得重复: " + line.category());
      }
      linesCopy.add(line);
    }
    orderedCategories = Collections.unmodifiableList(linesCopy); // ★ 冻在赋值处（保序）
    if (officialSalaryRule == null) {
      throw new IllegalArgumentException("officialSalaryRule 不得为 null（不发薪用 zero()）");
    }
    if (remittancePerMilleToSuperior < 0L || remittancePerMilleToSuperior > 1000L) {
      throw new IllegalArgumentException(
          "remittancePerMilleToSuperior 必须 ∈ [0,1000]: " + remittancePerMilleToSuperior);
    }
  }

  /** ★ <b>旧 2 参构造器（Z7c 兼容）</b>：Z7c 之前的调用点/测试不用改；上缴比例取 0（= 不上缴，与"旧档缺字段"同一语义）。 新调用点请显式给第三个参数。 */
  public GovBudgetPolicy(
      List<GovBudgetLine> orderedCategories, GovOfficialSalaryRule officialSalaryRule) {
    this(orderedCategories, officialSalaryRule, 0L);
  }

  /** 中性默认：空类别表 + 零工资 + 零上缴 = 不自动付、不自动转账（旧档缺源状态时使用）。 */
  public static GovBudgetPolicy neutral() {
    return new GovBudgetPolicy(List.of(), GovOfficialSalaryRule.zero(), 0L);
  }
}
