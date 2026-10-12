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

/**
 * ★★ <b>工具角色声明的装配期 fail-closed 校验</b>（2026-10-12 B4，设计书 §4.4「一处声明、三处派生」/ 不变量 I-G3）。
 *
 * <p>★ <b>为什么是"校验"而不是"派生注册"</b>：本仓的工具是**显式 {@code new} 装配**的（{@code SimosToolSource} 里逐条 {@code
 * built.add(...)}），不是从磁盘发现的。若让 {@link SimosTool#roles()} 直接决定"注册进哪个桶"， 那么**标错一个角色 = 权限面静默变化**
 * （比如给只在 GM 桶的窄写多标一个 {@code DM} ⇒ 决策人桶凭空多一条写工具，而没有任何症状）。 ⇒ 本批的口径是：**装配面照旧显式写死**（顺序、内容一字不改）， {@code
 * roles()} 是**声明**，两者在装配完成处做**双向全量比对**，不一致**当场抛**。
 *
 * <p>★ <b>两段校验，各一次 O(n)，都在"装配完成处"</b>（不是每条工具建的时候各查一遍）：
 *
 * <ol>
 *   <li>{@link #requireFace} —— 单桶：桶里每条工具都必须实现 {@link SimosTool}（忘了声明 ⇒ 红，而不是静默放行）， 且 {@code
 *       roles()} 必须含该桶的角色；
 *   <li>{@link #requireFaces} —— 双桶：**成员关系 ⇔ 声明**（工具名在 GM 桶里 ⇔ {@code roles()} 含 {@code GM}；在决策人桶里
 *       ⇔ 含 {@code DM}）。这一半专抓两条"单桶看不出来"的失效：<b>标了 DM 却没注册进 DM 桶</b>、<b>只在 GM 桶的工具却标了 DM</b>。
 * </ol>
 *
 * <p>★ <b>为什么本类住在 {@code simos-ga}</b>：它只认 {@link AgentTool} + {@link ToolRole}（契约层类型），不认任何域类型与 app
 * 的桶枚举 —— 与 {@link ToolBuckets} 同款（"工具运作"的纯规则）。{@code app} 负责把 {@code Role} 映射成 {@link ToolRole}
 * 并调它。
 */
public final class ToolRoleAudit {

  private ToolRoleAudit() {}

  /**
   * 单桶校验：该桶里每条工具必须声明了该桶的角色（{@code SimosTool.roles()} 含 {@code face}）。
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
                + "）没有声明 SimosTool.roles() —— 「这个工具能被谁用」缺唯一拼写点"
                + "（设计书 §4.4 / I-G3）。不要静默放行：给它补上 roles()；"
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
                + " 桶里 —— roles() 声明与装配面必须逐条一致（设计书 §4.4 / I-G3）。");
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
      declared.put(tool.name(), ((SimosTool) tool).roles());
    }
    for (AgentTool tool : decisionFace) {
      declared.putIfAbsent(tool.name(), ((SimosTool) tool).roles());
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
                + "：roles() "
                + (saysGm ? "声明了 GM" : "没声明 GM")
                + "，装配面"
                + (gmNames.contains(name) ? "注册在" : "没注册进")
                + " GM 桶");
      }
      if (saysDm != decisionNames.contains(name)) {
        faults.add(
            name
                + "：roles() "
                + (saysDm ? "声明了 DM" : "没声明 DM")
                + "，装配面"
                + (decisionNames.contains(name) ? "注册在" : "没注册进")
                + " 决策人桶");
      }
    }
    if (!faults.isEmpty()) {
      throw new IllegalStateException(
          "工具面装配故障：SimosTool.roles() 的声明与两桶注册面不一致（设计书 §4.4 / I-G3）—— " + String.join("；", faults));
    }
  }

  /** {@code roles()} 的契约守卫：非 null、非空（空集 = 谁都看不见 = 装配故障，不是"合法形态"）。 */
  private static Set<ToolRole> rolesOf(SimosTool tool, String name) {
    Set<ToolRole> roles = tool.roles();
    if (roles == null || roles.isEmpty()) {
      throw new IllegalStateException(
          "工具面装配故障："
              + name
              + " 的 roles() 返回"
              + (roles == null ? " null" : "空集")
              + " —— 空集 = 谁都看不见 = 装配故障（SimosTool#roles 的契约：必须是 {GM} / {DM} / {GM, DM} 之一）。");
    }
    return roles;
  }
}
