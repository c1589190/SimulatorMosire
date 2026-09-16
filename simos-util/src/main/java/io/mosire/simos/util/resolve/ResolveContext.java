package io.mosire.simos.util.resolve;

import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Objects;

/**
 * 解析上下文（spec §〇 附表）：`revision` 管数据版本、`at` 管模拟时间，两者正交。
 *
 * <p>**不加通用扩展袋**——需要什么就在这里显式长出来。
 */
public record ResolveContext(SimulationState state, SimosTimestamp at) {

  public ResolveContext {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(at, "at");
  }
}
