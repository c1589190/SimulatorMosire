package io.mosire.simos.ga.spi;

import java.util.Set;

/**
 * ★★ <b>「这条工具可被决策人 propose」的唯一声明</b>（批 K ① 根治版；用户 2026-10-15 原话：「这玩意不是在程序代码上已经写死了吗，就是有 DM 标签的工具 ……
 * 为啥额外要张表？」）。
 *
 * <p>★ <b>为什么是本接口（而不是一张名单）</b>：工具的**可提议性**此前是 {@code ProposalCatalog} 里的**第二处手写清单**（16 条 {@code new
 * XxxTool(...)}）——它与工具自己的 {@code toolRoles()} 是两处互不相干的声明，漂移**无症状**（原始清单里 7 条是 GM-only，
 * 却照样能被提议，没有任何东西拦）。本接口把「我能不能被提议」变成**工具自己的声明**：实现了它 ⇒ 进可提议面； 没实现 ⇒ 不进。装配期由 {@code
 * ProposalDeclarationAudit} 做 fail-closed 校验（实现者 {@code toolRoles()} 必须含 {@link ToolRole#DM}）。
 *
 * <p>★★ <b>本接口只有"配置"，没有"怎么提议"</b>：预览与目标提取是**通用**的（跑真 {@code preview=true}、从 args + preview 递归提目标），
 * 唯一实现住 {@code simos-app} 的 {@code ProposalCatalog}（不变量 K3）——本接口只声明各工具**自己的**那点差异。
 *
 * <p>★ <b>为什么没有 {@code toolName()} / {@code tool()}</b>（旧形状 {@code simos-ga} 版有这两个方法）：那时实现者是
 * catalog 内部的 {@code Entry} 适配器，它**只是持有**别人的工具实例，所以要回指"我叫什么、我包的是谁"；现在**工具自己实现**本接口 ⇒ {@code
 * toolName()} 就是它的 {@link io.mosire.agentlib.tool.AgentTool#name()}、{@code tool()} 就是 {@code
 * this}，两者都是冗余的第三处声明（会漂移），故**删除**。
 */
public interface DecisionProposable {

  /**
   * 目标提取时**整键跳过**的字段（如创建型工具的新 id 常量字段）。
   *
   * <p>★ 这是**每条工具自己的**配置：创建型工具的新单位 id（{@code newUnitId}）不是既有资源、不能按"将要成为的目标"去判越界（那会把合法提议全部拒掉），
   * 故显式跳过、改用 {@code at}/{@code regionId} 目标。
   */
  default Set<String> skipKeys() {
    return Set.of();
  }

  /** 是否是**创建型**工具：为真时，目标提取还会跳过与参数/预览里 {@code newUnitId} 逐字相等的单位 id（防止"新单位尚不存在 ⇒ 判不了"被误报越界）。 */
  default boolean createsUnit() {
    return false;
  }
}
