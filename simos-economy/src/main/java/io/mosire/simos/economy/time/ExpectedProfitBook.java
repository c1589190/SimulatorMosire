package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.api.relation.SubsistenceObligation;
import io.mosire.simos.economy.api.relation.Weight;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.MarketTables;
import io.mosire.simos.economy.model.MerchantPolicy;
import io.mosire.simos.economy.model.MerchantTradeTemplate;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionParticipant;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.model.ProductionWageDefault;
import io.mosire.simos.economy.model.ProductionWagePosting;
import io.mosire.simos.economy.model.ProductionWageQuote;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.economy.model.WagePostingKey;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.ExternalAttributeFacts;
import io.mosire.simos.production.contract.HouseholdProductionConditions;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionEfficiencyState;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionModeDefinitionView;
import io.mosire.simos.production.contract.ProductionProbeRequest;
import io.mosire.simos.production.contract.ProductionProbeResult;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.production.contract.ProductionRuntime;
import io.mosire.simos.production.contract.RolePayment;
import io.mosire.simos.social.api.id.HouseholdId;
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
 * <p>★★ <b>它替代的旧口径</b>：候选门槛不再是"这个 mode/hex 有没有既存组织/真实利润读数"，而是按 产业配方 + 市场价（bid/ask）+ 可寻址需求 + 家户资产/劳动
 * + 关系/地租规则现算。旧 {@link EnterpriseProfitBook} 退位为事后对账（本类不读它）。
 *
 * <p>★★ <b>量纲（一次写清，实现里不再猜）</b>：
 *
 * <ul>
 *   <li>{@code plannedOutput/selfConsumed/sellable/inputNeed} 一律<b>毫商品</b>；★ §15.12 B 起经营位置的 {@code
 *       plannedOutput} 直接是 {@code probe.expectedOutput}（毫商品/周期，含 GM 覆盖与熟练度倍率 E）， {@code inputNeed}
 *       是 {@code probe.inputRequired}（毫商品/周期）—— economy 不再自算 {@code outputPerUnit × scale ×
 *       1000}（那是第二份物理算术）；
 *   <li>{@code MarketTable.priceOf} 是"毫计价货币 / 1 商品单位"⇒ 数量（毫商品） × 价格 ÷ 1000 = 毫钱；
 *   <li>{@code laborPerUnit} 是"千分劳动 / 日 / 规模"；★ §15.12 B 起周期劳动 = {@code probe.laborRequiredMilli ×
 *       horizonDays}（probe 的 laborRequired 是"每日"口径，见 {@code ProductionProbeResult} 的类注）；
 *   <li>劳动成本折算：{@code laborNeed（千分劳动·日） × subsistence（毫粮/1000 千分劳动） ÷ 1000 = 毫粮}， 再 × 粮价（毫钱/商品单位） ÷
 *       1000 = 毫钱（两步合一：÷ 1_000_000，内部用 {@link BigInteger} 防截断/溢出）；
 *   <li>{@code netPerLaborScaled = floor(net × 1_000_000 / max(1, laborNeed))}（负数也向下取整；同 {@link
 *       EnterpriseProfitBook#PER_LABOR_SCALE} 的百万分之一刻度）。
 * </ul>
 *
 * <p>★★ <b>与设计文档 §3.2.1 的一处量纲更正（如实记）</b>：文档写 {@code laborScale =
 * row.participationAdjustedLaborMilli() / (laborPerUnit × horizonDays)}。核对量纲：行劳动是"千分劳动/日"、 {@code
 * laborPerUnit} 是"千分劳动/日/规模" ⇒ 分子必须先 × {@code horizonDays} 才是视界（本周期）内的劳动。★ D-032 §15.12 B 起 这段算术由
 * {@code ProductionRuntime.probe} 承担：本类传 {@code participationAdjustedLaborMilli × horizonDays} 作
 * {@code laborMilli}（周期累计口径），probe 内部 {@code ÷cycleDays} 折回日均、再 {@code ÷laborPerUnit} 得劳动通道规模。
 * 按文档字面（分子不乘）会把日劳动当周期劳动，正是文档 §3.2.3 明令禁止的混用，且真档 {@code laborPerUnit=143/1000}、{@code
 * horizonDays=120} 时规模恒 0（候选永远不可行）。
 *
 * <p>★★ <b>失败语义</b>：没有产业/资产/雇主/运力 ⇒ {@code feasible=false} 且理由具名（{@code NO_*}）；没有市场/没有价格/没有需求 ⇒
 * {@code feasible=true} 但 {@code sellable=0}、{@code reason} 具名（不抛、不静默当高利润）。数据坏（缺 row/结构/位置）仍
 * fail-closed 为具名不可行，不抛异常——本类是"候选评估器"，一个不可行候选不该让整轮关账失败。
 *
 * <p>★★ <b>claimed 集合的唯一来源（D-024 修复 1/1b，2026-10-06）</b>：判"闲置份额"所需的 {@code claimedByEnterprises}
 * <b>由调用方用当天工作副本 {@code enterprises}/{@code units}/{@code assetShares} 算出后传入</b>（{@code
 * ModeMigrationPolicy.plan} 已经这么做： {@code claimedOwnershipStakes(enterprises, units,
 * assetShares)}）；本类<b>不再</b>从 {@code base.productionOrganizations()} 重算——周期关账时 {@code base}
 * 是本周期开始时的 revision，重算会把所有 {@code owner==operator} 的份额（含 ESTATE 名下全部土地）误判成"闲置可租"。claimed 含两格：组织
 * {@code assetSources} 引用 + 与在产 unit 同产业同 operator 且 {@code quantity>0} 的份额（seeder 直接建立的 ESTATE 主
 * unit 没有组织行，必须靠 unit 格才不漏）。闲置判据仍只经 {@link ModeMigrationPolicy#isIdleShare(OwnershipStake, Set)}
 * 这一处谓词，本类不新增第二套判据。
 *
 * <p>★★ <b>雇主读数的来源（D-024 修复 1b）</b>：{@code employerOf} 只从当天工作副本 {@code units} 按"同格 + 同产业 + operator
 * != 本户"找雇主，规模 = {@code ProductionProcessBook.capacityScaleOf}；不再读 {@code
 * base.productionOrganizations()} 的 {@code enterprise.modeId}（那是周期开始时的过期快照）。
 *
 * <p>★★ <b>仍保留的具名近似（如实记）</b>：merchant 分支的组织查找仍读 {@code base.productionOrganizations()} （签名拿不到当天工作副本
 * enterprises）；当天自动组织阶段刚新建的组织可能不在 base 里，会让 merchant 候选的"现有组织"侧略偏乐观； 真正落地由 {@code
 * ModeMigrationPolicy} 的预留账本与 {@code ModeMigrationSettlement} 的逐笔拆份额复核（计划可新建 ⇔ 执行可拆到）。 merchant
 * 分支的<b>运力</b>是 M-A1 的派生量（{@link MerchantCapacity}：劳动投入 + 工具可投入量；纯状态路径读不到商品账 ⇒ 工具项取 0 =
 * 下界）。merchant 分支的"路线需求"用 {@link MarketDemandBook.Book#externalDemandTotal}/{@code
 * unfilledBuyerTotal}（Book 里保留的读数），因为签名同样拿不到 原始 {@code MarketReport} 列表。
 *
 * <p>★ <b>确定性</b>：全部遍历按 id / (q,r) 规范序；无随机、无时钟、无 UUID；同输入同输出。
 */
public final class ExpectedProfitBook {

  private ExpectedProfitBook() {}

  /** 单位劳动净收益的高精度刻度（与 {@link EnterpriseProfitBook#PER_LABOR_SCALE} 同一把尺）。 */
  public static final long PER_LABOR_SCALE = EnterpriseProfitBook.PER_LABOR_SCALE;

  /** merchant 分支没有产业模板时的视界兜底（天）：本轮真实世界产业周期都是 120。 */
  public static final long DEFAULT_MERCHANT_CYCLE_DAYS = 120L;

  /**
   * ★ 船/畜维护单价（毫/单位；旧 {@code MerchantSettlement.SHIP_CATTLE_UPKEEP_PER_UNIT_MILLI}，逐值不变）。
   *
   * <p>★ 迁移说明（M-A1）：它的旧宿主 {@code MerchantSettlement} 已随商号行整体退役；本常量现在只被 merchant 前瞻的
   * "资产维护成本"一项读取，故就地落在唯一使用处，数值与口径一字不改（10 毫/单位·周期，GM 可改）。
   */
  public static final long SHIP_CATTLE_UPKEEP_PER_UNIT_MILLI = 10L;

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
      long expectedWageCostMilli,
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
      if (feasibleScale < 0L || laborNeedMilli < 0L || expectedWageCostMilli < 0L) {
        throw new IllegalArgumentException(
            "Prospect.feasibleScale/laborNeedMilli/expectedWageCostMilli 不得为负: "
                + feasibleScale
                + "/"
                + laborNeedMilli
                + "/"
                + expectedWageCostMilli);
      }
      plannedOutputMilli =
          Collections.unmodifiableMap(copyNonNegative(plannedOutputMilli, "plannedOutputMilli"));
      selfConsumedMilli =
          Collections.unmodifiableMap(copyNonNegative(selfConsumedMilli, "selfConsumedMilli"));
      sellableMilli = Collections.unmodifiableMap(copyNonNegative(sellableMilli, "sellableMilli"));
    }
  }

  // ── 唯一公开入口 ───────────────────────────────────────────────────────────────────────────

  /**
   * 现算一个家户在（mode, hex, industry, market）下的预期净收益/劳动。
   *
   * <p>★ 目标位置由 mode 的阶层结构 + 家户当前位置推（与 {@code ModeMigrationSettlement.pickTargetPosition} 同一口径： 先同
   * relationToMeans/surplusRole、再 id 升序的第一个可生产位置）——本类不另立一套"选位置"规则。
   *
   * @param base
   *     结算前状态（只读：modes/classStructures/classPositions/classStandings/industries/productionOrganizations）
   * @param household 被评估的家户
   * @param modeId 候选生产方式
   * @param hex 候选格
   * @param industryOrNull 该格的产业模板；{@code null} ⇒ 按 mode 的默认 regime 在该格挑（挑不到 ⇒ NO_INDUSTRY）
   * @param marketOrNull 该格所在市场的价表；{@code null} ⇒ 无价（收入只算自给折算，标 NO_MARKET）
   * @param demand 需求簿（可寻址需求上限）
   * @param shares 实物资产份额（候选：自有自营 + 同格可租闲置）
   * @param claimedByEnterprises 已被组织引用、或在产 unit 占用的资产份额 id 集合；<b>必须由调用方用当天工作副本 {@code
   *     enterprises}/{@code units}/{@code assetShares} 算出</b> （{@link
   *     ModeMigrationPolicy#claimedOwnershipStakes(Map, Map, Map)}），本类不从 {@code base} 重算（过期快照会让已
   *     使用份额被误判成闲置）；闲置判据只经 {@link ModeMigrationPolicy#isIdleShare(OwnershipStake, Set)}
   * @param accounts 账户会话（只读；★ §15.12 B 起读该户商品余额作为 probe 的"可投入原料"，缺账户/缺键 ⇒ 0）
   * @param units 生产单元表（找现有雇主/现有规模）
   * @param relations 生产关系表（显式关系优先；缺则制度默认）
   * @param topology merchant lane 查询；可为 null（退化单格世界）
   * @param day 当前世界日（merchant 分支的读数；不参与产出/成本公式）
   * @param runtime ★ §15.12 B：本会话的生产运行时（物理量——产出/所需劳动/所需投入——的唯一来源；不得为 null）
   */
  public static Prospect prospect(
      EconomyDayView base,
      HouseholdId household,
      ProductionModeId modeId,
      HexCoord hex,
      IndustryDefinitionView industryOrNull,
      MarketTable marketOrNull,
      MarketDemandBook.Book demand,
      Map<AssetShareId, OwnershipStake> shares,
      Set<AssetShareId> claimedByEnterprises,
      AccountSession accounts,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<ProductionUnitId, ProductionRules> relations,
      MarketTopology topology,
      long day,
      ProductionRuntime runtime) {
    return prospect(
        base,
        household,
        modeId,
        hex,
        industryOrNull,
        marketOrNull,
        demand,
        shares,
        claimedByEnterprises,
        accounts,
        units,
        relations,
        topology,
        day,
        null,
        runtime);
  }

  /**
   * ★★★ <b>W9（2026-10-11，用户裁定 v3.29/v3.30 §7 第 2/4 条）：可以指定"落在哪一档"的同一入口</b> —— {@code positionOrNull
   * != null} 时用它当这次评估的位置（该户带的库存能支撑到的那一档），{@code null} ⇒ 既有口径 （{@link
   * #choosePosition}）。其余判据、算式、量纲一字不改。
   *
   * <p>★★ <b>为什么要能指定</b>：「**取库存最能支持的最高家户**」是<b>档位</b>（{@code IndustryClassSlots} 的四档）决定的 —— 同一
   * (mode, hex) 里"落哪一档"会改<b>位置</b>（如 merchant 的 {@code principal / self_employed /
   * porter}），而位置决定收益模型 （工资位置走 {@code wageProspect}、经营位置走 {@code productionProspect}）⇒
   * 预期收益必须**按那一档的位置**算，否则"算的是甲档、落的是乙档"。
   *
   * <p>★ <b>fail-closed</b>：指定的位置不在该 mode 的<b>可生产位置</b>集合里（或为 null）⇒ 退回 {@link #choosePosition}
   * 的既有口径（不抛、不静默换 mode）。
   *
   * <p>★★ <b>D-032 §15.12 B（R1）</b>：{@code runtime} 是经营位置物理量（预计产出/规模/所需投入/所需劳动）的唯一来源 —— 见 {@link
   * #productionProspect}。
   */
  public static Prospect prospect(
      EconomyDayView base,
      HouseholdId household,
      ProductionModeId modeId,
      HexCoord hex,
      IndustryDefinitionView industryOrNull,
      MarketTable marketOrNull,
      MarketDemandBook.Book demand,
      Map<AssetShareId, OwnershipStake> shares,
      Set<AssetShareId> claimedByEnterprises,
      AccountSession accounts,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<ProductionUnitId, ProductionRules> relations,
      MarketTopology topology,
      long day,
      ClassPositionId positionOrNull,
      ProductionRuntime runtime) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(modeId, "modeId");
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(demand, "demand");
    Objects.requireNonNull(shares, "shares");
    Objects.requireNonNull(
        claimedByEnterprises, "claimedByOrganizations（必须由调用方用当天工作副本 organizations 算出，不得传 null）");
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(relations, "relations");
    Objects.requireNonNull(runtime, "runtime（§15.12 B：物理量必须取自 production 的试算）");
    // ★★ R2：一次评估只派生一份资产预算；现有 unit 的预计/工资雇主规模都读同一份分配结果。
    AssetBudgetBook.AssetBudget assetBudget = AssetBudgetBook.allocate(units, shares);
    // ★★★ D-032 D3 §5.1（W1）：工资报价/目标只从 W2a 的当刻读口取一份快照；实例存在时读 posting，
    //   实例存在前读候选 default。缺报价/缺目标一律具名，绝不按 0 静默。
    WageTermsSource wageTermsSource = WageTermsSource.of(base);
    if (day < 0L) {
      throw new IllegalArgumentException("ExpectedProfitBook.prospect 的 day 不得为负: " + day);
    }
    ProductionModeDefinitionView mode = base.modes().get(modeId);
    if (mode == null) {
      return infeasible(household, modeId, null, hex, null, "NO_MODE");
    }
    ClassStructure structure = base.classStructures().get(mode.classStructureId());
    if (structure == null) {
      return infeasible(household, modeId, null, hex, null, "NO_STRUCTURE");
    }
    List<ProductionRole> producing = producingPositions(structure);
    if (producing.isEmpty()) {
      return infeasible(household, modeId, null, hex, null, "NO_POSITION");
    }
    // ★ 当前户在本 mode 的位置若是纯收租/被供养位置（landlord / destitute），不进入生产候选（E2 的 shouldProduce 同口径）：
    //   它没有"生产净收益"可算（租金收入本批不建模）⇒ 具名不可行、net=0；A 规则不会被它触发。
    ProductionRole currentPosition = currentPositionOf(base, household);
    if (currentPosition != null
        && modeId.equals(currentPosition.modeId())
        && (currentPosition.laborRole() == ProductionRole.LaborRole.NONE
            || currentPosition.surplusRole() == ProductionRole.SurplusRole.DEPENDENT)) {
      return infeasible(household, modeId, currentPosition.id(), hex, null, "DEPENDENT");
    }
    ProductionRole position = resolvePosition(base, household, modeId, producing, positionOrNull);
    HouseholdEconomy householdEconomy = base.classes().get(household);
    if (householdEconomy == null || householdEconomy.population() <= 0L) {
      return infeasible(household, modeId, position.id(), hex, null, "NO_ROW");
    }
    if (DefaultProductionModes.DISPLACED.equals(modeId)) {
      // ★★ D-023 #6：流民没有工作、不得从 DISPLACED 池主动招募 —— 预期收益里也不得假装它有一份雇主工资。
      return infeasible(household, modeId, position.id(), hex, null, "DISPLACED_NO_WORK");
    }
    IndustryDefinitionView industry =
        industryOrNull != null ? industryOrNull : resolveIndustry(base, hex, modeId);
    if (DefaultProductionModes.MERCHANT.equals(modeId)) {
      return merchantProspect(
          base,
          household,
          householdEconomy,
          modeId,
          position,
          hex,
          industry,
          marketOrNull,
          demand,
          shares,
          claimedByEnterprises,
          units,
          relations,
          topology,
          day,
          accounts,
          assetBudget,
          wageTermsSource);
    }
    if (industry == null) {
      return infeasible(household, modeId, position.id(), hex, null, "NO_INDUSTRY");
    }
    if (isWagePosition(position)) {
      return wageProspect(
          base,
          household,
          householdEconomy,
          modeId,
          position,
          hex,
          industry,
          marketOrNull,
          units,
          relations,
          assetBudget,
          wageTermsSource);
    }
    return productionProspect(
        base,
        household,
        householdEconomy,
        modeId,
        position,
        hex,
        industry,
        marketOrNull,
        demand,
        shares,
        claimedByEnterprises,
        accounts,
        units,
        relations,
        runtime,
        assetBudget,
        wageTermsSource);
  }

  // ── 生产位置（OWNER / SURPLUS_RECEIVER / TENANCY）─────────────────────────────────────────

  /**
   * ★★★ <b>D-032 §15.12 B（R1）：经营位置的预期读数 —— 物理量一律取自 {@link ProductionRuntime#probe}</b>。
   *
   * <p>★★ <b>为什么要换读源</b>：改前这里自算 {@code scale = min(资产规模, 劳动规模)}、{@code plannedOutput =
   * outputPerUnit × scale × 1000} —— 不读熟练度/经验、不读 GM 产出覆盖、不读解锁/可用性条件。于是"预计 100、实际 200" （熟练度 2
   * 倍）这类脱节在选择链上全程看不见（R1）。改后：probe 是<b>唯一的</b>物理产出/需求来源，本方法只保留估价 （价 × 量、自给、需求上限、关系分账）与排序读数。
   *
   * <p>★★ <b>probe 入参（§15.12 B 逐条）</b>：
   *
   * <pre>
   * 劳动       = 家户 participationAdjustedLaborMilli × horizonDays      （本周期累计；probe 内部 ÷cycleDays）
   * 占用       = 既有 unit 的 usableAssets（★ R2/E2 替换点：统一分配后必须换成分配结果）
   *              候选：与 assetScaleOf **同一判据**的可用资产表（自有自营 + 同格可租闲置、claimed 排除）
   * 投入       = 本户账户里方案投入键上的库存（缺账户/缺键 ⇒ 0）
   * 熟练度/经验 = 本 tick 冻结条件优先（R5），否则运行时读口（ProductionProbeInputs 的唯一拼写点）
   * carry      = runtime.efficiencyOf(unitId)（候选 ⇒ 空：没有实例就没有余数行）
   * attributes = EMPTY（正式日循环的属性通路本就未接；声明了外部属性条件的方案因此具名拒，见 §4.5）
   * </pre>
   *
   * <p>★★ <b>结果消费</b>：{@code plannedOutput = probe.expectedOutput}（毫商品/周期，含 GM 覆盖与 E）· {@code scale
   * = probe.scale()}（产出规模）· {@code inputCost} 用 {@code probe.inputRequired}（键集同配方）· {@code
   * laborNeed = probe.laborRequiredMilli × horizonDays}（每日 →
   * 周期）。自给/可售/需求上限/收入/分账（obligations）/净额的算式形状不变。
   *
   * <p>★ <b>不可行理由映射</b>：{@code asset-short → NO_ASSET:<industry>}、{@code labor-short →
   * NO_LABOR:<household>}、 {@code plan-unknown → NO_INDUSTRY}、其余（含 {@code locked}/{@code
   * condition-unsatisfied:*}）⇒ {@code PROBE:<reason>}； feasible 但规模 0 ⇒ {@code SCALE_ZERO}（旧词表保留）。★
   * 资产那道闸仍在 probe 之前用同一判据判（旧口径的 {@code NO_ASSET} 逐值不变；占用为空时 probe 会给"可行、规模 0"，不提前判会把旧理由漂成 {@code
   * SCALE_ZERO}）。
   */
  private static Prospect productionProspect(
      EconomyDayView base,
      HouseholdId household,
      HouseholdEconomy householdEconomy,
      ProductionModeId modeId,
      ProductionRole position,
      HexCoord hex,
      IndustryDefinitionView industry,
      MarketTable market,
      MarketDemandBook.Book demand,
      Map<AssetShareId, OwnershipStake> shares,
      Set<AssetShareId> claimedByEnterprises,
      AccountSession accounts,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<ProductionUnitId, ProductionRules> relations,
      ProductionRuntime runtime,
      AssetBudgetBook.AssetBudget assetBudget,
      WageTermsSource wageTermsSource) {
    long horizonDays = industry.cycleDays();
    long laborPerUnit = industry.recipe().laborPerUnit();
    ActorRef operator = HouseholdActors.of(household);
    ProductionUnitId unitId = existingUnitId(industry, operator, units);
    ProductionProcess existingUnit = units.get(unitId);
    Map<AssetKind, Long> occupancy;
    Optional<ProductionEfficiencyState> carry;
    if (existingUnit != null) {
      // ★★ R2：共享资产 scope 的 unit 在预计阶段具名不可行；不得把整池复制给多个实例。
      Optional<String> assetRejection = assetBudget.rejectionOf(existingUnit.id());
      if (assetRejection.isPresent()) {
        return infeasible(
            household, modeId, position.id(), hex, industry.id(), assetRejection.get());
      }
      // 当前在产：用这个 unit 的统一资产分配结果（含租佃而来的 operator==组织者份额）——预计与实际同源。
      occupancy =
          ProductionProbeInputs.positiveAssets(
              ProductionProcessBook.usableAssets(existingUnit, assetBudget));
      carry = runtime.efficiencyOf(unitId);
    } else {
      // ★★ R2：同一个 (industry, operator) 已有别的 unit（旧档 opaque id/坏路径）⇒ 不得把候选再叠成共享实例。
      if (AssetBudgetBook.hasUnitInScope(units, industry.id(), operator)) {
        return infeasible(
            household,
            modeId,
            position.id(),
            hex,
            industry.id(),
            AssetBudgetBook.SHARED_SCOPE_REJECTION);
      }
      // 候选：自有自营 + 同格可租闲置（闲置判据的唯一拼写点在 ModeMigrationPolicy）。
      // ★★ D-024 修复 1：claimed 由调用方从当天工作副本 enterprises 算出并传入，本类不再从 base 重算。
      occupancy =
          ProductionProbeInputs.positiveAssets(
              candidateUsableAssets(industry, household, shares, claimedByEnterprises));
      carry = Optional.empty(); // 候选：没有实例 ⇒ 没有精度余数行
    }
    // ★★★ D-032 D3 §5.1（W1）：全部 MONEY_WAGE 角色的预计应付工资进入成本；在算成本之前先做
    //   payer 级地点预检（existing ∪ candidate）；>1 地点 ⇒ 候选不可行、零工资成本/净额。
    WageEstimate wageEstimate =
        estimateEmployerWageCost(
            base, industry, existingUnit, hex, operator, units, horizonDays, wageTermsSource);
    if (wageEstimate.rejection().isPresent()) {
      return infeasible(
          household, modeId, position.id(), hex, industry.id(), wageEstimate.rejection().get());
    }

    // ★ 资产那道闸（与改前同一判据：min_k ⌊可用_k ÷ capacityPerUnit_k⌋ < 1 ⇒ NO_ASSET:<industry>）。
    long assetScale = assetScaleOf(industry, occupancy);
    if (assetScale < 1L) {
      return infeasible(
          household,
          modeId,
          position.id(),
          hex,
          industry.id(),
          "NO_ASSET:" + industry.id().value());
    }

    // ★★ §15.12 B：本周期累计劳动（probe 内部 ÷cycleDays 折回日均）。
    long laborPeriodMilli =
        Math.multiplyExact(householdEconomy.participationAdjustedLaborMilli(), horizonDays);
    HouseholdProductionConditions conditions =
        new HouseholdProductionConditions(
            household,
            laborPeriodMilli,
            occupancy,
            ProductionProbeInputs.inputsOf(accounts.householdGoods().get(household), industry),
            ProductionProbeInputs.masteryOf(runtime, household, industry),
            ProductionProbeInputs.experienceOf(runtime, household, industry));
    ProductionProbeResult probe =
        runtime.probe(
            new ProductionProbeRequest(
                industry.id(), conditions, occupancy, carry, ExternalAttributeFacts.EMPTY));
    if (!probe.feasible()) {
      return infeasible(
          household,
          modeId,
          position.id(),
          hex,
          industry.id(),
          probeRejectionReason(probe.reason(), industry, household));
    }
    long scale = probe.scale();
    if (scale < 1L) {
      return infeasible(household, modeId, position.id(), hex, industry.id(), "SCALE_ZERO");
    }

    LinkedHashMap<CommodityId, Long> plannedOutput = new LinkedHashMap<>(probe.expectedOutput());
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
              householdEconomy.naturalNeeds().getOrDefault(commodity, 0L), horizonDays);
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

    // ★★★ D-032 C2：产品/投入估值统一走 `bidValueMilli`/`askValueMilli`（唯一拼写点；与
    //   `rosterIncomes` 同口径），本方法不再内联一套价格公式。
    Map<CommodityId, Long> plannedQuantities = new LinkedHashMap<>();
    Set<CommodityId> unpricedOutputs = new LinkedHashSet<>();
    for (CommodityId commodity : plannedOutput.keySet()) {
      long quantity =
          Math.addExact(
              selfConsumed.getOrDefault(commodity, 0L), sellable.getOrDefault(commodity, 0L));
      if (quantity > 0L) {
        plannedQuantities.put(commodity, quantity);
      }
    }
    Map<CommodityId, Long> revenueByCommodity =
        bidValuesByCommodity(market, plannedQuantities, unpricedOutputs);
    long revenue = sumValues(revenueByCommodity);

    boolean inputPriceMissing = false;
    Set<CommodityId> unpricedInputs = new LinkedHashSet<>();
    Map<CommodityId, Long> inputCostByCommodity =
        askValuesByCommodity(market, probe.inputRequired(), unpricedInputs);
    inputPriceMissing = !unpricedInputs.isEmpty();
    long inputCost = sumValues(inputCostByCommodity);

    // ★★ §15.12 B：laborRequiredMilli 是"每日"口径（基准规模 × 每单位劳动）⇒ × horizonDays 折成本周期劳动。
    long laborNeed =
        laborPerUnit <= 0L ? 0L : Math.multiplyExact(probe.laborRequiredMilli(), horizonDays);
    long grainPrice = market == null ? 0L : market.priceOf(EconomySettlement.GRAIN);
    boolean grainPriceMissing =
        laborNeed > 0L && (market == null || !market.hasPrice(EconomySettlement.GRAIN));
    long subsistenceLaborCost =
        grainPriceMissing
            ? 0L
            : laborCostMilli(laborNeed, RegimeRelations.subsistenceMilliPerLabor(), grainPrice);

    ProductionRules relation =
        relationFor(relations, unitId, modeId, industry, householdEconomy, operator);
    Obligations obligations =
        obligationsOf(
            relation,
            household,
            householdEconomy,
            hex,
            market,
            revenueByCommodity,
            inputCostByCommodity,
            revenue,
            inputCost,
            laborNeed,
            false);
    // ★ D3 §5.4：laborCostMilli 只保留"生存/非工资劳动折算"；预计工资单独成项。
    long laborCost = subsistenceLaborCost;
    long expectedWageCost = wageEstimate.amountMilli();
    long rent = obligations.rentMilli();
    long net =
        Math.subtractExact(
            Math.subtractExact(
                Math.subtractExact(Math.subtractExact(revenue, inputCost), laborCost),
                expectedWageCost),
            rent);
    boolean demandCapped = demandCapped(remainingTotal, sellableTotal);
    String reason =
        joinReasons(
            obligations.notes(),
            wageEstimate.notes(),
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
        expectedWageCost,
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
      EconomyDayView base,
      HouseholdId household,
      HouseholdEconomy householdEconomy,
      ProductionModeId modeId,
      ProductionRole position,
      HexCoord hex,
      IndustryDefinitionView industry,
      MarketTable market,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<ProductionUnitId, ProductionRules> relations,
      AssetBudgetBook.AssetBudget assetBudget,
      WageTermsSource wageTermsSource) {
    long horizonDays = industry.cycleDays();
    long laborPerUnit = industry.recipe().laborPerUnit();
    Employer employer = employerOf(modeId, industry, hex, household, units, assetBudget);
    if (employer == null || employer.scale() < 1L) {
      return infeasible(
          household,
          modeId,
          position.id(),
          hex,
          industry.id(),
          "NO_EMPLOYER:" + industry.id().value());
    }
    long laborScale =
        laborPerUnit <= 0L
            ? Long.MAX_VALUE
            : householdEconomy.participationAdjustedLaborMilli() / laborPerUnit;
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
    ProductionProcess employerUnit = units.get(employer.unitId());
    List<ProductionRoleDefinition> moneyRoles = moneyWageRoles(industry);
    long income = 0L;
    boolean priceMissing = false;
    List<String> notes = new ArrayList<>();
    if (!moneyRoles.isEmpty()) {
      // ★★★ D-032 D3 §5.2（W1）：MONEY_WAGE worker 的预计应收 = quote × 预计可工作劳动 × cycleDays / 1000。
      //   先做 payer（雇主）级地点预检；>1 地点 ⇒ 候选 skip/具名拒绝，不进入收入计算。
      WageEstimate wageEstimate =
          estimateWorkerWageIncome(
              base,
              industry,
              employer,
              employerUnit,
              hex,
              householdEconomy,
              laborPerUnit,
              horizonDays,
              moneyRoles,
              units,
              wageTermsSource);
      if (wageEstimate.rejection().isPresent()) {
        return infeasible(
            household, modeId, position.id(), hex, industry.id(), wageEstimate.rejection().get());
      }
      income = wageEstimate.amountMilli();
      notes.addAll(wageEstimate.notes());
    } else {
      // ★ 旧非名册路径仍保留可计价非货币收入（in-kind/share）；但 FIXED_MONEY_WAGE 绝不回读（D3 §2.2.1）。
      ProductionRules relation =
          relationFor(
              relations,
              employer.unitId(),
              modeId,
              industry,
              householdEconomy,
              employer.operator());
      notes.add("wage-quote-missing");
      if (relation == null) {
        notes.add("NO_RELATION");
      } else {
        for (CompensationRule rule : relation.rules()) {
          if (!isWorkerPayee(rule.recipient(), household, householdEconomy, hex)) {
            continue;
          }
          switch (rule.type()) {
            case FIXED_MONEY_WAGE -> notes.add("LEGACY_MONEY_WAGE_IGNORED");
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
                      Math.multiplyExact(due, price) / EconomyVocabulary.MILLI_PER_COMMODITY_UNIT);
            }
            case OUTPUT_SHARE -> {
              // ★ 具名近似：分账池要"本受方劳动 ÷ Σ劳动"，而本计算器拿不到该 unit 的全部劳动
              //   ⇒ 按"这份产出全归本受方"的上界计入，并标 WAGE_SHARE_ESTIMATE（不静默）。
              long employerOutputValue = grossOutputValue(industry, employer.scale(), market);
              long share = Math.multiplyExact(employerOutputValue, rule.ratePerMille()) / 1000L;
              income = Math.addExact(income, share);
              notes.add("WAGE_SHARE_ESTIMATE");
            }
            default -> {
              // 其余规则（租金/自留）不是劳动者的收入来源，跳过。
            }
          }
        }
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
        0L,
        income,
        laborNeed,
        scaledNetPerLabor(income, laborNeed),
        false,
        true,
        joinReasons(notes, List.of("WAGE_POSITION")));
  }

  /**
   * ★★★ <b>W7-fix（2026-10-11，用户裁定 v3.28 §7）：跑商可行性的**新判据 = 劳动 + 工具**</b> ——
   * 「**能不能先把牲口要求取消掉啊？目前是在跑测试，算作工具需求得了**」。
   *
   * <pre>
   * 判据 = 该户**本轮能投的劳动投入 &gt; 0** ∧ 该户**手上的工具 &gt; 0**（会话商品账）
   * 口径 = J-B 的既有算式「运力 = f(劳动投入, 工具消耗)」的两个操作数（{@link MerchantCapacity#capacityMilliOf}）
   * 缺项 = 具名不可行：{@code NO_MERCHANT_CAPACITY:no-labor} / {@code NO_MERCHANT_CAPACITY:no-tool-input}
   * </pre>
   *
   * <p>★★ <b>它取代了什么（W7 的 `no-carrier-asset` 已删，§一.11）</b>：W7 在这一处要求"该户**自备 CATTLE &gt; 0**"。
   * 实测真世界（W7d 测试批）：全世界只有 {@code 0_0}/{@code 0_2} 两户持 CATTLE，非城市格既买不到也养不出 ⇒ {@code
   * MERCHANT_TRADE_INDUSTRY_CREATED} = **0**、非城市格一户新跑商都没有 —— 那道闸把本批要开的路整个堵死。 用户据此裁定：**CATTLE
   * 不再是跑商的可行性门槛，也不再是运力的来源**；跑商的家户**凭"劳动 + 工具"就能跑商**。
   *
   * <p>★ <b>真正决定成败的量仍是执行期与结算期的那一份</b>：本判据只回答"这一户**有没有**跑商的投入"（可行性）， 规模/收益/能不能接到活由 {@link
   * MerchantCapacityPool#providerCapacityMilli} 与市场轮决定（fail-closed： 这里说不可行 ⇒ 一定不可行）。
   *
   * @param accounts 会话账户（读该户的 {@code tool} 存量；读不到 ⇒ 0 ⇒ 具名 {@code no-tool-input}）
   * @param market 目标格的价表（★ W9 新增入参：工具维的"**买得到**"通道读它；{@code null} = 该格没有市场 ⇒ 买不到）
   */
  private static String merchantInputFailureReason(
      HouseholdEconomy householdEconomy,
      HouseholdId household,
      AccountSession accounts,
      MarketTable market) {
    Objects.requireNonNull(householdEconomy, "householdEconomy");
    Objects.requireNonNull(accounts, "accounts");
    // ① 劳动维：参与率折算后的本轮劳动投入（与 J-B 算式、与主/副业排序读**同一个** getter）。
    if (householdEconomy.participationAdjustedLaborMilli() <= 0L) {
      return "NO_MERCHANT_CAPACITY:no-labor";
    }
    // ② 工具维：手上**可投入**的工具（商品账口径；V-22 冻结：不动 AssetKind.SMALL_TOOL 产权份额）。
    //    ★ 只判"有没有"（> 0），不判多少 —— 量级由 providerCapacityMilli 与市场轮决定（不在这里造第二套标定）。
    long toolMilli =
        accounts
            .householdGoods()
            .getOrDefault(household, Map.of())
            .getOrDefault(MerchantTradeTemplate.TOOL_COMMODITY, 0L);
    if (toolMilli > 0L) {
      return null;
    }
    // ★★★ W9（2026-10-11，用户裁定 v3.29 §7 第 3 条「**没有生产工具不会买吗**」）：**手头没有工具 ≠ 不可行** ——
    //   只要目标格的市场**交易**工具这个商品，这户就有"买 / 赊购"的通道（W2 起买得起不是前提：未付价款记债 =
    //   {@code MarketSettlement.DEFERRED_PAYMENT_TERMS} 的口径；"0 价"也是合法定价 = 白送）。
    //   判据与 {@code ModeMigrationPolicy.inKindLegUnavailableReasonOf} 第 ③ 条**同一口径**（"市场有这一行 ⇒ 拿得到"）。
    //   ⇒ 只在"**手头没有 ∧ 市场也不交易它**"时才具名失败（＝用户说的"买不到（没货）"那一支）。
    if (market != null && market.hasPrice(MerchantTradeTemplate.TOOL_COMMODITY)) {
      return null;
    }
    return "NO_MERCHANT_CAPACITY:no-tool-input";
  }

  // ── merchant 分支 ──────────────────────────────────────────────────────────────────────────

  private static Prospect merchantProspect(
      EconomyDayView base,
      HouseholdId household,
      HouseholdEconomy householdEconomy,
      ProductionModeId modeId,
      ProductionRole position,
      HexCoord hex,
      IndustryDefinitionView industryOrNull,
      MarketTable market,
      MarketDemandBook.Book demand,
      Map<AssetShareId, OwnershipStake> shares,
      Set<AssetShareId> claimedByEnterprises,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<ProductionUnitId, ProductionRules> relations,
      MarketTopology topology,
      long day,
      AccountSession accounts,
      AssetBudgetBook.AssetBudget assetBudget,
      WageTermsSource wageTermsSource) {
    IndustryDefinitionView trade =
        industryOrNull != null && isMerchantRegime(industryOrNull)
            ? industryOrNull
            : resolveIndustry(base, hex, modeId);
    // ★★★ W7-fix（2026-10-11，用户裁定 v3.28 §7）：**可行性 = 劳动 + 工具**（CATTLE 门槛已删）。
    //
    //   判据位置在**解析产业模板之前**：这一户有没有跑商的投入，与"本格有没有商号模板"是两件事 —— 缺投入的户
    //   在本格有模板时同样不可行（改前它会在下游以 `NO_MERCHANT_CAPACITY:no-capacity` 返回，理由不如这里具体）。
    //   ⇒ 具名理由：no-labor（没劳动）/ no-tool-input（没工具），两者都是 NO_MERCHANT_CAPACITY 一族
    //      ⇒ 逐候选 DEBUG（MODE_MIGRATION_PROSPECT_INFEASIBLE）与轮级 MODE_MIGRATION_CANDIDATES 照旧可读。
    String inputFailure = merchantInputFailureReason(householdEconomy, household, accounts, market);
    if (inputFailure != null) {
      return infeasible(household, modeId, position.id(), hex, null, inputFailure);
    }
    // ★★★ W7（2026-10-11）：**非城市格也能长出跑商** —— 本格没有 merchant 制度的产业模板时，允许在该格
    //   **按需开一间商号**（用户问的正是这条：「难道没有家户自动估算其他生产方式的利润，然后自动设置
    //   往其他所需要投入更低、利润更高的家户跑的机制吗？」）。
    //
    //   ★★ W7-fix：门槛不再是"自备 CATTLE"，而是上面那条"劳动 + 工具"（用户裁定）⇒ 非城市格的家户
    //     **凭劳动 + 工具就能跑商**，这正是"跑商自己长出来"这条通道被打通的那一步。
    //     ★ 作业资本那一维（capacityPerUnit）已随模板改成 SMALL_TOOL ⇒ 执行期 `planAssetMoves` 要的是**工具**份额，
    //       它是可移动资产（随迁携带，D-023 §4），不再要求非城市格有牲口。
    //
    //   ★ 估算用的模板 = {@link MerchantTradeTemplate#industry(HexCoord)}（**纯函数、不落状态**）—— 它与迁移执行
    //     真的写进工作副本的那一份是**同一个拼写点** ⇒ "预估的那个商号"与"真的长出来的那个商号"不可能漂开。
    if (trade == null || !isMerchantRegime(trade)) {
      trade = MerchantTradeTemplate.industry(hex);
    }
    if (isWagePosition(position)) {
      return wageProspect(
          base,
          household,
          householdEconomy,
          modeId,
          position,
          hex,
          trade,
          market,
          units,
          relations,
          assetBudget,
          wageTermsSource);
    }
    ActorRef actor = HouseholdActors.of(household);
    // ★★★ W7 / 两把尺统一（用户派单 ④）：运力 = {@link MerchantCapacityPool#providerCapacityMilli} —— **决策层与
    //   结算层读同一份派生量**（服务成市格 = max(手上可交付的服务货, 劳动 + 工具能产出的量)；其余格 = 劳动 + 工具）。
    //   ★ 改前这里只读 `laborCapacityMilli(participationAdjustedLaborMilli)`（劳动一项）⇒ 与结算层的"手上的服务货"
    //     是两把尺：一个手上有服务货、但劳动为 0 的户会被判成"没有运力"，而它其实能卖服务（调查报告 G4/A3 实测的
    //     `hh-0_2-urban-landlord` 的 `no-bargaining-power` 就是这一处）。
    //   ★ 工具维在这条路径上从**会话商品账**读（W7 起 `accounts` 本来就在入参里，不再是"读不到 ⇒ 传 0"）——
    //     这正是用户原话「难到家户不会给预估生产方式预留生产资料吗？」指的那一项。
    long capacity =
        MerchantCapacityPool.providerCapacityMilli(base, household, hex, accounts.householdGoods());
    MerchantPolicy.MerchantTier tier = MerchantCapacity.tierOf(capacity);
    ProductionEnterprise firmEnterprise = merchantEnterpriseOf(base, actor);
    ProductionUnitId unitId =
        firmEnterprise != null && firmEnterprise.unitId().isPresent()
            ? firmEnterprise.unitId().get()
            : ProductionUnitId.idOf(trade.id(), actor);
    // ★★★ D-032 D3 §5.1（W1）：跑商经营候选同样把全部 MONEY_WAGE 角色的预计应付工资计入成本，
    //   并在算成本之前做 payer 级地点预检（existing ∪ candidate）。
    WageEstimate wageEstimate =
        estimateEmployerWageCost(
            base,
            trade,
            units.get(unitId),
            hex,
            actor,
            units,
            Math.max(1L, trade.cycleDays()),
            wageTermsSource);
    if (wageEstimate.rejection().isPresent()) {
      return infeasible(
          household, modeId, position.id(), hex, trade.id(), wageEstimate.rejection().get());
    }
    long assetUpkeep =
        shipCattleUpkeepOf(
            firmEnterprise == null ? List.of() : firmEnterprise.assetSources(), shares);
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
      revenue = ceilDiv(Math.multiplyExact(Math.multiplyExact(carryable, price), rate), 1_000_000L);
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
    ProductionRules relation =
        relationFor(relations, unitId, modeId, trade, householdEconomy, actor);
    // ★ M-A1：运力恒为派生量（旧"有商号行 ⇒ 存量运力"的支路已退役）。
    notes.add("DERIVED_MERCHANT_CAPACITY");
    if (hasInKindPerLaborRule(relation)) {
      // ★ 具名缺口：porter 劳动量拿不到（签名没有 HouseholdLaborCommitment）⇒ 按劳动计的 porter 工资记 0，不静默。
      notes.add("PORTER_LABOR_UNKNOWN");
    }
    Obligations obligations =
        obligationsOf(
            relation,
            household,
            householdEconomy,
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
        Math.multiplyExact(householdEconomy.participationAdjustedLaborMilli(), horizonDays);
    long expectedWageCost = wageEstimate.amountMilli();
    long net =
        Math.subtractExact(
            Math.subtractExact(
                Math.subtractExact(
                    Math.subtractExact(revenue, upkeep), obligations.laborWageMilli()),
                expectedWageCost),
            obligations.rentMilli());
    boolean demandCapped = requested > capacity;
    String reason =
        joinReasons(
            notes,
            obligations.notes(),
            wageEstimate.notes(),
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
        expectedWageCost,
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
   * ★★★ <b>§15.12 B：候选可用资产表</b>（自有自营 + 同格可租闲置，判据与 {@link ModeMigrationPolicy#isIdleShare} 逐字同源）——
   * "返回资产表"的<b>唯一拼写点</b>。
   *
   * <p>★ <b>为什么返回表而不是规模</b>：§15.12 B 要求"候选用与现有 {@code assetScaleOf} 同一判据的可用资产表"喂 {@code
   * probe.assetOccupancy}，而规模只是同一张表的一次 {@code min}（{@link #assetScaleOf(IndustryDefinitionView,
   * Map)}）。分成"先算规模、再算占用"两处算术就是两份判据 —— 本方法只做表，规模从表来。
   *
   * <p>★ <b>D-024 修复 1</b>：{@code claimedByEnterprises} 由调用方从当天工作副本 {@code enterprises}/{@code
   * units} 算出并传入，本类不从 {@code base} 重算（过期快照会把已使用份额误判成闲置）。
   */
  private static Map<AssetKind, Long> candidateUsableAssets(
      IndustryDefinitionView industry,
      HouseholdId household,
      Map<AssetShareId, OwnershipStake> shares,
      Set<AssetShareId> claimedByEnterprises) {
    ActorRef actor = HouseholdActors.of(household);
    Map<AssetKind, Long> available = new LinkedHashMap<>();
    for (OwnershipStake share : shares.values()) {
      if (!share.industry().equals(industry.id()) || share.quantity() <= 0L) {
        continue;
      }
      boolean own = share.operator().equals(actor);
      boolean idle = ModeMigrationPolicy.isIdleShare(share, claimedByEnterprises);
      if (!own && !idle) {
        continue;
      }
      available.merge(share.asset(), share.quantity(), Math::addExact);
    }
    return available;
  }

  /**
   * ★★ <b>资产表的产能规模（唯一算式）</b>：{@code min_k ⌊可用资产[k] ÷ capacityPerUnit[k]⌋}（缺键 = 0；空表 = 0）。
   *
   * <p>★ 与 {@code ProductionRuntimeEngine} 的资产通道逐字同式（E=1000 时既有的 {@code 资产规模} 读数不变）；既有 unit 的
   * 可用资产表由 {@link ProductionProcessBook#usableAssets(ProductionProcess, Map)} 给（同 industry +
   * operator 的份额合计）。
   */
  private static long assetScaleOf(
      IndustryDefinitionView industry, Map<AssetKind, Long> availableAssets) {
    long scale = Long.MAX_VALUE;
    for (Map.Entry<AssetKind, Long> required : industry.capacityPerUnit().entrySet()) {
      scale =
          Math.min(
              scale, availableAssets.getOrDefault(required.getKey(), 0L) / required.getValue());
    }
    return scale == Long.MAX_VALUE ? 0L : scale;
  }

  /**
   * ★★ <b>§15.12 B：probe 的不可行理由 → 经济侧的旧词表</b>（键序/词表稳定性）。
   *
   * <pre>
   * asset-short   → NO_ASSET:&lt;industry&gt;      （旧词，逐字保留）
   * labor-short   → NO_LABOR:&lt;household&gt;     （旧词，逐字保留）
   * plan-unknown  → NO_INDUSTRY               （旧词：该格没有可用产业定义）
   * 其余          → PROBE:&lt;production 的具名理由&gt;（locked / condition-unsatisfied:* / input-short …）
   * </pre>
   *
   * <p>★ 其余理由<b>不得</b>被折叠成 NO_INDUSTRY/SCALE_ZERO：{@code locked} 与 {@code condition-unsatisfied:*}
   * 是"这户还不够格 用这个方案"（R1 要求它们具名出现在选择链的拒绝里），与"没有产业模板"是两件事。
   */
  private static String probeRejectionReason(
      String probeReason, IndustryDefinitionView industry, HouseholdId household) {
    if ("asset-short".equals(probeReason)) {
      return "NO_ASSET:" + industry.id().value();
    }
    if ("labor-short".equals(probeReason)) {
      return "NO_LABOR:" + household.value();
    }
    if ("plan-unknown".equals(probeReason)) {
      return "NO_INDUSTRY";
    }
    return "PROBE:" + probeReason;
  }

  private static long shipCattleUpkeepOf(
      List<AssetShareId> assetSources, Map<AssetShareId, OwnershipStake> shares) {
    long upkeep = 0L;
    for (AssetShareId assetShareId : assetSources) {
      OwnershipStake share = shares.get(assetShareId);
      if (share == null || share.quantity() <= 0L) {
        continue;
      }
      if (share.asset() == AssetKind.CATTLE || share.asset() == AssetKind.SHIP) {
        upkeep =
            Math.addExact(
                upkeep, Math.multiplyExact(share.quantity(), SHIP_CATTLE_UPKEEP_PER_UNIT_MILLI));
      }
    }
    return upkeep;
  }

  /** 显式关系优先；缺则按产业 regime 推制度默认；制度未登记/拿不到 ⇒ null（调用方标 NO_RELATION，rent=0）。 */
  private static ProductionRules relationFor(
      Map<ProductionUnitId, ProductionRules> relations,
      ProductionUnitId unitId,
      ProductionModeId modeId,
      IndustryDefinitionView industry,
      HouseholdEconomy householdEconomy,
      ActorRef operator) {
    ProductionRules explicit = relations.get(unitId);
    if (explicit != null) {
      return explicit;
    }
    // ★ 制度默认的 regime 先取 mode 的默认制度（租佃 mode ⇒ tenant 规则——farm@hex 的产业 regime 是 feudal，
    //   直接按产业会错配成庄园规则）；mode 未登记（如 merchant）或该 regime 无默认表 ⇒ 退回产业 regime。
    // ★ family_farm 是桥接 mode：它的产业是复用的农场（regime=feudal），household 默认规则的
    //   CLOTH 不在农场产出表里（E14 会拒）⇒ 这一档一律用产业 regime，与 EconomyEnterpriseSettlement 同口径。
    Optional<String> modeRegime = RegimeOperators.defaultRegimeForMode(modeId);
    boolean useModeRegime =
        !DefaultProductionModes.FAMILY_FARM.equals(modeId)
            && modeRegime.isPresent()
            && RegimeRelations.registered().containsKey(modeRegime.get());
    RegimeId regime = useModeRegime ? new RegimeId(modeRegime.get()) : industry.regime();
    try {
      return RegimeRelations.defaultRelation(
          regime, unitId, industry.id(), operator, Set.of(householdEconomy.view().residence()));
    } catch (IllegalArgumentException ignored) {
      return null; // 未登记的制度（如 merchant）⇒ 不猜一套规则
    }
  }

  /**
   * 一条关系里"从本经营者净收益里扣出去"的地租/分成/工资义务（毫钱）。
   *
   * <p>★ 规则口径与 {@code ProductionSettlement} 对齐：固定租是每周期一笔；分成按池 × 率；按劳动给养按 {@link
   * SubsistenceObligation#perLaborDue}。★ 受方 == 本家户自己（同 actor / 同 household / 同 cohort）的规则是自付，
   * 直接跳过（与 E2 的 {@code normalizePayees} 同一口径）。
   */
  private static Obligations obligationsOf(
      ProductionRules relation,
      HouseholdId household,
      HouseholdEconomy householdEconomy,
      HexCoord hex,
      MarketTable market,
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
      if (isSelfPayee(rule.recipient(), relation.operator(), household, householdEconomy, hex)) {
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
                    Math.multiplyExact(due, price) / EconomyVocabulary.MILLI_PER_COMMODITY_UNIT);
          }
        }
        case FIXED_MONEY_WAGE -> {
          // ★★★ D-032 D3 §2.2.1/§5.1（W1）：运行时不得回读旧 FIXED_MONEY_WAGE.fixedAmount。
          //   工资义务改由 MONEY_WAGE 角色的 default/posting 报价逐角色进入 expectedWageCostMilli。
          notes.add("LEGACY_MONEY_WAGE_IGNORED");
        }
        case OUTPUT_SHARE -> {
          CommodityId commodity = rule.commodity().orElse(null);
          boolean perCommodity =
              commodity != null
                  && (!revenueByCommodity.isEmpty() || !inputCostByCommodity.isEmpty());
          long gross = perCommodity ? revenueByCommodity.getOrDefault(commodity, 0L) : totalRevenue;
          long input =
              perCommodity ? inputCostByCommodity.getOrDefault(commodity, 0L) : totalInputCost;
          long pool = rule.pool() == Pool.GROSS_OUTPUT ? gross : Math.max(0L, gross - input);
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
                    remaining, Math.multiplyExact(state[0], (long) rule.ratePerMille()) / 1000L);
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
      long share = Math.min(remaining, Math.multiplyExact(state[0], state[2]) / 1000L);
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

  // ── D-032 D3 §5.1（W1）：工资前置估值 / payer 地点预检 ───────────────────────────────────────

  /**
   * ★★★ <b>一个 MONEY_WAGE 角色的报价与目标劳动</b>（D-032 D3 §2.1/§5.1）。
   *
   * <p>量纲：{@code wagePerLaborMilli} = 毫货币/小时；{@code targetLaborMilliPerDay} = 毫小时/日。 ★ 0 报价合法（U18），
   * 缺报价/缺目标用 {@link WageTermsResult#rejection()} 具名，绝不用 0 冒充。
   */
  private record WageTerms(long wagePerLaborMilli, long targetLaborMilliPerDay) {
    private WageTerms {
      if (wagePerLaborMilli < 0L) {
        throw new IllegalArgumentException(
            "WageTerms.wagePerLaborMilli 不得为负（0 是合法报价）: " + wagePerLaborMilli);
      }
      if (targetLaborMilliPerDay < 0L) {
        throw new IllegalArgumentException(
            "WageTerms.targetLaborMilliPerDay 不得为负: " + targetLaborMilliPerDay);
      }
    }

    private boolean zeroQuote() {
      return wagePerLaborMilli == 0L;
    }
  }

  /**
   * 报价读取结论：成功给 {@link WageTerms}，失败给稳定拒绝词（{@code wage-quote-missing:*}/{@code
   * wage-target-missing:*}）。
   */
  private record WageTermsResult(Optional<WageTerms> terms, Optional<String> rejection) {
    private WageTermsResult {
      Objects.requireNonNull(terms, "terms");
      Objects.requireNonNull(rejection, "rejection");
    }

    private static WageTermsResult ok(WageTerms terms) {
      return new WageTermsResult(Optional.of(terms), Optional.empty());
    }

    private static WageTermsResult rejected(String reason) {
      return new WageTermsResult(Optional.empty(), Optional.of(reason));
    }
  }

  /**
   * ★★★ <b>W2a 工资读口的 W1 只读组合</b>（D-032 D3 §2.2.1/§2.3/§5.1）。
   *
   * <p>★ 两类来源严格分开：实例存在 ⇒ 只读 {@code productionWagePostings}（执行期唯一权威）；实例存在前 ⇒ 只读 {@code
   * productionWageDefaults}（候选级默认）。**不互相回退**。
   *
   * <p>★ <b>为什么同时持有 posting 表</b>：W2a 的 {@link ProductionWageBook} 暴露了 quote 与 default，但 posting 的
   * {@code targetLaborMilliPerDay} 尚无独立读口；W1 需要目标劳动才能算周期工资成本。这里只读同一份冻结视图里的 posting
   * 目标，不建立第二份报价权威、不写状态。
   */
  private static final class WageTermsSource {

    private final ProductionWageBook book;
    private final Map<WagePostingKey, ProductionWagePosting> postings;

    private WageTermsSource(
        ProductionWageBook book, Map<WagePostingKey, ProductionWagePosting> postings) {
      this.book = Objects.requireNonNull(book, "book");
      this.postings = Objects.requireNonNull(postings, "postings");
    }

    private static WageTermsSource of(EconomyDayView view) {
      Objects.requireNonNull(view, "view");
      // ★ 与 ProductionWageBook 同一 tick 的冻结：报价与目标必须来自同一份只读快照。
      Map<WagePostingKey, ProductionWagePosting> postings =
          Collections.unmodifiableMap(new LinkedHashMap<>(view.productionWagePostings()));
      return new WageTermsSource(new ProductionWageBook(view), postings);
    }

    private WageTermsResult lookup(
        IndustryDefinitionView plan,
        ProductionRoleId role,
        ProductionProcess existingUnit,
        MarketZoneId zone) {
      if (existingUnit != null) {
        Optional<ProductionWageQuote> quote = book.quoteFor(existingUnit.id(), role);
        if (quote.isEmpty()) {
          return WageTermsResult.rejected("wage-quote-missing:" + role.value());
        }
        ProductionWagePosting posting = postings.get(new WagePostingKey(existingUnit.id(), role));
        if (posting == null) {
          // 报价存在但目标读不到：具名缺目标，不把"缺"当 0。
          return WageTermsResult.rejected("wage-target-missing:" + role.value());
        }
        return WageTermsResult.ok(
            new WageTerms(quote.get().wagePerLaborMilli(), posting.targetLaborMilliPerDay()));
      }
      if (zone == null) {
        return WageTermsResult.rejected("wage-quote-missing:" + role.value());
      }
      Optional<ProductionWageDefault> defaults = book.defaultFor(plan.id(), role, zone);
      if (defaults.isEmpty()) {
        return WageTermsResult.rejected("wage-quote-missing:" + role.value());
      }
      ProductionWageDefault value = defaults.get();
      return WageTermsResult.ok(
          new WageTerms(value.initialWagePerLaborMilli(), value.initialTargetLaborMilliPerDay()));
    }
  }

  /** 工资估值结论：金额（毫货币/周期）+ 具名拒绝 + 读数标记（如 {@code WAGE_QUOTE_ZERO}）。 */
  private record WageEstimate(long amountMilli, Optional<String> rejection, List<String> notes) {
    private WageEstimate {
      if (amountMilli < 0L) {
        throw new IllegalArgumentException("WageEstimate.amountMilli 不得为负: " + amountMilli);
      }
      Objects.requireNonNull(rejection, "rejection");
      Objects.requireNonNull(notes, "notes");
      notes = List.copyOf(notes);
    }

    private static WageEstimate none() {
      return new WageEstimate(0L, Optional.empty(), List.of());
    }

    private static WageEstimate rejected(String reason) {
      return new WageEstimate(0L, Optional.of(reason), List.of());
    }
  }

  /** 方案内的 MONEY_WAGE 角色，按稳定角色 id 升序（D3 E10；不靠定义表插入序）。 */
  private static List<ProductionRoleDefinition> moneyWageRoles(IndustryDefinitionView plan) {
    List<ProductionRoleDefinition> roles = new ArrayList<>();
    for (ProductionRoleDefinition role : plan.roles()) {
      if (role.payment() instanceof RolePayment.MoneyWage) {
        roles.add(role);
      }
    }
    roles.sort(Comparator.comparing(role -> role.id().value()));
    return roles;
  }

  /** 候选格的显式地点（与 {@code ProductionProcess.location} 同形：{@code <q>_<r>}）。 */
  private static ProductionLocation locationOfHex(HexCoord hex) {
    return new ProductionLocation(IndustryHexKeys.hexKey(hex.q(), hex.r()));
  }

  /**
   * ★ 把实例的显式地点读回 {@link HexCoord}（只服务"该实例所在市场区"的只读派生）。
   *
   * <p>★ fail-closed：不是规范 {@code <q>_<r>} 形状 ⇒ {@code null}（调用方按"缺市场区"具名，不猜一个格子）。
   */
  private static HexCoord hexOfLocation(ProductionLocation location) {
    if (location == null || !location.isSpecified()) {
      return null;
    }
    String value = location.value();
    int separator = value.indexOf('_');
    if (separator <= 0
        || separator == value.length() - 1
        || value.indexOf('_', separator + 1) >= 0) {
      return null;
    }
    try {
      return new HexCoord(
          Integer.parseInt(value.substring(0, separator)),
          Integer.parseInt(value.substring(separator + 1)));
    } catch (NumberFormatException invalid) {
      return null;
    }
  }

  /**
   * ★★★ <b>当前 revision 中该 payer 的全部已有 settlement location</b>（D-032 D3 §6.9.1/F-01）。
   *
   * <p>汇总口径（只读、<b>不扫账户</b>）：
   *
   * <ol>
   *   <li>{@code operator == payer} 的全部 unit 的 {@link ProductionProcess#location()}；
   *   <li>{@code productionRosters} 的 organizer == payer 的 unit 地点（unit 不在工作副本时退回方案地点）；
   *   <li>{@code productionWagePostings} 指向的、由该 payer 经营的 unit 地点。
   * </ol>
   *
   * <p>★ 本方法只返回"已有"；候选地点由调用方另加，再交 {@link WagePayerLocationPrecheck#standard()}。禁止调用方只传候选单点。
   */
  private static Set<ProductionLocation> existingSettlementLocations(
      EconomyDayView base, Map<ProductionUnitId, ProductionProcess> units, ActorRef payer) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(payer, "payer");
    LinkedHashSet<ProductionLocation> locations = new LinkedHashSet<>();
    List<ProductionUnitId> unitIds = new ArrayList<>(units.keySet());
    unitIds.sort(Comparator.comparing(ProductionUnitId::value));
    for (ProductionUnitId unitId : unitIds) {
      ProductionProcess unit = units.get(unitId);
      if (unit != null && unit.operator().equals(payer)) {
        locations.add(unit.location());
      }
    }
    List<ProductionUnitId> rosterIds = new ArrayList<>(base.productionRosters().keySet());
    rosterIds.sort(Comparator.comparing(ProductionUnitId::value));
    for (ProductionUnitId unitId : rosterIds) {
      ProductionRoster roster = base.productionRosters().get(unitId);
      if (roster == null || !HouseholdActors.of(roster.organizerHousehold()).equals(payer)) {
        continue;
      }
      ProductionProcess unit = units.get(unitId);
      if (unit != null) {
        locations.add(unit.location());
      } else {
        ProductionLocation.ofPlan(roster.plan()).ifPresent(locations::add);
      }
    }
    List<WagePostingKey> postingKeys = new ArrayList<>(base.productionWagePostings().keySet());
    postingKeys.sort(Comparator.comparing(WagePostingKey::toString));
    for (WagePostingKey key : postingKeys) {
      ProductionProcess unit = units.get(key.unit());
      if (unit != null && unit.operator().equals(payer)) {
        locations.add(unit.location());
      }
    }
    return Collections.unmodifiableSet(locations);
  }

  /**
   * ★★ <b>W1/W2b/W3 共享的"已有 settlement location"只读构造入口</b>（D-032 D3 §6.9.1/F-01）。
   *
   * <p>{@code rosterIncomes} 的完整重载与后续 W2b/W3 装配点都应经本方法构造 {@code existingSettlementLocations}，
   * 而不是自己只传候选 unit 的地点。口径见 {@link #existingSettlementLocations(EconomyDayView, Map, ActorRef)}。
   */
  public static Set<ProductionLocation> existingSettlementLocationsOf(
      EconomyDayView base, Map<ProductionUnitId, ProductionProcess> units, ActorRef payer) {
    return existingSettlementLocations(base, units, payer);
  }

  /**
   * ★★★ <b>W1 雇主候选：全部 MONEY_WAGE 角色的周期预计工资成本</b>（D-032 D3 §5.1/A1）。
   *
   * <pre>
   * expectedWageCostMilli = Σ_role floor(quote.wagePerLaborMilli × targetLaborMilliPerDay × cycleDays / 1000)
   * </pre>
   *
   * <p>★ 先做 payer 级地点预检（existing ∪ candidate）；&gt;1 settlement location ⇒ {@code
   * wage-payer-location-unsupported}，零成本/零净额、不调用任何库存 lookup。★ 缺 quote/target ⇒ 具名拒绝；显式 0 则成本 0 但保留
   * {@code WAGE_QUOTE_ZERO} 读数。★ 整数精确、向下取整；溢出 ⇒ {@code wage-quote-overflow} fail-closed。
   */
  private static WageEstimate estimateEmployerWageCost(
      EconomyDayView base,
      IndustryDefinitionView plan,
      ProductionProcess existingUnit,
      HexCoord candidateHex,
      ActorRef payer,
      Map<ProductionUnitId, ProductionProcess> units,
      long cycleDays,
      WageTermsSource source) {
    List<ProductionRoleDefinition> moneyRoles = moneyWageRoles(plan);
    if (moneyRoles.isEmpty()) {
      return WageEstimate.none();
    }
    ProductionLocation candidateLocation =
        existingUnit == null ? locationOfHex(candidateHex) : existingUnit.location();
    WagePayerLocationSnapshot snapshot =
        new WagePayerLocationSnapshot(
            payer, existingSettlementLocations(base, units, payer), Set.of(candidateLocation));
    if (WagePayerLocationPrecheck.standard().evaluate(snapshot)
        == WagePayerLocationDecision.MULTIPLE_LOCATIONS) {
      return WageEstimate.rejected("wage-payer-location-unsupported");
    }
    HexCoord zoneHex = existingUnit == null ? candidateHex : hexOfLocation(existingUnit.location());
    MarketZoneId zone =
        zoneHex == null ? null : MarketTables.marketIdAt(base, zoneHex).orElse(null);
    long total = 0L;
    List<String> notes = new ArrayList<>();
    for (ProductionRoleDefinition role : moneyRoles) {
      WageTermsResult terms = source.lookup(plan, role.id(), existingUnit, zone);
      if (terms.rejection().isPresent()) {
        return WageEstimate.rejected(terms.rejection().get());
      }
      WageTerms value = terms.terms().orElseThrow();
      long amount;
      try {
        amount =
            wageAmountMilli(value.wagePerLaborMilli(), value.targetLaborMilliPerDay(), cycleDays);
        total = Math.addExact(total, amount);
      } catch (ArithmeticException overflow) {
        return WageEstimate.rejected("wage-quote-overflow");
      }
      if (value.zeroQuote()) {
        notes.add("WAGE_QUOTE_ZERO");
      }
    }
    notes.add("WAGE_COST_INCLUDED");
    return new WageEstimate(total, Optional.empty(), notes);
  }

  /**
   * ★★★ <b>W1 雇工候选：MONEY_WAGE worker 的周期预计应收</b>（D-032 D3 §5.2/A1）。
   *
   * <pre>
   * expectedWorkableLaborMilliPerCycle
   *   = min(本户每日可分配劳动, targetLaborMilliPerDay, 该角色在共享 unit cap 下的日容量) × cycleDays
   * wageIncomeMilli = floor(quote.wagePerLaborMilli × expectedWorkableLaborMilliPerCycle / 1000)
   * </pre>
   *
   * <p>★ 先做 payer（雇主）级地点预检；&gt;1 地点 ⇒ 候选 skip/具名拒绝。★ 本批不做匹配算法：多 MONEY_WAGE 角色时取稳定 id 升序第一个并具名
   * {@code WAGE_ROLE_FIRST_ONLY}，不虚构多角色同时受雇。
   */
  private static WageEstimate estimateWorkerWageIncome(
      EconomyDayView base,
      IndustryDefinitionView plan,
      Employer employer,
      ProductionProcess employerUnit,
      HexCoord candidateHex,
      HouseholdEconomy householdEconomy,
      long laborPerUnit,
      long cycleDays,
      List<ProductionRoleDefinition> moneyRoles,
      Map<ProductionUnitId, ProductionProcess> units,
      WageTermsSource source) {
    ProductionLocation candidateLocation =
        employerUnit == null ? locationOfHex(candidateHex) : employerUnit.location();
    WagePayerLocationSnapshot snapshot =
        new WagePayerLocationSnapshot(
            employer.operator(),
            existingSettlementLocations(base, units, employer.operator()),
            Set.of(candidateLocation));
    if (WagePayerLocationPrecheck.standard().evaluate(snapshot)
        == WagePayerLocationDecision.MULTIPLE_LOCATIONS) {
      return WageEstimate.rejected("wage-payer-location-unsupported");
    }
    HexCoord zoneHex = employerUnit == null ? candidateHex : hexOfLocation(employerUnit.location());
    MarketZoneId zone =
        zoneHex == null ? null : MarketTables.marketIdAt(base, zoneHex).orElse(null);
    ProductionRoleDefinition role = moneyRoles.get(0);
    WageTermsResult terms = source.lookup(plan, role.id(), employerUnit, zone);
    if (terms.rejection().isPresent()) {
      return WageEstimate.rejected(terms.rejection().get());
    }
    WageTerms value = terms.terms().orElseThrow();
    long employerCapacityPerDay =
        laborPerUnit <= 0L ? Long.MAX_VALUE : Math.multiplyExact(employer.scale(), laborPerUnit);
    long workablePerDay =
        Math.min(
            householdEconomy.participationAdjustedLaborMilli(),
            Math.min(value.targetLaborMilliPerDay(), employerCapacityPerDay));
    long amount;
    try {
      amount = wageAmountMilli(value.wagePerLaborMilli(), workablePerDay, cycleDays);
    } catch (ArithmeticException overflow) {
      return WageEstimate.rejected("wage-quote-overflow");
    }
    List<String> notes = new ArrayList<>();
    if (value.zeroQuote()) {
      notes.add("WAGE_QUOTE_ZERO");
    }
    notes.add("WAGE_INCOME_ESTIMATE");
    if (moneyRoles.size() > 1) {
      notes.add("WAGE_ROLE_FIRST_ONLY");
    }
    return new WageEstimate(amount, Optional.empty(), notes);
  }

  /**
   * ★ 精确周期工资：{@code floor(quote × targetLaborMilliPerDay × cycleDays / 1000)}。
   *
   * <p>★ 用 {@link BigInteger} 做乘加（D3 §2.4 的精确乘加口径），避免中间溢出；结果超出 {@code long} 抛 {@link
   * ArithmeticException}（调用方折 {@code wage-quote-overflow}）。
   */
  private static long wageAmountMilli(
      long wagePerLaborMilli, long laborMilliPerDay, long cycleDays) {
    BigInteger numerator =
        BigInteger.valueOf(wagePerLaborMilli)
            .multiply(BigInteger.valueOf(laborMilliPerDay))
            .multiply(BigInteger.valueOf(cycleDays));
    return numerator.divide(BigInteger.valueOf(1000L)).longValueExact();
  }

  // ── 位置 / 产业 / 雇主 ────────────────────────────────────────────────────────────────────

  private static List<ProductionRole> producingPositions(ClassStructure structure) {
    List<ProductionRole> positions = new ArrayList<>();
    for (ProductionRole position : structure.positions().values()) {
      if (position.laborRole() != ProductionRole.LaborRole.NONE
          && position.surplusRole() != ProductionRole.SurplusRole.DEPENDENT) {
        positions.add(position);
      }
    }
    positions.sort(Comparator.comparing(position -> position.id().value()));
    return positions;
  }

  /**
   * 目标位置：先取家户当前位置（若它属于本 mode），否则同 relationToMeans/surplusRole 的第一个可生产位置，再否则 id 升序第一个 —— 与 {@code
   * ModeMigrationSettlement.pickTargetPosition} 同一口径（本类不另立一套）。
   */
  private static ProductionRole currentPositionOf(EconomyDayView base, HouseholdId household) {
    var classMembership = base.classStandings().get(household);
    return classMembership == null
        ? null
        : base.classPositions().get(classMembership.currentPositionId());
  }

  private static ProductionRole choosePosition(
      EconomyDayView base,
      HouseholdId household,
      ProductionModeId modeId,
      List<ProductionRole> producing) {
    ProductionRole current = currentPositionOf(base, household);
    if (current != null && modeId.equals(current.modeId()) && producing.contains(current)) {
      return current;
    }
    if (current != null) {
      for (ProductionRole position : producing) {
        if (position.relationToMeans() == current.relationToMeans()
            && position.surplusRole() == current.surplusRole()) {
          return position;
        }
      }
    }
    return producing.get(0);
  }

  /**
   * ★★ <b>W9：位置解析的唯一分叉点</b> —— 指定了 {@code positionOrNull} 且它确实是该 mode 的<b>可生产位置</b> ⇒ 用它
   * （"按库存能支撑的最高档"那条路）；否则退回 {@link #choosePosition} 的既有口径（逐值不变）。
   */
  private static ProductionRole resolvePosition(
      EconomyDayView base,
      HouseholdId household,
      ProductionModeId modeId,
      List<ProductionRole> producing,
      ClassPositionId positionOrNull) {
    if (positionOrNull != null) {
      for (ProductionRole candidate : producing) {
        if (candidate.id().equals(positionOrNull)) {
          return candidate;
        }
      }
    }
    return choosePosition(base, household, modeId, producing);
  }

  private static boolean isWagePosition(ProductionRole position) {
    if (position.surplusRole() == ProductionRole.SurplusRole.WAGE_EARNER) {
      return true;
    }
    return position.relationToMeans() == ProductionRole.RelationToMeans.DIRECT_LABORER
        && position.surplusRole() != ProductionRole.SurplusRole.SELF_SUBSISTENCE;
  }

  private static boolean isMerchantRegime(IndustryDefinitionView industry) {
    return industry != null && RegimeOperators.MERCHANT.equals(industry.regime().value());
  }

  private static IndustryDefinitionView resolveIndustry(
      EconomyDayView base, HexCoord hex, ProductionModeId modeId) {
    for (IndustryId industryId : ModeMigrationPolicy.industriesForMode(base, hex, modeId)) {
      IndustryDefinitionView industry = base.industries().get(industryId);
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
   * 本 mode 在本格现有的最大雇主：<b>只读当天工作副本 {@code units}</b>，不再读 {@code
   * base.productionOrganizations()}（周期关账时 base 是本周期开始时的 revision，enterprise.modeId 是过期读数）。
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
   * <p>规模 = {@link ProductionProcessBook#capacityScaleOf(ProductionProcess, IndustryDefinitionView,
   * Map)}；规模 &lt; 1 的候选先跳过 （没有真实产能可雇人，且 wageProspect 也会以 scale&lt;1 判 NO_EMPLOYER）；同秩同规模按 {@link
   * ProductionUnitId#value()} 升序取第一个（确定性）。
   *
   * <p>★ 已知近似（如实记）：同一 farm 产业下 wage/family 两种 unit 的产业 regime 都是 feudal ⇒ target=wage_farm 时
   * family_farm 的 unit 也落第 1 档（"同产业即同一劳动市场"）；mode 精确到人的劳动配额/关系结算留给后续阶段。
   */
  private static Employer employerOf(
      ProductionModeId modeId,
      IndustryDefinitionView industry,
      HexCoord hex,
      HouseholdId household,
      Map<ProductionUnitId, ProductionProcess> units,
      AssetBudgetBook.AssetBudget assetBudget) {
    ActorRef self = HouseholdActors.of(household);
    Employer best = null;
    int bestRank = -1;
    List<ProductionUnitId> unitIds = new ArrayList<>(units.keySet());
    unitIds.sort(Comparator.comparing(ProductionUnitId::value));
    for (ProductionUnitId unitId : unitIds) {
      ProductionProcess unit = units.get(unitId);
      if (unit == null || !unit.industry().equals(industry.id()) || unit.operator().equals(self)) {
        continue;
      }
      HexCoord unitHex = industryHex(unit.industry());
      if (unitHex == null || !unitHex.equals(hex)) {
        continue;
      }
      int rank = employerModeRank(unit, modeId, industry);
      if (assetBudget.rejectionOf(unitId).isPresent()) {
        continue; // ★★ R2：共享 scope 的 unit 不是可用雇主。
      }
      long scale = ProductionProcessBook.capacityScaleOf(unit, industry, assetBudget);
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
   *   <li>2 = 直接命中：{@code unit.modeKey()} 等于"本 mode 组织阶段/迁移新建"的 key （{@link
   *       EconomyEnterpriseSettlement#MODE_KEY_PREFIX} + modeId.value()）；
   *   <li>1 = 产业 regime 相符：目标 mode 有默认 regime（{@link RegimeOperators#defaultRegimeForMode}）且等于该
   *       unit 所在产业的 {@code regime().value()}；
   *   <li>0 = 同产业回退：mode 未登记默认制度或制度不符 ⇒ 仍可在同产业 unit 里找工作（保持"同产业即可"语义）。
   * </ol>
   *
   * <p>★ 旧档/主 unit 的 {@code modeKey} 是产业 id（{@code farm@hex}）⇒ 落第 1 档；候选预设的 {@code
   * candidateId@version} 无法反解 modeId ⇒ 按产业 regime 落第 1/0 档（具名近似，不猜版本内容）。
   */
  private static int employerModeRank(
      ProductionProcess unit, ProductionModeId modeId, IndustryDefinitionView industry) {
    if (unit.modeKey().equals(EconomyEnterpriseSettlement.MODE_KEY_PREFIX + modeId.value())) {
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
   * <p>★ 为什么要先扫 {@code units}：播种/旧档的 unit id 可能由别的工厂/旧档原样落盘（{@code parse} 是 opaque 的）， 用 canonical
   * id 去查 {@code relations} 会漏掉真实关系、退回制度默认（那会让佃农拿到封建庄园规则）。
   */
  private static ProductionUnitId existingUnitId(
      IndustryDefinitionView industry,
      ActorRef operator,
      Map<ProductionUnitId, ProductionProcess> units) {
    ProductionUnitId canonical = ProductionUnitId.idOf(industry.id(), operator);
    if (units.containsKey(canonical)) {
      return canonical;
    }
    List<ProductionUnitId> unitIds = new ArrayList<>(units.keySet());
    unitIds.sort(Comparator.comparing(ProductionUnitId::value));
    for (ProductionUnitId unitId : unitIds) {
      ProductionProcess unit = units.get(unitId);
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
  private static long grossOutputValue(
      IndustryDefinitionView industry, long scale, MarketTable market) {
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
              total, Math.multiplyExact(Math.multiplyExact(output.getValue(), scale), price));
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

  /**
   * ★★★ <b>W9-fix（2026-10-11）：把任意"毫钱"读数折成<b>每单位劳动的缩放值</b> —— 与 {@link Prospect#netPerLaborScaled()}
   * <b>同一把尺</b>（{@link #PER_LABOR_SCALE} 百万分之一刻度）。</b>
   *
   * <pre>
   * perLaborScaled(amountMilli, laborNeedMilli) = ⌊amountMilli × PER_LABOR_SCALE ÷ laborNeedMilli⌋
   * amountMilli ≤ 0 ∨ laborNeedMilli ≤ 0 ⇒ 0（"没有这笔钱" / "没有规模" ⇒ 没有基准）
   * </pre>
   *
   * <p>★ <b>它为什么存在（唯一拼写点）</b>：转业速度算式（{@code ModeMigrationPolicy.profitGapSpeedPerMille}）需要一个
   * <b>正参考基准</b>（"这个生产方式的收益规模有多大"），而基准必须与收益差<b>同一把尺</b>才不会漂开 ⇒ 复用 {@link #scaledNetPerLabor}
   * 的那一次乘法（{@code × PER_LABOR_SCALE}），不新造第二份刻度。
   *
   * <p>★ 溢出语义：与 {@code netPerLaborScaled} 同制 —— {@code BigInteger} 中间量 + {@code longValueExact}
   * 溢出即抛（fail-closed，拒绝静默回绕）。
   */
  public static long perLaborScaled(long amountMilli, long laborNeedMilli) {
    if (amountMilli <= 0L || laborNeedMilli <= 0L) {
      return 0L;
    }
    return scaledNetPerLabor(amountMilli, laborNeedMilli);
  }

  /** {@code floor(net × PER_LABOR_SCALE / max(1, laborNeed))}；负数也向下取整（同 EnterpriseProfitBook）。 */
  private static long scaledNetPerLabor(long net, long laborNeed) {
    BigInteger numerator = BigInteger.valueOf(net).multiply(BigInteger.valueOf(PER_LABOR_SCALE));
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

  private static long maxMarketPrice(MarketTable market) {
    if (market == null) {
      return 0L;
    }
    long max = 0L;
    for (long price : market.prices().values()) {
      max = Math.max(max, price);
    }
    return max;
  }

  /**
   * ★ 本格所在区到邻接区的最大路线费率（‰）；退化拓扑/无邻区 ⇒ 0。
   *
   * <p>★ <b>商品维不参与这个上界</b>（2026-10-09 纠正）：费率只由距离/辐射/道路/城乡决定；商品差异是<b>面值维</b> （基础费，{@code
   * MarketSettlement.freightUnitMilli}），不是费率的乘数 —— 所以这里回到与本批改动前逐字相同的形状 （F
   * 批曾在这里取"全表最大系数"作为费率放大，那条路已撤销）。
   */
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
      max = Math.max(max, topology.freightPerMilleBetween(home.anchor(), other.anchor()));
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

  private static boolean isSelfPayee(
      Payee recipient,
      ActorRef operator,
      HouseholdId household,
      HouseholdEconomy householdEconomy,
      HexCoord hex) {
    if (recipient instanceof Payee.ToActor toActor) {
      return toActor.actor().equals(operator)
          || toActor.actor().equals(HouseholdActors.of(household));
    }
    if (recipient instanceof Payee.ToHousehold toHousehold) {
      return toHousehold.household().equals(household);
    }
    if (recipient instanceof Payee.ToCohort toCohort) {
      return toCohort.cohort().hex().equals(hex)
          && toCohort.cohort().residence() == householdEconomy.view().residence()
          && toCohort.cohort().stratum().equals(householdEconomy.view().stratum());
    }
    return false;
  }

  private static boolean hasInKindPerLaborRule(ProductionRules relation) {
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

  private static boolean isWorkerPayee(
      Payee recipient, HouseholdId household, HouseholdEconomy householdEconomy, HexCoord hex) {
    if (recipient instanceof Payee.ToActor toActor) {
      return toActor.actor().equals(HouseholdActors.of(household));
    }
    if (recipient instanceof Payee.ToHousehold toHousehold) {
      return toHousehold.household().equals(household);
    }
    if (recipient instanceof Payee.ToCohort toCohort) {
      return toCohort.cohort().hex().equals(hex)
          && toCohort.cohort().residence() == householdEconomy.view().residence()
          && toCohort.cohort().stratum().equals(householdEconomy.view().stratum());
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
        0L,
        false,
        false,
        reason);
  }

  /**
   * 键值非空、逐值 ≥ 0 的保序副本（返回<b>未包装</b>的 LinkedHashMap；调用方在赋值处 {@code Collections.unmodifiableMap(...)}
   * 包装 —— SpotBugs 只认赋值处看得见的包装，故这里刻意不代包）。
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
   * ★★ <b>M-A1：该家户在同格 merchant 生产方式下的生产组织</b>（有则用于取 unit/资产维护来源，没有 ⇒ 空）。
   *
   * <p>★★ <b>B2（2026-10-10）：改读"当刻组织表"</b>（{@code prospect} 的入参已从段首 {@code EconomyData} 换成 {@code
   * EconomyDayView}）—— 段内**当天**自动组织阶段刚 upsert 的 merchant 组织从此可见。这正是等价性判据要的：逐日推进时 每天看到的都是"前一天终态 +
   * 当天已发生的阶段"，一次长推进必须同源（旧读法下，段内第 N 天看到的组织集取决于段从哪里 切，是路径效应的一种）。★ 键集按 {@link
   * ProductionOrganizationId#value()} 升序 ⇒ 同输入同答案（确定性不变）。
   */
  private static ProductionEnterprise merchantEnterpriseOf(EconomyDayView base, ActorRef actor) {
    List<ProductionOrganizationId> ids = new ArrayList<>(base.productionOrganizations().keySet());
    ids.sort(Comparator.comparing(ProductionOrganizationId::value));
    for (ProductionOrganizationId organizationId : ids) {
      ProductionEnterprise enterprise = base.productionOrganizations().get(organizationId);
      if (enterprise != null
          && enterprise.organizer().equals(actor)
          && DefaultProductionModes.MERCHANT.equals(enterprise.modeId())) {
        return enterprise;
      }
    }
    return null;
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

  // ── ★★★ D-032 C2（D2 §8.1/§8.2）：名册的逐户预计所得 ─────────────────────────────────────

  /**
   * ★★★ <b>名册的一户预计所得</b>（D2 §8.2 的稳定形状）。
   *
   * <p>★ <b>负净额保留</b>（D2-I9 工程默认）：{@link #negative()} 为 true 的行<b>不</b>被过滤 —— 它只影响读数与 D3 选择，C2 不删行。
   *
   * @param productShareMilli 该户由 {@code RoleDistribution} 得到的逐商品份额（毫单位）
   * @param productShareValueMilli 产品份额按本地 bid 估值的金额（缺价商品按 0 计并记入 {@code unpricedCommodities}）
   * @param wageReceivableMilli 该户作为 MONEY_WAGE worker 的预计应收
   * @param wagePayableMilli 该户作为 organizer 的预计应付
   * @param inputBurdenMilli 该户作为 inputSupplier 的投入负担
   * @param expectedNetMilli 产品份额值 + 工资应收 − 工资应付 − 投入负担
   * @param rejection 具名拒绝（非空 ⇒ 本行不可作为可用预计；数值仍保留可审计）
   */
  public record RosterHouseholdIncome(
      ProductionUnitId unit,
      IndustryId plan,
      HouseholdId household,
      io.mosire.simos.economy.api.production.ProductionRoleId role,
      boolean organizer,
      Map<CommodityId, Long> productShareMilli,
      long productShareValueMilli,
      long wageReceivableMilli,
      long wagePayableMilli,
      long expectedWageCostMilli,
      long inputBurdenMilli,
      long expectedNetMilli,
      boolean negative,
      Set<CommodityId> unpricedCommodities,
      Set<String> wageReasons,
      Optional<String> rejection) {

    public RosterHouseholdIncome {
      Objects.requireNonNull(unit, "unit");
      Objects.requireNonNull(plan, "plan");
      Objects.requireNonNull(household, "household");
      Objects.requireNonNull(role, "role");
      productShareMilli = Collections.unmodifiableMap(new LinkedHashMap<>(productShareMilli));
      unpricedCommodities = Collections.unmodifiableSet(new LinkedHashSet<>(unpricedCommodities));
      Objects.requireNonNull(wageReasons, "wageReasons");
      List<String> orderedReasons = new ArrayList<>(new LinkedHashSet<>(wageReasons));
      orderedReasons.sort(Comparator.naturalOrder());
      wageReasons = Collections.unmodifiableSet(new LinkedHashSet<>(orderedReasons));
      rejection = Objects.requireNonNull(rejection, "rejection");
      if (wageReceivableMilli < 0L || wagePayableMilli < 0L || expectedWageCostMilli < 0L) {
        throw new IllegalArgumentException(
            "RosterHouseholdIncome 的工资读数不得为负: receivable="
                + wageReceivableMilli
                + " payable="
                + wagePayableMilli
                + " expectedWageCost="
                + expectedWageCostMilli);
      }
      if (negative != (expectedNetMilli < 0L)) {
        throw new IllegalArgumentException(
            "RosterHouseholdIncome.negative 必须与 expectedNetMilli 同号");
      }
    }
  }

  /**
   * ★★★ <b>D-032 D2 §8.1/§8.2：按名册逐户预计所得</b>。
   *
   * <p>数据流（逐条对应设计）：
   *
   * <ol>
   *   <li>名册/旧规则校验（{@code legacy-compensation-rules-present}）；
   *   <li>{@code probe}：计划级主体 = organizer；角色级资格由 production 的 execute 重查（probe 只判计划级）； 资产读口 = D4-C
   *       {@link AssetOccupancyLookup}（unavailable ⇒ 具名 {@code asset-occupancy-unavailable}，
   *       <b>不</b>回退整池扫描）；
   *   <li>毛产 → net 只走 {@link ProductionOutputNetting}（FEED + DEPRECIATION）；
   *   <li>{@code RoleDistribution.allocate} 用 <b>planned</b> labor / rights / weights 切角色池与角色内份额；
   *       产品份额不以劳动为条件（地主 plannedLabor=0 仍拿定义份额）；
   *   <li>逐户：产品份额值（本地 bid）+ 工资应收 − 工资应付 − 投入负担；负净额保留。
   * </ol>
   *
   * <p>★★ <b>C2 边界</b>：工资报价读口默认永远 empty ⇒ 含 {@code MONEY_WAGE} 角色的名册得到 {@code
   * wage-quote-missing:<role>}（不得按 0）；本方法不支付、不落账。
   *
   * <p>★ <b>纯读</b>：只调用 {@code probe} 与只读 lookup，不写任何状态。
   */
  public static List<RosterHouseholdIncome> rosterIncomes(
      IndustryDefinitionView plan,
      ProductionProcess unit,
      ProductionRoster roster,
      Map<AssetShareId, OwnershipStake> assetShares,
      AccountSession accounts,
      Map<ProductionUnitId, ProductionRules> relations,
      MarketTable market,
      ProductionWageQuoteLookup wageQuoteLookup,
      Map<HouseholdId, ExternalAttributeFacts> participantAttributes,
      AssetOccupancyLookup assetOccupancyLookup,
      io.mosire.simos.util.state.RevisionId expectedRevision,
      long day,
      ProductionRuntime runtime) {
    return rosterIncomes(
        plan,
        unit,
        roster,
        assetShares,
        accounts,
        relations,
        market,
        wageQuoteLookup,
        participantAttributes,
        assetOccupancyLookup,
        expectedRevision,
        Map.of(),
        Set.of(),
        day,
        runtime);
  }

  /**
   * ★★★ <b>W1 名册工资估值的完整重载</b>：多带 {@code posting 目标表}与 {@code 已有 settlement location}。
   *
   * <p>★ <b>为什么 posting 目标表是独立入参</b>：W2a 的 {@link ProductionWageQuoteLookup} 只暴露 quote；D3 §8.2 的
   * 组织者应付还需要 posting 的 {@code targetLaborMilliPerDay}。本重载由调用方（W4/C3 装配点）传 {@code
   * base.productionWagePostings()} 与 {@code
   * existingSettlementLocationsOf(...)}；旧签名以空表委托（fail-closed： 报价缺失时具名 {@code
   * wage-quote-missing:<role>}，不会静默按 0）。
   *
   * <p>★ <b>已有地点必须完整</b>：{@code existingSettlementLocations} 必须来自当前 revision 该 payer 的全部已有
   * unit/roster/posting 地点；不得只传候选。集合空 = 不知道已有地点（fail-open 仅限"没有工资角色"的旧调用方）。
   */
  public static List<RosterHouseholdIncome> rosterIncomes(
      IndustryDefinitionView plan,
      ProductionProcess unit,
      ProductionRoster roster,
      Map<AssetShareId, OwnershipStake> assetShares,
      AccountSession accounts,
      Map<ProductionUnitId, ProductionRules> relations,
      MarketTable market,
      ProductionWageQuoteLookup wageQuoteLookup,
      Map<HouseholdId, ExternalAttributeFacts> participantAttributes,
      AssetOccupancyLookup assetOccupancyLookup,
      io.mosire.simos.util.state.RevisionId expectedRevision,
      Map<WagePostingKey, ProductionWagePosting> wagePostings,
      Set<ProductionLocation> existingSettlementLocations,
      long day,
      ProductionRuntime runtime) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(roster, "roster");
    Objects.requireNonNull(runtime, "runtime");
    Objects.requireNonNull(wagePostings, "wagePostings");
    Objects.requireNonNull(existingSettlementLocations, "existingSettlementLocations");
    long cycleDays = Math.max(1L, plan.cycleDays());
    Map<HouseholdId, ExternalAttributeFacts> facts =
        participantAttributes == null ? Map.of() : participantAttributes;
    ProductionRules relation = relations == null ? null : relations.get(unit.id());
    String legacyRejection =
        relation != null && !relation.rules().isEmpty()
            ? "legacy-compensation-rules-present"
            : null;
    // ★ D2 §7.3：角色级资格逐户检查（计划级资格由 probe 的 organizer 主体判；execute 会再查一次）。
    Optional<String> qualificationRejection =
        roleQualificationRejection(plan, roster, runtime, facts);
    // 1) 资产占用（D4-C）：不可用 ⇒ 具名拒绝（不读成 0、不回退 usableAssets）。
    Map<AssetKind, Long> occupancy = Map.of();
    String occupancyRejection = null;
    if (assetOccupancyLookup == null || expectedRevision == null) {
      occupancyRejection = "asset-occupancy-unavailable";
    } else {
      Optional<AssetOccupancySnapshot> snapshot =
          assetOccupancyLookup.occupancyOf(unit.id(), expectedRevision, day, day + 1L);
      if (snapshot.isEmpty()) {
        occupancyRejection = "asset-occupancy-unavailable";
      } else {
        AssetOccupancySnapshot value = snapshot.get();
        if (!value.revision().equals(expectedRevision)
            || !value.unit().equals(unit.id())
            || !value.location().equals(unit.location())
            || value.fromTick() != day
            || value.toTickExclusive() != day + 1L) {
          throw new IllegalStateException(
              "asset-occupancy-snapshot-mismatch: unit=" + unit.id().value());
        }
        occupancy = ProductionProbeInputs.positiveAssets(value.allocation());
      }
    }
    // 2) 计划级 probe：主体 = organizer；劳动 = Σ planned × cycleDays。
    long plannedLaborPerDay = 0L;
    for (ProductionParticipant participant : roster.participants()) {
      plannedLaborPerDay = Math.addExact(plannedLaborPerDay, participant.plannedLaborMilliPerDay());
    }
    HouseholdId organizer = roster.organizerHousehold();
    HouseholdProductionConditions conditions =
        new HouseholdProductionConditions(
            organizer,
            Math.multiplyExact(plannedLaborPerDay, cycleDays),
            occupancy,
            ProductionProbeInputs.inputsOf(
                accounts == null ? Map.of() : accounts.householdGoods().get(organizer), plan),
            ProductionProbeInputs.masteryOf(runtime, organizer, plan),
            ProductionProbeInputs.experienceOf(runtime, organizer, plan));
    ProductionProbeResult probe =
        runtime.probe(
            new ProductionProbeRequest(
                plan.id(),
                conditions,
                occupancy,
                runtime.efficiencyOf(unit.id()),
                facts.getOrDefault(organizer, ExternalAttributeFacts.EMPTY)));
    String probeRejection =
        !probe.feasible()
            ? "PROBE:" + probe.reason()
            : probe.scale() <= 0L ? "PROBE:SCALE_ZERO" : null;
    String rejection =
        legacyRejection != null
            ? legacyRejection
            : qualificationRejection.orElse(
                occupancyRejection != null ? occupancyRejection : probeRejection);
    // 3) 毛产 → net（唯一拼写点）。
    Map<CommodityId, Long> netPools = new LinkedHashMap<>();
    if (rejection == null) {
      for (Map.Entry<CommodityId, ProductionOutputNetting.Netted> entry :
          ProductionOutputNetting.netAll(probe.expectedOutput()).entrySet()) {
        netPools.put(entry.getKey(), entry.getValue().net());
      }
    }
    // 4) 角色两层分配（planned labor / rights / weights）。
    Map<io.mosire.simos.production.contract.ProductionParticipantKey, Map<CommodityId, Long>>
        sharesByParticipant = new LinkedHashMap<>();
    Map<CommodityId, Long> unallocated = new LinkedHashMap<>();
    Map<io.mosire.simos.production.contract.ProductionParticipantKey, Long> plannedByParticipant =
        new LinkedHashMap<>();
    Map<io.mosire.simos.production.contract.ProductionParticipantKey, Long> rightsWeights =
        new LinkedHashMap<>();
    List<io.mosire.simos.production.contract.RoleDistributionParticipant> distributionParticipants =
        new ArrayList<>();
    for (ProductionParticipant participant : roster.participants()) {
      io.mosire.simos.production.contract.ProductionParticipantKey key =
          new io.mosire.simos.production.contract.ProductionParticipantKey(
              participant.household(), participant.role());
      distributionParticipants.add(
          new io.mosire.simos.production.contract.RoleDistributionParticipant(
              participant.household(), participant.role(), participant.configuredWeight()));
      plannedByParticipant.put(
          key, Math.multiplyExact(participant.plannedLaborMilliPerDay(), cycleDays));
      long rights = 0L;
      for (AssetShareId shareId : participant.rightsStakes()) {
        OwnershipStake stake = assetShares == null ? null : assetShares.get(shareId);
        if (stake == null) {
          throw new IllegalStateException(
              "BY_RIGHTS 引用的资产份额不存在: unit=" + unit.id().value() + " share=" + shareId.value());
        }
        rights = Math.addExact(rights, stake.quantity());
      }
      if (rights > 0L) {
        rightsWeights.put(key, rights);
      }
    }
    if (rejection == null && !netPools.isEmpty()) {
      io.mosire.simos.production.contract.RoleDistributionResult distribution =
          io.mosire.simos.production.contract.RoleDistribution.allocate(
              new io.mosire.simos.production.contract.RoleDistributionRequest(
                  plan, distributionParticipants, plannedByParticipant, rightsWeights, netPools));
      sharesByParticipant.putAll(distribution.shares());
      unallocated.putAll(distribution.unallocated());
    }
    // 5) ★ W1：payer 级地点预检先于工资读口/估值（D3 §5.1/§6.9.1）；>1 地点 ⇒ 具名拒绝，零 lookup/零工资读数。
    List<ProductionRoleDefinition> moneyRoles = moneyWageRoles(plan);
    String wageRejection = null;
    Set<String> wageReasons = new LinkedHashSet<>();
    if (!moneyRoles.isEmpty()) {
      ActorRef payer = HouseholdActors.of(roster.organizerHousehold());
      WagePayerLocationSnapshot snapshot =
          new WagePayerLocationSnapshot(
              payer, existingSettlementLocations, Set.of(unit.location()));
      if (WagePayerLocationPrecheck.standard().evaluate(snapshot)
          == WagePayerLocationDecision.MULTIPLE_LOCATIONS) {
        wageRejection = "wage-payer-location-unsupported";
      }
    }
    // 5b) 工资报价/目标：实例已存在 ⇒ posting 是执行期唯一权威；缺 quote/缺目标具名，不按 0 静默。
    Map<io.mosire.simos.economy.api.production.ProductionRoleId, ProductionWageQuote> wageQuotes =
        new LinkedHashMap<>();
    Map<io.mosire.simos.economy.api.production.ProductionRoleId, Long> wageTargets =
        new LinkedHashMap<>();
    if (wageRejection == null) {
      for (ProductionRoleDefinition role : moneyRoles) {
        Optional<ProductionWageQuote> quote =
            wageQuoteLookup == null
                ? Optional.empty()
                : wageQuoteLookup.quoteFor(unit.id(), role.id());
        if (quote.isEmpty()) {
          if (wageRejection == null) {
            wageRejection = "wage-quote-missing:" + role.id().value();
          }
          continue;
        }
        ProductionWagePosting posting = wagePostings.get(new WagePostingKey(unit.id(), role.id()));
        if (posting == null) {
          if (wageRejection == null) {
            wageRejection = "wage-target-missing:" + role.id().value();
          }
          continue;
        }
        wageQuotes.put(role.id(), quote.get());
        wageTargets.put(role.id(), posting.targetLaborMilliPerDay());
        if (quote.get().wagePerLaborMilli() == 0L) {
          wageReasons.add("WAGE_QUOTE_ZERO");
        }
      }
    }
    if (wageRejection != null) {
      wageReasons.add(wageRejection);
    }
    // 5c) 组织者周期预计应付：Σ quote × posting.targetLaborMilliPerDay × cycleDays / 1000（D3 §5.1/A1）。
    long organizerWageCost = 0L;
    if (wageRejection == null) {
      for (Map.Entry<io.mosire.simos.economy.api.production.ProductionRoleId, ProductionWageQuote>
          wage : wageQuotes.entrySet()) {
        Long target = wageTargets.get(wage.getKey());
        if (target == null) {
          continue;
        }
        try {
          organizerWageCost =
              Math.addExact(
                  organizerWageCost,
                  wageAmountMilli(wage.getValue().wagePerLaborMilli(), target, cycleDays));
        } catch (ArithmeticException overflow) {
          organizerWageCost = 0L;
          wageRejection = "wage-quote-overflow";
          wageReasons.add(wageRejection);
          break;
        }
      }
    }
    // 6) 逐户预计所得（负净额保留）。
    Map<io.mosire.simos.economy.api.production.ProductionRoleId, Long> wageLaborByRole =
        new LinkedHashMap<>();
    for (ProductionParticipant participant : roster.participants()) {
      wageLaborByRole.merge(
          participant.role(),
          Math.multiplyExact(participant.plannedLaborMilliPerDay(), cycleDays),
          Math::addExact);
    }
    // 投入负担：只有 relation.inputSupplier 解析到某参与户时才归该户；否则归 organizer。
    Optional<HouseholdId> inputSupplierHousehold = Optional.empty();
    if (relation != null && relation.inputSupplier() instanceof Payee.ToActor toActor) {
      for (ProductionParticipant participant : roster.participants()) {
        if (toActor.actor().equals(HouseholdActors.of(participant.household()))) {
          inputSupplierHousehold = Optional.of(participant.household());
          break;
        }
      }
    }
    long inputBurden =
        rejection == null && market != null ? askValueMilli(market, probe.inputRequired()) : 0L;
    List<RosterHouseholdIncome> incomes = new ArrayList<>();
    for (ProductionParticipant participant : roster.participants()) {
      io.mosire.simos.production.contract.ProductionParticipantKey key =
          new io.mosire.simos.production.contract.ProductionParticipantKey(
              participant.household(), participant.role());
      Map<CommodityId, Long> share = sharesByParticipant.getOrDefault(key, Map.of());
      long productValue = 0L;
      Set<CommodityId> unpriced = new LinkedHashSet<>();
      if (market != null) {
        for (Map.Entry<CommodityId, Long> line : share.entrySet()) {
          if (!market.hasPrice(line.getKey())) {
            unpriced.add(line.getKey());
            continue;
          }
          productValue =
              Math.addExact(
                  productValue,
                  Math.multiplyExact(line.getValue(), market.bidPriceOf(line.getKey()))
                      / EconomyVocabulary.MILLI_PER_COMMODITY_UNIT);
        }
      }
      long receivable = 0L;
      long payable = 0L;
      ProductionWageQuote quote = wageQuotes.get(participant.role());
      if (quote != null) {
        long labor = wageLaborByRole.getOrDefault(participant.role(), 0L);
        receivable = Math.multiplyExact(quote.wagePerLaborMilli(), labor) / 1000L;
      }
      if (participant.organizer()) {
        payable = organizerWageCost;
      }
      long participantInputBurden =
          inputSupplierHousehold.orElse(organizer).equals(participant.household())
              ? inputBurden
              : 0L;
      long net =
          Math.subtractExact(
              Math.addExact(productValue, receivable),
              Math.addExact(payable, participantInputBurden));
      String rowRejection = rejection != null ? rejection : wageRejection;
      Set<String> rowWageReasons = new LinkedHashSet<>(wageReasons);
      incomes.add(
          new RosterHouseholdIncome(
              unit.id(),
              plan.id(),
              participant.household(),
              participant.role(),
              participant.organizer(),
              share,
              productValue,
              receivable,
              payable,
              payable,
              participantInputBurden,
              net,
              net < 0L,
              unpriced,
              rowWageReasons,
              Optional.ofNullable(rowRejection)));
    }
    return Collections.unmodifiableList(incomes);
  }

  /**
   * ★ D2 §7.3：角色级资格逐户检查（`roles[].conditions`）。
   *
   * <p>★ 主体 = 该角色每个参与户；{@code MinimumExperience} 读 production 经验（冻结条件优先）， {@code ExternalAttribute}
   * 读该户属性事实；缺事实/经验不足 ⇒ 具名 {@code role-qualification:<role>:<household>:<reason>}。
   */
  private static Optional<String> roleQualificationRejection(
      IndustryDefinitionView plan,
      ProductionRoster roster,
      ProductionRuntime runtime,
      Map<HouseholdId, ExternalAttributeFacts> facts) {
    for (ProductionParticipant participant : roster.participants()) {
      io.mosire.simos.production.contract.ProductionRoleDefinition role =
          plan.roles().stream()
              .filter(candidate -> candidate.id().equals(participant.role()))
              .findFirst()
              .orElse(null);
      if (role == null) {
        return Optional.of("role-unknown:" + participant.role().value());
      }
      for (io.mosire.simos.production.contract.ProductionAvailabilityCondition condition :
          role.conditions()) {
        if (condition
            instanceof
            io.mosire.simos.production.contract.ProductionAvailabilityCondition.MinimumExperience
                minimum) {
          long experience =
              runtime.currentCondition().isPresent()
                  ? runtime
                      .currentCondition()
                      .get()
                      .experienceOf(participant.household(), minimum.track())
                  : runtime.experienceOf(participant.household(), minimum.track());
          if (experience < minimum.minimumExperience()) {
            return Optional.of(
                "role-qualification:"
                    + participant.role().value()
                    + ":"
                    + participant.household().value()
                    + ":"
                    + condition.describe());
          }
        } else {
          ExternalAttributeFacts participantFacts =
              facts.getOrDefault(participant.household(), ExternalAttributeFacts.EMPTY);
          ExternalAttributeFacts.Verdict verdict = participantFacts.evaluate(condition);
          if (!verdict.satisfied()) {
            return Optional.of(
                "role-qualification:"
                    + participant.role().value()
                    + ":"
                    + participant.household().value()
                    + ":"
                    + verdict.reason());
          }
        }
      }
    }
    return Optional.empty();
  }

  /** 数量 × 本地 bid ÷ 1000（毫商品 → 毫钱）；缺价/0 价 ⇒ 0。与 {@code productionProspect} 同一口径。 */
  private static long bidValueMilli(MarketTable market, Map<CommodityId, Long> quantities) {
    if (market == null) {
      return 0L;
    }
    long total = 0L;
    for (Map.Entry<CommodityId, Long> entry : quantities.entrySet()) {
      if (!market.hasPrice(entry.getKey())) {
        continue;
      }
      total =
          Math.addExact(
              total,
              Math.multiplyExact(entry.getValue(), market.bidPriceOf(entry.getKey()))
                  / EconomyVocabulary.MILLI_PER_COMMODITY_UNIT);
    }
    return total;
  }

  /** 逐商品 bid 估值（唯一口径）：缺价记入 {@code unpriced}、0 价按 0；只返回 >0 行。 */
  private static Map<CommodityId, Long> bidValuesByCommodity(
      MarketTable market, Map<CommodityId, Long> quantities, Set<CommodityId> unpriced) {
    Map<CommodityId, Long> values = new LinkedHashMap<>();
    if (market == null) {
      unpriced.addAll(quantities.keySet());
      return values;
    }
    for (Map.Entry<CommodityId, Long> entry : quantities.entrySet()) {
      if (entry.getValue() <= 0L) {
        continue;
      }
      if (!market.hasPrice(entry.getKey())) {
        unpriced.add(entry.getKey());
        continue;
      }
      long price = market.bidPriceOf(entry.getKey());
      if (price <= 0L) {
        continue;
      }
      values.put(
          entry.getKey(),
          Math.multiplyExact(entry.getValue(), price) / EconomyVocabulary.MILLI_PER_COMMODITY_UNIT);
    }
    return values;
  }

  /** 逐商品 ask 估值（唯一口径）：缺价记入 {@code unpriced}、0 价按 0；只返回 >0 行。 */
  private static Map<CommodityId, Long> askValuesByCommodity(
      MarketTable market, Map<CommodityId, Long> quantities, Set<CommodityId> unpriced) {
    Map<CommodityId, Long> values = new LinkedHashMap<>();
    if (market == null) {
      unpriced.addAll(quantities.keySet());
      return values;
    }
    for (Map.Entry<CommodityId, Long> entry : quantities.entrySet()) {
      if (entry.getValue() <= 0L) {
        continue;
      }
      if (!market.hasPrice(entry.getKey())) {
        unpriced.add(entry.getKey());
        continue;
      }
      long price = market.askPriceOf(entry.getKey());
      if (price <= 0L) {
        continue;
      }
      values.put(
          entry.getKey(),
          Math.multiplyExact(entry.getValue(), price) / EconomyVocabulary.MILLI_PER_COMMODITY_UNIT);
    }
    return values;
  }

  private static long sumValues(Map<CommodityId, Long> values) {
    long total = 0L;
    for (long value : values.values()) {
      total = Math.addExact(total, value);
    }
    return total;
  }

  /** 数量 × 本地 ask ÷ 1000（投入负担的唯一估值口径）。 */
  private static long askValueMilli(MarketTable market, Map<CommodityId, Long> quantities) {
    if (market == null) {
      return 0L;
    }
    long total = 0L;
    for (Map.Entry<CommodityId, Long> entry : quantities.entrySet()) {
      if (!market.hasPrice(entry.getKey())) {
        continue;
      }
      total =
          Math.addExact(
              total,
              Math.multiplyExact(entry.getValue(), market.askPriceOf(entry.getKey()))
                  / EconomyVocabulary.MILLI_PER_COMMODITY_UNIT);
    }
    return total;
  }
}
