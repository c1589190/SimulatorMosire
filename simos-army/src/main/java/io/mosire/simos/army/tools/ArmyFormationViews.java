package io.mosire.simos.army.tools;

import io.mosire.simos.army.Army;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmyId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.unit.ArmyFormation;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 军队完整编制读视图（{@code simos.army.formation} 与网页共用）。
 *
 * <p>★★ <b>唯一树来源</b>：编制树就是 {@link UnitState} 里的 {@code Unit.parent} 森林，从 {@code Army.rootUnit}
 * 向下遍历； 本类不缓存、不复制第二棵树。
 *
 * <p>★★ <b>人数</b>：每一级的 {@code directPersonnel} = Social 该单位家户成员总数（{@code
 * SocialData.unitPopulation}）， {@code subtreePersonnel} = 本节点直接人数 + 全部后代直接人数。两者分开，绝不把子树合计冒充本级。
 *
 * <p>★ {@code attached} 直接来自 Unit 的时态序列（当前状态时间戳）；detached 后代仍在组织树里，但不在同一移动支里。
 */
public final class ArmyFormationViews {

  private ArmyFormationViews() {}

  /** 单个节点。 */
  public record NodeView(
      String id,
      String name,
      String role,
      String masterGov,
      String parentId,
      int depth,
      boolean attached,
      Integer q,
      Integer r,
      long directPersonnel,
      long subtreePersonnel,
      List<Map<String, Object>> equipment,
      int speed,
      int mobilityPerMille,
      List<String> childIds) {

    public NodeView {
      equipment = freezeEquipment(equipment);
      childIds = List.copyOf(childIds);
    }

    /** 深冻结副本出站：不把内部可变 Map 列表交给调用方。 */
    @Override
    public List<Map<String, Object>> equipment() {
      return freezeEquipment(equipment);
    }
  }

  /** 一支军队的完整视图。 */
  public record FormationView(
      String armyId,
      String name,
      String masterGov,
      String rootUnitId,
      boolean contactWatch,
      int nodeCount,
      int maxDepth,
      long directPersonnel,
      long subtreePersonnel,
      List<NodeView> nodes) {

    public FormationView {
      nodes = List.copyOf(nodes);
    }
  }

  /** 全部军队（armyId 稳定序）。 */
  public static List<FormationView> all(
      SimulationState state, ArmyData armies, UnitState units, SocialData social) {
    List<Army> ordered = new ArrayList<>(armies.armies().values());
    ordered.sort(Comparator.comparing(army -> army.id().value()));
    List<FormationView> views = new ArrayList<>(ordered.size());
    for (Army army : ordered) {
      views.add(of(state, army, units, social));
    }
    return List.copyOf(views);
  }

  /** 单支军队；不存在 ⇒ 空。 */
  public static Optional<FormationView> find(
      SimulationState state, ArmyData armies, UnitState units, SocialData social, ArmyId armyId) {
    Army army = armies.armies().get(armyId);
    return army == null ? Optional.empty() : Optional.of(of(state, army, units, social));
  }

  private static FormationView of(
      SimulationState state, Army army, UnitState units, SocialData social) {
    Map<UnitId, List<Unit>> children = new LinkedHashMap<>();
    for (Unit unit : units.units().values()) {
      Optional<UnitId> parent = unit.parent().valueAt(state.meta().timestamp());
      parent.ifPresent(id -> children.computeIfAbsent(id, key -> new ArrayList<>()).add(unit));
    }
    for (List<Unit> list : children.values()) {
      list.sort(Comparator.comparing(unit -> unit.id().value()));
    }
    List<NodeView> nodes = new ArrayList<>();
    Set<UnitId> seen = new LinkedHashSet<>();
    Deque<Pending> queue = new ArrayDeque<>();
    queue.add(new Pending(army.rootUnit(), null, 1));
    while (!queue.isEmpty()) {
      Pending pending = queue.poll();
      UnitId id = pending.id();
      if (!seen.add(id)) {
        throw new IllegalStateException(
            "军队 " + army.id() + " 的编制树在 " + id + " 处重复/成环（UnitState 构造期本应拒绝）");
      }
      Unit unit = units.units().get(id);
      if (unit == null) {
        throw new IllegalStateException("军队 " + army.id() + " 的编制树引用了不存在的单位: " + id);
      }
      ArmyFormation formation =
          unit.module().orElse(null) instanceof ArmyFormation armyFormation ? armyFormation : null;
      long direct = social.unitPopulation(id.value());
      List<Map<String, Object>> equipment = new ArrayList<>();
      for (CompositionEntry entry : unit.equipment()) {
        equipment.add(Map.of("type", entry.type(), "amount", entry.amount()));
      }
      List<String> childIds = new ArrayList<>();
      for (Unit child : children.getOrDefault(id, List.of())) {
        childIds.add(child.id().value());
      }
      Optional<HexCoord> at = units.effectivePosition(id, state.meta().timestamp());
      nodes.add(
          new NodeView(
              id.value(),
              unit.name(),
              formation == null ? null : formation.role(),
              formation == null ? null : formation.masterGov().map(UnitId::value).orElse(null),
              pending.parentId() == null ? null : pending.parentId().value(),
              pending.depth(),
              unit.attached().valueAt(state.meta().timestamp()),
              at.map(HexCoord::q).orElse(null),
              at.map(HexCoord::r).orElse(null),
              direct,
              0L,
              List.copyOf(equipment),
              unit.speed(),
              unit.mobilityPerMille(),
              List.copyOf(childIds)));
      for (String childId : childIds) {
        queue.add(new Pending(new UnitId(childId), id, pending.depth() + 1));
      }
    }
    // 子树合计：逆序（子节点在父之后入队）累加，O(n)。
    Map<String, Long> sums = new LinkedHashMap<>();
    for (int index = nodes.size() - 1; index >= 0; index--) {
      NodeView node = nodes.get(index);
      long sum = node.directPersonnel();
      for (String childId : node.childIds()) {
        sum = Math.addExact(sum, sums.getOrDefault(childId, 0L));
      }
      sums.put(node.id(), sum);
    }
    List<NodeView> withTotals = new ArrayList<>(nodes.size());
    long directTotal = 0L;
    int maxDepth = 0;
    for (NodeView node : nodes) {
      long subtree = sums.getOrDefault(node.id(), node.directPersonnel());
      if (node.id().equals(army.rootUnit().value())) {
        // ★ FormationView.directPersonnel = root 的本级直接人数（不是全树所有节点之和）；
        //   subtreePersonnel 才是 root 子树总数。节点行自己的 directPersonnel 已正确。
        directTotal = node.directPersonnel();
      }
      maxDepth = Math.max(maxDepth, node.depth());
      withTotals.add(
          new NodeView(
              node.id(),
              node.name(),
              node.role(),
              node.masterGov(),
              node.parentId(),
              node.depth(),
              node.attached(),
              node.q(),
              node.r(),
              node.directPersonnel(),
              subtree,
              node.equipment(),
              node.speed(),
              node.mobilityPerMille(),
              node.childIds()));
    }
    return new FormationView(
        army.id().value(),
        army.name(),
        army.masterGovUnitId().map(UnitId::value).orElse(null),
        army.rootUnit().value(),
        army.contactWatch(),
        withTotals.size(),
        maxDepth,
        directTotal,
        sums.getOrDefault(army.rootUnit().value(), directTotal),
        List.copyOf(withTotals));
  }

  private record Pending(UnitId id, UnitId parentId, int depth) {}

  /** 深冻结装备行（外层保序不可变；内层 Map 也不把可变引用交出去）。 */
  private static List<Map<String, Object>> freezeEquipment(List<Map<String, Object>> equipment) {
    List<Map<String, Object>> copy = new ArrayList<>(equipment.size());
    for (Map<String, Object> entry : equipment) {
      copy.add(Collections.unmodifiableMap(new LinkedHashMap<>(entry)));
    }
    return List.copyOf(copy);
  }

  /** 视图 → 工具载荷（MCP 与网页共用）。 */
  public static Map<String, Object> toPayload(FormationView view) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("armyId", view.armyId());
    out.put("name", view.name());
    out.put("masterGov", view.masterGov());
    out.put("rootUnitId", view.rootUnitId());
    out.put("contactWatch", view.contactWatch());
    out.put("nodeCount", view.nodeCount());
    out.put("maxDepth", view.maxDepth());
    out.put("directPersonnel", view.directPersonnel());
    out.put("subtreePersonnel", view.subtreePersonnel());
    List<Map<String, Object>> nodes = new ArrayList<>(view.nodes().size());
    for (NodeView node : view.nodes()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", node.id());
      row.put("name", node.name());
      row.put("role", node.role());
      row.put("masterGov", node.masterGov());
      row.put("parentId", node.parentId());
      row.put("depth", node.depth());
      row.put("attached", node.attached());
      row.put("position", node.q() == null ? null : Map.of("q", node.q(), "r", node.r()));
      row.put("directPersonnel", node.directPersonnel());
      row.put("subtreePersonnel", node.subtreePersonnel());
      row.put("equipment", node.equipment());
      row.put("speed", node.speed());
      row.put("mobilityPerMille", node.mobilityPerMille());
      row.put("childIds", node.childIds());
      nodes.add(row);
    }
    out.put("nodes", List.copyOf(nodes));
    return out;
  }
}
