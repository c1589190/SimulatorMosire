package io.mosire.simos.economy.time;

import java.util.Objects;

/**
 * ★★★ <b>W1/W2b/W3 共享的付款人级地点预检</b>（D-032 D3 §6.9.1；N-02/B4）。
 *
 * <p>★ <b>纯只读、无账户读取</b>：只消费 {@link WagePayerLocationSnapshot} 的地点点集合并返回结论。 单一实现由本文件提供； W1/W2b/W3
 * 只能消费同一实现，不得各自复制"地点聚合/基数判断"逻辑，也不得在调用前自行缩减 snapshot。
 *
 * <p>★ 预检不能替代 {@code WagePayableInventoryLookup} 内部的全地点预留扣减： 通过预检仍必须按 N-01 扣除 payer 全部地点的 ACTIVE
 * 预留，再做支付/候选判断。
 */
public interface WagePayerLocationPrecheck {

  /** 判定候选/本次操作在 payer 级地点集合下是否可行。 */
  WagePayerLocationDecision evaluate(WagePayerLocationSnapshot snapshot);

  /** 唯一生产实现的共享入口（W1/W2b/W3 只拿这一份）。 */
  static WagePayerLocationPrecheck standard() {
    return StandardWagePayerLocationPrecheck.INSTANCE;
  }
}

/** {@link WagePayerLocationPrecheck} 的唯一实现：只判地点数，不读账户、不写状态。 */
final class StandardWagePayerLocationPrecheck implements WagePayerLocationPrecheck {

  static final StandardWagePayerLocationPrecheck INSTANCE = new StandardWagePayerLocationPrecheck();

  private StandardWagePayerLocationPrecheck() {}

  @Override
  public WagePayerLocationDecision evaluate(WagePayerLocationSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    return snapshot.allSettlementLocations().size() > 1
        ? WagePayerLocationDecision.MULTIPLE_LOCATIONS
        : WagePayerLocationDecision.OK;
  }
}
