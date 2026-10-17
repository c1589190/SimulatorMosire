/**
 * ★★ <b>{@code simos-culture}</b>（文化切片，namespace {@code "culture"}）—— D-031 接口先行的 Z1 责任区； ★★
 * <b>D-032 起为占位模块</b>。
 *
 * <p>本模块拥有：家户文化标签（文化 / 宗教 / 习俗）的状态、一条命令（{@code culture.SetHouseholdCulture}）、 codec/变更集，以及只读口
 * {@code contract.CultureLookup}。对外只提供契约包 {@code io.mosire.simos.culture.contract} 的值类型与只读口。
 *
 * <p>★★ <b>D-032（D2 直接删）："文化 → 生产效率倍率"整条通路已退役</b>。用户口径：「文化模块和生产效率模块解耦， 文化直接决定生产效率太不唯物主义了」（U5）、「D2
 * 直接删，文化部分目前都没咋做，留着模块占位置就行」（U7）。 已删除：{@code contract.ProductionAptitudeRule}、{@code
 * aptitude.ProductionAptitudeComposer}、 {@code aptitude.AptitudeBlend}、{@code
 * CultureData.aptitudeRules}、{@code CultureLookup.rulesFor}、 两条规则命令、economy-api 的 {@code
 * ProductionEfficiencyModifier} 与 economy 侧接收链、app 的 {@code CultureAptitudeBridge} / {@code
 * ProductionAptitudeAssembly}。
 *
 * <p>★★ <b>保留的用途</b>：文化将来只提供<b>需求差异</b>与<b>外部属性</b>（如识字率）。外部属性经 {@code
 * production.contract.ExternalAttributeFacts} 作为生产方案可用性条件的事实入参传入 production —— production
 * <b>不感知</b>属性来自哪个模块（U5 原文：「这些定义是自由的，不必让生产方式模块知道这些参数来自哪里」）。 文化配置本体是单家户独有、<b>不可迁移</b>（U5），迁移时不随人复制。
 *
 * <p>★ <b>依赖方向</b>（spec §4）：{@code culture →
 * simos-util/simos-map/simos-social-api/simos-economy-api/ simos-production} 单向（production 只读其
 * {@code contract} 包）；不得依赖 {@code simos-economy} 实现、 {@code simos-social} 实现、{@code
 * simos-core}、{@code simos-app}（enforcer 构建期强制）。
 *
 * <p>★ <b>无 {@code TimeParticipant}</b>：文化没有自己的推进能力，不为满足计数造空推进器；状态写入全部来自命令面。 ★
 * D-033（宗教/教区）按用户裁定暂不推进，本轮只留占位。
 */
package io.mosire.simos.culture;
