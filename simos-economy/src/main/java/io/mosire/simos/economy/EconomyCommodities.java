package io.mosire.simos.economy;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.util.economy.EconomyVocabulary;

/**
 * ★★ <b>经济切片的商品稳定 id 具名常量</b>（R3a 从{@code 旧结算引擎（R3a 已删除）} 的公开常量迁出）。
 *
 * <p>★ <b>为什么需要它</b>：旧的每日结算引擎（{@code 旧结算引擎（R3a 已删除）}）曾把 {@code GRAIN} / {@code CLOTH} 两个 {@link
 * CommodityId} 公开常量与 {@code EconomyVocabulary} 的字面量绑在一起；R3a 删除旧引擎后，**只读投影**（债务容量、家户状态读数）与 GUI
 * 读口仍要按同一份拼写取这两个商品。字面量的唯一拼写点仍是 {@link EconomyVocabulary#GRAIN_COMMODITY_ID} / {@link
 * EconomyVocabulary#CLOTH_COMMODITY_ID} —— 本类只是把它们包成类型化常量，<b>不新增第二处字面量</b>。
 *
 * <p>★ <b>边界</b>：本类不做任何计算、不写状态、不依赖任何结算类；它只服务读口、只读派生与结算日志的具名取用 （A1 起 {@link #HAUL} 由 {@code
 * EconomySettlement} 的产出入账日志按它取商品键）。
 */
public final class EconomyCommodities {

  /** 口粮商品（{@code grain}）的稳定 id。 */
  public static final CommodityId GRAIN = new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID);

  /** 衣着商品（{@code cloth}）的稳定 id。 */
  public static final CommodityId CLOTH = new CommodityId(EconomyVocabulary.CLOTH_COMMODITY_ID);

  /**
   * ★★ <b>运输服务（跑商/承运）的稳定 id</b>（A1，2026-10-10）：{@code haul} —— 中文"运输服务"。
   *
   * <p>★ <b>它是什么</b>：用户 2026-10-10 裁定的"运输服务算商品"里的那个商品（约束设计书 §3.1）。字面量的唯一拼写点仍是 {@link
   * EconomyVocabulary#HAUL_COMMODITY_ID} —— 本常量只是把它包成类型化常量，**不新增第二处字面量**。
   *
   * <p>★ <b>谁产它</b>：{@code trade@hex} 产业（跑商）按 {@code Industry.outputPerUnit} 在**生产阶段**产出 —— 这就是
   * "把跑商并回标准生产管线"的第一步（用户原话「跑商不是生产方式吗？」）。 ★ <b>本批（A1）没有牌价、没有派生需求</b> ⇒ 按既有口径"缺价 ⇒
   * 不交易"，世界里不会出现运输服务的成交（缺省中性）。
   */
  public static final CommodityId HAUL = new CommodityId(EconomyVocabulary.HAUL_COMMODITY_ID);

  private EconomyCommodities() {}
}
