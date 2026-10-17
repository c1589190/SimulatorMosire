package io.mosire.simos.production.contract;

import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>生产方案内的角色定义</b>（D-032 D1 §2.2）。
 *
 * <p>角色是方案内位置，不是社会阶层：{@link #socialClass()} 只是期望对应的阶层元数据，不因此自动获利。 {@link #sharePerMille()}
 * 是直接产品份额，不以该户是否下地劳动为条件（用户 U15）。
 *
 * <p>★ 量纲：{@link #standardLaborMilliPerCycle()} 单位 = <b>毫小时/周期</b>（1 小时 = 1000 毫小时）， 0 =
 * 未定；不是“千分劳动”。
 *
 * <p>★ 不变量：id/name/payment 非 null；{@code sharePerMille ∈ [0,1000]}； {@code
 * standardLaborMilliPerCycle ≥ 0}；{@code PRODUCT_SHARE ⇒ sharePerMille > 0}； {@code MONEY_WAGE ⇒
 * sharePerMille == 0}；conditions 保序不可变且无 null。
 *
 * @param id 方案内稳定身份；非 null、非空白、不含 {@code '.'}
 * @param name 展示名；非空白
 * @param socialClass 期望对应的社会阶层（仅元数据/选择参考）；可缺
 * @param sharePerMille 角色产品份额（千分比）；0..1000
 * @param intraRoleSplit 同角色多户拆分规则
 * @param standardLaborMilliPerCycle 标准劳动量（毫小时/周期）；≥ 0，0 = 未定
 * @param experienceGrowth 经验增长配置；可缺 = 该角色不增长经验
 * @param conditions 该角色资格条件；可空 list、无 null、fail-closed
 * @param payment 支付形态；不得为 null
 */
public record ProductionRoleDefinition(
    ProductionRoleId id,
    String name,
    Optional<SocialClassId> socialClass,
    long sharePerMille,
    IntraRoleSplitRule intraRoleSplit,
    long standardLaborMilliPerCycle,
    Optional<ExperienceGrowth> experienceGrowth,
    List<ProductionAvailabilityCondition> conditions,
    RolePayment payment) {

  /** 缺省单户方案的“经营者”角色（D1 §2.8）。 */
  public static ProductionRoleDefinition defaultOperator() {
    return new ProductionRoleDefinition(
        ProductionRoleId.parse("operator"),
        "经营者",
        Optional.empty(),
        1000L,
        IntraRoleSplitRule.EQUAL,
        0L,
        Optional.empty(),
        List.of(),
        new RolePayment.ProductShare());
  }

  public ProductionRoleDefinition {
    Objects.requireNonNull(id, "ProductionRoleDefinition.id 不得为 null");
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("ProductionRoleDefinition.name 不得为空白");
    }
    Objects.requireNonNull(
        socialClass, "ProductionRoleDefinition.socialClass 不得为 null（可缺请给 Optional.empty()）");
    Objects.requireNonNull(intraRoleSplit, "ProductionRoleDefinition.intraRoleSplit 不得为 null");
    Objects.requireNonNull(
        experienceGrowth,
        "ProductionRoleDefinition.experienceGrowth 不得为 null（可缺请给 Optional.empty()）");
    Objects.requireNonNull(payment, "ProductionRoleDefinition.payment 不得为 null");
    if (sharePerMille < 0L || sharePerMille > 1000L) {
      throw new IllegalArgumentException(
          "ProductionRoleDefinition.sharePerMille 必须在 [0,1000]: " + sharePerMille);
    }
    if (standardLaborMilliPerCycle < 0L) {
      throw new IllegalArgumentException(
          "ProductionRoleDefinition.standardLaborMilliPerCycle 不得为负（毫小时/周期）: "
              + standardLaborMilliPerCycle);
    }
    if (payment instanceof RolePayment.ProductShare && sharePerMille <= 0L) {
      throw new IllegalArgumentException("PRODUCT_SHARE 角色必须 sharePerMille > 0: " + id.value());
    }
    if (payment instanceof RolePayment.MoneyWage && sharePerMille != 0L) {
      throw new IllegalArgumentException(
          "MONEY_WAGE 角色必须 sharePerMille = 0（工资不是产品份额）: " + id.value());
    }
    if (conditions == null) {
      throw new IllegalArgumentException(
          "ProductionRoleDefinition.conditions 不得为 null（无条件用空 list）");
    }
    List<ProductionAvailabilityCondition> conditionCopy = new ArrayList<>();
    for (ProductionAvailabilityCondition condition : conditions) {
      if (condition == null) {
        throw new IllegalArgumentException(
            "ProductionRoleDefinition.conditions 不得含 null: " + id.value());
      }
      conditionCopy.add(condition);
    }
    conditions = Collections.unmodifiableList(conditionCopy);
  }
}
