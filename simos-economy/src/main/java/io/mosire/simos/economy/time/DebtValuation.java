package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.HouseholdId;
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

/**
 * ★★ <b>P11.1：偿还的纯估值 / 付款选择 helper（D-023）</b>。
 *
 * <p>本类只回答两个问题，<b>不改任何状态、不铸转移、不认识 AccountSession</b>：
 *
 * <ol>
 *   <li><b>估值顺序</b>：先看调用方给的<b>单个家户价目表</b>（{@link HouseholdPriceTable}；当前正式状态还没有这个字段 ⇒
 *       调用方传 {@code null}）；该户没有对应价格时，回落该格<b>市场区默认价目表</b>（{@link Market#prices()}）；
 *   <li><b>付款选择</b>：把债务人手头的<b>任何商品 / 任何币种</b>按上述价目表折成合同计价口径，整数 floor、<b>不做
 *       double</b>，选出一组"能付得最多"的实际介质腿；合同本金为上限，绝不超付。
 * </ol>
 *
 * <p>★★ <b>D-023 的介质自由</b>：合同单位是粮也可以收钱、合同单位是钱也可以收布 —— 不看 {@code DebtUnit} 对介质设限。 ★
 * <b>不做 FX</b>：非本格 {@code numeraire} 的币种没有价格表里的比价 ⇒ 该腿<b>不折算</b>、不进付款腿；若整笔都无可折介质则
 * 返回空计划并带上具名的 {@link PaymentPlan#unpricedAssets()}，由调用方落一条具名 skip（<b>绝不</b>静默付 0）。
 *
 * <p>★★ <b>量纲与整数口径</b>（与 {@link Market#prices()} 一致；1 商品单位 = {@value
 * EconomyVocabulary#MILLI_PER_COMMODITY_UNIT} 毫商品）：
 *
 * <pre>
 * 商品 g（毫商品）按价 p_g / p_c 折成合同商品 c（毫商品）:  ⌊q × p_g ÷ p_c⌋
 * 商品 g（毫商品）折成本格 numeraire 货币            :  ⌊q × p_g ÷ 1000⌋
 * numeraire 货币（毫钱）折成合同商品 c（毫商品）      :  ⌊money × 1000 ÷ p_c⌋
 * 同商品 / 同币种                                  :  1:1（identity，不需要价目表）
 * </pre>
 *
 * <p>★ <b>identity 腿为什么不需要价格</b>：粮还粮、银还银是"原物原还"，不是折算；没有市场的格子里这笔债也必须能还。
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
   * @param legs 有序付款腿（identity 腿在前；逐值 &gt; 0；保序不可变）
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

  /**
   * ★★ <b>给一笔债挑一组"能付得最多"的实际介质腿</b>（纯函数）。
   *
   * <p>可用资产由调用方先扣好冻结 / 一日口粮保留（本类不猜保留政策）。顺序稳定：<b>合同自身介质 identity → 其它商品（id
   * 升序）→ 本格 numeraire 货币</b>；每腿折成合同单位、按剩余本金夹住，整数 floor。无法定价的持有资产进 {@link
   * PaymentPlan#unpricedAssets()}（具名），不会变成 0 付款。
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
    Pricing pricing = resolvePricing(debtor, market, householdPrices);
    List<PaymentLeg> legs = new ArrayList<>();
    LinkedHashSet<String> unpriced = new LinkedHashSet<>();
    long remaining = outstandingPrincipal;
    switch (unit) {
      case DebtUnit.Commodity commodityUnit ->
          remaining =
              chooseForCommodityContract(
                  commodityUnit.commodity(),
                  remaining,
                  availableGoods,
                  availableMoney,
                  pricing,
                  legs,
                  unpriced);
      case DebtUnit.Money moneyUnit ->
          remaining =
              chooseForMoneyContract(
                  moneyUnit.currency(),
                  remaining,
                  availableGoods,
                  availableMoney,
                  pricing,
                  legs,
                  unpriced);
    }
    return new PaymentPlan(legs, outstandingPrincipal - remaining, new ArrayList<>(unpriced));
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

  // ── 合同单位 = 商品 ────────────────────────────────────────────────────────────────────────

  private static long chooseForCommodityContract(
      CommodityId contractCommodity,
      long remaining,
      Map<CommodityId, Long> availableGoods,
      Map<CurrencyId, Long> availableMoney,
      Pricing pricing,
      List<PaymentLeg> legs,
      LinkedHashSet<String> unpriced) {
    long contractPrice = pricing.priceOf(contractCommodity);
    // ① identity：合同商品自己；不需要市场价，也不受"该商品还没有价格"影响。
    long identity = availableGoods.getOrDefault(contractCommodity, 0L);
    if (identity > 0L && remaining > 0L) {
      long pay = Math.min(remaining, identity);
      legs.add(new CommodityLeg(contractCommodity, pay, pay));
      remaining -= pay;
    }
    if (remaining <= 0L) {
      return 0L;
    }
    // ② 其它商品：价格 p_asset / p_contract 折成合同商品。
    for (CommodityId commodity : sortedCommodities(availableGoods.keySet())) {
      if (remaining <= 0L) {
        break;
      }
      if (commodity.equals(contractCommodity)) {
        continue;
      }
      long available = availableGoods.getOrDefault(commodity, 0L);
      if (available <= 0L) {
        continue;
      }
      long assetPrice = pricing.priceOf(commodity);
      if (assetPrice <= 0L || contractPrice <= 0L) {
        unpriced.add("commodity:" + commodity.value());
        continue;
      }
      long maxPayable = convert(available, assetPrice, contractPrice);
      if (maxPayable <= 0L) {
        continue;
      }
      long target = Math.min(remaining, maxPayable);
      long used = maxAssetQuantity(target, assetPrice, contractPrice, available, maxPayable);
      long actual = convert(used, assetPrice, contractPrice);
      if (actual <= 0L) {
        continue;
      }
      legs.add(new CommodityLeg(commodity, used, actual));
      remaining -= actual;
    }
    // ③ 货币：只有本格 numeraire 能折成商品（其它币种没有比价，不做 FX）。
    for (CurrencyId currency : sortedCurrencies(availableMoney.keySet())) {
      if (remaining <= 0L) {
        break;
      }
      long available = availableMoney.getOrDefault(currency, 0L);
      if (available <= 0L) {
        continue;
      }
      boolean convertible =
          pricing.numeraire() != null && currency.equals(pricing.numeraire()) && contractPrice > 0L;
      if (!convertible) {
        unpriced.add("money:" + currency.value());
        continue;
      }
      long maxPayable = convertMoneyToCommodity(available, contractPrice);
      if (maxPayable <= 0L) {
        continue;
      }
      long target = Math.min(remaining, maxPayable);
      long used =
          maxAssetQuantity(
              target,
              EconomyVocabulary.MILLI_PER_COMMODITY_UNIT,
              contractPrice,
              available,
              maxPayable);
      long actual = convertMoneyToCommodity(used, contractPrice);
      if (actual <= 0L) {
        continue;
      }
      legs.add(new MoneyLeg(currency, used, actual));
      remaining -= actual;
    }
    return remaining;
  }

  // ── 合同单位 = 货币 ────────────────────────────────────────────────────────────────────────

  private static long chooseForMoneyContract(
      CurrencyId contractCurrency,
      long remaining,
      Map<CommodityId, Long> availableGoods,
      Map<CurrencyId, Long> availableMoney,
      Pricing pricing,
      List<PaymentLeg> legs,
      LinkedHashSet<String> unpriced) {
    // ① identity：合同币种自己（同币种 1:1，不需要价格）。
    long identity = availableMoney.getOrDefault(contractCurrency, 0L);
    if (identity > 0L && remaining > 0L) {
      long pay = Math.min(remaining, identity);
      legs.add(new MoneyLeg(contractCurrency, pay, pay));
      remaining -= pay;
    }
    if (remaining <= 0L) {
      return 0L;
    }
    boolean contractIsNumeraire =
        pricing.numeraire() != null && contractCurrency.equals(pricing.numeraire());
    // ② 商品：只有合同币种就是本格 numeraire 时，市场价才是它这把尺上的价。
    for (CommodityId commodity : sortedCommodities(availableGoods.keySet())) {
      if (remaining <= 0L) {
        break;
      }
      long available = availableGoods.getOrDefault(commodity, 0L);
      if (available <= 0L) {
        continue;
      }
      long assetPrice = pricing.priceOf(commodity);
      if (!contractIsNumeraire || assetPrice <= 0L) {
        unpriced.add("commodity:" + commodity.value());
        continue;
      }
      long maxPayable = convertCommodityToMoney(available, assetPrice);
      if (maxPayable <= 0L) {
        continue;
      }
      long target = Math.min(remaining, maxPayable);
      long used =
          maxAssetQuantity(
              target,
              assetPrice,
              EconomyVocabulary.MILLI_PER_COMMODITY_UNIT,
              available,
              maxPayable);
      long actual = convertCommodityToMoney(used, assetPrice);
      if (actual <= 0L) {
        continue;
      }
      legs.add(new CommodityLeg(commodity, used, actual));
      remaining -= actual;
    }
    // ③ 其它币种：没有 FX ⇒ 不折、具名（不静默付 0）。
    for (CurrencyId currency : sortedCurrencies(availableMoney.keySet())) {
      if (remaining <= 0L) {
        break;
      }
      if (currency.equals(contractCurrency)) {
        continue;
      }
      if (availableMoney.getOrDefault(currency, 0L) > 0L) {
        unpriced.add("money:" + currency.value());
      }
    }
    return remaining;
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

  /** 毫商品 → 毫 numeraire 货币（⌊q × 商品价 ÷ 1000⌋）。 */
  private static long convertCommodityToMoney(long quantityMilli, long commodityPrice) {
    return convert(quantityMilli, commodityPrice, EconomyVocabulary.MILLI_PER_COMMODITY_UNIT);
  }

  /** 毫 numeraire 货币 → 毫商品（⌊money × 1000 ÷ 商品价⌋）。 */
  private static long convertMoneyToCommodity(long moneyMilli, long commodityPrice) {
    return convert(moneyMilli, EconomyVocabulary.MILLI_PER_COMMODITY_UNIT, commodityPrice);
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
