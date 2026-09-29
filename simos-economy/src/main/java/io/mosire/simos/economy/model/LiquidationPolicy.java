package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.AssetRuleId;
import java.util.Objects;

/**
 * ★★ <b>清算政策</b>（理想架构 §5.4；E5a 地基）：在<b>不改写 {@link AssetRule}</b> 的前提下，为一条 {@link AssetRuleId}
 * 追加"超出技术模板的清算制度参数"。
 *
 * <pre>
 * LiquidationPolicy(
 *   ruleId,                       // 键 == 值内 ruleId（身份 = AssetRuleId，不再造 (modeId, assetKind) 第二身份）
 *   maxLiquidatePerMille,         // 每次清算最多处置的比例（0..1000；1000 = 可全量处置）
 *   protectedReserve,             // 保护口径（与 AssetShare.quantity 同单位；口粮/种粮/最低生产资料）
 *   priceSource: MARKET | AGREED | POLICY,
 *   policyValuePerUnitMilli,      // POLICY 档的账面价（每单位毫值）；其它档必须为 0
 *   recipientRule: CREDITOR_FIRST | MARKET_FIRST)
 * </pre>
 *
 * <p>★★ <b>用 {@link AssetRuleId} 做键的理由</b>：{@code AssetRule} 的身份已经是 {@code (modeId, assetKind)}
 * 的纯函数；若清算政策再以 {@code (modeId, assetKind)} 为键，同一件事就有了两处拼写点 —— 引用规则的一条与政策自己的一条可以在 mode/asset
 * 上漂开。故本类型只持 {@code ruleId}，由 {@code EconomyData} 构造期按"规则表非空则被引用规则必须存在"fail-closed。
 *
 * <p>★ <b>E5a 只落形状与守卫</b>：本类不执行清算、不解释比例/保护线/受偿顺序，也不读资产市场。执行顺序（核心程度 → 抵押优先级 →
 * AssetShareId）与"租佃份额不能由佃户卖"的边界在 E5b 的清算阶段；本类只把制度参数<b>可持久化地</b>记下来。
 *
 * <p>★ <b>不可变值记录</b>：全部字段为值/枚举；构造期把取值范围判死（非法组合当场抛，不静默折算）。空表 = 旧行为逐值不变。
 *
 * @param ruleId 被补充清算参数的生产资料规则；不得为 null（EconomyData 判"键 == 值内 ruleId"）
 * @param maxLiquidatePerMille 每次清算最多处置的千分比；必须 ∈ [0, 1000]
 * @param protectedReserve 保护量（与 AssetShare.quantity 同单位）；不得为负
 * @param priceSource 处置计价来源；不得为 null
 * @param policyValuePerUnitMilli POLICY 档的账面单价（毫值/单位）；不得为负；非 POLICY 档必须为 0
 * @param recipientRule 受偿顺序规则；不得为 null
 */
public record LiquidationPolicy(
    AssetRuleId ruleId,
    int maxLiquidatePerMille,
    long protectedReserve,
    PriceSource priceSource,
    long policyValuePerUnitMilli,
    RecipientRule recipientRule) {

  /** 处置计价来源：有市场价用 MARKET、有合同价用 AGREED、都没有才用制度账面价 POLICY。 */
  public enum PriceSource {
    /** 市场现行价（E5b 从市场读数组件取；本类型不存价）。 */
    MARKET,
    /** 债务合同/租约里的约定价。 */
    AGREED,
    /** 制度账面价（{@link #policyValuePerUnitMilli}，由 mode/政策显式给定）。 */
    POLICY
  }

  /** 处置受偿对象规则：先给债权人，还是先走市场。 */
  public enum RecipientRule {
    /** 优先转给债权人抵债（CREDITOR_FIRST）。 */
    CREDITOR_FIRST,
    /** 优先在市场变现（MARKET_FIRST）。 */
    MARKET_FIRST
  }

  public LiquidationPolicy {
    Objects.requireNonNull(ruleId, "LiquidationPolicy.ruleId 不得为 null");
    if (maxLiquidatePerMille < 0 || maxLiquidatePerMille > 1000) {
      throw new IllegalArgumentException(
          "LiquidationPolicy.maxLiquidatePerMille 必须 ∈ [0, 1000]: " + maxLiquidatePerMille);
    }
    if (protectedReserve < 0L) {
      throw new IllegalArgumentException(
          "LiquidationPolicy.protectedReserve 不得为负: " + protectedReserve);
    }
    Objects.requireNonNull(priceSource, "LiquidationPolicy.priceSource 不得为 null");
    if (policyValuePerUnitMilli < 0L) {
      throw new IllegalArgumentException(
          "LiquidationPolicy.policyValuePerUnitMilli 不得为负: " + policyValuePerUnitMilli);
    }
    if (priceSource != PriceSource.POLICY && policyValuePerUnitMilli != 0L) {
      throw new IllegalArgumentException(
          "LiquidationPolicy.policyValuePerUnitMilli 只在 POLICY 档可为非 0：priceSource="
              + priceSource
              + "，值="
              + policyValuePerUnitMilli);
    }
    Objects.requireNonNull(recipientRule, "LiquidationPolicy.recipientRule 不得为 null");
  }
}
