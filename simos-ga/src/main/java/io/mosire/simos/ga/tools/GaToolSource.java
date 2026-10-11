package io.mosire.simos.ga.tools;

import io.mosire.agentlib.plugin.ToolSource;
import io.mosire.agentlib.tool.AgentTool;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * ★★ <b>工具源的运行时承载</b>（2026-10-12 B2b，设计书 §4.5「从 app 迁入 {@code SimosToolSource}（装配两桶）」的
 * <b>可搬那一半</b>）：一份**构造期定死的静态工具清单** + {@link ToolSource} 契约的三个出口（{@code id} / {@code listTools} /
 * {@code onChange}）。
 *
 * <p>★ <b>为什么装配不在这里</b>：{@code SimosToolSource} 的构造器要 {@code new} 出**全部 16 个模块**的工具实例 （map / unit /
 * social / economy / actor / gov / army / sd / 创世三件套…），并注入 core、QueryService、 ApprovalCoordinator
 * —— 那是**组合根**的活。{@code ga} 的 enforcer 当场禁 {@code simos-app}/{@code simos-map}/… ⇒ 把装配搬进 {@code ga}
 * 就是 {@code app → ga → app} 环（设计书 §5 I-G2，实测 Maven 建图期直接拒）。<b>所以搬的是运行面、 留的是装配面</b>：app 造好 {@code
 * List<AgentTool>} 后交给本类，本类只负责"这份清单怎么被读、怎么通知、怎么冻结"。
 *
 * <p>★ <b>清单是冻结快照</b>：构造期拷贝一次（{@link Collections#unmodifiableList} 保序冻结，<b>不用</b> {@code
 * List.copyOf}——本仓明文口径：迭代序不是内容的纯函数），与迁入前 {@code List.copyOf(built)} 的语义逐条相同（列表内容不可变、 顺序即注册顺序）。
 *
 * <p>★ <b>{@code onChange} 是 no-op</b>：本源的集合是静态集（没有插件式的动态增删口子）⇒ 订阅即时成立、无需变更通知（与迁入前
 * 逐字同款）。若将来工具集变成动态的，替换这里的空实现即可（契约要求返回可关闭句柄）。
 */
public final class GaToolSource implements ToolSource {

  /** 供给源标识（同一运行时内唯一；审计与按源卸载依赖它）。 */
  public static final String ID = "simos";

  private final List<AgentTool> tools;

  private GaToolSource(List<AgentTool> tools) {
    this.tools = Collections.unmodifiableList(new ArrayList<>(tools));
  }

  /**
   * 冻结一份工具清单成工具源。
   *
   * @param tools 装配面造好的工具清单（非空；顺序 = 注册顺序，逐位保留）
   */
  public static GaToolSource of(List<AgentTool> tools) {
    return new GaToolSource(Objects.requireNonNull(tools, "tools"));
  }

  @Override
  public String id() {
    return ID;
  }

  @Override
  public List<AgentTool> listTools() {
    return tools;
  }

  /** 静态工具集：订阅即时成立、永不变更，故返回空句柄（契约要求非 null）。 */
  @Override
  public AutoCloseable onChange(Runnable listener) {
    Objects.requireNonNull(listener, "listener");
    return () -> {};
  }
}
