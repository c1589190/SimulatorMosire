package io.mosire.simos.production.contract;

import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.inventory.InventoryLotId;
import io.mosire.simos.social.api.id.HouseholdId;

/**
 * ★★ <b>D-032 L2b2a：一轮组织关系下的生产认领</b>（架构 v1 §2.4；plan §3.1）。
 *
 * <p>它只回答「<b>谁在哪个时间段为哪个 carrier lot 执行哪个生产方案</b>」，<b>不存</b> stage / progress / cycleInputUsed /
 * 任何生产进度。进度永远由 carrier lot 自己的 {@link io.mosire.simos.economy.api.inventory.LotState.InProduction}
 * 持有；本类型只是本轮内存态，不落 ChangeSet / Codec，也不读时钟、随机数或文件。
 *
 * <p>★ <b>owner 与 operator 可以不同</b>：owner 是 lot 的所有者，operator 是实际执行并暂时接收 v1 产出的家户。两者的经济分账由后续经济层处理，
 * 本类型只登记这两个身份。
 *
 * <p>★ <b>一个 lot 一个 tick 最多一个 active claim</b>：重复 lot 的 claim 集合由 {@code LandProductionSettlement}
 * 在入口处整体 fail-closed，不在本记录里做跨记录校验。
 *
 * @param claimId 本轮 claim 的稳定身份；非空白
 * @param lotId 被认领的 carrier lot；非 null
 * @param planId 生产方案（产业定义）稳定身份；非 null
 * @param ownerHousehold lot 的所有者家户；非 null
 * @param operatorHousehold 实际执行本 claim 的家户；非 null
 * @param fromTick 生效起点（含）；≥ 0
 * @param toTickExclusive 生效终点（不含）；必须严格大于 {@code fromTick}
 */
public record ProductionClaim(
    String claimId,
    InventoryLotId lotId,
    IndustryId planId,
    HouseholdId ownerHousehold,
    HouseholdId operatorHousehold,
    long fromTick,
    long toTickExclusive) {

  public ProductionClaim {
    if (claimId == null || claimId.isBlank()) {
      throw new IllegalArgumentException("ProductionClaim.claimId 不得为空白");
    }
    if (lotId == null) {
      throw new IllegalArgumentException("ProductionClaim.lotId 不得为 null");
    }
    if (planId == null) {
      throw new IllegalArgumentException("ProductionClaim.planId 不得为 null");
    }
    if (ownerHousehold == null) {
      throw new IllegalArgumentException("ProductionClaim.ownerHousehold 不得为 null");
    }
    if (operatorHousehold == null) {
      throw new IllegalArgumentException("ProductionClaim.operatorHousehold 不得为 null");
    }
    if (fromTick < 0L) {
      throw new IllegalArgumentException("ProductionClaim.fromTick 不得为负: " + fromTick);
    }
    if (toTickExclusive <= fromTick) {
      throw new IllegalArgumentException(
          "ProductionClaim.toTickExclusive 必须严格大于 fromTick: fromTick="
              + fromTick
              + ", toTickExclusive="
              + toTickExclusive);
    }
  }
}
