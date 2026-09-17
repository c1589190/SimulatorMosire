package io.mosire.simos.unit;

import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;

/**
 * unit 模块的状态切片（总纲 §4.5）：**独立的 record 树，只实现 {@link Snapshot}**——与 MapSnapshot / SocialSnapshot
 * 之间没有万能父类。
 *
 * <p>★ {@link #namespace()} 固定返回 {@code "unit"}：{@code SimulationState} 构造期校验"modules 的键 ==
 * snapshot.namespace()"，装配时键名写歪会当场抛。
 */
public record UnitSnapshot(StateRef ref, SimosTimestamp timestamp, UnitState state)
    implements Snapshot {

  public UnitSnapshot {
    if (ref == null) {
      throw new IllegalArgumentException("ref 不得为 null");
    }
    if (timestamp == null) {
      throw new IllegalArgumentException("timestamp 不得为 null");
    }
    if (state == null) {
      throw new IllegalArgumentException("state 不得为 null");
    }
  }

  @Override
  public String namespace() {
    return "unit";
  }
}
