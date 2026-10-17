package io.mosire.simos.ga.decision;

import io.mosire.simos.ga.model.DecisionMaker;
import java.util.Objects;
import java.util.Set;

/**
 * **决策人的可达面**（宿主注入）：回答"**这个决策人此刻能不能用这一件工具**"。
 *
 * <p>★★ <b>为什么必须有这个端口</b>（2026-10-14 真机实测缺陷的修法）：开场消息过去**无条件点名** {@code catalog} / {@code branches}
 * 等工具（"【先查看】…；【决策的两种表达】用 …"），而这些名字对**某些决策人根本够不着** —— 现场实测（决策人 {@code r8-dm}，{@code allowedTools} =
 * 6 件文档/工作区工具）里，模型读到"先查看 {@code simos_command_catalog}"之后
 * 发现自己的工具清单里**没有**这个名字，于是**换路径去够**：把工具名当成**文档挂载名**反复调 {@code doc_list}， 一轮 38 次工具调用里 **35 次**是同一条
 * {@code INVALID_ARGUMENTS} （{@code 虚拟路径的第一段不是合法挂载名…: 'simos_command_catalog'}），整轮预算烧光、决策没产出。 ⇒
 * 开场消息**不得把该决策人够不着的工具说成"你要用它"**，故本类把"够不够得着"这个**宿主事实**交给运行流。
 *
 * <p>★★ <b>谁是可达面的唯一真相</b>：{@code simos-app} 的 {@code DecisionCallerFactory}（{@code
 * whitelistFor(DecisionMaker)} = 全局决策人面 ∩ 该决策人的 {@code allowedTools}）。本接口只**问**， 绝不自己算 ——
 * 运行流若从工具声明（{@code toolRoles()}）、注册表或某个字面量清单推出第二份"可达面"，两份就会各说各话，
 * 而症状是**静默的**：消息里点名一件模型清单里没有的工具，模型不会报错，它只会去别处找（正是上面那次 35 连撞）。
 *
 * <p>★ <b>为什么是接口而不是直接读白名单</b>（照 {@link DecisionToolNames} 那套形制：接口在 {@code simos-ga}、实现在 {@code
 * simos-app}、装配处注入）：① {@code ga} 依赖 {@code app} 会成环（{@code app → ga → app}），故方向只能 "ga 定接口、app
 * 给实现"；② 同一个问题在**运行流之外**也要答（{@code DecisionAgentService#appendUserMessage}
 * 那条"用户先说话"的路要补同一条身份消息，手上并没有 runner）；③ 可达面的判据日后若要从"工具在白名单里"细化到 "它的资源面此刻也够得着"，改的是实现方一个类，运行流一个字不动。
 *
 * <p>★ <b>它答的是哪一层的"够得着"</b>：**工具面**那一层 —— 这个名字在不在系统**这次实际发给模型的工具清单**里 （{@code
 * DecisionToolDefs#requireAll} 已保证清单与注册表逐条对齐）。**资源面**那一层（某块地/某条记录可不可见）不归它管：
 * 那由每次调用现算的权限链判，消息里另有"范围之外的调用会被拒"与止损规则交代。
 *
 * <p>★ <b>时间口径</b>：答的是**此刻**。开场消息只在空会话首轮注入一次、此后**永久落盘**，而 {@code allowedTools} / 白名单都可能被 GM
 * 改（{@code ga.SetDecisionMakerAccess}）⇒ 消息里的点名词与后来的面可能不一致； 故消息正文一律写成
 * "以**你这次收到的工具清单**为准"这种**自纠正**措辞，而不是"你有/你没有 X"这种绝对断言（见 {@code
 * DecisionAgentRunner#openingUserMessage}）。
 */
@FunctionalInterface
public interface DecisionToolReach {

  /**
   * 这个决策人此刻能不能用这件工具。
   *
   * @param dm 要问的决策人（实现方按它取可达面；**不是**调用方自报的名字）
   * @param toolName **真实工具名**（{@code simos.command.catalog} 这种，不是线格式 {@code simos_command_catalog}）
   * @return {@code true} = 在它此刻的工具面里
   */
  boolean reachable(DecisionMaker dm, String toolName);

  /**
   * **退化形态**：调用方手上已经有一份"该决策人的工具面名单"时按它答（名单就是答案，不另算）。
   *
   * <p>★ 它服务的不是生产路径（生产走 {@code simos-app} 的实现），而是"已经显式给了白名单"的调用方 —— 运行流的旧构造形态与
   * 用例：那些调用方给的白名单**就是**它要发给模型的工具面，故"在不在清单里"与"够不够得着"是同一件事。 ★ 名字集合与 {@code dm}
   * 无关：这个形态**不按人**收窄（收窄发生在传入之前，见 {@code DecisionCallerFactory#whitelistFor}）。
   */
  static DecisionToolReach ofNames(Set<String> names) {
    Objects.requireNonNull(names, "names");
    Set<String> frozen = Set.copyOf(names);
    return (dm, toolName) -> frozen.contains(toolName);
  }
}
