package io.mosire.simos.economy.model;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.AssetRuleId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>生产资料规则</b>（理想架构 §2.5 的 {@code AssetRule}；E2）：一个生产方式（{@link ProductionModeId}）下
 * "某种生产资料（{@link AssetKind}）"的产权/租佃/抵押/清算/转移规则。
 *
 * <pre>
 * AssetRule(id, modeId, assetKind,
 *           isCoreMeans,        // 是否核心生产资料（E5 清算的保护边界：口粮/种粮/最低生产资料不处置）
 *           pledgeable,         // 可否抵押/质押（E4/E5 的信用与清算读它）
 *           liquidationPriority,// 清算顺序（越小越先处置；E5 的稳定序）
 *           rentRule,           // 租来的怎么收租（空 = 这种生产资料不产生租金模板）
 *           transferRule)       // 所有权/转租的边界
 * </pre>
 *
 * <p>★★ <b>身份 = (modeId, assetKind) 的纯函数</b>（{@link AssetRuleId#idOf}）：同一个 mode 的同一资产种类
 * <b>只允许一条规则</b>。这条唯一性由 {@code EconomyData} 的构造期守卫判死（不是"最后写入者赢"这种静默覆盖）。
 *
 * <p>★ <b>本类型只存规则、不含公式</b>：租金模板 → 结算规则的转换、清算顺序怎么用，分别在 E2 的组织阶段与 E5 的清算阶段； 本类不解释 {@link
 * RentRule}，也不对 {@code liquidationPriority} 做跨资产排序（那要看见整张表）。
 *
 * <p>★ <b>不可变</b>：全部组件是值/枚举/不可变记录（{@link RentRule} 与 {@link TransferRule} 的构造期都已冻结自己的表）。
 *
 * @param id 稳定身份；不得为 null（键 == 值内 id 由 {@code EconomyData} 判）
 * @param modeId 规则所属的生产方式；不得为 null
 * @param assetKind 生产资料种类；不得为 null
 * @param isCoreMeans 是否核心生产资料
 * @param pledgeable 可否抵押/质押
 * @param liquidationPriority 清算顺序（≥ 0；越小越先处置）
 * @param rentRule 租金模板；不得为 null（不产生租金用 {@code Optional.empty()}）
 * @param transferRule 可转移规则；不得为 null
 */
public record AssetRule(
    AssetRuleId id,
    ProductionModeId modeId,
    AssetKind assetKind,
    boolean isCoreMeans,
    boolean pledgeable,
    int liquidationPriority,
    Optional<RentRule> rentRule,
    TransferRule transferRule) {

  public AssetRule {
    Objects.requireNonNull(id, "AssetRule.id 不得为 null");
    Objects.requireNonNull(modeId, "AssetRule.modeId 不得为 null");
    Objects.requireNonNull(assetKind, "AssetRule.assetKind 不得为 null");
    Objects.requireNonNull(rentRule, "AssetRule.rentRule 不得为 null（不产生租金请用 Optional.empty()）");
    Objects.requireNonNull(transferRule, "AssetRule.transferRule 不得为 null");
    if (liquidationPriority < 0) {
      throw new IllegalArgumentException(
          "AssetRule.liquidationPriority 不得为负: " + liquidationPriority);
    }
  }

  /** id 是否与本规则身份一致（{@link AssetRuleId#idOf} 的纯函数检查；读口/测试用它判"键 == 值内 id"）。 */
  public boolean idMatchesIdentity() {
    return id.equals(AssetRuleId.idOf(modeId, assetKind));
  }
}
