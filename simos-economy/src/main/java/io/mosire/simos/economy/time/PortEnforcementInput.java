package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>R2：本轮的"口岸实际管制力"入参（逐轮瞬态；不进 {@code EconomyData} / 不进变更集 / 不落盘）</b>。
 *
 * <p>★★ <b>它解决哪件事</b>：{@code CurrencyValuation}（E 批落的家户货币估值）要按用户 2026-10-08 原话兑现
 * 「如果这个效率高，那么单个家户就更不倾向于用这种货币付款，因为如果付了要被抓」—— 那个"效率"= <b>该市场区该币种的实际管制力</b>，它由组合根（{@code
 * simos-app}）从"管辖政府的口岸政策 s × 口岸效率 e"折算而来 （设计书 §4.2/§4.3），economy 编译期看不见 gov 切片 ⇒ 只能由组合根<b>注入</b>。
 *
 * <pre>
 * PortEnforcementInput(currencyEnforcementPerMilleByZone: 区裸值 → 币种 → 管制力‰,
 *                      commodityEnforcementPerMilleByZone: 区裸值 → 商品 → 管制力‰)
 * </pre>
 *
 * <p>★★ <b>缺省 = {@link #none()}（两张空表）⇒ 逐值退回改前行为</b>（照 {@code FxRoundInput.none()} / {@code
 * GovernmentMarketMandatePlan.empty()} 的同一条形制）：没有政策的世界里"管制力 = 0" ⇒ 家户估值不减项 ⇒ 旧世界逐值不变（I-P8 的第一判据）。
 *
 * <p>★★ <b>管制力怎么算（唯一算式在组合根）</b>：
 *
 * <pre>
 * 逐接触面 k：enforcement_k = ⌊s_k × e_k ÷ 1000⌋          （s = 限制强度，缺省 0；e = 该政府口岸效率）
 * 本区该类的注入值 = 1000 − E_Z(c) = Σ(w_k × enforcement_k) ÷ Σ(w_k)   （按暴露边条数加权 = 管制力的区级总量）
 * 无接触面 ⇒ 注入值 0（没有口岸 ⇒ 没有管制 ⇒ 不减项）
 * </pre>
 *
 * <p>★ 也就是：<b>注入的就是"这个区对这类东西实际抓得有多严"（‰）</b>。它与 {@link PortRegimeAggregation} 的 {@code 1000 − E}
 * 恒等——公式的唯一拼写点在那边，本类型只装折算结果（不做聚合、不做 OR 规则）。
 *
 * <p>★ <b>保序不可变</b>：两层表都 {@code LinkedHashMap} 拷贝 + {@code Collections.unmodifiableMap} 冻结（不用
 * {@code Map.copyOf}；迭代序必须是内容的纯函数）。
 */
public record PortEnforcementInput(
    Map<String, Map<CurrencyId, Long>> currencyEnforcementPerMilleByZone,
    Map<String, Map<CommodityId, Long>> commodityEnforcementPerMilleByZone) {

  /**
   * ★★ <b>返回防御性副本</b>（修 SpotBugs {@code EI_EXPOSE_REP}）：record 的自动访问器会把内部两层表直接交出去。 ★ <b>为什么是逐层拷贝而不是
   * {@code Map.copyOf}</b>：内层表的迭代序是内容的纯函数（本类只用 {@code LinkedHashMap} 冻结）⇒ 逐层 {@code
   * Collections.unmodifiableMap(new LinkedHashMap<>(...))} 保序。
   */
  @Override
  public Map<String, Map<CurrencyId, Long>> currencyEnforcementPerMilleByZone() {
    return copyOuter(currencyEnforcementPerMilleByZone);
  }

  /** ★★ 同上（防御性副本；见 {@link #currencyEnforcementPerMilleByZone()} 的注）。 */
  @Override
  public Map<String, Map<CommodityId, Long>> commodityEnforcementPerMilleByZone() {
    return copyOuter(commodityEnforcementPerMilleByZone);
  }

  private static <K> Map<String, Map<K, Long>> copyOuter(Map<String, Map<K, Long>> outer) {
    LinkedHashMap<String, Map<K, Long>> copy = new LinkedHashMap<>();
    for (Map.Entry<String, Map<K, Long>> entry : outer.entrySet()) {
      copy.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
    }
    return Collections.unmodifiableMap(copy);
  }

  /** 没有口岸管制（旧世界 / 未注入）：两张空表。 */
  private static final PortEnforcementInput NONE = new PortEnforcementInput(Map.of(), Map.of());

  public PortEnforcementInput {
    currencyEnforcementPerMilleByZone =
        freeze(
            "currencyEnforcementPerMilleByZone",
            currencyEnforcementPerMilleByZone,
            CurrencyId.class);
    commodityEnforcementPerMilleByZone =
        freeze(
            "commodityEnforcementPerMilleByZone",
            commodityEnforcementPerMilleByZone,
            CommodityId.class);
  }

  /** 没有口岸管制（两张空表）；未注入的轮次一律取它 ⇒ 逐值退回改前行为。 */
  public static PortEnforcementInput none() {
    return NONE;
  }

  /** 两张表是否都空（空 ⇒ 本轮"没有口岸面"）。 */
  public boolean isActive() {
    return !currencyEnforcementPerMilleByZone.isEmpty()
        || !commodityEnforcementPerMilleByZone.isEmpty();
  }

  /**
   * 某区某币种的实际管制力（‰）；缺区/缺币 ⇒ <b>0</b>（= 没有管制 ⇒ 不减项）。
   *
   * <p>★ 0 与"未设"同义（用户 2026-10-09「肯定0啊」）：没有政府限制这种钱 ⇒ 家户照常认它。
   */
  public long currencyEnforcementPerMille(String zoneId, CurrencyId currency) {
    Objects.requireNonNull(currency, "currency");
    if (zoneId == null) {
      return 0L;
    }
    return valueOf(currencyEnforcementPerMilleByZone.get(zoneId), currency);
  }

  /** 某区某商品的实际管制力（‰）；缺区/缺商品 ⇒ 0。 */
  public long commodityEnforcementPerMille(String zoneId, CommodityId commodity) {
    Objects.requireNonNull(commodity, "commodity");
    if (zoneId == null) {
      return 0L;
    }
    return valueOf(commodityEnforcementPerMilleByZone.get(zoneId), commodity);
  }

  /** 设过口岸管制的区数（读数/日志用）。 */
  public int zoneCount() {
    Set<String> zones = new java.util.LinkedHashSet<>(currencyEnforcementPerMilleByZone.keySet());
    zones.addAll(commodityEnforcementPerMilleByZone.keySet());
    return zones.size();
  }

  private static <K> long valueOf(Map<K, Long> table, K key) {
    if (table == null) {
      return 0L;
    }
    Long value = table.get(key);
    return value == null ? 0L : value;
  }

  private static <K> Map<String, Map<K, Long>> freeze(
      String field, Map<String, Map<K, Long>> table, Class<K> keyType) {
    if (table == null) {
      return Map.of();
    }
    Map<String, Map<K, Long>> outer = new LinkedHashMap<>();
    for (Map.Entry<String, Map<K, Long>> entry : table.entrySet()) {
      if (entry.getKey() == null || entry.getKey().isBlank()) {
        throw new IllegalArgumentException("PortEnforcementInput." + field + " 的区键不得为空白");
      }
      Map<K, Long> inner = entry.getValue();
      if (inner == null) {
        throw new IllegalArgumentException(
            "PortEnforcementInput." + field + "[" + entry.getKey() + "] 不得为 null");
      }
      Map<K, Long> copy = new LinkedHashMap<>();
      for (Map.Entry<K, Long> item : inner.entrySet()) {
        if (item.getKey() == null || item.getValue() == null) {
          throw new IllegalArgumentException(
              "PortEnforcementInput." + field + "[" + entry.getKey() + "] 的键与值都不得为 null");
        }
        if (item.getValue() < 0L) {
          throw new IllegalArgumentException(
              "PortEnforcementInput."
                  + field
                  + "["
                  + entry.getKey()
                  + "] 的管制力（‰）不得为负: "
                  + item.getValue());
        }
        copy.put(keyType.cast(item.getKey()), item.getValue());
      }
      outer.put(entry.getKey(), Collections.unmodifiableMap(copy));
    }
    return Collections.unmodifiableMap(outer);
  }
}
