package io.mosire.simos.util.facet;

import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.resolve.ResolveContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Facet 注册表：注册语义与 {@code ResolverRegistry} 一致——名字唯一映射、重复注册立即抛异常、无兜底。
 *
 * <p>{@link #queryAll} **按注册顺序**拼接，显示顺序因此是确定性的（由 Core 的装配顺序决定），不引入排序规则。
 */
public final class FacetRegistry {

  private final Map<String, FacetProvider> providers = new LinkedHashMap<>();

  public void register(FacetProvider provider) {
    Objects.requireNonNull(provider, "provider");
    String facetName = provider.facetName();
    if (facetName == null || facetName.isBlank()) {
      throw new IllegalArgumentException("FacetProvider.facetName() 不得为空白");
    }
    if (providers.putIfAbsent(facetName, provider) != null) {
      throw new IllegalArgumentException("facetName " + facetName + " 已有提供者，不允许重复注册");
    }
  }

  /** 注册序。 */
  public List<String> facetNames() {
    return List.copyOf(providers.keySet());
  }

  /** 按注册顺序拼接所有提供者的结果；提供者返回空列表不报错。 */
  public List<FacetEntry> queryAll(Address subject, ResolveContext ctx) {
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(ctx, "ctx");
    List<FacetEntry> entries = new ArrayList<>();
    for (FacetProvider provider : providers.values()) {
      List<FacetEntry> fromProvider = provider.query(subject, ctx);
      if (fromProvider == null) {
        throw new IllegalStateException(
            "FacetProvider " + provider.facetName() + " 返回了 null，应返回空列表");
      }
      entries.addAll(fromProvider);
    }
    return List.copyOf(entries);
  }
}
