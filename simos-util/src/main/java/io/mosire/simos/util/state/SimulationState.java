package io.mosire.simos.util.state;

import io.mosire.simos.util.info.InfoSystem;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 整个模拟状态：元信息 + 各模块切片 + 外挂信息（spec §六）。
 *
 * <p>**不提供跨模块访问器**（没有 `state.map().units()` 这类入口）：跨模块可见性只走 Facet （铁律 3/4）。这条由 {@code
 * SimulationStateTest} 的反射用例钉住。
 */
public record SimulationState(StateMeta meta, Map<String, Snapshot> modules, InfoSystem info) {

  public SimulationState {
    Objects.requireNonNull(meta, "meta");
    Objects.requireNonNull(modules, "modules");
    Objects.requireNonNull(info, "info");
    modules = Map.copyOf(modules);
    for (Map.Entry<String, Snapshot> entry : modules.entrySet()) {
      String namespace = entry.getValue().namespace();
      if (!entry.getKey().equals(namespace)) {
        throw new IllegalArgumentException(
            "modules 的键必须等于该快照的 namespace()：键=" + entry.getKey() + "，快照=" + namespace);
      }
    }
  }

  /** 唯一的取用入口。 */
  public Optional<Snapshot> module(String namespace) {
    return Optional.ofNullable(modules.get(namespace));
  }
}
