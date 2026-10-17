package io.mosire.simos.culture;

import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;

/**
 * ★★ <b>culture 模块的状态切片</b>（D-031 spec §13 Z1）：独立的 record 树，只实现 {@link Snapshot}——与
 * Map/Social/Unit/Economy/Actor/Gov/Production 等切片之间没有万能父类。
 *
 * <p>★ {@link #namespace()} 固定返回 {@code "culture"}：{@code SimulationState} 构造期校验"modules 的键 ==
 * snapshot.namespace()"，装配时键名写歪会当场抛。
 *
 * <p>★★ <b>这个字面量全仓有两处同字面</b>：本类与 {@link io.mosire.simos.culture.codec.CultureCodec#namespace()}。
 * 改这里必须同时改那边（装配期那处有牙：写歪即抛）。不放共享常量是为了不让 util 认识领域命名空间（铁律 3）。
 *
 * <p>★ <b>状态内容 = 家户文化标签 + 熟练度规则</b>（本轮）；本轮真实文化效果未启用。
 */
public record CultureSnapshot(StateRef ref, SimosTimestamp timestamp, CultureData state)
    implements Snapshot {

  public CultureSnapshot {
    if (ref == null) {
      throw new IllegalArgumentException("CultureSnapshot.ref 不得为 null");
    }
    if (timestamp == null) {
      throw new IllegalArgumentException("CultureSnapshot.timestamp 不得为 null");
    }
    if (state == null) {
      throw new IllegalArgumentException("CultureSnapshot.state 不得为 null");
    }
  }

  @Override
  public String namespace() {
    return "culture";
  }
}
