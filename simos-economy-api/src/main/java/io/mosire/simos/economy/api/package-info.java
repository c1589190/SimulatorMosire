/**
 * EconomyApiSimos——六个经济切片共用的**稳定契约层**（设计稿 §1/§2/§10）。
 *
 * <p>★ **边界**：本模块**只放共用契约**——稳定 ID、{@link
 * io.mosire.simos.economy.api.id.CommodityId}，以及跨模块事件与转移意图（后者本轮未做，待五个切片存在后再定形状）。
 *
 * <p>★ **S1 阶段 2 起 {@code ActorRef} / {@code ActorKind} 不在本模块**：它们上移到更底层的 {@code
 * simos-actor-api}（{@link io.mosire.simos.actor.api.actor.ActorRef}）—— 本模块现在**引用**它们而不再**拥有**它们
 * （{@code LaborAllocation.actor} 仍持有 {@code ActorRef}，故 pom 里显式依赖 actor-api）。
 *
 * <p>★ **没有** Snapshot、**没有**数据库/存储、**没有**经济公式（税率、产量、价格、余额一律不在本模块）。
 *
 * <p>★ **五个经济切片各自只写自己的切片，跨模块编排在 app**（设计稿 §1）：{@code simos-property} / {@code simos-production} /
 * {@code simos-ledger} / {@code simos-market} / {@code simos-government} 与 {@code simos-social}
 * 可依赖本模块与 util/map，但**彼此不作源码依赖**；app 的日协调器负责跨模块编排与守恒校验。
 *
 * <p>依赖方向由本模块 {@code pom.xml} 的 enforcer 强制：不许反向依赖任何领域/编排模块。
 */
package io.mosire.simos.economy.api;
