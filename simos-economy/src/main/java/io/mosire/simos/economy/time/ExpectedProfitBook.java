package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.model.ProductionWageAnchor;
import io.mosire.simos.economy.model.WageAnchorKey;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.ExternalAttributeFacts;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRuntime;
import io.mosire.simos.social.api.id.HouseholdId;
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
          Collections.unmodifiableMap(
              ProspectArithmetic.copyNonNegative(plannedOutputMilli, "plannedOutputMilli"));
      selfConsumedMilli =
          Collections.unmodifiableMap(
              ProspectArithmetic.copyNonNegative(selfConsumedMilli, "selfConsumedMilli"));
      sellableMilli =
          Collections.unmodifiableMap(
              ProspectArithmetic.copyNonNegative(sellableMilli, "sellableMilli"));
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
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(shares, "shares");
    AssetOccupancyBudget assetBudget =
        ProductionProcessBook.assetOccupancyBudget(
            units,
            shares,
            base.assetOccupancies(),
            base.industries(),
            base.productionRosters(),
            day);
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
        positionOrNull,
        runtime,
        assetBudget);
  }

  /** 复用调用方已持有的同一 revision 的唯一占用预算；实现见 {@link ProductionProspectBook}。 */
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
      ProductionRuntime runtime,
      AssetOccupancyBudget assetBudget) {
    return ProductionProspectBook.prospect(
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
        positionOrNull,
        runtime,
        assetBudget);
  }

  /** 已有 settlement location 的只读构造入口；实现见 {@link WageProspectBook}。 */
  public static Set<ProductionLocation> existingSettlementLocationsOf(
      EconomyDayView base, Map<ProductionUnitId, ProductionProcess> units, ActorRef payer) {
    return WageProspectBook.existingSettlementLocationsOf(base, units, payer);
  }

  /** 每单位劳动的百万分之一刻度；实现见 {@link ProspectArithmetic}。 */
  public static long perLaborScaled(long amountMilli, long laborNeedMilli) {
    return ProspectArithmetic.perLaborScaled(amountMilli, laborNeedMilli);
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
      EconomyDayView dayView,
      Map<WageAnchorKey, ProductionWageAnchor> wageAnchors,
      Map<HouseholdId, ExternalAttributeFacts> participantAttributes,
      AssetOccupancyLookup assetOccupancyLookup,
      io.mosire.simos.util.state.RevisionId expectedRevision,
      Set<ProductionLocation> existingSettlementLocations,
      long day,
      ProductionRuntime runtime) {
    return RosterIncomeBook.rosterIncomes(
        plan,
        unit,
        roster,
        assetShares,
        accounts,
        relations,
        market,
        dayView,
        wageAnchors,
        participantAttributes,
        assetOccupancyLookup,
        expectedRevision,
        existingSettlementLocations,
        day,
        runtime);
  }
}
