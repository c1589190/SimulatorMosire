package io.mosire.simos.ga.tools;

import io.mosire.agentlib.tool.AgentTool;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * ★★ <b>工具分桶的运行时规则</b>（2026-10-12 B2b）：从 app 的 {@code SimosToolSource#readToolsFor} 抽出的**纯函数**——
 * "读工具两档共享，显式声明 GM-only 的那几条只进 GM 档"。
 *
 * <p>★ <b>判据为什么由装配面注入</b>：GM-only 的标记接口有两支 —— 契约层的 {@link io.mosire.simos.ga.spi.GmOnlyRead} 与 app
 * 侧**尚未退役**的 {@code io.mosire.simos.app.tools.GmOnlyRead}（B4 收口）。本模块看不见 app 的类型，故本类只承载
 * <b>规则</b>（"命中标记 ⇒ 不进决策人档"），"哪些类命中"由组合根以 {@link Predicate} 传入 —— 两支标记的**并集**仍是唯一拼写点
 * （不抄第二份判定，也不放松任何一条）。
 *
 * <p>★ <b>注册顺序一字不改</b>：过滤保序（{@link Collections#unmodifiableList} 包一层 {@link ArrayList}，**不用**
 * {@code List.copyOf}），与迁入前 {@code new ArrayList<>(all.size())} + 逐条 {@code add} 的迭代序逐位相同。
 */
public final class ToolBuckets {

  private ToolBuckets() {}

  /**
   * 决策人档可见的读工具 = 全量读工具 − 命中 {@code gmOnly} 的那些（**保序**）。
   *
   * @param all 全量读工具（构造顺序 = 注册顺序）
   * @param gmOnly "这条读工具只给 GM"的判据（装配面注入：契约层标记 ∪ app 标记）
   */
  public static List<AgentTool> decisionAgentReads(
      List<AgentTool> all, Predicate<AgentTool> gmOnly) {
    Objects.requireNonNull(all, "all");
    Objects.requireNonNull(gmOnly, "gmOnly");
    List<AgentTool> shared = new ArrayList<>(all.size());
    for (AgentTool tool : all) {
      if (!gmOnly.test(tool)) {
        shared.add(tool);
      }
    }
    return Collections.unmodifiableList(shared);
  }

  /**
   * ★★ <b>从一份（已由 {@link ToolRoleAudit} 校验过的）工具面派生工具名集合</b>（2026-10-12 B4）：这就是生产路径**决策人白名单**的来源 ——
   * 白名单不手抄任何条数，而是"决策人桶里有哪些工具"的纯函数（B4b 起生产里已无任何手写清单）。
   *
   * <p>★ <b>保序</b>：{@link LinkedHashSet} 按工具面的注册顺序收（与白名单冻结在赋值处的既有口径一致—— 迭代序是内容的纯函数，不做排序、也不依赖 {@code
   * Set.copyOf} 的未定义序）。
   *
   * @param tools 工具面（构造顺序 = 注册顺序）
   */
  public static Set<String> namesOf(List<AgentTool> tools) {
    Objects.requireNonNull(tools, "tools");
    Set<String> names = new LinkedHashSet<>(tools.size());
    for (AgentTool tool : tools) {
      names.add(tool.name());
    }
    return Collections.unmodifiableSet(names);
  }
}
