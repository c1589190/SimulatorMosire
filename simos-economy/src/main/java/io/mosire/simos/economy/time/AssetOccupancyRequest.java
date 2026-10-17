package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.model.AssetOccupancyGrantId;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.production.contract.ProductionLocation;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * ★★ <b>D-032 D4 统一资产预算请求</b>（D4 §5.1/§5.2）：一个生产实例在目标时段对逐资产种类的需求量。
 *
 * <p>★★ <b>请求量不得从资产可用量反推</b>。{@code requestedQuantity} 是调用方在进入 D4 分配前由非资产通道派生出的显式请求 （{@code
 * requestedScale × capacityPerUnit[k]}）；这里保留 {@code requestedScale} 作为同源证据，但绝不根据它猜容量。
 *
 * <p>★ <b>缺省边界（R-10）</b>：{@code requestedQuantity} 为空表示"调用方没有给请求表"。它只在"scope 内只有已存在单实例"的兼容路径里由
 * {@link AssetOccupancyBook} 解释为整池保留；候选/新实例或多实例必须显式给请求，否则具名 {@code
 * asset-occupancy-request-missing:<unit>}。没有"默认 1"的隐式生成。
 *
 * @param unitId 生产实例
 * @param industry 方案/产业身份
 * @param operator 经营者
 * @param location 生产地点（规范 {@code <q>_<r>}）
 * @param fromTick 区间起点（含）
 * @param toTickExclusive 区间终点（不含）
 * @param requestedQuantity 逐资产请求量；只保留正值，可为空表（= 未给请求）
 * @param requestedScale 调用方给定的非资产计划规模；empty = 调用方未声明（不是 1）
 * @param previousGrantId 续占证据；无上一段为 empty
 */
public record AssetOccupancyRequest(
    ProductionUnitId unitId,
    IndustryId industry,
    ActorRef operator,
    ProductionLocation location,
    long fromTick,
    long toTickExclusive,
    Map<AssetKind, Long> requestedQuantity,
    OptionalLong requestedScale,
    Optional<AssetOccupancyGrantId> previousGrantId) {

  public AssetOccupancyRequest {
    Objects.requireNonNull(unitId, "AssetOccupancyRequest.unitId 不得为 null");
    Objects.requireNonNull(industry, "AssetOccupancyRequest.industry 不得为 null");
    Objects.requireNonNull(operator, "AssetOccupancyRequest.operator 不得为 null");
    Objects.requireNonNull(location, "AssetOccupancyRequest.location 不得为 null");
    OwnershipStake.requireSpecifiedLocation(location);
    if (fromTick < 0L) {
      throw new IllegalArgumentException("AssetOccupancyRequest.fromTick 不得为负: " + fromTick);
    }
    if (fromTick >= toTickExclusive) {
      throw new IllegalArgumentException(
          "AssetOccupancyRequest 区间必须满足 fromTick < toTickExclusive: "
              + fromTick
              + " >= "
              + toTickExclusive);
    }
    Objects.requireNonNull(
        requestedQuantity, "AssetOccupancyRequest.requestedQuantity 不得为 null（无请求用空表）");
    LinkedHashMap<AssetKind, Long> quantities = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Long> entry : requestedQuantity.entrySet()) {
      AssetKind kind = entry.getKey();
      Long quantity = entry.getValue();
      if (kind == null || quantity == null) {
        throw new IllegalArgumentException(
            "AssetOccupancyRequest.requestedQuantity 的键与值都不得为 null: " + kind + " = " + quantity);
      }
      if (quantity <= 0L) {
        throw new IllegalArgumentException(
            "AssetOccupancyRequest.requestedQuantity 只允许正值: " + kind + " = " + quantity);
      }
      quantities.put(kind, quantity);
    }
    requestedQuantity = Collections.unmodifiableMap(quantities);
    Objects.requireNonNull(
        requestedScale, "AssetOccupancyRequest.requestedScale 不得为 null（未声明用 empty）");
    if (requestedScale.isPresent() && requestedScale.getAsLong() < 0L) {
      throw new IllegalArgumentException(
          "AssetOccupancyRequest.requestedScale 不得为负: " + requestedScale.getAsLong());
    }
    if (requestedScale.isPresent()
        && requestedScale.getAsLong() == 0L
        && !requestedQuantity.isEmpty()) {
      throw new IllegalArgumentException(
          "AssetOccupancyRequest.requestedScale=0 时不得同时给非空 requestedQuantity: "
              + requestedQuantity);
    }
    Objects.requireNonNull(
        previousGrantId, "AssetOccupancyRequest.previousGrantId 不得为 null（无上一段用 empty）");
  }

  /**
   * ★ D4 §5.2 的唯一请求量派生：{@code requestedQuantity[k] = requestedScale × capacityPerUnit[k]}。
   *
   * <p>调用方把<b>非资产通道</b>算出的 scale 与方案容量传进来；本方法不读资产供给反推规模。0/负容量项不进入请求表； 乘溢出具名 {@code
   * asset-occupancy-quantity-overflow}。返回表按 {@link AssetKind#name()} 升序冻结。
   */
  public static Map<AssetKind, Long> quantitiesForScale(
      Map<AssetKind, Long> capacityPerUnit, long requestedScale) {
    Objects.requireNonNull(capacityPerUnit, "capacityPerUnit 不得为 null（无容量用空表）");
    if (requestedScale < 0L) {
      throw new IllegalArgumentException(
          "asset-occupancy-request-invalid-scale: requestedScale 不得为负: " + requestedScale);
    }
    LinkedHashMap<AssetKind, Long> sorted = new LinkedHashMap<>();
    capacityPerUnit.entrySet().stream()
        .sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(AssetKind::name)))
        .forEach(
            entry -> {
              AssetKind kind = entry.getKey();
              Long capacity = entry.getValue();
              if (kind == null || capacity == null) {
                throw new IllegalArgumentException(
                    "capacityPerUnit 的键与值都不得为 null: " + kind + " = " + capacity);
              }
              if (capacity < 0L) {
                throw new IllegalArgumentException(
                    "asset-occupancy-quantity-invalid: capacityPerUnit 不得为负: "
                        + kind.name()
                        + " = "
                        + capacity);
              }
              if (capacity == 0L) {
                return;
              }
              long quantity;
              try {
                quantity = Math.multiplyExact(requestedScale, capacity);
              } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException(
                    "asset-occupancy-quantity-overflow: "
                        + requestedScale
                        + " × "
                        + capacity
                        + " kind="
                        + kind.name(),
                    overflow);
              }
              if (quantity > 0L) {
                sorted.put(kind, quantity);
              }
            });
    return Collections.unmodifiableMap(sorted);
  }

  /**
   * 由非资产通道的计划规模构造请求：调用方给 {@code capacityPerUnit} 与 {@code requestedScale}，请求表由 {@link
   * #quantitiesForScale(Map, long)} 唯一派生。
   */
  public static AssetOccupancyRequest ofScale(
      ProductionUnitId unitId,
      IndustryId industry,
      ActorRef operator,
      ProductionLocation location,
      long fromTick,
      long toTickExclusive,
      Map<AssetKind, Long> capacityPerUnit,
      long requestedScale,
      Optional<AssetOccupancyGrantId> previousGrantId) {
    return new AssetOccupancyRequest(
        unitId,
        industry,
        operator,
        location,
        fromTick,
        toTickExclusive,
        quantitiesForScale(capacityPerUnit, requestedScale),
        OptionalLong.of(requestedScale),
        previousGrantId);
  }

  /** 该资产种类是否有显式请求量。 */
  public boolean hasExplicitQuantity(AssetKind kind) {
    return requestedQuantity.containsKey(kind);
  }

  /** 是否"完全未给请求表"（scale 与逐 kind 数量都缺；用于 R-10 单实例兼容例外，不是默认 1）。 */
  public boolean hasNoRequestTable() {
    return requestedQuantity.isEmpty() && requestedScale.isEmpty();
  }

  /** 是否显式声明本 tick 不请求资产（{@code requestedScale=0} 且无逐 kind 数量）。 */
  public boolean hasExplicitZeroScale() {
    return requestedQuantity.isEmpty()
        && requestedScale.isPresent()
        && requestedScale.getAsLong() == 0L;
  }

  /** 显式声明了正规模但请求表为空 ⇒ 请求量缺失。 */
  public boolean declaresPositiveScaleWithoutQuantities() {
    return requestedQuantity.isEmpty()
        && requestedScale.isPresent()
        && requestedScale.getAsLong() > 0L;
  }

  /** 该请求在给定 scope 下的请求量；未列出该种类 ⇒ 0。 */
  public long requestedQuantityOf(AssetKind kind) {
    return requestedQuantity.getOrDefault(kind, 0L);
  }
}
