package io.mosire.simos.app.access;

import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.army.ArmyAffiliations;
import io.mosire.simos.gov.GovAffiliations;
import io.mosire.simos.gov.nation.NationAffiliations;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 范围函数注册表（spec §3.1）：按<b>归属种类名</b>（{@code kind} 字符串，<b>各模块自报</b>）选函数。
 *
 * <p>★★★ <b>2026-10-12 乙收尾：键从 {@code Class<? extends Affiliation>} 改成 kind
 * 字符串</b>（用户原话「<b>归属肯定归模块啊， 后面新加模块 ga 还得显式地重新添加？不可能的</b>」）：
 *
 * <ul>
 *   <li>旧形状要求"决策人身上带着一个 {@code Affiliation} 密封类型"，注册表按<b>运行时类型</b>分派 ⇒ 每加一个模块都要在 {@code ga}（值类型）与
 *       app（注册）各改一处，且 {@code Affiliation} 这个类必须<b>预先知道所有种类</b>；
 *   <li>新形状：决策人<b>不带归属</b>（{@link DecisionMaker} 已去掉那个字段），各自的表住各模块；本类按 kind 查函数， kind 由<b>模块自己的
 *       {@code KIND} 常量</b>给（{@link NationAffiliations#KIND} / {@link GovAffiliations#KIND} /
 *       {@link ArmyAffiliations#KIND}）。★ <b>加一个新模块 = 各域加一个 provider + 这里注册一行</b>， {@code simos-ga}
 *       / {@code simos-ga-spi} <b>一个字都不用改</b>。
 * </ul>
 *
 * <p>★ <b>可扩展</b>（用户："后面可能会要求支持更复杂的权限函数操作"）：新增一类范围 = 新增一个 {@link DecisionScopeFunction} 实现 +
 * 注册一行，<b>调用点一行不改</b>。
 *
 * <p>★★ <b>三种失败语义，逐条保持</b>：
 *
 * <ol>
 *   <li><b>没有归属 ⇒ deny-all</b>（裸决策人；用户原话「允许创建没有更多附加状态的单个决策人」）：四个命名空间全部 {@link
 *       ResourceScope#none()}——<b>不抛、不回落、不给全量</b>；
 *   <li><b>未注册 kind ⇒ 响亮失败</b>：某个模块报了归属、而这里没注册它的范围函数 ⇒ {@link IllegalStateException}（装配漏了一行）
 *       ——绝不静默给全量、也绝不静默 deny-all（那是"能力悄悄消失"）；
 *   <li><b>世界数据的问题</b>（军队不存在、单位没有位置）**不抛**，由各实现给显式 deny-all。
 * </ol>
 *
 * <p>★ <b>多轴归属取并集</b>（如实记，这是本批的新语义面）：同一个决策人可能被多个模块同时认领（Nation 一张表、Army 一张表、 GOV 单位一张表，各模块只拥有自己那张）⇒
 * 逐轴现算后<b>按命名空间取并集</b>。★ 只有一条归属时，并集与那一条<b>逐字段相同</b> （其余轴给的是 {@code none()}）：与迁移前的可见范围逐条等价。
 */
public final class DecisionScopeFunctions {

  private final Map<String, DecisionScopeFunction> byKind;

  /**
   * 按"归属种类名 → 范围函数"建注册表。
   *
   * <p>冻结**写在赋值处**（SpotBugs 的 {@code EI_EXPOSE_REP} 只认它看得见的包装调用）。
   */
  public DecisionScopeFunctions(Map<String, DecisionScopeFunction> functions) {
    Objects.requireNonNull(functions, "functions");
    Map<String, DecisionScopeFunction> copy = new LinkedHashMap<>();
    for (Map.Entry<String, DecisionScopeFunction> entry : functions.entrySet()) {
      String kind = Objects.requireNonNull(entry.getKey(), "归属种类名");
      if (kind.isBlank()) {
        throw new IllegalArgumentException("归属种类名不得为空白");
      }
      copy.put(kind, Objects.requireNonNull(entry.getValue(), "范围函数"));
    }
    if (copy.isEmpty()) {
      throw new IllegalArgumentException("范围函数注册表不得为空：空表 = 任何决策人都算不出范围（装配故障，绝不静默）");
    }
    this.byKind = Map.copyOf(copy);
  }

  /**
   * 三个内置实现（种类的名字<b>取自各模块自己的常量</b>）：{@code nation ⇒ NationScope}、{@code army ⇒ ArmyScope}、 {@code
   * gov ⇒ GovScope}。
   */
  public static DecisionScopeFunctions defaults() {
    return new DecisionScopeFunctions(
        Map.of(
            NationAffiliations.KIND, NationScope.INSTANCE,
            ArmyAffiliations.KIND, ArmyScope.INSTANCE,
            GovAffiliations.KIND, GovScope.INSTANCE));
  }

  /**
   * 现算这次调用者能碰哪些资源。
   *
   * @throws IllegalStateException 该决策人有一条归属、而它的种类没有注册范围函数（装配漏了注册，绝不静默给全量/deny-all）
   */
  public ResourceScopeMap scopesFor(DecisionMaker dm, SimulationState state, String mapId) {
    Objects.requireNonNull(dm, "dm");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(mapId, "mapId");
    // ★ 归属由各模块自报（唯一拼装点 = AppDecisionMakerAffiliations）：一条都没有 ⇒ 裸决策人 ⇒ deny-all。
    Set<String> kinds = AppDecisionMakerAffiliations.kindsOf(dm.id(), state);
    if (kinds.isEmpty()) {
      return denyAll();
    }
    ResourceScopeMap computed = null;
    for (String kind : kinds) {
      DecisionScopeFunction function = byKind.get(kind);
      if (function == null) {
        throw new IllegalStateException(
            "归属种类没有注册范围函数（绝不静默给全量、也绝不静默 deny-all）: " + kind + "，已注册种类=" + registeredKinds());
      }
      ResourceScopeMap one = function.scopesFor(dm, state, mapId);
      computed = computed == null ? one : union(computed, one);
    }
    return computed;
  }

  /**
   * ★★ <b>裸决策人的 deny-all</b>（四个命名空间全部 {@link ResourceScope#none()}）：与三个 scope 实现里的同名私有方法同一口径 ——
   * "什么都不表态"（空图 / 少配命名空间）会回落到工具缺省策略 ⇒ <b>静默放宽</b>，方向与"够不着"相反（spec §5.2 第 3 条）。
   */
  private static ResourceScopeMap denyAll() {
    return ResourceScopeMap.of(ToolSupport.MAP_NAMESPACE, ResourceScope.none())
        .withNamespace(ToolSupport.UNIT_NAMESPACE, ResourceScope.none())
        .withNamespace(ToolSupport.SOCIAL_NAMESPACE, ResourceScope.none())
        .withNamespace(ToolSupport.ACTOR_NAMESPACE, ResourceScope.none());
  }

  /**
   * 两张范围图<b>按命名空间取并集</b>（多轴归属）。
   *
   * <p>★ 命名空间并集 = 两边声明过的命名空间都要出现（缺席 = "本层不表态" ⇒ 会回落工具缺省 ⇒ 静默放宽，见 {@link #denyAll}）；
   * 单个命名空间内的作用域取前缀并集（{@code 不限 ∪ X = 不限}，空集 ∪ X = X）。★ 三个 scope 实现从不产出"不限"，故并集不会凭空放大。
   */
  private static ResourceScopeMap union(ResourceScopeMap left, ResourceScopeMap right) {
    Set<String> namespaces = new LinkedHashSet<>(left.byNamespace().keySet());
    namespaces.addAll(right.byNamespace().keySet());
    Map<String, ResourceScope> merged = new LinkedHashMap<>();
    for (String namespace : namespaces) {
      // ★ 某一边"没表态"（declaredScope 返回 null）在并集里按 none() 读：那一侧对这个命名空间无话可说，
      //   能说话的那一侧说了算；两边都不说的情况不存在（namespace 取自两边的键并集）。
      merged.put(
          namespace, union(declaredOrNone(left, namespace), declaredOrNone(right, namespace)));
    }
    return ResourceScopeMap.of(merged);
  }

  /** 某张图对某命名空间的取值；未表态 ⇒ {@link ResourceScope#none()}（并集里的"无话可说"，见 {@link #union}）。 */
  private static ResourceScope declaredOrNone(ResourceScopeMap map, String namespace) {
    ResourceScope scope = map.declaredScope(namespace);
    return scope == null ? ResourceScope.none() : scope;
  }

  /** 单个命名空间内的并集：{@code 不限} 吸收一切；两个受限集取前缀并集；空并集 ⇒ {@code none()}（显式"够不着"）。 */
  private static ResourceScope union(ResourceScope left, ResourceScope right) {
    if (left.unrestricted() || right.unrestricted()) {
      return ResourceScope.unlimited();
    }
    Set<String> prefixes = new LinkedHashSet<>(left.prefixes());
    prefixes.addAll(right.prefixes());
    return prefixes.isEmpty()
        ? ResourceScope.none()
        : ResourceScope.of(prefixes.toArray(String[]::new));
  }

  /** 已注册的归属种类（排序后给人看；注册漏行时用来一眼看出漏了哪个）。 */
  public String registeredKinds() {
    return new TreeSet<>(byKind.keySet()).toString();
  }
}
