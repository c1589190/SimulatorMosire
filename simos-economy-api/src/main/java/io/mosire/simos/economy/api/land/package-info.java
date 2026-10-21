/**
 * ★★★ <b>标量 LAND 公共契约</b>（D-032 RA1a，2026-10-19）：土地是绑定单个 hex 的、开局固定的不动商品，按 {@code (owner, hex,
 * quantityMilliMu)} 标量持有与任意数量转移。
 *
 * <p>★ <b>本包只放值形状与具名失败</b>：{@link LandHolding} / {@link LandHoldingKey} / {@link LandTransfer} /
 * {@link LandMarketIntent} / {@link LandArableSupply} 与 {@link LandFailureReason}；纯账本实现住 {@code
 * simos-economy} 的 {@code io.mosire.simos.economy.land}，不在这里。
 *
 * <p>★ <b>没有的东西</b>：无旧资产份额 id、无旧块编号、无土地池、无租约/抵押/Pledge。土地也不 进普通 {@code HouseholdGoods}；本包不依赖 actor
 * 库存或市场状态。
 *
 * <p>★ <b>尚未接持久化</b>：本包是 RA1a 的跨责任区契约；RA1b 才把它接进 EconomyData/ChangeSet/Codec/债务/市场/生产。
 */
package io.mosire.simos.economy.api.land;
