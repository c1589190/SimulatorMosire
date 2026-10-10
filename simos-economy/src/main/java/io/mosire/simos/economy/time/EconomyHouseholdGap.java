package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ★★★ <b>W3（2026-10-11）：经济腿的一户一天的"缺口事实"（瞬态 outbox 条目）</b>——{@link EconomySettlement} 在当日
 * <b>全部救济通道走完之后</b>（市场 → 借粮 → 还债）追加一条，由 {@code simos-app} 的 {@code
 * PopulationEconomyTimeParticipant} 在 {@code stepper.step(day)} 之后取走、翻译成 Social 的饥荒输入。
 *
 * <p>★★ <b>它是什么、不是什么</b>（约束设计书 §6.1 W3 行 / §2 的 S-11）：
 *
 * <pre>
 * ① productionCostGapByCommodity = 生产花费（内含口粮）的未满足量，逐商品
 *      来源 = 当日市场轮的 MarketReport.unmetProductionCostByHousehold()（W2 的读口，本类不重算）
 *      没有开市 ⇒ 空表（不是"缺口为 0"：那一天根本没有市场这件事）
 * ② rationGapMilli / rationNeedMilli = 口粮缺口那一支（家户当日"吃不上"的量与其分母）
 *      来源 = 当日消费步的 unmetNeed[grain] 与当日注入的粮自然需求
 * ③ household = 家户稳定身份（铁律 1：地址是定位方式、ID 是身份）；population = 经济行当日人口（核对用）
 * </pre>
 *
 * <p>★★ <b>为什么它是瞬态而不是持久组件</b>（与 P0 的 {@link EconomyPopulationTransfer} 同制）：人口/死亡率的权威在
 * Social，经济侧只是把"这一天我看见的逐户缺口"报出去；本条目不新增 {@code EconomyData} 组件、不进 {@code EconomyCodec}/变更集。★
 * 逐日重建（每条都在当天的 {@code settleOneDayInto} 里现算，会话随本次推进丢弃）⇒ <b>不引入跨日/跨 advance 的瞬态记忆</b>，"0→360 一次 / 3
 * 段 / 逐 tick" 三份 dump 仍逐字节相同（B2 分段等价的硬前提）。
 *
 * <p>★ <b>量纲</b>：毫商品（与 {@code BuyerOutcome} 同口径）；<b>不跨商品相加成"一个数"</b> —— 需要单一标量（如满足率）的
 * 调用方必须自己说清怎么加权（与"禁跨币相加"同一条纪律）。{@code productionCostGapByCommodity} 里没有的商品 = 那一项不缺。
 *
 * @param household 家户稳定 id；非 null
 * @param population 该户当日经济行人口（人；≥ 0）—— 只作核对/日志事实，本类不据此判任何事
 * @param rationGapMilli 口粮缺口（毫粮；≥ 0；0 = 当日吃上了）
 * @param rationNeedMilli 当日的粮自然需求（毫粮；≥ 0）—— 与分子同源同窗口，Social 据此自己算"断顿比例"
 * @param productionCostGapByCommodity 当日市场轮里该户逐商品的"生产花费未满足量"（毫；值 &gt; 0；没开市 ⇒ 空表）
 */
public record EconomyHouseholdGap(
    HouseholdId household,
    long population,
    long rationGapMilli,
    long rationNeedMilli,
    Map<CommodityId, Long> productionCostGapByCommodity) {

  public EconomyHouseholdGap {
    Objects.requireNonNull(household, "household（铁律 1：缺口必须挂在稳定家户身份上）");
    Objects.requireNonNull(
        productionCostGapByCommodity, "productionCostGapByCommodity（没有就传空表，不传 null）");
    if (population < 0L) {
      throw new IllegalArgumentException("EconomyHouseholdGap.population 不得为负: " + population);
    }
    if (rationGapMilli < 0L) {
      throw new IllegalArgumentException(
          "EconomyHouseholdGap.rationGapMilli 不得为负: " + rationGapMilli);
    }
    if (rationNeedMilli < 0L) {
      throw new IllegalArgumentException(
          "EconomyHouseholdGap.rationNeedMilli 不得为负: " + rationNeedMilli);
    }
    LinkedHashMap<CommodityId, Long> frozenCost = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : productionCostGapByCommodity.entrySet()) {
      CommodityId commodity = Objects.requireNonNull(entry.getKey(), "逐商品缺口不得含 null 商品");
      Long gap = Objects.requireNonNull(entry.getValue(), "逐商品缺口不得含 null 值");
      if (gap <= 0L) {
        throw new IllegalArgumentException(
            "逐商品缺口只记 > 0 的项（0 或缺项 = 那一项不缺）: commodity=" + commodity + " gap=" + gap);
      }
      frozenCost.put(commodity, gap);
    }
    productionCostGapByCommodity = Collections.unmodifiableMap(frozenCost);
  }

  /**
   * 当日有没有"吃不上"（口粮缺口那一支 &gt; 0）。
   *
   * <p>★ Social 的饥荒死亡率只认这一支（口径在 Social）：{@code productionCostGapByCommodity} 里的口粮维是 {@code
   * naturalNeeds × 35 天} 的<b>前瞻保留</b>（W2 的 {@code MARKET_LIFE_RESERVE_DAYS}），拿它当"今天吃不上"的比例会得到 恒接近
   * 1000‰ 的比值 —— 两条腿的分工见 {@code simos-app} 的 {@code FamineMortalityBridge}。
   */
  public boolean hasRationGap() {
    return rationGapMilli > 0L;
  }

  /** 当日市场轮里有没有逐商品的生产花费缺口（没开市 或 全买到 ⇒ false）。 */
  public boolean hasProductionCostGap() {
    return !productionCostGapByCommodity.isEmpty();
  }

  /**
   * ★ 拼一条 TRACE 日志用的可读摘要（不含任何非 ASCII 之外的处理；只为"现场判读"）。
   *
   * <p>放在这里而不是各调用点：经济腿与 app 桥要打印同一份形状，两处各拼一次就会漂开。
   */
  public String shortText() {
    return "household="
        + household.value()
        + " population="
        + population
        + " rationGapMilli="
        + rationGapMilli
        + " rationNeedMilli="
        + rationNeedMilli
        + " productionCostGap="
        + productionCostGapByCommodity;
  }
}
