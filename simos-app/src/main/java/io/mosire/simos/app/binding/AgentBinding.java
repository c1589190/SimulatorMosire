package io.mosire.simos.app.binding;

import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.simos.util.identity.SubjectId;
import java.util.Objects;

/**
 * 一条 Agent 绑定（spec §6 的记录形状）：某决策人被授权在某主体上按某范围、某方式行动。
 *
 * <p>★ **模型层，不做执行**：本记录只是"记录 + 可绑性 + 查询"的产物；绑定后的执行（决策人产出 → Command）由 Brain/MainMosire
 * 消费，不在本仓实现（spec §6 边界）。
 *
 * <p>★ {@code permissions} 是 **AgentLib 的类型**（{@link AgentPermissionSet}）：绑定要把权限原样带上，供消费方取用；本仓不解释它。
 *
 * @param id 绑定身份
 * @param agentId 决策人身份（{@code agent:<id>}）
 * @param target 被绑主体的**稳定身份**（canonical 化后的 {@link SubjectId}，不是地址）
 * @param scope 决策范围（不透明标签）
 * @param mode 生效方式
 * @param permissions 决策人的权限集（AgentLib 类型）
 */
public record AgentBinding(
    BindingId id,
    AgentId agentId,
    SubjectId target,
    DecisionScope scope,
    BindingMode mode,
    AgentPermissionSet permissions) {

  public AgentBinding {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(agentId, "agentId");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(scope, "scope");
    Objects.requireNonNull(mode, "mode");
    Objects.requireNonNull(permissions, "permissions");
  }
}
