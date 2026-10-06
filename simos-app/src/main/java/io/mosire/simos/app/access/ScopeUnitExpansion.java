package io.mosire.simos.app.access;

import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>单位子树展开</b>（2026-10-20 用户裁定：可见单位的下属单位/下辖 GOV 的家户也可操作）。
 *
 * <p>★★ <b>为什么要有这一层</b>：三个范围函数（{@code GovScope}/{@code NationScope}/{@code ArmyScope}）都按
 * "单位当刻位置落在范围内"来授 {@code unit} 前缀；但编制是树（{@link Unit#parent()}）——只授根单位会让"上级能指挥、不能看下属编制"
 * 的权限与编制语义脱节。本类把"沿当刻 {@code parent} 索引收集全部后代"这一件事收口成一个实现，三个范围函数共用。
 *
 * <p>★ <b>按时刻物化</b>：{@code parent} 是时态序列（改编/换隶属会变），所以按 {@code at} 时刻 {@code valueAt} 建 parent→children
 * 索引，而不是读某个"当前值"。这与 {@link UnitState#formationMembers} 同一取数口径。
 *
 * <p>★ <b>防环</b>：正常状态下 {@code UnitState} 构造期已拒环；这里仍用 {@code visited} 兜底——手工拼出的坏状态（工具/夹具）不该让
 * 范围函数死循环。{@code visited} 先放入 roots，根与根之间的交叉边只走一次。
 *
 * <p>★ <b>不含 roots 自身</b>：名字与返回语义一致；调用方若需要"根 + 后代"自己先加根（三个调用点都是这个形态，根本来就在前缀里）。
 */
public final class ScopeUnitExpansion {

  private ScopeUnitExpansion() {}

  /**
   * 收集 {@code roots} 在 {@code at} 时刻的全部后代（**不含 roots 自身**）。
   *
   * @param units 单位切片（非 null）
   * @param roots 根集合（非 null；空集 ⇒ 空结果）
   * @param at 物化 parent→children 索引的时刻（调用方取 {@code state.meta().timestamp()}）
   * @return 全部后代（LinkedHashSet 的 BFS 序，只读）
   */
  public static Set<UnitId> descendants(UnitState units, Set<UnitId> roots, SimosTimestamp at) {
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(roots, "roots");
    Objects.requireNonNull(at, "at");
    if (roots.isEmpty()) {
      return Set.of();
    }
    Map<UnitId, List<UnitId>> children = new HashMap<>();
    for (Unit unit : units.units().values()) {
      Optional<UnitId> parent = unit.parent().valueAt(at);
      if (parent.isPresent()) {
        children.computeIfAbsent(parent.get(), key -> new ArrayList<>()).add(unit.id());
      }
    }
    Set<UnitId> out = new LinkedHashSet<>();
    Set<UnitId> visited = new HashSet<>(roots);
    Deque<UnitId> queue = new ArrayDeque<>(roots);
    while (!queue.isEmpty()) {
      UnitId current = queue.poll();
      for (UnitId child : children.getOrDefault(current, List.of())) {
        if (visited.add(child)) {
          out.add(child);
          queue.add(child);
        }
      }
    }
    return Collections.unmodifiableSet(out);
  }
}
