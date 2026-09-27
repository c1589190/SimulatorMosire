package io.mosire.simos.economy.api.market;

import io.mosire.simos.map.hex.HexCoord;
import java.util.Objects;

/**
 * ★★ <b>一条贸易路线</b>（M2.4 最小版）：{@code from → to} 的<b>运力 / 时间 / 实际投入 / 损耗</b>。
 *
 * <p>★★ <b>运输成本拆成五件，本记录承载四件、第五件（运费）由结算层按具名费率铸一条腿</b>（M2.4 的硬要求：不许一个系数兼三职）：
 *
 * <ol>
 *   <li><b>运费</b>（货币，→ 承运主体的收入）：<b>不在本记录里</b>—— 它是"每格运费 × {@link #travelTicks()}", 由 {@code
 *       MarketSettlement} 用具名常量算出，且**只有在世界里真有承运 actor（{@code ActorKind.ORGANIZATION}）时才收**
 *       （没有收款方就不收，禁钱凭空消失）；
 *   <li><b>实际投入</b>（劳动/饲料/维护）：{@link #costPerUnit()} = {@code hexDistance × moveCost(目标格)} （口径抄
 *       {@code SettlementGenerator} 的腹地竞争：平原 1 … 山地 6 … 高原山地 12，海洋 999 = 不可通行）——
 *       它**只计入路线读数**，不另扣谁的库存（本批没有承运人的投入账，如实记）；
 *   <li><b>损耗</b>（在途实物减少 → 损耗账户）：{@link #lossPerMille()}（千分数；到货时从在途量里扣，进 {@code
 *       ProductionLedger.losses}）；
 *   <li><b>时间</b>（ETA，到货前目的地不得消费）：{@link #travelTicks()}（1 天/格，与单位移动口径一致）；
 *   <li><b>运力</b>（每个市场窗口最大发运量）：{@link #capacityPerWindow()}（毫商品）。
 * </ol>
 *
 * <p>★ <b>路线是区域撮合的产物、也是发货凭据</b>：{@link #from()} 必须是真的发货格、{@link #to()} 必须是真的收货格 —— 判据
 * "跨格成交可追到发货格/收货格/路线/ETA"读的就是这两个坐标与后两个数。
 *
 * @param from 发货格；不得为 null
 * @param to 收货格；不得为 null
 * @param capacityPerWindow 每个市场窗口的最大发运量（毫商品；{@code > 0}）
 * @param travelTicks 单程天数（世界日；{@code > 0}）
 * @param costPerUnit 实际投入代价（{@code hexDistance × moveCost(目标格)}；{@code > 0}）
 * @param lossPerMille 在途损耗率（千分数；{@code 0 <= x <= 1000}，1000 = 全损）
 */
public record TradeRoute(
    HexCoord from,
    HexCoord to,
    long capacityPerWindow,
    long travelTicks,
    long costPerUnit,
    int lossPerMille) {

  public TradeRoute {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    if (capacityPerWindow <= 0L) {
      throw new IllegalArgumentException(
          "TradeRoute.capacityPerWindow 必须 > 0（没有运力的路不是路）: " + capacityPerWindow);
    }
    if (travelTicks <= 0L) {
      throw new IllegalArgumentException(
          "TradeRoute.travelTicks 必须 > 0（区内即时不走 TradeRoute）: " + travelTicks);
    }
    if (costPerUnit <= 0L) {
      throw new IllegalArgumentException(
          "TradeRoute.costPerUnit 必须 > 0（hexDistance × moveCost(目标格) 的产物）: " + costPerUnit);
    }
    if (lossPerMille < 0 || lossPerMille > 1000) {
      throw new IllegalArgumentException("TradeRoute.lossPerMille 必须在 [0,1000]: " + lossPerMille);
    }
  }
}
