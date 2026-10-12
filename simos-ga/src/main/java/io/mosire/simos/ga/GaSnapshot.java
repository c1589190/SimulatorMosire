package io.mosire.simos.ga;

import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;

/**
 * ★★ <b>ga 模块的状态切片</b>（2026-10-12 批次 3a）：<b>独立的 record 树，只实现 {@link Snapshot}</b>——与 MapSnapshot /
 * SocialSnapshot / UnitSnapshot / SdSnapshot / EconomySnapshot / ActorSnapshot / GovSnapshot /
 * ArmySnapshot 之间没有万能父类。
 *
 * <p>★ {@link #namespace()} 固定返回 {@code "ga"}：{@code SimulationState} 构造期校验"modules 的键 ==
 * snapshot.namespace()"，装配时键名写歪会当场抛。
 *
 * <p>★★ <b>这个字面量全仓有两处同字面</b>：本类与 {@link io.mosire.simos.ga.codec.GaCodec#namespace()}。
 * 改这里必须同时改那边（装配期那处<b>有牙</b>：写歪即抛）。★ 与 gov 的写法核对过：{@code GovSnapshot} 用字面量 + 注释指向
 * codec，本类照同形制；不放共享常量是为了不让 util 认识领域命名空间（铁律 3）。
 */
public record GaSnapshot(StateRef ref, SimosTimestamp timestamp, GaState state)
    implements Snapshot {

  public GaSnapshot {
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
    return "ga";
  }
}
