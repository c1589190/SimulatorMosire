package io.mosire.simos.app.tools.write;

import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.model.Army;
import io.mosire.simos.unit.ArmyFormation;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ {@code simos.army.assignGov} 的<b>纯推导</b>（阶段 12 后续赋值缺口，2026-10-01）：从一份 {@link SimulationState}
 * 与参数算出 {@link Plan}——<b>不碰 {@link io.mosire.agentlib.tool.ToolContext}、不碰 {@code
 * CoreSimos}</b>，preview 与 apply 因此共用同一份语义（工具只负责读态、把 Plan 折成视图、组批、折叠结局）。
 *
 * <p>★★ <b>它为什么是 app 级组合工具而不是一条命令</b>：Army 主子在 {@code sd} 切片、单位侧 {@code ArmyFormation.masterGov} 在
 * {@code unit} 切片，单条命令只能落一个命名空间。本工具走 {@link io.mosire.simos.core.CoreSimos#submitBatch}（同 branch +
 * 同 expectedRevision ⇒ 一批 = 一条 revision，原子），把两侧主子同批落定、不给漂移留窗口。
 *
 * <p>★★ <b>批顺序（固定，可复现）</b>：需要同步 unit 侧时先 {@code unit.SetArmyFormation} → {@code
 * sd.SetArmyMasterGov} → {@code sd.PutInfo}；不需要同步时第一条缺席。后一条看得见前一条累积后的候选态。
 *
 * <p>★★ <b>unit 侧 role 语义</b>：
 *
 * <ul>
 *   <li>root unit <b>已有</b> {@link ArmyFormation} ⇒ 用它的<b>原 role</b>；本工具只同步 {@code masterGov}，不改
 *       role（调用方给的 {@code role} 在该分支不参与，preview 会回显实际使用的原 role）；
 *   <li>root unit <b>没有</b> {@code ArmyFormation} 且 {@code role} 给了 ⇒ 用给定 role 落同批 {@code
 *       unit.SetArmyFormation}；
 *   <li>root unit <b>没有</b> {@code ArmyFormation} 且 {@code role} 没给 ⇒ {@link
 *       IllegalArgumentException}（工具折 {@code BAD_REQUEST}，零 revision）：要么给 role，要么 {@code
 *       syncUnitSide=false}。
 * </ul>
 *
 * <p>★ <b>{@code masterGovUnitId} 的三态</b>：缺席 / {@code null} / 空串（含空白串）= 解除认领；给了非空白值必须存在、 且带 {@link
 * GovernmentFormation}（与 {@code sd.CreateArmy}/{@code unit.SetArmyFormation} 同口径）。
 *
 * <p>★ <b>纯推导校验（前置不满足 ⇒ 工具折 {@code BAD_REQUEST}、零 revision）</b>：{@code armyId}/{@code reason}
 * 非空白；Army 必须存在；root unit 必须存在；新主子若给必须存在且带 {@link GovernmentFormation}；{@code syncUnitSide=true} 时 unit 侧
 * role 必须能确定（见上）。批内域层拒（如 root 已带 {@code GovernmentFormation}、同 tick 二次改编）由 {@code submitBatch} 整条拒，逐条真拒因折成
 * {@code REJECTED}。
 *
 * <p>★ <b>确定性</b>：不碰墙钟（{@code tick} 是状态 meta 的函数）、不用随机量；字段序固定（{@code LinkedHashMap}）。
 */
final class AssignArmyGovPlan {

  /** {@code unit.SetArmyFormation} 的命令类型（仅需要同步 unit 侧时落）。 */
  static final String SET_ARMY_FORMATION_TYPE = "unit.SetArmyFormation";

  /** {@code sd.SetArmyMasterGov} 的命令类型。 */
  static final String SET_ARMY_MASTER_GOV_TYPE = "sd.SetArmyMasterGov";

  /** {@code sd.PutInfo} 的命令类型（恒有，行动记录）。 */
  static final String PUT_INFO_TYPE = "sd.PutInfo";

  private AssignArmyGovPlan() {}

  /**
   * 纯推导入口（见类注的批顺序、role 语义与校验清单）。
   *
   * @param state 读数所在的状态（preview / apply 都取<b>同一坐标</b>的状态）
   * @param armyId Army id（必须已存在）
   * @param masterGovUnitId 新主子 GOV 单位 id；空 Optional = 解除认领
   * @param syncUnitSide 是否同批同步 root unit 的 {@code ArmyFormation.masterGov}
   * @param role unit 侧 role（仅"root 还没有 ArmyFormation 且需要同步"时参与，必须是给定值；工具层已折成非空白）
   * @throws IllegalArgumentException 任一具名前置不满足（工具折 {@code BAD_REQUEST}）
   */
  static Plan plan(
      SimulationState state,
      String armyId,
      Optional<String> masterGovUnitId,
      boolean syncUnitSide,
      Optional<String> role) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(masterGovUnitId, "masterGovUnitId");
    Objects.requireNonNull(role, "role");
    requireNonBlank(armyId, "armyId");
    ArmyId id = ArmyId.parse(armyId);
    Army army = ToolSupport.sdState(state).armies().get(id);
    if (army == null) {
      throw new IllegalArgumentException("军队不存在: " + armyId);
    }
    UnitState units = ToolSupport.unitState(state);
    Unit root = units.units().get(army.rootUnit());
    if (root == null) {
      throw new IllegalArgumentException("root 单位不存在: " + army.rootUnit());
    }
    masterGovUnitId.ifPresent(govId -> requireGovUnit(units, govId, "masterGovUnitId"));
    Optional<String> effectiveRole;
    if (syncUnitSide) {
      if (root.module().orElse(null) instanceof ArmyFormation formation) {
        // ★ 已有 ArmyFormation：只同步 masterGov，role 取原值（调用方给的 role 在该分支不参与）。
        effectiveRole = Optional.of(formation.role());
      } else if (role.isPresent()) {
        effectiveRole = role;
      } else {
        throw new IllegalArgumentException(
            "root 单位 "
                + army.rootUnit()
                + " 还没有 ArmyFormation：要么给 role（同批落 unit.SetArmyFormation），"
                + "要么 syncUnitSide=false（只改 sd 侧主从关系）");
      }
    } else {
      effectiveRole = Optional.empty();
    }
    return new Plan(
        armyId,
        army.rootUnit().value(),
        army.masterGovUnitId().map(UnitId::value),
        masterGovUnitId,
        syncUnitSide,
        effectiveRole,
        state.meta().timestamp().tick());
  }

  /** 新主子必须存在且带 {@link GovernmentFormation}（与 {@code sd.CreateArmy} 同口径，纯只读）。 */
  private static void requireGovUnit(UnitState units, String govId, String field) {
    requireNonBlank(govId, field);
    UnitId gov = UnitId.parse(govId);
    Unit unit = units.units().get(gov);
    if (unit == null) {
      throw new IllegalArgumentException(field + " 指定的 GOV 单位不存在: " + govId);
    }
    if (!(unit.module().orElse(null) instanceof GovernmentFormation)) {
      throw new IllegalArgumentException(field + " 指定的单位 " + govId + " 没有 GovernmentFormation：不能作为 GOV");
    }
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " 必须是非空文本");
    }
  }

  /**
   * Army 的 canonical 地址（{@code sd:army.<armyId>}）：与 {@code SdResolver} 的 {@code address("army",
   * name)} 同形态（AST → {@link Address#canonical()}，不手拼），行动记录的唯一拼写点。
   */
  static String armyAddress(String armyId) {
    Objects.requireNonNull(armyId, "armyId");
    return new Address(List.of(new Namespace(ToolSupport.SD_NAMESPACE), Entity.of("army", armyId)))
        .canonical();
  }

  /**
   * 一份主子改派计划（全部字段是状态与参数的纯函数）。
   *
   * @param armyId Army id
   * @param rootUnit root 单位 id
   * @param oldMasterGov 旧主子 GOV 单位 id（未认领 = 空）
   * @param newMasterGov 新主子 GOV 单位 id（解除认领 = 空）
   * @param syncUnitSide 是否同步 unit 侧
   * @param role unit 侧实际使用的 role（{@code syncUnitSide=false} 时为空）
   * @param tick 推导时的世界日（行动记录用）
   */
  record Plan(
      String armyId,
      String rootUnit,
      Optional<String> oldMasterGov,
      Optional<String> newMasterGov,
      boolean syncUnitSide,
      Optional<String> role,
      long tick) {

    Plan {
      requireNonBlank(armyId, "armyId");
      requireNonBlank(rootUnit, "rootUnit");
      Objects.requireNonNull(oldMasterGov, "oldMasterGov");
      Objects.requireNonNull(newMasterGov, "newMasterGov");
      Objects.requireNonNull(role, "role");
      if (syncUnitSide && role.isEmpty()) {
        throw new IllegalArgumentException("批不自洽：syncUnitSide=true 却没有 unit 侧 role");
      }
      if (!syncUnitSide && role.isPresent()) {
        throw new IllegalArgumentException("批不自洽：syncUnitSide=false 却带着 unit 侧 role");
      }
      if (tick < 0L) {
        throw new IllegalArgumentException("tick 不得为负: " + tick);
      }
    }

    /** 是否要落 {@code unit.SetArmyFormation}（同步 unit 侧时恒落，载荷带实际 role 与可选新主子）。 */
    boolean hasUnitCommand() {
      return syncUnitSide;
    }

    /** 本工具将落的命令类型（批内固定顺序；preview 视图与 apply 组批共用这一处）。 */
    List<String> commandTypes() {
      List<String> types = new ArrayList<>(3);
      if (hasUnitCommand()) {
        types.add(SET_ARMY_FORMATION_TYPE);
      }
      types.add(SET_ARMY_MASTER_GOV_TYPE);
      types.add(PUT_INFO_TYPE);
      return List.copyOf(types);
    }

    /**
     * {@code unit.SetArmyFormation} 载荷：{@code {unitId, role, masterGov?}}——新主子有就给， 解除认领就不给该键（缺省 =
     * 未认主子）。
     */
    String setArmyFormationPayloadJson() {
      if (!hasUnitCommand()) {
        throw new IllegalStateException("批不自洽：不需要同步 unit 侧却要组装 unit.SetArmyFormation 载荷");
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("unitId", rootUnit);
      payload.put("role", role.orElseThrow());
      newMasterGov.ifPresent(govId -> payload.put("masterGov", govId));
      return ToolSupport.json(payload);
    }

    /**
     * {@code sd.SetArmyMasterGov} 载荷：{@code {armyId, masterGovUnitId?}}——新主子有就给，解除认领就不给该键（缺省/
     * null/空串 = 解除）。
     */
    String setArmyMasterGovPayloadJson() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("armyId", armyId);
      newMasterGov.ifPresent(govId -> payload.put("masterGovUnitId", govId));
      return ToolSupport.json(payload);
    }

    /** {@code sd.PutInfo} 的 {@code value}（JSON <b>字符串</b>；字段序固定，人可读审计）。 */
    String infoValueJson(String reason) {
      requireNonBlank(reason, "reason");
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("armyId", armyId);
      value.put("rootUnit", rootUnit);
      value.put("newMasterGov", newMasterGov.orElse(null));
      value.put("oldMasterGov", oldMasterGov.orElse(null));
      value.put("syncUnitSide", syncUnitSide);
      value.put("role", role.orElse(null));
      value.put("reason", reason);
      return ToolSupport.json(value);
    }

    /** 人可读行动摘要（工具结果与 {@code sd.PutInfo.note} 共用）。 */
    String infoNote(String reason) {
      requireNonBlank(reason, "reason");
      return "改派 Army "
          + armyId
          + " 的认领 GOV："
          + oldMasterGov.orElse("(未认领)")
          + " -> "
          + newMasterGov.orElse("(解除认领)")
          + "；root="
          + rootUnit
          + "，syncUnitSide="
          + syncUnitSide
          + (syncUnitSide ? "，role=" + role.orElse("") : "（不改 unit 侧）")
          + "，tick="
          + tick
          + "；reason="
          + reason;
    }
  }
}
