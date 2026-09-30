package io.mosire.simos.unit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 政府编制（阶段 9，2026-09-30 裁定 3/5/7）：实际拥有行政职能的政府单位挂它——中央与地方都走这个形状（中央也是 GOV 单位）。
 *
 * <p>★ <b>字段语义</b>：
 *
 * <ul>
 *   <li>{@code staff}：各行政角色的在编人数（{@link StaffRole} → 人）；空 map = 只有编制标签、尚无人员；
 *   <li>{@code policy}：编制政策（定额/上限/退休待遇）；
 *   <li>{@code superiorGov}：上级 GOV 单位 id——中央为空；多数省直接指中央；
 *   <li>{@code level}：层级（中央 / 省）。
 * </ul>
 *
 * <p>★ <b>不变量</b>：{@code staff} 非 null、键非 null、值非 null 且 ≥ 0，保序不可变；{@code policy}、{@code
 * superiorGov}（Optional 本身）、{@code level} 都不得为 null；违反一律当场抛 {@link IllegalArgumentException}。 ★
 * <b>不做跨字段校验</b>（如"中央必须没有上级"）——那是 GOV 侧创建期的事，本类型只保证自己的字段合法（照 {@code Jurisdiction} 只守自己一亩地的先例）。
 *
 * <p>★ <b>它不含任何力量/效率数值</b>：行政力、加成、税收覆盖全部在 {@code simos-gov} 里算（用户裁定 1/2）。
 *
 * @param staff 各行政角色在编人数（保序不可变；键非 null，值 ≥ 0）
 * @param policy 编制政策（非 null）
 * @param superiorGov 上级 GOV（中央/无上级用 {@code Optional.empty()}；Optional 本身非 null）
 * @param level 层级（非 null）
 */
public record GovFormation(
    Map<StaffRole, Long> staff, OfficePolicy policy, Optional<UnitId> superiorGov, GovLevel level)
    implements UnitModule {

  public GovFormation {
    if (staff == null) {
      throw new IllegalArgumentException("staff 不得为 null（无人员用 Map.of()）");
    }
    Map<StaffRole, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<StaffRole, Long> entry : staff.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("staff 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "staff 的值必须 ≥ 0: " + entry.getKey() + "=" + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    // ★ 冻在赋值处（SpotBugs 的 EI_EXPOSE_REP 只认它看得见的 Collections.unmodifiableMap）。
    staff = Collections.unmodifiableMap(copy);
    if (policy == null) {
      throw new IllegalArgumentException("policy 不得为 null");
    }
    if (superiorGov == null) {
      throw new IllegalArgumentException("superiorGov 不得为 null（无上级用 Optional.empty()）");
    }
    if (level == null) {
      throw new IllegalArgumentException("level 不得为 null");
    }
  }
}
