package io.mosire.simos.economy.time;

import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>P-T1d：本轮的"政府采购优先级"入参（逐轮瞬态；不进 {@code EconomyData} / 不进变更集 / 不落盘）</b>。
 *
 * <pre>
 * ProcurementPriorityInput(overtakeUnitsByTreasury: 国库户 HouseholdId → 行政力池（可超越的家户数，≥ 0）)
 * </pre>
 *
 * <p>★★ <b>它回答两件事</b>（2026-10-10 口岸设计书 §16.3 / §17；用户原话「如果政府要求管控市场，视为政府强制把自己的账户在市场交易里
 * 强制到最开始卖、最开始买，这时候前方要超越多少家户，政府就需要付多少额外行政劳动力」「可以改成政府要求管控后自己选择是否扩大官僚队伍， 如果行政力见底了那就不允许继续超越了」）：
 *
 * <ol>
 *   <li><b>谁要求管控市场</b>：<b>表里有这个国库户</b>（{@code hh-gov-<govUnitId>}）⇒ 该政府要求管控 ⇒ 它的挂单可以置顶； 表里没有 ⇒
 *       不要求管控 ⇒ <b>一个数都不动</b>（缺省语义中性，I-C2）；
 *   <li><b>它能超越几户</b>：值 = 该政府本轮的<b>行政力池</b>（单位 = <b>家户</b>；设计书 §17.4 N-1「一份行政力 = 一个家户（被超越的
 *       订单所属家户）」）。消耗由经济侧在市场轮里逐户扣，<b>只减不增、见底硬停</b>（fail-closed：不许"先超了再欠"）。
 * </ol>
 *
 * <p>★★ <b>为什么池子由组合根折算后注入、而不落在经济状态里</b>（与 {@link PortEnforcementInput} / {@link PortTaxInput}
 * 同一条纪律）：池子 = 该政府的<b>编制劳动力</b>（既有 {@code GovEfficiency} 行政维的口径）折成"可超越户数"，而"编制/行政效率" 在 gov 与 app
 * 手里、经济侧看不见（模块边界）⇒ 只能逐轮瞬态注入；它<b>不落盘</b>（落盘会让"政策关掉后池子还在"变成第二本权威）。
 *
 * <p>★★ <b>时序</b>：与口岸管制力/税相同 —— 折算读的是当日结算后算出的编制劳动力，注入值作用于<b>下一次</b>市场轮（一 tick 滞后）。
 *
 * <p>★ <b>保序不可变</b>：表 {@code LinkedHashMap} 拷贝 + {@code Collections.unmodifiableMap} 冻结，<b>不用</b>
 * {@code Map.copyOf}（迭代序必须是内容的纯函数：池子的消耗顺序按它与 canonical 序走，I7）。
 */
public record ProcurementPriorityInput(Map<HouseholdId, Long> overtakeUnitsByTreasury) {

  /** 没有政府要求管控市场（未注入的轮次一律取它 ⇒ 无置顶、无消耗 ⇒ 逐值退回改前行为）。 */
  private static final ProcurementPriorityInput NONE = new ProcurementPriorityInput(Map.of());

  public ProcurementPriorityInput {
    if (overtakeUnitsByTreasury == null) {
      throw new IllegalArgumentException(
          "ProcurementPriorityInput.overtakeUnitsByTreasury 不得为 null（没有政府管控给 Map.of()）");
    }
    Map<HouseholdId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, Long> entry : overtakeUnitsByTreasury.entrySet()) {
      HouseholdId treasury =
          Objects.requireNonNull(
              entry.getKey(), "ProcurementPriorityInput 的国库户键不得为 null（国库户是身份，不是可选项）");
      Long units = entry.getValue();
      if (units == null) {
        throw new IllegalArgumentException(
            "ProcurementPriorityInput[" + treasury.value() + "] 的行政力池不得为 null（没有余量给 0）");
      }
      if (units < 0L) {
        throw new IllegalArgumentException(
            "行政力池不得为负（" + treasury.value() + " = " + units + "）—— 池子只减不增、见底硬停");
      }
      copy.put(treasury, units);
    }
    overtakeUnitsByTreasury = Collections.unmodifiableMap(copy);
  }

  /** 没有政府要求管控市场（= 空表；缺省注入值，逐值退回改前行为，I-C2）。 */
  public static ProcurementPriorityInput none() {
    return NONE;
  }

  /** 表非空（至少一个政府要求管控市场）；★ 注意：表里某户的池子可以是 0（要求管控但一份行政力都没有 ⇒ 一户也超不了）。 */
  public boolean isActive() {
    return !overtakeUnitsByTreasury.isEmpty();
  }

  /** 该国库户是不是"要求管控市场"的政府（表里有没有它）。 */
  public boolean controls(HouseholdId treasury) {
    return treasury != null && overtakeUnitsByTreasury.containsKey(treasury);
  }

  /** 该国库户本轮的行政力池（可超越的家户数）；缺键 ⇒ 0（不要求管控，或没有余量）。 */
  public long unitsOf(HouseholdId treasury) {
    if (treasury == null) {
      return 0L;
    }
    Long units = overtakeUnitsByTreasury.get(treasury);
    return units == null ? 0L : units;
  }

  /** 要求管控市场的政府数（读数/日志用）。 */
  public int governmentCount() {
    return overtakeUnitsByTreasury.size();
  }

  /** 全部政府池子之和（读数/日志用；单户的消耗各自记账，本值只是"这一轮最多能超越多少户"）。 */
  public long totalUnits() {
    long total = 0L;
    for (Long units : overtakeUnitsByTreasury.values()) {
      total = Math.addExact(total, units);
    }
    return total;
  }

  /** 表里的国库户（保序副本；跨切片键口径核对用）。 */
  public Set<HouseholdId> treasuries() {
    return Collections.unmodifiableSet(new LinkedHashSet<>(overtakeUnitsByTreasury.keySet()));
  }
}
