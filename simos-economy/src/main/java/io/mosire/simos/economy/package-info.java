/**
 * EconomySimos —— 经济切片骨架（新经济设计 §3 的状态形状，**无公式**）。
 *
 * <p><b>本切片只写自己的数据</b>（§2）：产业表（制度/周期/进度/日投入/产出/阶层槽位/分配函数）、阶层行（人口份额/有效劳动/劳动投入率/
 * 生产资料/库存/货币/债务引用/需求）、债务表、周期流水，外加一份激活元信息。{@code IndustryId}/{@code ClassSlotId}/{@code
 * RegimeId}/{@code DebtId}/{@code CommodityId} 一律取自 {@code simos-economy-api}（铁律 1：不在切片里另造同义 ID）。
 *
 * <p><b>本切片不含任何经济公式</b>（§八 R1 行："模块化、无公式"）：产量、分配、税、市场撮合、流动全是 R2+ 的结算逻辑， {@link
 * io.mosire.simos.economy.EconomyData} 只是状态与形状。存量/流量分离（§3.3 末条）：{@code ClassRow} 是存量、 {@code
 * FlowRow} 是本期发生额（结算后清零）。
 *
 * <p><b>未激活 = {@code meta} 空</b>：{@code EconomyData.meta} 为空 {@code Optional} 表示日制世界尚未激活经济（§6.6）。
 * 空切片 ≠ 已激活。
 *
 * <p><b>硬约束</b>：不依赖任何别的领域切片（不依赖 {@code simos-social}/{@code simos-unit}/{@code simos-sd}/{@code
 * simos-core}/{@code simos-ledger}/app/agentlib），由本模块 POM 的 enforcer 在构建期强制。
 *
 * <p><b>切片形状</b>：{@link io.mosire.simos.economy.EconomyData}（状态树）/ {@link
 * io.mosire.simos.economy.EconomySnapshot}（落盘切片）/ {@link
 * io.mosire.simos.economy.change.EconomyChangeSet}（逐组件 {@code FieldDelta}）/ {@link
 * io.mosire.simos.economy.codec.EconomyCodec}（JSON 往返 + diff + apply）/ {@link
 * io.mosire.simos.economy.resolve.EconomyResolver}（{@code economy:} 地址解析）。
 */
package io.mosire.simos.economy;
