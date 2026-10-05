package io.mosire.simos.unit;

import io.mosire.simos.social.api.id.HouseholdId;

/**
 * ★★ <b>GOV 维护的领导层家户配置</b>（S3b，2026-10-09 用户裁定）——以 {@link HouseholdId} 为键的具名状态，挂在 {@link
 * GovernmentFormation} 上。
 *
 * <pre>
 * GovernmentPostOfHousehold(householdId, role, level, headOfGovernment)
 * </pre>
 *
 * <p>★ <b>为什么必须有具名类型</b>：各级政府领导层是"人少但配置特殊"的主体，可以单独建小家户，再挂 GOV 适用的特殊配置；只要参与编制人数、供给、
 * 行政计算，就必须是具名状态类型——{@code Info}/{@code stateDescriptions} 只放描述性、叙事性内容。
 *
 * <p>★★ <b>与 {@code GovernmentFormation.staff} 的关系（兼容期）</b>：{@code staff} 是旧口径的在编人数，本阶段保留为兼容字段；一旦某 GOV 单位的
 * {@code governmentPostsOfHousehold} 非空，{@code staff} 就**只是这些家户人口的投影**（app 组合根按家户人口现算校核），不得再被任何写口当作第二本权威。
 * 本类型本身不存人数——人数从 Social 家户成员现算。
 *
 * <p>★ <b>不变量</b>：{@code householdId}/{@code role}/{@code level} 非 null；{@code headOfGovernment}
 * 是普通布尔。
 *
 * @param householdId 该领导配置所属的家户（稳定身份；不得为 null）
 * @param role 行政编制角色（复用 {@link StaffRole} 的三类词表；不得为 null）
 * @param level 层级（中央/省；不得为 null）
 * @param headOfGovernment 是否政府首长（一 GOV 至多一个由 GOV 侧校验；本类型只守字段合法）
 */
public record GovernmentPostOfHousehold(
    HouseholdId householdId, StaffRole role, GovernmentLevel level, boolean headOfGovernment) {

  public GovernmentPostOfHousehold {
    if (householdId == null) {
      throw new IllegalArgumentException("GovernmentPostOfHousehold.householdId 不得为 null");
    }
    if (role == null) {
      throw new IllegalArgumentException("GovernmentPostOfHousehold.role 不得为 null");
    }
    if (level == null) {
      throw new IllegalArgumentException("GovernmentPostOfHousehold.level 不得为 null");
    }
  }
}
