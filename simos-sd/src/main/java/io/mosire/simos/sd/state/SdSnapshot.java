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
 *
 * <p>★★ <b>2026-10-12 批次 3b-4：内层 {@link SdState} 已是 0 组件的空树</b>（{@code effects} 搬进 {@code
 * GaState}）⇒ 本切片仍是"模块在册"的合法切片（{@code SdCodec} 照常注册、四个创世世界照常补空片），只是不再带数据； 整个模块的退役（连本类一起删）属批次 3e，见
 * {@code SdState} 的类注。
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
