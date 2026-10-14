package io.mosire.simos.ga.spi;

/**
 * 工具的三分类（"这个工具能被谁用 / 要不要问人"的**唯一口径**；2026-10-15 批次 3 设计书 §3.1）。
 *
 * <p>★ 用户原话（2026-10-15，逐字）：
 *
 * <blockquote>
 *
 * 「GMDM都能做那肯定是DM的需审批明命令啊，按照你所说的改吧」
 *
 * </blockquote>
 *
 * <p>判据是<b>纯派生</b>的（不看工具名、不查表）：{@link ToolRole} 的声明 × {@code gate()} 的**实得档** （派生点见 {@code
 * io.mosire.simos.ga.tools.ToolClassAudit}）：
 *
 * <ul>
 *   <li>{@link #GM_ONLY} = {@code roles() == {GM}} —— ★ {@code gate} <b>不参与判定</b>（DM 够不着 ⇒ 问不问人都一样；
 *       用户 2026-10-15 裁定<b>保留</b>它们既有的 {@code Ask}，故"GM-only 却有 Ask"<b>不是</b>异常）；
 *   <li>{@link #DM_APPROVAL} = {@code roles()} 含 DM 且 {@code gate()} 实得 {@code Ask}；
 *   <li>{@link #DM_FREE} = {@code roles()} 含 DM 且 {@code gate()} 实得 {@code ALLOW}（或无 {@code gate()}
 *       覆写 = 读口）。
 * </ul>
 *
 * <p>★ {@code {DM}} 与 {@code {DM, GM}} 在**分类这一维等价**（都表示"DM 能用"，见上面用户原话）⇒ 判类只看**要不要问人**。 ★
 * <b>其余形态判不出类</b> ⇒ 装配期当场抛（fail-closed，不静默、不跳过）。
 *
 * <p>★★ <b>本枚举不是"第二处声明"</b>：工具<b>不得</b>实现或返回它——它是装配期审计从 {@code roles()} + {@code gate()}
 * <b>派生</b>出来的分类结果（设计书 §3.2），不是工具自报的字段。让每条工具再声明一个 {@code toolClass()} 就是造第二处会漂移的声明，与 {@code
 * ToolRole}（"谁能用"的唯一拼写点）存在的理由正好相反。
 */
public enum ToolClass {

  /** 类1：只有 GM 能用（{@code roles() == {GM}}）。★ 它的 {@code gate()} 是什么一概不参与判定。 */
  GM_ONLY,

  /** 类2：DM 能用，但每次调用都要**过人**（{@code gate()} 实得 {@code Ask}）。 */
  DM_APPROVAL,

  /** 类3：DM 能用且**不必问人**（{@code gate()} 实得 {@code ALLOW}，或无 {@code gate()} 覆写 = 读口）。 */
  DM_FREE
}
