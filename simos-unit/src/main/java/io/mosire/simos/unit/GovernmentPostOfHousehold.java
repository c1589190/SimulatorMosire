package io.mosire.simos.unit;

import io.mosire.simos.social.api.id.HouseholdId;

/**
 * ★★ <b>GOV 维护的领导层/官吏家户岗位配置</b>（S3b，2026-10-09 用户裁定；Z4 加 {@code tierId}）——以 {@link HouseholdId}
 * 为键的具名状态，挂在 {@link GovernmentFormation} 上。
 *
 * <pre>
 * GovernmentPostOfHousehold(householdId, role, level, headOfGovernment, tierId)
 * </pre>
 *
 * <p>★ <b>为什么必须有具名类型</b>：各级政府领导层是"人少但配置特殊"的主体，可以单独建小家户，再挂 GOV 适用的特殊配置；只要参与编制人数、供给、
 * 行政计算，就必须是具名状态类型——{@code Info}/{@code stateDescriptions} 只放描述性、叙事性内容。
 *
 * <p>★★ <b>与 {@code GovernmentFormation.staff} 的关系（C4 一处真相，Z4）</b>：{@code staff} 是旧口径的在编人数兼容字段；一旦某
 * GOV 单位的 {@code governmentPostsOfHousehold} 非空，{@code staff} 就只是这些家户人口/承诺的投影（由 app
 * 组合根现算校核），不得再被任何写口当作第二本权威（见 {@code UnitOperations#setGovernmentFormation} 的具名拒与 {@code
 * GovernmentFormation#projectedStaff}）。 本类型本身不存人数——人数从 Social 家户成员/承诺现算。
 *
 * <p>★★ <b>{@code tierId}（Z4 冻结）</b>：指向 {@code GovAdministrationPlan.postTiers()} 里的档位 id（3
 * 档普通基层目录， 每档两维权重住在计划表里，<b>不复制进本类型</b>）。空串 = legacy/未指派档位（旧档缺字段读回同一语义）； 非空时由写口/工具按计划目录校验（unit 模块看不见
 * gov 计划，跨切片校验在 app/gov 侧）。
 *
 * <p>★ <b>不变量</b>：{@code householdId}/{@code role}/{@code level} 非 null；{@code headOfGovernment}
 * 是普通布尔；{@code tierId} 缺省 null ⇒ 归一为空串（legacy）。
 *
 * @param householdId 该岗位配置所属的家户（稳定身份；不得为 null）
 * @param role 行政编制角色（复用 {@link StaffRole} 的三类词表；不得为 null）
 * @param level 层级（中央/省；不得为 null）
 * @param headOfGovernment 是否政府首长（一 GOV 至多一个由 GOV 侧校验；本类型只守字段合法）
 * @param tierId 岗位档位 id（指向 {@code GovAdministrationPlan.postTiers}；空串 = legacy/未指派；不得为 null，null ⇒
 *     空串）
 */
public record GovernmentPostOfHousehold(
    HouseholdId householdId,
    StaffRole role,
    GovernmentLevel level,
    boolean headOfGovernment,
    String tierId) {

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
    // ★ Z4 旧档兼容：旧 JSON 缺 tierId ⇒ null；旧 Java 构造器传 null ⇒ 同一 legacy 语义（空串）。
    tierId = tierId == null ? "" : tierId;
  }

  /** 旧 4 参构造器（旧档/旧调用点兼容）：{@code tierId} 缺省空串 = legacy/未指派档位。 */
  public GovernmentPostOfHousehold(
      HouseholdId householdId, StaffRole role, GovernmentLevel level, boolean headOfGovernment) {
    this(householdId, role, level, headOfGovernment, "");
  }

  /** 是否已指派到档位（{@code tierId} 非空）；空 = legacy/未指派。 */
  public boolean hasTier() {
    return !tierId.isEmpty();
  }
}
