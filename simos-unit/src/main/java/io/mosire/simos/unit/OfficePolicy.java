package io.mosire.simos.unit;

import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 行政编制政策（阶段 9）：每个编制人员的定额、上限与退休待遇。
 *
 * <p>★ <b>它只存"政策是多少"</b>：不做任何结算、不读库存、不算行政效率——"每 tick 该付多少、缺料怎么办"归 {@code simos-gov} 的结算（阶段 11）。★
 * 这也是用户裁定 1/2 的落点：unit 侧只有编制/位置/移动，政策是编制成分，力量与产出都不在这里。
 *
 * <p>★ <b>量纲口径</b>：{@code grainPerStaffPerTick} / {@code moneyPerStaffPerTick} 是"每人每 tick"， {@code
 * clothPerStaffPerCycle} 是"每人每周期"（周期由 gov 侧定义）；{@link #defaults()} 直接复用 {@code EconomyVocabulary}
 * 的每人常量，<b>本类型不做 tick/周期折算</b>（折算只在结算公式里做一处）。
 *
 * <p>★ <b>保序不可变</b>：{@code staffCap} 用 {@code LinkedHashMap} 拷贝 + 在赋值处 {@code
 * Collections.unmodifiableMap} 冻结（与 {@code Unit.equipment} / {@code Jurisdiction} 同形制）；<b>不用</b>
 * {@code Map.copyOf}——它会打乱插入序。
 *
 * <p>★ <b>构造期校验</b>：四个数值都 ≥ 0；{@code staffCap} 非 null、键非 null、值非 null 且 ≥ 0；违反一律当场抛 {@link
 * IllegalArgumentException}，不静默钳制。空 map = 不设上限。
 *
 * @param grainPerStaffPerTick 每编制人员每 tick 的粮食定额（毫粮；≥ 0）
 * @param clothPerStaffPerCycle 每编制人员每周期的布料定额（毫布；≥ 0）
 * @param moneyPerStaffPerTick 每编制人员每 tick 的俸禄（最小币值；默认 0）
 * @param retirementPerStaff 退休/遣散的一次性安置（最小币值/人；默认 0，待遇由决策人政策定）
 * @param staffCap 各角色编制上限（空 map = 不设上限；保序不可变）
 */
public record OfficePolicy(
    long grainPerStaffPerTick,
    long clothPerStaffPerCycle,
    long moneyPerStaffPerTick,
    long retirementPerStaff,
    Map<StaffRole, Long> staffCap) {

  public OfficePolicy {
    requireNonNegative(grainPerStaffPerTick, "grainPerStaffPerTick");
    requireNonNegative(clothPerStaffPerCycle, "clothPerStaffPerCycle");
    requireNonNegative(moneyPerStaffPerTick, "moneyPerStaffPerTick");
    requireNonNegative(retirementPerStaff, "retirementPerStaff");
    if (staffCap == null) {
      throw new IllegalArgumentException("staffCap 不得为 null（不设上限用 Map.of()）");
    }
    Map<StaffRole, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<StaffRole, Long> entry : staffCap.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("staffCap 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "staffCap 的值必须 ≥ 0: " + entry.getKey() + "=" + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    // ★ 冻在赋值处（SpotBugs 的 EI_EXPOSE_REP 只认它看得见的 Collections.unmodifiableMap）。
    staffCap = Collections.unmodifiableMap(copy);
  }

  /**
   * ★ <b>出厂默认政策</b>：粮 = {@link EconomyVocabulary#RATION_MILLI_PER_PERSON}、布 = {@link
   * EconomyVocabulary#CLOTH_MILLI_PER_PERSON}（都是 util 词表里"每人每周期"的既有常量）、俸禄与退休待遇 = 0、编制上限 = 空表（不设限）。
   *
   * <p>★ <b>常量来源刻意只有一处</b>：口径住在 {@code simos-util} 的 {@link EconomyVocabulary}——{@code simos-unit}
   * 已依赖 util，故军/政两边读同一份"人吃多少粮、穿多少布"，<b>不引 economy 主模块</b>（阶段 9 硬约束）。
   */
  public static OfficePolicy defaults() {
    return new OfficePolicy(
        EconomyVocabulary.RATION_MILLI_PER_PERSON,
        EconomyVocabulary.CLOTH_MILLI_PER_PERSON,
        0L,
        0L,
        Map.of());
  }

  private static void requireNonNegative(long value, String field) {
    if (value < 0L) {
      throw new IllegalArgumentException(field + " 必须 ≥ 0: " + value);
    }
  }
}
