package io.mosire.simos.util.resolve;

import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.identity.QueryResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 解析器注册表：**namespace 唯一映射**（spec §〇 裁决 3）。
 *
 * <p>注册顺序只影响 {@link #namespaces()} 的展示顺序，**不构成优先级**；重复注册立即抛异常， 未知命名空间不给兜底。
 */
public final class ResolverRegistry {

  private final Map<String, Resolver> resolvers = new LinkedHashMap<>();

  public void register(Resolver resolver) {
    Objects.requireNonNull(resolver, "resolver");
    String namespace = resolver.namespace();
    if (namespace == null || namespace.isBlank()) {
      throw new IllegalArgumentException("Resolver.namespace() 不得为空白");
    }
    if (resolvers.putIfAbsent(namespace, resolver) != null) {
      throw new IllegalArgumentException("命名空间 " + namespace + " 已有解析器，不允许重复注册（注册表不设优先级）");
    }
  }

  /** 注册序。 */
  public List<String> namespaces() {
    return List.copyOf(resolvers.keySet());
  }

  /** 分发到地址首段对应的解析器；未注册的命名空间**抛异常，不兜底**。 */
  public QueryResult resolve(Address address, ResolveContext ctx) {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(ctx, "ctx");
    Resolver resolver = resolvers.get(address.namespace());
    if (resolver == null) {
      throw new IllegalArgumentException(
          "没有注册命名空间 " + address.namespace() + " 的解析器（已注册：" + resolvers.keySet() + "）");
    }
    return resolver.resolve(address, ctx);
  }
}
