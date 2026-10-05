package io.mosire.simos.unit;

import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Optional;

/**
 * ★★ <b>Army 维护的军官/军职家户配置</b>（S3b，2026-10-09 用户裁定）——以 {@link HouseholdId} 为键的具名状态，挂在 {@link
 * ArmyFormation} 上。
 *
 * <pre>
 * MilitaryDutyOfHousehold(householdId, kind, appointment, commandOf?)
 * </pre>
 *
 * <p>★ <b>为什么必须有具名类型</b>（用户裁定原文）：军官团、各级政府领导层这种人少但配置特殊的主体，可以单独建小家户；基层军官可以不单独立户，而 在一个大家户上挂 Army
 * 维护的军官配置。这些配置以 {@code HouseholdId} 为键；只要参与战斗、动员、供给、编制人数等计算，就必须是具名状态类型， 不能拿 {@code
 * InfoSystem}/{@code stateDescriptions} 当规则容器（后者只放描述性、叙事性内容）。
 *
 * <p>★★ <b>它不是第二本人头账</b>：{@code kind}/{@code appointment} 是"这个家户在军中是什么身份"的规则配置；人口真值仍然是 Social
 * 家户的成员批次（{@code PopulationLookup.householdPopulation}）。本类型不含任何人数、战力或效率数值——战力公式归 {@code
 * simos-army}，编制人数从家户成员现算。
 *
 * <p>★ <b>不变量</b>：{@code householdId}/{@code kind}/{@code appointment} 非 null（{@code appointment}
 * 非空白）； {@code commandOf} 的 Optional 本身非 null。{@code commandOf} 非空时它必须是一个存在的 Unit（由 {@link
 * UnitOperations} 在 设置编制时校验），但本类型自身不依赖 {@code UnitState}（只守自己一亩地）。
 *
 * @param householdId 该配置所属的家户（稳定身份；不得为 null）
 * @param kind 军职类别（不得为 null）
 * @param appointment 任职/岗位短名（非空白；词表后置）
 * @param commandOf 该家户主官统率的单位（非主官用 {@code Optional.empty()}；Optional 本身非 null）
 */
public record MilitaryDutyOfHousehold(
    HouseholdId householdId,
    MilitaryDutyKind kind,
    String appointment,
    Optional<UnitId> commandOf) {

  public MilitaryDutyOfHousehold {
    if (householdId == null) {
      throw new IllegalArgumentException("MilitaryDutyOfHousehold.householdId 不得为 null");
    }
    if (kind == null) {
      throw new IllegalArgumentException("MilitaryDutyOfHousehold.kind 不得为 null");
    }
    if (appointment == null || appointment.isBlank()) {
      throw new IllegalArgumentException("MilitaryDutyOfHousehold.appointment 不得为空白");
    }
    if (commandOf == null) {
      throw new IllegalArgumentException(
          "MilitaryDutyOfHousehold.commandOf 不得为 null（非主官用 Optional.empty()）");
    }
  }

  /** 军官家户（可单独立户）——一期只用到它 {@code true} 的那一档。 */
  public boolean officerHousehold() {
    return kind == MilitaryDutyKind.OFFICER || kind == MilitaryDutyKind.COMMANDER;
  }
}
