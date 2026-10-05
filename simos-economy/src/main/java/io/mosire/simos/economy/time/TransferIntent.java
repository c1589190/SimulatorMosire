package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.transfer.Transfer;
import java.util.Objects;

/**
 * ★★ <b>转移意向</b>（R1 并行内核）：{@link Transfer} 的不可变包装 + 稳定提交序。
 *
 * <p>★★ <b>提交口仍是 {@code EconomySettlement.applyTransfer}</b>：本意向不自己写账户 —— 单线程提交器把它喂给那唯一
 * 的两遍式写口（第一遍只读校验、第二遍落账）⇒ "并行路径不得出现第二个直接写账户的口"是结构上成立的，不是靠约定。
 *
 * <p>★ <b>提交序键 = 付方账户</b>（{@code (transfer.from, transfer.location)}）：一笔转移的第一条校验腿恒是付方， 同价分配 / 跨区撮合按
 * {@code (landedPrice, costRank, canonical seller, canonical buyer, orderId)} 的全局稳定序
 * 算完之后，仍以本条序落账（R2 的协调器只负责算出意向，不负责决定提交先后）。
 */
public record TransferIntent(CommitOrder order, Transfer transfer) implements OrderedAccountIntent {

  public TransferIntent {
    Objects.requireNonNull(order, "TransferIntent.order 不得为 null");
    Objects.requireNonNull(transfer, "TransferIntent.transfer 不得为 null");
    String expected = fromKey(transfer).canonical();
    if (!expected.equals(order.canonicalKey())) {
      throw new IllegalArgumentException(
          "TransferIntent 的提交序键必须等于付方账户的 canonical 串: " + order.canonicalKey() + " != " + expected);
    }
    if (transfer.goods().isEmpty() && transfer.money().isEmpty()) {
      throw new IllegalArgumentException("TransferIntent 的转移不得两条腿都为空（空转移不是一条发生额）");
    }
  }

  /** 产出点：规范串/分区号由账户键与分区计划给出，调用方不手拼提交序。 */
  public static TransferIntent of(
      SettlementStage stage, int partitionIndex, int intraIndex, Transfer transfer) {
    Objects.requireNonNull(transfer, "transfer");
    AccountPartitionKey key = fromKey(transfer);
    return new TransferIntent(
        new CommitOrder(stage, partitionIndex, key.canonical(), intraIndex), transfer);
  }

  @Override
  public AccountPartitionKey accountKey() {
    return AccountPartitionKey.parseCanonical(order.canonicalKey());
  }

  /** 收方账户键（它可能落在另一个分区 ⇒ 由协调阶段统一提交）。 */
  public AccountPartitionKey receiverKey() {
    return new AccountPartitionKey(HouseholdRouting.requireHouseholdOf(transfer.to()));
  }

  /** 付方账户键（账户主体只有家户；非家户 actor 具名抛）。 */
  private static AccountPartitionKey fromKey(Transfer transfer) {
    return new AccountPartitionKey(HouseholdRouting.requireHouseholdOf(transfer.from()));
  }
}
