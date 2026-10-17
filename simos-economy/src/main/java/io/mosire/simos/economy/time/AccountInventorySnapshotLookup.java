package io.mosire.simos.economy.time;

import java.util.Optional;

/**
 * ★★ <b>D-032 D4：economy 对账户只读快照的最小 provider</b>（D4 §7.1/§7.2）。
 *
 * <p>D4 不复制余额/冻结，也不直接读 actor 的活账户 Map。组合根在 revision 边界取一次 {@link AccountSnapshot} 注入本接口； {@link
 * WagePayableInventoryLookupImpl} 与 {@link InventoryReservationBook} 只通过它读取户级商品余额与冻结。
 *
 * <p>★ <b>{@link Optional#empty()} 语义</b>：账户权威不可用（provider 未装配/会话快照不可取）；不得读成"余额为 0"。已知家户没有账户由
 * {@link AccountSnapshot#actorKeyOrNull} 的 {@code null} 表达，不是这个 Optional 的空。
 *
 * <p>★ 只读：本接口不暴露活表、不写账户、不改变 revision。
 */
@FunctionalInterface
public interface AccountInventorySnapshotLookup {

  /** 当刻账户只读快照；不可用 ⇒ empty。 */
  Optional<AccountSnapshot> accountSnapshot();
}
