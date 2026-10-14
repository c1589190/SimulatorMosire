package io.mosire.simos.app.decision;

import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.ga.decision.DecisionToolReach;
import io.mosire.simos.ga.model.DecisionMaker;
import java.util.Objects;

/**
 * **app 侧交给运行流的可达面**（{@link DecisionToolReach} 的实现）：这是"app 只负责组装"的接线点之一。
 *
 * <p>★★ <b>唯一真相 = {@link DecisionCallerFactory#whitelistFor(DecisionMaker)}</b>（全局决策人面 ∩ 该决策人的
 * {@code allowedTools}）——**同一份数据**也是"给模型看的工具面"（{@code DecisionAgentService} 把它作为 {@code
 * toolWhitelist} 传给运行流）与执行期权限组（{@code permissionsFor}）的来源。★ 本类**不另算**任何东西：它的整个实现就是转问那一个方法，
 * 故"消息里点名的"与"模型清单里有的"不可能各说各话（三处同源）。 ★ 反过来，若在这里另立一份判断（读 {@code roles()}、 读注册表、或抄一份清单），两份就会漂移 ——
 * 而漂移的症状是**静默的**：消息点名一件模型清单里没有的工具，模型不报错， 它只会去别处找（真机实测的 35 连撞，见 {@link DecisionToolReach} 类注）。
 *
 * <p>★ <b>为什么是"转问工厂"而不是把白名单抄进来</b>：白名单是**按决策人现算**的（{@code allowedTools} 会随 revision 变），
 * 抄一份快照就等于把可达面冻在装配时刻；本类每次问都走工厂，拿到的是**此刻**的那一份。
 *
 * <p>★ {@link #of(DecisionCallerFactory)} 的实例无状态、可共享（唯一字段是那个工厂）：装配方建一次，两条路（回合内注入与
 * "用户先说话"补身份消息）用同一个实例。
 */
public final class AppDecisionToolReach implements DecisionToolReach {

  private final DecisionCallerFactory callerFactory;

  private AppDecisionToolReach(DecisionCallerFactory callerFactory) {
    this.callerFactory = Objects.requireNonNull(callerFactory, "callerFactory");
  }

  /**
   * 按某个已装配的决策人调用者工厂建一个可达面端口。
   *
   * <p>★★ <b>必须传"与工具面同一个"工厂实例</b>：生产装配里 {@code Shell} 只建一个 {@code DecisionCallerFactory}，工具面
   * （{@code whitelistFor}）、权限组（{@code permissionsFor}）与本端口都从它取；另建一个工厂（哪怕参数相同）就又多了一处 可漂移的声明。
   */
  public static AppDecisionToolReach of(DecisionCallerFactory callerFactory) {
    return new AppDecisionToolReach(callerFactory);
  }

  @Override
  public boolean reachable(DecisionMaker dm, String toolName) {
    Objects.requireNonNull(dm, "dm");
    Objects.requireNonNull(toolName, "toolName");
    // ★ 唯一真相就是这一句：与"给模型看的工具面"同源（见类注）。
    return callerFactory.whitelistFor(dm).contains(toolName);
  }
}
