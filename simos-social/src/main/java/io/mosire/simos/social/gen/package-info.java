/**
 * 聚落/人口生成器（P2 的纯函数核心，P3 才接线）。
 *
 * <p>★ **本包不碰存储、不碰命令**：输入是 {@link io.mosire.simos.social.gen.SettlementRequest} + {@link
 * io.mosire.simos.social.gen.TerrainView} + {@link io.mosire.simos.social.gen.SettlementParams}，输出是
 * {@link io.mosire.simos.social.gen.SettlementPlan}。地形经 {@code TerrainView.of(GameMap)} 进来一次，其余全是算术
 * —— 于是可以脱库单测。
 *
 * <p>★ **确定性**：随机数一律是坐标哈希（{@code splitmix64}），与任何 {@code Map}/{@code Set} 的迭代序无关；同种子同输入恒同输出。
 */
package io.mosire.simos.social.gen;
