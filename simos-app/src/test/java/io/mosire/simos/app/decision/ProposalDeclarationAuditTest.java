package io.mosire.simos.app.decision;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.spi.DecisionProposable;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>批 K ① 装配期审计 {@link ProposalDeclarationAudit} 的**负向判据**</b>（设计书 §3.3 的 C1 / C2；验收判据
 * <b>V2</b> / <b>V3</b>）。
 *
 * <h2>它补的是哪个洞（★ 这就是本类存在的全部理由）</h2>
 *
 * <p>批 K ① 收官时（提交 {@code 21074edc}）那两条负向判据是拿 <b>仓库外的临时夹具</b>跑出来的 —— 证据在台账里，仓里 <b>没有任何用例守着它们</b>。⇒
 * 谁把 {@code ProposalDeclarationAudit} 的 C1 判据删掉一行（或把 {@code toolRoles()} 的
 * 检查改成"记日志"），<b>整个测试面不会有任何一条红</b>：真装配照样启动（今天的 16 条实现者都含 DM），而"没有 DM 标签的工具
 * 不该出现在提议名单里"这条<b>用户口径</b>就静默失效了。
 *
 * <p>★★ <b>为什么必须合成假工具、而不只在真壳上断言</b>：真壳上那 16 条<b>都</b>含 DM ⇒ "凡实现者必含 DM"在真壳上恒真、 判不出
 * 审计到底有没有在判。要证明判据<b>有判别力</b>，就得造一条<b>违反</b>它、且其余一切正常的工具 —— 这正是本类四条用例的形态： 三条负向（各违反一条判据）+
 * 一条<b>对照组</b>（只把 {@code toolRoles()} 换回 {@code {DM,GM}} ⇒ 同一调用链不抛）。
 *
 * <p>★ <b>本类只调生产审计的公开入口</b>（{@link ProposalDeclarationAudit#requireProposable}），不复制它任何一行判据； 假工具也只实现
 * {@link SimosTool} / {@link DecisionProposable} 两个<b>公开契约</b>。
 */
class ProposalDeclarationAuditTest {

  /**
   * ★★ <b>V2：{@code toolRoles()={GM}} 且实现 {@link DecisionProposable} 的假工具**在工具面上** ⇒ 装配期抛</b>（C1）。
   *
   * <p>★ <b>场景就是 ① 之前那个真实缺陷的形状</b>：{@code ProposalCatalog} 的旧手写清单里有 7 条工具 {@code toolRoles()} 只有
   * {@code {GM}}，却照样能被提议 —— 没有任何东西拦。C1 就是那条"必须拦"的判据。
   *
   * <p>★★ <b>这条用例把 {@code ProposalCatalog} 的成员与工具面配成**一致的**</b>（假工具既在面上、也在目录里）⇒ C2 的两个方向
   * 都<b>满足</b>，唯一的违反是 C1。这样"抛"这件事**只可能**来自 DM 标签那一条判据，不会与"目录/面不一致"混在一起 （否则用例红了也说不清是哪条判据在起作用）。
   *
   * <p>★ <b>报文要求</b>（设计书 §3.3 C1 的"含工具名 + 实际 {@code toolRoles()}"）：工具名与 {@code toolRoles()} 原文都要在
   * 报文里 —— 否则装配故障只报"有工具没打标签"，运维得自己把它找出来。
   */
  @Test
  void aGmOnlyProposableOnTheToolFaceIsRejectedAtAssembly() {
    FakeProposable gmOnly = new FakeProposable("probe.gmOnlyProposable", Set.of(ToolRole.GM));
    ProposalCatalog gmCatalog = new ProposalCatalog(List.of(gmOnly), "Map1");
    ProposalCatalog emptyCatalog = new ProposalCatalog(List.of(), "Map1");

    assertThatThrownBy(
            () ->
                ProposalDeclarationAudit.requireProposable(
                    List.of(gmOnly), List.of(), gmCatalog, emptyCatalog))
        .as("★★ V2：实现 DecisionProposable 却 toolRoles() 不含 DM ⇒ 装配期必须当场抛（fail-closed，不降级成日志）")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("C1")
        .hasMessageContaining("probe.gmOnlyProposable")
        .as("★ 报文必须带 toolRoles() 的**原文**（{GM}）—— 否则只有「某条工具没打标签」这一个信息，定位得靠人翻")
        .hasMessageContaining("[GM]");
  }

  /**
   * ★★ <b>V3：实现者**不在工具面上** ⇒ 装配期抛</b>（C2 的第一方向：目录成员必须能在工具面上取到实例）。
   *
   * <p>★ <b>为什么"不在面上"必须抛而不是跳过</b>：目录里的成员要拿去跑真 {@code preview=true}、还要在执行期被 {@code requireTool} 取出来
   * —— 不在面上就是<b>取不到实例</b>，那条提议从"预览"到"执行"整条路都是空的。静默跳过它 = 决策人看到一条"能提议"的工具却永远提不出东西。
   *
   * <p>★ <b>本用例的两个面都是空的</b> ⇒ C1（"凡实现者必含 DM"）在空集上恒真、不可能触发 ⇒ 抛只可能来自 C2。
   */
  @Test
  void aProposableThatIsNotOnTheToolFaceIsRejectedAtAssembly() {
    FakeProposable offFace =
        new FakeProposable("probe.offFaceProposable", Set.of(ToolRole.DM, ToolRole.GM));
    ProposalCatalog catalogWithOffFaceMember = new ProposalCatalog(List.of(offFace), "Map1");
    ProposalCatalog emptyCatalog = new ProposalCatalog(List.of(), "Map1");

    assertThatThrownBy(
            () ->
                ProposalDeclarationAudit.requireProposable(
                    List.of(), List.of(), catalogWithOffFaceMember, emptyCatalog))
        .as("★★ V3：目录成员不在工具面上 ⇒ 装配期必须抛（预览/执行都取不到实例）")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("C2")
        .hasMessageContaining("probe.offFaceProposable")
        .hasMessageContaining("不在 GM 工具面上");
  }

  /**
   * ★★ <b>V3 的另一半（同一判据的第二方向）：实现者**在面上却不在目录里** ⇒ 装配期抛</b>。
   *
   * <p>★ <b>为什么两个方向都要</b>：只查"目录 ⊆ 面"会漏掉<b>静默的能力丢失</b> —— 一条工具在工具面上声明了 {@code implements
   * DecisionProposable}（= 我该被提议），却被漏在目录外 ⇒ 决策人<b>提不到它</b>，而没有任何症状。 ① 的审计把两个方向都写了（C2 的类注明确列了"装配漏注册 /
   * 目录漏收 / 目录多收"三类漂移）⇒ 测试面把两半都钉住。
   */
  @Test
  void aProposableOnTheFaceThatNeverMadeItIntoTheCatalogIsRejectedAtAssembly() {
    FakeProposable onFace =
        new FakeProposable("probe.onFaceMissingFromCatalog", Set.of(ToolRole.DM, ToolRole.GM));
    ProposalCatalog emptyCatalog = new ProposalCatalog(List.of(), "Map1");

    assertThatThrownBy(
            () ->
                ProposalDeclarationAudit.requireProposable(
                    List.of(onFace), List.of(), emptyCatalog, emptyCatalog))
        .as("★★ V3 反向：声明了可提议却没进目录 ⇒ 装配期必须抛（静默的能力丢失）")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("C2")
        .hasMessageContaining("probe.onFaceMissingFromCatalog")
        .hasMessageContaining("不在目录里");
  }

  /**
   * ★★ <b>对照组（判据的判别力自证）：只把 {@code toolRoles()} 换成 {@code {DM,GM}}，其余一个字不动 ⇒ 同一调用链**不抛**</b>。
   *
   * <p>★★ <b>没有这一条，上面三条"抛"就是假绿</b>：{@code requireProposable} 可能因为<b>任何</b>原因抛（工具面重名、目录里
   * 有别的毛病、参数校验……），而"抛了"就被读成"判据生效"。本条把<b>唯一变量</b>钉成 {@code toolRoles()} 一个：
   *
   * <pre>
   * 同一个假工具名 · 同一份目录 · 同样的两个面 —— 只有 toolRoles() 从 {GM} 换成 {DM,GM} ⇒ 从"必抛"变成"必不抛"
   * </pre>
   *
   * ⇒ "抛"这件事与 DM 标签<b>一一对应</b>，而不是与整个夹具的复杂度对应。
   */
  @Test
  void aDmProposableOnTheFaceAndInTheCatalogPassesTheAudit() {
    FakeProposable dmProposable =
        new FakeProposable("probe.sameNameDifferentRoles", Set.of(ToolRole.DM, ToolRole.GM));
    ProposalCatalog catalog = new ProposalCatalog(List.of(dmProposable), "Map1");
    ProposalCatalog emptyCatalog = new ProposalCatalog(List.of(), "Map1");

    assertThatCode(
            () ->
                ProposalDeclarationAudit.requireProposable(
                    List.of(dmProposable), List.of(), catalog, emptyCatalog))
        .as("★ 对照组：toolRoles() 含 DM ⇒ 同一条调用链必须**不抛**（否则上面三条负向用例的'抛'没有判别力）")
        .doesNotThrowAnyException();

    FakeProposable sameNameGmOnly =
        new FakeProposable("probe.sameNameDifferentRoles", Set.of(ToolRole.GM));
    ProposalCatalog gmOnlyCatalog = new ProposalCatalog(List.of(sameNameGmOnly), "Map1");
    assertThatThrownBy(
            () ->
                ProposalDeclarationAudit.requireProposable(
                    List.of(sameNameGmOnly), List.of(), gmOnlyCatalog, emptyCatalog))
        .as("★★ 判别力：**同一个工具名**、同一份调用链，只把 toolRoles() 换成 {GM} ⇒ 当场抛（唯一的变量就是 DM 标签）")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("[GM]");
  }

  /**
   * ★ 假工具：只声明 {@code name()} / {@code toolRoles()}（{@link io.mosire.agentlib.tool.AgentTool}
   * 的其余方法是缺省实现）， 外加 {@link DecisionProposable} 的"我可被提议"声明。★ 它<b>不执行</b>任何东西 —— 审计只读声明，不跑工具。
   *
   * <p>★ 组件名必须叫 {@code toolRoles}（不是 {@code roles}）：0.2.1 起 {@code AgentTool} 有同名的 {@code roles():
   * Set<String>}，record 访问器若还叫 {@code roles} 就会**先撞上库的方法**（返回类型不兼容）。
   */
  private record FakeProposable(String name, Set<ToolRole> toolRoles)
      implements SimosTool, DecisionProposable {

    @Override
    public ToolResult execute(ToolContext context) {
      throw new UnsupportedOperationException("测试夹具：不执行");
    }
  }
}
