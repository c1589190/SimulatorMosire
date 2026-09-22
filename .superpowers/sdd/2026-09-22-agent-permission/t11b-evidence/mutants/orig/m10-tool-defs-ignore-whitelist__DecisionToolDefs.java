package io.mosire.simos.app.decision;

import io.mosire.agentlib.llm.ToolDef;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolDefs;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.simos.app.access.DecisionCallerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * **决策人可见的工具面 → AgentLib 的 {@link ToolDef}**（spec §2.3 要点 1）：给模型看的工具清单与 {@link
 * DecisionCallerFactory} 的**权限组白名单同源**。
 *
 * <p>★ **为什么"看得见的"必须等于"调得动的"**：模型只会请求它看得见的工具；如果这里多给了（比如把 GM 的通用写也塞进来），
 * 模型会去调、然后在权限层被拒——一轮对话就此浪费且暴露了工具面的形状；如果这里少给了，能力静默消失、**不报错**。两种错位都没有症状， 故唯一的解法是**同一份数据**（{@code
 * callerFactory.whitelist()}）。
 *
 * <p>★ **交集形态 vs 响亮形态**：{@link #of} 取"白名单 ∩ 注册表"（静默取交）；{@link #requireAll}
 * 在此之上**要求白名单里每一条都在注册表里**，缺一条就抛。
 *
 * <p>★ **为什么运行流必须用响亮形态**：运行流拿到的注册表是**决策人桶**（{@code Shell.toolsFor(Role.DECISION_AGENT)}）——
 * 那条桶与白名单本应逐条对齐（两处都由 T10 的裁决收窄过）。若某次改动把桶里少放了一条，{@link #of} 会**静默**地把那个能力从
 * 决策人手里拿走（模型再也不请求它，也不会有任何报错）；本仓的口径是"装配故障当场炸，不静默兜底"（T6 裁定），故运行流走这一条。
 *
 * <p>★ **输出有序**（按工具名排序）：工具列表进的是 LLM 请求，无序会让同一份权限面产出不同字节的请求体（本仓的"同状态同字节"口径）。
 */
public final class DecisionToolDefs {

  private DecisionToolDefs() {}

  /** 决策人默认工具面（{@link DecisionCallerFactory#WHITELIST} ∩ 注册表）。 */
  public static List<ToolDef> of(ToolRegistry registry) {
    return of(registry, DecisionCallerFactory.WHITELIST);
  }

  /**
   * 白名单 ∩ 注册表。
   *
   * @param registry 工具注册表（生产路径给**决策人桶**）
   * @param allowed 允许出现的工具名（生产路径给 {@link DecisionCallerFactory#whitelist()}）
   */
  public static List<ToolDef> of(ToolRegistry registry, Set<String> allowed) {
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(allowed, "allowed");
    List<ToolDef> defs = new ArrayList<>();
    for (String name : new TreeSet<>(allowed)) {
      AgentTool tool = registry.find(name).orElse(null);
      if (tool != null) {
        defs.add(ToolDefs.of(tool));
      }
    }
    return List.copyOf(defs);
  }

  /**
   * 响亮形态：白名单里**每一条**都必须在注册表里，否则抛（装配故障，见类注）。
   *
   * @throws IllegalStateException 白名单里有注册表查不到的工具（消息里逐条列出缺了哪些）
   */
  public static List<ToolDef> requireAll(ToolRegistry registry, Set<String> allowed) {
    Objects.requireNonNull(registry, "registry");
    Objects.requireNonNull(allowed, "allowed");
    List<String> missing =
        allowed.stream().filter(name -> registry.find(name).isEmpty()).sorted().toList();
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          "决策人的工具面不完整——白名单里有 " + missing.size() + " 条工具不在注册表里（装配故障）: " + missing);
    }
    return of(registry, allowed);
  }
}
