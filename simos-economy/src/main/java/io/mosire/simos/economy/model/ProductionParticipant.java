package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * ★★★ <b>名册里的一个参与项</b>（D-032 D2 §5.2）：某家户在某个生产角色上的已确认周期承诺。
 *
 * <p>★ <b>它是什么 / 不是什么</b>：
 *
 * <ul>
 *   <li>{@code plannedLaborMilliPerDay} 是<b>预计所得的输入</b>，不是实际劳动权威；实际劳动只看 economy 的 {@code
 *       HouseholdLaborCommitment} / production 的回执。
 *   <li>{@code rightsStakes} 只存 {@link AssetShareId} 引用，<b>不复制 quantity</b>；权威数量仍从 {@code
 *       EconomyData.assetShares} 当刻读取。
 *   <li>{@code configuredWeight} 只在角色规则为 {@code CONFIGURED_WEIGHTS} 时被读取；角色定义是否允许非零由 {@code
 *       EconomyReferenceValidation} 判（本类型看不见 production 定义）。
 * </ul>
 *
 * <p>★★ <b>量纲（D2 工程裁定）</b>：{@code plannedLaborMilliPerDay} = <b>毫小时/日</b>（1 小时 = 1000 毫小时），与 {@code
 * HouseholdEconomy.laborMilli} / {@code HouseholdLaborCommitment.laborMilli} 同量纲。
 *
 * <p>★ <b>构造期只做不依赖 production 定义的校验</b>（null / 非负 / 份额去重 / 冻结）。角色存在性、份额 owner、资格、权重规则等跨切片校验在 {@code
 * EconomyReferenceValidation#validateProductionRosters}。
 *
 * @param household 参与家户；不得为 null
 * @param role 方案内角色；不得为 null
 * @param organizer 是否整份名册的组织者（整份名册恰好一个 true，由 {@link ProductionRoster} 判）
 * @param plannedLaborMilliPerDay 计划劳动（毫小时/日）；必须 ≥ 0
 * @param rightsStakes {@code BY_RIGHTS} 引用的份额 id；不得为 null、不得重复
 * @param configuredWeight {@code CONFIGURED_WEIGHTS} 的显式权重；必须 ≥ 0
 */
public record ProductionParticipant(
    HouseholdId household,
    ProductionRoleId role,
    boolean organizer,
    long plannedLaborMilliPerDay,
    List<AssetShareId> rightsStakes,
    long configuredWeight) {

  public ProductionParticipant {
    Objects.requireNonNull(household, "ProductionParticipant.household 不得为 null");
    Objects.requireNonNull(role, "ProductionParticipant.role 不得为 null");
    if (plannedLaborMilliPerDay < 0L) {
      throw new IllegalArgumentException(
          "ProductionParticipant.plannedLaborMilliPerDay 不得为负（毫小时/日）: " + plannedLaborMilliPerDay);
    }
    if (configuredWeight < 0L) {
      throw new IllegalArgumentException(
          "ProductionParticipant.configuredWeight 不得为负: " + configuredWeight);
    }
    if (rightsStakes == null) {
      throw new IllegalArgumentException("ProductionParticipant.rightsStakes 不得为 null（无引用给空表）");
    }
    List<AssetShareId> stakeCopy = new ArrayList<>(rightsStakes.size());
    Set<AssetShareId> seen = new LinkedHashSet<>();
    for (AssetShareId stake : rightsStakes) {
      if (stake == null) {
        throw new IllegalArgumentException("ProductionParticipant.rightsStakes 不得含 null");
      }
      if (!seen.add(stake)) {
        throw new IllegalArgumentException(
            "ProductionParticipant.rightsStakes 不得重复引用同一份额: " + stake.value());
      }
      stakeCopy.add(stake);
    }
    rightsStakes = Collections.unmodifiableList(stakeCopy); // ★ 冻在赋值处
  }

  /** 便捷构造：无 rights、无权重（最常见的 PRODUCT_SHARE / labor 型参与项）。 */
  public static ProductionParticipant of(
      HouseholdId household,
      ProductionRoleId role,
      boolean organizer,
      long plannedLaborMilliPerDay) {
    return new ProductionParticipant(
        household, role, organizer, plannedLaborMilliPerDay, List.of(), 0L);
  }
}
