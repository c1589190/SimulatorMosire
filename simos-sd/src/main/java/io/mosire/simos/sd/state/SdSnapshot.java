package io.mosire.simos.sd.state;

import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;

/**
 * sd 模块的状态切片（spec §三.1）：**独立的 record 树，只实现 {@link Snapshot}**——与 MapSnapshot / SocialSnapshot /
 * UnitSnapshot 之间没有万能父类。
 *
 * <p>★ {@link #namespace()} 固定返回 {@code "sd"}：{@code SimulationState} 构造期校验"modules 的键 ==
 * snapshot.namespace()"，装配时键名写歪会当场抛。
 */
public record SdSnapshot(StateRef ref, SimosTimestamp timestamp, SdState state)
    implements Snapshot {

  public SdSnapshot {
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
    return "sd";
  }
}
