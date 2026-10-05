package io.mosire.simos.app.tools.write;

import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ {@code simos.gov.dispatchTeam} 的<b>纯推导</b>（阶段 13A 人员流转，GOV/Army 计划 §2.6）： 从 GOV
 * 编制里出人，同批建<b>无标签纯人员单位</b>（可加 ArmyFormation 变武装调查组）+ 留行动记录，一批落一条 revision——<b>不碰</b> {@link
 * io.mosire.agentlib.tool.ToolContext}/{@code CoreSimos}。
 *
 * <p>★★ <b>调查组 = 纯人员单位 +（可选）小军队编制</b>（用户裁定 3/10 的口径）：本计划只调 {@code unit.DismissStaff}（编制
 * −count，<b>不经退休付款逻辑</b>）+ {@code unit.CreateUnit}（untagged，speed=6、
 * mobilityPerMille=900、position=来源 GOV 当刻有效位置）；{@code armed=true} 时同批落 {@code
 * unit.SetArmyFormation}（masterGov=来源 GOV、role="armed-team"）——不造任何“调查组”新类型。
 *
 * <p>★★ <b>批顺序（固定，可复现）</b>：{@code unit.DismissStaff}（{@code {unitId, role, count}}，出人不付待遇） → {@code
 * unit.CreateUnit} →（{@code armed}）{@code unit.SetArmyFormation} → {@code sd.PutInfo}（地址 = 来源 GOV
 * canonical，key={@code dispatchTeam}，value 含 armed 标记）。
 *
 * <p>★★ <b>纯推导校验（前置不满足 ⇒ 工具折 {@code BAD_REQUEST}、零 revision）</b>：来源 GOV 存在且带 {@link
 * GovernmentFormation}；{@code count ≥ 1}（{@code unit.CreateUnit} 的 manpower amount 是 long，不再有 int 上限）；
 * {@code roster[role] ≥ count}（缺省 role = SCRIBE）否则具名拒（带现有/请求数字）；来源 GOV 当刻必须有有效 位置；{@code newUnitId}
 * 给了且已存在 ⇒ 具名拒（缺省确定性生成）。
 *
 * <p>★ <b>守恒</b>：{@code roster[role] − count == 出人后 roster}，且 {@code count == 新单位 manpower 的
 * amount}；Plan 构造期逐值互校。
 */
final class GovDispatchTeamPlan {

  /** {@code unit.DismissStaff} 的命令类型（与 {@code DismissStaffHandler.type()} 同字面）。 */
  static final String DISMISS_STAFF_TYPE = "unit.DismissStaff";

  /** {@code unit.CreateUnit} 的命令类型。 */
  static final String CREATE_UNIT_TYPE = "unit.CreateUnit";

  /** {@code unit.SetArmyFormation} 的命令类型（仅 armed=true 才落）。 */
  static final String SET_ARMY_FORMATION_TYPE = "unit.SetArmyFormation";

  /** {@code sd.PutInfo} 的命令类型（恒有，行动记录）。 */
  static final String PUT_INFO_TYPE = "sd.PutInfo";

  /** 新调查组单位的速度（控制方口径：纯人员单位、赶路快）。 */
  static final int NEW_UNIT_SPEED = 6;

  /** 新调查组单位的机动性（控制方口径 900‰）。 */
  static final int NEW_UNIT_MOBILITY_PER_MILLE = 900;

  /** 武装调查组的 ArmyFormation.role（控制方口径：通用接口，不造新类型）。 */
  static final String ARMED_TEAM_ROLE = "armed-team";

  /** {@code role} 缺省（缺省从 SCRIBE 出人）。 */
  static final StaffRole DEFAULT_ROLE = StaffRole.SCRIBE;

  private GovDispatchTeamPlan() {}

  /**
   * 纯推导入口（见类注的校验清单）。
   *
   * @param state 读数所在的状态（preview / apply 都取<b>同一坐标</b>的状态）
   * @param unitId 来源 GOV 单位 id（必须带 {@link GovernmentFormation}）
   * @param count 出人数量（≥ 1，且不得超过该角色现有在编与 int 上限）
   * @param roleText 出人角色（可选；缺省 {@link #DEFAULT_ROLE}，只认 SCRIBE|YAMEN|POST）
   * @param armed 是否同批加 ArmyFormation（武装调查组 = 通用接口的小军队编制）
   * @param newUnitId 新单位 id（可选；缺省确定性生成）
   * @throws IllegalArgumentException 任一具名前置不满足（工具折 {@code BAD_REQUEST}）
   */
  static Plan plan(
      SimulationState state,
      String unitId,
      long count,
      Optional<String> roleText,
      boolean armed,
      Optional<String> newUnitId) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(roleText, "roleText");
    Objects.requireNonNull(newUnitId, "newUnitId");
    requireNonBlank(unitId, "unitId");
    if (count < 1L) {
      throw new IllegalArgumentException("出人数量 count 必须 ≥ 1: " + count);
    }
    StaffRole role = roleText.map(GovDispatchTeamPlan::parseRole).orElse(DEFAULT_ROLE);
    UnitState units = ToolSupport.unitState(state);
    SimosTimestamp timestamp = state.meta().timestamp();
    long tick = timestamp.tick();
    Unit source = units.units().get(UnitId.parse(unitId));
    if (source == null) {
      throw new IllegalArgumentException("来源 GOV 单位不存在: " + unitId);
    }
    GovernmentFormation governmentFormation = requireGovernmentFormation(source, unitId);
    long staffBefore = governmentFormation.staff().getOrDefault(role, 0L);
    if (staffBefore < count) {
      throw new IllegalArgumentException(
          "出人 " + role + " " + count + " 人超过现有在编: 现有 " + staffBefore + " < 请求 " + count);
    }
    Optional<HexCoord> at = units.effectivePosition(source.id(), timestamp);
    if (at.isEmpty()) {
      throw new IllegalArgumentException("来源 GOV " + unitId + " 当刻没有有效位置：新单位落点无法确定；先 unit.PlaceAt");
    }
    String newId =
        GovSelectExamineesPlan.resolveNewUnitId(
            units, newUnitId, "team-" + unitId + "-" + tick + "-" + count, "newUnitId");
    return new Plan(
        unitId,
        newId,
        "调查组 " + unitId,
        at.get(),
        tick,
        count,
        role,
        staffBefore,
        staffBefore - count,
        armed);
  }

  /** 角色词表：只认 SCRIBE|YAMEN|POST，别的词给具名拒（不静默当缺省）。 */
  private static StaffRole parseRole(String roleText) {
    requireNonBlank(roleText, "role");
    try {
      return StaffRole.valueOf(roleText);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("role 不是合法角色（SCRIBE|YAMEN|POST）: " + roleText, e);
    }
  }

  /** 单位必须带 {@link GovernmentFormation}（本工具只从 GOV 编制出人）。 */
  private static GovernmentFormation requireGovernmentFormation(Unit unit, String unitId) {
    if (unit.module().orElse(null) instanceof GovernmentFormation governmentFormation) {
      return governmentFormation;
    }
    throw new IllegalArgumentException(
        "单位 " + unitId + " 没有 GovernmentFormation：本工具只从 GOV 编制出人；先 unit.SetGovFormation");
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " 必须是非空文本");
    }
  }

  /**
   * 一份调查组出人计划（全部字段是状态的纯函数）。
   *
   * @param unitId 来源 GOV 单位 id
   * @param newUnitId 新调查组单位 id（untagged；armed 时才有 ArmyFormation）
   * @param unitName 新单位名
   * @param at 新单位落点 = 来源 GOV 当刻有效位置
   * @param tick 推导时的世界日
   * @param count 出人数量（= roster 减量 = 新单位 manpower 单条的 amount；type=role.name()）
   * @param role 出人角色
   * @param staffBefore 该角色出人前在编
   * @param staffAfter 该角色出人后在编（= staffBefore − count）
   * @param armed 是否同批加 ArmyFormation
   */
  record Plan(
      String unitId,
      String newUnitId,
      String unitName,
      HexCoord at,
      long tick,
      long count,
      StaffRole role,
      long staffBefore,
      long staffAfter,
      boolean armed) {

    Plan {
      requireNonBlank(unitId, "unitId");
      requireNonBlank(newUnitId, "newUnitId");
      requireNonBlank(unitName, "unitName");
      Objects.requireNonNull(at, "at");
      if (tick < 0L) {
        throw new IllegalArgumentException("tick 不得为负: " + tick);
      }
      if (count < 1L || count > Integer.MAX_VALUE) {
        throw new IllegalArgumentException("count 必须在 [1, Integer.MAX_VALUE]: " + count);
      }
      Objects.requireNonNull(role, "role");
      if (staffBefore < count || staffAfter != staffBefore - count) {
        throw new IllegalArgumentException(
            "守恒破坏：staffBefore="
                + staffBefore
                + " − count="
                + count
                + " != staffAfter="
                + staffAfter);
      }
    }

    /** 本工具将落的命令类型（批内固定顺序；preview 视图与 apply 组批共用这一处）。 */
    List<String> commandTypes() {
      List<String> types = new ArrayList<>(4);
      types.add(DISMISS_STAFF_TYPE);
      types.add(CREATE_UNIT_TYPE);
      if (armed) {
        types.add(SET_ARMY_FORMATION_TYPE);
      }
      types.add(PUT_INFO_TYPE);
      return List.copyOf(types);
    }

    /** {@code unit.DismissStaff} 载荷：只减 roster、<b>不支付退休待遇、不回写社会</b>。 */
    String dismissStaffPayloadJson() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("unitId", unitId);
      payload.put("role", role.name());
      payload.put("count", count);
      return ToolSupport.json(payload);
    }

    /** {@code unit.CreateUnit} 载荷：untagged 纯人员单位（speed=6、mobilityPerMille=900）。 */
    String createUnitPayloadJson() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("id", newUnitId);
      payload.put("name", unitName);
      payload.put("position", ToolSupport.hexCoord(at));
      // ★ D3a：出人来自 GOV roster[role]，type 用角色的自然语义名（roles 是受控词表，天然是好的 type）。
      payload.put(
          "manpower",
          ToolSupport.compositionView(List.of(new CompositionEntry(role.name(), count))));
      payload.put("equipment", List.of());
      payload.put("speed", NEW_UNIT_SPEED);
      payload.put("mobilityPerMille", NEW_UNIT_MOBILITY_PER_MILLE);
      return ToolSupport.json(payload);
    }

    /** {@code unit.SetArmyFormation} 载荷（{@link #armed} 为真时才可调用）：认来源 GOV 当主子。 */
    String setArmyFormationPayloadJson() {
      if (!armed) {
        throw new IllegalStateException("批不自洽：armed=false 却要组装 unit.SetArmyFormation 载荷");
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("unitId", newUnitId);
      payload.put("masterGov", unitId);
      payload.put("role", ARMED_TEAM_ROLE);
      return ToolSupport.json(payload);
    }

    /** {@code sd.PutInfo} 的 {@code value}（JSON 字符串；含 role/count/armed/前后编制）。 */
    String infoValueJson(String reason) {
      requireNonBlank(reason, "reason");
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("govUnitId", unitId);
      value.put("newUnitId", newUnitId);
      value.put("role", role.name());
      value.put("count", count);
      value.put("armed", armed);
      value.put("tick", tick);
      value.put("at", ToolSupport.hexCoord(at));
      value.put("staffBefore", staffBefore);
      value.put("staffAfter", staffAfter);
      value.put("reason", reason);
      return ToolSupport.json(value);
    }

    /** 人可读行动摘要（工具结果与 {@code sd.PutInfo.note} 共用）。 */
    String infoNote(String reason) {
      requireNonBlank(reason, "reason");
      return "派出"
          + (armed ? "武装" : "")
          + "调查组 "
          + newUnitId
          + "（"
          + count
          + " 人，role="
          + role
          + "，tick "
          + tick
          + "）：来源 GOV "
          + unitId
          + " 编制 "
          + staffBefore
          + "→"
          + staffAfter
          + "；落点 ("
          + at.q()
          + ","
          + at.r()
          + ")"
          + (armed ? "，ArmyFormation role=" + ARMED_TEAM_ROLE : "，无 module")
          + "；reason="
          + reason;
    }
  }
}
