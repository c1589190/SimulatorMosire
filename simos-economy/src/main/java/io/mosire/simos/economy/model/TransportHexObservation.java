package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.map.hex.HexCoord;
import java.util.Objects;

/**
 * ★★★ <b>D-032 C7b1：一个市场区 × 发货 hex 的本地运输轮观察</b>。
 *
 * <p>观察只影响本 hex 的后续运输生产预期与读口，不改变区级锚价；区级 D/S 由 {@code TransportRoundPlan.demandSupplyByZone}
 * 单独汇总，本地未服务量与本地未售容量不得先互相抵消。
 *
 * <p>瞬态类型：本任务只把观察放计划与日志；C7b2 后续才把它冻结为 {@code MarketRoundStats.lastTransportByHex} 扩展读数面。
 *
 * @param marketZone 市场区；不得为 null
 * @param shippingHex 本地发货格；不得为 null
 * @param marketRoundId 市场轮 id；不得为负
 * @param demandWorkMilli 本 hex 当轮需求（毫商品·程）；不得为负
 * @param offeredWorkMilli 本 hex 当轮本地批次总容量；不得为负
 * @param committedWorkMilli 本 hex 当轮已承诺容量；不得为负
 * @param unservedWorkMilli 本 hex 未被承诺的需求；不得为负
 * @param unsoldWorkMilli 本 hex 未承诺的本地容量；不得为负
 * @param anchorPricePerWorkMilli 当轮市场区锚价快照；不得为负，0 合法
 */
public record TransportHexObservation(
    MarketZoneId marketZone,
    HexCoord shippingHex,
    long marketRoundId,
    long demandWorkMilli,
    long offeredWorkMilli,
    long committedWorkMilli,
    long unservedWorkMilli,
    long unsoldWorkMilli,
    long anchorPricePerWorkMilli) {

  public TransportHexObservation {
    Objects.requireNonNull(marketZone, "TransportHexObservation.marketZone 不得为 null");
    Objects.requireNonNull(shippingHex, "TransportHexObservation.shippingHex 不得为 null");
    if (marketRoundId < 0L) {
      throw new IllegalArgumentException(
          "TransportHexObservation.marketRoundId 不得为负: " + marketRoundId);
    }
    if (demandWorkMilli < 0L
        || offeredWorkMilli < 0L
        || committedWorkMilli < 0L
        || unservedWorkMilli < 0L
        || unsoldWorkMilli < 0L
        || anchorPricePerWorkMilli < 0L) {
      throw new IllegalArgumentException(
          "TransportHexObservation 的容量/价格字段不得为负: demand="
              + demandWorkMilli
              + " offered="
              + offeredWorkMilli
              + " committed="
              + committedWorkMilli
              + " unserved="
              + unservedWorkMilli
              + " unsold="
              + unsoldWorkMilli
              + " anchor="
              + anchorPricePerWorkMilli);
    }
    if (committedWorkMilli > demandWorkMilli) {
      throw new IllegalArgumentException(
          "TransportHexObservation.committedWorkMilli 不得超过 demandWorkMilli: "
              + committedWorkMilli
              + " > "
              + demandWorkMilli);
    }
    if (committedWorkMilli > offeredWorkMilli) {
      throw new IllegalArgumentException(
          "TransportHexObservation.committedWorkMilli 不得超过 offeredWorkMilli: "
              + committedWorkMilli
              + " > "
              + offeredWorkMilli);
    }
    if (unservedWorkMilli != demandWorkMilli - committedWorkMilli) {
      throw new IllegalArgumentException(
          "TransportHexObservation.unservedWorkMilli 必须等于 demand-committed: "
              + unservedWorkMilli
              + " != "
              + (demandWorkMilli - committedWorkMilli));
    }
    if (unsoldWorkMilli != offeredWorkMilli - committedWorkMilli) {
      throw new IllegalArgumentException(
          "TransportHexObservation.unsoldWorkMilli 必须等于 offered-committed: "
              + unsoldWorkMilli
              + " != "
              + (offeredWorkMilli - committedWorkMilli));
    }
  }
}
