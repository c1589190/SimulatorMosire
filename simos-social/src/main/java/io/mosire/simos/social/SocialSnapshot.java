package io.mosire.simos.social;

import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;

/**
 * social 模块的状态切片（总纲 §4.5）：**独立的 record 树，只实现 {@link Snapshot}**——与 MapSnapshot / UnitSnapshot
 * 之间没有万能父类。
 *
 * <p>★ {@link #namespace()} 固定返回 {@code "social"}：{@code SimulationState} 构造期校验"modules 的键 ==
 * snapshot.namespace()"，装配时键名写歪会当场抛。
 */
public record SocialSnapshot(StateRef ref, SimosTimestamp timestamp, SocialData data)
    implements Snapshot {

  public SocialSnapshot {
    if (ref == null) {
      throw new IllegalArgumentException("ref 不得为 null");
    }
    if (timestamp == null) {
      throw new IllegalArgumentException("timestamp 不得为 null");
    }
    if (data == null) {
      throw new IllegalArgumentException("data 不得为 null");
    }
  }

  @Override
  public String namespace() {
    return "social";
  }
}
