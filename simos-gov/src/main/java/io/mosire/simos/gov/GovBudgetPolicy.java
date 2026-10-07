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
 * <p>★ <b>为什么没有 unitId</b>：同 {@link GovAdministrationPlan} —— 身份是 {@link GovState#budgetPolicies()}
 * 的键。
 *
 * @param orderedCategories 有序支出类别表（非 null、表项非 null、类别不重复；可为空 = 不自动付；保序不可变）
 * @param officialSalaryRule 官吏工资规则（非 null；0/0 = 不发薪）
 */
public record GovBudgetPolicy(
    List<GovBudgetLine> orderedCategories, GovOfficialSalaryRule officialSalaryRule) {

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
  }

  /** 中性默认：空类别表 + 零工资 = 不自动付（旧档缺源状态时使用）。 */
  public static GovBudgetPolicy neutral() {
    return new GovBudgetPolicy(List.of(), GovOfficialSalaryRule.zero());
  }
}
