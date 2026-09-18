package io.mosire.simos.app.binding;

import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.identity.QueryResult;
import io.mosire.simos.util.identity.ResolvedSubject;
import io.mosire.simos.util.identity.SubjectId;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.resolve.ResolverRegistry;
import io.mosire.simos.util.spi.AgentAttachPolicy;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 绑定注册表（spec §6，M5 T10）：**记录 + 可绑性 + 查询**，不做执行。
 *
 * <p>★ **本类是独立可注入组件**，M5 T10 **不**把它接进 {@code Shell}（保持与其他工作树文件不相交）。装配归后续任务。
 *
 * <h2>bind 的四步（R8 的载体）</h2>
 *
 * <ol>
 *   <li><b>canonical 化</b>——用注入的 {@link ResolverRegistry} 把地址解析成**唯一**主体；0 个候选或多解都拒绝。
 *   <li><b>问策略</b>——按 canonical 地址的**首段命名空间**取 {@link AgentAttachPolicy}；没注册 ⇒ 拒绝，策略说不行 ⇒ 拒绝。
 *   <li><b>查重</b>——同一 {@code (agentId, target)} 只允许一条，重复 ⇒ 拒绝。
 *   <li><b>落记录</b>——生成 {@link BindingId}，原样存下 {@link AgentPermissionSet}（AgentLib 类型），返回绑定。
 * </ol>
 *
 * <p>★ **每一步的"拒绝"都是可读的 {@link IllegalArgumentException}**：绑定不是世界状态写面，坏了就当场炸、不留半条记录， 不引入新异常类型（spec
 * §6 未给异常契约）。
 *
 * <p>★ **无并发承诺**：M5 单进程单实例（spec §3.4）；本类不加锁。
 */
public final class BindingRegistry {

  private final ResolverRegistry resolverRegistry;
  private final Map<String, AgentAttachPolicy> policies = new LinkedHashMap<>();
  private final Map<BindingId, AgentBinding> bindings = new LinkedHashMap<>();
  private final Set<AgentTarget> seen = new LinkedHashSet<>();

  /**
   * @param resolverRegistry 各命名空间的解析器（canonical 化用；不得为 null）
   * @param policies 模块声明的策略（可为空表——此时任何 bind 都因"无策略"被拒）
   */
  public BindingRegistry(ResolverRegistry resolverRegistry, List<AgentAttachPolicy> policies) {
    this.resolverRegistry = Objects.requireNonNull(resolverRegistry, "resolverRegistry");
    Objects.requireNonNull(policies, "policies");
    for (AgentAttachPolicy policy : policies) {
      Objects.requireNonNull(policy, "policy");
      String namespace = policy.namespace();
      if (namespace == null || namespace.isBlank()) {
        throw new IllegalArgumentException("AgentAttachPolicy.namespace() 不得为空白");
      }
      if (this.policies.putIfAbsent(namespace, policy) != null) {
        throw new IllegalArgumentException("命名空间 " + namespace + " 已有 AgentAttachPolicy，不允许重复注册");
      }
    }
  }

  /**
   * 绑定：canonical 化主体 → 问策略 → 查重 → 落记录。
   *
   * @param agentId 决策人（{@code agent:<id>}）
   * @param subject 被绑主体的地址（可以是 Human 形式；本方法负责 canonical 化）
   * @param scope 决策范围
   * @param mode 生效方式
   * @param permissions 决策人权限集（原样存储）
   * @param ctx 解析上下文（{@code state} + {@code at}）
   * @return 新建的绑定（{@code target} 是 canonical 化后的 {@link SubjectId}）
   * @throws IllegalArgumentException 主体无候选/多解、命名空间无策略、策略拒绝、或 {@code (agentId, target)} 重复
   */
  public AgentBinding bind(
      AgentId agentId,
      Address subject,
      DecisionScope scope,
      BindingMode mode,
      AgentPermissionSet permissions,
      ResolveContext ctx) {
    Objects.requireNonNull(agentId, "agentId");
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(permissions, "permissions");
    Objects.requireNonNull(ctx, "ctx");

    ResolvedSubject resolved = canonicalize(subject, ctx);
    Address canonical = Address.parse(resolved.canonicalAddress());

    AgentAttachPolicy policy = policies.get(canonical.namespace());
    if (policy == null) {
      throw new IllegalArgumentException(
          "命名空间 "
              + canonical.namespace()
              + " 没有注册 AgentAttachPolicy（已注册："
              + policies.keySet()
              + "），拒绝绑定："
              + canonical.canonical());
    }
    if (!policy.canAttach(canonical, ctx)) {
      throw new IllegalArgumentException(
          "策略拒绝：命名空间 " + canonical.namespace() + " 的 " + canonical.canonical() + " 不可绑决策人");
    }

    AgentTarget key = new AgentTarget(agentId, resolved.id());
    if (seen.contains(key)) {
      throw new IllegalArgumentException(
          "重复绑定（agentId + target）：" + agentId.value() + " → " + targetText(resolved.id()));
    }

    AgentBinding binding =
        new AgentBinding(BindingId.generate(), agentId, resolved.id(), scope, mode, permissions);
    bindings.put(binding.id(), binding);
    seen.add(key);
    return binding;
  }

  /** 解绑。返回是否真的删掉了一条（不存在 ⇒ {@code false}，不抛）。 */
  public boolean unbind(BindingId id) {
    Objects.requireNonNull(id, "id");
    AgentBinding removed = bindings.remove(id);
    if (removed == null) {
      return false;
    }
    seen.remove(new AgentTarget(removed.agentId(), removed.target()));
    return true;
  }

  /** 全部绑定，注册序。 */
  public List<AgentBinding> list() {
    return List.copyOf(bindings.values());
  }

  /** 绑在该主体上的全部绑定，注册序。 */
  public List<AgentBinding> bySubject(SubjectId subject) {
    Objects.requireNonNull(subject, "subject");
    return bindings.values().stream().filter(binding -> binding.target().equals(subject)).toList();
  }

  /** 该决策人的全部绑定，注册序。 */
  public List<AgentBinding> byAgent(AgentId agentId) {
    Objects.requireNonNull(agentId, "agentId");
    return bindings.values().stream().filter(binding -> binding.agentId().equals(agentId)).toList();
  }

  /** 解析为**唯一**主体；0 个候选或多解都拒绝（多解要调用方先给 canonical 地址）。 */
  private ResolvedSubject canonicalize(Address subject, ResolveContext ctx) {
    QueryResult result = resolverRegistry.resolve(subject, ctx);
    if (result.candidates().isEmpty()) {
      throw new IllegalArgumentException("无法解析主体（没有候选）：" + subject.canonical());
    }
    if (result.candidates().size() > 1) {
      throw new IllegalArgumentException(
          "主体多解（"
              + result.candidates().size()
              + " 个候选），绑定需要唯一主体，请用 canonical 地址："
              + subject.canonical());
    }
    return result.candidates().get(0);
  }

  private static String targetText(SubjectId id) {
    return id.namespace() + ":" + id.localId();
  }

  /** 查重键：同一决策人 + 同一主体只允许一条绑定。 */
  private record AgentTarget(AgentId agentId, SubjectId target) {}
}
