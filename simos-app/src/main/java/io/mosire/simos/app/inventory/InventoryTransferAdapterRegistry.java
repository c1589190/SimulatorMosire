package io.mosire.simos.app.inventory;

import io.mosire.simos.economy.api.inventory.InventoryLotKind;
import io.mosire.simos.economy.api.inventory.InventoryTransferPort;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 按 {@link InventoryLotKind} 选择 U1b 适配器；缺失适配器具名拒绝。 */
public final class InventoryTransferAdapterRegistry {

  private final Map<InventoryLotKind, InventoryTransferAdapter> adapters;

  /** 生产默认注册表：v1 只注册 COMMODITY 与 LAND。 */
  public InventoryTransferAdapterRegistry() {
    this(Arrays.asList(new CommodityTransferAdapter(), new LandLotTransferAdapter()));
  }

  public InventoryTransferAdapterRegistry(Collection<? extends InventoryTransferAdapter> adapters) {
    Objects.requireNonNull(adapters, "adapters");
    Map<InventoryLotKind, InventoryTransferAdapter> copy = new LinkedHashMap<>();
    for (InventoryTransferAdapter adapter : adapters) {
      Objects.requireNonNull(adapter, "adapters 不得含 null");
      InventoryLotKind kind = Objects.requireNonNull(adapter.kind(), "adapter.kind()");
      InventoryTransferAdapter previous = copy.put(kind, adapter);
      if (previous != null) {
        throw new IllegalArgumentException("InventoryTransferAdapter 重复注册 kind: " + kind);
      }
    }
    this.adapters = Collections.unmodifiableMap(copy);
  }

  public InventoryTransferAdapterRegistry(InventoryTransferAdapter... adapters) {
    this(Arrays.asList(adapters));
  }

  /** 显式 kind → adapter 注册；键必须与适配器自报 kind 一致。 */
  public InventoryTransferAdapterRegistry(
      Map<InventoryLotKind, InventoryTransferAdapter> adapters) {
    Objects.requireNonNull(adapters, "adapters");
    Map<InventoryLotKind, InventoryTransferAdapter> copy = new LinkedHashMap<>();
    for (Map.Entry<InventoryLotKind, InventoryTransferAdapter> entry : adapters.entrySet()) {
      InventoryLotKind kind = Objects.requireNonNull(entry.getKey(), "adapters key");
      InventoryTransferAdapter adapter = Objects.requireNonNull(entry.getValue(), "adapters value");
      if (!kind.equals(adapter.kind())) {
        throw new IllegalArgumentException(
            "InventoryTransferAdapter 注册键与 adapter.kind() 不一致: key="
                + kind
                + " adapter="
                + adapter.kind());
      }
      copy.put(kind, adapter);
    }
    this.adapters = Collections.unmodifiableMap(copy);
  }

  public static InventoryTransferAdapterRegistry standard() {
    return new InventoryTransferAdapterRegistry();
  }

  /** 取必需适配器；缺失按任务书具名拒绝。 */
  public InventoryTransferAdapter require(InventoryLotKind kind) {
    Objects.requireNonNull(kind, "kind");
    InventoryTransferAdapter adapter = adapters.get(kind);
    if (adapter == null) {
      throw new IllegalArgumentException(
          InventoryTransferPort.REASON_KIND_UNSUPPORTED + ": no adapter for " + kind);
    }
    return adapter;
  }

  /** 按 kind 查找；缺失为空。 */
  public Optional<InventoryTransferAdapter> find(InventoryLotKind kind) {
    Objects.requireNonNull(kind, "kind");
    return Optional.ofNullable(adapters.get(kind));
  }

  /** {@link #require(InventoryLotKind)} 的语义化别名。 */
  public InventoryTransferAdapter adapterFor(InventoryLotKind kind) {
    return require(kind);
  }

  /** 当前注册表快照；保序不可变。 */
  public Map<InventoryLotKind, InventoryTransferAdapter> adapters() {
    return adapters;
  }
}
