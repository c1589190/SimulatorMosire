package io.mosire.simos.ga.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>判据 C + B：{@code SimosToolAdapter} 的三条不变量（I3 逐字委托 / I4 gate 不静默丢弃 / I1 roles 唯一拼写点）</b>。
 *
 * <h2>它守的是哪种坏法</h2>
 *
 * <p>本类包装的是<b>外来</b>工具（Docs / Workspace 那 10 条，它们只实现 {@code AgentTool}）。三条不变量的坏法都<b>没有症状</b>：
 *
 * <ul>
 *   <li><b>I3 破</b>：某一条不再原样转发（例如 {@code resources()} 返回自己的声明、{@code execute} 顺手换个参数）⇒
 *       外来工具自己的权限声明被换掉/结果被改造，而两边都不报错；
 *   <li><b>I4 破</b>：给 {@code gate} 一个"缺省放行"⇒ 绕过审批链；把 {@code gateOf} 抛的异常吞成 {@code ALLOW} ⇒
 *       审批策略判不出时静默直放（正是 fail-open 的经典形态）；
 *   <li><b>I1 破</b>：{@code roles} 被当成可空/可变的容器（空集静默注册 ⇒ 谁都看不见；构造后被人改一份 ⇒ 第二处真相）；
 *   <li><b>顺带多声明一个接口</b>：适配器若实现 {@link DecisionProposable} 或 {@link GmOnlyRead}，那 10 条外来工具的 <b>可提议面
 *       / 可见面被一次性静默放大</b>——而这两个接口都不需要它写任何代码（marker / 全 default）。
 * </ul>
 */
class SimosToolAdapterContractTest {

  private static final ToolContext CONTEXT =
      ToolContext.of(AccessToken.DEFAULT, AgentPermissionSet.system());

  // ────────────────────────── ① I3：逐字委托 ──────────────────────────

  /**
   * ★★ <b>I3：七条一律逐字转给被包装的工具（同实例、不改参数、不加白名单）</b>。
   *
   * <p>★ 用 {@code isSameAs} 而不是 {@code isEqualTo}：值相等的复制品与"原样转发"在行为上可能今天看不出差别，但 {@code resources()}
   * 那条尤其要紧——外来工具的资源声明（{@code READ_MANIFEST} / {@code WRITE_MANIFEST}）是 <b>我们权限判定的输入</b>，换一份就是换一套权限。
   */
  @Test
  void everyDelegatedMemberReturnsTheDelegateValueVerbatim() {
    FakeExternalTool delegate = new FakeExternalTool("doc_create");
    SimosToolAdapter adapter =
        new SimosToolAdapter(delegate, Set.of(ToolRole.GM), context -> ToolGate.ALLOW);

    assertThat(adapter.name())
        .as("name 原样转发（I7：保留他们的原名 doc_* / workspace_*）")
        .isEqualTo("doc_create");
    assertThat(adapter.description()).isSameAs(delegate.descriptionSentinel);
    assertThat(adapter.jsonSchema()).as("jsonSchema 原样转发").isSameAs(delegate.jsonSchema());
    assertThat(adapter.spec()).as("spec 原样转发").isSameAs(delegate.spec());
    assertThat(adapter.resources())
        .as("★★ resources 原样转发：这是外来的资源声明，我们的权限判定靠它")
        .isSameAs(delegate.resources());
    assertThat(adapter.ledgerArgs(CONTEXT)).as("ledgerArgs 原样转发").isSameAs(delegate.ledger());
    assertThat(adapter.execute(CONTEXT)).as("execute 原样转发（不吞结果）").isSameAs(delegate.result());
    assertThat(delegate.lastContext).as("★ 参数也必须原样转发（上下文被换掉 = 判定者被换掉）").isSameAs(CONTEXT);
  }

  // ────────────────────────── ② I1：roles 唯一拼写点（fail-closed） ──────────────────────────

  /**
   * ★★ <b>I1：空角色集 / 缺参 ⇒ 装配期当场抛（报文点名是哪条工具）</b>。
   *
   * <p>守的坏法：空集静默注册 ⇒ 这条工具<b>谁都看不见</b>（与 {@code SimosTool#toolRoles()} 的契约同口径：空集 = 装配故障），
   * 表现为"某个功能悄悄消失"，而不是一条错。
   */
  @Test
  void assemblyFailsClosedOnAnEmptyOrMissingRoleSet() {
    FakeExternalTool delegate = new FakeExternalTool("workspace_write");
    Function<ToolContext, ToolGate> allow = context -> ToolGate.ALLOW;

    assertThatThrownBy(() -> new SimosToolAdapter(delegate, Set.of(), allow))
        .as("★★ 判据 B：空角色集 ⇒ 装配故障（不许静默注册成「谁都看不见」）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("workspace_write")
        .hasMessageContaining("空集");
    assertThatThrownBy(() -> new SimosToolAdapter(delegate, null, allow))
        .as("★★ 判据 B：roles 为 null ⇒ 抛")
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new SimosToolAdapter(null, Set.of(ToolRole.GM), allow))
        .as("★★ 判据 B：没有被包装的工具 ⇒ 抛")
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new SimosToolAdapter(delegate, Set.of(ToolRole.GM), null))
        .as("★★ 判据 B（I4）：没给审批策略 ⇒ 抛（「没给就当 ALLOW」正是绕过审批链的形态）")
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("gateOf");
    assertThatThrownBy(
            () ->
                new SimosToolAdapter(
                    delegate, new HashSet<>(Arrays.asList((ToolRole) null)), allow))
        .as("★★ 判据 B：角色集里混进 null ⇒ 装配期抛（不许静默当成一个角色）")
        .isInstanceOf(NullPointerException.class);

    assertThatCode(() -> new SimosToolAdapter(delegate, Set.of(ToolRole.GM), allow))
        .as("对照：{GM} + 逐条给策略 ⇒ 不抛")
        .doesNotThrowAnyException();
  }

  /**
   * ★★ <b>I1：{@code toolRoles()} 是<b>不可变副本</b>——构造方事后改自己那份集合，不会改出第二处真相</b>。
   *
   * <p>守的坏法：直接持有调用方的集合 ⇒ 装配面（{@code SimosToolSource} 的角色表）与工具自报的角色可以各自
   * 被改一次，桶装配读到的"属于谁"与审计读到的不同，而两边都觉得自己是对的。
   */
  @Test
  void rolesAreAnImmutableSnapshotTakenAtConstruction() {
    Set<ToolRole> declared = new LinkedHashSet<>(Set.of(ToolRole.GM));
    SimosToolAdapter adapter =
        new SimosToolAdapter(new FakeExternalTool("doc_edit"), declared, context -> ToolGate.ALLOW);

    declared.clear();
    declared.add(ToolRole.DM);

    assertThat(adapter.toolRoles())
        .as("★★ 判据 C：toolRoles() 是构造期的快照（外部 clear/add 之后仍然只有 GM）")
        .containsExactly(ToolRole.GM);
    assertThatThrownBy(() -> adapter.toolRoles().add(ToolRole.DM))
        .as("★★ 判据 C：toolRoles() 必须真不可变（注册面拿着它当规格读）")
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> adapter.toolRoles().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  // ────────────────────────── ③ I4：gate 由装配方注入，不许吞 ──────────────────────────

  /**
   * ★★ <b>I4：{@code gate()} 逐次问注入方，返回它给的那一个；注入方抛 ⇒ <b>原样</b>抛出（不吞、不换 ALLOW）</b>。
   *
   * <p>守的坏法：判不出审批策略时静默回落 {ToolGate#ALLOW} ⇒ 需要人看的调用直接执行（fail-open）；或把异常包成 "判不出"再放行。★ 判据用 {@code
   * isSameAs} 钉住"就是那一个实例 / 就是那一个异常"。
   */
  @Test
  void theGateIsInjectedPerCallAndItsFailuresAreNeverSwallowed() {
    FakeExternalTool delegate = new FakeExternalTool("doc_append");
    ToolGate.Ask ask = new ToolGate.Ask("doc:write", "外部文档写入");
    ToolContext[] seen = new ToolContext[1];
    SimosToolAdapter adapter =
        new SimosToolAdapter(
            delegate,
            Set.of(ToolRole.GM, ToolRole.DM),
            context -> {
              seen[0] = context;
              return ask;
            });

    assertThat(adapter.gate(CONTEXT))
        .as("★★ 判据 C（I4）：gate() 返回装配方给的那一个实例（本类不解释、不替换）")
        .isSameAs(ask);
    assertThat(seen[0]).as("★ 注入方拿到的是**本次调用的**上下文").isSameAs(CONTEXT);

    ProbeExplosion boom = new ProbeExplosion("审批策略判不出");
    SimosToolAdapter exploding =
        new SimosToolAdapter(
            delegate,
            Set.of(ToolRole.GM),
            context -> {
              throw boom;
            });
    assertThatThrownBy(() -> exploding.gate(CONTEXT))
        .as("★★ 判据 B（I4）：gate 策略判不出 ⇒ 原样抛出（吞成 ALLOW = 绕过审批链）")
        .isSameAs(boom);
  }

  // ────────────────────────── ④ 不许顺带多声明一个面 ──────────────────────────

  /**
   * ★★ <b>判据 C：适配器<b>只</b>实现 {@link SimosTool}——不许顺手实现可提议面 / 可见面标记</b>。
   *
   * <p>守的坏法：{@link DecisionProposable} 与 {@link GmOnlyRead} 都是"什么都不用写就生效"的接口—— 适配器一旦实现它们，<b>那 10
   * 条外来工具会在同一次提交里整体变成"可被决策人提议"（或整体只给 GM 看）</b>， 而"哪条外来工具是什么角色/能不能被提议"是组合根的<b>业务判断</b>（{@code
   * SimosToolSource.EXTERNAL_TOOL_ROLES}）， 不是契约层能替它做的。
   */
  @Test
  void theAdapterDeclaresNoSurfaceBeyondSimosTool() {
    assertThat(SimosToolAdapter.class.getInterfaces())
        .as("★★ 判据 C：适配器只许实现 SimosTool（多一个 marker/全 default 接口 = 10 条外来工具的可见面被静默放大）")
        .containsExactly(SimosTool.class);
  }

  // ────────────────────────── 夹具 ──────────────────────────

  /** 探针异常（不是 {@code IllegalStateException}）：用来证明"原样抛出"而不是"换了类型"。 */
  private static final class ProbeExplosion extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ProbeExplosion(String message) {
      super(message);
    }
  }

  /** 一条**外来**工具（只实现 {@code AgentTool}）：每条转发判据各自返回一个可辨识的实例。 */
  private static final class FakeExternalTool implements AgentTool {

    private final String name;
    private final String descriptionSentinel = "外来的描述";
    private final Map<String, Object> schema = new LinkedHashMap<>(Map.of("type", "object"));
    private final ToolSpec spec = ToolSpec.DEFAULT;
    private final ResourceManifest manifest = ResourceManifest.NONE;
    private final Map<String, Object> ledger = new LinkedHashMap<>(Map.of("doc", "d-1"));
    private final ToolResult result = ToolResult.ok("外来工具的结果");
    private ToolContext lastContext;

    FakeExternalTool(String name) {
      this.name = name;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public String description() {
      return descriptionSentinel;
    }

    @Override
    public Map<String, Object> jsonSchema() {
      return schema;
    }

    @Override
    public ToolSpec spec() {
      return spec;
    }

    @Override
    public ResourceManifest resources() {
      return manifest;
    }

    @Override
    public Map<String, Object> ledgerArgs(ToolContext context) {
      lastContext = context;
      return ledger;
    }

    @Override
    public ToolResult execute(ToolContext context) {
      lastContext = context;
      return result;
    }

    Map<String, Object> ledger() {
      return ledger;
    }

    ToolResult result() {
      return result;
    }
  }
}
