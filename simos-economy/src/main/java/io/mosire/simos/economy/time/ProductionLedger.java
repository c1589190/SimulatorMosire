package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.relation.CompensationRule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★★ <b>一天结算里"离开 {@code ClassRow} 的那些发生额"</b>（S1 阶段 4+5 Task 4；spec §四 ①→⑤ 的账）。
 *
 * <p>★★ <b>它为什么必须存在</b>（本阶段最要紧的一件事）：产出<b>不再写进阶层行</b>（R5 ②）—— 它变成 <b>产权条目</b>（{@code actor} 账上的增减）。
 * 这一样<b>不是 economy 切片自己的数据</b>： 产权住在 {@code simos-actor}，而"谁把 {@code ActorEntry}
 * 落到账上"必须由<b>同时看得见两片</b>的 app 协调器来做（R4/E7）。 于是 economy 侧交出的就是它 ——
 * <b>一天一本账</b>，一个字段不少，且<b>绝不静默丢弃</b>。
 *
 * <p>★★ <b>它是"一天"的，不是"一个周期"的</b>（{@link EconomyDayStepper#step(long)} 的返回值）：协调器逐步 把它落到账上 ⇒
 * 若它跨日累计，调用方就会<b>重复落账</b>。需要"整周期"的读数由调用方自己按天攒（这是有意的：" 哪一份累加器"这件事只该有一个主人）。
 *
 * <p>★★ <b>五件</b>（逐件与判据对应；★ H1.3 删掉了原来的第六件 {@code cohortIntake}）：
 *
 * <ul>
 *   <li>{@link #gross()} 本期毛产（逐产业 × 逐商品）—— I4.2 的 {@code ΣOutput}；
 *   <li>{@link #losses()} 本期生产损耗（饲料 0‰ + 折旧 30‰）—— I4.2 的 {@code ΣLoss}； ★ 它<b>不再进 {@code
 *       FlowRow.consumed}</b>：损耗不是"谁消费了"，是"蒸发了"，在守恒式里自成一项（R1）；
 *   <li>{@link #inputs()} 本期现扣周期投入 —— I4.2 的 {@code ΣProductionInputs}。★★ <b>H1
 *       起投入从家户账（会话工作副本）扣</b> （改前从消费行扣）⇒ 它<b>仍然不是一条 {@code ActorEntry}</b>：家户的账在 economy
 *       侧是<b>会话状态</b>（K1）， "扣了多少料"直接写在那份副本上，不经过产权账；
 *   <li>{@link #actorEntries()} 产权条目（毫单位；{@code > 0} 收 / {@code < 0} 付）—— 协调器把它们落到 {@code
 *       ActorData.accounts}。★ 含两族：<b>产出</b>（{@code +净产 → operator}）与 {@link ProductionSettlement}
 *       的<b>转出/收入</b>（{@code −实付} 付方 + {@code +实付} 受方）；★★ <b>H1.3 起受方恒为 actor</b> —— {@code
 *       ToCohort} 的受方是 {@code HouseholdActors.of(cohort)}，与 {@code ToActor} 走同一条条目流 （"cohort
 *       入账"那个中间形态已删）；
 *   <li>{@link #deferredMoney()} 待 S2 的货币规则（I5.3：<b>只定义、不结算</b>，不产生上面任何一样）。
 * </ul>
 *
 * <p>★★ <b>{@link #hasOutput()} 是 fail-closed 的判据</b>（E7/R4）：{@link EconomySettlement#settle} 那类
 * <b>没有产权落账口</b>的入口，一旦某一天交出的账里有产出（毛产或产权条目）就<b>当场抛</b> —— 否则产出会<b>在账上静默消失</b>。 ★ 判据刻意<b>不含 {@code
 * inputs}</b>：投入是<b>家户账侧</b>的完整事件（扣在会话副本里、记在流水里），不经过任何外部账。 ★ <b>H1 起它还多担一层</b>：{@code settle}
 * 连家户账都没有 ⇒ 它在**第一天之前**就已经 fail-closed（见那边的消息），这条判据留着守"全零人口的世界照样不许静默丢产出"。
 *
 * <p>★ <b>三张表都保序不可变</b>：{@code LinkedHashMap} + {@code Collections.unmodifiableMap}，<b>绝不用 {@code
 * Map.copyOf}</b> —— 它的迭代序不是内容的纯函数。
 */
public record ProductionLedger(
    Map<IndustryId, Map<CommodityId, Long>> gross,
    Map<IndustryId, Map<CommodityId, Long>> losses,
    Map<IndustryId, Map<CommodityId, Long>> inputs,
    List<ProductionSettlement.ActorEntry> actorEntries,
    List<CompensationRule> deferredMoney) {

  public ProductionLedger {
    // ★ 缺键按空处理（同 EconomyData 的旧档兼容口径：这里只服务"当天什么都没发生"这一形态）。
    // ★★ 不可变写在**赋值处**（照 Industry.outputPerUnit / ProductionSettlement.Facts 的先例）：SpotBugs 的
    //   EI_EXPOSE_REP 不做跨过程分析，看不出"校验助手返回的是一份不可变副本"。
    gross = Collections.unmodifiableMap(freezeQuantities(gross, "gross"));
    losses = Collections.unmodifiableMap(freezeQuantities(losses, "losses"));
    inputs = Collections.unmodifiableMap(freezeQuantities(inputs, "inputs"));
    actorEntries = actorEntries == null ? List.of() : List.copyOf(actorEntries);
    deferredMoney = deferredMoney == null ? List.of() : List.copyOf(deferredMoney);
  }

  /** 一天什么都没有发生（既没关账、也没有任何条目）。 */
  public static ProductionLedger empty() {
    return new ProductionLedger(Map.of(), Map.of(), Map.of(), List.of(), List.of());
  }

  /**
   * ★★ <b>这一天有没有"产出"</b>（E7/R4 的 fail-closed 判据）：有毛产、或有产权条目。
   *
   * <p>★ 为什么这两样：它们正是<b>离开 {@code ClassRow} 的部分</b> —— 没有产权落账口的入口拿它们<b>无处可放</b>。 ★ 为什么不含 {@link
   * #inputs()}：投入扣在行里、记在流水的 {@code consumed} 里，行侧账是完整的。 ★ 为什么不含 {@link
   * #deferredMoney()}：货币档<b>只定义、不结算</b>（I5.3），它不产生任何数量，丢不了东西。
   */
  public boolean hasOutput() {
    return !gross.isEmpty() || !actorEntries.isEmpty();
  }

  /** 逐产业 × 逐商品的毛产（毫单位）。 */
  public long grossOf(IndustryId industry, CommodityId commodity) {
    return gross.getOrDefault(industry, Map.of()).getOrDefault(commodity, 0L);
  }

  /** 逐产业 × 逐商品的生产损耗（毫单位）。 */
  public long lossOf(IndustryId industry, CommodityId commodity) {
    return losses.getOrDefault(industry, Map.of()).getOrDefault(commodity, 0L);
  }

  /** 逐产业 × 逐商品的现扣投入（毫单位）。 */
  public long inputOf(IndustryId industry, CommodityId commodity) {
    return inputs.getOrDefault(industry, Map.of()).getOrDefault(commodity, 0L);
  }

  /**
   * ★★ <b>一天的可变累加器</b>（包内可见）—— 日结算边跑边记，跑完 {@link #toLedger()} 冻成上面那个 record。
   *
   * <p>★ 形制与 {@code settleOneDay} 里那几张"逐日累加器"同款（{@code consumedGoods} / {@code income}）：<b>可变的那一份
   * 不出包</b>，外部拿到的永远是冻好的值。
   */
  static final class Accumulator {

    private final Map<IndustryId, Map<CommodityId, Long>> gross = new LinkedHashMap<>();
    private final Map<IndustryId, Map<CommodityId, Long>> losses = new LinkedHashMap<>();
    private final Map<IndustryId, Map<CommodityId, Long>> inputs = new LinkedHashMap<>();
    private final List<ProductionSettlement.ActorEntry> actorEntries = new ArrayList<>();
    private final List<CompensationRule> deferredMoney = new ArrayList<>();

    void addGross(IndustryId industry, CommodityId commodity, long amount) {
      addQuantities(gross, industry, commodity, amount);
    }

    void addLoss(IndustryId industry, CommodityId commodity, long amount) {
      addQuantities(losses, industry, commodity, amount);
    }

    void addInput(IndustryId industry, CommodityId commodity, long amount) {
      addQuantities(inputs, industry, commodity, amount);
    }

    /** 追加一条产权条目（{@code delta} 为 0 的条目在此挡掉：0 不是一条发生额）。 */
    void addEntry(ProductionSettlement.ActorEntry entry) {
      if (entry.delta() == 0L) {
        return;
      }
      actorEntries.add(entry);
    }

    /** 一条被推迟的货币规则（I5.3：只定义、不结算）。 */
    void addDeferred(CompensationRule rule) {
      deferredMoney.add(rule);
    }

    ProductionLedger toLedger() {
      return new ProductionLedger(gross, losses, inputs, actorEntries, deferredMoney);
    }

    private static void addQuantities(
        Map<IndustryId, Map<CommodityId, Long>> acc,
        IndustryId industry,
        CommodityId commodity,
        long amount) {
      if (amount == 0L) {
        return;
      }
      acc.computeIfAbsent(industry, key -> new LinkedHashMap<>())
          .merge(commodity, amount, Long::sum);
    }
  }

  // ── 冻结 ───────────────────────────────────────────────────────────────────────────

  /** 逐产业 → 逐商品的表：**两层都保序不可变**（★ 内层也要冻：它会经 {@code grossOf} / {@code gross().get(k)} 逸出）。 */
  private static Map<IndustryId, Map<CommodityId, Long>> freezeQuantities(
      Map<IndustryId, Map<CommodityId, Long>> quantities, String field) {
    if (quantities == null) {
      return Map.of();
    }
    Map<IndustryId, Map<CommodityId, Long>> copy = new LinkedHashMap<>();
    for (Map.Entry<IndustryId, Map<CommodityId, Long>> entry : quantities.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(field + " 的键与值都不得为 null: " + entry.getKey());
      }
      Map<CommodityId, Long> inner = new LinkedHashMap<>(entry.getValue());
      for (Map.Entry<CommodityId, Long> row : inner.entrySet()) {
        if (row.getKey() == null || row.getValue() == null) {
          throw new IllegalArgumentException(field + " 的键与值都不得为 null: " + row.getKey());
        }
      }
      copy.put(entry.getKey(), Collections.unmodifiableMap(inner));
    }
    return copy; // 外层的不可变由**赋值处**加（见紧凑构造器）
  }
}
