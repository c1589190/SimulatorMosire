package io.mosire.simos.economy.time;

import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>P2-B：一个世界日"家户劳动利润率排队"的可读结果</b>（进程内读数；不进 {@code EconomyData}、不落盘）。
 *
 * <p>它回答计划 §13.5 要求的那句话："<b>这家户这几小时为什么给这个 mode</b>"：
 *
 * <pre>
 * 每家户：预算（毫小时）、保留给非排队活动的量、已分配、空缺
 * 逐条决定：rank（利润率序）、unit、mode、排序键、预期净收益、预期劳动、最大可吸收劳动、实配、结果（GRANTED / IDLE_*）、具名理由
 * </pre>
 *
 * <p>★ <b>唯一来源</b>：{@link LaborQueueBook.Plan}（排队算法的输出）。本记录只做"冻结 + 查询"，不重算任何数。
 */
public record LaborQueueReport(long day, List<LaborQueueBook.Plan> plans) {

  public LaborQueueReport {
    if (day < 0L) {
      throw new IllegalArgumentException("LaborQueueReport.day 不得为负: " + day);
    }
    Objects.requireNonNull(plans, "LaborQueueReport.plans 不得为 null（没有给空表）");
    List<LaborQueueBook.Plan> copy = new ArrayList<>(plans.size());
    for (LaborQueueBook.Plan plan : plans) {
      copy.add(Objects.requireNonNull(plan, "LaborQueueReport.plans 不得含 null"));
    }
    plans = Collections.unmodifiableList(copy); // ★ 冻在赋值处（保序：调用方给的 household 序）
  }

  /** 空报告（mode 世界已接线但本日没有可排的家户 / 旧档不产生报告时用）。 */
  public static LaborQueueReport empty(long day) {
    return new LaborQueueReport(day, List.of());
  }

  /** 某个家户本日的排队结果；没有 ⇒ 空。 */
  public Optional<LaborQueueBook.Plan> planOf(HouseholdId household) {
    for (LaborQueueBook.Plan plan : plans) {
      if (plan.household().equals(household)) {
        return Optional.of(plan);
      }
    }
    return Optional.empty();
  }

  /** 全报告的可读多行文本（INFO/DEBUG 日志与读口共用；不参与状态）。 */
  public String describe() {
    StringBuilder text = new StringBuilder();
    text.append("day=").append(day).append(" households=").append(plans.size());
    for (LaborQueueBook.Plan plan : plans) {
      text.append('\n').append(LaborQueueBook.describe(plan));
    }
    return text.toString();
  }

  /** 本日全部家户的合计读数（INFO 一行用）。 */
  public long totalBudgetMilli() {
    long total = 0L;
    for (LaborQueueBook.Plan plan : plans) {
      total += plan.budgetMilli();
    }
    return total;
  }

  /** 本日全部家户的已分配合计。 */
  public long totalAllocatedMilli() {
    long total = 0L;
    for (LaborQueueBook.Plan plan : plans) {
      total += plan.allocatedLaborMilli();
    }
    return total;
  }

  /** 本日全部家户的空缺合计。 */
  public long totalIdleMilli() {
    long total = 0L;
    for (LaborQueueBook.Plan plan : plans) {
      total += plan.idleLaborMilli();
    }
    return total;
  }
}
