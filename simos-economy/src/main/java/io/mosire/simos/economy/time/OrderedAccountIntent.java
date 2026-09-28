package io.mosire.simos.economy.time;

import java.util.Objects;

/**
 * ★★ <b>可稳定提交的账户意向</b>（R1 并行内核）：worker 只能产出本接口的实现（{@link AccountDelta} / {@link TransferIntent} /
 * {@link FreezeIntent}），由<b>单线程提交器</b>{@link SettlementExecutor#commit} 按 {@link #order()} 字典序落账。
 *
 * <p>★★ <b>为什么不是"给账户加锁"</b>：加锁把"谁先到"变成结果的一部分（死锁面 + 不可重放）；本接口让 worker 只产出 <b>不可变意向</b>，共享账户 Map
 * 只有提交器一个写者 —— 线程安全来自结构，不来自锁。
 *
 * <p>★ 每个实现都必须给出以账户 canonical 串为 {@code canonicalKey} 的 {@link CommitOrder}；同一分区内序号唯一 ⇒
 * 提交序无并列（{@link SettlementExecutor#commit} 会把并列键判死，而不是靠 {@code List.sort} 的稳定性兜底）。
 */
public sealed interface OrderedAccountIntent extends Comparable<OrderedAccountIntent>
    permits AccountDelta, TransferIntent, FreezeIntent {

  /** 本意向在全局提交序里的位置。 */
  CommitOrder order();

  /** 本意向触碰的账户分区键（{@code order().canonicalKey()} 的解析结果）。 */
  AccountPartitionKey accountKey();

  /** 提交序 = {@link CommitOrder} 的自然序；<b>不许</b>任何实现重写它。 */
  @Override
  default int compareTo(OrderedAccountIntent other) {
    Objects.requireNonNull(other, "other");
    return order().compareTo(other.order());
  }
}
