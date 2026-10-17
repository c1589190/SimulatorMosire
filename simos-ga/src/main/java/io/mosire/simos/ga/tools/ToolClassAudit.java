package io.mosire.simos.ga.tools;

import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolClass;
import io.mosire.simos.ga.spi.ToolRole;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>工具三分类的装配期 fail-closed 分区</b>（2026-10-15 批次 3a；设计书 §3.1 口径 / §3.2 接线 / 不变量 C1–C3）。
 *
 * <p>★ <b>它补的是哪一块</b>：三分类此前是**两处互不相干的手写声明**（{@code toolRoles()} 说"谁能用"、{@code gate()} 说"要不要问人"），
 * 没有任何东西检查两者是否自洽、也没有东西检查"每一条工具都落进了三格之一" ⇒ 漂移**无症状**（漏一条 {@code Ask}、{@code roles}
 * 写反，装配期照样过）。本类把它变成**装配完成处一次 O(n) 的分区**：判不出类的工具<b>当场抛</b>， 与会话里已解决的 {@link
 * ToolRoleAudit#requireFaces} 同一类问题、同一形制（那里钉"桶 ⇔ {@code toolRoles()}"，这里钉"{@code toolRoles()} ×
 * {@code gate()} ⇒ 类"）。
 *
 * <p>★★ <b>判据只来自 {@code gate()} 的实得档，不引入"第二个声明"</b>（设计书 §3.2 的要害）：{@code toolRoles()} 是
 * context-free 的 ⇒ 直接读；{@code gate(ToolContext)} 需要上下文 ⇒ 用**探针上下文**（{@link #PROBE}：{@code
 * arguments()} = 空 {@code Map}）真调它、判 {@code Ask} / {@code ALLOW}。★ 只有读**真的** {@code
 * gate()}，"分类"才不可能与**行为**漂移； 若改成让每条工具再声明一个 {@code toolClass()}，那就是**第二处会漂移的声明**——与 {@code
 * ToolRoleAudit} 存在的理由（消灭第二处声明） 正好相反。★ 若某条工具的 {@code gate()}
 * 在空参下抛异常，本类**不兜、不降级、不跳过**：当场抛（把工具名与真实异常附上）。
 *
 * <p>★ <b>为什么本类住在 {@code simos-ga}</b>：它只认 {@link AgentTool} / {@link ToolContext} / {@link
 * ToolGate} （agentlib 契约）与 {@link SimosTool} / {@link ToolRole} / {@link ToolClass}（本仓契约），不认任何域类型与
 * app 的桶枚举 —— 与 {@link ToolBuckets} / {@link ToolRoleAudit} 同款（"工具运作"的纯规则）。{@code app} 负责把两桶递进来。
 *
 * <p>★ <b>本批只读</b>：审计不删任何 {@code Ask}、不改任何 {@code toolRoles()} 的集合内容（设计书 §6 C1）。
 */
public final class ToolClassAudit {

  private ToolClassAudit() {}

  /**
   * ★★ <b>探针上下文</b>：三分类里唯一需要"调用一次"的判据（{@code gate()}）的入参。
   *
   * <p>★ 它只给 {@link ToolContext} 记录构造器要求的两个非 null 分量（{@code caller} / {@code permissions}），
   * 其余取缺省：{@code config} / {@code arguments} 都是**空 Map**（分类必须与"模型填了什么"无关），{@code identity} 取 {@code
   * AgentIdentity.UNKNOWN}，{@code resources} 取契约缺省（fail-closed 的 {@code denying()}——
   * 探针**不**发许可，免得掩盖"某条 {@code gate()} 去问资源"这种越界）。
   *
   * <p>★ <b>身份维取最宽的一档</b>（{@code SYSTEM} + 全授权）而不是某条真实调用链的身份：本审计问的是"这条工具<b>自报</b>要不要人看"，
   * 与调用者够不够格无关；取最宽的一档，可使"万一真有一条 {@code gate()} 读了身份维"也拿到它最放行的答案， 而不是被探针的权限假象逼出一个假的 {@code Ask}。
   *
   * <p>★ <b>实测依据</b>（设计书 §3.2 要求"探针可行"必须被确认）：{@code simos-*&#47;src/main} 下共 73 处 {@code
   * gate(ToolContext)} 覆写，逐条核过 —— 63 处返回常量 {@code Ask}、8 处返回常量 {@code ALLOW}、2 处是**纯转发** （{@code
   * SimosToolAdapter} → 装配注入的策略、{@code RecordingToolSource} → 被包装的工具）；{@code arguments()} 只用于拼
   * summary 文案、且 {@code args.get(...)} 对缺键 null 安全。★ 没有任何一处读 {@code caller()} / {@code
   * permissions()} / {@code identity()} / {@code resources()} / {@code config()}。
   */
  private static final ToolContext PROBE =
      new ToolContext(AccessToken.SYSTEM, AgentPermissionSet.system(), Map.of(), Map.of());

  /**
   * ★★ <b>全量分区 + fail-closed 校验</b>：遍历<b>两桶并集</b>里的每一条工具，按设计书 §3.1 判类；判不出 ⇒ {@link
   * IllegalStateException}（装配期抛，不静默）。
   *
   * <p>★ 遍历序 = GM 桶注册序 + 决策人桶里 GM 桶没有的那些（两桶共享的工具只算一条）⇒ 三份清单的顺序是**内容的纯函数**、可复核。 桶内重名与"桶 ⇔ {@code
   * toolRoles()}"的成员关系**不归本类**（那是 {@link ToolRoleAudit#requireFaces} 的活，本类一个字不放松它）。
   *
   * @param gmFace GM 桶工具面（含急停协议那三条；非 null）
   * @param decisionFace 决策人桶工具面（非 null）
   * @return 三份**不可变**清单（类1 / 类2 / 类3），供后续批次与测试取用
   * @throws IllegalStateException 任一工具判不出类（{@code toolRoles()} 判不出、或 {@code gate()} 实得档既非 {@code
   *     Ask} 也非 {@code ALLOW}、或 {@code gate()} 在探针上下文下抛异常）
   */
  public static Partition requireClasses(List<AgentTool> gmFace, List<AgentTool> decisionFace) {
    Objects.requireNonNull(gmFace, "gmFace");
    Objects.requireNonNull(decisionFace, "decisionFace");
    Map<String, AgentTool> union = new LinkedHashMap<>();
    for (AgentTool tool : gmFace) {
      union.putIfAbsent(tool.name(), tool);
    }
    for (AgentTool tool : decisionFace) {
      union.putIfAbsent(tool.name(), tool);
    }
    List<AgentTool> gmOnly = new ArrayList<>();
    List<AgentTool> dmApproval = new ArrayList<>();
    List<AgentTool> dmFree = new ArrayList<>();
    for (AgentTool tool : union.values()) {
      classify(tool, gmOnly, dmApproval, dmFree);
    }
    return new Partition(gmOnly, dmApproval, dmFree);
  }

  /** 单条工具判类（设计书 §3.1 的三格 + 兜底抛出）；命中哪一格就往哪一格的清单里加。 */
  private static void classify(
      AgentTool tool, List<AgentTool> gmOnly, List<AgentTool> dmApproval, List<AgentTool> dmFree) {
    String name = tool.name();
    Set<ToolRole> roles = rolesOf(tool, name);
    // 类1：toolRoles() == {GM}。★ gate() **不参与判定**：DM 够不着 ⇒ 问不问人都一样；用户 2026-10-15 裁定保留它们既有的 Ask，
    //   故"GM-only 却有 Ask"不是异常（设计书 §3.1 类1 行）。
    if (roles.size() == 1 && roles.contains(ToolRole.GM)) {
      gmOnly.add(tool);
      return;
    }
    if (!roles.contains(ToolRole.DM)) {
      throw new IllegalStateException(
          "工具三分类装配故障："
              + name
              + " 的 toolRoles() = "
              + roles
              + " 既不是 {GM}、也不含 DM —— 判不出它属于三格里的哪一格（设计书 §3.1「✗ 其余 ⇒ 当场抛」）。"
              + "★ 不要把它跳过或降级成日志：改它的 toolRoles() 声明才是对的修法。");
    }
    ToolGate gate = gateOf(tool, name);
    if (gate instanceof ToolGate.Ask) {
      dmApproval.add(tool);
      return;
    }
    if (gate instanceof ToolGate.Allow) {
      dmFree.add(tool);
      return;
    }
    throw new IllegalStateException(
        "工具三分类装配故障："
            + name
            + " 的 toolRoles() = "
            + roles
            + "（含 DM），但 gate() 的**实得档** = "
            + gate
            + " 既不是 Ask、也不是 ALLOW —— 判不出它是类2（DM_APPROVAL）还是类3（DM_FREE）（设计书 §3.1「✗ 其余 ⇒ 当场抛」）。"
            + "★ 判据**只**来自 gate() 的实得档：不许给它另加一个 toolClass() 声明（那是第二处会漂移的声明，设计书 §3.2）。");
  }

  /**
   * {@code toolRoles()} 的契约守卫：必须是 {@link SimosTool}，且返回**非 null、非空**（空集 = 谁都看不见 = 装配故障）。
   *
   * <p>★ 这里与 {@link ToolRoleAudit} 各查一遍不是重复：那道审计钉的是"桶 ⇔ 声明"，本类钉的是"声明 × 闸位 ⇒ 类"，两处的失败信息 指向不同的修法（补
   * {@code toolRoles()} vs 补/改 {@code gate()}）。
   */
  private static Set<ToolRole> rolesOf(AgentTool tool, String name) {
    if (!(tool instanceof SimosTool simosTool)) {
      throw new IllegalStateException(
          "工具三分类装配故障："
              + name
              + "（"
              + tool.getClass().getName()
              + "）没有声明 SimosTool.toolRoles() —— 三分类的两个判据（属于谁 / 要不要问人）里，"
              + "「属于谁」取不到 ⇒ 判不出类。不要静默跳过：给它补上 toolRoles()。");
    }
    Set<ToolRole> roles = simosTool.toolRoles();
    if (roles == null || roles.isEmpty()) {
      throw new IllegalStateException(
          "工具三分类装配故障："
              + name
              + " 的 toolRoles() 返回"
              + (roles == null ? " null" : "空集")
              + " —— 空集 = 谁都看不见 = 装配故障，判不出类（SimosTool#roles 的契约：必须是 {GM} / {DM} / {GM, DM} 之一）。");
    }
    return roles;
  }

  /**
   * ★★ <b>真调 {@code gate()}（探针上下文）</b>——本类唯一一次"问工具自己"的地方。
   *
   * <p>★ 抛出的异常**不吞**：探针调不通就是"判不出类"，包装成 {@link IllegalStateException} 只为把**是哪条工具、抛了什么**写进
   * 装配故障信息（原异常经 {@code cause} 原样带出），判定仍然是**硬失败**——不跳过、不降级、不换成第二处声明（设计书 §3.2）。
   */
  private static ToolGate gateOf(AgentTool tool, String name) {
    try {
      return tool.gate(PROBE);
    } catch (RuntimeException e) {
      throw new IllegalStateException(
          "工具三分类装配故障："
              + name
              + " 的 gate() 在探针上下文（arguments() = 空 Map）下抛出 "
              + e.getClass().getName()
              + ": "
              + e.getMessage()
              + " —— 判不出类。★ 按设计书 §3.2：不许悄悄换成第二处声明、不许把它跳过、不许 catch 住当「判不出」"
              + "（要么让 gate() 对空参可判，要么停下报 BLOCKED）。",
          e);
    }
  }

  /**
   * ★ <b>三格分区结果</b>：三份**不可变**的<b>工具实例</b>清单（顺序 = 两桶并集的遍历序 = 注册序），供后续批次（3b 的 {@code
   * ProposalCatalog}）与测试取用。★ 清单从**两桶并集**派生（{@code requireClasses} 的 {@code union}），暴露的是 {@link
   * AgentTool} 实例 —— 本类<b>不另造工具名清单</b>；需要名字的调用方（{@link ToolRoleAudit}、app 侧的白名单）经 {@link
   * ToolBuckets#namesOf} 自己从工具面派生。
   */
  public record Partition(
      List<AgentTool> gmOnly, List<AgentTool> dmApproval, List<AgentTool> dmFree) {

    public Partition {
      gmOnly = List.copyOf(Objects.requireNonNull(gmOnly, "gmOnly"));
      dmApproval = List.copyOf(Objects.requireNonNull(dmApproval, "dmApproval"));
      dmFree = List.copyOf(Objects.requireNonNull(dmFree, "dmFree"));
    }

    /** 某一格的清单（{@link ToolClass} 的三个取值各对应一份；null ⇒ 抛）。 */
    public List<AgentTool> toolsOf(ToolClass toolClass) {
      Objects.requireNonNull(toolClass, "toolClass");
      return switch (toolClass) {
        case GM_ONLY -> gmOnly;
        case DM_APPROVAL -> dmApproval;
        case DM_FREE -> dmFree;
      };
    }
  }
}
