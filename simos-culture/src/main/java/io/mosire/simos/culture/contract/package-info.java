/**
 * ★★ <b>{@code simos-culture} 的公开契约包</b>（D-031 spec §6.2/§13 Z1；★ D-032 起只剩标签面）。
 *
 * <p>本包是**跨模块只读消费面**：文化身份（{@link io.mosire.simos.culture.contract.CultureId} / {@link
 * io.mosire.simos.culture.contract.ReligionId} / {@link
 * io.mosire.simos.culture.contract.CustomTag}）、家户文化标签（{@link
 * io.mosire.simos.culture.contract.CultureTag} / {@link
 * io.mosire.simos.culture.contract.HouseholdCulture}）与只读口 {@link
 * io.mosire.simos.culture.contract.CultureLookup}。
 *
 * <p>★★ <b>D-032（D2 直接删）：熟练度规则（{@code ProductionAptitudeRule}）已从本包删除</b>——"文化直接决定 生产效率"正是用户 U5
 * 要解耦的那条通路。文化将来只提供需求差异与外部属性（如识字率），后者由所属领域提供、 组合根装配成 {@code
 * production.contract.ExternalAttributeFacts}，production 只消费事实、不感知来源模块。
 *
 * <p>★ <b>本包不再引用 {@code ProductionSkillKey}</b>（旧规则表的内层键已随规则表删除）：文化现在与"生产技能目录" 之间没有任何引用关系。
 *
 * <p>★ 本轮只立 typed 契约：<b>不</b>生成传播/同化/真实流动行为，<b>不</b>加激活位或开关。
 */
package io.mosire.simos.culture.contract;
