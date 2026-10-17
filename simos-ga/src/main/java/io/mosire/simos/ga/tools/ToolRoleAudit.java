package io.mosire.simos.ga.tools;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * ★★ <b>工具角色声明的装配期 fail-closed 校验</b>（2026-10-12 B4，设计书 §4.4「一处声明、三处派生」/ 不变量 I-G3）。
 *
 * <p>★ <b>为什么是"校验"而不是"派生注册"</b>：本仓的工具是**显式 {@code new} 装配**的（{@code SimosToolSource} 里逐条 {@code
 * built.add(...)}），不是从磁盘发现的。若让 {@link SimosTool#toolRoles()} 直接决定"注册进哪个桶"， 那么**标错一个角色 = 权限面静默变化**
 * （比如给只在 GM 桶的窄写多标一个 {@code DM} ⇒ 决策人桶凭空多一条写工具，而没有任何症状）。 ⇒ 本批的口径是：**装配面照旧显式写死**（顺序、内容一字不改）， {@code
 * toolRoles()} 是**声明**，两者在装配完成处做**双向全量比对**，不一致**当场抛**。
 *
 * <p>★ <b>两段校验，各一次 O(n)，都在"装配完成处"</b>（不是每条工具建的时候各查一遍）：
 *
 * <ol>
 *   <li>{@link #requireFace} —— 单桶：桶里每条工具都必须实现 {@link SimosTool}（忘了声明 ⇒ 红，而不是静默放行）， 且 {@code
 *       toolRoles()} 必须含该桶的角色；
 *   <li>{@link #requireFaces} —— 双桶：**成员关系 ⇔ 声明**（工具名在 GM 桶里 ⇔ {@code toolRoles()} 含 {@code
 *       GM}；在决策人桶里 ⇔ 含 {@code DM}）。这一半专抓两条"单桶看不出来"的失效：<b>标了 DM 却没注册进 DM 桶</b>、<b>只在 GM 桶的工具却标了
 *       DM</b>。
 * </ol>
 *
 * <p>★★ <b>2026-10-17 批 R 追加 post-wrap 两件（审核 P1-1 / P1-2）</b>：{@link #requireFacesOnAgentTools}（语义同上两段，但读
 * {@link AgentTool#roles()} 的**字符串**、不再强转 {@link SimosTool} ⇒ 能在记录壳上跑）与 {@link #declaredRoleCatalog}（装订期
 * {@code RoleCatalogAudit} 的输入 A）。前两段**一个字没动**（包装前的旧判据继续存在）：包装丢角色（记录壳不转发 {@code
 * roles()} ⇒ 空声明 = 本维不表态）只有 post-wrap 这一段看得见。
 *
 * <p>★ <b>为什么本类住在 {@code simos-ga}</b>：它只认 {@link AgentTool} + {@link ToolRole}（契约层类型），不认任何域类型与 app
 * 的桶枚举 —— 与 {@link ToolBuckets} 同款（"工具运作"的纯规则）。{@code app} 负责把 {@code Role} 映射成 {@link ToolRole}
 * 并调它。
 */
public final class ToolRoleAudit {

  private ToolRoleAudit() {}

  /**
   * 单桶校验：该桶里每条工具必须声明了该桶的角色（{@code SimosTool.toolRoles()} 含 {@code face}）。
   *
   * @param tools 该桶的工具面（构造顺序 = 注册顺序；只读）
   * @param face 该桶应有的角色（GM 桶给 {@link ToolRole#GM}，决策人桶给 {@link ToolRole#DM}）
   */
  public static void requireFace(List<AgentTool> tools, ToolRole face) {
    Objects.requireNonNull(tools, "tools");
    Objects.requireNonNull(face, "face");
    Set<String> seen = new LinkedHashSet<>();
    for (AgentTool tool : tools) {
      String name = tool.name();
      if (!(tool instanceof SimosTool simosTool)) {
        throw new IllegalStateException(
            "工具面装配故障："
                + name
                + "（"
                + tool.getClass().getName()
                + "）没有声明 SimosTool.toolRoles() —— 「这个工具能被谁用」缺唯一拼写点"
                + "（设计书 §4.4 / I-G3）。不要静默放行：给它补上 toolRoles()；"
                + "「谁能用」不许有第二处拼写（桶与白名单都从它派生）。");
      }
      if (!seen.add(name)) {
        throw new IllegalStateException("工具面装配故障：" + face + " 桶里出现重名工具 " + name + "（同一桶重复注册）。");
      }
      Set<ToolRole> roles = rolesOf(simosTool, name);
      if (!roles.contains(face)) {
        throw new IllegalStateException(
            "工具面装配故障："
                + name
                + " 声明 "
                + roles
                + "（不含 "
                + face
                + "），却注册在 "
                + face
                + " 桶里 —— toolRoles() 声明与装配面必须逐条一致（设计书 §4.4 / I-G3）。");
      }
    }
  }

  /**
   * 双桶校验：**成员关系 ⇔ 声明**（双向、全量、一次 O(n)）。
   *
   * <p>★ 它抓的是单桶校验看不见的那一半：一条工具<b>声明了 DM 却没被注册进决策人桶</b>（决策人该有的能力静默消失/或标错角色而无人发现）、 或一条只在 GM 桶的工具<b>多标了
   * DM</b>（声明说它能给决策人用，装配却没那么做 —— 谁改谁错，必须当场红）。
   *
   * @param gmFace GM 桶工具面
   * @param decisionFace 决策人桶工具面
   */
  public static void requireFaces(List<AgentTool> gmFace, List<AgentTool> decisionFace) {
    Objects.requireNonNull(gmFace, "gmFace");
    Objects.requireNonNull(decisionFace, "decisionFace");
    requireFace(gmFace, ToolRole.GM);
    requireFace(decisionFace, ToolRole.DM);
    Map<String, Set<ToolRole>> declared = new LinkedHashMap<>();
    for (AgentTool tool : gmFace) {
      declared.put(tool.name(), ((SimosTool) tool).toolRoles());
    }
    for (AgentTool tool : decisionFace) {
      declared.putIfAbsent(tool.name(), ((SimosTool) tool).toolRoles());
    }
    Set<String> gmNames = ToolBuckets.namesOf(gmFace);
    Set<String> decisionNames = ToolBuckets.namesOf(decisionFace);
    List<String> faults = new ArrayList<>();
    for (Map.Entry<String, Set<ToolRole>> entry : declared.entrySet()) {
      String name = entry.getKey();
      Set<ToolRole> roles = entry.getValue();
      boolean saysGm = roles.contains(ToolRole.GM);
      boolean saysDm = roles.contains(ToolRole.DM);
      if (saysGm != gmNames.contains(name)) {
        faults.add(
            name
                + "：toolRoles() "
                + (saysGm ? "声明了 GM" : "没声明 GM")
                + "，装配面"
                + (gmNames.contains(name) ? "注册在" : "没注册进")
                + " GM 桶");
      }
      if (saysDm != decisionNames.contains(name)) {
        faults.add(
            name
                + "：toolRoles() "
                + (saysDm ? "声明了 DM" : "没声明 DM")
                + "，装配面"
                + (decisionNames.contains(name) ? "注册在" : "没注册进")
                + " 决策人桶");
      }
    }
    if (!faults.isEmpty()) {
      throw new IllegalStateException(
          "工具面装配故障：SimosTool.toolRoles() 的声明与两桶注册面不一致（设计书 §4.4 / I-G3）—— "
              + String.join("；", faults));
    }
  }

  /**
   * ★★ <b>包装后（post-wrap）的双面校验</b>（2026-10-17 批 R，计划 §4.4 / 审核 P1-1、P1-2）：语义同 {@link #requireFaces}（某面工具 ⇔
   * 该面角色），但读 {@link AgentTool#roles()} 的**字符串**，与 {@link ToolRole#name()} 比对，**不再强转 {@link SimosTool}** ⇒
   * 记录壳（{@code RecordingToolSource.RecordingTool}）上也能跑。
   *
   * <p>★ <b>为什么必须另起一段</b>：{@link #requireFaces} 靠 {@code (SimosTool) tool} 取声明，只能在**包装前**跑；而 {@code
   * Shell} 真正注册/暴露给 MCP 的是记录壳 ⇒ 只跑包装前那一段，包装丢角色（空声明 = 本维不表态）会静默放过（审核 §1 探针实测
   * {@code raw_roles=[GM]; wrapped_roles=[]}）。
   *
   * <p>★ <b>两桶同名工具逐实例各验自己那面</b>：{@code gmFace} 与 {@code decisionFace} 里的每个实例**分别**读自己的 {@code
   * roles()}，再与两份桶成员名单双向对照——**不按名字合并、不在两实例里任选一侧**。⇒ "GM 面实例声明 {GM}、决策人面实例声明 {DM}"
   * 这种同名不同声明会在两个方向各留一条"声明与桶成员不符"，当场红。
   *
   * @param gmFace GM 面**实际注册的**工具实例（记录壳之后的注册表；只读）
   * @param decisionFace 装配给决策人的工具面实际实例（决策人注册表；只读）
   * @throws IllegalStateException 任一面有工具未声明该面角色，或某个同名工具的声明与两桶实际成员不符
   */
  public static void requireFacesOnAgentTools(List<AgentTool> gmFace, List<AgentTool> decisionFace) {
    Objects.requireNonNull(gmFace, "gmFace");
    Objects.requireNonNull(decisionFace, "decisionFace");
    requireFaceOnAgentTools(gmFace, ToolRole.GM);
    requireFaceOnAgentTools(decisionFace, ToolRole.DM);
    Set<String> gmNames = ToolBuckets.namesOf(gmFace);
    Set<String> decisionNames = ToolBuckets.namesOf(decisionFace);
    Set<String> faults = new LinkedHashSet<>();
    collectAgentToolFaults(faults, gmFace, gmNames, decisionNames);
    collectAgentToolFaults(faults, decisionFace, gmNames, decisionNames);
    if (!faults.isEmpty()) {
      throw new IllegalStateException(
          "工具面装配故障：包装后的 AgentTool.roles() 声明与两桶实际成员不一致（计划 §4.4 / 审核 P1-2）—— "
              + String.join("；", faults)
              + "。两个观察面（实际实例的 roles() / 实际桶成员）必须逐条一致：检查记录壳是否转发 roles()、"
              + "以及角色名是否仍只从 SimosTool.toolRoles() 派生。");
    }
  }

  /**
   * ★★ <b>装订期审计的输入 A（声明面，post-wrap）</b>（2026-10-17 批 R，计划 §4.4 / 审核 P1-2）：把两个面的**实际实例**的
   * {@link AgentTool#roles()} 汇成「角色名 → 工具名集合」。
   *
   * <p>★ <b>与输入 B（实际桶成员投影）是两个独立观察面</b>：本方法**不读桶名单**（B 由调用方另行投影，见 {@code
   * RoleCatalogAudit.withHandwrittenCatalog}）。两者的键都是角色名（{@link ToolRole#name()}），值都是工具名 ⇒ "声明写错成另一个已知角色 /
   * 改成空、桶没动"能被 {@code RoleCatalogAudit} 的对账抓红（同一时刻面校验也会先红）。
   *
   * <p>★ <b>空角色名不在这里吞掉</b>：{@code null} 读作空串（{@code RoleCatalogAudit} 自己的防呆口径），空白名会让库的目录防御
   * （{@code withDerivation} 的拷贝）**响亮失败**——不静默丢一项，把漂移洗成"没声明"。
   *
   * @param gmFace GM 面实际实例（记录壳之后的注册表）
   * @param decisionFace 装配给决策人的实际实例
   * @return 不可变的 角色名 → 工具名集合（键升序；值为不可变集合）
   */
  public static Map<String, Set<String>> declaredRoleCatalog(
      List<AgentTool> gmFace, List<AgentTool> decisionFace) {
    Objects.requireNonNull(gmFace, "gmFace");
    Objects.requireNonNull(decisionFace, "decisionFace");
    Map<String, Set<String>> catalog = new TreeMap<>();
    collectDeclarations(catalog, gmFace);
    collectDeclarations(catalog, decisionFace);
    Map<String, Set<String>> frozen = new TreeMap<>();
    for (Map.Entry<String, Set<String>> entry : catalog.entrySet()) {
      frozen.put(entry.getKey(), Set.copyOf(entry.getValue()));
    }
    return Map.copyOf(frozen);
  }

  /** 单面校验（post-wrap）：该面每个实例的 {@code AgentTool.roles()} 都必须含该面角色名（{@link ToolRole#name()}）。 */
  private static void requireFaceOnAgentTools(List<AgentTool> tools, ToolRole face) {
    Set<String> seen = new LinkedHashSet<>();
    for (AgentTool tool : tools) {
      String name = tool.name();
      if (!seen.add(name)) {
        throw new IllegalStateException(
            "工具面装配故障：" + face + " 面里出现重名工具 " + name + "（同一面重复注册）。");
      }
      Set<String> roles = roleNamesOf(tool);
      if (!roles.contains(face.name())) {
        throw new IllegalStateException(
            "工具面装配故障："
                + name
                + "（"
                + tool.getClass().getName()
                + "）的 AgentTool.roles() 声明 "
                + roles
                + "（不含 "
                + face.name()
                + "），却注册在 "
                + face
                + " 面里 —— 包装后的实际实例必须原样声明该面角色（记录壳必须转发 roles()；"
                + "角色名只能从 SimosTool.toolRoles() 派生）。");
      }
    }
  }

  /** 双向对照（post-wrap，逐实例）：该实例的 {@code roles()} ⇔ 两份实际桶成员名单。 */
  private static void collectAgentToolFaults(
      Set<String> faults,
      List<AgentTool> face,
      Set<String> gmNames,
      Set<String> decisionNames) {
    for (AgentTool tool : face) {
      String name = tool.name();
      Set<String> roles = roleNamesOf(tool);
      boolean saysGm = roles.contains(ToolRole.GM.name());
      boolean saysDm = roles.contains(ToolRole.DM.name());
      if (saysGm != gmNames.contains(name)) {
        faults.add(
            name
                + "：AgentTool.roles() "
                + (saysGm ? "声明了 GM" : "没声明 GM")
                + "，实际"
                + (gmNames.contains(name) ? "注册在" : "没注册进")
                + " GM 面");
      }
      if (saysDm != decisionNames.contains(name)) {
        faults.add(
            name
                + "：AgentTool.roles() "
                + (saysDm ? "声明了 DM" : "没声明 DM")
                + "，实际"
                + (decisionNames.contains(name) ? "注册在" : "没注册进")
                + " 决策人面");
      }
    }
  }

  /** 读 {@code AgentTool.roles()}；null 按库契约的防呆口径读作"不表态"（空集），不在这里制造 NPE。 */
  private static Set<String> roleNamesOf(AgentTool tool) {
    Set<String> roles = tool.roles();
    return roles == null ? Set.of() : roles;
  }

  /** 逐个实例收集 role → tool；空白名收成空串交给库的目录防御（不静默丢弃，见 {@link #declaredRoleCatalog}）。 */
  private static void collectDeclarations(Map<String, Set<String>> catalog, List<AgentTool> face) {
    for (AgentTool tool : face) {
      Set<String> roles = tool.roles();
      if (roles == null) {
        continue; // 库契约要求非 null；null 读作"不表态"（同 RoleCatalogAudit 的缺省派生口径）
      }
      for (String role : roles) {
        String name = role == null ? "" : role;
        catalog.computeIfAbsent(name, key -> new TreeSet<>()).add(tool.name());
      }
    }
  }

  /** {@code toolRoles()} 的契约守卫：非 null、非空（空集 = 谁都看不见 = 装配故障，不是"合法形态"）。 */
  private static Set<ToolRole> rolesOf(SimosTool tool, String name) {
    Set<ToolRole> roles = tool.toolRoles();
    if (roles == null || roles.isEmpty()) {
      throw new IllegalStateException(
          "工具面装配故障："
              + name
              + " 的 toolRoles() 返回"
              + (roles == null ? " null" : "空集")
              + " —— 空集 = 谁都看不见 = 装配故障（SimosTool#roles 的契约：必须是 {GM} / {DM} / {GM, DM} 之一）。");
    }
    return roles;
  }
}
