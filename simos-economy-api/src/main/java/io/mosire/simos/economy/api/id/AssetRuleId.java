package io.mosire.simos.economy.api.id;

import io.mosire.simos.actor.api.asset.AssetKind;

/**
 * ★★ <b>生产资料规则（{@code AssetRule}）的稳定身份</b>（理想架构 §2.5/§3.3；E2）：一个 mode 下"某种生产资料"
 * 的产权/租佃/抵押/清算/转移规则。身份 = {@code (modeId, assetKind)} 的纯函数 —— 同一个 mode 的同一资产种类 <b>只允许一条规则</b>（由
 * {@code EconomyData} 的构造期守卫判死）。
 *
 * <p>★ 裸值 {@code toString()} + {@code static parse} 三件套（铁律 1）；{@code parse} 是 opaque 的，不做格式再解释。 ★
 * <b>规范串不含 {@code "."}</b>：地址会被 {@code AddressParser} 在第一个点处截断 ⇒ 本类型在构造期当场抛。
 *
 * <p>★★ <b>新 id 的唯一拼写点</b> = {@link #idOf(ProductionModeId, AssetKind)}： {@code
 * asset-rule-<mode>-<asset>}。确定性同 {@link ProductionOrganizationId#idOf}。
 *
 * @param value 非空白且不含 {@code "."} 的稳定规范串
 */
public record AssetRuleId(String value) {

  public AssetRuleId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("AssetRuleId 不得为空白");
    }
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException("AssetRuleId 不得含 '.'（地址会被第一个点截断）: " + value);
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白与不含 {@code "."}；不做格式约束（新 id 的拼写点属命令层）。 */
  public static AssetRuleId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("AssetRuleId 不得为空白: " + text);
    }
    return new AssetRuleId(text);
  }

  /**
   * ★★ <b>新 id 的唯一拼写点</b>：{@code asset-rule-<mode>-<asset>}（不含 {@code "."}）。
   *
   * @param modeId 规则所属的生产方式；不得为 null
   * @param assetKind 生产资料种类；不得为 null
   */
  public static AssetRuleId idOf(ProductionModeId modeId, AssetKind assetKind) {
    if (modeId == null) {
      throw new IllegalArgumentException("AssetRuleId.idOf 的 modeId 不得为 null");
    }
    if (assetKind == null) {
      throw new IllegalArgumentException("AssetRuleId.idOf 的 assetKind 不得为 null");
    }
    String value = "asset-rule-" + modeId.value() + "-" + assetKind.name();
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException("AssetRule id 不得含 '.'（地址会被第一个点截断）: " + value);
    }
    return new AssetRuleId(value);
  }
}
