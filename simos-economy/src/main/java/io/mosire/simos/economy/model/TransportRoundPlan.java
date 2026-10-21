package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★★ <b>D-032 C7b1：一个市场轮的运输需求/lane 承诺/本地观察计划</b>。
 *
 * <p>本记录只活在 {@code SettlementDayContext.transportRoundPlan}：<b>瞬态，不落盘、不进
 * EconomyData/ChangeSet/Codec</b>。 C7b2 的商品 matcher 只读本计划，不得重新派生需求或复制 lane 分配算法：
 *
 * <pre>
 * plan.demands
 * plan.commitments
 * plan.observationsByShippingHex
 * plan.demandSupplyByZone
 * </pre>
 *
 * <p>所有 map/list 保持调用方给出的插入序，并在构造期复制为不可变包装；数量、容量、承诺与价格全部非负。 {@code demandSupplyByZone} 的值是 {@code
 * [D,S]} 两元素数组，构造/读取均做防御性拷贝，调用方改不到计划内部状态。
 *
 * @param marketRoundId 本计划结算的市场轮 id（绝对 tick）；不得为负
 * @param demands 需求表（保序不可变）；不得为 null
 * @param commitments 每条需求的 lane 承诺表（保序不可变）；不得为 null
 * @param observationsByShippingHex 逐发货 hex 观察（保序不可变）；不得为 null
 * @param demandSupplyByZone 区级 [D,S] 汇总（保序不可变；数组防御性拷贝）；不得为 null
 * @param totalDemandWorkMilli 全部需求运力之和；不得为负
 * @param totalOfferedWorkMilli 全部当轮本地批次容量之和；不得为负
 * @param totalCommittedWorkMilli 全部 lane 承诺之和；不得为负
 */
public record TransportRoundPlan(
    long marketRoundId,
    Map<TransportDemandKey, TransportDemand> demands,
    Map<TransportDemandKey, List<LaneCapacityCommitment>> commitments,
    Map<HexCoord, TransportHexObservation> observationsByShippingHex,
    Map<MarketZoneId, long[]> demandSupplyByZone,
    long totalDemandWorkMilli,
    long totalOfferedWorkMilli,
    long totalCommittedWorkMilli) {

  public TransportRoundPlan {
    if (marketRoundId < 0L) {
      throw new IllegalArgumentException("TransportRoundPlan.marketRoundId 不得为负: " + marketRoundId);
    }
    if (totalDemandWorkMilli < 0L || totalOfferedWorkMilli < 0L || totalCommittedWorkMilli < 0L) {
      throw new IllegalArgumentException(
          "TransportRoundPlan 的汇总容量不得为负: demand="
              + totalDemandWorkMilli
              + " offered="
              + totalOfferedWorkMilli
              + " committed="
              + totalCommittedWorkMilli);
    }
    demands = freezeDemands(demands);
    commitments = freezeCommitments(commitments);
    observationsByShippingHex = freezeObservations(observationsByShippingHex);
    demandSupplyByZone = freezeDemandSupply(demandSupplyByZone);
  }

  /** 无条件空计划（无市场 / 无订单时使用；仍保留本轮 id）。 */
  public static TransportRoundPlan empty(long marketRoundId) {
    return new TransportRoundPlan(
        marketRoundId, Map.of(), Map.of(), Map.of(), Map.of(), 0L, 0L, 0L);
  }

  /** 是否一条需求/承诺/观察/区级汇总都没有。 */
  public boolean isEmpty() {
    return demands.isEmpty()
        && commitments.isEmpty()
        && observationsByShippingHex.isEmpty()
        && demandSupplyByZone.isEmpty()
        && totalDemandWorkMilli == 0L
        && totalOfferedWorkMilli == 0L
        && totalCommittedWorkMilli == 0L;
  }

  /** 区级 D/S；缺键 ⇒ null（不是伪造 0）。 */
  public long[] demandSupplyOf(MarketZoneId zone) {
    Objects.requireNonNull(zone, "zone");
    long[] value = demandSupplyByZone.get(zone);
    return value == null ? null : value.clone();
  }

  /** 需求表的防御性只读拷贝（保序不可变；调用方改不到计划内部状态）。 */
  @Override
  public Map<TransportDemandKey, TransportDemand> demands() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(demands));
  }

  /** lane 承诺表的防御性只读拷贝（保序不可变；内层列表在构造期已冻结）。 */
  @Override
  public Map<TransportDemandKey, List<LaneCapacityCommitment>> commitments() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(commitments));
  }

  /** 逐发货 hex 观察的防御性只读拷贝（保序不可变；调用方改不到计划内部状态）。 */
  @Override
  public Map<HexCoord, TransportHexObservation> observationsByShippingHex() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(observationsByShippingHex));
  }

  /** 区级 [D,S] 汇总的防御性只读拷贝。★ 每个数组也复制 ⇒ 调用方无法通过修改返回值影响计划内部状态。 */
  @Override
  public Map<MarketZoneId, long[]> demandSupplyByZone() {
    return freezeDemandSupply(demandSupplyByZone);
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof TransportRoundPlan that)) {
      return false;
    }
    return marketRoundId == that.marketRoundId
        && totalDemandWorkMilli == that.totalDemandWorkMilli
        && totalOfferedWorkMilli == that.totalOfferedWorkMilli
        && totalCommittedWorkMilli == that.totalCommittedWorkMilli
        && demands.equals(that.demands)
        && commitments.equals(that.commitments)
        && observationsByShippingHex.equals(that.observationsByShippingHex)
        && sameDemandSupply(demandSupplyByZone, that.demandSupplyByZone);
  }

  @Override
  public int hashCode() {
    int result = Long.hashCode(marketRoundId);
    result = 31 * result + demands.hashCode();
    result = 31 * result + commitments.hashCode();
    result = 31 * result + observationsByShippingHex.hashCode();
    result = 31 * result + deepDemandSupplyHash(demandSupplyByZone);
    result = 31 * result + Long.hashCode(totalDemandWorkMilli);
    result = 31 * result + Long.hashCode(totalOfferedWorkMilli);
    result = 31 * result + Long.hashCode(totalCommittedWorkMilli);
    return result;
  }

  private static Map<TransportDemandKey, TransportDemand> freezeDemands(
      Map<TransportDemandKey, TransportDemand> source) {
    Objects.requireNonNull(source, "TransportRoundPlan.demands 不得为 null（没有需求请给空表）");
    Map<TransportDemandKey, TransportDemand> copy = new LinkedHashMap<>();
    for (Map.Entry<TransportDemandKey, TransportDemand> entry : source.entrySet()) {
      TransportDemandKey key =
          Objects.requireNonNull(entry.getKey(), "TransportRoundPlan.demands 的键不得为 null");
      TransportDemand value =
          Objects.requireNonNull(entry.getValue(), "TransportRoundPlan.demands 的值不得为 null");
      if (!key.equals(value.key())) {
        throw new IllegalArgumentException(
            "TransportRoundPlan.demands 键与 value.key 不一致: " + key + " vs " + value.key());
      }
      copy.put(key, value);
    }
    return Collections.unmodifiableMap(copy);
  }

  private static Map<TransportDemandKey, List<LaneCapacityCommitment>> freezeCommitments(
      Map<TransportDemandKey, List<LaneCapacityCommitment>> source) {
    Objects.requireNonNull(source, "TransportRoundPlan.commitments 不得为 null（没有承诺请给空表）");
    Map<TransportDemandKey, List<LaneCapacityCommitment>> copy = new LinkedHashMap<>();
    for (Map.Entry<TransportDemandKey, List<LaneCapacityCommitment>> entry : source.entrySet()) {
      TransportDemandKey key =
          Objects.requireNonNull(entry.getKey(), "TransportRoundPlan.commitments 的键不得为 null");
      List<LaneCapacityCommitment> value =
          Objects.requireNonNull(entry.getValue(), "TransportRoundPlan.commitments 的值不得为 null");
      List<LaneCapacityCommitment> rows = new ArrayList<>(value.size());
      for (LaneCapacityCommitment row : value) {
        LaneCapacityCommitment checked =
            Objects.requireNonNull(row, "TransportRoundPlan.commitments 的元素不得为 null");
        if (!key.equals(checked.demandKey())) {
          throw new IllegalArgumentException(
              "TransportRoundPlan.commitments 键与 commitment.demandKey 不一致: "
                  + key
                  + " vs "
                  + checked.demandKey());
        }
        rows.add(checked);
      }
      copy.put(key, Collections.unmodifiableList(rows));
    }
    return Collections.unmodifiableMap(copy);
  }

  private static Map<HexCoord, TransportHexObservation> freezeObservations(
      Map<HexCoord, TransportHexObservation> source) {
    Objects.requireNonNull(
        source, "TransportRoundPlan.observationsByShippingHex 不得为 null（没有观察请给空表）");
    Map<HexCoord, TransportHexObservation> copy = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, TransportHexObservation> entry : source.entrySet()) {
      HexCoord key =
          Objects.requireNonNull(
              entry.getKey(), "TransportRoundPlan.observationsByShippingHex 的键不得为 null");
      TransportHexObservation value =
          Objects.requireNonNull(
              entry.getValue(), "TransportRoundPlan.observationsByShippingHex 的值不得为 null");
      if (!key.equals(value.shippingHex())) {
        throw new IllegalArgumentException(
            "TransportRoundPlan.observationsByShippingHex 键与 observation.shippingHex 不一致: "
                + key
                + " vs "
                + value.shippingHex());
      }
      copy.put(key, value);
    }
    return Collections.unmodifiableMap(copy);
  }

  private static Map<MarketZoneId, long[]> freezeDemandSupply(Map<MarketZoneId, long[]> source) {
    Objects.requireNonNull(source, "TransportRoundPlan.demandSupplyByZone 不得为 null（没有汇总请给空表）");
    Map<MarketZoneId, long[]> copy = new LinkedHashMap<>();
    for (Map.Entry<MarketZoneId, long[]> entry : source.entrySet()) {
      MarketZoneId key =
          Objects.requireNonNull(
              entry.getKey(), "TransportRoundPlan.demandSupplyByZone 的键不得为 null");
      long[] value =
          Objects.requireNonNull(
              entry.getValue(), "TransportRoundPlan.demandSupplyByZone 的值不得为 null");
      if (value.length != 2) {
        throw new IllegalArgumentException(
            "TransportRoundPlan.demandSupplyByZone 的值必须是 [D,S] 两元素: zone=" + key);
      }
      if (value[0] < 0L || value[1] < 0L) {
        throw new IllegalArgumentException(
            "TransportRoundPlan.demandSupplyByZone 的 D/S 不得为负: zone="
                + key
                + " D="
                + value[0]
                + " S="
                + value[1]);
      }
      copy.put(key, new long[] {value[0], value[1]});
    }
    return Collections.unmodifiableMap(copy);
  }

  private static boolean sameDemandSupply(
      Map<MarketZoneId, long[]> left, Map<MarketZoneId, long[]> right) {
    if (!left.keySet().equals(right.keySet())) {
      return false;
    }
    for (Map.Entry<MarketZoneId, long[]> entry : left.entrySet()) {
      if (!Arrays.equals(entry.getValue(), right.get(entry.getKey()))) {
        return false;
      }
    }
    return true;
  }

  private static int deepDemandSupplyHash(Map<MarketZoneId, long[]> source) {
    int result = 0;
    for (Map.Entry<MarketZoneId, long[]> entry : source.entrySet()) {
      result += entry.getKey().hashCode() ^ Arrays.hashCode(entry.getValue());
    }
    return result;
  }
}
