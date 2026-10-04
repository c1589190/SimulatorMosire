package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.api.relation.SubsistenceObligation;
import io.mosire.simos.economy.api.relation.Weight;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassPosition;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.MerchantFirm;
import io.mosire.simos.economy.model.MerchantPolicy;
import io.mosire.simos.economy.model.ProductionMode;
import io.mosire.simos.economy.model.ProductionOrganization;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>D-024 / 2026-10-06 设计 §3.2：单个（家户 × 候选生产方式 × 市场）的预期净收益/劳动</b>（纯函数；只读入参，不写状态）。
 *
 * <p>★★ <b>它替代的旧口径</b>：候选门槛不再是"这个 mode/hex 有没有既存组织/真实利润读数"，而是按
 * 产业配方 + 市场价（bid/ask）+ 可寻址需求 + 家户资产/劳动 + 关系/地租规则现算。旧 {@link OrganizationProfitBook}
 * 退位为事后对账（本类不读它）。
 *
 * <p>★★ <b>量纲（一次写清，实现里不再猜）</b>：
 *
 * <ul>
 *   <li>{@code plannedOutput/selfConsumed/sellable/inputNeed} 一律<b>毫商品</b>；{@code outputPerUnit} 是"商品单位/规模"⇒
 *       × scale × {@link EconomyVocabulary#MILLI_PER_COMMODITY_UNIT}；{@code inputPerUnit} 已经是"毫商品/规模/周期"；
 *   <li>{@code Market.priceOf} 是"毫计价货币 / 1 商品单位"⇒ 数量（毫商品） × 价格 ÷ 1000 = 毫钱；
 *   <li>{@code laborPerUnit} 是"千分劳动 / 日 / 规模"⇒ <b>周期劳动</b>必须 × {@code horizonDays}（
 *       {@link Industry#cycleDays()}）；{@code laborPerUnit × scale} 是每日劳动，不是周期劳动；
 *   <li>劳动成本折算：{@code laborNeed（千分劳动·日） × subsistence（毫粮/1000 千分劳动） ÷ 1000 = 毫粮}，
 *       再 × 粮价（毫钱/商品单位） ÷ 1000 = 毫钱（两步合一：÷ 1_000_000，内部用 {@link BigInteger} 防截断/溢出）；
 *   <li>{@code netPerLaborScaled = floor(net × 1_000_000 / max(1, laborNeed))}（负数也向下取整；同
 *       {@link OrganizationProfitBook#PER_LABOR_SCALE} 的百万分之一刻度）。
 * </ul>
 *
 * <p>★★ <b>与设计文档 §3.2.1 的一处量纲更正（如实记）</b>：文档写
 * {@code laborScale = row.participationAdjustedLaborMilli() / (laborPerUnit × horizonDays)}。核对量纲：行劳动是"千分劳动/日"、
 * {@code laborPerUnit} 是"千分劳动/日/规模" ⇒ 分子必须先 × {@code horizonDays} 才是视界内的劳动，两边同除 horizonDays 后为
 * {@code row / laborPerUnit}。按文档字面（分子不乘）会把日劳动当周期劳动，正是文档 §3.2.3 明令禁止的混用，且真档
 * {@code laborPerUnit=143/1000}、{@code horizonDays=120} 时 scale 恒 0（候选永远不可行）。故实现取等价、量纲正确的
 * {@code row.participationAdjustedLaborMilli() / laborPerUnit}。
 *
 * <p>★★ <b>失败语义</b>：没有产业/资产/雇主/运力 ⇒ {@code feasible=false} 且理由具名（{@code NO_*}）；没有市场/没有价格/没有需求 ⇒
 * {@code feasible=true} 但 {@code sellable=0}、{@code reason} 具名（不抛、不静默当高利润）。数据坏（缺 row/结构/位置）仍
 * fail-closed 为具名不可行，不抛异常——本类是"候选评估器"，一个不可行候选不该让整轮关账失败。
 *
 * <p>★★ <b>claimed 集合的唯一来源（D-024 修复 1/1b，2026-10-06）</b>：判"闲置份额"所需的
 * {@code claimedByOrganizations} <b>由调用方用当天工作副本 {@code organizations}/{@code units}/{@code assetShares}
 * 算出后传入</b>（{@code ModeMigrationPolicy.plan} 已经这么做：
 * {@code claimedAssetShares(organizations, units, assetShares)}）；本类<b>不再</b>从
 * {@code base.productionOrganizations()} 重算——周期关账时 {@code base} 是本周期开始时的 revision，重算会把所有
 * {@code owner==operator} 的份额（含 ESTATE 名下全部土地）误判成"闲置可租"。claimed 含两格：组织
 * {@code assetSources} 引用 + 与在产 unit 同产业同 operator 且 {@code quantity>0} 的份额（seeder 直接建立的
 * ESTATE 主 unit 没有组织行，必须靠 unit 格才不漏）。闲置判据仍只经
 * {@link ModeMigrationPolicy#isIdleShare(AssetShare, Set)} 这一处谓词，本类不新增第二套判据。
 *
 * <p>★★ <b>雇主读数的来源（D-024 修复 1b）</b>：{@code employerOf} 只从当天工作副本 {@code units} 按"同格 + 同产业 +
 * operator != 本户"找雇主，规模 = {@code ProductionUnitBook.capacityScaleOf}；不再读
 * {@code base.productionOrganizations()} 的 {@code organization.modeId}（那是周期开始时的过期快照）。
 *
 * <p>★★ <b>仍保留的具名近似（如实记）</b>：merchant 分支的商号/组织查找仍读 {@code base.merchantFirms()} 与
 * {@code base.productionOrganizations()}（签名拿不到当天工作副本 organizations）；当天自动组织阶段刚新建的组织可能不在
 * base 里，会让 merchant 候选的"现有商号"侧略偏乐观；真正落地由 {@code ModeMigrationPolicy} 的预留账本与
 * {@code ModeMigrationSettlement} 的逐笔拆份额复核（计划可新建 ⇔ 执行可拆到）。merchant 分支的"路线需求"用
 * {@link MarketDemandBook.Book#externalDemandTotal}/{@code unfilledBuyerTotal}（Book 里保留的读数），因为签名同样拿不到
 * 原始 {@code MarketReport} 列表。
 *
 * <p>★ <b>确定性</b>：全部遍历按 id / (q,r) 规范序；无随机、无时钟、无 UUID；同输入同输出。
 */
public final class ExpectedProfitBook {

  private ExpectedProfitBook() {}

  /** 单位劳动净收益的高精度刻度（与 {@link OrganizationProfitBook#PER_LABOR_SCALE} 同一把尺）。 */
  public static final long PER_LABOR_SCALE = OrganizationProfitBook.PER_LABOR_SCALE;

  /** merchant 分支没有产业模板时的视界兜底（天）：本轮真实世界产业周期都是 120。 */
  public static final long DEFAULT_MERCHANT_CYCLE_DAYS = 120L;

  /** 没有 MerchantFirm 时从闲置 CATTLE/SHIP 资产推导 tier 的具名默认值（最低档，避免高估运价）。 */
  public static final MerchantPolicy.MerchantTier DEFAULT_MERCHANT_TIER =
      MerchantPolicy.MerchantTier.PORTER;

  /** 一个（家户 × 候选 mode × hex）的预期读数（不可变；数量单位见类注）。 */
  public record Prospect(
      HouseholdId household,
      ProductionModeId modeId,
      ClassPositionId positionId,
      HexCoord hex,
      Optional<IndustryId> industryId,
      long feasibleScale,
      Map<CommodityId, Long> plannedOutputMilli,
      Map<CommodityId, Long> selfConsumedMilli,
      Map<CommodityId, Long> sellableMilli,
      long revenueMilli,
      long inputCostMilli,
      long laborCostMilli,
      long rentMilli,
      long netMilli,
      long laborNeedMilli,
      long netPerLaborScaled,
      boolean demandCapped,
      boolean feasible,
      String reason) {

    public Prospect {
      Objects.requireNonNull(household, "Prospect.household 不得为 null");
      Objects.requireNonNull(modeId, "Prospect.modeId 不得为 null");
      Objects.requireNonNull(hex, "Prospect.hex 不得为 null");
      Objects.requireNonNull(industryId, "Prospect.industryId 不得为 null（没有请用 Optional.empty()）");
      Objects.requireNonNull(reason, "Prospect.reason 不得为 null（没有就给空串）");
      if (feasible && positionId == null) {
        throw new IllegalArgumentException("feasible Prospect 必须有 positionId");
      }
      if (feasibleScale < 0L || laborNeedMilli < 0L) {
        throw new IllegalArgumentException(
            "Prospect.feasibleScale/laborNeedMilli 不得为负: " + feasibleScale + "/" + laborNeedMilli);
      }
      plannedOutputMilli =
          Collections.unmodifiableMap(copyNonNegative(plannedOutputMilli, "plannedOutputMilli"));
      selfConsumedMilli =
          Collections.unmodifiableMap(copyNonNegative(selfConsumedMilli, "selfConsumedMilli"));
      sellableMilli =
          Collections.unmodifiableMap(copyNonNegative(sellableMilli, "sellableMilli"));
    }
  }

  // ── 唯一公开入口 ───────────────────────────────────────────────────────────────────────────

  /**
   * 现算一个家户在（mode, hex, industry, market）下的预期净收益/劳动。
   *
   * <p>★ 目标位置由 mode 的阶层结构 + 家户当前位置推（与 {@code ModeMigrationSettlement.pickTargetPosition} 同一口径：
   * 先同 relationToMeans/surplusRole、再 id 升序的第一个可生产位置）——本类不另立一套"选位置"规则。
   *
   * @param base 结算前状态（只读：modes/classStructures/classPositions/classStandings/industries/merchantFirms）
   * @param household 被评估的家户
   * @param modeId 候选生产方式
   * @param hex 候选格
   * @param industryOrNull 该格的产业模板；{@code null} ⇒ 按 mode 的默认 regime 在该格挑（挑不到 ⇒ NO_INDUSTRY）
   * @param marketOrNull 该格所在市场的价表；{@code null} ⇒ 无价（收入只算自给折算，标 NO_MARKET）
   * @param demand 需求簿（可寻址需求上限）
   * @param shares 实物资产份额（候选：自有自营 + 同格可租闲置）
   * @param claimedByOrganizations 已被组织引用、或在产 unit 占用的资产份额 id 集合；<b>必须由调用方用当天工作副本
   *     {@code organizations}/{@code units}/{@code assetShares} 算出</b>
   *     （{@link ModeMigrationPolicy#claimedAssetShares(Map, Map, Map)}），本类不从 {@code base} 重算（过期快照会让已
   *     使用份额被误判成闲置）；闲置判据只经 {@link ModeMigrationPolicy#isIdleShare(AssetShare, Set)}
   * @param accounts 账户会话（只读；本类当前不读它，保留入参以承接后续流动性/工资口径）
   * @param units 生产单元表（找现有雇主/现有规模）
   * @param relations 生产关系表（显式关系优先；缺则制度默认）
   * @param topology merchant lane 查询；可为 null（退化单格世界）
   * @param day 当前世界日（merchant 分支的读数；不参与产出/成本公式）
   */
  public static Prospect prospect(
      EconomyData base,
      HouseholdId household,
      ProductionModeId modeId,
      HexCoord hex,
      Industry industryOrNull,
      Market marketOrNull,
      MarketDemandBook.Book demand,
      Map<AssetShareId, AssetShare> shares,
      Set<AssetShareId> claimedByOrganizations,
      AccountSession accounts,
      Map<ProductionUnitId, ProductionUnit> units,
      Map<ProductionUnitId, ProductionRelation> relations,
      MarketTopology topology,
      long day) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(modeId, "modeId");
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(demand, "demand");
    Objects.requireNonNull(shares, "shares");
    Objects.requireNonNull(
        claimedByOrganizations,
        "claimedByOrganizations（必须由调用方用当天工作副本 organizations 算出，不得传 null）");
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(relations, "relations");
    if (day < 0L) {
      throw new IllegalArgumentException("ExpectedProfitBook.prospect 的 day 不得为负: " + day);
    }
    ProductionMode mode = base.modes().get(modeId);
    if (mode == null) {
      return infeasible(household, modeId, null, hex, null, "NO_MODE");
    }
    ClassStructure structure = base.classStructures().get(mode.classStructureId());
    if (structure == null) {
      return infeasible(household, modeId, null, hex, null, "NO_STRUCTURE");
    }
    List<ClassPosition> producing = producingPositions(structure);
    if (producing.isEmpty()) {
      return infeasible(household, modeId, null, hex, null, "NO_POSITION");
    }
    // ★ 当前户在本 mode 的位置若是纯收租/被供养位置（landlord / destitute），不进入生产候选（E2 的 shouldProduce 同口径）：
    //   它没有"生产净收益"可算（租金收入本批不建模）⇒ 具名不可行、net=0；A 规则不会被它触发。
    ClassPosition currentPosition = currentPositionOf(base, household);
    if (currentPosition != null
        && modeId.equals(currentPosition.modeId())
        && (currentPosition.laborRole() == ClassPosition.LaborRole.NONE
            || currentPosition.surplusRole() == ClassPosition.SurplusRole.DEPENDENT)) {
      return infeasible(household, modeId, currentPosition.id(), hex, null, "DEPENDENT");
    }
    ClassPosition position = choosePosition(base, household, modeId, producing);
    ClassRow row = base.classes().get(household);
    if (row == null || row.population() <= 0L) {
      return infeasible(household, modeId, position.id(), hex, null, "NO_ROW");
    }
    if (DefaultProductionModes.DISPLACED.equals(modeId)) {
      // ★★ D-023 #6：流民没有工作、不得从 DISPLACED 池主动招募 —— 预期收益里也不得假装它有一份雇主工资。
      return infeasible(household, modeId, position.id(), hex, null, "DISPLACED_NO_WORK");
    }
    Industry industry =
        industryOrNull != null ? industryOrNull : resolveIndustry(base, hex, modeId);
    if (DefaultProductionModes.MERCHANT.equals(modeId)) {
      return merchantProspect(
          base, household, row, modeId, position, hex, industry, marketOrNull, demand, shares,
          claimedByOrganizations, units, relations, topology, day);
    }
    if (industry == null) {
      return infeasible(household, modeId, position.id(), hex, null, "NO_INDUSTRY");
    }
    if (isWagePosition(position)) {
      return wageProspect(
          household, row, modeId, position, hex, industry, marketOrNull, shares, units, relations);
    }
    return productionProspect(
        household, row, modeId, position, hex, industry, marketOrNull, demand, shares,
        claimedByOrganizations, units, relations);
  }

  // ── 生产位置（OWNER / SURPLUS_RECEIVER / TENANCY）─────────────────────────────────────────

  private static Prospect productionProspect(
      HouseholdId household,
      ClassRow row,
      ProductionModeId modeId,
      ClassPosition position,
      HexCoord hex,
      Industry industry,
      Market market,
      MarketDemandBook.Book demand,
      Map<AssetShareId, AssetShare> shares,
      Set<AssetShareId> claimedByOrganizations,
      Map<ProductionUnitId, ProductionUnit> units,
      Map<ProductionUnitId, ProductionRelation> relations) {
    long horizonDays = industry.cycleDays();
    long laborPerUnit = industry.recipe().laborPerUnit();
    ActorRef operator = HouseholdActors.of(household);
    ProductionUnitId unitId = existingUnitId(industry, operator, units);
    ProductionUnit existingUnit = units.get(unitId);
    long assetScale;
    if (existingUnit != null) {
      // 当前在产：用这个 unit 的真实可用资产（含租佃而来的 operator==组织者份额）——与旧 A 规则同一口径。
      assetScale = ProductionUnitBook.capacityScaleOf(existingUnit, industry, shares);
    } else {
      // 候选：自有自营 + 同格可租闲置（闲置判据的唯一拼写点在 ModeMigrationPolicy）。
      // ★★ D-024 修复 1：claimed 由调用方从当天工作副本 organizations 算出并传入，本类不再从 base 重算。
      assetScale = assetScaleOf(industry, household, shares, claimedByOrganizations);
    }
    long laborScale =
        laborPerUnit <= 0L
            ? Long.MAX_VALUE
            : row.participationAdjustedLaborMilli() / laborPerUnit;
    if (assetScale < 1L) {
      return infeasible(
          household, modeId, position.id(), hex, industry.id(), "NO_ASSET:" + industry.id().value());
    }
    if (laborScale < 1L) {
      return infeasible(
          household, modeId, position.id(), hex, industry.id(), "NO_LABOR:" + household.value());
    }
    long scale = Math.min(assetScale, laborScale);
    if (scale < 1L) {
      return infeasible(household, modeId, position.id(), hex, industry.id(), "SCALE_ZERO");
    }

    LinkedHashMap<CommodityId, Long> plannedOutput = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> output : industry.recipe().outputPerUnit().entrySet()) {
      if (output.getValue() <= 0L) {
        continue;
      }
      long plannedMilli =
          Math.multiplyExact(
              Math.multiplyExact(output.getValue(), scale),
              EconomyVocabulary.MILLI_PER_COMMODITY_UNIT);
      plannedOutput.put(output.getKey(), plannedMilli);
    }
    LinkedHashMap<CommodityId, Long> selfConsumed = new LinkedHashMap<>();
    LinkedHashMap<CommodityId, Long> sellable = new LinkedHashMap<>();
    boolean priceMissing = false;
    long remainingTotal = 0L;
    long sellableTotal = 0L;
    for (Map.Entry<CommodityId, Long> output : plannedOutput.entrySet()) {
      CommodityId commodity = output.getKey();
      long planned = output.getValue();
      long need =
          Math.multiplyExact(
              row.naturalNeeds().getOrDefault(commodity, 0L), horizonDays);
      long self = Math.min(planned, need);
      long remaining = Math.max(0L, planned - self);
      long addressable = demand.addressable(hex, commodity);
      long sell = Math.min(remaining, Math.max(0L, addressable - self));
      if (self > 0L) {
        selfConsumed.put(commodity, self);
      }
      if (sell > 0L) {
        sellable.put(commodity, sell);
      }
      remainingTotal = Math.addExact(remainingTotal, remaining);
      sellableTotal = Math.addExact(sellableTotal, sell);
      if (sell + self > 0L && (market == null || !market.hasPrice(commodity))) {
        priceMissing = true;
      }
    }

    long revenue = 0L;
    Map<CommodityId, Long> revenueByCommodity = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> output : plannedOutput.entrySet()) {
      CommodityId commodity = output.getKey();
      long quantity =
          Math.addExact(
              selfConsumed.getOrDefault(commodity, 0L), sellable.getOrDefault(commodity, 0L));
      if (quantity <= 0L) {
        continue;
      }
      if (market == null || !market.hasPrice(commodity)) {
        continue; // 从未定价 ⇒ 这一项按 0 计，priceMissing 已记
      }
      long price = market.bidPriceOf(commodity);
      if (price <= 0L) {
        continue; // 明确 0 价（免费）⇒ 收入为 0；仍然"有价"，不算缺价
      }
      long money =
          Math.multiplyExact(quantity, price) / EconomyVocabulary.MILLI_PER_COMMODITY_UNIT;
      revenue = Math.addExact(revenue, money);
      revenueByCommodity.merge(commodity, money, Math::addExact);
    }

    long inputCost = 0L;
    boolean inputPriceMissing = false;
    Map<CommodityId, Long> inputCostByCommodity = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> input : industry.recipe().inputPerUnit().entrySet()) {
      if (input.getValue() <= 0L) {
        continue;
      }
      long need = Math.multiplyExact(input.getValue(), scale);
      if (market == null || !market.hasPrice(input.getKey())) {
        inputPriceMissing = true;
        continue; // 从未定价的投入 ⇒ 不硬折
      }
      long price = market.askPriceOf(input.getKey());
      if (price <= 0L) {
        continue; // 明确 0 价投入 ⇒ 免费，成本 0；仍然"有价"
      }
      long money =
          Math.multiplyExact(need, price) / EconomyVocabulary.MILLI_PER_COMMODITY_UNIT;
      inputCost = Math.addExact(inputCost, money);
      inputCostByCommodity.merge(input.getKey(), money, Math::addExact);
    }

    long laborNeed =
        laborPerUnit <= 0L ? 0L : Math.multiplyExact(Math.multiplyExact(laborPerUnit, scale), horizonDays);
    long grainPrice = market == null ? 0L : market.priceOf(EconomySettlement.GRAIN);
    boolean grainPriceMissing =
        laborNeed > 0L && (market == null || !market.hasPrice(EconomySettlement.GRAIN));
    long subsistenceLaborCost =
        grainPriceMissing
            ? 0L
            : laborCostMilli(laborNeed, RegimeRelations.subsistenceMilliPerLabor(), grainPrice);

    ProductionRelation relation =
        relationFor(relations, unitId, modeId, industry, row, operator);
    Obligations obligations =
        obligationsOf(
            relation,
            household,
            row,
            hex,
            market,
            revenueByCommodity,
            inputCostByCommodity,
            revenue,
            inputCost,
            laborNeed,
            false);
    long laborCost = Math.addExact(subsistenceLaborCost, obligations.laborWageMilli());
    long rent = obligations.rentMilli();
    long net =
        Math.subtractExact(
            Math.subtractExact(Math.subtractExact(revenue, inputCost), laborCost), rent);
    boolean demandCapped = demandCapped(remainingTotal, sellableTotal);
    String reason =
        joinReasons(
            obligations.notes(),
            priceMissing ? List.of("NO_PRICE") : List.of(),
            inputPriceMissing ? List.of("NO_INPUT_PRICE") : List.of(),
            grainPriceMissing ? List.of("NO_GRAIN_PRICE") : List.of(),
            market == null ? List.of("NO_MARKET") : List.of(),
            demandCapped ? List.of("DEMAND_CAPPED") : List.of());
    return new Prospect(
        household,
        modeId,
        position.id(),
        hex,
        Optional.of(industry.id()),
        scale,
        plannedOutput,
        selfConsumed,
        sellable,
        revenue,
        inputCost,
        laborCost,
        rent,
        net,
        laborNeed,
        scaledNetPerLabor(net, laborNeed),
        demandCapped,
        true,
        reason);
  }

  // ── 工资位置（WAGE_EARNER / DIRECT_LABORER / PROVIDER）──────────────────────────────────────

  private static Prospect wageProspect(
      HouseholdId household,
      ClassRow row,
      ProductionModeId modeId,
      ClassPosition position,
      HexCoord hex,
      Industry industry,
      Market market,
      Map<AssetShareId, AssetShare> shares,
      Map<ProductionUnitId, ProductionUnit> units,
      Map<ProductionUnitId, ProductionRelation> relations) {
    long horizonDays = industry.cycleDays();
    long laborPerUnit = industry.recipe().laborPerUnit();
    Employer employer = employerOf(modeId, industry, hex, household, units, shares);
    if (employer == null || employer.scale() < 1L) {
      return infeasible(
          household, modeId, position.id(), hex, industry.id(), "NO_EMPLOYER:" + industry.id().value());
    }
    long laborScale =
        laborPerUnit <= 0L
            ? Long.MAX_VALUE
            : row.participationAdjustedLaborMilli() / laborPerUnit;
    if (laborScale < 1L) {
      return infeasible(
          household, modeId, position.id(), hex, industry.id(), "NO_LABOR:" + household.value());
    }
    long scale = Math.min(employer.scale(), laborScale);
    if (scale < 1L) {
      return infeasible(household, modeId, position.id(), hex, industry.id(), "SCALE_ZERO");
    }
    long laborNeed =
        laborPerUnit <= 0L
            ? 0L
            : Math.multiplyExact(Math.multiplyExact(laborPerUnit, scale), horizonDays);
    ProductionRelation relation =
        relationFor(
            relations, employer.unitId(), modeId, industry, row, employer.operator());
    long income = 0L;
    boolean priceMissing = false;
    List<String> notes = new ArrayList<>();
    if (relation == null) {
      notes.add("NO_RELATION");
    } else {
      for (CompensationRule rule : relation.rules()) {
        if (!isWorkerRecipient(rule.recipient(), household, row, hex)) {
          continue;
        }
        switch (rule.type()) {
          case FIXED_MONEY_WAGE -> income = Math.addExact(income, rule.fixedAmount());
          case FIXED_IN_KIND_PER_LABOR -> {
            CommodityId commodity = rule.commodity().orElse(null);
            long price = market == null || commodity == null ? 0L : market.bidPriceOf(commodity);
            if (price <= 0L) {
              priceMissing = true;
              continue;
            }
            long due = SubsistenceObligation.perLaborDue(laborNeed, rule.fixedAmount());
            income =
                Math.addExact(
                    income,
                    Math.multiplyExact(due, price)
                        / EconomyVocabulary.MILLI_PER_COMMODITY_UNIT);
          }
          case OUTPUT_SHARE -> {
            // ★ 这是具名近似：分账池要"本受方劳动 ÷ Σ劳动"，而本计算器拿不到该 unit 的全部劳动
            //   ⇒ 按"这份产出全归本受方"的上界计入，并标 WAGE_SHARE_ESTIMATE（不静默）。
            long employerOutputValue = grossOutputValue(industry, employer.scale(), market);
            long share =
                Math.multiplyExact(employerOutputValue, rule.ratePerMille()) / 1000L;
            income = Math.addExact(income, share);
            notes.add("WAGE_SHARE_ESTIMATE");
          }
          default -> {
            // 其余规则（租金/自留）不是劳动者的收入来源，跳过。
          }
        }
      }
      if (income == 0L) {
        notes.add("NO_WAGE_RULE");
      }
    }
    if (priceMissing) {
      notes.add("NO_PRICE");
    }
    return new Prospect(
        household,
        modeId,
        position.id(),
        hex,
        Optional.of(industry.id()),
        scale,
        Map.of(),
        Map.of(),
        Map.of(),
        income,
        0L,
        0L,
        0L,
        income,
        laborNeed,
        scaledNetPerLabor(income, laborNeed),
        false,
        true,
        joinReasons(notes, List.of("WAGE_POSITION")));
  }

  // ── merchant 分支 ──────────────────────────────────────────────────────────────────────────

  private static Prospect merchantProspect(
      EconomyData base,
      HouseholdId household,
      ClassRow row,
      ProductionModeId modeId,
      ClassPosition position,
      HexCoord hex,
      Industry industryOrNull,
      Market market,
      MarketDemandBook.Book demand,
      Map<AssetShareId, AssetShare> shares,
      Set<AssetShareId> claimedByOrganizations,
      Map<ProductionUnitId, ProductionUnit> units,
      Map<ProductionUnitId, ProductionRelation> relations,
      MarketTopology topology,
      long day) {
    Industry trade =
        industryOrNull != null && isMerchantRegime(industryOrNull)
            ? industryOrNull
            : resolveIndustry(base, hex, modeId);
    if (trade == null || !isMerchantRegime(trade)) {
      return infeasible(
          household, modeId, position.id(), hex, null, "NO_MERCHANT_CAPACITY:no-trade-industry");
    }
    if (isWagePosition(position)) {
      return wageProspect(
          household, row, modeId, position, hex, trade, market, shares, units, relations);
    }
    ActorRef actor = HouseholdActors.of(household);
    MerchantFirm firm = null;
    ProductionOrganization firmOrganization = null;
    for (ProductionOrganizationId organizationId : sortedOrganizationIds(base)) {
      MerchantFirm candidate = base.merchantFirms().get(organizationId);
      if (candidate == null || !candidate.homeHex().equals(hex)) {
        continue;
      }
      // ★ 仍读 base.productionOrganizations() 过期快照（D-024 修复 1b 只修 employer/claimed，merchant 分支留下一轮）：
      //   当天自动组织阶段新建的组织不在 base 里，会让"现有商号"侧偏乐观；下一轮应传入当天工作副本 organizations。
      ProductionOrganization organization = base.productionOrganizations().get(organizationId);
      if (organization != null && organization.organizer().equals(actor)) {
        firm = candidate;
        firmOrganization = organization;
        break;
      }
    }
    long capacity;
    MerchantPolicy.MerchantTier tier;
    ProductionUnitId unitId;
    long assetUpkeep;
    boolean derivedCapacity = false;
    if (firm != null) {
      capacity = Math.max(0L, firm.capacityPerRound() - firm.capacityUsedThisRound());
      tier = firm.tier();
      unitId =
          firmOrganization != null && firmOrganization.unitId().isPresent()
              ? firmOrganization.unitId().get()
              : ProductionUnitId.idOf(trade.id(), actor);
      assetUpkeep =
          shipCattleUpkeepOf(
              firmOrganization == null ? List.of() : firmOrganization.assetSources(), shares);
    } else {
      Map<AssetKind, Long> assets =
          merchantAssetsOf(hex, actor, shares, claimedByOrganizations);
      long cattle = assets.getOrDefault(AssetKind.CATTLE, 0L);
      long ships = assets.getOrDefault(AssetKind.SHIP, 0L);
      capacity = Math.addExact(cattle, ships);
      tier = DEFAULT_MERCHANT_TIER; // 无商号 ⇒ 用具名最低档，避免高估 tier 折扣/城区当量
      unitId = ProductionUnitId.idOf(trade.id(), actor);
      assetUpkeep =
          Math.multiplyExact(
              capacity, MerchantSettlement.SHIP_CATTLE_UPKEEP_PER_UNIT_MILLI);
      derivedCapacity = true;
    }
    if (capacity <= 0L) {
      return infeasible(
          household, modeId, position.id(), hex, trade.id(), "NO_MERCHANT_CAPACITY:no-capacity");
    }
    long horizonDays = trade.cycleDays() <= 0L ? DEFAULT_MERCHANT_CYCLE_DAYS : trade.cycleDays();
    long routeDemand = demand.externalDemandTotal(hex);
    long unfilledBuyer = demand.unfilledBuyerTotal(hex);
    long requested = Math.max(routeDemand, unfilledBuyer);
    long carryable = Math.min(capacity, requested);
    long price = maxMarketPrice(market);
    long rate = maxAdjacentLaneRate(topology, hex);
    long revenue = 0L;
    List<String> notes = new ArrayList<>();
    if (carryable > 0L && price > 0L && rate > 0L) {
      revenue =
          ceilDiv(
              Math.multiplyExact(Math.multiplyExact(carryable, price), rate),
              1_000_000L);
    } else {
      if (requested <= 0L) {
        notes.add("NO_DEMAND");
      }
      if (price <= 0L) {
        notes.add("NO_PRICE");
      }
      if (rate <= 0L) {
        notes.add("NO_LANE");
      }
    }
    ProductionRelation relation = relationFor(relations, unitId, modeId, trade, row, actor);
    if (derivedCapacity) {
      notes.add("DERIVED_MERCHANT_CAPACITY");
    }
    if (hasInKindPerLaborRule(relation)) {
      // ★ 具名缺口：porter 劳动量拿不到（签名没有 LaborAllocation）⇒ 按劳动计的 porter 工资记 0，不静默。
      notes.add("PORTER_LABOR_UNKNOWN");
    }
    Obligations obligations =
        obligationsOf(
            relation,
            household,
            row,
            hex,
            market,
            Map.of(),
            Map.of(),
            revenue,
            0L,
            0L,
            true);
    if (relation == null) {
      notes.add("NO_RELATION");
    }
    long upkeep =
        Math.addExact(
            Math.multiplyExact((long) tier.districtUse(), MerchantPolicy.UPKEEP_PER_DISTRICT_USE),
            assetUpkeep);
    long laborNeed =
        Math.multiplyExact(row.participationAdjustedLaborMilli(), horizonDays);
    long net =
        Math.subtractExact(
            Math.subtractExact(
                Math.subtractExact(revenue, upkeep), obligations.laborWageMilli()),
            obligations.rentMilli());
    boolean demandCapped = requested > capacity;
    String reason =
        joinReasons(
            notes,
            obligations.notes(),
            demandCapped ? List.of("DEMAND_CAPPED") : List.of());
    return new Prospect(
        household,
        modeId,
        position.id(),
        hex,
        Optional.of(trade.id()),
        capacity,
        Map.of(),
        Map.of(),
        Map.of(),
        revenue,
        0L,
        Math.addExact(upkeep, obligations.laborWageMilli()),
        obligations.rentMilli(),
        net,
        laborNeed,
        scaledNetPerLabor(net, laborNeed),
        demandCapped,
        true,
        reason);
  }

  // ── 规模 / 关系 / 义务 ────────────────────────────────────────────────────────────────────

  /**
   * 候选可用资产规模（自有自营 + 同格可租闲置，判据与 {@link ModeMigrationPolicy#isIdleShare} 逐字同源）：
   * {@code min_k floor(可用资产[k] / capacityPerUnit[k])}。
   */
  private static long assetScaleOf(
      Industry industry,
      HouseholdId household,
      Map<AssetShareId, AssetShare> shares,
      Set<AssetShareId> claimedByOrganizations) {
    ActorRef actor = HouseholdActors.of(household);
    Map<AssetKind, Long> available = new LinkedHashMap<>();
    for (AssetShare share : shares.values()) {
      if (!share.industry().equals(industry.id()) || share.quantity() <= 0L) {
        continue;
      }
      boolean own = share.operator().equals(actor);
      boolean idle = ModeMigrationPolicy.isIdleShare(share, claimedByOrganizations);
      if (!own && !idle) {
        continue;
      }
      available.merge(share.asset(), share.quantity(), Math::addExact);
    }
    long scale = Long.MAX_VALUE;
    for (Map.Entry<AssetKind, Long> required : industry.capacityPerUnit().entrySet()) {
      scale = Math.min(scale, available.getOrDefault(required.getKey(), 0L) / required.getValue());
    }
    return scale == Long.MAX_VALUE ? 0L : scale;
  }

  /** merchant 运力资产（同格 trade 产业下的 CATTLE/SHIP；自有自营或可租闲置；claimed 由调用方传入）。 */
  private static Map<AssetKind, Long> merchantAssetsOf(
      HexCoord hex,
      ActorRef actor,
      Map<AssetShareId, AssetShare> shares,
      Set<AssetShareId> claimedByOrganizations) {
    Map<AssetKind, Long> available = new LinkedHashMap<>();
    for (AssetShare share : shares.values()) {
      if (share.asset() != AssetKind.CATTLE && share.asset() != AssetKind.SHIP) {
        continue;
      }
      if (share.quantity() <= 0L || !hex.equals(industryHex(share.industry()))) {
        continue;
      }
      boolean own = share.operator().equals(actor);
      boolean idle = ModeMigrationPolicy.isIdleShare(share, claimedByOrganizations);
      if (!own && !idle) {
        continue;
      }
      available.merge(share.asset(), share.quantity(), Math::addExact);
    }
    return available;
  }

  private static long shipCattleUpkeepOf(
      List<AssetShareId> assetSources, Map<AssetShareId, AssetShare> shares) {
    long upkeep = 0L;
    for (AssetShareId assetShareId : assetSources) {
      AssetShare share = shares.get(assetShareId);
      if (share == null || share.quantity() <= 0L) {
        continue;
      }
      if (share.asset() == AssetKind.CATTLE || share.asset() == AssetKind.SHIP) {
        upkeep =
            Math.addExact(
                upkeep,
                Math.multiplyExact(
                    share.quantity(), MerchantSettlement.SHIP_CATTLE_UPKEEP_PER_UNIT_MILLI));
      }
    }
    return upkeep;
  }

  /** 显式关系优先；缺则按产业 regime 推制度默认；制度未登记/拿不到 ⇒ null（调用方标 NO_RELATION，rent=0）。 */
  private static ProductionRelation relationFor(
      Map<ProductionUnitId, ProductionRelation> relations,
      ProductionUnitId unitId,
      ProductionModeId modeId,
      Industry industry,
      ClassRow row,
      ActorRef operator) {
    ProductionRelation explicit = relations.get(unitId);
    if (explicit != null) {
      return explicit;
    }
    // ★ 制度默认的 regime 先取 mode 的默认制度（租佃 mode ⇒ tenant 规则——farm@hex 的产业 regime 是 feudal，
    //   直接按产业会错配成庄园规则）；mode 未登记（如 merchant）或该 regime 无默认表 ⇒ 退回产业 regime。
    // ★ family_farm 是桥接 mode：它的产业是复用的农场（regime=feudal），household 默认规则的
    //   CLOTH 不在农场产出表里（E14 会拒）⇒ 这一档一律用产业 regime，与 EconomyOrganizationSettlement 同口径。
    Optional<String> modeRegime = RegimeOperators.defaultRegimeForMode(modeId);
    boolean useModeRegime =
        !DefaultProductionModes.FAMILY_FARM.equals(modeId)
            && modeRegime.isPresent()
            && RegimeRelations.registered().containsKey(modeRegime.get());
    RegimeId regime = useModeRegime ? new RegimeId(modeRegime.get()) : industry.regime();
    try {
      return RegimeRelations.defaultRelation(
          regime, unitId, industry.id(), operator, Set.of(row.view().residence()));
    } catch (IllegalArgumentException ignored) {
      return null; // 未登记的制度（如 merchant）⇒ 不猜一套规则
    }
  }

  /**
   * 一条关系里"从本经营者净收益里扣出去"的地租/分成/工资义务（毫钱）。
   *
   * <p>★ 规则口径与 {@code ProductionSettlement} 对齐：固定租是每周期一笔；分成按池 × 率；按劳动给养按
   * {@link SubsistenceObligation#perLaborDue}。★ 受方 == 本家户自己（同 actor / 同 household / 同 cohort）的规则是自付，
   * 直接跳过（与 E2 的 {@code normalizeRecipients} 同一口径）。
   */
  private static Obligations obligationsOf(
      ProductionRelation relation,
      HouseholdId household,
      ClassRow row,
      HexCoord hex,
      Market market,
      Map<CommodityId, Long> revenueByCommodity,
      Map<CommodityId, Long> inputCostByCommodity,
      long totalRevenue,
      long totalInputCost,
      long laborNeed,
      boolean includeWages) {
    if (relation == null) {
      return new Obligations(0L, 0L, List.of());
    }
    long rent = 0L;
    long wages = 0L;
    boolean priceMissing = false;
    List<String> notes = new ArrayList<>();
    // ★ OUTPUT_SHARE 的池是**逐商品**的（结算公式 {@code pool_c × rate × weight}）：这里按 (pool 层, 商品)
    //   维护 [池额, 已分出去, 劳动加权的最大率]。
    //   · Weight.NONE 的规则在结算里按 priority/表序读"已付"、付款上限咬合 ⇒ 本估算也逐条封顶，绝不把
    //     四条 1000‰ 的规则加成 4000‰（那会让真档农田纤维把经营者的净收益扣成负数）。
    //   · Weight.LABOR_AMOUNT 拿不到 Σ劳动 ⇒ 对同一池取最大率、只计一次（标 SHARE_WEIGHT_ESTIMATE），是上界。
    Map<String, long[]> sharePools = new LinkedHashMap<>();
    for (CompensationRule rule : relation.rules()) {
      if (isSelfRecipient(rule.recipient(), relation.operator(), household, row, hex)) {
        continue;
      }
      switch (rule.type()) {
        case FIXED_IN_KIND_RENT -> {
          CommodityId commodity = rule.commodity().orElse(null);
          long price = market == null || commodity == null ? 0L : market.askPriceOf(commodity);
          if (price <= 0L) {
            priceMissing = true;
          } else {
            rent =
                Math.addExact(
                    rent,
                    Math.multiplyExact(rule.fixedAmount(), price)
                        / EconomyVocabulary.MILLI_PER_COMMODITY_UNIT);
          }
        }
        case FIXED_MONEY_RENT -> rent = Math.addExact(rent, rule.fixedAmount());
        case FIXED_IN_KIND_PER_LABOR -> {
          if (!includeWages) {
            continue;
          }
          CommodityId commodity = rule.commodity().orElse(null);
          long price = market == null || commodity == null ? 0L : market.askPriceOf(commodity);
          if (price <= 0L) {
            priceMissing = true;
          } else {
            long due = SubsistenceObligation.perLaborDue(laborNeed, rule.fixedAmount());
            wages =
                Math.addExact(
                    wages,
                    Math.multiplyExact(due, price)
                        / EconomyVocabulary.MILLI_PER_COMMODITY_UNIT);
          }
        }
        case FIXED_MONEY_WAGE -> {
          if (includeWages) {
            wages = Math.addExact(wages, rule.fixedAmount());
          }
        }
        case OUTPUT_SHARE -> {
          CommodityId commodity = rule.commodity().orElse(null);
          boolean perCommodity =
              commodity != null
                  && (!revenueByCommodity.isEmpty() || !inputCostByCommodity.isEmpty());
          long gross =
              perCommodity
                  ? revenueByCommodity.getOrDefault(commodity, 0L)
                  : totalRevenue;
          long input =
              perCommodity
                  ? inputCostByCommodity.getOrDefault(commodity, 0L)
                  : totalInputCost;
          long pool =
              rule.pool() == Pool.GROSS_OUTPUT ? gross : Math.max(0L, gross - input);
          if (pool <= 0L) {
            continue;
          }
          String key = rule.pool().name() + "|" + (commodity == null ? "*" : commodity.value());
          long[] state = sharePools.computeIfAbsent(key, ignored -> new long[] {pool, 0L, 0L});
          state[0] = Math.max(state[0], pool);
          if (rule.weight() == Weight.LABOR_AMOUNT) {
            notes.add("SHARE_WEIGHT_ESTIMATE");
            state[2] = Math.max(state[2], rule.ratePerMille());
          } else {
            long remaining = Math.max(0L, state[0] - state[1]);
            long share =
                Math.min(
                    remaining,
                    Math.multiplyExact(state[0], (long) rule.ratePerMille()) / 1000L);
            if (share > 0L) {
              rent = Math.addExact(rent, share);
              state[1] = Math.addExact(state[1], share);
            }
          }
        }
        default -> {
          // SELF_RETENTION 数量恒 0（余额归 residualOwner），不构成义务。
        }
      }
    }
    for (long[] state : sharePools.values()) {
      if (state[2] <= 0L) {
        continue;
      }
      long remaining = Math.max(0L, state[0] - state[1]);
      long share =
          Math.min(remaining, Math.multiplyExact(state[0], state[2]) / 1000L);
      rent = Math.addExact(rent, share);
    }
    if (priceMissing) {
      notes.add("NO_PRICE");
    }
    return new Obligations(rent, wages, notes);
  }

  private record Obligations(long rentMilli, long laborWageMilli, List<String> notes) {
    private Obligations {
      Objects.requireNonNull(notes, "notes");
      notes = List.copyOf(notes);
    }
  }

  // ── 位置 / 产业 / 雇主 ────────────────────────────────────────────────────────────────────

  private static List<ClassPosition> producingPositions(ClassStructure structure) {
    List<ClassPosition> positions = new ArrayList<>();
    for (ClassPosition position : structure.positions().values()) {
      if (position.laborRole() != ClassPosition.LaborRole.NONE
          && position.surplusRole() != ClassPosition.SurplusRole.DEPENDENT) {
        positions.add(position);
      }
    }
    positions.sort(Comparator.comparing(position -> position.id().value()));
    return positions;
  }

  /**
   * 目标位置：先取家户当前位置（若它属于本 mode），否则同 relationToMeans/surplusRole 的第一个可生产位置，再否则 id 升序第一个
   * —— 与 {@code ModeMigrationSettlement.pickTargetPosition} 同一口径（本类不另立一套）。
   */
  private static ClassPosition currentPositionOf(EconomyData base, HouseholdId household) {
    var standing = base.classStandings().get(household);
    return standing == null ? null : base.classPositions().get(standing.currentPositionId());
  }

  private static ClassPosition choosePosition(
      EconomyData base, HouseholdId household, ProductionModeId modeId, List<ClassPosition> producing) {
    ClassPosition current = currentPositionOf(base, household);
    if (current != null && modeId.equals(current.modeId()) && producing.contains(current)) {
      return current;
    }
    if (current != null) {
      for (ClassPosition position : producing) {
        if (position.relationToMeans() == current.relationToMeans()
            && position.surplusRole() == current.surplusRole()) {
          return position;
        }
      }
    }
    return producing.get(0);
  }

  private static boolean isWagePosition(ClassPosition position) {
    if (position.surplusRole() == ClassPosition.SurplusRole.WAGE_EARNER) {
      return true;
    }
    return position.relationToMeans() == ClassPosition.RelationToMeans.DIRECT_LABORER
        && position.surplusRole() != ClassPosition.SurplusRole.SELF_SUBSISTENCE;
  }

  private static boolean isMerchantRegime(Industry industry) {
    return industry != null && RegimeOperators.MERCHANT.equals(industry.regime().value());
  }

  private static Industry resolveIndustry(EconomyData base, HexCoord hex, ProductionModeId modeId) {
    for (IndustryId industryId : ModeMigrationPolicy.industriesForMode(base, hex, modeId)) {
      Industry industry = base.industries().get(industryId);
      if (industry != null) {
        return industry;
      }
    }
    return null;
  }

  /** 雇主读数：unit 身份 + 经营者 + 产能规模（规模 = 该 unit 的 {@code capacityScaleOf}）。 */
  private record Employer(ProductionUnitId unitId, ActorRef operator, long scale) {
    private Employer {
      Objects.requireNonNull(unitId, "unitId");
      Objects.requireNonNull(operator, "operator");
    }
  }

  /**
   * 本 mode 在本格现有的最大雇主：<b>只读当天工作副本 {@code units}</b>，不再读
   * {@code base.productionOrganizations()}（周期关账时 base 是本周期开始时的 revision，organization.modeId 是过期读数）。
   *
   * <p>筛选判据（唯一拼写点 = 本方法 + {@link #employerModeRank}）：
   *
   * <ol>
   *   <li>{@code unit.industry().equals(industry.id())}（同产业；产业 id 自带格键，再用 {@link #industryHex} 判
   *       {@code industryHex(unit.industry()).equals(hex)}，不内联拼串）；
   *   <li>{@code unit.operator() != HouseholdActors.of(household)}（自己的 unit 不是自己的雇主）；
   *   <li>mode/产业兼容性按 {@link #employerModeRank} 分档（2 &gt; 1 &gt; 0）：有高秩候选时只在最高秩里选。
   * </ol>
   *
   * <p>规模 = {@link ProductionUnitBook#capacityScaleOf(ProductionUnit, Industry, Map)}；规模 &lt; 1 的候选先跳过
   * （没有真实产能可雇人，且 wageProspect 也会以 scale&lt;1 判 NO_EMPLOYER）；同秩同规模按
   * {@link ProductionUnitId#value()} 升序取第一个（确定性）。
   *
   * <p>★ 已知近似（如实记）：同一 farm 产业下 wage/family 两种 unit 的产业 regime 都是 feudal ⇒ target=wage_farm 时
   * family_farm 的 unit 也落第 1 档（"同产业即同一劳动市场"）；mode 精确到人的劳动配额/关系结算留给后续阶段。
   */
  private static Employer employerOf(
      ProductionModeId modeId,
      Industry industry,
      HexCoord hex,
      HouseholdId household,
      Map<ProductionUnitId, ProductionUnit> units,
      Map<AssetShareId, AssetShare> shares) {
    ActorRef self = HouseholdActors.of(household);
    Employer best = null;
    int bestRank = -1;
    List<ProductionUnitId> unitIds = new ArrayList<>(units.keySet());
    unitIds.sort(Comparator.comparing(ProductionUnitId::value));
    for (ProductionUnitId unitId : unitIds) {
      ProductionUnit unit = units.get(unitId);
      if (unit == null
          || !unit.industry().equals(industry.id())
          || unit.operator().equals(self)) {
        continue;
      }
      HexCoord unitHex = industryHex(unit.industry());
      if (unitHex == null || !unitHex.equals(hex)) {
        continue;
      }
      int rank = employerModeRank(unit, modeId, industry);
      long scale = ProductionUnitBook.capacityScaleOf(unit, industry, shares);
      if (scale < 1L) {
        continue; // 规模 0 的 unit 不是真实雇主（wageProspect 同样以 scale<1 判 NO_EMPLOYER），不让它挡住低秩的可用雇主
      }
      // unitIds 已按 id 升序：同秩同规模时先到者即"id 升序第一个"，不再改 best。
      if (best == null || rank > bestRank || (rank == bestRank && scale > best.scale())) {
        best = new Employer(unitId, unit.operator(), scale);
        bestRank = rank;
      }
    }
    return best;
  }

  /**
   * 雇主候选的 mode 兼容档（越大越优先）：
   *
   * <ol>
   *   <li>2 = 直接命中：{@code unit.modeKey()} 等于"本 mode 组织阶段/迁移新建"的 key
   *       （{@link EconomyOrganizationSettlement#MODE_KEY_PREFIX} + modeId.value()）；
   *   <li>1 = 产业 regime 相符：目标 mode 有默认 regime（{@link RegimeOperators#defaultRegimeForMode}）且等于该
   *       unit 所在产业的 {@code regime().value()}；
   *   <li>0 = 同产业回退：mode 未登记默认制度或制度不符 ⇒ 仍可在同产业 unit 里找工作（保持"同产业即可"语义）。
   * </ol>
   *
   * <p>★ 旧档/主 unit 的 {@code modeKey} 是产业 id（{@code farm@hex}）⇒ 落第 1 档；候选预设的
   * {@code candidateId@version} 无法反解 modeId ⇒ 按产业 regime 落第 1/0 档（具名近似，不猜版本内容）。
   */
  private static int employerModeRank(
      ProductionUnit unit, ProductionModeId modeId, Industry industry) {
    if (unit.modeKey().equals(EconomyOrganizationSettlement.MODE_KEY_PREFIX + modeId.value())) {
      return 2;
    }
    Optional<String> wanted = RegimeOperators.defaultRegimeForMode(modeId);
    if (wanted.isPresent() && wanted.get().equals(industry.regime().value())) {
      return 1;
    }
    return 0;
  }

  /**
   * 本户在该产业上实际在产的 unit id（现有 unit 优先；没有 ⇒ 由 {@code (industry, operator)} 确定性推出的候选 unit id）。
   *
   * <p>★ 为什么要先扫 {@code units}：播种/旧档的 unit id 可能由别的工厂/旧档原样落盘（{@code parse} 是 opaque 的），
   * 用 canonical id 去查 {@code relations} 会漏掉真实关系、退回制度默认（那会让佃农拿到封建庄园规则）。
   */
  private static ProductionUnitId existingUnitId(
      Industry industry, ActorRef operator, Map<ProductionUnitId, ProductionUnit> units) {
    ProductionUnitId canonical = ProductionUnitId.idOf(industry.id(), operator);
    if (units.containsKey(canonical)) {
      return canonical;
    }
    List<ProductionUnitId> unitIds = new ArrayList<>(units.keySet());
    unitIds.sort(Comparator.comparing(ProductionUnitId::value));
    for (ProductionUnitId unitId : unitIds) {
      ProductionUnit unit = units.get(unitId);
      if (unit != null
          && unit.industry().equals(industry.id())
          && unit.operator().equals(operator)) {
        return unitId;
      }
    }
    return canonical;
  }

  // ── 价 / 量 / 收益小工具 ───────────────────────────────────────────────────────────────────

  /** 产出的毛值（毫钱）：{@code Σ outputPerUnit × scale × bidPrice}（商品单位 × 规模 × 毫钱/单位）。 */
  private static long grossOutputValue(Industry industry, long scale, Market market) {
    long total = 0L;
    for (Map.Entry<CommodityId, Long> output : industry.recipe().outputPerUnit().entrySet()) {
      if (output.getValue() <= 0L || market == null) {
        continue;
      }
      long price = market.bidPriceOf(output.getKey());
      if (price <= 0L) {
        continue;
      }
      total =
          Math.addExact(
              total,
              Math.multiplyExact(Math.multiplyExact(output.getValue(), scale), price));
    }
    return total;
  }

  /** 劳动成本（毫钱）= laborNeed × subsistence × grainPrice ÷ 1_000_000（BigInteger 防溢出/截断）。 */
  private static long laborCostMilli(long laborNeed, long subsistence, long grainPrice) {
    if (laborNeed <= 0L || subsistence <= 0L || grainPrice <= 0L) {
      return 0L;
    }
    BigInteger numerator =
        BigInteger.valueOf(laborNeed)
            .multiply(BigInteger.valueOf(subsistence))
            .multiply(BigInteger.valueOf(grainPrice));
    return numerator.divide(BigInteger.valueOf(1_000_000L)).longValueExact();
  }

  /** {@code floor(net × PER_LABOR_SCALE / max(1, laborNeed))}；负数也向下取整（同 OrganizationProfitBook）。 */
  private static long scaledNetPerLabor(long net, long laborNeed) {
    BigInteger numerator =
        BigInteger.valueOf(net).multiply(BigInteger.valueOf(PER_LABOR_SCALE));
    BigInteger[] quotientAndRemainder =
        numerator.divideAndRemainder(BigInteger.valueOf(Math.max(1L, laborNeed)));
    BigInteger scaled = quotientAndRemainder[0];
    if (numerator.signum() < 0 && quotientAndRemainder[1].signum() != 0) {
      scaled = scaled.subtract(BigInteger.ONE);
    }
    try {
      return scaled.longValueExact();
    } catch (ArithmeticException overflow) {
      throw new ArithmeticException(
          "ExpectedProfitBook.netPerLaborScaled 溢出 long（fail-closed）: net="
              + net
              + ", labor="
              + laborNeed);
    }
  }

  private static boolean demandCapped(long remainingTotal, long sellableTotal) {
    return sellableTotal < remainingTotal;
  }

  private static long maxMarketPrice(Market market) {
    if (market == null) {
      return 0L;
    }
    long max = 0L;
    for (long price : market.prices().values()) {
      max = Math.max(max, price);
    }
    return max;
  }

  /** 本格所在区到邻接区的最大路线费率（‰）；退化拓扑/无邻区 ⇒ 0。 */
  private static long maxAdjacentLaneRate(MarketTopology topology, HexCoord hex) {
    if (topology == null || !topology.regional()) {
      return 0L;
    }
    MarketRegion home = regionOfOrNull(topology, hex);
    if (home == null) {
      return 0L;
    }
    long max = 0L;
    for (MarketRegion other : topology.regions()) {
      if (!topology.adjacent(home, other)) {
        continue;
      }
      max =
          Math.max(
              max, topology.freightPerMilleBetween(home.anchor(), other.anchor()));
    }
    return max;
  }

  private static MarketRegion regionOfOrNull(MarketTopology topology, HexCoord hex) {
    try {
      return topology.regionOf(hex);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  private static HexCoord industryHex(IndustryId industryId) {
    return IndustryHexKeys.hexKeyOf(industryId).map(HexCoord::parse).orElse(null);
  }

  private static boolean isSelfRecipient(
      Recipient recipient, ActorRef operator, HouseholdId household, ClassRow row, HexCoord hex) {
    if (recipient instanceof Recipient.ToActor toActor) {
      return toActor.actor().equals(operator) || toActor.actor().equals(HouseholdActors.of(household));
    }
    if (recipient instanceof Recipient.ToHousehold toHousehold) {
      return toHousehold.household().equals(household);
    }
    if (recipient instanceof Recipient.ToCohort toCohort) {
      return toCohort.cohort().hex().equals(hex)
          && toCohort.cohort().residence() == row.view().residence()
          && toCohort.cohort().stratum().equals(row.view().stratum());
    }
    return false;
  }

  private static boolean hasInKindPerLaborRule(ProductionRelation relation) {
    if (relation == null) {
      return false;
    }
    for (CompensationRule rule : relation.rules()) {
      if (rule.type() == io.mosire.simos.economy.api.relation.RuleType.FIXED_IN_KIND_PER_LABOR) {
        return true;
      }
    }
    return false;
  }

  private static boolean isWorkerRecipient(
      Recipient recipient, HouseholdId household, ClassRow row, HexCoord hex) {
    if (recipient instanceof Recipient.ToActor toActor) {
      return toActor.actor().equals(HouseholdActors.of(household));
    }
    if (recipient instanceof Recipient.ToHousehold toHousehold) {
      return toHousehold.household().equals(household);
    }
    if (recipient instanceof Recipient.ToCohort toCohort) {
      return toCohort.cohort().hex().equals(hex)
          && toCohort.cohort().residence() == row.view().residence()
          && toCohort.cohort().stratum().equals(row.view().stratum());
    }
    return false;
  }

  // ── 规范序 / 冻结 / 理由串 ────────────────────────────────────────────────────────────────

  private static Prospect infeasible(
      HouseholdId household,
      ProductionModeId modeId,
      ClassPositionId positionId,
      HexCoord hex,
      IndustryId industryId,
      String reason) {
    return new Prospect(
        household,
        modeId,
        positionId,
        hex,
        Optional.ofNullable(industryId),
        0L,
        Map.of(),
        Map.of(),
        Map.of(),
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        0L,
        false,
        false,
        reason);
  }

  /**
   * 键值非空、逐值 ≥ 0 的保序副本（返回<b>未包装</b>的 LinkedHashMap；调用方在赋值处
   * {@code Collections.unmodifiableMap(...)} 包装 —— SpotBugs 只认赋值处看得见的包装，故这里刻意不代包）。
   */
  private static Map<CommodityId, Long> copyNonNegative(
      Map<CommodityId, Long> values, String field) {
    if (values == null) {
      throw new IllegalArgumentException("Prospect." + field + " 不得为 null");
    }
    Map<CommodityId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : values.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("Prospect." + field + " 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "Prospect." + field + " 不得为负：" + entry.getKey() + " = " + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return copy;
  }

  /**
   * ★★ <b>仍读过期快照（D-024 修复 1b 未改的 merchant 分支专用）</b>：只服务 {@code merchantProspect} 的商号/组织查找，
   * 键集仍读 {@code base.productionOrganizations()}——周期关账时 base 是本周期开始时的 revision，当天自动组织阶段刚新建的
   * 组织不在其中。下一轮若修 merchant 分支，应把当天工作副本 {@code organizations} 作为入参传进来，而不是改这里去猜。
   */
  private static List<ProductionOrganizationId> sortedOrganizationIds(EconomyData base) {
    List<ProductionOrganizationId> ids =
        new ArrayList<>(base.productionOrganizations().keySet());
    ids.sort(Comparator.comparing(ProductionOrganizationId::value));
    return ids;
  }

  @SafeVarargs
  private static String joinReasons(List<String>... groups) {
    LinkedHashSet<String> tokens = new LinkedHashSet<>();
    for (List<String> group : groups) {
      for (String token : group) {
        if (token != null && !token.isBlank()) {
          tokens.add(token);
        }
      }
    }
    return String.join(",", tokens);
  }

  private static long ceilDiv(long numerator, long denominator) {
    if (denominator <= 0L) {
      throw new IllegalArgumentException("ceilDiv 的分母必须 > 0: " + denominator);
    }
    return Math.floorDiv(numerator + denominator - 1L, denominator);
  }
}
