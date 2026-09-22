package io.mosire.simos.sd.model;

import io.mosire.simos.sd.id.DecisionMakerId;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 决策人（spec §三.2）：归属（Nation / Army）+ 窄工具白名单（N9）+ accessLimit（R10 / N6）+ 决策周期（N5）+ LLM provider
 * 引用（M11）。
 *
 * <p>★ **决策周期与命令延迟分开**（N5）：{@code decisionCadenceTicks} 是"多久能下一次决心"；命令延迟（决定→执行开始）是 **独立通道**（v1 落在
 * {@code Effect.readyAtTick}，见 spec §〇.3）。
 *
 * <p>★ **{@code providerId} 只是一个不透明的基础设施引用**（M11）：它指向 app / AgentLib 侧 LLM provider 配置里的一条记录， sd
 * **不解释、不校验其存在性**（配置归 AgentLib / app，sd 看不见——铁律 3 的结构化）。空 {@link Optional} = 未绑定。
 *
 * <p>★★ **回放语义**（M11，与铁律 2 一致）：{@code providerId} 是**世界事实**——它随 revision 落盘、可回放、可回退分岔。但 **provider
 * 的存在性不是**：旧 revision 里绑定的 provider 完全可能在新环境里已被删除/改名。⇒ sd **不**在命令期校验存在性、也**不**在重放期兜底； **使用时刻**（app
 * 层的 provider 解析）必须 **fail-closed**——解析不到就明确报错或走既定降级，**绝不静默换一个 provider、也绝不当成"未绑定"**。 把校验放进 sd
 * 会同时犯两个错：让 sd 依赖它看不见的配置（铁律 3），并把"配置缺失"与"世界事实"混为一谈。
 *
 * <p>★★ **{@code conversationGeneration} = 这个决策人的 LLM 会话"第几代"**（缺省 0，{@code
 * sd.ResetDecisionMakerConversation} 每次 +1）。它存在是因为**会话本身会在现实中坏掉**：改版前落盘的老会话在回放时会 400（历史里没有 {@code
 * reasoning_content}，补不上）⇒ 唯一的处置是**换一段新的重开**。把那件事做成一条命令（而不是改库/删文件），它就有了铁律 2 的全部性质：可回放、可回退分岔、AAR
 * 里看得见"谁在什么时候把谁的会话换掉了"。
 *
 * <p>★★ **为什么是"世代计数器"而不是一个"会话 id"字段**（评估过，决定不做）：
 *
 * <ol>
 *   <li>收一个 id，工具就能把某个决策人指向**别人的**会话（两点之间没有任何东西挡得住——id 只是个字符串），而计数器没有这个面；
 *   <li>id 是**基础设施的键**（AgentLib 那个会话库怎么组织、用什么前缀），让命令直接写它等于把 app 层的命名约定钉进世界事实 ⇒ 换命名就得改历史档；
 *   <li>计数器的语义**可读**："这个人的会话换过 3 次"是一句人话；一个裸 id 不是。
 * </ol>
 *
 * <p>★ **会话 id 仍由世界事实纯函数派生**（见 {@code DecisionAgentRunner.conversationIdOf}）——本类只存"第几代"，
 * **不认识**会话库、也不知道 id 长什么样（铁律 3：sd 看不见 app 层）。
 */
public record DecisionMaker(
    DecisionMakerId id,
    Affiliation affiliation,
    Set<String> allowedTools,
    AccessLimit accessLimit,
    long decisionCadenceTicks,
    Optional<String> providerId,
    long conversationGeneration) {

  /**
   * 5 参重载：新建的**没绑 provider、世代 0**的决策人。
   *
   * <p>★ 只应被**创建**路径使用（{@code sd.CreateDecisionMaker}）；**重建**既有决策人的处理器（如 {@link
   * io.mosire.simos.sd.spi.SetDecisionMakerAccessHandler} / {@link
   * io.mosire.simos.sd.spi.SetDecisionMakerProviderHandler}）**必须**把它自己的 {@code providerId()} **与**
   * {@code conversationGeneration()} 原样带过来，否则会静默丢绑定（M11 的变异靶子 m2）或 **静默把会话退回第 0 代**（=
   * 那个人又接回一段早已作废的会话）。
   */
  public DecisionMaker(
      DecisionMakerId id,
      Affiliation affiliation,
      Set<String> allowedTools,
      AccessLimit accessLimit,
      long decisionCadenceTicks) {
    this(id, affiliation, allowedTools, accessLimit, decisionCadenceTicks, Optional.empty(), 0L);
  }

  public DecisionMaker {
    if (id == null) {
      throw new IllegalArgumentException("id 不得为 null");
    }
    if (affiliation == null) {
      throw new IllegalArgumentException("affiliation 不得为 null");
    }
    if (accessLimit == null) {
      throw new IllegalArgumentException("accessLimit 不得为 null（无额外限制用 AccessLimit.empty()）");
    }
    if (decisionCadenceTicks < 1) {
      throw new IllegalArgumentException("decisionCadenceTicks 必须 ≥ 1: " + decisionCadenceTicks);
    }
    if (allowedTools == null) {
      throw new IllegalArgumentException("allowedTools 不得为 null");
    }
    if (providerId == null) {
      throw new IllegalArgumentException("providerId 不得为 null（未绑定用 Optional.empty()）");
    }
    if (providerId.isPresent() && providerId.get().isBlank()) {
      throw new IllegalArgumentException("providerId 不得为空白（未绑定用 Optional.empty()）");
    }
    // ★ 世代是**单调递增的计数器**，负数没有含义。它同时是**溢出的兜底**：世代已经顶到 Long.MAX_VALUE 时
    //   `+1` 会回绕成负数 ⇒ 那一条重置命令在这里被**响亮拒掉**（而不是把"第 -9223372036854775808 代"写进世界）。
    if (conversationGeneration < 0) {
      throw new IllegalArgumentException(
          "conversationGeneration 必须 ≥ 0: " + conversationGeneration);
    }
    Set<String> tools = new LinkedHashSet<>();
    for (String tool : allowedTools) {
      if (tool == null || tool.isBlank()) {
        throw new IllegalArgumentException("allowedTools 不得含空白");
      }
      tools.add(tool);
    }
    allowedTools = Collections.unmodifiableSet(tools); // ★ 冻在赋值处
  }
}
