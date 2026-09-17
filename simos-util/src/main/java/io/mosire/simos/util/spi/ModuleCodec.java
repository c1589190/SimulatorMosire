package io.mosire.simos.util.spi;

import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;

/**
 * 状态编解码与施加（spec §八）：Core 与模块之间**唯一**触碰状态形状的地方。
 *
 * <p>★ {@link #apply} 里的 cast 发生在**模块自己的实现里**——Core 从不 cast、从不反射模块类型（C26）。 多态反序列化的整类问题因此在 Core
 * 侧不存在。
 *
 * <p>★ 快照的 JSON 形态是**各模块自己的事**（Core 只把它当一段文本内嵌，spec §6.3）。
 */
public interface ModuleCodec {

  /** 与 {@code Snapshot.namespace()} 一致；Core 用它把载荷路由回正确的模块。 */
  String namespace();

  ChangeSet decodeChangeSet(String json);

  String encodeChangeSet(ChangeSet changeSet);

  Snapshot decodeSnapshot(String json);

  String encodeSnapshot(Snapshot snapshot);

  /**
   * 把变更集施加到 base 上，返回**新的**快照（不得就地改 base）。
   *
   * <p>★ **第三个参数 {@code newMeta} 是执行期裁定 C28**（spec §〇.2）：模块的 {@code XChangeSet.apply}
   * 返回的是**模块状态类型**（{@code GameMap} / {@code SocialData} / {@code UnitState}），**不带 {@code ref} 与
   * {@code timestamp}**——实测见 spec §〇.3 第 10 条。而 spec §5.4 第 4 项要求施加后的快照**必须带上新的 {@code ref} 与
   * {@code tick}**。 ⇒ 新坐标只能由 Core 告诉 codec。用 M1 既有的 {@link StateMeta} 承载，**不新造类型**。
   *
   * <p>★ **二参形态是不可满足的**——没有它，codec 只能照抄 base 的（陈旧且错）或凭空造一个。这正是本 spec 从"纸面冻结"到"真写实现"之间被磨出来的那一处。
   */
  Snapshot apply(ChangeSet changeSet, Snapshot base, StateMeta newMeta);
}
