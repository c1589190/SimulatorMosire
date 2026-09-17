package io.mosire.simos.unit.move;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.Unit;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A* 路径规划（M3 spec §4.4，冻结）。
 *
 * <p>★ **启发与成本出自同一实现**：{@code h(n) = cost.minStepCostMillis(unit, map) × n.distanceTo(goal)}。
 * 任一单步成本 ≥ 该下界 ⇒ 启发可采纳且**一致** ⇒ 首次弹出即最优，{@code closed} 集合可以直接用（不需要 reopen）。
 *
 * <p>★ **决定论**：优先队列按 {@code (f, h, q, r)} **全序**比较（spec §4.4 冻结形态，不得删减平局项），邻居按 {@code
 * HexCoord.neighbors()} 的枚举序展开 ⇒ 同一输入永远给同一条路径。
 */
public final class PathFinder {

  private PathFinder() {}

  /** 搜索节点：{@code f = g + h} 现算，不入构造器。 */
  private record Node(HexCoord hex, long g, long h) {
    long f() {
      return g + h;
    }
  }

  /** ★ 平局定序必须是**全序**（{@code f} 相等时还能靠 {@code h}、{@code q}、{@code r} 分开）——否则决定论会把锅甩给堆的内部实现。 */
  private static final Comparator<Node> ORDER =
      Comparator.comparingLong(Node::f)
          .thenComparingLong(Node::h)
          .thenComparingInt(node -> node.hex().q())
          .thenComparingInt(node -> node.hex().r());

  /**
   * 从 {@code start} 到 {@code goal} 的最低成本路径（含首尾）。
   *
   * <p>前置：起点或终点不在图上 ⇒ 空（合法但不存在，同 {@code MapResolver} 口径）；{@code start.equals(goal)} ⇒ 单元素路径；不可达 ⇒
   * 空。
   *
   * @param map 局面图（只读）
   * @param start 起点格
   * @param goal 终点格
   * @param unit 走这段路的单位（机动性进成本公式）
   * @param cost 成本实现——启发函数的下界也从它出（C2：启发与成本同源）
   * @return 最低成本路径；起点/终点不在图或不可达 ⇒ {@link Optional#empty()}
   */
  public static Optional<List<HexCoord>> findPath(
      GameMap map, HexCoord start, HexCoord goal, Unit unit, MovementCost cost) {
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(start, "start");
    Objects.requireNonNull(goal, "goal");
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(cost, "cost");
    if (!map.hexes().containsKey(start) || !map.hexes().containsKey(goal)) {
      return Optional.empty();
    }
    if (start.equals(goal)) {
      return Optional.of(List.of(start));
    }
    long minStep = cost.minStepCostMillis(unit, map);
    Map<HexCoord, Long> bestG = new HashMap<>();
    Map<HexCoord, HexCoord> cameFrom = new HashMap<>();
    Set<HexCoord> closed = new HashSet<>();
    PriorityQueue<Node> open = new PriorityQueue<>(ORDER);
    bestG.put(start, 0L);
    open.add(new Node(start, 0L, minStep * start.distanceTo(goal)));

    while (!open.isEmpty()) {
      Node current = open.poll();
      if (!closed.add(current.hex())) {
        continue; // 一致性保证：先进入 closed 的那一份就是最优，后来的同格节点直接丢
      }
      if (current.hex().equals(goal)) {
        return Optional.of(reconstruct(cameFrom, goal));
      }
      for (HexCoord next : current.hex().neighbors()) {
        if (closed.contains(next)) {
          continue;
        }
        OptionalLong step = cost.costMillis(current.hex(), next, unit, map);
        if (step.isEmpty()) {
          continue; // 不可通行的边（含图外格）
        }
        long g = current.g() + step.getAsLong();
        Long known = bestG.get(next);
        if (known != null && known <= g) {
          continue;
        }
        bestG.put(next, g);
        cameFrom.put(next, current.hex());
        open.add(new Node(next, g, minStep * next.distanceTo(goal)));
      }
    }
    return Optional.empty();
  }

  private static List<HexCoord> reconstruct(Map<HexCoord, HexCoord> cameFrom, HexCoord goal) {
    Deque<HexCoord> path = new ArrayDeque<>();
    HexCoord current = goal;
    while (current != null) {
      path.addFirst(current);
      current = cameFrom.get(current);
    }
    return List.copyOf(path);
  }
}
