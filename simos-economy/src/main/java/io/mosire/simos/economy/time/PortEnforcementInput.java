package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.market.MarketOrderKind;
import io.mosire.simos.economy.api.market.PortDirection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>P-T1a/P-T1e：本轮的"口岸实际管制力"入参（逐轮瞬态；不进 {@code EconomyData} / 不进变更集 / 不落盘）</b>
 *
 * <p>★★ <b>它解决哪三件事</b>：
 *
 * <ol>
 *   <li><b>币种维（R2 起）</b>：{@code CurrencyValuation}（E 批落的家户货币估值）按用户 2026-10-08 原话「如果这个效率高，
 *       那么单个家户就更不倾向于用这种货币付款，因为如果付了要被抓」读<b>该区该币种的实际管制力</b>；
 *   <li>★★ <b>P-T1a：跨区节流（设计书 §11/§12）</b>：跨区候选配对处按 {@code 可通过比例 = E_源(出口) × E_目的(入口) ÷ 1e6} 节流 ——
 *       本类型给出<b>逐区逐商品</b>的两个方向的实际管制力；
 *   <li>★★ <b>P-T1e：币种挂单过滤（设计书 §14.3）</b>：币种表<b>再按挂单类型</b>（兑换 / 货↔钱 / 借贷， {@link
 *       MarketOrderKind}）分列 —— 规则可以"禁止某类挂单进入市场／出市场"（用户："有一方不给过就不过"），
 *       而"禁止本市场区货币被外国借贷"这条规则<b>只有带上类型键才表达得出来</b>。
 * </ol>
 *
 * <pre>
 * PortEnforcementInput(currencyEnforcementPerMilleByZone:  区裸值 → 币种 → (逐挂单类型 → (入口‰, 出口‰)),
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
 * 1000 × 1000 ÷ 1e6 = transit}、币种挂单闸恒放行 ⇒ 旧世界逐值不变（I-P8/I-C2 的第一判据）。
 *
 * <p>★★ <b>管制力怎么算（唯一算式在组合根）</b>：
 *
 * <pre>
 * 逐接触面 k：enforcement_k = ⌊s_k × e_k ÷ 1000⌋          （s = 该方向该类该挂单类型的限制强度，缺省 0；e = 该政府口岸效率）
 * 本区该方向的实际管制力 = 1000 − E_Z(c, 类型, 方向) = Σ(w_k × enforcement_k) ÷ Σ(w_k)   （按暴露边条数加权）
 * 无接触面 ⇒ 管制力 0（没有口岸 ⇒ 没有管制 ⇒ 不减项、不节流、不拦挂单）
 * </pre>
 *
 * ★ <b>无政府那一侧 = 1000‰ 开放</b>（设计书 §12.2-4：对面三不管／无区 ⇒ 没有口岸可管）⇒ 组合根本侧管制力 0 （三不管的暴露边 {@code s =
 * 0}），节流与挂单闸实际由有规则那侧决定。
 *
 * <p>★ <b>保序不可变</b>：三层表都 {@code LinkedHashMap} 拷贝 + {@code Collections.unmodifiableMap} 冻结（不用
 * {@code Map.copyOf}；迭代序必须是内容的纯函数）。
 */
public record PortEnforcementInput(
    Map<String, Map<CurrencyId, CurrencyEnforcement>> currencyEnforcementPerMilleByZone,
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
   * ★★ <b>P-T1e：一个区里"某种钱"的逐挂单类型管制力</b>（{@link MarketOrderKind} → {@link Directional}）。
   *
   * <p>★★ <b>为什么要这一层</b>：设计书 §14.3-4——规则必须能按"挂单类型"分列（用户例子："禁止本市场区货币被外国借贷"，
   * 并点明"借贷走的也是市场挂单"）。同一个币上，"能不能拿它做兑换"与"能不能拿它做借贷"是两条独立规则 ⇒ 一个"币种 → 一个数"的 形状表达不出来。
   *
   * <p>★ <b>缺键 = 该类型不限制</b>（{@link Directional#NONE}）：一条规则都没设 ⇒ 全类型全方向 0。
   */
  public record CurrencyEnforcement(Map<MarketOrderKind, Directional> enforcementPerKind) {

    /** 没有任何类型的管制（= 全开）；这种条目<b>不入表</b>（与"缺键"读法同义）。 */
    public static final CurrencyEnforcement NONE = new CurrencyEnforcement(Map.of());

    /** ★★ <b>返回防御性副本</b>（修 SpotBugs {@code EI_EXPOSE_REP}）：保序不可变（不用 {@code Map.copyOf}）。 */
    @Override
    public Map<MarketOrderKind, Directional> enforcementPerKind() {
      return Collections.unmodifiableMap(new LinkedHashMap<>(enforcementPerKind));
    }

    public CurrencyEnforcement {
      enforcementPerKind =
          freezeKinds("currencyEnforcementPerMilleByZone.byKind", enforcementPerKind);
    }

    /** 某类型的两侧管制力；缺类型键 ⇒ {@link Directional#NONE}。 */
    public Directional of(MarketOrderKind orderKind) {
      Objects.requireNonNull(orderKind, "orderKind");
      Directional value = enforcementPerKind.get(orderKind);
      return value == null ? Directional.NONE : value;
    }

    /** 某类型某方向的管制力（‰）。 */
    public long enforcementPerMille(MarketOrderKind orderKind, PortDirection direction) {
      return of(orderKind).of(direction);
    }

    /** 有没有任一类型设过管制（{@code false} ⇒ 这种钱不产生口岸面）。 */
    public boolean zero() {
      for (Directional value : enforcementPerKind.values()) {
        if (!value.zero()) {
          return false;
        }
      }
      return true;
    }

    /**
     * ★★ <b>标量口径（取所有类型 × 两个方向里最严的一侧）</b>：只服务 {@code CurrencyValuation} 的<b>家户估值减项</b> ——
     * 那是"这种钱在这个区有多不受待见"的<b>感知量</b>（不是"过境流量"，也不是"某类挂单过不过"）。
     *
     * <p>★ <b>它与改前逐值相同</b>：P-T1a 的币种表只有"币种 → 两侧"，本层只是把同一个数按类型拆开 ⇒ 只有一种类型设了规则时， 取最严 = 取那一侧 =
     * 改前的值（缺省中性，I-C2）。
     */
    public long strictestPerMille() {
      long strictest = 0L;
      for (Directional value : enforcementPerKind.values()) {
        strictest = Math.max(strictest, value.strictestPerMille());
      }
      return strictest;
    }

    /** 设过管制的挂单类型数（读数/日志用）。 */
    public int kindCount() {
      return enforcementPerKind.size();
    }

    /** 逐类型（保序 = 声明序；读数/日志用；{@link MarketOrderKind#all()} 是唯一词表序）。 */
    public Set<MarketOrderKind> kinds() {
      Set<MarketOrderKind> kinds = new LinkedHashSet<>();
      for (MarketOrderKind kind : MarketOrderKind.all()) {
        if (enforcementPerKind.containsKey(kind)) {
          kinds.add(kind);
        }
      }
      return Collections.unmodifiableSet(kinds);
    }
  }

  /**
   * ★★ <b>返回防御性副本</b>（修 SpotBugs {@code EI_EXPOSE_REP}）：record 的自动访问器会把内部两层表直接交出去。 ★ <b>为什么是逐层拷贝而不是
   * {@code Map.copyOf}</b>：内层表的迭代序是内容的纯函数（本类只用 {@code LinkedHashMap} 冻结）⇒ 逐层 {@code
   * Collections.unmodifiableMap(new LinkedHashMap<>(...))} 保序。
   */
  @Override
  public Map<String, Map<CurrencyId, CurrencyEnforcement>> currencyEnforcementPerMilleByZone() {
    return copyOuter(currencyEnforcementPerMilleByZone);
  }

  /** ★★ 同上（防御性副本；见 {@link #currencyEnforcementPerMilleByZone()} 的注）。 */
  @Override
  public Map<String, Map<CommodityId, Directional>> commodityEnforcementPerMilleByZone() {
    return copyOuter(commodityEnforcementPerMilleByZone);
  }

  private static <K, V> Map<String, Map<K, V>> copyOuter(Map<String, Map<K, V>> outer) {
    LinkedHashMap<String, Map<K, V>> copy = new LinkedHashMap<>();
    for (Map.Entry<String, Map<K, V>> entry : outer.entrySet()) {
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
        freezeCurrencies("currencyEnforcementPerMilleByZone", currencyEnforcementPerMilleByZone);
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
   * ★★ <b>P-T1e：币种表是不是非空</b>（币种挂单闸的<b>唯一</b>早退判据）。
   *
   * <p>★ <b>为什么要与 {@link #isActive()} 分开</b>：挂单闸在撮合最内层逐候选调用（数百万次），只设了<b>商品</b>规则的 世界里它必须是一条 {@code
   * isEmpty} 就返回的路径；用 {@link #isActive()} 当判据会把商品政策也算进来（无害但白跑）。
   */
  public boolean currencyRegimeActive() {
    return !currencyEnforcementPerMilleByZone.isEmpty();
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

  /**
   * ★★ <b>P-T1e：某区某币种<b>某类挂单</b>某方向的实际管制力（‰）</b>；缺区/缺币种/缺类型 ⇒ 0。
   *
   * <p>★ 这是币种挂单闸（{@code MarketSettlement.acceptsCurrency}）与估值减项（标量口径）共用的取数口。
   */
  public long currencyEnforcementPerMille(
      String zoneId, CurrencyId currency, MarketOrderKind orderKind, PortDirection direction) {
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(orderKind, "orderKind");
    Objects.requireNonNull(direction, "direction");
    return currencyEnforcementOf(zoneId, currency).enforcementPerMille(orderKind, direction);
  }

  /**
   * ★★ <b>P-T1e：某区某币种某类挂单某方向的开放度（‰）= 1000 − 管制力</b> —— 挂单闸唯一读它。
   *
   * <p>★ <b>{@code 0} 的语义 = "这一侧完全不给过"</b>（该侧所有接触面的管制度都拉满）：挂单闸只在两侧开放度都 &gt; 0 时放行 （"有一方不给过就不过"，设计书
   * §14.3-2）。★ 部分强度（{@code 0 < E < 1000}）不构成禁令 —— 它是"抓不严"的比例量，
   * 本批的挂单闸是<b>禁入/禁出</b>（布尔），不是节流；强度仍进读数与日志（见账本"关键判断"）。
   */
  public long currencyOpennessPerMille(
      String zoneId, CurrencyId currency, MarketOrderKind orderKind, PortDirection direction) {
    return PER_MILLE - currencyEnforcementPerMille(zoneId, currency, orderKind, direction);
  }

  /**
   * ★★ <b>某区某币种的"标量"管制力（‰）—— 取所有挂单类型 × 两个方向里最严的一侧</b>。
   *
   * <p>★★ <b>这是 P-T1a 的实现口径判断（控制方未钉死，账本 §关键判断记了它） —— P-T1e 起按挂单类型取最严</b>：本标量只服务 {@code
   * CurrencyValuation} 的<b>家户估值减项</b>——那条机制是"这种钱在这个区有多不受待见"的<b>感知量</b>， 不是"过境流量"（后者必须两侧相乘，{@link
   * PortThrottle}），也不是"某类挂单过不过"（那是挂单闸）。任何一种挂单类型被管得严 ⇒ 这种钱就有被抓的风险 ⇒ 取最严。★
   * <b>与改前逐值相同</b>：币种表多出的类型层只是把同一个数拆开，只有一种类型设了规则时 {@code max} 与"就取那一侧"逐值相同；两侧都没设 ⇒ 0 ⇒
   * 逐值退回改前（I-P8）。★ 设计书 §16.4 已判"撤掉异币估值减项" ⇒ 届时本条连同 {@code CurrencyValuation}
   * 的入参一并退役（本批<b>不动</b>它，见账本"偏离记录"）。
   */
  public long currencyEnforcementPerMille(String zoneId, CurrencyId currency) {
    Objects.requireNonNull(currency, "currency");
    return currencyEnforcementOf(zoneId, currency).strictestPerMille();
  }

  /** 设过口岸管制的区数（读数/日志用）。 */
  public int zoneCount() {
    return zoneIds().size();
  }

  /** 设过口岸管制的（区 × 类）条数（读数/日志用；商品 + （币种 × 挂单类型））。 */
  public int classCount() {
    int count = 0;
    for (Map<CurrencyId, CurrencyEnforcement> table : currencyEnforcementPerMilleByZone.values()) {
      for (CurrencyEnforcement entry : table.values()) {
        count += entry.kindCount();
      }
    }
    for (Map<CommodityId, Directional> table : commodityEnforcementPerMilleByZone.values()) {
      count += table.size();
    }
    return count;
  }

  /** 表里全部区键（保序副本；跨切片键口径核对用）。 */
  public Set<String> zoneIds() {
    Set<String> zones = new LinkedHashSet<>(currencyEnforcementPerMilleByZone.keySet());
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

  /** 某区某币种的逐类型管制力；缺区/缺币种 ⇒ {@link CurrencyEnforcement#NONE}（= 全类型全开）。 */
  private CurrencyEnforcement currencyEnforcementOf(String zoneId, CurrencyId currency) {
    if (zoneId == null) {
      return CurrencyEnforcement.NONE;
    }
    Map<CurrencyId, CurrencyEnforcement> inner = currencyEnforcementPerMilleByZone.get(zoneId);
    if (inner == null) {
      return CurrencyEnforcement.NONE;
    }
    CurrencyEnforcement value = inner.get(currency);
    return value == null ? CurrencyEnforcement.NONE : value;
  }

  /** 冻结一层 {@code Map<MarketOrderKind, Directional>}（保序 + 校验；只给 {@link CurrencyEnforcement} 用）。 */
  private static Map<MarketOrderKind, Directional> freezeKinds(
      String field, Map<MarketOrderKind, Directional> table) {
    if (table == null) {
      throw new IllegalArgumentException("PortEnforcementInput." + field + " 不得为 null");
    }
    Map<MarketOrderKind, Directional> copy = new LinkedHashMap<>();
    for (Map.Entry<MarketOrderKind, Directional> entry : table.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("PortEnforcementInput." + field + " 的挂单类型键与值都不得为 null");
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy);
  }

  private static <K> Map<String, Map<K, Directional>> freeze(
      String field, Map<String, Map<K, Directional>> table) {
    if (table == null) {
      return Map.of();
    }
    Map<String, Map<K, Directional>> outer = new LinkedHashMap<>();
    for (Map.Entry<String, Map<K, Directional>> entry : table.entrySet()) {
      requireZoneKey(field, entry.getKey());
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

  /**
   * 冻结币种表（两层：区 → 币种 → 逐类型）；保序 + 校验（区键/币种键/值都不得为 null）。
   *
   * <p>★ <b>为什么不复用上面那一版</b>：值类型不同（{@link CurrencyEnforcement} 而不是 {@link Directional}）——
   * 用泛型把两者塞进一个方法会让"币种表少了一层类型"这种形状错误在编译期查不出来，宁可写两个具体方法。
   */
  private static Map<String, Map<CurrencyId, CurrencyEnforcement>> freezeCurrencies(
      String field, Map<String, Map<CurrencyId, CurrencyEnforcement>> table) {
    if (table == null) {
      return Map.of();
    }
    Map<String, Map<CurrencyId, CurrencyEnforcement>> outer = new LinkedHashMap<>();
    for (Map.Entry<String, Map<CurrencyId, CurrencyEnforcement>> entry : table.entrySet()) {
      requireZoneKey(field, entry.getKey());
      Map<CurrencyId, CurrencyEnforcement> inner = entry.getValue();
      if (inner == null) {
        throw new IllegalArgumentException(
            "PortEnforcementInput." + field + "[" + entry.getKey() + "] 不得为 null");
      }
      Map<CurrencyId, CurrencyEnforcement> copy = new LinkedHashMap<>();
      for (Map.Entry<CurrencyId, CurrencyEnforcement> item : inner.entrySet()) {
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

  private static void requireZoneKey(String field, String zoneId) {
    if (zoneId == null || zoneId.isBlank()) {
      throw new IllegalArgumentException("PortEnforcementInput." + field + " 的区键不得为空白");
    }
  }
}
