package io.mosire.simos.map;

import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;

/**
 * map 模块的状态切片（总纲 §4.5）：**独立的 record 树，只实现 {@link Snapshot}**——与 SocialSnapshot/UnitSnapshot
 * 之间没有万能父类。
 *
 * <p>★ {@link #namespace()} 固定返回 {@code "map"}：{@code SimulationState} 构造期校验 "modules 的键 ==
 * snapshot.namespace()"，装配时键名写歪会当场抛，而不是留到查询时静默 miss。
 */
public record MapSnapshot(StateRef ref, SimosTimestamp timestamp, GameMap map) implements Snapshot {

  public MapSnapshot {
    if (ref == null) {
      throw new IllegalArgumentException("ref 不得为 null");
    }
    if (timestamp == null) {
      throw new IllegalArgumentException("timestamp 不得为 null");
    }
    if (map == null) {
      throw new IllegalArgumentException("map 不得为 null");
    }
  }

  /** 本快照所属的模块。 */
  @Override
  public String namespace() {
    return "map";
  }
}
