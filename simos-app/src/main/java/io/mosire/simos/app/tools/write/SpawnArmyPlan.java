package io.mosire.simos.app.tools.write;

import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.unit.GovFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ {@code simos.unit.spawnArmy} 的<b>纯推导</b>（P5，2026-10-01 后端 + MCP 稳定化计划）：从一份 {@link
 * SimulationState} 与参数算出 {@link Plan}——<b>不碰 {@link io.mosire.agentlib.tool.ToolContext}、不碰 {@code
 * CoreSimos}</b>，preview 与 apply 因此共用同一份语义（工具只负责读态、把 Plan 折成视图、组批、折叠结局）。
 *
 * <p>★★ <b>它为什么是 app 级组合工具而不是一条命令</b>：新单位在 {@code unit} 切片、Army 归属在 {@code sd} 切片，单条命令只能落一个
 * 命名空间。本工具走 {@link io.mosire.simos.core.CoreSimos#submitBatch}（同 branch + 同 expectedRevision ⇒ 一批 =
 * 一条 revision，原子）。
 *
 * <p>★★ <b>批顺序（固定，可复现）</b>：{@code unit.CreateUnit} →（{@code role} 非空才落）{@code
 * unit.SetArmyFormation} → {@code sd.CreateArmy} → {@code sd.PutInfo}。后三条都看得见前一条累积后的候选态，故 {@code
 * sd.CreateArmy} 的 {@code rootUnitId} 就是同批刚创建的 root 单位。
 *
 * <p>★★ <b>GM 特权口径</b>：直接建军<b>不抽人口、不抽粮饷</b>（用户 2026-10-01 裁定 6）；{@code member} 直接写进新单位，
 * 允许无人口、无国库；{@code member} 仍须 ≥ 1（P5 计划明文）。{@code raiseUnit} 保持抽取语义，一个字不动。
 *
 * <p>★★ <b>{@code masterGov} 的双边语义</b>：{@code masterGov} 给了 ⇒ 必须存在且带 {@link GovFormation}；同批写入
 * {@code sd.CreateArmy.masterGovUnitId}。{@code role} 非空 ⇒ 同批再落 {@code unit.SetArmyFormation}，并把同一个
 * {@code masterGov} 写进它的 {@code masterGov} 字段。{@code role} 为空但 {@code masterGov} 给了 ⇒ <b>只写 sd
 * 侧</b>（unit 侧不落 ArmyFormation，也就不设 {@code ArmyFormation.masterGov}）——preview 由 {@link
 * Plan#armyFormationNote()} 明确说明。
 *
 * <p>★★ <b>纯推导校验（前置不满足 ⇒ 工具折 {@code BAD_REQUEST}、零 revision）</b>：{@code unitId}/{@code name}/
 * {@code armyId} 非空白；{@code member ≥ 1}；{@code speed ≥ 1}；{@code mobilityPerMille ∈
 * [1,1000]}；{@code equipment} 键非空、值 ≥ 0；{@code unitId} 不得与既有单位重复；{@code armyId} 不得与既有 Army
 * 重复；{@code (q,r)} 必须存在于当前 {@link GameMap}；{@code parent} 若给必须存在且当刻有效位置与落点同格；{@code masterGov}
 * 若给必须存在且带 {@link GovFormation}。批内域层拒（如同一 tick 的第二次改编）由 {@code submitBatch} 整条拒，逐条真拒因折成 {@code
 * REJECTED}。
 *
 * <p>★ <b>确定性 / 保序不可变</b>：不碰墙钟（{@code tick} 是状态 meta 的函数）、不用随机量；{@code equipment} 用 {@link
 * LinkedHashMap} 拷贝 + 赋值处冻结（{@code Collections.unmodifiableMap}，<b>不用</b> {@code Map.copyOf}——它不承诺
 * 保序）。
 */
final class SpawnArmyPlan {

  /** {@code unit.CreateUnit} 的命令类型（字面量与 {@code CreateUnitHandler.type()} 同源）。 */
  static final String CREATE_UNIT_TYPE = "unit.CreateUnit";

  /** {@code unit.SetArmyFormation} 的命令类型（仅 {@code role} 非空才落）。 */
  static final String SET_ARMY_FORMATION_TYPE = "unit.SetArmyFormation";

  /** {@code sd.CreateArmy} 的命令类型。 */
  static final String CREATE_ARMY_TYPE = "sd.CreateArmy";

  /** {@code sd.PutInfo} 的命令类型（恒有，行动记录）。 */
  static final String PUT_INFO_TYPE = "sd.PutInfo";

  /** 新单位速度缺省值（P5 计划：{@code speed} 可选，缺省 3；域层要求 ≥ 1）。 */
  static final int DEFAULT_SPEED = 3;

  /** 新单位机动性缺省值（P5 计划：{@code mobilityPerMille} 可选，缺省 1000；域层要求 [1,1000]）。 */
  static final int DEFAULT_MOBILITY_PER_MILLE = 1000;

  /** 新单位状态缺省值（P5 计划：{@code status} 可选，缺省 {@code RESTING}）。 */
  static final UnitStatus DEFAULT_STATUS = UnitStatus.RESTING;

  private SpawnArmyPlan() {}

  /**
   * 纯推导入口（见类注的批顺序与校验清单）。
   *
   * @param state 读数所在的状态（preview / apply 都取<b>同一坐标</b>的状态）
   * @param unitId 新 root 单位 id（不得与既有单位重复）
   * @param name 新单位名（非空白）
   * @param at 新单位落点（工具层的 {@code q}/{@code r}；必须是当前 GameMap 上的格）
   * @param member 新单位人数（≥ 1；GM 直接建军不抽人口）
   * @param equipment 新单位装备（可空表；键非空白、值 ≥ 0）
   * @param speed 新单位速度（≥ 1）
   * @param mobilityPerMille 新单位机动性（[1,1000]）
   * @param parent 父单位 id（可选；给了必须存在且当刻有效位置与 {@code at} 同格）
   * @param status 新单位状态（工具层已折；缺省 {@link #DEFAULT_STATUS}）
   * @param armyId Army id（工具层已给缺省；不得与既有 Army 重复）
   * @param role 兵种/职责短名（可选；给了才同批落 {@code unit.SetArmyFormation}）
   * @param masterGov 认领的 GOV 单位 id（可选；给了必须存在且带 {@link GovFormation}）
   * @throws IllegalArgumentException 任一具名前置不满足（工具折 {@code BAD_REQUEST}）
   */
  static Plan plan(
      SimulationState state,
      String unitId,
      String name,
      HexCoord at,
      int member,
      Map<String, Integer> equipment,
      int speed,
      int mobilityPerMille,
      Optional<String> parent,
      UnitStatus status,
      String armyId,
      Optional<String> role,
      Optional<String> masterGov) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(equipment, "equipment");
    Objects.requireNonNull(parent, "parent");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(masterGov, "masterGov");
    requireNonBlank(unitId, "unitId");
    requireNonBlank(name, "name");
    requireNonBlank(armyId, "armyId");
    if (member < 1) {
      throw new IllegalArgumentException("member 必须 ≥ 1: " + member);
    }
    if (speed < 1) {
      throw new IllegalArgumentException("speed 必须 ≥ 1（unit.CreateUnit 领域约束）: " + speed);
    }
    if (mobilityPerMille < 1 || mobilityPerMille > 1000) {
      throw new IllegalArgumentException(
          "mobilityPerMille 必须在 [1,1000]（unit.CreateUnit 领域约束 + 千分上限）: " + mobilityPerMille);
    }
    requireEquipment(equipment);

    UnitState units = ToolSupport.unitState(state);
    UnitId id = UnitId.parse(unitId);
    if (units.units().containsKey(id)) {
      throw new IllegalArgumentException("单位 id 已存在（unitId 不得复用）: " + unitId);
    }
    GameMap map = ToolSupport.gameMap(state);
    if (!map.hexes().containsKey(at)) {
      throw new IllegalArgumentException(
          "hex " + hexText(at) + " 不在当前 GameMap 上（先 map.CreateRegion / 地图编辑纳入该格）");
    }
    SimosTimestamp timestamp = state.meta().timestamp();
    parent.ifPresent(parentId -> requireParentAt(units, parentId, at, timestamp));
    ArmyId army = ArmyId.parse(armyId);
    if (ToolSupport.sdState(state).armies().containsKey(army)) {
      throw new IllegalArgumentException("军队 id 已存在（armyId 不得复用）: " + armyId);
    }
    masterGov.ifPresent(govId -> requireGovUnit(units, govId, "masterGov"));
    return new Plan(
        unitId,
        name,
        at,
        member,
        equipment,
        speed,
        mobilityPerMille,
        parent,
        status,
        armyId,
        role,
        masterGov,
        timestamp.tick());
  }

  /** 装备形状校验：可为空表；键非空、值非负（范围已在工具面折成 int）。 */
  private static void requireEquipment(Map<String, Integer> equipment) {
    for (Map.Entry<String, Integer> entry : equipment.entrySet()) {
      if (entry.getKey() == null || entry.getKey().isBlank()) {
        throw new IllegalArgumentException("equipment 的键不得空白");
      }
      if (entry.getValue() == null || entry.getValue() < 0) {
        throw new IllegalArgumentException(
            "equipment 的值必须 ≥ 0: " + entry.getKey() + "=" + entry.getValue());
      }
    }
  }

  /** 父单位必须存在且当刻有效位置与落点同格（{@code unit.CreateUnit} 的硬要求）。 */
  private static void requireParentAt(
      UnitState units, String parentId, HexCoord at, SimosTimestamp timestamp) {
    requireNonBlank(parentId, "parent");
    UnitId parentKey = UnitId.parse(parentId);
    if (!units.units().containsKey(parentKey)) {
      throw new IllegalArgumentException("parent 单位不存在: " + parentId);
    }
    Optional<HexCoord> parentAt = units.effectivePosition(parentKey, timestamp);
    if (parentAt.isEmpty() || !parentAt.get().equals(at)) {
      throw new IllegalArgumentException(
          "parent "
              + parentId
              + " 当刻有效位置 "
              + parentAt.map(SpawnArmyPlan::hexText).orElse("(不可确定)")
              + " 与新单位落点 "
              + hexText(at)
              + " 不同格：unit.CreateUnit 要求同格才能编入同一支");
    }
  }

  /**
   * {@code masterGov} 必须存在且带 {@link GovFormation}（与 {@code sd.CreateArmy}/{@code UnitOperations}
   * 同口径）。
   */
  private static void requireGovUnit(UnitState units, String govId, String field) {
    requireNonBlank(govId, field);
    UnitId gov = UnitId.parse(govId);
    Unit unit = units.units().get(gov);
    if (unit == null) {
      throw new IllegalArgumentException(field + " 指定的 GOV 单位不存在: " + govId);
    }
    if (!(unit.module().orElse(null) instanceof GovFormation)) {
      throw new IllegalArgumentException(field + " 指定的单位 " + govId + " 没有 GovFormation：不能作为 GOV");
    }
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " 必须是非空文本");
    }
  }

  /** 格的可读文本（拒因、note 与 info 视图共用；格式不与任何资源路径语法绑定）。 */
  private static String hexText(HexCoord at) {
    return "(" + at.q() + "," + at.r() + ")";
  }

  /**
   * 单位 canonical 地址（{@code unit:<unitId>}）：只经 {@link Address#parse} → {@link Address#canonical()}（与
   * {@code RaiseUnitTool}/{@code GovCreateOfficeTool} 同款），行动记录的唯一拼写点。
   */
  static String unitAddress(String unitId) {
    Objects.requireNonNull(unitId, "unitId");
    return Address.parse(ToolSupport.UNIT_NAMESPACE + ":" + unitId).canonical();
  }

  /**
   * 一份按格建军计划（全部字段是状态与参数的纯函数）。
   *
   * @param unitId root 单位 id
   * @param name 新单位名
   * @param at 新单位落点
   * @param member 新单位人数（≥ 1）
   * @param equipment 新单位装备（保序不可变；缺省空表）
   * @param speed 新单位速度（≥ 1）
   * @param mobilityPerMille 新单位机动性（[1,1000]）
   * @param parent 父单位 id（可选）
   * @param status 新单位状态
   * @param armyId Army id
   * @param role 兵种/职责短名（可选；给了才落 {@code unit.SetArmyFormation}）
   * @param masterGov 认领的 GOV 单位 id（可选）
   * @param tick 推导时的世界日（行动记录用）
   */
  record Plan(
      String unitId,
      String name,
      HexCoord at,
      int member,
      Map<String, Integer> equipment,
      int speed,
      int mobilityPerMille,
      Optional<String> parent,
      UnitStatus status,
      String armyId,
      Optional<String> role,
      Optional<String> masterGov,
      long tick) {

    Plan {
      requireNonBlank(unitId, "unitId");
      requireNonBlank(name, "name");
      requireNonBlank(armyId, "armyId");
      Objects.requireNonNull(at, "at");
      Objects.requireNonNull(parent, "parent");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(role, "role");
      Objects.requireNonNull(masterGov, "masterGov");
      if (member < 1) {
        throw new IllegalArgumentException("member 必须 ≥ 1: " + member);
      }
      if (speed < 1) {
        throw new IllegalArgumentException("speed 必须 ≥ 1: " + speed);
      }
      if (mobilityPerMille < 1 || mobilityPerMille > 1000) {
        throw new IllegalArgumentException("mobilityPerMille 必须在 [1,1000]: " + mobilityPerMille);
      }
      if (tick < 0L) {
        throw new IllegalArgumentException("tick 不得为负: " + tick);
      }
      parent.ifPresent(value -> requireNonBlank(value, "parent"));
      role.ifPresent(value -> requireNonBlank(value, "role"));
      masterGov.ifPresent(value -> requireNonBlank(value, "masterGov"));
      // ★ 装备冻在赋值处（SpotBugs 的 EI_EXPOSE_REP 只认它看得见的 Collections.unmodifiable*）；
      //   不用 Map.copyOf（它不承诺保序）。
      Map<String, Integer> copy = new LinkedHashMap<>();
      for (Map.Entry<String, Integer> entry :
          Objects.requireNonNull(equipment, "equipment").entrySet()) {
        if (entry.getKey() == null || entry.getKey().isBlank()) {
          throw new IllegalArgumentException("equipment 的键不得空白");
        }
        if (entry.getValue() == null || entry.getValue() < 0) {
          throw new IllegalArgumentException(
              "equipment 的值必须 ≥ 0: " + entry.getKey() + "=" + entry.getValue());
        }
        copy.put(entry.getKey(), entry.getValue());
      }
      equipment = Collections.unmodifiableMap(copy);
    }

    /** 是否要落 {@code unit.SetArmyFormation}（只有 {@code role} 给了才落）。 */
    boolean hasArmyFormationCommand() {
      return role.isPresent();
    }

    /** 本工具将落的命令类型（批内固定顺序；preview 视图与 apply 组批共用这一处）。 */
    List<String> commandTypes() {
      List<String> types = new ArrayList<>(4);
      types.add(CREATE_UNIT_TYPE);
      if (hasArmyFormationCommand()) {
        types.add(SET_ARMY_FORMATION_TYPE);
      }
      types.add(CREATE_ARMY_TYPE);
      types.add(PUT_INFO_TYPE);
      return List.copyOf(types);
    }

    /**
     * {@code unit.CreateUnit} 载荷：位置恒为 {@code at}，status 显式写入（缺省 RESTING 不靠 handler 的 MOVING 缺省）。
     */
    String createUnitPayloadJson() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("id", unitId);
      payload.put("name", name);
      payload.put("position", ToolSupport.hexCoord(at));
      payload.put("member", member);
      payload.put("equipment", new LinkedHashMap<>(equipment));
      payload.put("speed", speed);
      payload.put("mobilityPerMille", mobilityPerMille);
      payload.put("status", status.name());
      parent.ifPresent(parentId -> payload.put("parent", parentId));
      return ToolSupport.json(payload);
    }

    /** {@code unit.SetArmyFormation} 载荷：{@code {unitId, role, masterGov?}}（仅 role 非空时组装）。 */
    String setArmyFormationPayloadJson() {
      if (!hasArmyFormationCommand()) {
        throw new IllegalStateException("批不自洽：role 为空却要组装 unit.SetArmyFormation 载荷");
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("unitId", unitId);
      payload.put("role", role.orElseThrow());
      masterGov.ifPresent(govId -> payload.put("masterGov", govId));
      return ToolSupport.json(payload);
    }

    /**
     * {@code sd.CreateArmy} 载荷：{@code {armyId, masterGovUnitId?, rootUnitId, name}}——rootUnitId
     * 指向同批刚创建的 单位；masterGov 给了才写 sd 侧主子。
     */
    String createArmyPayloadJson() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("armyId", armyId);
      masterGov.ifPresent(govId -> payload.put("masterGovUnitId", govId));
      payload.put("rootUnitId", unitId);
      payload.put("name", name);
      return ToolSupport.json(payload);
    }

    /** {@code sd.PutInfo} 的 {@code value}（JSON <b>字符串</b>；字段序固定，人可读审计）。 */
    String infoValueJson(String reason) {
      requireNonBlank(reason, "reason");
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("unitId", unitId);
      value.put("armyId", armyId);
      value.put("hex", ToolSupport.hexCoord(at));
      value.put("member", member);
      value.put("equipment", new LinkedHashMap<>(equipment));
      value.put("speed", speed);
      value.put("mobility", mobilityPerMille);
      value.put("masterGov", masterGov.orElse(null));
      value.put("role", role.orElse(null));
      value.put("reason", reason);
      return ToolSupport.json(value);
    }

    /**
     * unit 侧 {@code ArmyFormation} 的说明：role 非空时说明将落命令；role 为空但 masterGov 给了时，明确说明 unit 侧不设 {@code
     * ArmyFormation.masterGov}（只写 sd 侧）。
     */
    String armyFormationNote() {
      if (hasArmyFormationCommand()) {
        return "unit.SetArmyFormation 将落：role="
            + role.orElseThrow()
            + "，masterGov="
            + masterGov.orElse("(未认主子)");
      }
      if (masterGov.isPresent()) {
        return "role 为空：unit 侧不落 unit.SetArmyFormation，unit 侧未设 ArmyFormation.masterGov；"
            + "masterGov 只写入 sd.CreateArmy.masterGovUnitId";
      }
      return "role 为空：unit 侧不落 unit.SetArmyFormation，unit 侧未设 ArmyFormation.masterGov";
    }

    /** 人可读行动摘要（工具结果与 {@code sd.PutInfo.note} 共用）。 */
    String infoNote(String reason) {
      requireNonBlank(reason, "reason");
      return "按格直接建军 "
          + unitId
          + "（"
          + name
          + "，tick "
          + tick
          + "）：armyId="
          + armyId
          + "，hex="
          + hexText(at)
          + "，member="
          + member
          + "，equipment="
          + equipment
          + "，speed="
          + speed
          + "，mobility="
          + mobilityPerMille
          + "，status="
          + status
          + "，parent="
          + parent.orElse("(无)")
          + "，role="
          + role.orElse("(不落 SetArmyFormation)")
          + "，masterGov="
          + masterGov.orElse("(未给)")
          + "；reason="
          + reason;
    }
  }
}
