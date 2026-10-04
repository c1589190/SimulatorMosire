package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyCommodities;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * ★★ <b>P11.1 / D-023 / D-030：债务估值的纯 helper（不改状态、不铸转移、不认识 AccountSession）</b>。
 *
 * <p>本类回答两组问题：
 *
 * <ol>
 *   <li><b>估值顺序</b>：先看调用方给的<b>单个家户价目表</b>（{@link HouseholdPriceTable}；当前正式状态没有这个字段 ⇒
 *       调用方传 {@code null}）；该户没有对应价格时，回落该户所在<b>市场区默认价目表</b>（{@link Market#prices()}）；
 *   <li><b>任意 medium ↔ 任意 {@link DebtUnit} 的折算</b>：commodity ↔ commodity、commodity ↔ money、money ↔
 *       money；缺任一侧价格 ⇒ 该 medium 对这条债具名不可折算（{@link PaymentPlan#unpricedAssets()}），调用方据此跳下一条，
 *       <b>不静默当 0</b>。
 * </ol>
 *
 * <p>★★ <b>共同尺度（唯一拼写点）</b>：一个单位的共同价值 {@code unitValue} 定义成
 *
 * <pre>
 * Commodity(c) = price(c)                 // 毫 numeraire / 商品单位（与 Market.prices 同量纲）
 * Money(cur)   = 1000                     // 仅当 cur == 价目表 numeraire；1 商品单位 = 1000 毫商品
 * </pre>
 *
 * <p>于是 {@code q(毫 medium) → debtUnits(毫 debt)} 的整数折算恒为
 * {@code ⌊q × unitValue(medium) ÷ unitValue(debt)⌋}。这条式子同时给出本类类注里那四条既有算式：粮价
 * {@code price(grain)} 作为分母把共同尺度落回“毫粮等值”时，商品债为 {@code principal × price(c) / price(grain)}、
 * 货币债为 {@code principal × 1000 / price(grain)}（D-030 §3.4）。
 *
 * <p>★★ <b>量纲与整数口径</b>（与 {@link Market#prices()} 一致；1 商品单位 = {@value
 * EconomyVocabulary#MILLI_PER_COMMODITY_UNIT} 毫商品）：
 *
 * <pre>
 * 商品 g（毫商品）按 p_g / p_c 折成合同商品 c（毫商品）:  ⌊q × p_g ÷ p_c⌋
 * 商品 g（毫商品）折成本格 numeraire 货币            :  ⌊q × p_g ÷ 1000⌋
 * numeraire 货币（毫钱）折成合同商品 c（毫商品）      :  ⌊money × 1000 ÷ p_c⌋
 * 同商品 / 同币种                                  :  1:1（identity，不需要价目表）
 * </pre>
 *
 * <p>★★ <b>防 1000 倍错（量纲写死）</b>：{@link Market#prices()} 的价格 p 是<b>毫 numeraire / 1 商品单位</b>，而
 * 1 商品单位 = {@value EconomyVocabulary#MILLI_PER_COMMODITY_UNIT} 毫商品；本类的 {@code quantity/amount/principal}
 * 又全是<b>毫</b>单位。所以商品价直接取 {@code price(c)}（不是 {@code price(c)/1000}），货币的“单位价”取 1000
 * —— 两条腿的毫数量乘各自的 unitValue 后才是同一量纲；{@code 商品↔货币} 的 1000 因子正是从
 * “1 商品单位 = 1000 毫商品”来的。
 *
 * <p>★ <b>identity 腿为什么不需要价格</b>：粮还粮、银还银是"原物原还"，不是折算；没有市场的格子里这笔债也必须能还。
 *
 * <p>★★ <b>不做 FX</b>：非本价目表 {@code numeraire} 的币种没有比价 ⇒ 该腿不折算、进 {@link
 * PaymentPlan#unpricedAssets()}；缺价不等于付 0。
 */
public final class DebtValuation {

  private DebtValuation() {}

  /** 无价格资产的具名 skip 原因前缀（后面接 {@code commodity:<id>} / {@code money:<id>} 的稳定清单）。 */
  public static final String UNPRICED_ASSET_REASON_PREFIX = "unpriced-repayment-assets:";

  /**
   * ★★ <b>单个家户的价目表（可选输入口）</b>：D-023 的估值顺序第 1 位；调用方没有这个表 ⇒ 传 {@code null} 走市场默认。
   *
   * <p>{@link #numeraire()} 是该家户价目表的计价货币（必须与价格同量纲）；只给 {@code numeraire} 下的商品价格。
   */
  public interface HouseholdPriceTable {

    /** 家户价目表的计价货币；不得为 null。 */
    CurrencyId numeraire();

    /**
     * 该户对某商品的单价（毫 {@code numeraire} / 商品单位）；{@code <= 0} = <b>本户没有对应价格</b> ⇒ 回落市场默认。
     */
    long priceOf(HouseholdId household, CommodityId commodity);
  }

  /** 从固定表构造一个只读家户价目表（GM / 测试 / 后续家户字段的便捷工厂）。 */
  public static HouseholdPriceTable householdPriceTable(
      CurrencyId numeraire, Map<CommodityId, Long> prices) {
    Objects.requireNonNull(numeraire, "家户价目表 numeraire 不得为 null");
    Objects.requireNonNull(prices, "家户价目表 prices 不得为 null");
    LinkedHashMap<CommodityId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : prices.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("家户价目表的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "家户价目表的单价不得为负（0 = 没有对应价格）: " + entry.getKey() + " = " + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    Map<CommodityId, Long> frozen = Collections.unmodifiableMap(copy);
    return new HouseholdPriceTable() {
      @Override
      public CurrencyId numeraire() {
        return numeraire;
      }

      @Override
      public long priceOf(HouseholdId household, CommodityId commodity) {
        return frozen.getOrDefault(commodity, 0L);
      }
    };
  }

  /**
   * ★★ <b>债权人 → 收款 actor 的唯一解析口</b>：默认 {@link #HOUSEHOLD_ACTORS}（家户 actor）；外部放贷主体 / 政府的账户
   * 只要在 {@code AccountSession}/{@code actor} 账里可收，就可以由调用方换一份 resolver 把债权人 id 指到那个 actor。
   */
  @FunctionalInterface
  public interface RepayeeResolver {

    /** 默认：债权人是家户，收款 actor = {@link HouseholdActors#of(HouseholdId)}。 */
    RepayeeResolver HOUSEHOLD_ACTORS = HouseholdActors::of;

    /**
     * 解析收款 actor；返回 {@code null} = 说不出收款人（调用方必须具名 fail-closed，不许静默吞款）。
     */
    ActorRef resolve(HouseholdId creditor);
  }

  /** 一条实际付款腿：把 {@code quantity/amount} 的真实商品/货币交出去，折成 {@code contractUnits} 合同计价单位。 */
  public sealed interface PaymentLeg permits CommodityLeg, MoneyLeg {

    /** 这条腿折成合同计价单位的数量（= 可减少的本金；毫单位）。恒 &gt; 0。 */
    long contractUnits();
  }

  /** 商品付款腿（{@code quantityMilli} 毫商品 → {@code contractUnits} 合同单位）。 */
  public record CommodityLeg(CommodityId commodity, long quantityMilli, long contractUnits)
      implements PaymentLeg {

    public CommodityLeg {
      Objects.requireNonNull(commodity, "CommodityLeg.commodity 不得为 null");
      if (quantityMilli <= 0L || contractUnits <= 0L) {
        throw new IllegalArgumentException(
            "CommodityLeg 的数量必须 > 0: quantity=" + quantityMilli + " units=" + contractUnits);
      }
    }
  }

  /** 货币付款腿（{@code amountMilli} 毫钱 → {@code contractUnits} 合同单位）。 */
  public record MoneyLeg(CurrencyId currency, long amountMilli, long contractUnits)
      implements PaymentLeg {

    public MoneyLeg {
      Objects.requireNonNull(currency, "MoneyLeg.currency 不得为 null");
      if (amountMilli <= 0L || contractUnits <= 0L) {
        throw new IllegalArgumentException(
            "MoneyLeg 的数量必须 > 0: amount=" + amountMilli + " units=" + contractUnits);
      }
    }
  }

  /**
   * 一次付款选择的纯结果。
   *
   * @param legs 有序付款腿（按调用方给的介质序；逐值 &gt; 0；保序不可变）
   * @param totalContractUnits Σ {@link PaymentLeg#contractUnits()}（= 本次可减少的本金；≤ 传入本金，≤
   *     {@code Long.MAX_VALUE}）
   * @param unpricedAssets 手头持有、但<b>没有价格</b>因而没有折算的资产（稳定序：{@code commodity:…} / {@code money:…}）；
   *     非空 = 调用方必须落具名 skip，不许静默付 0
   */
  public record PaymentPlan(
      List<PaymentLeg> legs, long totalContractUnits, List<String> unpricedAssets) {

    public PaymentPlan {
      Objects.requireNonNull(legs, "PaymentPlan.legs 不得为 null");
      Objects.requireNonNull(unpricedAssets, "PaymentPlan.unpricedAssets 不得为 null");
      if (totalContractUnits < 0L) {
        throw new IllegalArgumentException("PaymentPlan.totalContractUnits 不得为负: " + totalContractUnits);
      }
      List<PaymentLeg> legsCopy = new ArrayList<>(legs.size());
      long sum = 0L;
      for (PaymentLeg leg : legs) {
        PaymentLeg checked = Objects.requireNonNull(leg, "PaymentPlan.legs 不得含 null");
        legsCopy.add(checked);
        sum = Math.addExact(sum, checked.contractUnits());
      }
      if (sum != totalContractUnits) {
        throw new IllegalArgumentException(
            "PaymentPlan.totalContractUnits 必须等于 Σlegs: total="
                + totalContractUnits
                + " sum="
                + sum);
      }
      legs = Collections.unmodifiableList(legsCopy);
      List<String> unpricedCopy = new ArrayList<>(unpricedAssets.size());
      for (String asset : unpricedAssets) {
        if (asset == null || asset.isBlank()) {
          throw new IllegalArgumentException("PaymentPlan.unpricedAssets 不得含 null/空白");
        }
        unpricedCopy.add(asset);
      }
      unpricedAssets = Collections.unmodifiableList(unpricedCopy);
    }

    public boolean isEmpty() {
      return legs.isEmpty();
    }

    /** 商品腿按商品合并（保序不可变）；没有商品腿 ⇒ 空表。 */
    public Map<CommodityId, Long> goodsLegs() {
      LinkedHashMap<CommodityId, Long> goods = new LinkedHashMap<>();
      for (PaymentLeg leg : legs) {
        if (leg instanceof CommodityLeg commodityLeg) {
          goods.merge(commodityLeg.commodity(), commodityLeg.quantityMilli(), Long::sum);
        }
      }
      return Collections.unmodifiableMap(goods);
    }

    /** 货币腿按币种合并（保序不可变）；没有货币腿 ⇒ 空表。 */
    public Map<CurrencyId, Long> moneyLegs() {
      LinkedHashMap<CurrencyId, Long> money = new LinkedHashMap<>();
      for (PaymentLeg leg : legs) {
        if (leg instanceof MoneyLeg moneyLeg) {
          money.merge(moneyLeg.currency(), moneyLeg.amountMilli(), Long::sum);
        }
      }
      return Collections.unmodifiableMap(money);
    }
  }

  // ── 共同尺度的唯一拼写点（D-030 §3.4 / §3.5）──────────────────────────────────────────────

  /**
   * ★★ <b>任意 {@link DebtUnit} 本金 → 毫粮等值</b>（D-030 §3.4，市场默认价目表口径）：
   *
   * <pre>
   * Commodity(c): amount × price(c) ÷ price(grain)
   * Money(cur):   amount × 1000 ÷ price(grain)      // 仅 cur == market.numeraire
   * </pre>
   *
   * <p>返回空 = 不可定价（market 缺、缺 grain 价、缺该单位价）。{@code amount == 0} ⇒ 返回 0（已结清的历史合同不再占
   * unpriced）。本方法是 {@link DebtCapacityBook#marketPriceLookup(Map)} 的唯一算式来源。
   */
  public static OptionalLong grainEquivalentMilli(long amountMilli, DebtUnit unit, Market market) {
    Objects.requireNonNull(unit, "unit 不得为 null");
    if (amountMilli < 0L) {
      throw new IllegalArgumentException("grainEquivalentMilli 的 amount 不得为负: " + amountMilli);
    }
    if (amountMilli == 0L) {
      return OptionalLong.of(0L);
    }
    if (market == null) {
      return OptionalLong.empty();
    }
    Pricing pricing = new Pricing(market.numeraire(), market, null, null);
    long grainPrice = pricing.priceOf(EconomyCommodities.GRAIN);
    long unitValue = commonUnitValue(pricing, unit);
    if (grainPrice <= 0L || unitValue <= 0L) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(convert(amountMilli, unitValue, grainPrice));
  }

  /**
   * ★★ <b>任意 {@link DebtUnit} 本金 → 共同价值</b>（D-030 §3.5 的债务排序用）：价目表按家户表优先、市场默认回退。
   *
   * <p>返回空 = 这条债在该债务人的价目表下不可定价（不可定价的债在 {@code repayDebts} 排最后）。
   */
  public static OptionalLong commonValueMilli(
      long amountMilli,
      DebtUnit unit,
      HouseholdId debtor,
      Market market,
      HouseholdPriceTable householdPrices) {
    Objects.requireNonNull(unit, "unit 不得为 null");
    Objects.requireNonNull(debtor, "debtor 不得为 null");
    if (amountMilli < 0L) {
      throw new IllegalArgumentException("commonValueMilli 的 amount 不得为负: " + amountMilli);
    }
    if (amountMilli == 0L) {
      return OptionalLong.of(0L);
    }
    Pricing pricing = resolvePricing(debtor, market, householdPrices);
    long unitValue = commonUnitValue(pricing, unit);
    if (unitValue <= 0L) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(convert(amountMilli, unitValue, 1L));
  }

  /**
   * 一条单位在已解析价目表下的共同价值（毫 numeraire / 商品单位，或货币的 1000）：<b>不要</b>在别处重写这条 switch。
   */
  private static long commonUnitValue(Pricing pricing, DebtUnit unit) {
    return switch (unit) {
      case DebtUnit.Commodity commodity -> pricing.priceOf(commodity.commodity());
      case DebtUnit.Money money ->
          pricing.numeraire() != null && money.currency().equals(pricing.numeraire())
              ? EconomyVocabulary.MILLI_PER_COMMODITY_UNIT
              : 0L;
    };
  }

  // ── 付款选择 ───────────────────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>按调用方给定的介质序，给一笔债挑一组"能付得最多"的实际介质腿</b>（纯函数；D-030 §3.5 的逐 debt × 逐
   * medium 口径）。
   *
   * <p>介质序由调用方定（生产路径 = 全部货币按余额降序、再全部商品按余额降序）；本方法只按该序逐项尝试：identity
   * 直接 1:1；其它单位用 {@link #commonValueMilli} 同一套价目表折成共同价值，floor、最多还清本金；缺价项进 {@link
   * PaymentPlan#unpricedAssets()} 具名跳过，换下一条 medium。
   *
   * @param outstandingPrincipal 合同未偿本金（合同计价单位；必须 &gt; 0）
   * @param unit 合同计价口径（商品 / 货币）
   * @param debtor 债务人（家户价目表按它取价）
   * @param mediumOrder 介质顺序（不得含 null / 重复单位；本方法不重排）
   * @param availableByUnit 当前可动余额（毫单位；值不得为负；缺键 = 0）
   * @param market 该户所在市场区默认价目表（可为 null = 没有市场默认价目表；identity 腿仍可用）
   * @param householdPrices 单个家户价目表（可为 null；非 null 时优先，缺价回落同 numeraire 的市场默认）
   * @return 付款计划；无任何可折介质时为空计划（{@link PaymentPlan#unpricedAssets()} 具名说明缺价资产）
   */
  public static PaymentPlan choosePayment(
      long outstandingPrincipal,
      DebtUnit unit,
      HouseholdId debtor,
      List<DebtUnit> mediumOrder,
      Map<DebtUnit, Long> availableByUnit,
      Market market,
      HouseholdPriceTable householdPrices) {
    Objects.requireNonNull(unit, "DebtValuation.choosePayment 的 unit 不得为 null");
    Objects.requireNonNull(debtor, "DebtValuation.choosePayment 的 debtor 不得为 null");
    Objects.requireNonNull(mediumOrder, "DebtValuation.choosePayment 的 mediumOrder 不得为 null");
    Objects.requireNonNull(availableByUnit, "DebtValuation.choosePayment 的 availableByUnit 不得为 null");
    if (outstandingPrincipal <= 0L) {
      throw new IllegalArgumentException(
          "DebtValuation.choosePayment 的 outstandingPrincipal 必须 > 0: " + outstandingPrincipal);
    }
    LinkedHashSet<DebtUnit> seenMedia = new LinkedHashSet<>();
    for (DebtUnit mediumUnit : mediumOrder) {
      if (mediumUnit == null) {
        throw new IllegalArgumentException("DebtValuation.choosePayment 的 mediumOrder 不得含 null");
      }
      if (!seenMedia.add(mediumUnit)) {
        throw new IllegalArgumentException(
            "DebtValuation.choosePayment 的 mediumOrder 不得含重复单位: " + mediumUnit.key());
      }
    }
    for (Map.Entry<DebtUnit, Long> entry : availableByUnit.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "DebtValuation.choosePayment 的 availableByUnit 键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "DebtValuation.choosePayment 的 availableByUnit 不得为负: "
                + entry.getKey().key()
                + " = "
                + entry.getValue());
      }
    }
    Pricing pricing = resolvePricing(debtor, market, householdPrices);
    long contractUnitValue = commonUnitValue(pricing, unit);
    List<PaymentLeg> legs = new ArrayList<>();
    LinkedHashSet<String> unpriced = new LinkedHashSet<>();
    long remaining = outstandingPrincipal;
    for (DebtUnit mediumUnit : mediumOrder) {
      if (remaining <= 0L) {
        break;
      }
      long available = availableByUnit.getOrDefault(mediumUnit, 0L);
      if (available <= 0L) {
        continue;
      }
      // ① identity：原物原还，不需要价格（与 D-023/H2 的既有口径一致）。
      if (mediumUnit.equals(unit)) {
        long pay = Math.min(remaining, available);
        legs.add(legOf(mediumUnit, pay, pay));
        remaining -= pay;
        continue;
      }
      // ② 非 identity：先用同一价目表把两边都折成共同价值；任一缺价 ⇒ 具名跳过，不静默付 0。
      long mediumUnitValue = commonUnitValue(pricing, mediumUnit);
      if (contractUnitValue <= 0L || mediumUnitValue <= 0L) {
        unpriced.add(assetKey(mediumUnit));
        continue;
      }
      long maxPayable = convert(available, mediumUnitValue, contractUnitValue);
      if (maxPayable <= 0L) {
        continue;
      }
      long target = Math.min(remaining, maxPayable);
      long used =
          maxAssetQuantity(target, mediumUnitValue, contractUnitValue, available, maxPayable);
      long actual = convert(used, mediumUnitValue, contractUnitValue);
      if (used <= 0L || actual <= 0L) {
        continue;
      }
      legs.add(legOf(mediumUnit, used, actual));
      remaining -= actual;
    }
    return new PaymentPlan(legs, outstandingPrincipal - remaining, new ArrayList<>(unpriced));
  }

  /**
   * ★★ <b>旧 P11.1 入口（兼容保留）</b>：给一笔债挑一组"能付得最多"的实际介质腿。
   *
   * <p>它按旧顺序构造介质表（合同单位 identity → 其它商品 id 升序 → 币种 id 升序）后委托给上面的
   * {@link #choosePayment(long, DebtUnit, HouseholdId, List, Map, Market, HouseholdPriceTable)}，因此旧调用方行为不变；
   * 生产偿还路径使用显式介质序的重载。
   *
   * @param outstandingPrincipal 合同未偿本金（合同计价单位；必须 &gt; 0）
   * @param unit 合同计价口径（商品 / 货币）
   * @param debtor 债务人（家户价目表按它取价）
   * @param availableGoods 已扣除冻结/口粮保留的可动商品（毫商品；值 &gt; 0）
   * @param availableMoney 已扣除冻结的可动货币（毫钱；值 &gt; 0）
   * @param market 该户所在格的市场（可为 null = 没有市场默认价目表；identity 腿仍可用）
   * @param householdPrices 单个家户价目表（可为 null；非 null 时优先，缺价回落同 numeraire 的市场默认）
   * @return 付款计划；无任何可折介质时为空计划（{@link PaymentPlan#unpricedAssets()} 具名说明缺价资产）
   */
  public static PaymentPlan choosePayment(
      long outstandingPrincipal,
      DebtUnit unit,
      HouseholdId debtor,
      Map<CommodityId, Long> availableGoods,
      Map<CurrencyId, Long> availableMoney,
      Market market,
      HouseholdPriceTable householdPrices) {
    Objects.requireNonNull(unit, "DebtValuation.choosePayment 的 unit 不得为 null");
    Objects.requireNonNull(debtor, "DebtValuation.choosePayment 的 debtor 不得为 null");
    Objects.requireNonNull(availableGoods, "DebtValuation.choosePayment 的 availableGoods 不得为 null");
    Objects.requireNonNull(availableMoney, "DebtValuation.choosePayment 的 availableMoney 不得为 null");
    if (outstandingPrincipal <= 0L) {
      throw new IllegalArgumentException(
          "DebtValuation.choosePayment 的 outstandingPrincipal 必须 > 0: " + outstandingPrincipal);
    }
    LinkedHashMap<DebtUnit, Long> availableByUnit = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : availableGoods.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "DebtValuation.choosePayment 的 availableGoods 键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() > 0L) {
        availableByUnit.put(DebtUnit.commodity(entry.getKey()), entry.getValue());
      }
    }
    for (Map.Entry<CurrencyId, Long> entry : availableMoney.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "DebtValuation.choosePayment 的 availableMoney 键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() > 0L) {
        availableByUnit.put(DebtUnit.money(entry.getKey()), entry.getValue());
      }
    }
    return choosePayment(
        outstandingPrincipal,
        unit,
        debtor,
        legacyMediumOrder(unit, availableGoods, availableMoney),
        availableByUnit,
        market,
        householdPrices);
  }

  /** 旧 P11.1 的介质序：identity → 其它商品 id 升序 → 币种 id 升序（唯一的旧顺序拼写点）。 */
  private static List<DebtUnit> legacyMediumOrder(
      DebtUnit contractUnit,
      Map<CommodityId, Long> availableGoods,
      Map<CurrencyId, Long> availableMoney) {
    LinkedHashSet<DebtUnit> order = new LinkedHashSet<>();
    order.add(contractUnit);
    for (CommodityId commodity : sortedCommodities(availableGoods.keySet())) {
      if (contractUnit instanceof DebtUnit.Commodity commodityUnit
          && commodityUnit.commodity().equals(commodity)) {
        continue;
      }
      order.add(DebtUnit.commodity(commodity));
    }
    for (CurrencyId currency : sortedCurrencies(availableMoney.keySet())) {
      if (contractUnit instanceof DebtUnit.Money moneyUnit
          && moneyUnit.currency().equals(currency)) {
        continue;
      }
      order.add(DebtUnit.money(currency));
    }
    return new ArrayList<>(order);
  }

  /** 按介质单位构造一条实际付款腿；调用方保证 quantity/units &gt; 0。 */
  private static PaymentLeg legOf(DebtUnit mediumUnit, long quantityMilli, long contractUnits) {
    return switch (mediumUnit) {
      case DebtUnit.Commodity commodity ->
          new CommodityLeg(commodity.commodity(), quantityMilli, contractUnits);
      case DebtUnit.Money money -> new MoneyLeg(money.currency(), quantityMilli, contractUnits);
    };
  }

  /** 缺价资产的稳定具名键（与现有 {@link #UNPRICED_ASSET_REASON_PREFIX} 的清单格式一致）。 */
  private static String assetKey(DebtUnit unit) {
    return switch (unit) {
      case DebtUnit.Commodity commodity -> "commodity:" + commodity.commodity().value();
      case DebtUnit.Money money -> "money:" + money.currency().value();
    };
  }

  // ── 估值顺序的唯一拼写点 ────────────────────────────────────────────────────────────────────

  /** 已解析的价目表视图：家户表优先、否则市场默认；两者计价货币不同 ⇒ 不混用（缺价 ⇒ 0 = 无价格）。 */
  private record Pricing(
      CurrencyId numeraire, Market market, HouseholdPriceTable householdPrices, HouseholdId household) {

    long priceOf(CommodityId commodity) {
      if (householdPrices != null) {
        long price = householdPrices.priceOf(household, commodity);
        if (price > 0L) {
          return price;
        }
      }
      if (market == null) {
        return 0L;
      }
      if (numeraire != null && !numeraire.equals(market.numeraire())) {
        return 0L; // 两个价目表计价货币不同：缺价时不能用另一把尺静默折算
      }
      return market.priceOf(commodity);
    }
  }

  private static Pricing resolvePricing(
      HouseholdId debtor, Market market, HouseholdPriceTable householdPrices) {
    CurrencyId numeraire = market == null ? null : market.numeraire();
    if (householdPrices != null) {
      numeraire =
          Objects.requireNonNull(
              householdPrices.numeraire(), "HouseholdPriceTable.numeraire 不得为 null");
    }
    return new Pricing(numeraire, market, householdPrices, debtor);
  }

  // ── 整数换算（全部 floor；绝不用 double）────────────────────────────────────────────────────

  /** {@code ⌊quantity × numerator ÷ denominator⌋}；任何非正输入（或分母为 0）⇒ 0。 */
  private static long convert(long quantity, long numerator, long denominator) {
    if (quantity <= 0L || numerator <= 0L || denominator <= 0L) {
      return 0L;
    }
    try {
      return Math.multiplyExact(quantity, numerator) / denominator;
    } catch (ArithmeticException overflow) {
      throw new IllegalStateException(
          "偿还估值整数溢出的具名失败（拒绝回退到 double）: quantity="
              + quantity
              + " numerator="
              + numerator
              + " denominator="
              + denominator,
          overflow);
    }
  }

  /**
   * 在 {@code convert(q) = ⌊q × numerator ÷ denominator⌋ ≤ targetUnits} 下取最大的 {@code q ≤
   * available}。
   *
   * <p>★ 这不是"(target × denominator ÷ numerator) 再 floor"的近似：那个写法在整数网格上会少用 1 毫资产（如
   * target=10、num=3、den=2 时只取 6 而正解是 7）。正解：{@code q ≤ ⌊(((target+1) × den − 1)) ÷ num⌋}。
   */
  private static long maxAssetQuantity(
      long targetUnits, long numerator, long denominator, long available, long maxPayable) {
    if (available <= 0L) {
      return 0L;
    }
    if (targetUnits >= maxPayable) {
      return available;
    }
    long bound;
    try {
      bound =
          Math.subtractExact(
                  Math.multiplyExact(Math.addExact(targetUnits, 1L), denominator), 1L)
              / numerator;
    } catch (ArithmeticException overflow) {
      throw new IllegalStateException(
          "偿还估值整数溢出的具名失败（拒绝回退到 double）: target="
              + targetUnits
              + " numerator="
              + numerator
              + " denominator="
              + denominator,
          overflow);
    }
    return Math.max(0L, Math.min(available, bound));
  }

  // ── 稳定序遍历 ────────────────────────────────────────────────────────────────────────────

  private static List<CommodityId> sortedCommodities(Collection<CommodityId> keys) {
    List<CommodityId> sorted = new ArrayList<>(keys);
    sorted.sort(Comparator.comparing(CommodityId::value));
    return sorted;
  }

  private static List<CurrencyId> sortedCurrencies(Collection<CurrencyId> keys) {
    List<CurrencyId> sorted = new ArrayList<>(keys);
    sorted.sort(Comparator.comparing(CurrencyId::value));
    return sorted;
  }
}
