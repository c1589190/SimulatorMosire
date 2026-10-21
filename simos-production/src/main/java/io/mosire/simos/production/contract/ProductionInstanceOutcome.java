package io.mosire.simos.production.contract;

import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>实例建档/改写的结果（成功 / 业务拒绝）</b>（D-032 约束设计书 §4.3；I15「业务拒绝 = INFO 具名理由」）。
 *
 * <p>★★ <b>为什么不是异常</b>：请求指向一个<b>已经存在的实例</b>、或一个<b>不存在的方案/实例</b>，都是正常世界会 走到的情况（迁移刚拆掉、清区刚删掉、GM
 * 刚停用、同一格两队人都想用同一个 id）——那是<b>业务拒绝</b>： economy 记 INFO
 * 具名理由、跳过该候选，世界照常推进。只有<b>契约故障</b>（本域状态自相矛盾：方案固有地点与 实例地点矛盾、键与行内身份不一致）才抛 {@link
 * ProductionContractFault}，让整个提案被拒。
 *
 * <p>★ 两个分支恰有其一（构造期判）：拒绝时没有实例，建档时没有理由。
 */
public sealed interface ProductionInstanceOutcome {

  /** 建档/改写成功：带回写后的实例（只读）。 */
  record Written(ProductionProcess instance) implements ProductionInstanceOutcome {

    public Written {
      Objects.requireNonNull(instance, "ProductionInstanceOutcome.Written.instance 不得为 null");
    }
  }

  /**
   * 业务拒绝（INFO，具名理由）。
   *
   * @param reason 稳定短 id，如 {@code unit-conflict}/{@code plan-unknown}/{@code instance-unknown}/
   *     {@code same-household}
   */
  record Rejected(String reason) implements ProductionInstanceOutcome {

    public Rejected {
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("ProductionInstanceOutcome.Rejected.reason 不得为空白");
      }
    }
  }

  /** 实例（拒绝时 empty）。★ 名字带 {@code OrEmpty}：record 的 {@code instance()} 访问器已占用那个签名。 */
  default Optional<ProductionProcess> instanceOrEmpty() {
    return this instanceof Written written ? Optional.of(written.instance()) : Optional.empty();
  }

  /** 拒绝理由（成功时 empty）。 */
  default Optional<String> rejectionReason() {
    return this instanceof Rejected rejected ? Optional.of(rejected.reason()) : Optional.empty();
  }

  /** 是否建档/改写成功。 */
  default boolean written() {
    return this instanceof Written;
  }
}
