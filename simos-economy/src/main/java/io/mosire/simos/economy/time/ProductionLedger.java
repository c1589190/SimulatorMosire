package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.TransferId;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ★★ <b>一天结算里"离开 {@code ClassRow} 的那些发生额"</b>（S1 阶段 4+5 Task 4；spec §四 ①→⑤ 的账）。
 *
 * <p>★★ <b>它为什么必须存在</b>（本阶段最要紧的一件事）：产出<b>不再写进阶层行</b>（R5 ②）—— 它变成产权条目/转移（{@code actor}
 * 账上的增减）。这一样<b>不是 economy 切片自己的数据</b>： 产权住在 {@code simos-actor}，而"谁把条目/转移落到账上"必须由<b>同时看得见两片</b>的
 * app 协调器来做（R4/E7）。 于是 economy 侧交出的就是它 —— <b>一天一本账</b>，一个字段不少，且<b>绝不静默丢弃</b>。
 *
 * <p>★★ <b>它是"一天"的，不是"一个周期"的</b>（{@link EconomyDayStepper#step(long)} 的返回值）：协调器逐步 把它落到账上 ⇒
 * 若它跨日累计，调用方就会<b>重复落账</b>。需要"整周期"的读数由调用方自己按天攒。
 *
 * <p>★★ <b>七件</b>（逐件与判据对应；★ H1.3 删掉了原来的第六件 {@code cohortIntake}，★ H2 把 {@code actorEntries} 拆成"产出计提
 * + 转移"并补上"逐规则读数"）：
 *
 * <ul>
 *   <li>{@link #gross()} 本期毛产（逐产业 × 逐商品）—— I4.2 的 {@code ΣOutput}；
 *   <li>{@link #losses()} 本期生产损耗（饲料 0‰ + 折旧 30‰）—— I4.2 的 {@code ΣLoss}； ★ 它<b>不再进 {@code
 *       FlowRow.consumed}</b>：损耗不是"谁消费了"，是"蒸发了"，在守恒式里自成一项（R1）；
 *   <li>{@link #inputs()} 本期现扣周期投入 —— I4.2 的 {@code ΣProductionInputs}。★★ <b>H1
 *       起投入从家户账（会话工作副本）扣</b> ⇒ 它<b>不是一条转移</b>：扣减已经写在副本上，而"扣了多少料"在这里读得出来；
 *   <li>★★ {@link #transfers()} <b>当天的转移</b>（{@link Transfer}；H2 起这是全系统唯一的"东西从 A 到 B"的事实）——
 *       含五族：<b>关系实付</b>（实物与<b>货币</b>两族；{@code operator → 受方}）、<b>投入征调</b>（{@code 供方 → 经营者}）、
 *       <b>同格借粮</b>（家户 → 家户）、<b>同格市场成交</b>（H4：一笔买卖<b>一对</b>转移 —— 货一条、钱一条）。 ★ <b>货币腿逐币种直接可读</b>（H4
 *       的逐币种守恒判据就读它，不另设一张"货币发生额"表：同一件事两处拼写必然漂开）。 ★ 协调器把它们折成 {@code (actor, location, commodity,
 *       delta)} 落到 {@code ActorData.accounts}（见 {@link #transfers()} 的注释）；
 *   <li>{@link #outputAccruals()} <b>产出计提</b>（{@code +净产 → operator}；{@link
 *       ProductionSettlement.ActorEntry}）。 ★ <b>它不是一条转移</b>：产出是<b>造出来</b>的、没有对端，而转移的两端恒为 actor
 *       且不许相等 —— 理由详见 {@link ProductionSettlement.ActorEntry}；
 *   <li>{@link #ruleSettlements()} <b>逐规则的实得读数</b>（应付 / 实付 / 欠；裁定 S4）。★ <b>只读</b>：不影响守恒、不落债权；
 *   <li>{@link #deferredMoney()} ★ <b>留档字段</b>（H4 起<b>恒为空表</b>）：H2/H3 装的是"只定义、不结算"的货币规则； H4
 *       起货币档真的结算（进 {@link #transfers()} 与 {@link #ruleSettlements()}）⇒ 它再没有内容 —— 保留的理由见 {@code
 *       ProductionSettlement.Outcome} 的 {@code deferredMoney} 注释。
 * </ul>
 *
 * <p>★★ <b>{@link #hasOutput()} 是 fail-closed 的判据</b>（E7/R4）：{@link EconomySettlement#settle} 那类
 * <b>没有产权落账口</b>的入口，一旦某一天交出的账里有产出（毛产或产出计提）就<b>当场抛</b> —— 否则产出会<b>在账上静默消失</b>。 ★ 判据刻意<b>不含 {@code
 * inputs}</b>（投入扣在家户账侧、账是完整的）、也<b>不含 {@code transfers}</b>（借粮与取材不是产出； 且那个入口可达的状态里它们恒空 —— 全零人口 ⇒
 * 没有份额、没有缺口、没有关账）。
 *
 * <p>★ <b>三张表都保序不可变</b>：{@code LinkedHashMap} + {@code Collections.unmodifiableMap}，<b>绝不用 {@code
 * Map.copyOf}</b> —— 它的迭代序不是内容的纯函数。
 */
public record ProductionLedger(
    Map<IndustryId, Map<CommodityId, Long>> gross,
    Map<IndustryId, Map<CommodityId, Long>> losses,
    Map<IndustryId, Map<CommodityId, Long>> inputs,
    List<ProductionSettlement.ActorEntry> outputAccruals,
    List<Transfer> transfers,
    List<ProductionSettlement.RuleSettlement> ruleSettlements,
    List<CompensationRule> deferredMoney) {

  public ProductionLedger {
    // ★ 缺键按空处理（同 EconomyData 的旧档兼容口径：这里只服务"当天什么都没发生"这一形态）。
    // ★★ 不可变写在**赋值处**（照 Industry.outputPerUnit / ProductionSettlement.Facts 的先例）：SpotBugs 的
    //   EI_EXPOSE_REP 不做跨过程分析，看不出"校验助手返回的是一份不可变副本"。
    gross = Collections.unmodifiableMap(freezeQuantities(gross, "gross"));
    losses = Collections.unmodifiableMap(freezeQuantities(losses, "losses"));
    inputs = Collections.unmodifiableMap(freezeQuantities(inputs, "inputs"));
    outputAccruals = outputAccruals == null ? List.of() : List.copyOf(outputAccruals);
    transfers = transfers == null ? List.of() : List.copyOf(transfers);
    ruleSettlements = ruleSettlements == null ? List.of() : List.copyOf(ruleSettlements);
    deferredMoney = deferredMoney == null ? List.of() : List.copyOf(deferredMoney);
  }

  /** 一天什么都没有发生（既没关账、也没有任何转移）。 */
  public static ProductionLedger empty() {
    return new ProductionLedger(
        Map.of(), Map.of(), Map.of(), List.of(), List.of(), List.of(), List.of());
  }

  /**
   * ★★ <b>这一天有没有"产出"</b>（E7/R4 的 fail-closed 判据）：有毛产、或有产出计提。
   *
   * <p>★ 为什么这两样：它们正是<b>离开 {@code ClassRow} 的部分</b> —— 没有产权落账口的入口拿它们<b>无处可放</b>。 ★ 为什么不含 {@link
   * #inputs()}：投入扣在家户账（会话副本）里、记在流水的 {@code consumed} 里，账是完整的。 ★ 为什么不含 {@link #deferredMoney()}：H4
   * 起它<b>恒为空</b>（货币档真的结算了），对判据没有影响。 ★ 为什么不含 {@link
   * #transfers()}：借粮与取材<b>不是产出</b>（它们是既有库存的换手）；而本判据服务的入口（多日静态 {@code settle}）
   * 在全零人口之外<b>根本进不来</b>（第一天之前就抛），那个状态里三者恒空。
   */
  public boolean hasOutput() {
    return !gross.isEmpty() || !outputAccruals.isEmpty();
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
   * <p>★★ <b>它还管一件事：转移凭据的铸造</b>（H2）。{@link #mint} 是<b>全系统唯一分配 {@link TransferId} 的地方</b>： id =
   * {@code "tr-<day>-<seq>"}，{@code seq} = <b>当天</b>该账本内第几条（从 1 起）⇒ 同一天同一序列必然给出同一串 id （重放/分支可比）。★
   * 日号由构造器收（调用方知道它推进到了第几天），序号在累加器里自增 —— 两段都只有一处拼写点。
   *
   * <p>★ 形制与 {@code settleOneDay} 里那几张"逐日累加器"同款（{@code consumedGoods} / {@code income}）：<b>可变的那一份
   * 不出包</b>，外部拿到的永远是冻好的值。
   */
  static final class Accumulator implements ProductionSettlement.TransferMint {

    private final long day;
    private final Map<IndustryId, Map<CommodityId, Long>> gross = new LinkedHashMap<>();
    private final Map<IndustryId, Map<CommodityId, Long>> losses = new LinkedHashMap<>();
    private final Map<IndustryId, Map<CommodityId, Long>> inputs = new LinkedHashMap<>();
    private final List<ProductionSettlement.ActorEntry> outputAccruals = new ArrayList<>();
    private final List<Transfer> transfers = new ArrayList<>();
    private final List<ProductionSettlement.RuleSettlement> ruleSettlements = new ArrayList<>();
    private final List<CompensationRule> deferredMoney = new ArrayList<>();

    /** ★ M2.3/M2.4：当天区域市场的只读报告（瞬态；不进 {@link ProductionLedger}，由 stepper 交给 L3 读数）。 */
    private MarketReport marketReport;

    /** 当天已铸的转移条数（{@link TransferId} 的 {@code seq} 段）。 */
    private long transferSequence;

    /**
     * @param day 这一天是第几个世界日（进 {@link Transfer#day()} 与 id 的第二段）
     */
    Accumulator(long day) {
      this.day = day;
    }

    void addGross(IndustryId industry, CommodityId commodity, long amount) {
      addQuantities(gross, industry, commodity, amount);
    }

    void addLoss(IndustryId industry, CommodityId commodity, long amount) {
      addQuantities(losses, industry, commodity, amount);
    }

    void addInput(IndustryId industry, CommodityId commodity, long amount) {
      addQuantities(inputs, industry, commodity, amount);
    }

    /** 追加一条产出计提（{@code delta} 为 0 的条目在此挡掉：0 不是一条发生额）。 */
    void addOutputAccrual(ProductionSettlement.ActorEntry accrual) {
      if (accrual.delta() == 0L) {
        return;
      }
      outputAccruals.add(accrual);
    }

    /**
     * ★★ <b>铸一条转移并记进当天的账</b>（唯一分配点；见类注）。
     *
     * <p>★ {@code settles} 恒空（清偿属 H5）；★ <b>H4 起货币腿真的有钱</b>（货币工资/地租走只带货币腿的转移、 市场成交走一对转移 —— 见 {@code
     * Transfer} 的货币腿口径）。
     */
    @Override
    public Transfer mint(
        ActorRef from,
        ActorRef to,
        HexCoord location,
        Map<CommodityId, Long> goods,
        Map<CurrencyId, Long> money,
        TransferReason reason) {
      Transfer transfer =
          new Transfer(
              new TransferId("tr-" + day + "-" + (++transferSequence)),
              day,
              from,
              to,
              location,
              goods,
              money,
              reason,
              Optional.empty());
      transfers.add(transfer);
      return transfer;
    }

    /** 一条逐规则的实得读数（应付 / 实付 / 欠；★ 只读）。 */
    void addRuleSettlement(ProductionSettlement.RuleSettlement reading) {
      ruleSettlements.add(reading);
    }

    /**
     * 收下一条**被推迟的货币规则**（★ H4 起<b>没有生产调用方</b>：货币档真的结算了 ⇒ 这张表恒空；它只服务旧口径的留痕， 见 {@code
     * ProductionSettlement.Outcome.deferredMoney}）。
     */
    void addDeferred(CompensationRule rule) {
      deferredMoney.add(rule);
    }

    /**
     * ★ 记下当天的区域市场报告（M2.3/M2.4；瞬态，不进落盘的 {@link ProductionLedger}）—— {@code
     * EconomyDayStepper.lastMarketReport()} 读它。
     */
    void recordMarketReport(MarketReport report) {
      this.marketReport = report;
    }

    /** 当天的市场报告（没开市 ⇒ {@code null}）。 */
    MarketReport marketReport() {
      return marketReport;
    }

    ProductionLedger toLedger() {
      return new ProductionLedger(
          gross, losses, inputs, outputAccruals, transfers, ruleSettlements, deferredMoney);
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
