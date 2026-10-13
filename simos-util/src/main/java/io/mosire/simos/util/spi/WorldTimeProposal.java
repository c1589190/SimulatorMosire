package io.mosire.simos.util.spi;

import io.mosire.simos.util.state.ChangeSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 一次时间推进里**某一个参与者的提案**，可携带**多个模块**的变更集（2026-09-24 日制裁定的通用契约， POLITICAL_ECONOMY_DESIGN.md §9）。
 *
 * <p>★ 与 {@link TimeProposal} 的关系：后者是"单切片参与者"的窄形态（{@code namespace} 既是身份又是模块键）。
 * 多切片参与者（例如日结算协调器：一次推进同时改 social/property/production/ledger/market/government）用本类型， 用 {@code
 * participantId} 表身份、{@code moduleChanges} 逐模块交结果。**二者都在，不互相取代**： {@link TimeParticipant} 的默认
 * {@code simulateWorld} 把前者包装成后者，既有模块一行都不用改。
 *
 * <p>★ **它是提案，不是事实**：尚未跨模块解算、尚未校验、尚未提交。
 *
 * <p>★ {@code reads}/{@code writes} 是 **canonical 地址字符串**（C15），**按参与者整体声明**（不按模块拆）：
 * 日结算协调器读写的地址集天然跨域，拆开只会制造"同一地址在两个模块集合里都要写一遍"的重复。
 *
 * <p>★ 组件**防御性拷贝**且**保序**：{@link Set#copyOf}/{@link Map#copyOf} 的迭代序不是内容的纯函数（M2 Task 5 实测 30 次），
 * 而冲突报告与落盘的 {@code changeset_json} 都要求确定序，故用 {@code LinkedHashMap}/{@code LinkedHashSet} 包一层不可变视图。
 * 包装写在**字段赋值表达式上**（与 {@link TimeProposal} 同一条纪律：SpotBugs 的 EI_EXPOSE_REP 不做跨过程分析）。
 *
 * <p>★★ **2026-10-13 急停协议：新增 {@code incidents}（约束设计书 §4.2）**——与 {@link TimeProposal#incidents()}
 * 同义： 提案自此可以承载"**我发现了什么**"。★ **加字段 + 兼容构造器**（不变量 I7）：四参写法继续可用、既有参数顺序一位未动； 空表 =
 * "我没发现突发事件"，不是"我不提案"。它不进世界状态、不参与 Resolve 的读写集判定。
 */
public record WorldTimeProposal(
    String participantId,
    Map<String, ChangeSet> moduleChanges,
    Set<String> reads,
    Set<String> writes,
    List<Incident> incidents) {

  public WorldTimeProposal {
    Objects.requireNonNull(participantId, "participantId");
    if (participantId.isBlank()) {
      throw new IllegalArgumentException("participantId 不得为空白");
    }
    Objects.requireNonNull(moduleChanges, "moduleChanges");
    if (moduleChanges.isEmpty()) {
      // ★ 空提案不是"无事发生"，是**装配错误**：参与者清单是构造期定死的，每一个都在推进里被调用；
      //   "本日无事"应当交一个模块自己的**不变变更集**（Changed=Unchanged 分量），而不是交空 —— 后者会让
      //   "这一参与者是否真的参与了本次推进"从事件里彻底消失。
      throw new IllegalArgumentException(
          "moduleChanges 不得为空（participantId=" + participantId + "）：无事的参与者应交不变变更集");
    }
    LinkedHashMap<String, ChangeSet> moduleCopy = new LinkedHashMap<>();
    for (Map.Entry<String, ChangeSet> entry : moduleChanges.entrySet()) {
      moduleCopy.put(
          Objects.requireNonNull(entry.getKey(), "moduleChanges 的键"),
          Objects.requireNonNull(entry.getValue(), "moduleChanges 的值"));
    }
    moduleChanges = Collections.unmodifiableMap(moduleCopy);
    reads = Collections.unmodifiableSet(orderedCopy(reads, "reads"));
    writes = Collections.unmodifiableSet(orderedCopy(writes, "writes"));
    incidents = Collections.unmodifiableList(orderedIncidents(incidents));
  }

  /**
   * ★ **四参兼容构造器**（不变量 I7）：没有突发事件 ⇒ 空表。既有构造点（{@code EconomyOwnershipTimeParticipant}、 {@code
   * PopulationEconomyTimeParticipant}、{@code PopulationUnitTimeParticipant} + 测试夹具）**零改动**。
   */
  public WorldTimeProposal(
      String participantId,
      Map<String, ChangeSet> moduleChanges,
      Set<String> reads,
      Set<String> writes) {
    this(participantId, moduleChanges, reads, writes, List.of());
  }

  /**
   * 单切片提案的同形包装：模块键 = 该提案自己的 {@code namespace}。
   *
   * <p>这是 {@link TimeParticipant#simulateWorld} 默认实现的落点，也是 Core 把"单一模块参与者"和"多切片参与者" 统一喂给 {@code
   * TimeProposalResolver} 的桥。
   *
   * <p>★★ **{@code incidents} 必须随行**（2026-10-13 急停协议）：默认实现走的正是这条桥——**5 个参与者里 4 个用默认实现**，
   * 漏带就等于它们的突发事件在 Core 眼里不存在（急停静默失效）。当时写四参包装会**静默丢掉**它，故这里显式传五参。
   */
  public static WorldTimeProposal single(TimeProposal proposal) {
    Objects.requireNonNull(proposal, "proposal");
    return new WorldTimeProposal(
        proposal.namespace(),
        Map.of(proposal.namespace(), proposal.changeSet()),
        proposal.reads(),
        proposal.writes(),
        proposal.incidents());
  }

  /**
   * 逐元素 null 校验的**保序**拷贝（与 {@link TimeProposal} 同法）：少了逐元素那一层，集合里的 {@code null} 会活到 Resolve 的 {@code
   * retainAll} 才炸，红点离肇事点很远；失败消息恰是 "{@code <name>} 的元素"。
   */
  private static LinkedHashSet<String> orderedCopy(Set<String> source, String name) {
    Objects.requireNonNull(source, name);
    LinkedHashSet<String> copy = new LinkedHashSet<>();
    for (String element : source) {
      copy.add(Objects.requireNonNull(element, name + " 的元素"));
    }
    return copy;
  }

  /** 逐元素 null 校验的**保序**拷贝（与集合同法，失败消息点名 "{@code incidents 的元素}"）。 */
  private static List<Incident> orderedIncidents(List<Incident> source) {
    Objects.requireNonNull(source, "incidents");
    List<Incident> copy = new ArrayList<>();
    for (Incident incident : source) {
      copy.add(Objects.requireNonNull(incident, "incidents 的元素"));
    }
    return copy;
  }
}
