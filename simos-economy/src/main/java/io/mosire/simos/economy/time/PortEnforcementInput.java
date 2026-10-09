package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.market.PortDirection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>P-T1a：本轮的"口岸实际管制力"入参（逐轮瞬态；不进 {@code EconomyData} / 不进变更集 / 不落盘）</b>
 *
 * <p>★★ <b>它解决哪两件事</b>：
 *
 * <ol>
 *   <li><b>币种维（R2 起）</b>：{@code CurrencyValuation}（E 批落的家户货币估值）按用户 2026-10-08 原话「如果这个效率高，
 *       那么单个家户就更不倾向于用这种货币付款，因为如果付了要被抓」读<b>该区该币种的实际管制力</b>；
 *   <li>★★ <b>P-T1a 新增：跨区节流（设计书 §11/§12）</b>：跨区候选配对处按 {@code 可通过比例 = E_源(出口) × E_目的(入口) ÷ 1e6} 节流
 *       —— 本类型给出**逐区逐类的两个方向**的实际管制力。
 * </ol>
 *
 * <pre>
 * PortEnforcementInput(currencyEnforcementPerMilleByZone:  区裸值 → 币种 → (入口‰, 出口‰),
 *                      commodityEnforcementPerMilleByZone: 区裸值 → 商品 → (入口‰, 出口‰))
 * </pre>
 *
 * <p>★★ <b>表里存的是"管制力 = 1000 − E"（‰），不是 E</b>（设计书 §12.4 冻结口径："表里存的是 {@code 1000 − E}， 取 {@code E =
 * 1000 − 该值}"）——经济侧要开放度时取 {@link #commodityOpennessPerMille} / {@link
 * #currencyOpennessPerMille}，不要在别处再拼一次减法。
 *
 * <p>★★ <b>为什么要方向维</b>：用户 2026-10-10「出入都设规则拦，分别按口岸效率算……都需要两边都过才能跨区」⇒ 一个区一个值
 * <b>表达不出来</b>：跨区时<b>出口</b>读<b>源区</b>的出口规则算出的 {@code E_源}、<b>入口</b>读<b>目的区</b>的入口规则算出的 {@code
 * E_目的}（{@link PortDirection}）。
 *
 * <p>★★ <b>缺省 = {@link #none()}（两张空表）⇒ 逐值退回改前行为</b>（照 {@code FxRoundInput.none()} / {@code
 * GovernmentMarketMandatePlan.empty()} 的同一条形制）：没有政策的世界里"管制力 = 0" ⇒ 家户估值不减项、跨区节流恒等于 {@code transit ×
 * 1000 × 1000 ÷ 1e6 = transit} ⇒ 旧世界逐值不变（I-P8 的第一判据）。
 *
 * <p>★★ <b>管制力怎么算（唯一算式在组合根）</b>：
 *
 * <pre>
 * 逐接触面 k：enforcement_k = ⌊s_k × e_k ÷ 1000⌋          （s = 该方向该类的限制强度，缺省 0；e = 该政府口岸效率）
 * 本区该方向的实际管制力 = 1000 − E_Z(c, 方向) = Σ(w_k × enforcement_k) ÷ Σ(w_k)   （按暴露边条数加权）
 * 无接触面 ⇒ 管制力 0（没有口岸 ⇒ 没有管制 ⇒ 不减项、不节流）
 * </pre>
 *
 * ★ <b>无政府那一侧 = 1000‰ 开放</b>（设计书 §12.2-4：对面三不管／无区 ⇒ 没有口岸可管）⇒ 组合根本侧管制力 0 （三不管的暴露边 {@code s =
 * 0}），节流实际由有规则那侧决定。
 *
 * <p>★ <b>保序不可变</b>：两层表都 {@code LinkedHashMap} 拷贝 + {@code Collections.unmodifiableMap} 冻结（不用
 * {@code Map.copyOf}；迭代序必须是内容的纯函数）。
 */
public record PortEnforcementInput(
    Map<String, Map<CurrencyId, Directional>> currencyEnforcementPerMilleByZone,
    Map<String, Map<CommodityId, Directional>> commodityEnforcementPerMilleByZone) {

  /**
   * ★★ <b>逐方向的实际管制力（‰）= 1000 − E</b>：入口一侧与出口一侧<b>各一个数</b>（设计书 §12.2）。
   *
   * <p>★ 两个分量都是 {@code [0,1000]}（管制力 = 1000 − 开放度，而开放度被钳在 {@code [0,1000]}）⇒ 开放度 = 1000 − 管制力 恒落在
   * {@code [0,1000]}，下游不必再钳。
   *
   * @param entryPerMille 入口侧管制力（‰）：本区是<b>目的地</b>时用（{@link PortDirection#ENTRY}）
   * @param exitPerMille 出口侧管制力（‰）：本区是<b>来源地</b>时用（{@link PortDirection#EXIT}）
   */
  public record Directional(long entryPerMille, long exitPerMille) {

    /** 千分制上限：{@code 1000‰ = 全关}（管制力不可能超过它——开放度被钳在 {@code [0,1000]}）。 */
    public static final long FULL_ENFORCEMENT_PER_MILLE = 1000L;

    /** 两个方向都没有管制（= 两侧全开）；这种条目<b>不入表</b>（0 与"缺键"读法同义）。 */
    public static final Directional NONE = new Directional(0L, 0L);

    public Directional {
      requireRange(entryPerMille, "entryPerMille（入口管制力‰）");
      requireRange(exitPerMille, "exitPerMille（出口管制力‰）");
    }

    /** 某方向的管制力（‰）。 */
    public long of(PortDirection direction) {
      Objects.requireNonNull(direction, "direction");
      return direction == PortDirection.ENTRY ? entryPerMille : exitPerMille;
    }

    /** 两个方向都是 0（⇒ 这一条不产生任何口岸面）。 */
    public boolean zero() {
      return entryPerMille == 0L && exitPerMille == 0L;
    }

    /**
     * ★ 币种估值减项用的<b>标量</b>口径：取<b>更严的那一侧</b>（见 {@link #currencyEnforcementPerMille(String,
     * CurrencyId)}）。
     */
    public long strictestPerMille() {
      return Math.max(entryPerMille, exitPerMille);
    }

    private static void requireRange(long value, String field) {
      if (value < 0L || value > FULL_ENFORCEMENT_PER_MILLE) {
        throw new IllegalArgumentException(
            "PortEnforcementInput.Directional." + field + " 必须落在 [0,1000]: " + value);
      }
    }
  }

  /**
   * ★★ <b>返回防御性副本</b>（修 SpotBugs {@code EI_EXPOSE_REP}）：record 的自动访问器会把内部两层表直接交出去。 ★ <b>为什么是逐层拷贝而不是
   * {@code Map.copyOf}</b>：内层表的迭代序是内容的纯函数（本类只用 {@code LinkedHashMap} 冻结）⇒ 逐层 {@code
   * Collections.unmodifiableMap(new LinkedHashMap<>(...))} 保序。
   */
  @Override
  public Map<String, Map<CurrencyId, Directional>> currencyEnforcementPerMilleByZone() {
    return copyOuter(currencyEnforcementPerMilleByZone);
  }

  /** ★★ 同上（防御性副本；见 {@link #currencyEnforcementPerMilleByZone()} 的注）。 */
  @Override
  public Map<String, Map<CommodityId, Directional>> commodityEnforcementPerMilleByZone() {
    return copyOuter(commodityEnforcementPerMilleByZone);
  }

  private static <K> Map<String, Map<K, Directional>> copyOuter(
      Map<String, Map<K, Directional>> outer) {
    LinkedHashMap<String, Map<K, Directional>> copy = new LinkedHashMap<>();
    for (Map.Entry<String, Map<K, Directional>> entry : outer.entrySet()) {
      copy.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
    }
    return Collections.unmodifiableMap(copy);
  }

  /** 千分制（与 {@code PortRegimeAggregation.PER_MILLE} 同值；本类不依赖它，就地声明）。 */
  public static final long PER_MILLE = 1000L;

  /** 没有口岸管制（旧世界 / 未注入）：两张空表。 */
  private static final PortEnforcementInput NONE = new PortEnforcementInput(Map.of(), Map.of());

  public PortEnforcementInput {
    currencyEnforcementPerMilleByZone =
        freeze("currencyEnforcementPerMilleByZone", currencyEnforcementPerMilleByZone);
    commodityEnforcementPerMilleByZone =
        freeze("commodityEnforcementPerMilleByZone", commodityEnforcementPerMilleByZone);
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
   * 某区某商品某方向的实际管制力（‰）；缺区/缺商品/缺方向 ⇒ <b>0</b>（= 没有管制 ⇒ 不节流）。
   *
   * <p>★ 0 与"未设"同义（用户 2026-10-09「肯定0啊」）：没有政府限制这种货 ⇒ 该侧全开（{@code E = 1000}）。
   */
  public long commodityEnforcementPerMille(
      String zoneId, CommodityId commodity, PortDirection direction) {
    Objects.requireNonNull(commodity, "commodity");
    Objects.requireNonNull(direction, "direction");
    return directionalOf(commodityEnforcementPerMilleByZone, zoneId, commodity).of(direction);
  }

  /**
   * ★★ <b>某区某商品某方向的开放度 {@code E = 1000 − 管制力}</b>（‰）—— 跨区两侧节流（P-T1a）唯一读它。
   *
   * <p>取值恒在 {@code [0,1000]}（{@link Directional} 的构造期守卫保证了管制力落在 {@code [0,1000]}）。
   */
  public long commodityOpennessPerMille(
      String zoneId, CommodityId commodity, PortDirection direction) {
    return PER_MILLE - commodityEnforcementPerMille(zoneId, commodity, direction);
  }

  /** 某区某币种某方向的实际管制力（‰）；缺区/缺币种 ⇒ 0。 */
  public long currencyEnforcementPerMille(
      String zoneId, CurrencyId currency, PortDirection direction) {
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(direction, "direction");
    return directionalOf(currencyEnforcementPerMilleByZone, zoneId, currency).of(direction);
  }

  /** 某区某币种某方向的开放度（‰）= 1000 − 管制力。 */
  public long currencyOpennessPerMille(
      String zoneId, CurrencyId currency, PortDirection direction) {
    return PER_MILLE - currencyEnforcementPerMille(zoneId, currency, direction);
  }

  /**
   * ★★ <b>某区某币种的"标量"管制力（‰）—— 取两个方向里更严的那一侧</b>：{@link Directional#strictestPerMilli()}。
   *
   * <p>★★ <b>这是 P-T1a 的实现口径判断（控制方未钉死，账本 §关键判断记了它）</b>：本标量只服务 {@code CurrencyValuation}
   * 的<b>家户估值减项</b>——那条机制是"这种钱在这个区有多不受待见"的<b>感知量</b>， 不是"过境流量"（后者必须两侧相乘，{@link
   * PortThrottle}）。两侧里任一侧管得严 ⇒ 这种钱就有被抓的风险 ⇒ 取更严的一侧（{@code max}）。★ 缺省（只有一侧设了限制）时 {@code max}
   * 与"就取那一侧"逐值相同；两侧都没设 ⇒ 0 ⇒ 逐值退回改前（I-P8）。★ 设计书 §14.6 已把币种维的机制判给"挂单禁入/禁出 + 手续费"（P-T5），届时本条减项可能整体撤销。
   */
  public long currencyEnforcementPerMille(String zoneId, CurrencyId currency) {
    Objects.requireNonNull(currency, "currency");
    return directionalOf(currencyEnforcementPerMilleByZone, zoneId, currency).strictestPerMille();
  }

  /** 设过口岸管制的区数（读数/日志用）。 */
  public int zoneCount() {
    Set<String> zones = new java.util.LinkedHashSet<>(currencyEnforcementPerMilleByZone.keySet());
    zones.addAll(commodityEnforcementPerMilleByZone.keySet());
    return zones.size();
  }

  /** 设过口岸管制的（区 × 类）条数（读数/日志用；商品 + 币种）。 */
  public int classCount() {
    int count = 0;
    for (Map<CurrencyId, Directional> table : currencyEnforcementPerMilleByZone.values()) {
      count += table.size();
    }
    for (Map<CommodityId, Directional> table : commodityEnforcementPerMilleByZone.values()) {
      count += table.size();
    }
    return count;
  }

  /** 表里全部区键（保序副本；跨切片键口径核对用）。 */
  public Set<String> zoneIds() {
    Set<String> zones = new java.util.LinkedHashSet<>(currencyEnforcementPerMilleByZone.keySet());
    zones.addAll(commodityEnforcementPerMilleByZone.keySet());
    return Collections.unmodifiableSet(zones);
  }

  /** 某区某类的逐方向管制力；缺区/缺类 ⇒ {@link Directional#NONE}（= 两侧全开）。 */
  private static <K> Directional directionalOf(
      Map<String, Map<K, Directional>> table, String zoneId, K key) {
    if (zoneId == null) {
      return Directional.NONE;
    }
    Map<K, Directional> inner = table.get(zoneId);
    if (inner == null) {
      return Directional.NONE;
    }
    Directional value = inner.get(key);
    return value == null ? Directional.NONE : value;
  }

  private static <K> Map<String, Map<K, Directional>> freeze(
      String field, Map<String, Map<K, Directional>> table) {
    if (table == null) {
      return Map.of();
    }
    Map<String, Map<K, Directional>> outer = new LinkedHashMap<>();
    for (Map.Entry<String, Map<K, Directional>> entry : table.entrySet()) {
      if (entry.getKey() == null || entry.getKey().isBlank()) {
        throw new IllegalArgumentException("PortEnforcementInput." + field + " 的区键不得为空白");
      }
      Map<K, Directional> inner = entry.getValue();
      if (inner == null) {
        throw new IllegalArgumentException(
            "PortEnforcementInput." + field + "[" + entry.getKey() + "] 不得为 null");
      }
      Map<K, Directional> copy = new LinkedHashMap<>();
      for (Map.Entry<K, Directional> item : inner.entrySet()) {
        if (item.getKey() == null || item.getValue() == null) {
          throw new IllegalArgumentException(
              "PortEnforcementInput." + field + "[" + entry.getKey() + "] 的键与值都不得为 null");
        }
        copy.put(item.getKey(), item.getValue());
      }
      outer.put(entry.getKey(), Collections.unmodifiableMap(copy));
    }
    return Collections.unmodifiableMap(outer);
  }
}
