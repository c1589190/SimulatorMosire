/**
 * ★★ <b>一般市场的稳定契约（M2.1）</b>：买卖双方各自挂牌的订单形状 —— {@link
 * io.mosire.simos.economy.api.market.BuyOrder}（买）与 {@link
 * io.mosire.simos.economy.api.market.SellOrder}（卖），外加买方的独立预算 {@link
 * io.mosire.simos.economy.api.market.Budget}。
 *
 * <p>★★ <b>为什么订单住 {@code economy-api}</b>：订单的双方是 {@code ActorRef}（actor 切片的稳定身份）、商品是 {@code
 * CommodityId}、地点是 {@code HexCoord} —— 这三者都是本模块的主依赖能看见的契约类型。结算逻辑（怎么产生订单、怎么撮合）留在 {@code
 * simos-economy}，本包<b>只有形状与构造期守卫</b>，没有公式。
 *
 * <p>★ <b>本包在本层是"瞬时"的</b>：订单在一个市场轮次内建、同一个轮次内清，<b>不进任何状态树</b>（不给 {@code EconomyData}
 * 加组件、不进变更集/codec）。★ 在途与跨轮存活是 L2 的 {@code ShipmentBatch}，那时才需要新的持久组件。
 *
 * <p>★ 量纲与全仓一致：数量是<b>毫商品</b>、金额是<b>最小币值</b>（毫银）、价格是<b>毫计价货币 / 商品单位</b>（见 {@code Market} 的类注）。
 */
package io.mosire.simos.economy.api.market;
