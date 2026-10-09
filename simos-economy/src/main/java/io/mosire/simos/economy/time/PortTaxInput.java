package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.market.PortDirection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>P-T1b：本轮的"口岸税 + 区内市场税"入参（逐轮瞬态；不进 {@code EconomyData} / 不进变更集 / 不落盘）</b>。
 *
 * <pre>
 * PortTaxInput(taxByZone: 区裸值 → ZoneTaxTable)
 *
 * ZoneTaxTable(legalTender,                  ← 收税政府的法定币（= 该市场区的法定币，MarketZone.legalTender）
 *              governments,                  ← 该区管辖政府（国库户 + 暴露边权重；规范序）
 *              ratesByCommodity)             ← 逐商品的区级税率（两个方向 × 两个分量）
 *
 * CommodityTaxRates(exitPerUnitMilli, exitAdValoremPerMille, entryPerUnitMilli, entryAdValoremPerMille)
 * </pre>
 *
 * <p>★★ <b>税率为什么是"两个分量"</b>（用户原话「我建议是规则可以灵活，从量从价都行」）：从量是"毫法定币 / 商品单位" （{@link
 * io.mosire.simos.economy.api.market.PortTaxMode#PER_UNIT_MILLI}），从价是"货值的千分比" （{@link
 * io.mosire.simos.economy.api.market.PortTaxMode#AD_VALOREM_PER_MILLE}）—— 两者量纲不同，
 * <b>不能合成一个数</b>。多政府共管一个区时，两个分量<b>各自</b>按暴露边权重加权平均（与口岸开放度 E 同一套权重口径）：
 *
 * <pre>
 * perUnitMilli      = ⌊Σ(w_k × 该政府从量额) ÷ Σw⌋      （只有设了从量规则的政府进分子；都没设 ⇒ 0）
 * adValoremPerMille = ⌊Σ(w_k × 该政府从价额) ÷ Σw⌋      （只有设了从价规则的政府进分子；都没设 ⇒ 0）
 * 税额 = ⌊成交毛量 × perUnitMilli ÷ 1000⌋ + ⌊货款 × adValoremPerMille ÷ 1000⌋
 * </pre>
 *
 * <p>★★ <b>税额怎么分到各政府国库（逐值守恒）</b>：区级总额按<b>同一套权重</b>分摊 —— {@code 各国库分得 = ⌊总额 × w_k ÷
 * Σw⌋}，<b>余数给规范序最后一家</b> ⇒ {@code Σ 各国库分得 == 区级总额} （floor 不吞钱，也不多造钱）。
 *
 * <p>★★ <b>无政府的一侧 ⇒ 该侧税 = 0</b>（与"没有政府 ⇒ 开放度 1000"同源，口岸设计书 §13.2-3）：三不管的暴露边 在加权平均里交 0、也不进 {@link
 * ZoneTaxTable#governments()}；整区都没有政府 ⇒ 该区收 0。
 *
 * <p>★★ <b>缺省 = {@link #none()}（空表）⇒ 逐值退回改前行为</b>（I-C2：未设税 ⇒ 一个数都不动）。
 *
 * <p>★★ <b>币种（V-2）</b>：从量税率在<b>收税政府的法定币</b>（{@link ZoneTaxTable#legalTender()}）下计， 结算时经<b>同一份</b>
 * {@link CurrencyValuation} 折成<b>买方支付币</b>（从价是无量纲的千分比，<b>不</b>折算）—— 具名折算次序见 {@link MarketTaxBook}
 * 的类注（禁两次折算、禁换估值实例）。
 *
 * <p>★ <b>保序不可变</b>：两层表都 {@code LinkedHashMap} 拷贝 + {@code Collections.unmodifiableMap} 冻结 （不用
 * {@code Map.copyOf}；迭代序必须是内容的纯函数，I7）。
 */
public record PortTaxInput(Map<String, ZoneTaxTable> taxByZone) {

  /**
   * ★★ <b>一个市场区的税制</b>：法定币 + 收税政府 + 逐商品的区级税率。
   *
   * <p>★ 为什么"法定币/政府"在区上而"税率"在商品上：区只记"这一片用哪种钱、这块地归谁管"（{@code MarketZone} 的
   * 口径），而政策是<b>逐商品</b>设的（{@code GovPortPolicy.commodityRules}）——两张表的键齐了才是一档政策。
   *
   * @param legalTender 收税政府的法定币（= 该区法定币；从量税率的量纲、折算的起点）；不得为 null
   * @param governments 该区管辖政府（国库户 + 暴露边权重；<b>保序 = 政府 id 规范序</b>；空 = 该区没有政府 ⇒ 不收税）
   * @param ratesByCommodity 逐商品的区级税率（保序；空 = 本区没有口岸税 —— 区内市场税仍可收，它另有税率来源）
   */
  public record ZoneTaxTable(
      CurrencyId legalTender,
      List<GovernmentShare> governments,
      Map<CommodityId, CommodityTaxRates> ratesByCommodity) {

    public ZoneTaxTable {
      Objects.requireNonNull(legalTender, "ZoneTaxTable.legalTender 不得为 null");
      if (governments == null) {
        throw new IllegalArgumentException("ZoneTaxTable.governments 不得为 null（没有政府给空表）");
      }
      List<GovernmentShare> governmentCopy = new ArrayList<>(governments.size());
      for (GovernmentShare share : governments) {
        governmentCopy.add(Objects.requireNonNull(share, "ZoneTaxTable.governments 不得含 null"));
      }
      governments = Collections.unmodifiableList(governmentCopy); // ★ 保序冻结（规范序由组合根给定）
      Map<CommodityId, CommodityTaxRates> rateCopy = new LinkedHashMap<>();
      if (ratesByCommodity != null) {
        for (Map.Entry<CommodityId, CommodityTaxRates> entry : ratesByCommodity.entrySet()) {
          rateCopy.put(
              Objects.requireNonNull(entry.getKey(), "ZoneTaxTable.ratesByCommodity 的键不得为 null"),
              Objects.requireNonNull(entry.getValue(), "ZoneTaxTable.ratesByCommodity 的值不得为 null"));
        }
      }
      ratesByCommodity = Collections.unmodifiableMap(rateCopy);
    }

    /** 该区有没有<b>能收钱的政府</b>（没有 ⇒ 该区任何一层税都收 0，口岸设计书 §13.2-3）。 */
    public boolean taxable() {
      return !governments.isEmpty();
    }

    /** 某商品的区级税率；缺 ⇒ {@link CommodityTaxRates#NONE}（= 不收口岸税）。 */
    public CommodityTaxRates ratesOf(CommodityId commodity) {
      CommodityTaxRates rates = commodity == null ? null : ratesByCommodity.get(commodity);
      return rates == null ? CommodityTaxRates.NONE : rates;
    }

    /** 本区有没有"收得动"的口岸税（任一类任一侧非 0）。 */
    public boolean collectsPortTax() {
      for (CommodityTaxRates rates : ratesByCommodity.values()) {
        if (!rates.zero()) {
          return true;
        }
      }
      return false;
    }
  }

  /**
   * ★★ <b>一个商品在某区的区级税率（两个方向 × 两个分量）</b>—— 加权平均的结果，量纲写明。
   *
   * @param exitPerUnitMilli 出口·从量（毫法定币 / 商品单位；≥ 0）
   * @param exitAdValoremPerMille 出口·从价（货值千分比 ‰；≥ 0）
   * @param entryPerUnitMilli 进口·从量（毫法定币 / 商品单位；≥ 0）
   * @param entryAdValoremPerMille 进口·从价（货值千分比 ‰；≥ 0）
   */
  public record CommodityTaxRates(
      long exitPerUnitMilli,
      long exitAdValoremPerMille,
      long entryPerUnitMilli,
      long entryAdValoremPerMille) {

    /** 不收口岸税（缺键 = 全 0；唯一拼写点）。 */
    public static final CommodityTaxRates NONE = new CommodityTaxRates(0L, 0L, 0L, 0L);

    public CommodityTaxRates {
      requireNonNegative(exitPerUnitMilli, "exitPerUnitMilli");
      requireNonNegative(exitAdValoremPerMille, "exitAdValoremPerMille");
      requireNonNegative(entryPerUnitMilli, "entryPerUnitMilli");
      requireNonNegative(entryAdValoremPerMille, "entryAdValoremPerMille");
    }

    /** 某方向的区级从量税率（毫法定币 / 商品单位）。 */
    public long perUnitMilli(PortDirection direction) {
      Objects.requireNonNull(direction, "direction");
      return direction == PortDirection.EXIT ? exitPerUnitMilli : entryPerUnitMilli;
    }

    /** 某方向的区级从价税率（货值千分比 ‰）。 */
    public long adValoremPerMille(PortDirection direction) {
      Objects.requireNonNull(direction, "direction");
      return direction == PortDirection.EXIT ? exitAdValoremPerMille : entryAdValoremPerMille;
    }

    /** 两侧四个数全是 0（= 等于没设）。 */
    public boolean zero() {
      return exitPerUnitMilli == 0L
          && exitAdValoremPerMille == 0L
          && entryPerUnitMilli == 0L
          && entryAdValoremPerMille == 0L;
    }

    private static void requireNonNegative(long value, String field) {
      if (value < 0L) {
        throw new IllegalArgumentException("区级税率不得为负（非法政策）: " + field + " = " + value);
      }
    }
  }

  /**
   * ★★ <b>一个收税政府</b>：收款国库户 + 它在<b>本区暴露边</b>里的权重（与口岸开放度 E 同一套权重）。
   *
   * <p>★ {@code treasury} 的权威来源是 {@code Government.treasury()}（<b>不按 {@code hh-gov-} 前缀猜</b>）：
   * 组合根按"管着本区某块地的政府单位"反查政府记录后取它。★ 只有 {@code ActorKind.HOUSEHOLD} 的国库才进得来 （账户主体只有家户；{@code
   * GOVERNMENT} actor 国库收不了钱，组合根会具名记一条并把它排除）。
   *
   * @param governmentId 政府身份（读口的"收款政府"列；{@code gov-unit-<govUnitId>} 形态）
   * @param treasury 国库户 actor（钱铸给它）；不得为 null
   * @param exposureWeight 该政府在本区的暴露边权重（> 0；分摊比例 = 本值 ÷ 该区收税政府的权重合计）
   */
  public record GovernmentShare(String governmentId, ActorRef treasury, long exposureWeight) {

    public GovernmentShare {
      if (governmentId == null || governmentId.isBlank()) {
        throw new IllegalArgumentException("GovernmentShare.governmentId 不得为空白");
      }
      Objects.requireNonNull(treasury, "GovernmentShare.treasury 不得为 null");
      if (exposureWeight <= 0L) {
        throw new IllegalArgumentException(
            "GovernmentShare.exposureWeight 必须 > 0（0 权重的政府不进表）: " + exposureWeight);
      }
    }
  }

  private static final PortTaxInput NONE = new PortTaxInput(Map.of());

  public PortTaxInput {
    if (taxByZone == null) {
      taxByZone = Map.of();
    }
    Map<String, ZoneTaxTable> copy = new LinkedHashMap<>();
    for (Map.Entry<String, ZoneTaxTable> entry : taxByZone.entrySet()) {
      if (entry.getKey() == null || entry.getKey().isBlank()) {
        throw new IllegalArgumentException("PortTaxInput.taxByZone 的区键不得为空白");
      }
      copy.put(
          entry.getKey(),
          Objects.requireNonNull(entry.getValue(), "PortTaxInput.taxByZone 的值不得为 null"));
    }
    taxByZone = Collections.unmodifiableMap(copy);
  }

  /** 没有税（空表）；未注入的轮次一律取它 ⇒ 逐值退回改前行为（I-C2）。 */
  public static PortTaxInput none() {
    return NONE;
  }

  /** 表非空（至少一个区有条目；条目可能"只有政府、税率为 0" —— 那仍是不收税）。 */
  public boolean isActive() {
    return !taxByZone.isEmpty();
  }

  /** 某区的税制；缺区 ⇒ {@code null}（读作"这个区不收税/没有政府"）。 */
  public ZoneTaxTable tableOf(String zoneId) {
    return zoneId == null ? null : taxByZone.get(zoneId);
  }

  /** 表里的区数（读数/日志用）。 */
  public int zoneCount() {
    return taxByZone.size();
  }

  /** 表里"收了真税"的（区 × 商品 × 方向）条数（读数/日志用）。 */
  public int taxedSides() {
    int count = 0;
    for (ZoneTaxTable table : taxByZone.values()) {
      for (CommodityTaxRates rates : table.ratesByCommodity().values()) {
        if (rates.exitPerUnitMilli() > 0L || rates.exitAdValoremPerMille() > 0L) {
          count++;
        }
        if (rates.entryPerUnitMilli() > 0L || rates.entryAdValoremPerMille() > 0L) {
          count++;
        }
      }
    }
    return count;
  }

  /** 表里全部区键（保序副本；跨切片键口径核对用）。 */
  public Set<String> zoneIds() {
    return Collections.unmodifiableSet(new LinkedHashSet<>(taxByZone.keySet()));
  }

  /** 该区有没有能收钱的政府。 */
  public boolean taxable(String zoneId) {
    ZoneTaxTable table = tableOf(zoneId);
    return table != null && table.taxable();
  }

  /** 该区该商品在本方向上的区级从量税率（缺区/缺类 ⇒ 0）。 */
  public long perUnitMilli(String zoneId, CommodityId commodity, PortDirection direction) {
    ZoneTaxTable table = tableOf(zoneId);
    return table == null ? 0L : table.ratesOf(commodity).perUnitMilli(direction);
  }

  /** 该区该商品在本方向上的区级从价税率（缺区/缺类 ⇒ 0）。 */
  public long adValoremPerMille(String zoneId, CommodityId commodity, PortDirection direction) {
    ZoneTaxTable table = tableOf(zoneId);
    return table == null ? 0L : table.ratesOf(commodity).adValoremPerMille(direction);
  }

  /** 该区的收税政府（缺区 ⇒ 空表）。 */
  public List<GovernmentShare> governmentsOf(String zoneId) {
    ZoneTaxTable table = tableOf(zoneId);
    return table == null ? List.of() : table.governments();
  }
}
