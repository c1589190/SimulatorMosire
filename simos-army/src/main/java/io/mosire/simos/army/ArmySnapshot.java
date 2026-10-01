package io.mosire.simos.army;

import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;

/**
 * army 模块的状态切片（阶段 D1 / 用户设计 D-012，2026-10-02）：<b>独立的 record 树，只实现 {@link Snapshot}</b>——与
 * MapSnapshot / SocialSnapshot / UnitSnapshot / SdSnapshot / EconomySnapshot / ActorSnapshot /
 * GovSnapshot 之间没有万能父类。
 *
 * <p>★ {@link #namespace()} 固定返回 {@code "army"}：{@code SimulationState} 构造期校验"modules 的键 ==
 * snapshot.namespace()"，装配时键名写歪会当场抛。
 *
 * <p>★★ <b>这个字面量全仓有两处同字面</b>：本类与 {@link
 * io.mosire.simos.army.codec.ArmyCodec#namespace()}。改这里必须同时改那边 （装配期那处**有牙**：写歪即抛）。★ 与 {@code
 * ActorSnapshot}/{@code GovSnapshot} 同制：不放共享常量，免得 util 认识领域命名空间（铁律 3）。
 */
public record ArmySnapshot(StateRef ref, SimosTimestamp timestamp, ArmyData data)
    implements Snapshot {

  public ArmySnapshot {
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
    return "army";
  }
}
