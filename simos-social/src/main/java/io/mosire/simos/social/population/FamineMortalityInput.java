package io.mosire.simos.social.population;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★★ <b>W3（2026-10-11）：经济侧交给 Social 的"逐户缺口事实"（Social 的饥荒死亡率输入）</b>。
 *
 * <p>★★ <b>它落在哪里、为什么不落盘</b>：它是 {@code simos-app} 的桥（{@code FamineMortalityBridge}）在<b>每一天</b>从
 * 经济腿的瞬态 outbox（{@code io.mosire.simos.economy.time.EconomyHouseholdGap}）翻出来的<b>入参</b>， 与 {@code
 * CalendarClock} 同制 —— <b>不是</b> {@code SocialData} 的组件，不进 {@code SocialCodec}/变更集/落盘。 ⇒ <b>铁律 5
 * 不触发</b>（没有新增/改动持久形状）：饥荒死亡率走的是既有的 {@code SocialVitalRemainders}（DEATH 那一档的余数累加器），本输入只改<b>当 tick
 * 的那个分子</b>。
 *
 * <p>★★ <b>口径归属（关键分工）</b>：经济侧只给<b>事实</b>（缺口多少、当日需求多少，两个量同窗口），<b>怎么折成死亡率由 Social 自己定</b>（{@link
 * FamineMortality}）。⇒ 经济模块编译期不认识本类型（它只认识自己的 {@code EconomyHouseholdGap}）， Social 也不认识经济模块的实现 ——
 * 两者只在 {@code simos-app}（唯一认识所有模块的地方）交汇。
 *
 * <pre>
 * 每户 facts：
 *   rationGapMilli   = 当日"吃不上"的量（毫粮；≥ 0；0 = 吃上了）
 *   rationNeedMilli  = 当日的粮自然需求（毫粮；≥ 0）—— 与分子同源同窗口 ⇒ 断顿比例自洽
 *   productionCostGapByCommodity = 当日市场轮的"生产花费（内含口粮）未满足量"（逐商品；毫；值 &gt; 0；没开市 ⇒ 空表）
 * </pre>
 *
 * <p>★ <b>为什么把"逐商品生产花费缺口"也带进来</b>（§6.1 W3 行要求"至少报 ① 生产花费未满足量（逐商品）"）：它是 W3 缺口的
 * <b>一般读数</b>与审计面（{@code FAMINE_MORTALITY_HOUSEHOLD} 的 TRACE 逐户明细就打印它）。★ 它<b>不</b>进饥荒死亡率算式， 理由写在
 * {@link FamineMortality} 的类注（前瞻保留 vs 当日实际，量纲差两个数量级）—— 这条口径随时可被控制方推翻，改一处即可。
 *
 * <p>★ <b>缺省中性</b>：{@link #none()}（空表）⇒ 每户 famine 项 = 0 ⇒ <b>行为与 W3 之前逐值相同</b> （"未设新东西 ⇒ 不动作"，I-P8
 * 同族）。★ 输入里没有的家户 = 缺口 0（不猜、不默认成饥荒）。
 *
 * @param shortages 逐户缺口事实（键 = 家户稳定 id；值非 null；不得为 null —— 没有就给空表）
 */
public record FamineMortalityInput(Map<HouseholdId, Shortage> shortages) {

  /**
   * 一户一天的缺口事实（经济腿报什么，这里就装什么；本类型<b>不</b>做任何折算）。
   *
   * @param rationGapMilli 当日口粮缺口（毫粮；≥ 0）
   * @param rationNeedMilli 当日粮自然需求（毫粮；≥ 0）
   * @param productionCostGapByCommodity 当日市场轮的逐商品生产花费未满足量（毫；键值 &gt; 0；没开市 ⇒ 空表）
   */
  public record Shortage(
      long rationGapMilli,
      long rationNeedMilli,
      Map<CommodityId, Long> productionCostGapByCommodity) {

    public Shortage {
      Objects.requireNonNull(
          productionCostGapByCommodity, "productionCostGapByCommodity（没有就传空表，不传 null）");
      if (rationGapMilli < 0L) {
        throw new IllegalArgumentException("Shortage.rationGapMilli 不得为负: " + rationGapMilli);
      }
      if (rationNeedMilli < 0L) {
        throw new IllegalArgumentException("Shortage.rationNeedMilli 不得为负: " + rationNeedMilli);
      }
      LinkedHashMap<CommodityId, Long> frozen = new LinkedHashMap<>();
      for (Map.Entry<CommodityId, Long> entry : productionCostGapByCommodity.entrySet()) {
        CommodityId commodity = Objects.requireNonNull(entry.getKey(), "逐商品缺口不得含 null 商品");
        Long gap = Objects.requireNonNull(entry.getValue(), "逐商品缺口不得含 null 值");
        if (gap <= 0L) {
          throw new IllegalArgumentException(
              "逐商品缺口只记 > 0 的项（0 或缺项 = 那一项不缺）: commodity=" + commodity + " gap=" + gap);
        }
        frozen.put(commodity, gap);
      }
      productionCostGapByCommodity = Collections.unmodifiableMap(frozen);
    }

    /** 这一户今天有没有"吃不上"（饥荒死亡率只认这一支）。 */
    public boolean hasRationGap() {
      return rationGapMilli > 0L;
    }
  }

  public FamineMortalityInput {
    Objects.requireNonNull(shortages, "shortages（没有缺口就给 FamineMortalityInput.none()）");
    LinkedHashMap<HouseholdId, Shortage> frozen = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, Shortage> entry : shortages.entrySet()) {
      HouseholdId household = Objects.requireNonNull(entry.getKey(), "shortages 的键不得为 null");
      Shortage shortage = Objects.requireNonNull(entry.getValue(), "shortages 的值不得为 null");
      frozen.put(household, shortage);
    }
    shortages = Collections.unmodifiableMap(frozen);
  }

  /** ★ <b>缺省中性</b>：一个缺口都没有（没有经济输入 / 赤字为 0 的世界）⇒ 饥荒项恒 0，行为与 W3 之前逐值相同。 */
  public static FamineMortalityInput none() {
    return new FamineMortalityInput(Map.of());
  }

  /** 该户的缺口事实；不在表里 ⇒ {@link Optional#empty()}（= 缺口 0，不猜）。 */
  public Optional<Shortage> forHousehold(HouseholdId householdId) {
    Objects.requireNonNull(householdId, "householdId");
    return Optional.ofNullable(shortages.get(householdId));
  }

  public boolean isEmpty() {
    return shortages.isEmpty();
  }

  /** 有缺口的家户数（= 表大小；表里每一条都是"至少一条腿非 0"）。 */
  public int size() {
    return shortages.size();
  }
}
