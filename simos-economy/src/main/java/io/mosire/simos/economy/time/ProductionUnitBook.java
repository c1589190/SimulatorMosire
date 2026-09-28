package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.ProductionUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★★ <b>生产单元的派生读口</b>（R3B.2）：从 {@code AssetShare} 实物总账<b>纯派生</b>一个 unit 的可用资产与规模，不落第二份状态。
 *
 * <p>★★ <b>为什么必须是派生</b>：{@code AssetShare} 是唯一的实物总账；若 unit 或 {@code Industry} 再存一份 {@code
 * capacity}，两处就会漂开（旧代码正是这样：{@code EconomySettlement.capacityScaleOf} 读 {@code Industry.capacity}，而
 * {@code HouseholdClassRule} 另用 {@code AssetShare} 近似）。本类只做算术，不含公式常量。
 *
 * <pre>
 * usableAssets(unit)   = Σ AssetShare{industry == unit.industry && operator == unit.operator}.quantity  按 AssetKind 汇总
 * capacityScaleOf      = min over k ∈ industry.capacityPerUnit: ⌊usableAssets[k] ÷ capacityPerUnit[k]⌋
 * plannedCapacityScale = capacityScaleOf × StressPolicy.plannedScalePerMille(condition.status)
 * </pre>
 *
 * <p>★ <b>缺项/空表的口径与旧实现逐字一致</b>（迁移期判据）：缺某种资产 ⇒ 该路读到 0 ⇒ 规模 0（"这一格/这个主体没有这类资产"）； {@code
 * capacityPerUnit} 空表 ⇒ 没有"单位规模"的锚 ⇒ 返回 0（旧 {@code capacityScaleOf} 同值）。
 */
public final class ProductionUnitBook {

  private ProductionUnitBook() {}

  /**
   * ★★ <b>一个 unit 在某产业上的可用实物资产</b>：{@code Σ AssetShare{industry==unit.industry &&
   * operator==unit.operator}} 按 {@link AssetKind} 汇总。
   *
   * <p>★ <b>只按 operator 汇总</b>（不是 owner）：规模问的是"这个经营者实际能用多少"；所有权只决定收益归属（关系规则）。
   * 旧档一对一迁移（owner==operator）下两者同值，与旧 {@code Industry.capacity} 逐值相等。
   *
   * @param assetShares 实物总账（键序 = 状态插入序；返回表按份额首次出现序保序）
   * @return 按资产种类汇总的数量（只含出现过的种类；无份额 ⇒ 空表）
   */
  public static Map<AssetKind, Long> usableAssets(
      ProductionUnit unit, Map<AssetShareId, AssetShare> assetShares) {
    Map<AssetKind, Long> sums = new LinkedHashMap<>();
    for (AssetShare share : assetShares.values()) {
      if (!share.industry().equals(unit.industry()) || !share.operator().equals(unit.operator())) {
        continue;
      }
      sums.merge(share.asset(), share.quantity(), Math::addExact);
    }
    return sums;
  }

  /**
   * ★★ <b>unit 的产能规模</b> = {@code min over k ∈ capacityPerUnit: ⌊usableAssets[k] ÷
   * capacityPerUnit[k]⌋}。
   *
   * <p>★ 与旧 {@code EconomySettlement.capacityScaleOf(Industry)} 同式：缺资产 = 0，空表 = 0，整数向下取整。
   */
  public static long capacityScaleOf(
      ProductionUnit unit, Industry industry, Map<AssetShareId, AssetShare> assetShares) {
    Map<AssetKind, Long> usable = usableAssets(unit, assetShares);
    long scale = Long.MAX_VALUE;
    for (Map.Entry<AssetKind, Long> entry : industry.capacityPerUnit().entrySet()) {
      scale = Math.min(scale, usable.getOrDefault(entry.getKey(), 0L) / entry.getValue());
    }
    return scale == Long.MAX_VALUE ? 0L : scale;
  }

  /**
   * ★★ 计划规模 = 产能规模 × {@link StressPolicy#plannedScalePerMille}（条件缺失 ⇒ ACTIVE ⇒ 1000‰ ⇒ 旧行为逐值相同）。
   */
  public static long plannedCapacityScaleOf(
      ProductionUnit unit,
      Industry industry,
      Map<AssetShareId, AssetShare> assetShares,
      OperatorCondition condition) {
    return capacityScaleOf(unit, industry, assetShares)
        * StressPolicy.plannedScalePerMille(
            condition == null ? OperatorCondition.IndustryStatus.ACTIVE : condition.status())
        / 1_000L;
  }
}
