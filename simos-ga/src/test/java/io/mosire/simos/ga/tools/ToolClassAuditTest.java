package io.mosire.simos.ga.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolClass;
import io.mosire.simos.ga.spi.ToolRole;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>{@link ToolClassAudit} 的判类语义 + 负向路径</b>（2026-10-15 批 3a 的测试面；设计书 {@code
 * 2026-10-15-tool-three-categories.md} §3.1 口径 / §3.2 审计 / §6 不变量 C2）。
 *
 * <p>★ <b>本类钉的是"合成工具面"上的规则</b>：三格判据（{@code toolRoles()} × {@code gate()} 的**实得档**）、判不出类 ⇒
 * 装配期抛、{@code gate()} 抛 ⇒ 包装但**保留原 cause**、{@code Partition} 的三份清单**真不可变**。 <b>真装配面</b>（194
 * 条工具的三格计数 140/17/37、类2 具名 17 条、逐条"分类 ⇔ 真 gate()"）由 {@code simos-app} 的 {@code
 * ToolClassAuditAssemblyTest} 钉 —— 本类**不**重复那些数字（真装配夹具在 app 侧，本模块看不见）。
 *
 * <p>★ <b>为什么用合成夹具</b>：这些判据的坏法都是"某条**特定的**坏声明悄悄过了"——真工具面里造不出它们（生产代码冻结， 也没有一条真工具是坏的），只能自己造。★
 * 每个负向用例都带<b>正向对照</b>（同一形状的合法工具不抛），否则"抛了"可能只是夹具本身坏了。
 */
class ToolClassAuditTest {

  // ────────────────────────── ① §3.1 三格判据（正向） ──────────────────────────

  /**
   * ★★ <b>类1 = {@code toolRoles() == {GM}}，且 {@code gate()} <b>根本不参与判定</b></b>（设计书 §3.1 类1 行）。
   *
   * <p>★ <b>判据怎么造才打得准</b>：给这条 {@code {GM}} 工具配一个<b>一调就炸</b>的 {@code gate()}。若审计判类1 时读了 {@code
   * gate()}，它会当场抛（{@link #PROBE_EXPLOSION}）；不抛 + 落类1 ⇒ 证明"类1 的 gate 不参与判定"。
   *
   * <p>★ 为什么这条重要：用户 2026-10-15 裁定<b>保留</b> GM-only 工具既有的 {@code Ask}（「那后面如果开放给DM用了呢？放着，
   * 不碍事就行」）⇒「GM-only 却有 Ask」**不是**异常，审计不许因此抛。
   */
  @Test
  void classOneIsGmOnlyAndNeverConsultsTheGate() {
    FakeTool gmOnlyWithExplodingGate =
        FakeTool.throwing(
            "fake.gmOnly.exploding", Set.of(ToolRole.GM), new ProbeExplosion("不该被调用"));
    FakeTool gmOnlyWithAsk =
        FakeTool.of("fake.gmOnly.ask", Set.of(ToolRole.GM), new ToolGate.Ask("k", "s"));

    ToolClassAudit.Partition partition = partitionOf(gmOnlyWithExplodingGate, gmOnlyWithAsk);

    assertThat(namesOf(partition.toolsOf(ToolClass.GM_ONLY)))
        .as("★★ 类1 = {GM}；★ 且它的 gate() 一调就炸却没炸 ⇒ 判类1 时**没有**调 gate()")
        .containsExactlyInAnyOrder("fake.gmOnly.exploding", "fake.gmOnly.ask");
    assertThat(namesOf(partition.toolsOf(ToolClass.DM_APPROVAL))).isEmpty();
    assertThat(namesOf(partition.toolsOf(ToolClass.DM_FREE))).isEmpty();
  }

  /**
   * ★★ <b>类2 = {@code toolRoles()} 含 DM 且 {@code gate()} 实得 {@code Ask}</b>，且 {@code {DM}} 与 {@code
   * {DM,GM}} 在**分类这一维等价**</b>。
   *
   * <p>★ <b>这条就是用户原话的可执行形态</b>（2026-10-15 逐字）：「GMDM都能做那肯定是DM的需审批明命令啊」⇒ 「两者都能用」+「要问人」= DM
   * 需审批，与它是不是也能给 GM 用无关。
   */
  @Test
  void classTwoIsDmCapablePlusAskRegardlessOfWhetherGmIsAlsoAllowed() {
    FakeTool dmOnlyAsk =
        FakeTool.of("fake.dmOnly.ask", Set.of(ToolRole.DM), new ToolGate.Ask("k", "s"));
    FakeTool dmGmAsk =
        FakeTool.of("fake.dmGm.ask", Set.of(ToolRole.GM, ToolRole.DM), new ToolGate.Ask("k", "s"));

    ToolClassAudit.Partition partition = partitionOf(dmOnlyAsk, dmGmAsk);

    assertThat(namesOf(partition.toolsOf(ToolClass.DM_APPROVAL)))
        .as("★★ 含 DM + Ask ⇒ 类2；{DM} 与 {DM,GM} 在这一维等价（用户 2026-10-15 原话）")
        .containsExactlyInAnyOrder("fake.dmOnly.ask", "fake.dmGm.ask");
    assertThat(namesOf(partition.toolsOf(ToolClass.GM_ONLY))).isEmpty();
    assertThat(namesOf(partition.toolsOf(ToolClass.DM_FREE))).isEmpty();
  }

  /**
   * ★ <b>类3 = {@code toolRoles()} 含 DM 且 {@code gate()} 实得 {@code ALLOW}（<b>或无 {@code gate()} 覆写 =
   * 读口</b>）</b>。
   *
   * <p>★ 第三种形态（不覆写 {@code gate()}）单列：它走的不是"显式 ALLOW"，而是 {@code AgentTool.gate()} 的**接口缺省** ——
   * 判据必须把这条缺省也算成 ALLOW，否则每个纯读工具都会被判成"判不出类"。
   */
  @Test
  void classThreeIsDmCapablePlusAllowOrTheInterfaceDefaultGate() {
    FakeTool dmOnlyAllow =
        FakeTool.of("fake.dmOnly.allow", Set.of(ToolRole.DM), new ToolGate.Allow());
    FakeTool dmGmAllow =
        FakeTool.of("fake.dmGm.allow", Set.of(ToolRole.GM, ToolRole.DM), new ToolGate.Allow());
    SimosTool dmOnlyNoGateOverride =
        new DefaultGateFakeTool("fake.dmOnly.defaultGate", Set.of(ToolRole.DM));

    ToolClassAudit.Partition partition = partitionOf(dmOnlyAllow, dmGmAllow, dmOnlyNoGateOverride);

    assertThat(namesOf(partition.toolsOf(ToolClass.DM_FREE)))
        .as("★ 含 DM + ALLOW（含「无覆写」这一形态）⇒ 类3")
        .containsExactlyInAnyOrder(
            "fake.dmOnly.allow", "fake.dmGm.allow", "fake.dmOnly.defaultGate");
    assertThat(namesOf(partition.toolsOf(ToolClass.GM_ONLY))).isEmpty();
    assertThat(namesOf(partition.toolsOf(ToolClass.DM_APPROVAL))).isEmpty();
  }

  // ────────────────────────── ② 判不出类 ⇒ 装配期抛（负向，A4） ──────────────────────────

  /**
   * ★★ <b>A4 负向：{@code toolRoles()} 判不出 ⇒ 装配期当场抛 {@link IllegalStateException}，报文含工具名与原因</b>。
   *
   * <p>覆盖三条"取不到「属于谁」"的路径：{@code toolRoles() = 空集}（= 谁都看不见）、{@code toolRoles() = null}、以及 <b>根本不是
   * {@link SimosTool}</b>（忘了声明 {@code toolRoles()} 的原生 {@code AgentTool}）。
   */
  @Test
  void unclassifiableRolesThrowAtAssemblyWithToolNameAndReason() {
    // ① 空集：契约说"空集 = 谁都看不见 = 装配故障"（SimosTool#roles 的类注）
    assertThatThrownBy(() -> ToolClassAudit.requireClasses(List.of(emptyRolesTool()), List.of()))
        .as("★ A4：toolRoles() = 空集 ⇒ 判不出类 ⇒ 装配期当场抛")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fake.emptyRoles")
        .hasMessageContaining("空集");

    // ② null
    assertThatThrownBy(() -> ToolClassAudit.requireClasses(List.of(nullRolesTool()), List.of()))
        .as("★ A4：toolRoles() = null ⇒ 判不出类 ⇒ 装配期当场抛")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fake.nullRoles")
        .hasMessageContaining("null");

    // ③ 非空、但既不是 {GM} 也不含 DM —— 用"未知角色值"代言（ToolRole 今天只有 GM/DM 两个值 ⇒ 本仓只能这样打到这条兜底分支）
    Set<ToolRole> unknownRole = new HashSet<>(Arrays.asList((ToolRole) null));
    assertThatThrownBy(
            () ->
                ToolClassAudit.requireClasses(
                    List.of(FakeTool.of("fake.unknownRole", unknownRole, ToolGate.ALLOW)),
                    List.of()))
        .as("★ A4：toolRoles() 非空但既不是 {GM}、也不含 DM ⇒ 判不出类 ⇒ 装配期当场抛（兜底分支，防将来加角色时静默漏判）")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fake.unknownRole")
        .hasMessageContaining("既不是 {GM}、也不含 DM");

    // ④ 原生 AgentTool（没有 toolRoles() 这个概念）
    assertThatThrownBy(
            () ->
                ToolClassAudit.requireClasses(
                    List.of(new RawAgentTool("fake.rawAgentTool")), List.of()))
        .as("★ A4：未实现 SimosTool 的工具入面 ⇒ 「属于谁」取不到 ⇒ 装配期当场抛（不静默放行）")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fake.rawAgentTool")
        .hasMessageContaining("没有声明 SimosTool.toolRoles()");

    // ★ 正向对照：同一形状的**合法**工具不抛（否则上面四条"抛"可能只是夹具坏了）
    assertThatCode(
            () ->
                partitionOf(
                    FakeTool.of(
                        "fake.dmOnly.ask", Set.of(ToolRole.DM), new ToolGate.Ask("k", "s"))))
        .as("对照：合法的 {DM} + Ask 不抛")
        .doesNotThrowAnyException();
  }

  /**
   * ★★ <b>A4 负向（第二条判据）：{@code gate()} 实得第三态（{@code Block}）⇒ 装配期当场抛</b>。
   *
   * <p>★ 为什么这条与上一条分开：失败信息指向的**修法不同** —— 上一条要补 {@code toolRoles()}，这一条要改 {@code gate()} 的档位。★ 类2 与类3
   * 的差别全在 {@code gate()} 的实得档上，第三态必须**响亮失败**，不许被猜成其中一格。
   */
  @Test
  void gateReturningABlockThrowsAtAssemblyWithToolNameAndReason() {
    FakeTool blocked =
        FakeTool.of("fake.dmOnly.blocked", Set.of(ToolRole.DM), new ToolGate.Block("k", "r"));

    assertThatThrownBy(() -> ToolClassAudit.requireClasses(List.of(blocked), List.of()))
        .as("★★ A4：含 DM 但 gate() 实得 Block（既非 Ask 也非 ALLOW）⇒ 判不出类 ⇒ 装配期当场抛")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fake.dmOnly.blocked")
        .hasMessageContaining("既不是 Ask、也不是 ALLOW");
  }

  // ────────────────────────── ③ gate() 抛 ⇒ 包装但不吞 cause（负向，A5） ──────────────────────────

  /**
   * ★★ <b>A5 负向：{@code gate()} 在探针上下文下抛异常 ⇒ 包装成 {@link IllegalStateException}，报文带工具名 + 原异常类别/消息，且
   * <b>原 cause 原样带出</b></b>（设计书 §3.2 第 3 条：不许悄悄换第二处声明、不许跳过、不许 catch 住当"判不出"）。
   *
   * <p>★ <b>判据的三条腿</b>（缺一条这条用例就是假的）：① 抛的是装配期故障类型；② 报文里能看出**是哪条工具**、**抛的是什么**； ③ {@code getCause()}
   * **就是**那个原异常实例（不是复制品、不是 null）—— 第三条正是"没被吞成判不出"的执行证据。
   */
  @Test
  void gateThrowingIsWrappedWithToolNameAndTheOriginalCause() {
    ProbeExplosion boom = new ProbeExplosion("探针空参下炸了");
    FakeTool exploding = FakeTool.throwing("fake.dmOnly.explodingGate", Set.of(ToolRole.DM), boom);

    assertThatThrownBy(() -> ToolClassAudit.requireClasses(List.of(exploding), List.of()))
        .as("★★ A5：gate() 抛 ⇒ 装配期仍然硬失败（包装只为把「是哪条工具、抛了什么」写进故障信息）")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fake.dmOnly.explodingGate")
        .hasMessageContaining("gate() 在探针上下文")
        .hasMessageContaining(ProbeExplosion.class.getName())
        .hasMessageContaining("探针空参下炸了")
        .hasCauseInstanceOf(ProbeExplosion.class)
        .satisfies(
            thrown ->
                assertThat(thrown.getCause())
                    .as("★ A5：原异常必须**原样**带出（同一实例）——「吞成判不出」在这里当场红")
                    .isSameAs(boom));
  }

  // ────────────────────────── ④ Partition 真不可变（A6） ──────────────────────────

  /**
   * ★★ <b>A6：{@link ToolClassAudit.Partition} 的三份清单<b>真</b>不可变（执行证据）</b>。
   *
   * <p>★ 为什么"静态类型不算"：{@code List} 接口不承诺不可变——返回一个普通 {@code ArrayList} 完全编译得过，而调用方 （3b 的 {@code
   * ProposalCatalog}、测试、后续批次）会把它当**规格**读 ⇒ 谁改一下就是"第二处会漂移的清单"。 本用例**真的去改**它，{@code add} / {@code
   * clear} 都必须抛 {@link UnsupportedOperationException}。
   */
  @Test
  void partitionListsRejectMutation() {
    FakeTool gmOnly = FakeTool.of("fake.gmOnly", Set.of(ToolRole.GM), new ToolGate.Ask("k", "s"));
    FakeTool dmApproval =
        FakeTool.of("fake.dmApproval", Set.of(ToolRole.DM), new ToolGate.Ask("k", "s"));
    FakeTool dmFree = FakeTool.of("fake.dmFree", Set.of(ToolRole.DM), ToolGate.ALLOW);

    ToolClassAudit.Partition partition = partitionOf(gmOnly, dmApproval, dmFree);

    for (ToolClass toolClass : ToolClass.values()) {
      List<AgentTool> tools = partition.toolsOf(toolClass);
      assertThat(tools).as("前提：%s 那一格不是空的（否则下面的「改不动」什么都没证明）", toolClass).isNotEmpty();
      AgentTool first = tools.get(0);
      assertThatThrownBy(() -> tools.add(first))
          .as("★★ A6：%s 的清单必须拒绝 add（真不可变，不是「约定不可变」）", toolClass)
          .isInstanceOf(UnsupportedOperationException.class);
      assertThatThrownBy(tools::clear)
          .as("★★ A6：%s 的清单必须拒绝 clear", toolClass)
          .isInstanceOf(UnsupportedOperationException.class);
      assertThatThrownBy(() -> tools.remove(0))
          .as("★★ A6：%s 的清单必须拒绝 remove", toolClass)
          .isInstanceOf(UnsupportedOperationException.class);
    }

    // ★ 三份清单各归各的（toolsOf 的映射不能串格）
    assertThat(namesOf(partition.toolsOf(ToolClass.GM_ONLY))).containsExactly("fake.gmOnly");
    assertThat(namesOf(partition.toolsOf(ToolClass.DM_APPROVAL)))
        .containsExactly("fake.dmApproval");
    assertThat(namesOf(partition.toolsOf(ToolClass.DM_FREE))).containsExactly("fake.dmFree");
  }

  // ────────────────────────── 夹具与小件 ──────────────────────────

  /** ★ 探针上下文爆炸（A5 用）：一个**不是** {@code IllegalStateException} 的原异常，才能证明"包装"真的发生了。 */
  private static final class ProbeExplosion extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ProbeExplosion(String message) {
      super(message);
    }
  }

  /** 合成工具：{@code toolRoles()} 直接给，{@code gate()} 由函数给（含"一调就抛"形态）。 */
  private static final class FakeTool implements SimosTool {
    private final String name;
    private final Set<ToolRole> roles;
    private final Function<ToolContext, ToolGate> gate;

    private FakeTool(String name, Set<ToolRole> roles, Function<ToolContext, ToolGate> gate) {
      this.name = name;
      this.roles = roles;
      this.gate = gate;
    }

    static FakeTool of(String name, Set<ToolRole> roles, ToolGate gate) {
      return new FakeTool(name, roles, context -> gate);
    }

    static FakeTool throwing(String name, Set<ToolRole> roles, RuntimeException boom) {
      return new FakeTool(
          name,
          roles,
          context -> {
            throw boom;
          });
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public Set<ToolRole> toolRoles() {
      return roles;
    }

    @Override
    public ToolGate gate(ToolContext context) {
      return gate.apply(context);
    }

    @Override
    public ToolResult execute(ToolContext context) {
      throw new UnsupportedOperationException("测试夹具：不执行");
    }
  }

  /** 纯读工具的形态：<b>不覆写</b> {@code gate()} ⇒ 走 {@code AgentTool.gate()} 的接口缺省。 */
  private static final class DefaultGateFakeTool implements SimosTool {
    private final String name;
    private final Set<ToolRole> roles;

    DefaultGateFakeTool(String name, Set<ToolRole> roles) {
      this.name = name;
      this.roles = roles;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public Set<ToolRole> toolRoles() {
      return roles;
    }

    @Override
    public ToolResult execute(ToolContext context) {
      throw new UnsupportedOperationException("测试夹具：不执行");
    }
  }

  /** 原生 {@code AgentTool}：**没有** {@code toolRoles()} 这个概念（A4 第 ④ 条）。 */
  private record RawAgentTool(String name) implements AgentTool {

    @Override
    public ToolResult execute(ToolContext context) {
      throw new UnsupportedOperationException("测试夹具：不执行");
    }
  }

  private static SimosTool emptyRolesTool() {
    return FakeTool.of("fake.emptyRoles", Set.of(), ToolGate.ALLOW);
  }

  private static SimosTool nullRolesTool() {
    return FakeTool.of("fake.nullRoles", null, ToolGate.ALLOW);
  }

  /**
   * 按<b>声明</b>把合成工具放进两个面（含 GM ⇒ GM 面；含 DM ⇒ 决策人面）⇒ 与真装配同构地调 {@link ToolClassAudit#requireClasses}。
   */
  private static ToolClassAudit.Partition partitionOf(AgentTool... tools) {
    List<AgentTool> gmFace = new ArrayList<>();
    List<AgentTool> decisionFace = new ArrayList<>();
    for (AgentTool tool : tools) {
      Set<ToolRole> roles = ((SimosTool) tool).toolRoles();
      if (roles.contains(ToolRole.GM)) {
        gmFace.add(tool);
      }
      if (roles.contains(ToolRole.DM)) {
        decisionFace.add(tool);
      }
    }
    return ToolClassAudit.requireClasses(gmFace, decisionFace);
  }

  /** 一份清单的工具名（{@link LinkedHashSet} ⇒ 可读且能查重）。 */
  private static Set<String> namesOf(List<AgentTool> tools) {
    Set<String> names = new LinkedHashSet<>();
    for (AgentTool tool : tools) {
      names.add(tool.name());
    }
    return names;
  }
}
