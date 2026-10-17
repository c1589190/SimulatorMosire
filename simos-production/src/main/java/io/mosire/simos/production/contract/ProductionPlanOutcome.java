package io.mosire.simos.production.contract;

import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>方案定义按需登记的结果（成功 / 业务拒绝）</b>（D-032 §13.10.3 裁定五； {@link ProductionRuntime#registerPlan}
 * 的返回值）。
 *
 * <p>★★ <b>为什么不复用 {@link ProductionInstanceOutcome}</b>：那两个分支的载荷是<b>实例</b> （{@code
 * ProductionProcess}），而本口登记的是<b>方案定义</b>（{@link IndustryDefinitionView}）——
 * "按需新建定义"与"按需新建实例"是两件事（§4.1 原文），返回值也必须是两个类型，否则调用方会被 逼着写 {@code (ProductionProcess) outcome}
 * 这种把类型安全扔掉的转换。
 *
 * <p>★★ <b>业务拒绝 = INFO 具名理由；同 id 不同值 = 契约故障（不在这里）</b>： 本类型只承载"正常世界会走到"的结论 —— 登记成功、或调用方给的候选定义本身不成立
 * （{@link Rejected#reason()} 具名，如 {@code plan-id-mismatch}）。<b>冲突</b>（同 id 不同值）不是业务拒绝：
 * 它是"两个权威对同一方案各说一套"，由 {@link ProductionRuntime#registerPlan} 抛 {@link
 * ProductionContractFault}（ERROR 不降级、拒绝整个候选提交）。
 *
 * <p>★ <b>幂等</b>：同 id 逐值相同 ⇒ {@link Written}（带回当刻权威视图，不重写、不报冲突）。
 */
public sealed interface ProductionPlanOutcome {

  /** 登记成功（或幂等 no-op）：带回当刻权威的该方案定义视图（只读）。 */
  record Written(IndustryDefinitionView plan) implements ProductionPlanOutcome {

    public Written {
      Objects.requireNonNull(plan, "ProductionPlanOutcome.Written.plan 不得为 null");
    }
  }

  /**
   * 业务拒绝（INFO，具名理由）。
   *
   * @param reason 稳定短 id，如 {@code plan-id-mismatch}（键与值内 id 不一致）
   */
  record Rejected(String reason) implements ProductionPlanOutcome {

    public Rejected {
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("ProductionPlanOutcome.Rejected.reason 不得为空白");
      }
    }
  }

  /** 登记后的方案定义（拒绝时 empty）。★ 名字带 {@code OrEmpty}：record 的 {@code plan()} 访问器已占用那个签名。 */
  default Optional<IndustryDefinitionView> planOrEmpty() {
    return this instanceof Written written ? Optional.of(written.plan()) : Optional.empty();
  }

  /** 拒绝理由（成功时 empty）。 */
  default Optional<String> rejectionReason() {
    return this instanceof Rejected rejected ? Optional.of(rejected.reason()) : Optional.empty();
  }

  /** 是否登记成功。 */
  default boolean written() {
    return this instanceof Written;
  }
}
