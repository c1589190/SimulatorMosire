/**
 * EconomyApiSimos——六个经济切片共用的**稳定契约层**（设计稿 §1/§2/§10）。
 *
 * <p>★ **边界**：本模块**只放共用契约**——稳定 ID、{@link
 * io.mosire.simos.economy.api.id.CommodityId}，以及跨模块事件与转移意图（后者本轮未做，待五个切片存在后再定形状）。
 *
 * <p>★★ **S1 阶段 4+5 起，本口径放宽一格**（裁定 E10）：本模块现在也放**关系契约的数据记录**—— {@link
 * io.mosire.simos.economy.api.relation.RuleType} / {@link
 * io.mosire.simos.economy.api.relation.Pool} / {@link io.mosire.simos.economy.api.relation.Weight}
 * / {@link io.mosire.simos.economy.api.relation.Recipient} / {@link
 * io.mosire.simos.economy.api.relation.CompensationRule} / {@link
 * io.mosire.simos.economy.api.relation.ProductionRelation} 与 {@link
 * io.mosire.simos.economy.api.cohort.CohortKey}。★ 它们**不是 ID**，而是"一次生产如何在经营者 / 劳动者 /
 * 资产所有者之间结算"的**形状**；★★ **H2（裁定 D2-A/K4）起还放转移原语**（{@link
 * io.mosire.simos.economy.api.transfer.Transfer} 与 {@link
 * io.mosire.simos.economy.api.transfer.TransferReason}）—— 全系统唯一的"东西从 A 到 B"的事实 （{@code
 * simos-ledger} 退役后形状搬到这里，主体改 {@code ActorRef}）。★ {@link
 * io.mosire.simos.economy.api.relation.Basis} 仍在，但它已是**只服务旧档读侧**的兼容词表（H2 把它拆成池 × 权重）。
 *
 * <p>★ **为什么放宽的是"数据记录可以有"而不是"公式也可以有"**：两侧切片（{@code economy} 算、{@code actor} 存）都要看得见这批类型，而它们分属两个模块 ⇒
 * 只能住契约层（先例 = {@code HouseholdLaborCommitment} / {@code LotChange}）。★ **"没有经济公式"这一条一字不改**：哪些规则组合有公式、公式怎么算，仍在
 * {@code simos-economy} 的结算里；本包只有枚举、字段与构造期守卫。
 *
 * <p>★ **落点曾在 spec 里写错**（裁定 E3）：spec §三 把这批契约列在 {@code simos-actor-api}，而那个模块的 **主依赖为零**（连 util/map
 * 都不声明）⇒ 装不下要用 {@code CommodityId} / {@code HexCoord} 的类型，硬放会成 {@code actor-api → economy-api →
 * actor-api} **循环**。与阶段 2「{@code AssetClassKey} 无物可移」同款修正。
 *
 * <p>★ **S1 阶段 2 起 {@code ActorRef} / {@code ActorKind} 不在本模块**：它们上移到更底层的 {@code
 * simos-actor-api}（{@link io.mosire.simos.actor.api.actor.ActorRef}）—— 本模块现在**引用**它们而不再**拥有**它们
 * （{@code HouseholdLaborCommitment.actor} 仍持有 {@code ActorRef}，故 pom 里显式依赖 actor-api）。
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
