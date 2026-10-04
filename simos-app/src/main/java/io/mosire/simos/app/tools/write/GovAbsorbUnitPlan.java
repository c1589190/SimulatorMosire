package io.mosire.simos.app.tools.write;

import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.unit.CompositionDelta;
import io.mosire.simos.unit.GovFormation;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ {@code simos.gov.absorbUnit} 的<b>纯推导</b>（阶段 13A 人员流转，GOV/Army 计划 §2.6）： 把一个人口单位的成员吸收进 GOV
 * 编制（可顺带解散已空的源单位）+ 留行动记录，一批落一条 revision——<b>不碰</b> {@link
 * io.mosire.agentlib.tool.ToolContext}/{@code CoreSimos}。
 *
 * <p>★★ <b>批顺序（固定，可复现）</b>：{@code unit.ApplyCasualties}（源单位 {@code manpower=[{type,−taken}]}、
 * {@code equipment=[]}）→ {@code unit.RecruitStaff}（{@code sources=[{kind:"unit", id,
 * count}]}）→（{@code disbandSource} 且吸收后源人力合计==0）{@code unit.DisbandUnit} → {@code sd.PutInfo}（地址 =
 * GOV canonical， key={@code absorbUnit}）。
 *
 * <p>★★ <b>源单位口径（用户裁定 3/10）</b>：<b>默认拒</b>带 ArmyFormation 的源单位（军队单位不是人口容器），<b>除非 源是无 module
 * 的纯人员单位</b>；带 GovFormation 的源同样拒。⇒ 本计划要求 {@code source.module().isEmpty()}， 两类编制都给出各自的具名拒因。
 *
 * <p>★★ <b>守恒</b>：{@code 源人力合计前 − count == 源人力合计后} 且 {@code GOV roster[role] 前 + count == roster
 * 后}；源有多个 type 时按源表序逐条扣；Plan 构造期逐值互校。
 *
 * <p>★ <b>disbandSource 的条件语义</b>：只有“吸收后源人力合计==0”才落 {@code unit.DisbandUnit}；若源仍有剩余人员，
 * 本工具<b>不自动解散</b>（那会丢掉剩下的人），并在行动记录里具名说明 {@code disbandSkippedReason}。
 *
 * <p>★ <b>校验</b>：GOV 存在且带 {@link GovFormation}；源单位存在且人力合计 {@code ≥ count}；{@code count ≥ 1}； {@code
 * staffCap[role]} 超限 ⇒ 具名拒（带现有 / 上限 / 请求，不截断）。
 */
final class GovAbsorbUnitPlan {

  /** {@code unit.ApplyCasualties} 的命令类型（与 {@code ApplyCasualtiesHandler.type()} 同字面）。 */
  static final String APPLY_CASUALTIES_TYPE = "unit.ApplyCasualties";

  /** {@code unit.RecruitStaff} 的命令类型（与 {@code RecruitStaffHandler.type()} 同字面）。 */
  static final String RECRUIT_STAFF_TYPE = "unit.RecruitStaff";

  /** {@code unit.DisbandUnit} 的命令类型（仅 disbandSource 且源已空时才落）。 */
  static final String DISBAND_UNIT_TYPE = "unit.DisbandUnit";

  /** {@code sd.PutInfo} 的命令类型（恒有，行动记录）。 */
  static final String PUT_INFO_TYPE = "sd.PutInfo";

  private GovAbsorbUnitPlan() {}

  /**
   * 纯推导入口（见类注的校验与守恒口径）。
   *
   * @param state 读数所在的状态（preview / apply 都取<b>同一坐标</b>的状态）
   * @param govUnitId 吸收方 GOV 单位 id（必须带 {@link GovFormation}）
   * @param roleText 入编角色词表（SCRIBE|YAMEN|POST）
   * @param sourceUnitId 源人口单位 id（必须存在、且必须是无 module 的纯人员单位）
   * @param count 吸收人数（≥ 1，且不得超过源 member）
   * @param disbandSource 源被吸收后若已空，是否同批解散
   * @throws IllegalArgumentException 任一具名前置不满足（工具折 {@code BAD_REQUEST}）
   */
  static Plan plan(
      SimulationState state,
      String govUnitId,
      String roleText,
      String sourceUnitId,
      long count,
      boolean disbandSource) {
    Objects.requireNonNull(state, "state");
    requireNonBlank(govUnitId, "unitId");
    StaffRole role = parseRole(roleText);
    requireNonBlank(sourceUnitId, "sourceUnitId");
    if (count < 1L) {
      throw new IllegalArgumentException("吸收人数 count 必须 ≥ 1: " + count);
    }
    // ★ S3b（2026-10-09）：Unit.manpower 已退役 ⇒ "把源单位的人吸收成 GOV 编制"必须先落到 Social 家户
    //   （转移成员批次 + 改两侧 households），本工具尚未接线到那条写口。这里 fail-closed、具名拒，绝不把人员
    //   静默写回 unit 侧的第二本 headcount。接线点（下一步）：social.TransferHouseholdMembers 组合。
    throw new IllegalArgumentException(
        "GovAbsorbUnit 尚未接线到家户模型（S3b）：Unit.manpower 已退役，人员转移必须先走 Social 家户"
            + "（social.CreateHousehold / social.TransferHouseholdMembers + unit.SetUnitHouseholds，"
            + "或用 simos.unit.assignHousehold）；本工具暂只保留失败路径，不做第二本 headcount");
  }

  /** 角色词表：只认 SCRIBE|YAMEN|POST，别的词给具名拒（不静默当缺省）。 */
  private static StaffRole parseRole(String roleText) {
    if (roleText == null || roleText.isBlank()) {
      throw new IllegalArgumentException("role 必须是非空文本（SCRIBE|YAMEN|POST）");
    }
    try {
      return StaffRole.valueOf(roleText);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("role 不是合法角色（SCRIBE|YAMEN|POST）: " + roleText, e);
    }
  }

  /** 吸收方必须带 {@link GovFormation}（入编命令的领域前置）。 */
  private static GovFormation requireGovFormation(Unit unit, String unitId) {
    if (unit.module().orElse(null) instanceof GovFormation gov) {
      return gov;
    }
    throw new IllegalArgumentException(
        "单位 " + unitId + " 没有 GovFormation：吸收只对 GOV 编制单位；先 unit.SetGovFormation");
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " 必须是非空文本");
    }
  }

  /**
   * 一份吸收计划（全部字段是状态的纯函数）。
   *
   * @param govUnitId 吸收方 GOV
   * @param sourceUnitId 源纯人员单位
   * @param role 入编角色
   * @param count 吸收人数（= 源人力合计减量 = roster 增量）
   * @param sourceMemberBefore 源吸收前人力合计
   * @param sourceMemberAfter 源吸收后人力合计（= before − count）
   * @param manpowerDeltas 源人力表上的负增量（按源表序逐 type 扣，Σ(−amount) == count）
   * @param staffBefore 该角色吸收前在编
   * @param staffAfter 该角色吸收后在编（= before + count）
   * @param disbandSource 调用方是否请求“源空则解散”
   * @param disbandDispatched 批里是否真的落 unit.DisbandUnit（disbandSource 且源已空）
   * @param disbandSkippedReason 请求了但没落解散时的具名原因（其余 = empty）
   * @param tick 推导时的世界日
   */
  record Plan(
      String govUnitId,
      String sourceUnitId,
      StaffRole role,
      long count,
      long sourceMemberBefore,
      long sourceMemberAfter,
      List<CompositionDelta> manpowerDeltas,
      long staffBefore,
      long staffAfter,
      boolean disbandSource,
      boolean disbandDispatched,
      Optional<String> disbandSkippedReason,
      long tick) {

    Plan {
      requireNonBlank(govUnitId, "govUnitId");
      requireNonBlank(sourceUnitId, "sourceUnitId");
      Objects.requireNonNull(role, "role");
      if (count < 1L) {
        throw new IllegalArgumentException("count 必须 ≥ 1: " + count);
      }
      if (sourceMemberBefore < count || sourceMemberAfter != sourceMemberBefore - count) {
        throw new IllegalArgumentException(
            "守恒破坏：源人力 " + sourceMemberBefore + " − count " + count + " != " + sourceMemberAfter);
      }
      manpowerDeltas = List.copyOf(Objects.requireNonNull(manpowerDeltas, "manpowerDeltas"));
      long deltaTotal = 0L;
      for (CompositionDelta delta : manpowerDeltas) {
        if (delta.amount() >= 0L) {
          throw new IllegalArgumentException("吸收载荷的增量必须为负: " + delta.type() + "=" + delta.amount());
        }
        deltaTotal += -delta.amount();
      }
      if (deltaTotal != count) {
        throw new IllegalArgumentException(
            "守恒破坏：Σ源人力扣减 " + deltaTotal + " != count " + count + "（批载荷必须逐值对应）");
      }
      if (staffAfter != staffBefore + count) {
        throw new IllegalArgumentException(
            "守恒破坏：roster " + staffBefore + " + count " + count + " != " + staffAfter);
      }
      Objects.requireNonNull(disbandSkippedReason, "disbandSkippedReason");
      if (disbandDispatched != (disbandSource && sourceMemberAfter == 0L)) {
        throw new IllegalArgumentException(
            "内部分摊不自洽：disbandDispatched="
                + disbandDispatched
                + " 与 disbandSource="
                + disbandSource
                + "/sourceMemberAfter="
                + sourceMemberAfter
                + " 不一致");
      }
      if (disbandDispatched && disbandSkippedReason.isPresent()) {
        throw new IllegalArgumentException("内部分摊不自洽：已落解散却带跳过原因");
      }
      if (tick < 0L) {
        throw new IllegalArgumentException("tick 不得为负: " + tick);
      }
    }

    /** 本工具将落的命令类型（批内固定顺序；preview 视图与 apply 组批共用这一处）。 */
    List<String> commandTypes() {
      List<String> types = new ArrayList<>(4);
      types.add(APPLY_CASUALTIES_TYPE);
      types.add(RECRUIT_STAFF_TYPE);
      if (disbandDispatched) {
        types.add(DISBAND_UNIT_TYPE);
      }
      types.add(PUT_INFO_TYPE);
      return List.copyOf(types);
    }

    /** {@code unit.ApplyCasualties} 载荷：源单位按**人力表逐 type** 减员（D3a 的双轨 delta 有序表），装备不动（空数组）。 */
    String applyCasualtiesPayloadJson() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("id", sourceUnitId);
      payload.put("manpower", ToolSupport.compositionDeltaView(manpowerDeltas));
      payload.put("equipment", List.of());
      return ToolSupport.json(payload);
    }

    /** {@code unit.RecruitStaff} 载荷：source 逐来源写清“来自哪个单位、多少人”。 */
    String recruitStaffPayloadJson() {
      Map<String, Object> sourceRow = new LinkedHashMap<>();
      sourceRow.put("kind", "unit");
      sourceRow.put("id", sourceUnitId);
      sourceRow.put("count", count);
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("unitId", govUnitId);
      payload.put("role", role.name());
      payload.put("count", count);
      payload.put("sources", List.of(sourceRow));
      return ToolSupport.json(payload);
    }

    /** {@code unit.DisbandUnit} 载荷（{@link #disbandDispatched} 为真时才可调用）。 */
    String disbandUnitPayloadJson() {
      if (!disbandDispatched) {
        throw new IllegalStateException("批不自洽：不落解散却要组装 unit.DisbandUnit 载荷");
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("id", sourceUnitId);
      return ToolSupport.json(payload);
    }

    /** {@code sd.PutInfo} 的 {@code value}（JSON 字符串；含 source/role/count/前后 member 与 roster）。 */
    String infoValueJson(String reason) {
      requireNonBlank(reason, "reason");
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("govUnitId", govUnitId);
      value.put("sourceUnitId", sourceUnitId);
      value.put("role", role.name());
      value.put("count", count);
      value.put("sourceMemberBefore", sourceMemberBefore);
      value.put("sourceMemberAfter", sourceMemberAfter);
      value.put("staffBefore", staffBefore);
      value.put("staffAfter", staffAfter);
      value.put("disbandSource", disbandSource);
      value.put("disbanded", disbandDispatched);
      value.put("disbandSkippedReason", disbandSkippedReason.orElse(null));
      value.put("tick", tick);
      value.put("reason", reason);
      return ToolSupport.json(value);
    }

    /** 人可读行动摘要（工具结果与 {@code sd.PutInfo.note} 共用）。 */
    String infoNote(String reason) {
      requireNonBlank(reason, "reason");
      return "吸收 "
          + sourceUnitId
          + " → "
          + govUnitId
          + " 的 "
          + role
          + " "
          + count
          + " 人（tick "
          + tick
          + "）：源 member "
          + sourceMemberBefore
          + "→"
          + sourceMemberAfter
          + "，在编 "
          + staffBefore
          + "→"
          + staffAfter
          + (disbandDispatched
              ? "；源已空，同批 unit.DisbandUnit"
              : disbandSkippedReason.map(text -> "；未解散（" + text + "）").orElse("；未请求解散源"))
          + "；reason="
          + reason;
    }
  }
}
