package io.mosire.simos.production.spi;

import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.Objects;

/**
 * 从 {@link SimulationState} 取 production 切片的唯一入口（本包 handler 共用）。形制照 {@code GovSnapshots} / {@code
 * UnitSnapshots}。
 *
 * <p>★ <b>装配故障当场炸</b>：缺切片、或切片类型不对，都抛 {@link IllegalStateException}——它们是装配的错，不走 {@code Rejected} 路径。
 */
final class ProductionSnapshots {

  private ProductionSnapshots() {}

  static ProductionData of(SimulationState state) {
    Objects.requireNonNull(state, "state");
    Snapshot snapshot =
        state
            .module("production")
            .orElseThrow(() -> new IllegalStateException("state 里没有 production 切片（装配故障）"));
    if (!(snapshot instanceof ProductionSnapshot productionSnapshot)) {
      throw new IllegalStateException(
          "state 的 production 切片不是 ProductionSnapshot: " + snapshot.getClass().getName());
    }
    return productionSnapshot.state();
  }

  // ★★★ D-032 B4（§13.14.5 第 1 条）收口：`consumerPort(SimulationState)` **已删除**。
  //   它要求"economy 切片自己实现 ProductionConsumerPort"，但那个实现看不见 production 的实例表（B2b 后
  //   `IndustryReferenceCounts.units` 的唯一权威）⇒ 该转型路径注定装配故障。端口的实现改由组合根提供，并在
  //   装配期**构造器注入**给用它的 handler / 工具（缺了就 requireNonNull 当场炸）。
}
