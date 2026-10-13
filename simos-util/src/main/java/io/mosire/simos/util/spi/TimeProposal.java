package io.mosire.simos.util.spi;

import io.mosire.simos.util.state.ChangeSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一次时间推进里**某一个模块的提案**（spec §5.2）。
 *
 * <p>★ **它是提案，不是事实**：模块自己算出、尚未跨模块解算、尚未校验、尚未提交。
 *
 * <p>★ {@code reads} / {@code writes} 是 **canonical 地址字符串**（C15）。v1 **只做精确集合匹配**，不做前缀
 * 包含——前缀会引入地址语义，而 Core 不懂地址语义。
 *
 * <p>★ 组件**防御性拷贝**且**保序**：{@link Set#copyOf} 的迭代序不是键集的纯函数（M2 Task 5 实测 30 次）， 而 Resolve
 * 的冲突报告要求**字典序可比**（C15），故用 {@code LinkedHashSet} 包一层不可变视图。★ 包装这一步必须**写在字段赋值表达式上**（构造器里直接 {@code
 * Collections.unmodifiableSet(...)}）：SpotBugs 的 EI_EXPOSE_REP 不做跨过程分析，藏在私有方法里的包装它看不见——实测会报两处
 * Medium（record 访问器"暴露内部表示"）。
 *
 * <p>★★ **2026-10-13 急停协议：新增 {@code incidents}（约束设计书 §4.2）**——提案自此可以承载"**我发现了什么**"，而不只是"我要改什么"。
 * 它是**附加通道**，与 {@code changeSet} 正交：
 *
 * <ul>
 *   <li>{@code incidents} 为空 = "**我没发现突发事件**"，**不是**"我不提案"（"不提案"仍是不变变更集）；
 *   <li>★ **加字段 + 兼容构造器**（不变量 I7）：既有参数顺序一位未动，{@code records} 的四参写法继续可用（5 个参与者实现 + 多测试夹具都在构造它）；
 *   <li>★ 它**不是** {@code reads}/{@code writes} 的地址：突发事件不进世界状态、不参与 Resolve 的读写集判定（它是过程观测，见 {@link
 *       Incident}）。
 * </ul>
 */
public record TimeProposal(
    String namespace,
    ChangeSet changeSet,
    Set<String> reads,
    Set<String> writes,
    List<Incident> incidents) {

  public TimeProposal {
    Objects.requireNonNull(namespace, "namespace");
    Objects.requireNonNull(changeSet, "changeSet");
    reads = Collections.unmodifiableSet(orderedCopy(reads, "reads"));
    writes = Collections.unmodifiableSet(orderedCopy(writes, "writes"));
    incidents = Collections.unmodifiableList(orderedIncidents(incidents));
  }

  /**
   * ★ **四参兼容构造器**（不变量 I7）：没有突发事件 ⇒ 空表。既有构造点（5 个参与者实现 + 测试夹具）**零改动**。
   *
   * <p>与规范构造器并存正是"加字段不改既有写法"的兑现；两个构造器**不歧义**（参数个数不同）。
   */
  public TimeProposal(
      String namespace, ChangeSet changeSet, Set<String> reads, Set<String> writes) {
    this(namespace, changeSet, reads, writes, List.of());
  }

  /**
   * 逐元素 null 校验的**保序**拷贝。★ 逐元素那一层是必须的：少了它，集合里的 {@code null} 会活到 Resolve 的 {@code retainAll}
   * 才炸，红点离肇事点很远；失败消息恰是 "{@code <name>} 的元素"，测试用 {@code hasMessage} 精确匹配钉住它。
   */
  private static LinkedHashSet<String> orderedCopy(Set<String> source, String name) {
    Objects.requireNonNull(source, name);
    LinkedHashSet<String> copy = new LinkedHashSet<>();
    for (String element : source) {
      copy.add(Objects.requireNonNull(element, name + " 的元素"));
    }
    return copy;
  }

  /** 逐元素 null 校验的**保序**拷贝（与集合同法：{@code List.copyOf} 的失败消息不点名参数，红点会离肇事点很远）。 */
  private static List<Incident> orderedIncidents(List<Incident> source) {
    Objects.requireNonNull(source, "incidents");
    List<Incident> copy = new ArrayList<>();
    for (Incident incident : source) {
      copy.add(Objects.requireNonNull(incident, "incidents 的元素"));
    }
    return copy;
  }
}
