package io.mosire.simos.production.contract;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>① 试算：请求</b>（D-032 约束设计书 §4.2「试算(方案ID, 家户条件, 外部属性事实)」）。
 *
 * <p>★★ <b>三个"输入"入参</b>：方案、家户条件、外部属性事实。★ <b>价格与需求不在其中</b>（§4.2 原文「价格/需求不进 ①：production
 * 不靠市场价格/需求决定物理产量；估值、排序、分配是 economy 的事」）。 把价格塞进来会让"物理产量"变成价格的函数，而 D-032 的分工是：production 说"能产多少"，
 * economy 说"值不值得产"。
 *
 * <p>★★ <b>A 批新增 {@link #assetOccupancy()} 的理由是"预计与实际同一个入口"</b>（I6；复核裁决 §3
 * 「试算与实际、家户利润比较都读<b>同一份配置与同一入口</b>」）：三通道基准规模里的<b>资产通道</b>要拿
 * "这一格、这个经营者为这个方案实占的生产资料"算规模，而它<b>不在</b>家户条件里（家户条件只说"这家户手上 有多少"）。⇒
 * 试算必须能拿到同一份占用读数，否则"预计"与"实际"会走两条不同的算式（那正是复核裁决 §3 拒绝的）。★ A′ 批起资产通道<b>不</b>乘效率倍率（倍率只作用在三通道取 min
 * 之后一次），故这份占用读数 直接就是"投入侧"的那一路约束。 ★ 它仍<b>不是</b> economy 的状态对象：只是一个不可变值表，由调用方按统一分配结果填。
 *
 * <p>★★ <b>无副作用是硬约束</b>（I5）：本请求的求值<b>不得</b>改任何切片、不得写 revision、不得推进时间、 不得涨经验。等价地：同一份 {@link
 * #household()} + 同一份 {@link #assetOccupancy()} + 同一份 {@link #attributes()} + 同一份世界状态 ⇒ 同一个 {@link
 * ProductionProbeResult}（V1/V2）。
 *
 * @param plan 方案稳定 ID；不得为 null
 * @param household 家户条件；不得为 null
 * @param assetOccupancy ★ 该实例/该经营者为此方案实占的生产资料（按种类）；不得为 null（无占用用空表）、 逐值必须为正（0 不进表）
 * @param durableCommodityOccupancy ★★ <b>D-032 C5a</b>：该实例/该经营者为此方案实占的<b>耐久商品资本</b> （商品 id → 数量，如
 *     {@code transport_horse}）；不得为 null（无此通道用空表）、逐值必须为正（0 不进表）。 ★ 它只作规模锚、<b>不</b>被扣减；不得从参与户
 *     conditions 聚合（必须由 economy 按 operator/owner 户同一份库存填）。
 * @param carry ★ 该实例当前周期已结转的精度余数（{@link ProductionEfficiencyState}）；不得为 null （没有实例/没有余数给 {@link
 *     Optional#empty()}）。★ 试算与执行必须读<b>同一份</b>余数， 否则"预计"与"实际"会在跨周期边界上分叉（复核裁决 §3「同一入口」）
 * @param attributes 外部属性事实；不得为 null（没有属性用 {@link ExternalAttributeFacts#EMPTY}）
 */
public record ProductionProbeRequest(
    IndustryId plan,
    HouseholdProductionConditions household,
    Map<AssetKind, Long> assetOccupancy,
    Map<CommodityId, Long> durableCommodityOccupancy,
    Optional<ProductionEfficiencyState> carry,
    ExternalAttributeFacts attributes) {

  /** ★ 三入参形态（无资产占用、无耐久商品占用、无余数读数）——等价于两张占用表为空 + 余数缺行。 */
  public ProductionProbeRequest(
      IndustryId plan, HouseholdProductionConditions household, ExternalAttributeFacts attributes) {
    this(plan, household, Map.of(), Map.of(), Optional.empty(), attributes);
  }

  /** ★ 四入参形态（有余数、无占用读数）。 */
  public ProductionProbeRequest(
      IndustryId plan,
      HouseholdProductionConditions household,
      ProductionEfficiencyState carry,
      ExternalAttributeFacts attributes) {
    this(plan, household, Map.of(), Map.of(), Optional.ofNullable(carry), attributes);
  }

  /**
   * ★ <b>C5a 之前的 5 参形状</b>（含资产占用、无耐久商品占用）：等价于 {@code durableCommodityOccupancy =
   * Map.of()}；旧调用点逐值不变。
   */
  public ProductionProbeRequest(
      IndustryId plan,
      HouseholdProductionConditions household,
      Map<AssetKind, Long> assetOccupancy,
      Optional<ProductionEfficiencyState> carry,
      ExternalAttributeFacts attributes) {
    this(plan, household, assetOccupancy, Map.of(), carry, attributes);
  }

  public ProductionProbeRequest {
    Objects.requireNonNull(plan, "ProductionProbeRequest.plan 不得为 null");
    Objects.requireNonNull(household, "ProductionProbeRequest.household 不得为 null");
    Objects.requireNonNull(
        attributes,
        "ProductionProbeRequest.attributes 不得为 null（无属性用 ExternalAttributeFacts.EMPTY）");
    if (carry == null) {
      throw new IllegalArgumentException(
          "ProductionProbeRequest.carry 不得为 null（无余数请给 Optional.empty()）");
    }
    if (assetOccupancy == null) {
      throw new IllegalArgumentException("ProductionProbeRequest.assetOccupancy 不得为 null（无占用用空表）");
    }
    Map<AssetKind, Long> occupancyCopy = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Long> entry : assetOccupancy.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("ProductionProbeRequest.assetOccupancy 的键与值都不得为 null");
      }
      if (entry.getValue() <= 0L) {
        throw new IllegalArgumentException(
            "ProductionProbeRequest.assetOccupancy 必须为正（0 不进表）: "
                + entry.getKey()
                + " = "
                + entry.getValue());
      }
      occupancyCopy.put(entry.getKey(), entry.getValue());
    }
    assetOccupancy = Collections.unmodifiableMap(occupancyCopy); // ★ 冻在赋值处
    if (durableCommodityOccupancy == null) {
      throw new IllegalArgumentException(
          "ProductionProbeRequest.durableCommodityOccupancy 不得为 null（无占用用空表）");
    }
    Map<CommodityId, Long> durableCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : durableCommodityOccupancy.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionProbeRequest.durableCommodityOccupancy 的键与值都不得为 null");
      }
      if (entry.getValue() <= 0L) {
        throw new IllegalArgumentException(
            "ProductionProbeRequest.durableCommodityOccupancy 必须为正（0 不进表）: "
                + entry.getKey()
                + " = "
                + entry.getValue());
      }
      durableCopy.put(entry.getKey(), entry.getValue());
    }
    durableCommodityOccupancy = Collections.unmodifiableMap(durableCopy); // ★ 冻在赋值处
  }
}
