package io.mosire.simos.economy.time;

import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>M-A2：一轮的运力报价表（逐轮瞬态，不进 {@code EconomyData}、不进变更集、不落盘）</b>。
 *
 * <p>★★ <b>与 FX 民间簿同形</b>（K-6 的口径："提供者自报价，与 FX 民间簿的'自报价'同形"）：跑商家户在每轮市场装配时
 * <b>重新挂一次</b>自己的限价，跨轮不保留（{@code FxSettlement} 的民间簿同样"订单逐轮瞬态、不存任何跨轮状态"）；
 * 家户的"运力单"不是状态，而是"该户本轮成本与规模"的<b>现算视图</b>。这与运力本身"不可储存、不可转卖"（H-E）一致： 一张跨轮还活着的运力报价单会让"运力"变成可囤积的资产。
 *
 * <p>★★ <b>两种形态（本批的缺省中性所在）</b>：
 *
 * <pre>
 * {@link #empty()}               无报价 ⇒ 缺省口径：提供者按 {@link CapacityQuote#defaultQuotedPerMilleOf}
 *                                （= M-A1 的派生承运成本，**不加上门附加费**），分配序仍按 M-A1 的市场议价权。
 *                                ⇒ 逐值等于 M-A1（I-C2：缺省语义中性，不是兼容位）
 * {@link #selfQuoted()}          生产路径：跑商家户<b>逐户自报价</b>（成本 × 规模）+ 具名固定"上门"附加费 ⇒ 成市：
 *                                分配序 = 限价升序（价格优先）→ 同价按 M-A1 的 canonical 序（议价权 → 家户 id）。
 * </pre>
 *
 * <p>★ <b>为什么"无报价"由调用方给定而不是从状态里读</b>：本批不新增持久状态（运力单簿属另一批），报价逐轮瞬态装配； 夹具/纯状态读者（迁移前瞻、{@code
 * hasCapacityAt(EconomyData, hex)}）拿不到本轮成本与规模的现算视图 ⇒ 传 {@link #empty()}，于是那些调用点逐值退回 M-A1 口径。
 *
 * <p>★ 保序（I7）：表 = {@code LinkedHashMap} 的不可变副本，禁 {@code Map.copyOf}。
 */
public final class CapacityQuoteBook {

  /** 无报价（缺省口径）；全局共享的不可变空表。 */
  private static final CapacityQuoteBook EMPTY =
      new CapacityQuoteBook(Collections.emptyMap(), false);

  /** 家户 → 它挂出的限价（‰）。键序 = 挂单序（本批由装配方决定；读取一律按键查，不依赖迭代序）。 */
  private final Map<HouseholdId, Long> postedPerMille;

  /** 是否成市（报价口径）。{@code false} = 无报价 ⇒ 缺省口径。 */
  private final boolean priced;

  private CapacityQuoteBook(Map<HouseholdId, Long> postedPerMille, boolean priced) {
    this.postedPerMille = postedPerMille;
    this.priced = priced;
  }

  /** ★★ <b>无报价</b>（缺省口径 ⇒ M-A1 逐值不变）。 */
  public static CapacityQuoteBook empty() {
    return EMPTY;
  }

  /**
   * ★★ <b>生产路径：跑商家户逐户按其成本与规模自报价</b>（无逐户覆写）。
   *
   * <p>每户的限价 = {@link CapacityQuote#selfQuotedPerMilleOf}（成本维 × 规模维）。
   */
  public static CapacityQuoteBook selfQuoted() {
    return selfQuoted(Map.of());
  }

  /**
   * ★★ <b>带逐户运力单的报价表</b>：{@code postedPerMille} 里的家户 = <b>挂了运力单</b>（用它的限价）； 不在表里的提供者 = <b>未挂运力单</b>
   * ⇒ 取具名缺省 {@link CapacityQuote#defaultQuotedPerMilleOf}。
   *
   * @param postedPerMille 家户 → 限价（‰）；值域 ≥ 0（负值 ⇒ 具名抛）
   */
  public static CapacityQuoteBook selfQuoted(Map<HouseholdId, Long> postedPerMille) {
    Objects.requireNonNull(postedPerMille, "postedPerMille");
    if (postedPerMille.isEmpty()) {
      return new CapacityQuoteBook(Collections.emptyMap(), true);
    }
    LinkedHashMap<HouseholdId, Long> frozen = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, Long> entry : postedPerMille.entrySet()) {
      HouseholdId household = Objects.requireNonNull(entry.getKey(), "报价表的家户不得为 null");
      Long ask = Objects.requireNonNull(entry.getValue(), "报价表的限价不得为 null");
      if (ask < 0L) {
        throw new IllegalArgumentException("运力限价不得为负: 家户=" + household + " 限价=" + ask);
      }
      frozen.put(household, ask);
    }
    return new CapacityQuoteBook(Collections.unmodifiableMap(frozen), true);
  }

  /** 本轮是否成市（有报价口径）。{@code false} = 无报价 ⇒ 缺省口径（M-A1 逐值不变）。 */
  public boolean isPriced() {
    return priced;
  }

  /** 挂了运力单的家户数（逐户覆写条数；{@code selfQuoted()} 下为 0 —— 全部走具名缺省算式）。 */
  public int postedCount() {
    return postedPerMille.size();
  }

  /** 该家户这一轮挂了运力单吗。 */
  public boolean hasPosted(HouseholdId household) {
    return postedPerMille.containsKey(household);
  }

  /**
   * ★★ <b>该提供者本轮的成交限价（‰）</b>——唯一取价口。
   *
   * <pre>
   * 无报价（{@code empty()}）⇒ 具名缺省限价（= M-A1 派生承运成本，**不加**上门附加费）
   * 有报价（成市）      ⇒ （挂了单 ? 它的限价 : 具名缺省限价）+ 具名固定上门附加费（深度 1，只加一次）
   * </pre>
   *
   * <p>★ 为什么无报价那一支<b>不加</b>上门附加费：缺省口径下这不是"买运力"（没有市场、没有报价），而是 M-A1 的 按议价权分摊 —— 加一笔新费用会让"无报价 ⇒
   * 逐值不变"当场不成立。上门附加费属于"运力服务成市后含上门"这一口径。
   */
  public long askPerMilleOf(MerchantCapacity capacity) {
    Objects.requireNonNull(capacity, "capacity");
    if (!priced) {
      return CapacityQuote.defaultQuotedPerMilleOf(capacity.tier());
    }
    Long posted = postedPerMille.get(capacity.household());
    long quoted = posted != null ? posted : CapacityQuote.defaultQuotedPerMilleOf(capacity.tier());
    return CapacityQuote.withGetReadyPerMille(quoted);
  }
}
