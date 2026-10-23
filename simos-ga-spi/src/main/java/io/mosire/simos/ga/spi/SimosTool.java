package io.mosire.simos.ga.spi;

import io.mosire.agentlib.tool.AgentTool;
import java.util.Set;
import java.util.TreeSet;

/**
 * 模拟世界的工具接口：agentlib 的 {@link AgentTool} **收窄 + 角色声明**。
 *
 * <p>★★ <b>它取代今天"三处手抄"</b>（设计书 §4.4「一处声明、三处派生」；现状见 §3 的 F3/F4）：
 *
 * <ol>
 *   <li>GM 桶 —— 今天 {@code SimosToolSource} 里一份 {@code built.add(...)} 清单；
 *   <li>决策人桶 —— 同一份工具在 {@code SimosToolSource} 里**再注册一次**（同一工具两个桶各写一遍）；
 *   <li>权限组白名单 —— 同一批工具名**再抄一遍**（44 条字面量；B4b 已删，改为从决策人桶派生）。
 * </ol>
 *
 * <p>{@code SimosToolSource} 自己的注释已写明"只改一处 = 留旁路"——本接口就是那个"一处"： 桶与白名单**从 {@link #toolRoles()}
 * 派生**（B4 收口），不许再有第 2/3 处手抄（不变量 I-G3）。
 *
 * <p>★ <b>B1 只立契约</b>；B3 下放 + B4 收口已把 176 个工具类**全部接上**本接口（设计书 §6 批次表），B4b 删掉了最后一份手抄白名单。
 *
 * <p>★ <b>为什么不在这里声明审批相关的方法</b>：审批链按**装配**决定（GM 面 {@code GmAutoApproveGate}、 DM 面 {@code
 * ConfirmGate}），工具只声明"谁能用"（设计书 §4.4 第 4 条）。把它写进接口 = 第二处真相。
 *
 * <p>★★ <b>2026-10-17 AgentLib 0.2.1 升级：角色声明方法 {@code roles()} → {@link #toolRoles()} 更名</b>（分支
 * {@code agentlib/dock-0.2.1}）。原因：0.2.0 起库的 {@link AgentTool} 新增角色维 {@code default Set<String>
 * roles()}（空集 = 本维不表态），与 simos 既有的 {@code roles(): Set<ToolRole>} **同名同擦除、返回类型不兼容** ⇒
 * 升级当场编译红（`simos-ga-spi` 第一站）。枚举值的唯一拼写点不变（{@link ToolRole}），只让出方法名。
 *
 * <p>★★ <b>2026-10-17 批 R：本接口把角色声明派生到库的角色维</b>（{@link #roles()}，计划 §4.1）。0.2.1 升上来时本接口**刻意不**覆写
 * {@code AgentTool.roles()}：库的角色维在**调用者侧**空集 = ⊥ = "谁都不是"（fail-closed），而当时 simos 的调用者权限集还没带角色名 ⇒
 * 单方面把 258 条工具都声明成 {@code {GM}}/{@code {DM}} 会把 GM/DM 的**全部**工具调用拒掉（deny-all，行为变化）。批 R 把四件**同批**
 * 接通：声明派生（{@link #roles()}）→ 调用者权限集挂角色（决策人 C1 / GM C2）→ 记录壳转发 → 装配期两个独立观察面的审计 （{@code
 * ToolRoleAudit.requireFacesOnAgentTools} + {@code RoleCatalogAudit}）。★ 唯一拼写点仍是 {@link
 * #toolRoles()}： **任何实现类不得直接覆写 {@link #roles()}**。
 */
public interface SimosTool extends AgentTool {

  /**
   * "这个工具能被谁用"的**唯一拼写点**（设计书 §4.4）。
   *
   * <p>★ 返回**非空**集合：空集 = 谁都看不见 = 装配故障（B4 的收口判据应当把它变成编译期或测试期红， 而不是让它静默地什么都不注册）。★ 集合必须**不可变**（{@code
   * Set.of(...)} / {@code EnumSet} 的副本）， 免得注册面把它当可变容器改出第二份真相。
   *
   * @return 该工具的角色标签；{@code {GM}} / {@code {DM}} / {@code {GM, DM}} 三种合法形态
   */
  Set<ToolRole> toolRoles();

  /**
   * ★★ <b>库的角色维（声明侧）的派生值</b>（2026-10-17 批 R，计划 §4.1 / 审核 §7）：返回 {@link #toolRoles()} 的**枚举名**集合
   * （{@link ToolRole#name()}），不可变。
   *
   * <p>★ <b>为什么在这里派生而不是让 185 个实现类各写一遍</b>：角色名对 AgentLib 是**不透明字符串标签**（库不认识 {@code GM}/{@code DM}），而
   * simos 的角色拼写点只有一个 —— {@link ToolRole}。多写一处 {@code "GM"} 就多一处会漂移的第二真相 （审核 §7：调用者与审计处同样一律从 {@link
   * ToolRole#name()} 派生）。⇒ 实现类**一行不改**，也不得直接覆写本方法。
   *
   * <p>★ <b>语义与调用者侧相反</b>（库 {@code AgentTool#roles()} 的契约）：本方法非空（{@code toolRoles()} 的契约就是非空）⇒
   * 参与判定且只收窄； 记录壳（{@code RecordingToolSource.RecordingTool}）必须原样转发它，否则包装后声明变成空集 = 本维不表态 ⇒
   * 角色判定被静默跳过。
   *
   * @return {@link #toolRoles()} 的枚举名集合（{@code Set.copyOf} 的不可变快照）
   */
  @Override
  default Set<String> roles() {
    // TreeSet 收集（审核 §7 的形状）：先按枚举名去重/定型，再交给 Set.copyOf 冻结。
    Set<String> names = new TreeSet<>();
    for (ToolRole role : toolRoles()) {
      names.add(role.name());
    }
    return Set.copyOf(names);
  }
}
