/**
 * ★★ <b>{@code simos-production} 的公开契约包</b>（D-031 spec §6.1/§13 Z2）。
 *
 * <p>本包是**跨模块只读消费面**：economy 适配器（Z3）与 culture（Z1）只许 import {@code
 * io.mosire.simos.production.contract}，不得 import 本模块的非 contract 包（外部包边界测试守）。具体实现如 {@code
 * io.mosire.simos.production.skill.ProductionSkillCatalog} 住模块内部，只由 production/app 装配并注入。
 *
 * <p>★★ <b>三处"未完成"标记（本模块相关）</b>：① 生产定义权威**尚未迁移**——本轮仍在 {@code simos-economy}， {@code
 * ProductionDefinitionLookup} 的实现是 economy 适配器，P1 才把定义搬入本模块；② 真实文化效果**未启用**（本轮只有中性默认路径）； ③
 * 旧双流动机制**未被替换**（{@code ClassSlideSettlement} / {@code ModeMigrationPolicy} 原样在跑）。
 *
 * <p>★ <b>本包值类型不得引用 economy 实现类</b>（spec §4 无环证明）：字段只取 util / map / economy-api（{@code AssetKind} 经
 * economy-api 传递可见）。本包不含状态、不含公式、不含命令 handler。
 */
package io.mosire.simos.production.contract;
