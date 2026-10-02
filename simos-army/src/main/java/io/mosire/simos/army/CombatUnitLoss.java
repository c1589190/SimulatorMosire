package io.mosire.simos.army;

import io.mosire.simos.unit.CompositionDelta;
import io.mosire.simos.unit.UnitId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一个单位在交战结局里的**损失/变动**（阶段 D4 / 用户设计 D-006 补裁 R1 + D-009 补裁 + D-010，2026-10-02）。
 *
 * <p>★★ <b>形状与口径</b>：复用 unit 的 {@link CompositionDelta}（{@code type + amount}
 * 的**有符号增量**），人力与装备**同构**两条表 ——这正是 R2「Army 只做编排/随机化，写单位仍走 unit 命令」的载荷形状：Army 记录的就是候选的 {@code
 * unit.AdjustComposition} 载荷（{@code id}/{@code manpower}/{@code equipment}），结算时逐单位原样提交。
 *
 * <p>★ <b>有符号不是笔误</b>：D-009 说"给单位上结果（人力/装备变动）"——战损是负增量，补充/新建是正增量，两者同一条通道。{@code unit
 * .AdjustComposition} 的负增量要求 type 已存在且不越界，那两条判据属于 unit 域，记录构造期不重复实现。
 *
 * <p>★ <b>本类自身的不变量</b>：{@code unit} 非 null；两条表非 null（空表合法）；**同一张表内不得有重复 type**（同 {@code
 * unit.AdjustComposition} 的输入口径——重复 type 会让"先加后减"与"先减后加"产生不同结果）。amount 的符号不限（可为 0）。
 *
 * <p>★ <b>保序不可变</b>：两条表一律 {@code List.copyOf} 风格的保序冻结（不用任何不承诺顺序的集合）。
 */
public record CombatUnitLoss(
    UnitId unit, List<CompositionDelta> manpower, List<CompositionDelta> equipment) {

  public CombatUnitLoss {
    Objects.requireNonNull(unit, "unit");
    if (manpower == null) {
      throw new IllegalArgumentException("manpower 不得为 null（空表合法）");
    }
    if (equipment == null) {
      throw new IllegalArgumentException("equipment 不得为 null（空表合法）");
    }
    // ★ T2 收尾：List.copyOf 必须在**赋值处**看得见——SpotBugs 的 EI_EXPOSE_REP 只认它看得见的冻结；
    //   包在 copyDeltas 里（helper 返回 List.copyOf）它看不见，会误报两条 Medium。
    manpower = List.copyOf(copyDeltas(manpower, "manpower"));
    equipment = List.copyOf(copyDeltas(equipment, "equipment"));
  }

  /** 两条表都为空 ⇒ 这个单位在本结局里没有变动（结算时不会生成 unit.AdjustComposition 命令）。 */
  public boolean empty() {
    return manpower.isEmpty() && equipment.isEmpty();
  }

  /** 拷贝 + 判重，末尾 {@code List.copyOf} 冻结（保序不可变；不用任何不承诺顺序的集合）。 */
  private static List<CompositionDelta> copyDeltas(List<CompositionDelta> deltas, String field) {
    List<CompositionDelta> copy = new ArrayList<>(deltas.size());
    Set<String> seen = new LinkedHashSet<>();
    for (CompositionDelta delta : deltas) {
      if (delta == null) {
        throw new IllegalArgumentException(field + " 的元素不得为 null");
      }
      if (!seen.add(delta.type())) {
        throw new IllegalArgumentException(field + " 不得有重复 type: " + delta.type());
      }
      copy.add(delta);
    }
    return List.copyOf(copy);
  }
}
