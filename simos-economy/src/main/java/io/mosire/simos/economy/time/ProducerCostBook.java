package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionProcess;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * ★★ <b>S3：生产者单位成本估计（唯一拼写点）</b>—— 市场按 {@code unitCostEstimate + freightPerUnit}
 * 决定"同商品、同区/邻区里谁先被选中"，成交价仍按参考价/买卖限价口径，成本<b>不改变成交单价</b>。
 *
 * <p>★★ <b>公式（计划 §S3.1 逐字）</b>：
 *
 * <pre>
 * unitCostEstimate = Σ inputPerUnit[c] × referencePrice(market, c)
 *                  + laborPerUnit × subsistenceWagePerLaborMilli
 *                  + assetRentPerUnit(industry, relation)
 * </pre>
 *
 * <p>★★ <b>量纲（一处容易读错的地方，写清楚）</b>：本类把结果统一成 <b>千分之一毫银 / 单位规模</b>（即计划里 {@code unitCostEstimate} 的整数放大
 * 1000 倍）。理由是 {@code laborPerUnit}（千分劳动/规模）与给养（毫粮/1000 千分劳动）相乘后按毫银口径会落到 1 以下、被整数除法截成 0 —— 那会让"劳动密集型
 * vs 原料密集型"的排序失去判别力 （不是精度选择，是判别力问题）。放大只影响读数刻度，不影响排序：所有卖方共用同一个刻度。
 *
 * <p>★★ <b>缺价按 0 但读数标 {@link Estimate#missingPrices()}</b>：市场没有给某个投入商品定价时，公式里那一项按 0 计
 * （不抛，否则旧档/小夹具根本开不了市），但估计结果必须带上"哪一个商品缺价" —— <b>不得假装便宜</b>：读数是给竞争
 * 归因用的，缺价卖方在报表里要能被一眼认出来，而不是被当成"成本很低所以卖得快"。
 *
 * <p>★★ <b>地租/工具租</b>：从 {@link ProductionRules#rules()} 里取 {@link RuleType#FIXED_IN_KIND_RENT} /
 * {@link RuleType#FIXED_MONEY_RENT}，按参考价折成同一刻度后除以 {@code capacityScaleOf(industry)}（满规模）。没有关系表 /
 * 没有租规则 ⇒ 0（不是"必有的成本"）。
 *
 * <p>★ <b>纯函数</b>：不读状态、不写状态、无缓存；同一 (industry, market, relation) 必然给同一结果。 任何新排序/tie-break
 * 都是内容的纯函数（计划 §S3.1 的硬要求）。
 */
public final class ProducerCostBook {

  /**
   * ★ 估计值的整数刻度：{@code 1 单位 = 千分之一毫银 / 单位规模}。
   *
   * <p>★ 为什么是 1000：见类注的量纲说明。它只出现一次，比较与读写都读这一个常量。
   */
  public static final long ESTIMATE_SCALE = 1_000L;

  /** 没有显式租规则时的资产租（同一刻度）。★ 不是"必有成本"，显式常量是为了让"没有"与"忘了写"可分辨。 */
  public static final long DEFAULT_ASSET_RENT_PER_UNIT = 0L;

  private ProducerCostBook() {}

  /**
   * 一个产业的单位成本估计（只读；{@link #missingPrices()} 非空 = 有投入商品缺参考价，读数必须原样带出）。
   *
   * @param unitCostEstimateMilli ★ 单位成本（**千分之一毫银 / 单位规模**；见类注的量纲）：输入成本 + 劳动成本 + 资产租
   * @param inputCostMilli 投入成本那一项（同刻度）
   * @param laborCostMilli 劳动成本那一项（同刻度；来自 {@link RegimeRelations#subsistenceMilliPerLabor()}）
   * @param assetRentMilli 资产租那一项（同刻度；来自关系表里的固定租规则）
   * @param missingPrices 缺参考价的投入商品（保序、去重；空表 = 所有投入都有价）
   * @param laborWagePriceMissing 劳动成本折算所需的粮价缺失（无粮价的市场上劳动一项只能按 0 计入，并标记）
   */
  public record Estimate(
      long unitCostEstimateMilli,
      long inputCostMilli,
      long laborCostMilli,
      long assetRentMilli,
      List<CommodityId> missingPrices,
      boolean laborWagePriceMissing,
      boolean recipeMissing) {

    public Estimate {
      if (unitCostEstimateMilli < 0L
          || inputCostMilli < 0L
          || laborCostMilli < 0L
          || assetRentMilli < 0L) {
        throw new IllegalArgumentException(
            "ProducerCostBook.Estimate 的各项成本不得为负: "
                + unitCostEstimateMilli
                + "/"
                + inputCostMilli
                + "/"
                + laborCostMilli
                + "/"
                + assetRentMilli);
      }
      missingPrices =
          Collections.unmodifiableList(
              missingPrices == null ? List.of() : new ArrayList<>(missingPrices));
    }

    /** 有任何一项缺价（投入或劳动折算）⇒ 读数必须标 {@code PRICE_MISSING}。 */
    public boolean priceMissing() {
      return !missingPrices.isEmpty() || laborWagePriceMissing;
    }

    /** 找不到经营这个卖方的产业配方 ⇒ 成本<b>未知</b>（排序时排在已知成本之后，不假装便宜）。 */
    public boolean costKnown() {
      return !recipeMissing;
    }

    /** 无配方卖方的占位估计（各项 0 + {@code recipeMissing=true}；不是"成本为零"）。 */
    public static Estimate unknown() {
      return new Estimate(0L, 0L, 0L, 0L, List.of(), false, true);
    }
  }

  /**
   * 按计划公式算单位成本估计。{@code market} 提供参考价（{@code prices}）；{@code market == null} ⇒ 所有投入都缺价（逐项按
   * 0、标记缺失），不抛 —— 读口要能对"这一格没有市场表"如实给估计。
   *
   * @param unit 生产单元（可用资产从 OwnershipStake 派生）；不得为 null
   * @param industry 产业模板（配方）；不得为 null
   * @param assetShares 实物总账（产能规模的来源）；不得为 null
   * @param market 本格市场（参考价唯一真值源）；可为 null（= 没有价表）
   * @param relation 生产关系（固定租规则的来源）；可为 null
   */
  public static Estimate estimate(
      ProductionProcess unit,
      Industry industry,
      Map<AssetShareId, OwnershipStake> assetShares,
      Market market,
      ProductionRules relation) {
    if (unit == null) {
      throw new IllegalArgumentException("ProducerCostBook.estimate 的 unit 不得为 null");
    }
    if (industry == null) {
      throw new IllegalArgumentException("ProducerCostBook.estimate 的 industry 不得为 null");
    }
    if (assetShares == null) {
      throw new IllegalArgumentException("ProducerCostBook.estimate 的 assetShares 不得为 null");
    }
    return estimate(
        unit,
        industry,
        market,
        relation,
        () -> EconomySettlement.capacityScaleOf(unit, industry, assetShares));
  }

  /**
   * ★★ <b>索引口径的单位成本估计</b>（R4-B.3a-perf）：产能规模从日结算入口的 {@link SettlementIndex} 查，不再逐 unit 扫 {@code
   * OwnershipStake}。除“规模从哪里来”以外，公式、量纲、缺价读数与旧签名逐字相同。
   */
  public static Estimate estimate(
      ProductionProcess unit,
      Industry industry,
      SettlementIndex index,
      Market market,
      ProductionRules relation) {
    if (index == null) {
      throw new IllegalArgumentException("ProducerCostBook.estimate 的 index 不得为 null");
    }
    return estimate(
        unit,
        industry,
        market,
        relation,
        () -> EconomySettlement.capacityScaleOf(unit, industry, index));
  }

  /** 单位成本估计的唯一算式：{@code capacityScale} 作为延迟取值在**与旧实现相同的位置**调用，故异常/取整顺序都保持不变。 */
  private static Estimate estimate(
      ProductionProcess unit,
      Industry industry,
      Market market,
      ProductionRules relation,
      LongSupplier capacityScale) {
    if (unit == null) {
      throw new IllegalArgumentException("ProducerCostBook.estimate 的 unit 不得为 null");
    }
    if (industry == null) {
      throw new IllegalArgumentException("ProducerCostBook.estimate 的 industry 不得为 null");
    }
    List<CommodityId> missing = new ArrayList<>();
    long inputCost = 0L;
    for (Map.Entry<CommodityId, Long> entry : industry.inputPerUnit().entrySet()) {
      CommodityId commodity = entry.getKey();
      long quantity = entry.getValue();
      if (quantity <= 0L) {
        continue;
      }
      long price = priceOf(market, commodity);
      if (price <= 0L) {
        if (!missing.contains(commodity)) {
          missing.add(commodity);
        }
        continue; // ★ 缺价按 0 计（不抛），但 missing 里留下证据
      }
      // 量纲换算：quantity（毫商品/规模）× price（毫银/商品单位）= 千分之一毫银/规模。
      inputCost = Math.addExact(inputCost, Math.multiplyExact(quantity, price));
    }
    long grainPrice = priceOf(market, EconomySettlement.GRAIN);
    boolean grainMissing = grainPrice <= 0L;
    long laborCost = 0L;
    if (industry.laborPerUnit() > 0L) {
      // 毫粮/规模 = laborPerUnit（千分劳动/规模） × 给养（毫粮/1000 千分劳动） ÷ 1000；
      // 折毫银 = 毫粮 × 粮价（毫银/商品单位） ÷ 1000；两步合一：× 给养 × 粮价 ÷ 1,000,000。
      long wageMilliGrain =
          Math.multiplyExact(industry.laborPerUnit(), RegimeRelations.subsistenceMilliPerLabor());
      if (!grainMissing) {
        long laborMilliMoney = wageMilliGrain / 1_000L; // 毫粮/规模
        // 毫银/规模 × 1000（本类刻度）= 毫粮/规模 × 粮价（毫银/商品单位）
        laborCost = Math.multiplyExact(laborMilliMoney, grainPrice);
      }
    }
    long assetRent = assetRentPerUnit(industry, market, relation, capacityScale.getAsLong());
    long total = Math.addExact(Math.addExact(inputCost, laborCost), assetRent);
    return new Estimate(total, inputCost, laborCost, assetRent, missing, grainMissing, false);
  }

  /**
   * 资产租（同一刻度）：关系表里 {@code FIXED_IN_KIND_RENT} / {@code FIXED_MONEY_RENT} 的每周期固定额，
   * 按参考价折成毫银后除以满规模（{@link EconomySettlement#capacityScaleOf(Industry)}；规模 0 ⇒ 不摊）。
   *
   * <p>★ 只读规则类型 + {@code fixedAmount}；不解释规则公式（那是 {@code ProductionSettlement} 的职责）。
   */
  public static long assetRentPerUnit(
      Industry industry, Market market, ProductionRules relation, long capacityScale) {
    if (relation == null || relation.rules().isEmpty()) {
      return DEFAULT_ASSET_RENT_PER_UNIT;
    }
    long perCycle = 0L;
    for (CompensationRule rule : relation.rules()) {
      if (rule.type() == RuleType.FIXED_IN_KIND_RENT) {
        Optional<CommodityId> commodity = rule.commodity();
        if (commodity.isEmpty() || rule.fixedAmount() <= 0L) {
          continue;
        }
        long price = priceOf(market, commodity.get());
        if (price <= 0L) {
          continue; // 缺价按 0 计入租（读数侧由 Estimate 的 missingPrices 承接不了租缺价，见类注）
        }
        perCycle = Math.addExact(perCycle, Math.multiplyExact(rule.fixedAmount(), price));
      } else if (rule.type() == RuleType.FIXED_MONEY_RENT) {
        // fixedAmount 是毫银/周期 ⇒ × 1000 换到本类刻度。
        perCycle = Math.addExact(perCycle, Math.multiplyExact(rule.fixedAmount(), ESTIMATE_SCALE));
      }
    }
    if (perCycle <= 0L) {
      return 0L;
    }
    long scale = Math.max(1L, capacityScale);
    return perCycle / scale;
  }

  /**
   * 单位的到货成本（同一刻度）：单位成本估计 + 单位运费 × {@link #ESTIMATE_SCALE}（运费按毫银/商品单位给， 本类刻度是千分之一毫银/单位规模 ⇒ 乘 1000
   * 后可直接相加）。
   */
  public static long landedCostMilli(long unitCostEstimateMilli, long freightPerUnitMilli) {
    return Math.addExact(
        unitCostEstimateMilli,
        Math.multiplyExact(Math.max(0L, freightPerUnitMilli), ESTIMATE_SCALE));
  }

  /**
   * ★★ 同成本层的 canonical tie-break：{@code (hex, actor)} 稳定升序（计划 §S3.1）。
   *
   * <p>★ 格式只在这里拼一次；比较时按整个串字典序，因此 1/4/8 线程与不同遍历顺序给出同一个次序。
   */
  public static String canonicalKey(HexCoord hex, ActorRef actor) {
    if (hex == null || actor == null) {
      throw new IllegalArgumentException("ProducerCostBook.canonicalKey 的 hex / actor 不得为 null");
    }
    return hex.q() + "_" + hex.r() + "|" + actor.kind().name() + "|" + actor.id();
  }

  private static long priceOf(Market market, CommodityId commodity) {
    return market == null ? 0L : market.priceOf(commodity);
  }
}
