package io.mosire.simos.economy.model;

import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Objects;

/**
 * ★★★ <b>D-032 C7b1：一条 lane 容量承诺</b>。
 *
 * <p>承诺 = 某个当前市场轮 {@link TransportCapacityBatch} 的一条本地批次，对某条 {@link TransportDemand} 承诺的运力。同一批次可在本
 * hex 的多个目的地 lane 间共享；所有承诺之和不得超过该批次 {@code capacityWorkMilli}。本类型只是计划期投影，不修改 C6 持久批次。
 *
 * <p>瞬态类型：不落盘、不进 ChangeSet/Codec。
 *
 * @param demandKey 被服务的需求；不得为 null
 * @param batchKey 提供运力的 C6 批次键；不得为 null
 * @param carrierHousehold 承运家户；必须等于 {@code batchKey.household()}
 * @param committedWorkMilli 本 lane 承诺的运力；不得为负
 */
public record LaneCapacityCommitment(
    TransportDemandKey demandKey,
    TransportCapacityBatchKey batchKey,
    HouseholdId carrierHousehold,
    long committedWorkMilli) {

  public LaneCapacityCommitment {
    Objects.requireNonNull(demandKey, "LaneCapacityCommitment.demandKey 不得为 null");
    Objects.requireNonNull(batchKey, "LaneCapacityCommitment.batchKey 不得为 null");
    Objects.requireNonNull(carrierHousehold, "LaneCapacityCommitment.carrierHousehold 不得为 null");
    if (!carrierHousehold.equals(batchKey.household())) {
      throw new IllegalArgumentException(
          "LaneCapacityCommitment.carrierHousehold 必须等于 batchKey.household: "
              + carrierHousehold
              + " vs "
              + batchKey.household());
    }
    if (committedWorkMilli < 0L) {
      throw new IllegalArgumentException(
          "LaneCapacityCommitment.committedWorkMilli 不得为负: " + committedWorkMilli);
    }
    if (!batchKey.homeHex().equals(demandKey.shippingHex())) {
      throw new IllegalArgumentException(
          "LaneCapacityCommitment 违反本地运力约束: batch.homeHex="
              + batchKey.homeHex()
              + " shippingHex="
              + demandKey.shippingHex());
    }
  }
}
