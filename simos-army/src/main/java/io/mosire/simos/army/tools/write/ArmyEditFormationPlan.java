package io.mosire.simos.army.tools.write;

import io.mosire.simos.army.Army;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmyId;
import io.mosire.simos.army.spi.CreateArmyHandler;
import io.mosire.simos.army.spi.UpdateArmyHandler;
import io.mosire.simos.army.tools.ArmyToolReads;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.social.spi.SubmitHouseholdWorkOrderHandler;
import io.mosire.simos.unit.ArmyFormation;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.spi.CreateUnitHandler;
import io.mosire.simos.unit.spi.SetArmyFormationHandler;
import io.mosire.simos.unit.spi.SetUnitHouseholdsHandler;
import io.mosire.simos.unit.spi.UpdateFormationNodeHandler;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@code simos.army.editFormation} 的纯计划：读同一 {@link SimulationState}，把一份递归节点树推导成 {@code
 * social}/{@code unit}/{@code army} 的原子命令批。
 *
 * <p>★★ <b>权威不复制</b>：树与位置归 unit，人员归 Social 家户，Army 身份/名称/masterGov 归 army；本类不存第二棵树、不存第二本人数， 也不发
 * economy/actor/ga 命令。
 *
 * <p>★★ <b>create</b>：root 必须给坐标；新下级默认 {@code attached=true}，坐标缺省时写实为父位置；每节点都带 {@code
 * ArmyFormation}（masterGov 与 Army 一致）；HQ 可 0 人；新节点整树一次 batch，任何失败全回滚。
 *
 * <p>★★ <b>update</b>：只改给定字段；未提及节点保留、不自动删除；{@code children} 表示把这些节点编到该父之下，不是整表删除；更改 {@code
 * Army.root} 具名拒。跨其他 Army 掠取节点、循环、重复 id、attached 跨格都在组批前拒。
 */
public final class ArmyEditFormationPlan {

  public static final int MAX_NODES = 512;
  public static final int MAX_DEPTH = 16;

  /** 默认成年男性锚点年龄：18 年（按项目日制 365 天/年）；工具描述与结果显式说明。 */
  public static final long DEFAULT_ADULT_MALE_AGE_DAYS = 18L * 365L;

  public static final String CREATE_UNIT = CreateUnitHandler.TYPE;
  public static final String SET_ARMY_FORMATION = SetArmyFormationHandler.TYPE;
  public static final String CREATE_ARMY = CreateArmyHandler.TYPE;
  public static final String UPDATE_ARMY = UpdateArmyHandler.TYPE;
  public static final String SUBMIT_HOUSEHOLD_WORK_ORDER = SubmitHouseholdWorkOrderHandler.TYPE;
  public static final String SET_UNIT_HOUSEHOLDS = SetUnitHouseholdsHandler.TYPE;
  public static final String UPDATE_FORMATION_NODE = UpdateFormationNodeHandler.TYPE;

  private ArmyEditFormationPlan() {}

  /** 递归节点输入；{@code null} = 该字段未给（update 保持 / create 取缺省）。 */
  public record NodeSpec(
      String id,
      String name,
      String role,
      HexCoord position,
      Long personnel,
      Map<String, Long> equipment,
      Integer speed,
      Integer mobilityPerMille,
      Boolean attached,
      List<NodeSpec> children) {

    public NodeSpec {
      // null = 未填（保持）；给了就冻结保序 Map，避免外部可变表穿透 record。
      equipment =
          equipment == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(equipment));
      children = List.copyOf(children);
    }
  }

  public record Command(String type, String payloadJson) {}

  public record Plan(
      String mode,
      String armyId,
      String name,
      String masterGov,
      String rootUnitId,
      int nodeCount,
      int createdNodes,
      int updatedNodes,
      long directPersonnel,
      long subtreePersonnel,
      List<Command> commands) {

    public Plan {
      commands = List.copyOf(commands);
    }
  }

  public static Plan plan(
      SimulationState state,
      String mode,
      String armyIdText,
      String name,
      String masterGovText,
      NodeSpec root,
      String reason) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(root, "root");
    ArmyData armies = ArmyToolReads.armyData(state);
    UnitState units = ArmyToolReads.unitState(state);
    SocialData social = ArmyToolReads.socialData(state);
    GameMap map = ArmyToolReads.gameMap(state);
    ArmyId armyId = ArmyId.parse(armyIdText);
    List<NodeSpec> flat = flatten(root);
    if (flat.size() > MAX_NODES) {
      throw new IllegalArgumentException("编制节点数 " + flat.size() + " 超过上限 " + MAX_NODES);
    }
    if ("create".equals(mode)) {
      return planCreate(
          state, armies, units, social, map, armyId, name, masterGovText, root, flat, reason);
    }
    if ("update".equals(mode)) {
      return planUpdate(
          state, armies, units, social, map, armyId, name, masterGovText, root, flat, reason);
    }
    throw new IllegalArgumentException("mode 只能是 create|update: " + mode);
  }

  // ── create ──────────────────────────────────────────────────────────

  private static Plan planCreate(
      SimulationState state,
      ArmyData armies,
      UnitState units,
      SocialData social,
      GameMap map,
      ArmyId armyId,
      String name,
      String masterGovText,
      NodeSpec root,
      List<NodeSpec> flat,
      String reason) {
    if (armies.armies().containsKey(armyId)) {
      throw new IllegalArgumentException("军队已存在（create 不能覆盖）: " + armyId);
    }
    if (root.position() == null) {
      throw new IllegalArgumentException("create 的 root.position 必填（root 必须明确坐标）");
    }
    Optional<UnitId> masterGov = parseMasterGov(units, masterGovText);
    String armyName = name == null || name.isBlank() ? requireName(root.name(), root.id()) : name;
    Map<String, HexCoord> positions =
        resolvePositions(root, null, map, units, true, state.meta().timestamp());
    for (NodeSpec node : flat) {
      UnitId id = UnitId.parse(node.id());
      if (units.units().containsKey(id)) {
        throw new IllegalArgumentException("create 的节点 id 已存在: " + id);
      }
    }
    List<Command> commands = new ArrayList<>();
    int created = 0;
    long direct = 0L;
    long subtree = 0L;
    for (NodeSpec node : preorder(root)) {
      NodeSpec parent = parentOf(root, node);
      HexCoord at = positions.get(node.id());
      // 根是独立单位（parent 为空 ⇒ 默认 attached=false）；新下级默认随父（attached=true）。
      // 显式给根 attached=true 允许（无父即顶层，图显示“根单位”），不改变旧世界兼容。
      boolean attached = node.attached() == null ? parent != null : node.attached();
      if (attached && parent != null && !at.equals(positions.get(parent.id()))) {
        throw new IllegalArgumentException(
            "节点 "
                + node.id()
                + " attached=true 但坐标 "
                + at
                + " 与父 "
                + parent.id()
                + " 的 "
                + positions.get(parent.id())
                + " 不同格；独立落点请 attached=false");
      }
      long personnel =
          node.personnel() == null ? 0L : requirePersonnel(node.personnel(), node.id());
      List<CompositionEntry> equipment = equipmentOf(node.equipment(), node.id());
      int speed = requireAtLeastOne(node.speed(), 1, "speed", node.id());
      int mobility =
          requireAtLeastOne(node.mobilityPerMille(), 1000, "mobilityPerMille", node.id());
      commands.add(
          command(
              CREATE_UNIT,
              map(
                  "id", node.id(),
                  "name", requireName(node.name(), node.id()),
                  "position", hex(at),
                  "attached", attached,
                  "parent", parent == null ? null : parent.id(),
                  "equipment", equipmentPayload(equipment),
                  "speed", speed,
                  "mobilityPerMille", mobility,
                  "households", List.of())));
      commands.add(
          command(
              SET_ARMY_FORMATION,
              map(
                  "unitId", node.id(),
                  "role", requireRole(node.role(), node.id()),
                  "masterGov", masterGov.map(UnitId::value).orElse(null))));
      addNewPersonnelCommands(commands, node.id(), personnel, reason);
      created++;
      if (parent == null) {
        direct = personnel;
      }
      subtree = Math.addExact(subtree, personnel);
    }
    commands.add(
        command(
            CREATE_ARMY,
            map(
                "armyId", armyId.value(),
                "name", armyName,
                "masterGovUnitId", masterGov.map(UnitId::value).orElse(null),
                "rootUnitId", root.id())));
    return new Plan(
        "create",
        armyId.value(),
        armyName,
        masterGov.map(UnitId::value).orElse(null),
        root.id(),
        flat.size(),
        created,
        0,
        direct,
        subtree,
        List.copyOf(commands));
  }

  // ── update ──────────────────────────────────────────────────────────

  private static Plan planUpdate(
      SimulationState state,
      ArmyData armies,
      UnitState units,
      SocialData social,
      GameMap map,
      ArmyId armyId,
      String name,
      String masterGovText,
      NodeSpec root,
      List<NodeSpec> flat,
      String reason) {
    Army army = armies.armies().get(armyId);
    if (army == null) {
      throw new IllegalArgumentException("军队不存在（update 不能新建）: " + armyId);
    }
    if (!army.rootUnit().value().equals(root.id())) {
      throw new IllegalArgumentException(
          "更改 Army.root 不在本次 update 语义内：既有 root="
              + army.rootUnit()
              + "，载荷 root="
              + root.id()
              + "。root 必须逐字等于既有根单位");
    }
    String armyName = name == null || name.isBlank() ? army.name() : name;
    Optional<UnitId> masterGov =
        masterGovText == null ? army.masterGovUnitId() : parseMasterGov(units, masterGovText);
    boolean masterGovChanged = !masterGov.equals(army.masterGovUnitId());
    Set<String> ownTree = treeOf(units, army.rootUnit(), state);
    Set<String> otherArmyNodes = new LinkedHashSet<>();
    for (Army other : armies.armies().values()) {
      if (!other.id().equals(army.id())) {
        otherArmyNodes.addAll(treeOf(units, other.rootUnit(), state));
      }
    }
    Map<String, HexCoord> positions =
        resolvePositions(root, army.rootUnit(), map, units, false, state.meta().timestamp());
    // ★ 真实结果树 = 当前 Army 全树 + 新节点 + 被提到节点的既有子树；未提及节点必须计入规模/深度/人数，
    //   不能字段名报 subtreePersonnel 却只累加载荷提及的那几个节点。
    Map<String, NodeSpec> specById = new LinkedHashMap<>();
    for (NodeSpec node : flat) {
      specById.put(node.id(), node);
    }
    Set<String> resulting = new LinkedHashSet<>(ownTree);
    for (NodeSpec node : flat) {
      if (units.units().containsKey(new UnitId(node.id()))) {
        resulting.addAll(treeOf(units, new UnitId(node.id()), state));
      } else {
        resulting.add(node.id());
      }
    }
    if (resulting.size() > MAX_NODES) {
      throw new IllegalArgumentException(
          "update 后实际节点数 " + resulting.size() + " 超过上限 " + MAX_NODES);
    }
    Map<String, String> parentById = new LinkedHashMap<>();
    for (Unit unit : units.units().values()) {
      Optional<UnitId> parent = unit.parent().valueAt(state.meta().timestamp());
      parentById.put(unit.id().value(), parent.map(UnitId::value).orElse(null));
    }
    for (NodeSpec node : flat) {
      NodeSpec parent = parentOf(root, node);
      parentById.put(node.id(), parent == null ? null : parent.id());
    }
    parentById.put(root.id(), null);
    Map<String, Integer> depthById = new LinkedHashMap<>();
    depthById.put(root.id(), 1);
    Deque<String> depthQueue = new ArrayDeque<>();
    depthQueue.add(root.id());
    while (!depthQueue.isEmpty()) {
      String parentId = depthQueue.poll();
      int depth = depthById.getOrDefault(parentId, 1);
      for (String childId : resulting) {
        if (parentId.equals(parentById.get(childId))) {
          if (depth + 1 > MAX_DEPTH) {
            throw new IllegalArgumentException(
                "update 后实际深度超过上限 " + MAX_DEPTH + "（节点 " + childId + "）");
          }
          if (!depthById.containsKey(childId)) {
            depthById.put(childId, depth + 1);
            depthQueue.add(childId);
          }
        }
      }
    }
    if (depthById.size() != resulting.size()) {
      throw new IllegalArgumentException(
          "update 后存在无法从 root 到达的节点（循环或跨树引用）: " + resulting.size() + " vs " + depthById.size());
    }
    List<Command> commands = new ArrayList<>();
    int created = 0;
    int updated = 0;
    for (NodeSpec node : preorder(root)) {
      UnitId id = UnitId.parse(node.id());
      Unit existing = units.units().get(id);
      NodeSpec parent = parentOf(root, node);
      if (existing != null && !ownTree.contains(node.id()) && otherArmyNodes.contains(node.id())) {
        throw new IllegalArgumentException("节点 " + node.id() + " 属于其他 Army，不能跨 Army 掠取");
      }
      if (existing != null && !ownTree.contains(node.id()) && !ownTree.isEmpty()) {
        // 既有单位可加入本 Army，但不能把别人的编制树连根搬过来（其下已有 attached 后代时由位置同步显式处理）。
        if (existing.module().orElse(null) instanceof GovernmentFormation) {
          throw new IllegalArgumentException("节点 " + node.id() + " 是 GOV 单位，不能编入 Army");
        }
      }
      if (existing == null) {
        if (node.name() == null || node.role() == null) {
          throw new IllegalArgumentException(
              "update 新节点 " + node.id() + " 必须给 name 与 role（稳定 id 之外还要有显示身份）");
        }
        HexCoord at = positions.get(node.id());
        // 根是独立单位（parent 为空 ⇒ 默认 attached=false）；新下级默认随父（attached=true）。
        // 显式给根 attached=true 允许（无父即顶层，图显示“根单位”），不改变旧世界兼容。
        boolean attached = node.attached() == null ? parent != null : node.attached();
        if (attached && parent != null && !at.equals(positions.get(parent.id()))) {
          throw new IllegalArgumentException(
              "新节点 "
                  + node.id()
                  + " attached=true 但坐标 "
                  + at
                  + " 与父 "
                  + parent.id()
                  + " 的 "
                  + positions.get(parent.id())
                  + " 不同格；独立落点请 attached=false");
        }
        long personnel =
            node.personnel() == null ? 0L : requirePersonnel(node.personnel(), node.id());
        List<CompositionEntry> equipment = equipmentOf(node.equipment(), node.id());
        int speed = requireAtLeastOne(node.speed(), 1, "speed", node.id());
        int mobility =
            requireAtLeastOne(node.mobilityPerMille(), 1000, "mobilityPerMille", node.id());
        commands.add(
            command(
                CREATE_UNIT,
                map(
                    "id", node.id(),
                    "name", node.name(),
                    "position", hex(at),
                    "attached", attached,
                    "parent", parent == null ? null : parent.id(),
                    "equipment", equipmentPayload(equipment),
                    "speed", speed,
                    "mobilityPerMille", mobility,
                    "households", List.of())));
        commands.add(
            command(
                SET_ARMY_FORMATION,
                map(
                    "unitId", node.id(),
                    "role", node.role(),
                    "masterGov", masterGov.map(UnitId::value).orElse(null))));
        addNewPersonnelCommands(commands, node.id(), personnel, reason);
        created++;
        continue;
      }
      // 既有节点：只把"给了的字段"放进 patch；parent 来自本次提供树（root 不写 parent）。
      Map<String, Object> patch = new LinkedHashMap<>();
      patch.put("id", node.id());
      if (node.name() != null) {
        patch.put("name", requireName(node.name(), node.id()));
      }
      if (node.position() != null) {
        patch.put("position", hex(node.position()));
      }
      if (node.attached() != null) {
        patch.put("attached", node.attached());
      }
      if (parent != null) {
        patch.put("parent", parent.id());
      }
      if (node.speed() != null) {
        patch.put("speed", requireAtLeastOne(node.speed(), 1, "speed", node.id()));
      }
      if (node.mobilityPerMille() != null) {
        patch.put(
            "mobilityPerMille",
            requireAtLeastOne(node.mobilityPerMille(), 1, "mobilityPerMille", node.id()));
      }
      if (node.equipment() != null) {
        patch.put("equipment", equipmentPayload(equipmentOf(node.equipment(), node.id())));
      }
      commands.add(command(UPDATE_FORMATION_NODE, patch));
      boolean needsFormation =
          node.role() != null
              || masterGovChanged
              || !(existing.module().orElse(null) instanceof ArmyFormation);
      if (needsFormation) {
        String role =
            node.role() != null ? requireRole(node.role(), node.id()) : roleOf(existing, node.id());
        commands.add(
            command(
                SET_ARMY_FORMATION,
                map(
                    "unitId", node.id(),
                    "role", role,
                    "masterGov", masterGov.map(UnitId::value).orElse(null))));
      }
      if (node.personnel() != null) {
        addExistingPersonnelCommands(
            commands,
            units,
            social,
            node.id(),
            requirePersonnel(node.personnel(), node.id()),
            reason);
      }
      updated++;
    }
    // masterGov 是 Army 级字段：显式改变时，把当前树里未在载荷出现、且非新节点的既有单位也同步到新 GOV，
    // 否则 Army 与各节点 ArmyFormation.masterGov 会长期不一致（保留 role/duties/policy）。
    if (masterGovChanged) {
      Set<String> mentioned = new LinkedHashSet<>();
      for (NodeSpec node : flat) {
        mentioned.add(node.id());
      }
      for (String unitIdText : ownTree) {
        if (mentioned.contains(unitIdText)) {
          continue;
        }
        Unit unit = units.units().get(new UnitId(unitIdText));
        if (unit == null || !(unit.module().orElse(null) instanceof ArmyFormation formation)) {
          continue;
        }
        commands.add(
            command(
                SET_ARMY_FORMATION,
                map(
                    "unitId", unitIdText,
                    "role", formation.role(),
                    "masterGov", masterGov.map(UnitId::value).orElse(null))));
      }
    }
    if (!armyName.equals(army.name()) || masterGovChanged) {
      Map<String, Object> update = new LinkedHashMap<>();
      update.put("armyId", armyId.value());
      if (!armyName.equals(army.name())) {
        update.put("name", armyName);
      }
      if (masterGovChanged) {
        update.put("masterGovUnitId", masterGov.map(UnitId::value).orElse(""));
      }
      commands.add(command(UPDATE_ARMY, update));
    }
    long subtree = 0L;
    long direct = 0L;
    for (String unitId : resulting) {
      NodeSpec spec = specById.get(unitId);
      long personnel =
          spec != null && spec.personnel() != null
              ? requirePersonnel(spec.personnel(), unitId)
              : (units.units().containsKey(new UnitId(unitId))
                  ? social.unitPopulation(unitId)
                  : 0L);
      subtree = Math.addExact(subtree, personnel);
      if (unitId.equals(root.id())) {
        direct = personnel;
      }
    }
    return new Plan(
        "update",
        armyId.value(),
        armyName,
        masterGov.map(UnitId::value).orElse(null),
        root.id(),
        resulting.size(),
        created,
        updated,
        direct,
        subtree,
        List.copyOf(commands));
  }

  // ── positions / tree ────────────────────────────────────────────────

  /** 解析每个节点的落点：root 明坐标（create）或既有位置；子节点缺省从父写实。 */
  private static Map<String, HexCoord> resolvePositions(
      NodeSpec root,
      UnitId existingRoot,
      GameMap map,
      UnitState units,
      boolean create,
      SimosTimestamp at) {
    Map<String, HexCoord> positions = new LinkedHashMap<>();
    for (NodeSpec node : preorder(root)) {
      NodeSpec parent = parentOf(root, node);
      HexCoord position;
      if (parent == null) {
        if (node.position() != null) {
          position = node.position();
        } else if (!create && existingRoot != null) {
          position =
              units
                  .effectivePosition(existingRoot, at)
                  .orElseThrow(
                      () ->
                          new IllegalArgumentException(
                              "既有 root " + existingRoot + " 没有可确定的位置；update 请显式给 root.position"));
        } else {
          throw new IllegalArgumentException("create 的 root.position 必填");
        }
      } else {
        HexCoord parentAt = positions.get(parent.id());
        if (node.position() == null) {
          position = parentAt;
        } else {
          position = node.position();
        }
      }
      if (!map.hexes().containsKey(position)) {
        throw new IllegalArgumentException("节点 " + node.id() + " 的坐标不在图上: " + position);
      }
      positions.put(node.id(), position);
    }
    return positions;
  }

  private static List<NodeSpec> flatten(NodeSpec root) {
    List<NodeSpec> flat = new ArrayList<>();
    Deque<Frame> stack = new ArrayDeque<>();
    stack.push(new Frame(root, 1));
    Set<String> seen = new LinkedHashSet<>();
    while (!stack.isEmpty()) {
      Frame frame = stack.pop();
      NodeSpec node = frame.node();
      if (node.id() == null || node.id().isBlank()) {
        throw new IllegalArgumentException("每个节点必须给稳定 id（不得按数组下标派生身份）");
      }
      if (!seen.add(node.id())) {
        throw new IllegalArgumentException("编制树有重复节点 id: " + node.id());
      }
      if (frame.depth() > MAX_DEPTH) {
        throw new IllegalArgumentException(
            "编制树深度 " + frame.depth() + " 超过上限 " + MAX_DEPTH + "（节点 " + node.id() + "）");
      }
      flat.add(node);
      List<NodeSpec> children = node.children() == null ? List.of() : node.children();
      for (int index = children.size() - 1; index >= 0; index--) {
        NodeSpec child = children.get(index);
        if (child == null) {
          throw new IllegalArgumentException("节点 " + node.id() + " 的 children 不得含 null");
        }
        stack.push(new Frame(child, frame.depth() + 1));
      }
    }
    return List.copyOf(flat);
  }

  private static List<NodeSpec> preorder(NodeSpec root) {
    List<NodeSpec> out = new ArrayList<>();
    Deque<NodeSpec> stack = new ArrayDeque<>();
    stack.push(root);
    while (!stack.isEmpty()) {
      NodeSpec node = stack.pop();
      out.add(node);
      List<NodeSpec> children = node.children() == null ? List.of() : node.children();
      for (int index = children.size() - 1; index >= 0; index--) {
        stack.push(children.get(index));
      }
    }
    return List.copyOf(out);
  }

  private static NodeSpec parentOf(NodeSpec root, NodeSpec target) {
    if (root == target) {
      return null;
    }
    Deque<NodeSpec> stack = new ArrayDeque<>();
    stack.push(root);
    while (!stack.isEmpty()) {
      NodeSpec node = stack.pop();
      for (NodeSpec child : node.children() == null ? List.<NodeSpec>of() : node.children()) {
        if (child == target) {
          return node;
        }
        stack.push(child);
      }
    }
    throw new IllegalStateException("节点不在树里: " + target.id());
  }

  private static Set<String> treeOf(UnitState units, UnitId root, SimulationState state) {
    Set<String> members = new LinkedHashSet<>();
    Deque<UnitId> queue = new ArrayDeque<>();
    queue.add(root);
    while (!queue.isEmpty()) {
      UnitId id = queue.poll();
      if (!members.add(id.value())) {
        continue;
      }
      Unit unit = units.units().get(id);
      if (unit == null) {
        continue;
      }
      for (Unit candidate : units.units().values()) {
        Optional<UnitId> parent = candidate.parent().valueAt(state.meta().timestamp());
        if (parent.isPresent() && parent.orElseThrow().equals(id)) {
          queue.add(candidate.id());
        }
      }
    }
    return members;
  }

  // ── personnel ───────────────────────────────────────────────────────

  private static void addNewPersonnelCommands(
      List<Command> commands, String unitId, long personnel, String reason) {
    if (personnel <= 0L) {
      return;
    }
    String household = householdOf(unitId);
    commands.add(
        command(
            SET_UNIT_HOUSEHOLDS,
            map(
                "unitId", unitId,
                "households", List.of(household),
                "reason", calibrationReason(reason))));
    List<Map<String, Object>> plan = new ArrayList<>();
    plan.add(createHouseholdStep(unitId, household));
    plan.add(addMembersStep(household, personnel, "army-edit:" + unitId + ":1"));
    commands.add(command(SUBMIT_HOUSEHOLD_WORK_ORDER, workOrderPayload(household, reason, plan)));
  }

  private static void addExistingPersonnelCommands(
      List<Command> commands,
      UnitState units,
      SocialData social,
      String unitIdText,
      long target,
      String reason) {
    Unit unit = units.units().get(new UnitId(unitIdText));
    if (unit == null) {
      throw new IllegalArgumentException("personnel 目标单位不存在: " + unitIdText);
    }
    HouseholdId toolHousehold = new HouseholdId(householdOf(unitIdText));
    boolean hasToolHousehold = unit.households().contains(toolHousehold);
    long direct = social.unitPopulation(unitIdText);
    if (direct == target) {
      return;
    }
    List<HouseholdId> otherHouseholds = new ArrayList<>();
    for (HouseholdId household : unit.households()) {
      if (!household.equals(toolHousehold)) {
        otherHouseholds.add(household);
      }
    }
    if (!otherHouseholds.isEmpty()) {
      throw new IllegalArgumentException(
          "单位 "
              + unitIdText
              + " 还有非本工具家户 "
              + otherHouseholds
              + "（当前直接人数 "
              + direct
              + "，目标 "
              + target
              + "）：无法安全调整而不覆盖其他 Social 状态；请先人工处理这些家户");
    }
    List<Map<String, Object>> plan = new ArrayList<>();
    if (!hasToolHousehold) {
      if (target == 0L) {
        return;
      }
      String household = toolHousehold.value();
      plan.add(createHouseholdStep(unitIdText, household));
      plan.add(addMembersStep(household, target, nextLotId(social, unitIdText)));
      List<HouseholdId> next = new ArrayList<>(unit.households());
      next.add(toolHousehold);
      commands.add(
          command(
              SET_UNIT_HOUSEHOLDS,
              map(
                  "unitId", unitIdText,
                  "households", next.stream().map(HouseholdId::value).toList(),
                  "reason", calibrationReason(reason))));
      commands.add(command(SUBMIT_HOUSEHOLD_WORK_ORDER, workOrderPayload(household, reason, plan)));
      return;
    }
    Household household = social.households().get(toolHousehold);
    if (household == null) {
      throw new IllegalArgumentException(
          "单位 "
              + unitIdText
              + " 的 Unit.households 指向 "
              + toolHousehold
              + "，但 Social 里没有这个家户（状态不自洽）");
    }
    long current = social.householdPopulation(toolHousehold);
    if (target > current) {
      plan.add(
          addMembersStep(toolHousehold.value(), target - current, nextLotId(social, unitIdText)));
    } else {
      long toRemove = current - target;
      for (Map.Entry<PeopleLotId, Long> entry : household.members().entrySet()) {
        if (toRemove <= 0L) {
          break;
        }
        long take = Math.min(toRemove, entry.getValue());
        if (take <= 0L) {
          continue;
        }
        plan.add(removeMembersStep(toolHousehold.value(), entry.getKey().value(), take));
        toRemove -= take;
      }
      if (toRemove > 0L) {
        throw new IllegalStateException(
            "单位 " + unitIdText + " 的家户成员不足以减到目标 " + target + "（差 " + toRemove + "）");
      }
    }
    if (!plan.isEmpty()) {
      commands.add(
          command(
              SUBMIT_HOUSEHOLD_WORK_ORDER, workOrderPayload(toolHousehold.value(), reason, plan)));
    }
  }

  private static Map<String, Object> workOrderPayload(
      String target, String callerReason, List<Map<String, Object>> plan) {
    return map(
        "target", target,
        "reason", calibrationReason(callerReason),
        "source", Map.of("module", "army"),
        "plan", List.copyOf(plan));
  }

  private static String calibrationReason(String callerReason) {
    return "editFormation 直接人数校准（调用者原因："
        + callerReason
        + "；新成员默认成年男性锚点 "
        + DEFAULT_ADULT_MALE_AGE_DAYS
        + " 天，不是真实人口结构）";
  }

  private static Map<String, Object> createHouseholdStep(String unitId, String householdId) {
    return map(
        "op", "CREATE_HOUSEHOLD",
        "household", householdId,
        "location", Map.of("type", "UNIT", "unitId", unitId),
        "profile", Map.of("name", unitId + "·编制人数"),
        "vitalRates", List.of());
  }

  private static Map<String, Object> addMembersStep(String householdId, long count, String lotId) {
    return map(
        "op", "ADD_MEMBERS",
        "household", householdId,
        "lotId", lotId,
        "sex", "MALE",
        "count", count,
        "ageAtAnchorDays", DEFAULT_ADULT_MALE_AGE_DAYS);
  }

  private static Map<String, Object> removeMembersStep(
      String householdId, String lotId, long count) {
    return map(
        "op", "REMOVE_MEMBERS",
        "household", householdId,
        "lotId", lotId,
        "count", count);
  }

  private static String nextLotId(SocialData social, String unitId) {
    String prefix = "army-edit:" + unitId + ":";
    for (int suffix = 1; suffix < Integer.MAX_VALUE; suffix++) {
      PeopleLotId candidate = PeopleLotId.parse(prefix + suffix);
      if (!social.groups().containsKey(candidate)) {
        return candidate.value();
      }
    }
    throw new IllegalStateException("确定性批次 id 后缀已用尽: " + prefix);
  }

  private static String householdOf(String unitId) {
    return "hh-unit:" + unitId;
  }

  // ── validation helpers ──────────────────────────────────────────────

  private static Optional<UnitId> parseMasterGov(UnitState units, String masterGovText) {
    if (masterGovText == null || masterGovText.isBlank()) {
      return Optional.empty();
    }
    UnitId masterGov = UnitId.parse(masterGovText);
    Unit unit = units.units().get(masterGov);
    if (unit == null) {
      throw new IllegalArgumentException("masterGov 不存在: " + masterGov);
    }
    if (!(unit.module().orElse(null) instanceof GovernmentFormation)) {
      throw new IllegalArgumentException("masterGov 不是 GOV 单位: " + masterGov);
    }
    return Optional.of(masterGov);
  }

  private static String requireName(String name, String id) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("节点 " + id + " 的 name 不得为空白");
    }
    return name;
  }

  private static String requireRole(String role, String id) {
    if (role == null || role.isBlank()) {
      throw new IllegalArgumentException("节点 " + id + " 的 role 不得为空白（复用 ArmyFormation.role）");
    }
    return role;
  }

  private static String roleOf(Unit unit, String id) {
    if (unit.module().orElse(null) instanceof ArmyFormation formation) {
      return formation.role();
    }
    throw new IllegalArgumentException("节点 " + id + " 没有 ArmyFormation，update 必须给 role");
  }

  private static long requirePersonnel(long personnel, String id) {
    if (personnel < 0L) {
      throw new IllegalArgumentException("节点 " + id + " 的 personnel 必须 ≥ 0: " + personnel);
    }
    return personnel;
  }

  private static int requireAtLeastOne(Integer value, int fallback, String field, String id) {
    if (value == null) {
      return fallback;
    }
    if (value < 1) {
      throw new IllegalArgumentException("节点 " + id + " 的 " + field + " 必须 ≥ 1: " + value);
    }
    return value;
  }

  private static List<CompositionEntry> equipmentOf(Map<String, Long> equipment, String id) {
    if (equipment == null) {
      return List.of();
    }
    List<String> types = new ArrayList<>(equipment.keySet());
    types.sort(Comparator.naturalOrder());
    List<CompositionEntry> entries = new ArrayList<>();
    for (String type : types) {
      if (type == null || type.isBlank()) {
        throw new IllegalArgumentException("节点 " + id + " 的装备 type 不得为空白");
      }
      Long amount = equipment.get(type);
      if (amount == null || amount < 0L) {
        throw new IllegalArgumentException("节点 " + id + " 的装备 " + type + " 数量必须 ≥ 0: " + amount);
      }
      entries.add(new CompositionEntry(type, amount));
    }
    return List.copyOf(entries);
  }

  private static List<Map<String, Object>> equipmentPayload(List<CompositionEntry> equipment) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (CompositionEntry entry : equipment) {
      out.add(Map.of("type", entry.type(), "amount", entry.amount()));
    }
    return List.copyOf(out);
  }

  private static Command command(String type, Map<String, Object> payload) {
    return new Command(type, ToolResults.json(payload));
  }

  private static Map<String, Object> map(Object... keyValues) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (int index = 0; index < keyValues.length; index += 2) {
      out.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
    }
    return out;
  }

  private static Map<String, Object> hex(HexCoord hex) {
    return map("q", hex.q(), "r", hex.r());
  }

  private record Frame(NodeSpec node, int depth) {}

  /** 计划回显。 */
  public static Map<String, Object> view(Plan plan, boolean dryRun, String reason) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("dryRun", dryRun);
    out.put("mode", plan.mode());
    out.put("armyId", plan.armyId());
    out.put("name", plan.name());
    out.put("masterGov", plan.masterGov());
    out.put("rootUnitId", plan.rootUnitId());
    out.put("nodeCount", plan.nodeCount());
    out.put("createdNodes", plan.createdNodes());
    out.put("updatedNodes", plan.updatedNodes());
    out.put("directPersonnel", plan.directPersonnel());
    out.put("subtreePersonnel", plan.subtreePersonnel());
    out.put(
        "personnelNote",
        "personnel 是本级直接人数；新成员默认成年男性锚点年龄 " + DEFAULT_ADULT_MALE_AGE_DAYS + " 天，不是真实人口结构。");
    out.put("reason", reason);
    List<Map<String, Object>> commands = new ArrayList<>();
    for (Command command : plan.commands()) {
      commands.add(Map.of("type", command.type(), "payload", command.payloadJson()));
    }
    out.put("commands", List.copyOf(commands));
    return out;
  }
}
