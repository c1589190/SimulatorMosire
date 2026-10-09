package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.Objects;

/**
 * ★★ <b>商品基础运费的「未设过表」具名缺省</b>（毫计价货币 / 商品单位 / 程）—— 全仓**唯一拼写点**。
 *
 * <p>★★ <b>它不是第二本权威</b>：{@code EconomyData.commodityFreightBaseMilli} 表里**有**该商品的键 ⇒
 * <b>状态值唯一权威</b>；只有"表里没有这个商品"（旧档缺该组件键、新世界创世、夹具空表、以及词表里没登记的商品）才落到本类。
 *
 * <p>★ <b>数值来源（现行硬编码分档，逐值照搬）</b>：{@code MarketSettlement} 在 2026-10-09 之前把这组数写死在私有 dispatch {@code
 * commodityFreightBaseMilli(商品)} 里（源自 {@code d7604ea5}，**非本批引入**），其注释原文即用户口径 「<b>运费只和商品种类有关</b>」：粮
 * 1 / 纤维 1 / 布 2 / 工具 3（毫 / 单位 / 程）；词表里没登记的商品 ⇒ {@link #DEFAULT_MILLI} = 1。
 *
 * <p>★★ <b>为什么缺键要回落到这组数、而不是一律 1</b>：约束设计书 §4.1 修正版要求「旧档缺该组件键 ⇒ 归一到现行硬编码分档 ⇒
 * <b>旧世界逐值不变</b>（I-F1）」。若缺键一律取 1，布(2)/工具(3) 的运费会当场变便宜 ⇒ 未设表的世界**不再**逐值等于改动前， I-F1 不成立。GM 要把某商品改成
 * 1（或 0 = 免基础费）⇒ <b>显式设它</b>，表里就有键了。
 *
 * <p>★ <b>量纲</b>：与 {@link TransportTariff} 无关 —— 本类是"每件每程的面值"，费率是"距离/辐射/道路/城乡的千分比"； 两者在 {@code
 * MarketSettlement.freightUnitMilli} 里相乘（唯一算式）。商品维**只**活在这一处，费率维不许再有商品系数。
 */
public final class CommodityFreightBase {

  /** 词表里没登记的商品的基础运费（毫 / 单位 / 程）；也是"缺键 ⇒ 具名缺省"里那个「说不出价 ⇒ 1 毫」。 */
  public static final long DEFAULT_MILLI = 1L;

  /** 粮：1 毫 / 单位 / 程（现行硬编码分档）。 */
  public static final long GRAIN_MILLI = 1L;

  /** 纤维：1 毫 / 单位 / 程（与粮同档：轻而贱）。 */
  public static final long FIBER_MILLI = 1L;

  /** 布：2 毫 / 单位 / 程（比粮更重、更占运力，故基础费高一档）。 */
  public static final long CLOTH_MILLI = 2L;

  /** 工具：3 毫 / 单位 / 程（最重的一档）。 */
  public static final long TOOL_MILLI = 3L;

  private CommodityFreightBase() {}

  /**
   * 「表里没有这个商品」时的基础运费（毫 / 单位 / 程）：粮 1 / 纤维 1 / 布 2 / 工具 3；其他（含词表未登记的商品）⇒ 1。
   *
   * <p>★ 这是"缺键 ⇒ 具名缺省"的**唯一**实现；{@code EconomyData.commodityFreightBaseMilliOf} 与 {@code
   * MarketTopology.commodityFreightBaseMilliOf} 都调它。逐值等于 F 批之前写死在 {@code MarketSettlement} dispatch
   * 里的那一组数。
   */
  public static long legacyMilli(CommodityId commodity) {
    Objects.requireNonNull(commodity, "commodity");
    String value = commodity.value();
    if (EconomyVocabulary.GRAIN_COMMODITY_ID.equals(value)) {
      return GRAIN_MILLI;
    }
    if (EconomyVocabulary.FIBER_COMMODITY_ID.equals(value)) {
      return FIBER_MILLI;
    }
    if (EconomyVocabulary.CLOTH_COMMODITY_ID.equals(value)) {
      return CLOTH_MILLI;
    }
    if (EconomyVocabulary.TOOL_COMMODITY_ID.equals(value)) {
      return TOOL_MILLI;
    }
    return DEFAULT_MILLI;
  }
}
