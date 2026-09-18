package io.mosire.simos.unit.spi;

import io.mosire.simos.unit.resolve.UnitResolver;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.identity.QueryResult;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.spi.AgentAttachPolicy;
import java.util.Objects;

/**
 * 单位侧的可绑性策略（spec §6，M5 T10）：**任意（存在的）{@code Unit} 可绑决策人**。
 *
 * <p>★ **判定只经本模块自己的类型**：委托 {@link UnitResolver} 解析——它产出 {@code typeName = "Unit"} 的即单位； {@code
 * unit:<id>:equipment.<名>} 产出 {@code "Equipment"}，落选。app 层看不到 {@code Unit} 这个类。
 *
 * <p>★ {@code UnitResolver} 的链式定位可多解（同名单位）；但 {@code BindingRegistry} 在问策略**之前**已 canonical 化并拒掉多解，
 * 故本策略收到的是唯一主体的 canonical 地址。这里的 {@code anyMatch} 对 canonical 地址恒等价于"唯一候选是 Unit"。
 */
public final class UnitAgentAttachPolicy implements AgentAttachPolicy {

  private static final String NAMESPACE = "unit";
  private static final String UNIT_TYPE = "Unit";

  private final UnitResolver resolver = new UnitResolver();

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public boolean canAttach(Address subject, ResolveContext ctx) {
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(ctx, "ctx");
    if (!NAMESPACE.equals(subject.namespace())) {
      return false; // 外来命名空间不服务：认领与否由返回值表达，不去触碰本模块的状态
    }
    QueryResult result = resolver.resolve(subject, ctx);
    return result.candidates().stream()
        .anyMatch(candidate -> UNIT_TYPE.equals(candidate.typeName()));
  }
}
