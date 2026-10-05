/**
 * ActorSimos —— actor 切片的领域内核（S1 spec §三 L277）。
 *
 * <p>★★ <b>本切片承担什么</b>（用户裁定，spec §三 L277 原文）：{@code Actor identity · AssetHolding · Goods
 * ownership/inventory · ProductionRelation}。★ <b>已落地</b>（C 段逐块增量）：{@link
 * io.mosire.simos.actor.model.Actor}（身份本体）+ 状态树骨架（{@code meta} / {@code actors}）+ <b>库存</b> （{@link
 * io.mosire.simos.actor.model.HouseholdInventory}，Task 6 加：聚合键 {@code (owner, location)}，★ 它是新产权模型里商品余额的
 * 唯一真源 —— 见裁定 R6 与该类的类注）；{@code ProductionRelation} 属后续阶段 —— 状态树的表按任务顺序<b>增量</b>加，每个任务自身可编译可测。
 *
 * <p>★★ <b>2026-09-27 裁定 S3：产权（{@code AssetHolding} / {@code AssetClassKey} / {@code
 * AssetHoldingKey}）整块退役</b> —— 实测生产侧零写入者（真档创世把 actor 起成 {@code ActorData.empty()}）、economy 侧 {@code
 * harvest} 更是硬编码空表 ⇒ 收益为 0，属"看起来在记、其实永远不被读"。★ 资产（土地/工具/牲畜）推迟到真需要时再加； <b>{@code AssetKind}
 * 粗类型词表保留</b>（economy 仍拿它作产能的键）。⇒ <b>本切片里商品库存（{@code HouseholdInventory}）就是唯一的那本账。</b>
 *
 * <p>★★ <b>本切片不吞 Money / Debt</b>（spec §三 L281-283）：债权天然是跨主体关系（{@code debtor / creditor / principal
 * / terms}），Money/credit 又是 S2 的领域 ⇒ <b>禁止</b>写 {@code ActorRow { Money money; List<Debt> debts; }}
 * 这种形状，否则 S2 第一件事就是拆 S1。既有的 {@code EconomyData.debts} 与 {@code HouseholdEconomy.debts}
 * <b>本阶段一行不动</b>（legacy bridge，S2 迁走）。
 *
 * <p>★★ <b>库存不是 Actor 的字段</b>（spec §2.3 L109、§三 L290）：<i>"资产是 Actor <b>拥有的关系</b>， 不是 Actor
 * <b>本体的一部分</b>"</i> —— 改一本账不用打开整个 Actor aggregate；商品余额独立成 {@code HouseholdInventory}（裁定 S3
 * 之后它是本切片唯一的那本账）。故 {@code Actor} 的成分表<b>只有</b> {@code ref} 与 {@code label}（由 {@code
 * ActorRoundTripTest} 的反射断言把守）。
 *
 * <p><b>未激活 = {@code meta} 空</b>：{@code ActorData.meta} 为空 {@code Optional} 表示本世界尚未落 actor 切片。 空快照
 * ≠ 已激活（同 {@code economy} 的口径）。
 *
 * <p><b>切片形状</b>：{@link io.mosire.simos.actor.ActorData}（状态树）/ {@link
 * io.mosire.simos.actor.ActorSnapshot}（落盘切片，{@code namespace()} 恒 {@code "actor"}）/ {@link
 * io.mosire.simos.actor.change.ActorChangeSet}（逐组件 {@code FieldDelta}，铁律 5）。
 *
 * <p><b>硬约束</b>：不依赖 {@code economy} / {@code ledger}（同层切片）与 {@code social}/{@code unit}/{@code
 * sd}/{@code core}/{@code app}/{@code agentlib}，由本模块 POM 的 enforcer 在构建期强制（裁定
 * R2：只允许类型依赖，不允许运行时领域控制流反向流入）。
 */
package io.mosire.simos.actor;
