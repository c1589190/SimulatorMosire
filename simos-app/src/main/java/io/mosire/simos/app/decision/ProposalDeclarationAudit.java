package io.mosire.simos.app.decision;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.simos.ga.spi.DecisionProposable;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>「可被决策人 propose」的装配期 fail-closed 审计</b>（批 K ① 根治版，设计书 §3.3；与 {@code
 * io.mosire.simos.ga.tools.ToolClassAudit} 同址同制：装配完成处一次 O(n)、判不出<b>当场抛</b>、不降级成日志）。
 *
 * <p>★ <b>它补的是哪一块</b>：{@link ProposalCatalog} 的名单此前是**第二处手写清单**（16 条 {@code new XxxTool(...)}），
 * 与工具自己的 {@code roles()} 互不相干 —— 原始清单里 7 条只有 {@code {GM}} 却照样能被提议，**没有任何东西拦**。批 K ①
 * 把名单改成从工具面派生（{@code instanceof DecisionProposable}）之后，本类把那两处声明**钉在一起**：
 *
 * <table>
 *   <caption>判据</caption>
 *   <tr><th>#</th><th>判据</th><th>违反时</th></tr>
 *   <tr><td>C1</td><td>工具面上每个 {@link DecisionProposable} 实现者的 {@code roles()} <b>必须含 {@link
 *       ToolRole#DM}</b>（= 用户口径「没有 DM 标签的工具不该出现在提议名单里」）</td><td>{@link IllegalStateException}（含工具名 + 实际
 *       {@code roles()} + 指路）</td></tr>
 *   <tr><td>C2</td><td>目录成员 <b>⇔</b> 工具面上实现了该接口的工具，<b>双向逐条相等</b>（不重不漏）：目录里每条工具都必须**在工具面上**（否则取不到实例
 *       —— 预览与执行都无从谈起），工具面上每个实现者也必须**在目录里**（否则"声明了可提议"却进不去，是静默的能力丢失）</td><td>{@link
 *       IllegalStateException}</td></tr>
 * </table>
 *
 * <p>★★ <b>C2 的边界（如实记，不许当成比它更强的判据）</b>：它比的是**装配面 vs 目录**这两份"活的"数据，因此能抓住"装配漏注册 / 目录漏收 /
 * 目录多收"三类漂移；它<b>抓不住</b>"某个类实现了 {@link DecisionProposable} 却**在任何工具面上都没被实例化**"——
 * 那种类在运行期根本不存在对象可比（要抓它只能扫源码或扫 classpath，两者都不是装配期的可靠手段：生产跑的是 shaded jar）。⇒ 这一条靠**用例** （源码/工具面扫描）守，见批
 * K 的验收判据 V3 的测试面。
 *
 * <p>★ <b>为什么本类住在 {@code simos-app}</b>：它要同时认识三个东西——{@link DecisionProposable}（{@code ga-spi}
 * 契约）、{@link ProposalCatalog}（住 app 的决策包）、以及两桶的工具面（装配面）。{@code simos-ga} 看不见 {@code
 * ProposalCatalog}（那是 {@code app → ga → app} 环）⇒ 与 {@code ToolClassAudit} 不同址，但**同制**。
 *
 * <p>★ <b>本类只读</b>：不删任何 {@code Ask}、不改任何 {@code roles()}、不动任何桶的成员；它唯一的动作是"判 + 抛"。
 */
public final class ProposalDeclarationAudit {

  private ProposalDeclarationAudit() {}

  /**
   * ★★ <b>装配完成处的一次全量审计</b>（C1 + C2）：判据见类注的表。
   *
   * @param gmFace GM 桶工具面（含急停协议那三条；非 null）
   * @param decisionFace 决策人桶工具面（非 null）
   * @param gmCatalog GM 档的可提议目录（{@code SimosToolSource.proposalCatalog()}；非 null）
   * @param decisionCatalog 决策人档的可提议目录（同上；非 null）
   * @throws IllegalStateException C1 或 C2 被违反（消息里带工具名、实际 {@code roles()} 与修法指向）
   */
  public static void requireProposable(
      List<AgentTool> gmFace,
      List<AgentTool> decisionFace,
      ProposalCatalog gmCatalog,
      ProposalCatalog decisionCatalog) {
    Objects.requireNonNull(gmFace, "gmFace");
    Objects.requireNonNull(decisionFace, "decisionFace");
    Objects.requireNonNull(gmCatalog, "gmCatalog");
    Objects.requireNonNull(decisionCatalog, "decisionCatalog");
    // C1：先扫两桶并集（**声明**那一侧）——把"实现者却不含 DM"当场拦下。★ 与 ToolClassAudit 同款：
    //   桶内重名/「桶 ⇔ roles()」不归本类（那是 ToolRoleAudit.requireFaces 的活），本类一个字不放松它。
    requireDmRole(unionOf(gmFace, decisionFace));
    // C2：再把目录与工具面**双向**对上（C2 的两个方向各一次，两个档都查）。
    requireCatalogMatchesFace(gmCatalog, gmFace, "GM");
    requireCatalogMatchesFace(decisionCatalog, decisionFace, "决策人");
  }

  /** 两桶并集（保序；GM 桶在前，决策人桶里 GM 桶没有的那些追加在后）。 */
  private static Map<String, AgentTool> unionOf(
      List<AgentTool> gmFace, List<AgentTool> decisionFace) {
    Map<String, AgentTool> union = new LinkedHashMap<>();
    for (AgentTool tool : gmFace) {
      union.putIfAbsent(tool.name(), tool);
    }
    for (AgentTool tool : decisionFace) {
      union.putIfAbsent(tool.name(), tool);
    }
    return union;
  }

  /**
   * ★★ <b>C1</b>：工具面上每个 {@link DecisionProposable} 实现者的 {@code roles()} 必须含 {@link ToolRole#DM}。
   *
   * <p>★ <b>为什么判据取自工具自己的两处声明而不是任何名单</b>：「我能不能被提议」= {@code implements DecisionProposable}； 「DM
   * 够不够得着」= {@code roles()}。两者都住在工具类里 ⇒ 本判据没有可以漂移的第三方。
   *
   * <p>★ {@code roles()} 的契约守卫（非 null、非空、必须实现 {@link SimosTool}）**不在这里重查**：那是 {@code ToolRoleAudit}
   * 与 {@code ToolClassAudit} 已经钉死的前置（同一个装配点、同一次 O(n)）；本类只在那两个前置之后追加"实现者必须含 DM"这一条。
   */
  private static void requireDmRole(Map<String, AgentTool> union) {
    List<String> faults = new ArrayList<>();
    for (AgentTool tool : union.values()) {
      if (!(tool instanceof DecisionProposable)) {
        continue;
      }
      Set<ToolRole> roles = tool instanceof SimosTool simosTool ? simosTool.roles() : Set.of();
      if (!roles.contains(ToolRole.DM)) {
        faults.add(tool.name() + "（" + tool.getClass().getName() + "）的 roles() = " + roles);
      }
    }
    if (!faults.isEmpty()) {
      throw new IllegalStateException(
          "可提议工具装配故障（C1）：下列工具实现了 DecisionProposable（= 声明自己可被决策人 propose），但 roles() **不含 DM** —— "
              + String.join("；", faults)
              + "。★ 用户口径（2026-10-15）：「有 DM 标签的工具」才谈得上 DM 提议；没有 DM 标签的工具不该出现在提议名单里。"
              + "★ 不要把它从工具面或目录里静默跳过、也不要把本审计降级成日志：要么给它的 roles() 补上 DM（若它本来就该由 DM 提议），"
              + "要么让它**别再实现** DecisionProposable（若它不该被提议）—— 两条路都在工具类里，别在装配面绕。");
    }
  }

  /**
   * ★★ <b>C2</b>：目录成员 ⇔ 工具面上实现了 {@link DecisionProposable} 的工具（双向逐条相等）。
   *
   * @param catalog 该档的可提议目录
   * @param face 该档的工具面
   * @param faceLabel 报错信息里的档名（{@code GM} / {@code 决策人}）
   */
  private static void requireCatalogMatchesFace(
      ProposalCatalog catalog, List<AgentTool> face, String faceLabel) {
    Set<String> onFace = new LinkedHashSet<>();
    Set<String> declaredOnFace = new LinkedHashSet<>();
    for (AgentTool tool : face) {
      onFace.add(tool.name());
      if (tool instanceof DecisionProposable) {
        declaredOnFace.add(tool.name());
      }
    }
    List<String> inCatalog = catalog.toolNames();
    List<String> faults = new ArrayList<>();
    for (String name : inCatalog) {
      if (!onFace.contains(name)) {
        faults.add("目录成员 " + name + " **不在 " + faceLabel + " 工具面上**");
      }
    }
    Set<String> catalogNames = new LinkedHashSet<>(inCatalog);
    for (String name : declaredOnFace) {
      if (!catalogNames.contains(name)) {
        faults.add("工具面上的 " + name + " 实现了 DecisionProposable，却**不在目录里**");
      }
    }
    if (!faults.isEmpty()) {
      throw new IllegalStateException(
          "可提议工具装配故障（C2）：目录成员与 "
              + faceLabel
              + " 工具面上的 DecisionProposable 实现者不是同一批 —— "
              + String.join("；", faults)
              + "。★ 目录的成员**只能**由工具面派生（设计书 §3.2 不变量 K1）：不在工具面上 = 取不到实例（预览/执行都无从谈起）；"
              + "在工具面上却没进目录 = 声明了可提议却静默进不去。★ 不要靠手工往目录里补名字：要修的要么是装配面的注册，要么是工具自己的声明。");
    }
  }
}
