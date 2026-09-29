package io.mosire.simos.economy.model;

/**
 * ★★ <b>生产资料的可转移规则</b>（理想架构 §2.5 的 {@code transferRule}；E2 只落形状，E5 的清算/抵押按它判处置边界）。
 *
 * <p>★ <b>三个布尔各是一个制度事实</b>（不是三个"可选开关"）：
 *
 * <ul>
 *   <li>{@code transferable} —— 所有权可否转移（买卖/赠予/继承；清算时能否强制处置）；
 *   <li>{@code requiresOwnerConsent} —— 转移是否必须所有权人同意（租佃/抵押的边界；E5 据此判"租佃份额不能由佃户卖"）；
 *   <li>{@code allowSublease} —— 经营者可否把使用/经营权重再租出去（转租）。
 * </ul>
 *
 * <p>★ <b>本类型不含公式</b>：它只回答"这条资产允不允许"；"允许多少/顺序如何"住在 {@code AssetRule.liquidationPriority} 与 E5
 * 的清算阶段里。★ 不可变值记录，构造期无额外不变量（三个布尔都是合法状态）。
 *
 * @param transferable 所有权可否转移
 * @param requiresOwnerConsent 转移是否必须所有权人同意
 * @param allowSublease 经营者可否转租
 */
public record TransferRule(
    boolean transferable, boolean requiresOwnerConsent, boolean allowSublease) {}
