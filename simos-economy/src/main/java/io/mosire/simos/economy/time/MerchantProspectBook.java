package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.ExpectedProfitBook.DEFAULT_MERCHANT_CYCLE_DAYS;
import static io.mosire.simos.economy.time.ExpectedProfitBook.SHIP_CATTLE_UPKEEP_PER_UNIT_MILLI;
import static io.mosire.simos.economy.time.ProductionProspectBook.isMerchantRegime;
import static io.mosire.simos.economy.time.ProductionProspectBook.isWagePosition;
import static io.mosire.simos.economy.time.ProductionProspectBook.resolveIndustry;
import static io.mosire.simos.economy.time.ProspectArithmetic.infeasible;
import static io.mosire.simos.economy.time.ProspectArithmetic.joinReasons;
import static io.mosire.simos.economy.time.ProspectArithmetic.obligationsOf;
import static io.mosire.simos.economy.time.ProspectArithmetic.relationFor;
import static io.mosire.simos.economy.time.ProspectArithmetic.scaledNetPerLabor;
import static io.mosire.simos.economy.time.WageProspectBook.estimateEmployerWageCost;
import static io.mosire.simos.economy.time.WageProspectBook.wageProspect;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.MerchantPolicy;
import io.mosire.simos.economy.model.MerchantTradeTemplate;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.time.ExpectedProfitBook.Prospect;
import io.mosire.simos.economy.time.ProspectArithmetic.Obligations;
import io.mosire.simos.economy.time.WageProspectBook.WageEstimate;
import io.mosire.simos.economy.time.WageProspectBook.WageTermsSource;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class MerchantProspectBook {
  private MerchantProspectBook() {}

  static String merchantInputFailureReason(
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

  static Prospect merchantProspect(
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
      AssetOccupancyBudget assetBudget,
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

  static long shipCattleUpkeepOf(
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

  static long maxMarketPrice(MarketTable market) {
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
  static long maxAdjacentLaneRate(MarketTopology topology, HexCoord hex) {
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

  static MarketRegion regionOfOrNull(MarketTopology topology, HexCoord hex) {
    try {
      return topology.regionOf(hex);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  static boolean hasInKindPerLaborRule(ProductionRules relation) {
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

  static ProductionEnterprise merchantEnterpriseOf(EconomyDayView base, ActorRef actor) {
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

  static long ceilDiv(long numerator, long denominator) {
    if (denominator <= 0L) {
      throw new IllegalArgumentException("ceilDiv 的分母必须 > 0: " + denominator);
    }
    return Math.floorDiv(numerator + denominator - 1L, denominator);
  }
}
