package io.mosire.simos.production.contract;

import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>② 执行：结果（执行成功 / 业务拒绝）</b>（D-032 约束设计书 §4.2；I15「业务拒绝 = INFO 具名理由」）。
 *
 * <p>★★ <b>为什么拒绝不是异常</b>：请求指向一个<b>不存在的实例或方案</b>是正常世界会走到的情况 （迁移刚拆掉、清区刚删掉、GM
 * 刚停用），它是<b>业务拒绝</b>——economy 记 INFO 具名理由、跳过该候选， 世界照常推进。只有<b>契约故障</b>（本域状态自相矛盾、公式求值失败、候选态校验不过）才抛
 * {@link ProductionContractFault}，让整个提案被拒。
 *
 * <p>★ 两个分支恰有其一（构造期判）：拒绝时没有回执，执行时没有理由。
 */
public sealed interface ProductionExecutionOutcome {

  /** 执行成功：带回执。 */
  record Executed(ProductionReceipt receipt) implements ProductionExecutionOutcome {

    public Executed {
      Objects.requireNonNull(receipt, "ProductionExecutionOutcome.Executed.receipt 不得为 null");
    }
  }

  /**
   * 业务拒绝（INFO，具名理由）。★ 被拒请求<b>零残留</b>（不建实例、不改进度/经验/投入/占用）。
   *
   * @param reason 稳定短 id，如 {@code instance-unknown}/{@code plan-unknown}/{@code unit-conflict}；
   *     R3（§15.3）起还包括使用资格类理由： {@code locked}（未解锁）、{@code
   *     condition-unsatisfied:&lt;理由&gt;}（可用性条件不满足，文本与 probe 逐字相同）、 {@code
   *     condition-subject-unknown}（经营者不是家户，而方案要求经验门槛/解锁门槛）
   */
  record Rejected(String reason) implements ProductionExecutionOutcome {

    public Rejected {
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("ProductionExecutionOutcome.Rejected.reason 不得为空白");
      }
    }
  }

  /** 回执（拒绝时 empty）。★ 名字带 {@code OrEmpty}：record 的 {@code receipt()} 访问器已占用那个签名。 */
  default Optional<ProductionReceipt> receiptOrEmpty() {
    return this instanceof Executed executed ? Optional.of(executed.receipt()) : Optional.empty();
  }

  /** 拒绝理由（执行成功时 empty）。 */
  default Optional<String> rejectionReason() {
    return this instanceof Rejected rejected ? Optional.of(rejected.reason()) : Optional.empty();
  }

  /** 是否执行成功。 */
  default boolean executed() {
    return this instanceof Executed;
  }
}
