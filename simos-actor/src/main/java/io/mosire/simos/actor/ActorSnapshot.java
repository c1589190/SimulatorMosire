package io.mosire.simos.actor;

import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;

/**
 * actor 模块的状态切片（总纲 §4.5）：<b>独立的 record 树，只实现 {@link Snapshot}</b>——与 MapSnapshot / SocialSnapshot /
 * UnitSnapshot / LedgerSnapshot / EconomySnapshot 之间没有万能父类。
 *
 * <p>★ {@link #namespace()} 固定返回 {@code "actor"}：{@code SimulationState} 构造期校验"modules 的键 ==
 * snapshot.namespace()"，装配时键名写歪会当场抛。
 *
 * <p>★★ <b>这个字面量有三处同字面</b>（Task 7 的 {@code ActorCodec.namespace()}、Task 9 的 {@code
 * ToolSupport.ACTOR_NAMESPACE}）——本类是其中第一处，改这里必须同时改那两处。
 */
public record ActorSnapshot(StateRef ref, SimosTimestamp timestamp, ActorData data)
    implements Snapshot {

  public ActorSnapshot {
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
    return "actor";
  }
}
