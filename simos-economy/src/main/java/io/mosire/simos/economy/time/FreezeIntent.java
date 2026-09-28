package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import java.util.Objects;

/**
 * ★★ <b>冻结 / 占位意向</b>（R1 并行内核）：把某本账上某商品的冻结额<b>置为</b>一个绝对值（{@code 0} = 解冻）。
 *
 * <p>★★ <b>为什么冻结必须单独成意向</b>：冻结是"余额的子集被明确占用"（{@code GoodsAccount} 的构造期守卫： {@code 冻结 ≤ 余额}）。并行 worker
 * 若直接改共享冻结表，就会与另一分区的扣款意向交叉；本类把"放置/释放"做成可排序的 不可变意向，由单线程提交器统一校验（{@code 0 ≤ amount ≤ 余额}）并落账 ——
 * <b>冻结的写者仍只有一处</b>。
 *
 * <p>★ <b>绝对值语义照 {@code OwnershipBooks.freeze/release} 逐字一致</b>：同一账户同一商品的多次放置，后提交者生效； 提交器按 {@link
 * CommitOrder} 的顺序落，故"后者"由稳定序决定，与 worker 完成先后无关。
 *
 * <p>★ 两个变体（商品 / 货币）各自带强类型键；金额单位随各自 {@code Map} 的口径（毫商品 / 最小币值）。
 */
public sealed interface FreezeIntent extends OrderedAccountIntent {

  /** 要放置的冻结额（绝对值，≥ 0；{@code 0} = 解冻）。 */
  long amount();

  /** 商品冻结意向：{@code (账户, 商品) → amount}。 */
  record Goods(CommitOrder order, CommodityId commodity, long amount) implements FreezeIntent {

    public Goods {
      Objects.requireNonNull(order, "FreezeIntent.Goods.order 不得为 null");
      Objects.requireNonNull(commodity, "FreezeIntent.Goods.commodity 不得为 null");
      if (amount < 0L) {
        throw new IllegalArgumentException("冻结额不得为负（解冻用 0，不是负数）: " + amount);
      }
    }

    @Override
    public AccountPartitionKey accountKey() {
      return AccountPartitionKey.parseCanonical(order.canonicalKey());
    }
  }

  /** 货币冻结意向：{@code (账户, 币种) → amount}。 */
  record Money(CommitOrder order, CurrencyId currency, long amount) implements FreezeIntent {

    public Money {
      Objects.requireNonNull(order, "FreezeIntent.Money.order 不得为 null");
      Objects.requireNonNull(currency, "FreezeIntent.Money.currency 不得为 null");
      if (amount < 0L) {
        throw new IllegalArgumentException("冻结额不得为负（解冻用 0，不是负数）: " + amount);
      }
    }

    @Override
    public AccountPartitionKey accountKey() {
      return AccountPartitionKey.parseCanonical(order.canonicalKey());
    }
  }

  /** 商品冻结的产出点（规范串由账户键给出）。 */
  static FreezeIntent goods(
      AccountPartitionKey key,
      SettlementStage stage,
      int partitionIndex,
      int intraIndex,
      CommodityId commodity,
      long amount) {
    Objects.requireNonNull(key, "key");
    return new Goods(
        new CommitOrder(stage, partitionIndex, key.canonical(), intraIndex), commodity, amount);
  }

  /** 货币冻结的产出点（规范串由账户键给出）。 */
  static FreezeIntent money(
      AccountPartitionKey key,
      SettlementStage stage,
      int partitionIndex,
      int intraIndex,
      CurrencyId currency,
      long amount) {
    Objects.requireNonNull(key, "key");
    return new Money(
        new CommitOrder(stage, partitionIndex, key.canonical(), intraIndex), currency, amount);
  }
}
