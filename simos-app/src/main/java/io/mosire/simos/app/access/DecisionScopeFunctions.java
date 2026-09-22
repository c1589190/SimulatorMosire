package io.mosire.simos.app.access;

import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 范围函数注册表（spec §3.1）：按 {@link Affiliation} 的**运行时类型**选函数。
 *
 * <p>★ **可扩展**（用户："后面可能会要求支持更复杂的权限函数操作"）：新增一类范围 = 新增一个 {@link DecisionScopeFunction} 实现 +
 * 注册一行，**调用点一行不改**。
 *
 * <p>★ **未注册的类型 ⇒ 响亮失败**：绝不静默给全量——"算不出来 ⇒ 放行"正是本阶段要消灭的形态 （spec §5.2：{@code
 * null}/空图是"不表态(放行)"，与"够不着"行为相反，配错即静默放宽）。 抛 {@link IllegalStateException}
 * 是因为它属**装配的错**（注册漏了一行），不是世界数据的问题。
 *
 * <p>★ 世界数据的问题（军队不存在、单位没有位置）**不抛**，由各实现给显式 deny-all—— 那是"这个决策人此刻什么都看不见"，不是"代码配错了"。
 */
public final class DecisionScopeFunctions {

  private final Map<Class<? extends Affiliation>, DecisionScopeFunction> byAffiliationType;

  /**
   * 按"归属类型 → 范围函数"建注册表。
   *
   * <p>冻结**写在赋值处**（SpotBugs 的 {@code EI_EXPOSE_REP} 只认它看得见的包装调用）。
   */
  public DecisionScopeFunctions(
      Map<Class<? extends Affiliation>, DecisionScopeFunction> functions) {
    Objects.requireNonNull(functions, "functions");
    Map<Class<? extends Affiliation>, DecisionScopeFunction> copy = new LinkedHashMap<>();
    for (Map.Entry<Class<? extends Affiliation>, DecisionScopeFunction> entry :
        functions.entrySet()) {
      copy.put(
          Objects.requireNonNull(entry.getKey(), "归属类型"),
          Objects.requireNonNull(entry.getValue(), "范围函数"));
    }
    this.byAffiliationType = Map.copyOf(copy);
  }

  /** 两个内置实现：{@code Nation ⇒ NationScope}、{@code Army ⇒ ArmyScope}。 */
  public static DecisionScopeFunctions defaults() {
    return new DecisionScopeFunctions(
        Map.of(
            Affiliation.Nation.class, NationScope.INSTANCE,
            Affiliation.Army.class, ArmyScope.INSTANCE));
  }

  /**
   * 现算这次调用者能碰哪些资源。
   *
   * @throws IllegalStateException 该归属类型没有注册范围函数（装配漏了注册，绝不静默给全量）
   */
  public ResourceScopeMap scopesFor(DecisionMaker dm, SimulationState state, String mapId) {
    Objects.requireNonNull(dm, "dm");
    Affiliation affiliation = dm.affiliation();
    DecisionScopeFunction function = byAffiliationType.get(affiliation.getClass());
    if (function == null) {
      throw new IllegalStateException(
          "归属类型没有注册范围函数（绝不静默给全量）: "
              + affiliation.getClass().getName()
              + "，已注册类型="
              + registeredTypes());
    }
    return function.scopesFor(dm, state, mapId);
  }

  /** 已注册的归属类型（排序后给人看；注册漏行时用来一眼看出漏了哪个）。 */
  private String registeredTypes() {
    return new TreeSet<>(byAffiliationType.keySet().stream().map(Class::getName).toList())
        .toString();
  }
}
