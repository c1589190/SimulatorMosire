package io.mosire.simos.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.approval.ApprovalDecision;
import io.mosire.agentlib.approval.ApprovalRequest;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.docs.DocsCapability;
import io.mosire.docs.DocsTools;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.tools.SimosToolSource;
import io.mosire.simos.app.world.SmallWorld;
import io.mosire.simos.ga.GaSnapshot;
import io.mosire.simos.ga.model.DecisionMaker;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.SimosToolAdapter;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.tools.ToolBuckets;
import io.mosire.simos.ga.tools.ToolRoleAudit;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import io.mosire.workspace.CapabilityCatalog;
import io.mosire.workspace.CapabilityCatalogs;
import io.mosire.workspace.DefaultWorkspaceRegistry;
import io.mosire.workspace.Mount;
import io.mosire.workspace.WorkspaceRegistry;
import io.mosire.workspace.WorkspaceTools;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ★★ <b>2026-10-14 AgentLib Docs/Workspace 接入 —— 测试面判据（设计书 §6.2 P1/P3/P4/P5、§5 I3/I6、§6.4
 * N1/N2）</b>。
 *
 * <p>本类补齐的是"<b>接线真的通了</b>"这一层：不是"桶里多了一个名字"，而是 ① 适配后的两个面能装配（{@link ToolRoleAudit#requireFaces} 不抛）；
 * ② ★ <b>决策人在自己的工作区里能自由记录文档</b>（{@code doc_create} → {@code doc_read} 往返内容一致 ——
 * 用户点名的原话「测试一下新agentlib体系能不能让dm自由记录文档」）； ③ ★ <b>审批摘要只含路径</b>（不泄漏正文/owner/tags）； ④ 委托保真（I3）与角色表
 * fail-closed（I6）； ⑤ 切片隔离（DM2 读 DM1 的地 ⇒ 同码的"不可见/不存在"）。
 *
 * <p>★★ <b>装置说明（为什么必须"起两次壳"）</b>：装配期的逐 DM 默认工作区是在 {@code Shell.start} 的注册面之后 <b>读世界</b>拿决策人清单的 ⇒
 * 要让某个 DM 有默认工作区，store 里<b>必须先有</b>那个决策人。 而小世界创世<b>不带任何决策人</b>（实测 {@code after-genesis
 * DMs=[]}）。故本类的夹具是：
 *
 * <pre>
 * ① 空库起壳 → bootstrap small-world → GM 经真工具 ga.CreateDecisionMaker 建两个决策人 → 关壳
 * ② 非空 store 再起壳 ⇒ 装配期为两个 DM 各挂一块默认工作区（真权限面 + 真工具门）
 * </pre>
 *
 * ★ <b>为什么单次空库起壳测不到</b>：空库时 {@code currentDecisionMakerIds} 提前返回空集 ⇒ 根本不走逐 DM 挂载。 这是本批已踩过的坑（实现方账本
 * §13.4：「空库探针测不到『已有世界』的路径」）。
 *
 * <p>★ <b>DM 工具调用的两个 authorizer</b>：判"能不能做"用 {@code gmToolAuthorizer()}（自动放行 ——
 * 否则没人点审批会阻塞到审批超时），<b>身份与权限仍是 DM 的</b>（{@code DecisionCallerFactory.callerFor} 现算）； 只有 P4
 * 那条<b>专门测审批链本身</b>的用例走真决策人 authorizer（{@code shell.toolAuthorizer()}）并当场代为批准。
 */
class AgentLibDocsWiringTest {

  /**
   * ★★ <b>决策人 id 必须每个用例唯一</b>：{@code SimosToolSource.ExternalWorkspaceHost} 的工作区注册表是
   * <b>进程内静态单例</b>（它的理由见实现方账本 D2）⇒ 同一个 JVM 里多个 {@code Shell} 共享同一张挂载表， 而逐个用例的 {@code @TempDir}
   * 各不相同。若两个用例用同一个 DM id，后者的"装配期挂载"会被幂等判据跳过、 指向<b>前一个用例已清理的目录</b> ⇒ 假红/假绿。故 id 带每实例唯一后缀。
   */
  private final String suffix = Long.toHexString(System.nanoTime());

  private final String dmOneId = "dm-one-" + suffix;

  private final String dmTwoId = "dm-two-" + suffix;

  private static final String DM_WORKSPACE = SimosToolSource.DEFAULT_WORKSPACE_NAME;

  /** 装配期挂载的物理子目录约定（{@code <store>/workspaces/dm/<id>}）。 */
  private static final String DOC_RELATIVE = "notes/first.md";

  private static final String DOC_BODY = "DM 的第一次记录：AgentLib 文档通路已接通";

  private static final long APPROVAL_WAIT_MILLIS = 20_000L;

  @TempDir Path tempDir;

  // ── P1 / P5 ───────────────────────────────────────────────────────────────────────────────

  /**
   * ★ <b>P1 + P5</b>：适配后的两个面<b>能装配</b>（{@code requireFaces} 不抛、双向成员⇔声明一致）， 且角色表把 {@code doc_*}
   * 放进决策人桶、把 {@code mount_workspace} 挡在决策人桶外（GM-only）。
   *
   * <p>★ 为什么这条同时是 P1 与 P5：{@code requireFaces} 校验的是"桶成员 ⇔ roles() 声明"的**双向**一致 —— 一条工具多标了 DM
   * 却没进决策人桶、或没标 DM 却进了桶，都会当场红。所以"能装配"本身就证明了角色表自洽。
   */
  @Test
  void p1AndP5_adaptedFacesAssembleAndWorkspaceToolsStayGmOnly() {
    try (Shell shell = startShell(emptyStore())) {
      List<AgentTool> gmFace = shell.toolsFor(SimosToolSource.Role.GM);
      List<AgentTool> dmFace = shell.toolsFor(SimosToolSource.Role.DECISION_AGENT);

      assertThatCode(() -> ToolRoleAudit.requireFaces(gmFace, dmFace))
          .as("P1：适配后两桶装配通过（roles() ⇔ 桶成员双向一致；外来工具已套 SimosToolAdapter）")
          .doesNotThrowAnyException();

      List<String> gmNames = namesOf(gmFace);
      List<String> dmNames = namesOf(dmFace);

      // P5 正向：6 条决策人可用的 doc 工具都在决策人桶
      assertThat(dmNames)
          .as("P5：决策人桶含 7 条 doc 工具（doc_list/read/query/append/create/edit + doc_guide）")
          .contains(
              DocsTools.DOC_LIST,
              DocsTools.DOC_READ,
              DocsTools.DOC_QUERY,
              DocsTools.DOC_APPEND,
              DocsTools.DOC_CREATE,
              DocsTools.DOC_EDIT)
          // ★★ 2026-10-14 上游 R8（控制方裁定 §11.3）：能力自述工具 {GM, DM} + gate=ALLOW。
          .contains(DocsTools.DOC_GUIDE, WorkspaceTools.WORKSPACE_INFO);
      assertThat(gmNames)
          .as("P1：GM 桶含全部 8 条 doc 工具（多一条 doc_void）")
          .contains(
              DocsTools.DOC_LIST,
              DocsTools.DOC_READ,
              DocsTools.DOC_QUERY,
              DocsTools.DOC_APPEND,
              DocsTools.DOC_CREATE,
              DocsTools.DOC_EDIT,
              DocsTools.DOC_GUIDE,
              DocsTools.DOC_VOID);

      // P5 反向：GM-only 的四条**不在**决策人桶
      assertThat(dmNames)
          .as("★ P5：mount_workspace 不在决策人桶（工作区由 GM 维护，U1）")
          .doesNotContain(WorkspaceTools.MOUNT_WORKSPACE);
      assertThat(dmNames)
          .as("★ P5：unmount_workspace / list_workspaces 同样不在决策人桶")
          .doesNotContain(WorkspaceTools.UNMOUNT_WORKSPACE, WorkspaceTools.LIST_WORKSPACES);
      assertThat(dmNames)
          .as("★ P5：doc_void 不在决策人桶（作废别人的记录是 GM 动作）")
          .doesNotContain(DocsTools.DOC_VOID);

      // 角色声明的逐条核对（不是"桶里有名字"就算 —— 名字的角色标注也要对）
      assertThat(rolesOf(gmFace, WorkspaceTools.MOUNT_WORKSPACE))
          .as("mount_workspace 的角色声明恰为 {GM}")
          .containsExactly(ToolRole.GM);
      assertThat(rolesOf(dmFace, DocsTools.DOC_CREATE))
          .as("doc_create 的角色声明含 DM（否则它进不了决策人桶）")
          .contains(ToolRole.DM);
    }
  }

  // ── I3 ────────────────────────────────────────────────────────────────────────────────────

  /**
   * ★ <b>I3 委托保真</b>：适配层的七条读口<b>逐字等于被包装的原生工具</b>—— {@code name / description / jsonSchema / spec /
   * resources} 逐项 <b>equal</b>， 而 {@code delegate()} 就是那个原生实例。
   *
   * <p>★ 为什么这样测就够：适配层的全部意义就是"只补角色与审批这两件事，其余原样转"。任何一条读口被改写 （哪怕只改一个描述字），这里当场红。★ 与"重新 {@code
   * DocsTools.all(...)} 造一个原生实例再比"相比， 直读 {@code delegate()} 更强：它比的是<b>被包装的那一个实例</b>，不是"同类的另一个实例"。
   */
  @Test
  void i3_adapterDelegatesEveryAccessorToTheNativeTool() {
    try (Shell shell = startShell(emptyStore())) {
      List<AgentTool> adapted =
          shell.toolsFor(SimosToolSource.Role.GM).stream()
              .filter(tool -> isExternal(tool.name()))
              .toList();
      assertThat(adapted)
          .as("前提：GM 桶里确实有 12 条外来工具（10 条 + 上游 R8 的 doc_guide / workspace_info；否则本用例测了个空集）")
          .hasSize(12);

      for (AgentTool tool : adapted) {
        assertThat(tool)
            .as("%s 必须是适配器实例（桶里放原生 AgentTool = 装配故障，见 N1）", tool.name())
            .isInstanceOf(SimosToolAdapter.class);
        SimosToolAdapter adapter = (SimosToolAdapter) tool;
        AgentTool nativeTool = adapter.delegate();

        assertThat(adapter.name()).as("%s：name 逐字委托", tool.name()).isEqualTo(nativeTool.name());
        assertThat(adapter.description())
            .as("%s：description 逐字委托", tool.name())
            .isEqualTo(nativeTool.description());
        assertThat(adapter.jsonSchema())
            .as("%s：jsonSchema 逐字委托（我们的模型看到的就是他们的 schema）", tool.name())
            .isEqualTo(nativeTool.jsonSchema());
        assertThat(adapter.spec()).as("%s：spec 逐字委托", tool.name()).isEqualTo(nativeTool.spec());
        assertThat(adapter.resources())
            .as("%s：resources 逐字委托（权限判定靠它，改了就判错）", tool.name())
            .isEqualTo(nativeTool.resources());
        // ★ 原生实例真的来自隔壁 jar（不是我们重新实现的同名工具）
        assertThat(nativeTool.getClass().getName())
            .as("%s 的原生类应住在隔壁 jar（io.mosire.docs / io.mosire.workspace）", tool.name())
            .matches("io\\.mosire\\.(docs|workspace)\\..*");
      }
    }
  }

  // ── I6（+ N2 的载体） ──────────────────────────────────────────────────────────────────────

  /**
   * ★ <b>I6 角色表逐条、无缺省</b>：一个<b>不在表里</b>的工具名 ⇒ <b>当场抛</b>（不是拿一个默认角色继续装配）。
   *
   * <p>★ <b>为什么用反射</b>：那处 fail-closed 判据在 {@code SimosToolSource.rolesOf(String)} 里，是 {@code
   * private static}（它只服务装配期的逐条查表，生产上不该有第二个调用点）。 本用例要打的正是"<b>未知名字</b>"这条路径 —— 只能从私有口进。★ 这条判据的判别力由变异
   * N2 （把未知名改成返回默认角色）当场证明：那时本用例红。
   */
  @Test
  void i6_unknownExternalToolNameHasNoDefaultRole() throws Exception {
    Method rolesOf = SimosToolSource.class.getDeclaredMethod("rolesOf", String.class);
    rolesOf.setAccessible(true);

    assertThatThrownBy(() -> invoke(rolesOf, "no.such.external.tool"))
        .as("★ I6：未知外来工具名必须当场抛（有缺省角色 = 权限面悄悄变化）")
        .rootCause()
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不许有缺省角色");

    // ★ 正向对照：表里的名字照样查得到（否则上面那条"抛"可能只是"方法坏了"）
    for (String known : externalToolNames()) {
      assertThat(invoke(rolesOf, known))
          .as("%s 在角色表里（逐条声明）", known)
          .isInstanceOf(java.util.Set.class);
    }
  }

  // ── N1 ────────────────────────────────────────────────────────────────────────────────────

  /**
   * ★ <b>N1（负向）</b>：把<b>原生</b> {@code AgentTool}（不套适配层）塞进桶 ⇒ <b>装配当场抛</b>。
   *
   * <p>⇒ 证明适配层是<b>必需</b>的，不是"可选包装"：原生工具面没有 {@code roles()} 这个概念 （{@code ToolRole} 在隔壁 jar 里 0
   * 命中），而"这个工具能被谁用"缺唯一拼写点 ⇒ 必须响亮失败。
   */
  @Test
  void n1_rawVendorToolInBucketIsRejectedAtAssembly() {
    List<AgentTool> natives = nativeDocsTools();
    assertThat(natives).as("前提：隔壁真的给了一批原生工具").isNotEmpty();

    assertThatThrownBy(() -> ToolRoleAudit.requireFace(natives, ToolRole.GM))
        .as("★ N1：原生工具（未套适配层）入桶 ⇒ 装配当场抛（这就是「适配层必需」的证明）")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("没有声明 SimosTool.roles()");

    // ★ 对照：同一批工具**套上**适配层之后就能过（证明上面抛的是"缺 roles()"，不是别的）
    List<AgentTool> adapted =
        natives.stream()
            .map(
                tool ->
                    (AgentTool)
                        new SimosToolAdapter(
                            tool, java.util.Set.of(ToolRole.GM), ctx -> ToolGate.ALLOW))
            .toList();
    assertThatCode(() -> ToolRoleAudit.requireFace(adapted, ToolRole.GM))
        .as("对照：同一批工具套上适配层后装配通过")
        .doesNotThrowAnyException();
  }

  // ── P3（用户点名的那件事） ─────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>P3 主判据</b>：决策人在<b>自己的工作区</b>里 {@code doc_create} → {@code doc_read}，
   * <b>读回的内容与写入的逐字一致</b>。
   *
   * <p>用户原话（2026-10-14）：「测试一下新agentlib体系能不能让dm自由记录文档」——本用例就是那句话的可执行形态： 真壳装配 + 真权限面 + 真工作区挂载 + 真工具门
   * + 真 DM 身份，往返内容逐字比对。
   *
   * <p>★ 覆盖面（缺一条这条判据就是假的）：① DM 真的有默认工作区（装配期挂载，不靠 GM 手工）； ② 写入走的是<b>DM 身份</b>（{@code
   * identity.instanceId() == decision-maker:dm-one}，I9）； ③ 读回正文与写入逐字相等（不是"成功"两个字）。
   */
  @Test
  void p3_decisionMakerCreatesAndReadsBackItsOwnDocument() {
    try (Shell shell = startShell(seedStoreWithTwoDecisionMakers())) {
      DecisionMaker dmOne = decisionMaker(shell, dmOneId);
      DecisionCallerFactory factory = decisionFactory(shell);
      ToolRegistry dmRegistry = decisionRegistry(shell);

      // ① 装配期已为该 DM 挂上默认工作区（不是靠 GM 逐次手工）
      List<Mount> mounts = SimosToolSource.mountsOf(userKey(dmOneId));
      assertThat(mounts).as("① 装配期为 %s 挂了默认工作区（用户裁定：每个决策人开跑即有一块自己的地）", dmOneId).isNotEmpty();
      assertThat(mounts.get(0).user()).as("I9：挂载的 user 必须等于决策人身份键").isEqualTo(userKey(dmOneId));

      SimulationState state = headState(shell);
      ToolContext ctx = factory.callerFor(dmOne, state, mapId());
      assertThat(ctx.identity().instanceId())
          .as("② 调用身份是决策人自己（不是 GM 代劳）")
          .isEqualTo(userKey(dmOneId));

      // ③ 写入
      ToolResult created =
          factory.execute(
              dmRegistry,
              DocsTools.DOC_CREATE,
              dmOne,
              state,
              mapId(),
              Map.of("mount", DM_WORKSPACE + "/" + DOC_RELATIVE, "text", DOC_BODY));
      assertThat(created.success())
          .as("③ DM 在自己的工作区里 doc_create 成功；实得 %s", created.message())
          .isTrue();

      // ④ 读回，正文逐字相等
      ToolResult read =
          factory.execute(
              dmRegistry,
              DocsTools.DOC_READ,
              dmOne,
              state,
              mapId(),
              Map.of("mount", DM_WORKSPACE + "/" + DOC_RELATIVE));
      assertThat(read.success()).as("④ doc_read 成功；实得 %s", read.message()).isTrue();
      assertThat(read.message()).as("★★ P3：读回的正文与写入的逐字一致（「往返内容一致」的可执行形态）").contains(DOC_BODY);
    }
  }

  // ── P4 ────────────────────────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>P4 主判据（端到端）</b>：触发 {@code doc_create} 的审批 ⇒ 抓真 {@link ApprovalRequest}， 断言 {@code
   * summary} <b>只出现路径</b>，<b>不出现正文 / owner / tags / 查询条件</b>。
   *
   * <p>★ <b>为什么必须端到端</b>：summary 是我们喂给审批链的字符串，但它会经 AgentLib 的编排器搬进 {@link ApprovalRequest}。只断言
   * {@code gate().summary()} 测的是"我们填了什么"，端到端测的是 "<b>GM 最终看到什么</b>"——U3 要求的正是后者。
   *
   * <p>★ 走<b>真决策人 authorizer</b>（{@code shell.toolAuthorizer()}）：它的链是 {@code AutoApproveGate →
   * ConfirmGate}，没人点会阻塞到审批超时 ⇒ 本用例在虚线程里发起调用、 主线程轮询 {@code pendingApprovals()} 抓请求并当场批准（形制照 {@code
   * RunDecisionEndToEndTest}）。
   */
  @Test
  void p4_approvalSummaryCarriesOnlyThePath() throws Exception {
    try (Shell shell = startShell(seedStoreWithTwoDecisionMakers())) {
      DecisionMaker dmOne = decisionMaker(shell, dmOneId);
      // ★ 用真决策人 authorizer（不是 gmToolAuthorizer）—— 本用例测的就是审批链本身
      DecisionCallerFactory factory =
          DecisionCallerFactory.forDecisionFace(
              shell.toolAuthorizer(),
              ToolBuckets.namesOf(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT)));
      ToolRegistry dmRegistry = decisionRegistry(shell);
      SimulationState state = headState(shell);

      String path = DM_WORKSPACE + "/" + DOC_RELATIVE;
      // 参数里塞满"绝不许进 summary"的东西：正文、owner、tags、查询条件
      Map<String, Object> args = new LinkedHashMap<>();
      args.put("mount", path);
      args.put("text", DOC_BODY);
      args.put("owner", "self");
      args.put("tags", List.of("secret-tag"));
      args.put("affiliations", List.of("secret-affiliation"));
      args.put("from", 1);
      args.put("to", 999);

      FutureTask<ToolResult> call =
          new FutureTask<>(
              () -> factory.execute(dmRegistry, DocsTools.DOC_CREATE, dmOne, state, mapId(), args));
      Thread.ofVirtual().name("t12-p4-doc-create").start(call);

      ApprovalRequest captured = null;
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(APPROVAL_WAIT_MILLIS);
      while (System.nanoTime() < deadline && captured == null) {
        for (ApprovalRequest pending : shell.pendingApprovals().pending()) {
          if (DocsTools.DOC_CREATE.equals(pending.tool())) {
            captured = pending;
            shell
                .pendingApprovals()
                .decide(pending.id(), ApprovalDecision.APPROVE_ONCE, "test:task-12");
            break;
          }
        }
        if (captured == null && call.isDone()) {
          break;
        }
        Thread.sleep(10L);
      }

      assertThat(captured).as("★ 前提：doc_create 是敏感写 ⇒ 必须真的进审批链（进不去 = I4 破了，P4 测不到）").isNotNull();
      String summary = captured.summary();
      assertThat(summary).as("★ P4：summary 里必须有路径").contains(path);
      assertThat(summary)
          .as("★★ P4：summary 不得出现正文（GM 只能按「路径 + 谁在写」判断 —— 这是用户有意裁定 U3）")
          .doesNotContain(DOC_BODY);
      assertThat(summary)
          .as("★ P4：summary 不得出现 owner / tags / affiliations / 查询条件")
          .doesNotContain("self", "secret-tag", "secret-affiliation", "from", "to", "999");
      assertThat(captured.classKey())
          .as("审批的 classKey = 工具名（按工具名归类的审批链）")
          .isEqualTo(DocsTools.DOC_CREATE);
      assertThat(captured.kind()).as("敏感写 ⇒ AskKind.SENSITIVE").isEqualTo(AskKind.SENSITIVE);

      ToolResult result = call.get(APPROVAL_WAIT_MILLIS, TimeUnit.MILLISECONDS);
      assertThat(result.success()).as("批准后调用应当成功；实得 %s", result.message()).isTrue();
    }
  }

  // ── 切片隔离 ──────────────────────────────────────────────────────────────────────────────

  /**
   * ★ <b>切片隔离</b>：DM2 读 DM1 的工作区 ⇒ <b>"不可见"与"不存在"同码</b>（{@code DOC_NOT_FOUND}）。
   *
   * <p>★ 这条是"每个 DM 一块自己的地"的<b>反证</b>：如果两个 DM 共用一块地（或挂载的 user 键写错成同一个）， DM2 就会读到 DM1 的记录 —— 那时本用例红。⇒
   * 它挡的是"默认工作区挂成了同一块地盘"这类静默越权。
   */
  @Test
  void sliceIsolation_dmTwoCannotReadDmOnesDocument() {
    try (Shell shell = startShell(seedStoreWithTwoDecisionMakers())) {
      DecisionMaker dmOne = decisionMaker(shell, dmOneId);
      DecisionMaker dmTwo = decisionMaker(shell, dmTwoId);
      DecisionCallerFactory factory = decisionFactory(shell);
      ToolRegistry dmRegistry = decisionRegistry(shell);
      SimulationState state = headState(shell);

      // 两个 DM 的工作区是**两块不同的物理地**
      List<Mount> one = SimosToolSource.mountsOf(userKey(dmOneId));
      List<Mount> two = SimosToolSource.mountsOf(userKey(dmTwoId));
      assertThat(one).as("DM1 有默认工作区").isNotEmpty();
      assertThat(two).as("DM2 有默认工作区").isNotEmpty();
      assertThat(one.get(0).dir())
          .as("★ 两个 DM 的工作区必须是不同的物理目录（共用一块 = 越权）")
          .isNotEqualTo(two.get(0).dir());

      ToolResult created =
          factory.execute(
              dmRegistry,
              DocsTools.DOC_CREATE,
              dmOne,
              state,
              mapId(),
              Map.of("mount", DM_WORKSPACE + "/" + DOC_RELATIVE, "text", DOC_BODY));
      assertThat(created.success()).as("前提：DM1 写入成功；实得 %s", created.message()).isTrue();

      ToolResult crossRead =
          factory.execute(
              dmRegistry,
              DocsTools.DOC_READ,
              dmTwo,
              state,
              mapId(),
              Map.of("mount", DM_WORKSPACE + "/" + DOC_RELATIVE));
      assertThat(crossRead.success())
          .as("★ 切片隔离：DM2 读 DM1 的地必须失败（成功 = 越权）；实得 %s", crossRead.message())
          .isFalse();
      assertThat(crossRead.code())
          .as(
              "★★ 「不可见」与「不存在」同码（DOC_NOT_FOUND）—— 不泄漏「这里其实有东西」；实得 code=%s msg=%s",
              crossRead.code(), crossRead.message())
          .isEqualTo("DOC_NOT_FOUND");
      assertThat(crossRead.message()).as("★ 拒的时候不得把正文漏回去").doesNotContain(DOC_BODY);

      // 对照：DM2 在自己的地里写同名文件没问题（证明上面拒的是"跨切片"，不是"这个 DM 不能写")
      ToolResult ownWrite =
          factory.execute(
              dmRegistry,
              DocsTools.DOC_CREATE,
              dmTwo,
              state,
              mapId(),
              Map.of("mount", DM_WORKSPACE + "/" + DOC_RELATIVE, "text", "DM2 自己的记录"));
      assertThat(ownWrite.success()).as("对照：DM2 在自己的工作区写同名文件成功").isTrue();
    }
  }

  // ── 夹具 ──────────────────────────────────────────────────────────────────────────────────

  /** 空 store（不创世）：够 P1/P5/I3/N1 用。 */
  private Path emptyStore() {
    return tempDir.resolve("empty-" + System.nanoTime());
  }

  /**
   * ★ <b>两段式夹具</b>：先建世界与两个决策人并关壳，让第二次 {@code Shell.start} 能在装配期读到决策人清单。 这正是实现方账本 §13.4 记的教训 ——
   * "空库跑通"不是证据，凡"装配期读世界"的改动必须有一次"打开已存在 store"的启动。
   */
  private Path seedStoreWithTwoDecisionMakers() {
    Path store = tempDir.resolve("seeded-" + System.nanoTime());
    try (Shell shell = startShell(store)) {
      shell.coreSimos().bootstrapGenesis(SmallWorld.state(mapId()));
      createDecisionMaker(shell, dmOneId);
      createDecisionMaker(shell, dmTwoId);
    }
    return store;
  }

  /** 经真 GM 工具建一个决策人（与生产同一条命令路径，不直写状态）。 */
  private static void createDecisionMaker(Shell shell, String id) {
    BranchId main = new BranchId("main");
    ToolContext gm = Shell.gmCaller();
    Map<String, Object> args = new LinkedHashMap<>();
    args.put("branch", main.value());
    args.put("expectedRevision", shell.coreSimos().head(main).orElseThrow().value());
    args.put("payloadJson", "{\"id\":\"" + id + "\",\"allowedTools\":[],\"cadence\":1}");
    ToolContext call =
        new ToolContext(
            gm.caller(), gm.permissions(), gm.config(), args, gm.identity(), gm.resources());
    ToolResult result =
        shell.gmToolAuthorizer().execute(shell.toolRegistry(), "ga.CreateDecisionMaker", call);
    assertThat(result.success()).as("夹具：建决策人 %s 必须成功；实得 %s", id, result.message()).isTrue();
  }

  private Shell startShell(Path store) {
    ShellConfig base = ShellConfig.defaults(store).withPorts(0, 0, 0);
    return Shell.start(
        new ShellConfig(
            base.storeDir(),
            base.checkpointInterval(),
            base.guiPort(),
            base.mcpPort(),
            base.mcpPath(),
            base.approvalPort(),
            "agent:task-12",
            base.mapId(),
            base.bindAddress()));
  }

  private String mapId() {
    return ShellConfig.defaults(tempDir).mapId();
  }

  private static SimulationState headState(Shell shell) {
    BranchId main = new BranchId("main");
    return shell.coreSimos().replay(new StateRef(main, shell.coreSimos().head(main).orElseThrow()));
  }

  private static DecisionMaker decisionMaker(Shell shell, String id) {
    GaSnapshot ga = (GaSnapshot) headState(shell).module("ga").orElseThrow();
    return Optional.ofNullable(ga.state().decisionMakers().get(new DecisionMakerId(id)))
        .orElseThrow(() -> new AssertionError("夹具：世界里必须有决策人 " + id));
  }

  /** 决策人身份键（与 {@code DecisionCallerFactory.INSTANCE_ID_PREFIX} 同源；I9）。 */
  private static String userKey(String decisionMakerId) {
    return DecisionCallerFactory.INSTANCE_ID_PREFIX + decisionMakerId;
  }

  /**
   * 决策人面工厂，action 走 {@code gmToolAuthorizer()}（自动放行、但身份/权限仍是 DM 的）。
   *
   * <p>★ 为什么不用 {@code shell.toolAuthorizer()}：决策人链要人点审批，没人点会阻塞到审批超时 ⇒ 除 P4 那条
   * 专门测审批链的用例外，一律用自动放行的那条（判的还是真资源/权限面）。
   */
  private static DecisionCallerFactory decisionFactory(Shell shell) {
    return DecisionCallerFactory.forDecisionFace(
        shell.gmToolAuthorizer(),
        ToolBuckets.namesOf(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT)));
  }

  /** 真决策人桶的注册表（与生产同源：{@code Shell} 也是 {@code registerAll(decisionAgentFace)}）。 */
  private static ToolRegistry decisionRegistry(Shell shell) {
    ToolRegistry registry = new ToolRegistry();
    registry.registerAll(shell.toolsFor(SimosToolSource.Role.DECISION_AGENT));
    return registry;
  }

  private static List<AgentTool> nativeDocsTools() {
    CapabilityCatalog catalog = CapabilityCatalogs.inMemory();
    DocsCapability.register(catalog);
    WorkspaceRegistry registry = DefaultWorkspaceRegistry.create(catalog);
    return new ArrayList<>(DocsTools.all(registry));
  }

  private static List<String> namesOf(List<AgentTool> tools) {
    return tools.stream().map(AgentTool::name).toList();
  }

  private static boolean isExternal(String name) {
    return externalToolNames().contains(name);
  }

  /** 外来工具名（用他们的常量，改名 ⇒ 编译期红）。 */
  private static List<String> externalToolNames() {
    return List.of(
        DocsTools.DOC_LIST,
        DocsTools.DOC_READ,
        DocsTools.DOC_QUERY,
        DocsTools.DOC_APPEND,
        DocsTools.DOC_CREATE,
        DocsTools.DOC_EDIT,
        DocsTools.DOC_GUIDE,
        DocsTools.DOC_VOID,
        WorkspaceTools.MOUNT_WORKSPACE,
        WorkspaceTools.UNMOUNT_WORKSPACE,
        WorkspaceTools.LIST_WORKSPACES,
        WorkspaceTools.WORKSPACE_INFO);
  }

  private static java.util.Set<ToolRole> rolesOf(List<AgentTool> face, String name) {
    return face.stream()
        .filter(tool -> tool.name().equals(name))
        .map(tool -> ((SimosTool) tool).roles())
        .findFirst()
        .orElseThrow(() -> new AssertionError("桶里没有 " + name));
  }

  /** 反射调用一个静态私有方法，并把受检异常包成非受检（供 AssertJ 的 rootCause 断言）。 */
  private static Object invoke(Method method, Object... args) {
    try {
      return method.invoke(null, args);
    } catch (java.lang.reflect.InvocationTargetException e) {
      throw new IllegalStateException(e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
